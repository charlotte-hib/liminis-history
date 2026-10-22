package art.liminis.translating

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredOutputConfig
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import art.liminis.drafting.DraftingProperties
import tools.jackson.databind.json.JsonMapper

// The shape Claude must answer in: the JSON schema sent to the API is derived from these classes.

/** A text's French, by the path it was given under. */
data class DraftText(val path: String, val fr: String)

/** A record's texts in French, by the key it was given under. */
data class DraftRecord(val record: String, val texts: List<DraftText>)

data class TranslationDraft(val records: List<DraftRecord>)

/**
 * A record to translate: its key, what it is, all its texts in English for context, the paths of those to translate,
 * why the curator rejected its last French, if they did, and, for a text whose English changed since its French was
 * written, that English and French, by path: Claude updates the French rather than translating anew.
 */
data class ToTranslate(
    val record: String,
    val label: String,
    val texts: List<Pair<String, String>>,
    val paths: List<String>,
    val rejected: String? = null,
    val updates: Map<String, Before> = emptyMap(),
)

/** A text's English as it was, and the French written for it then. */
data class Before(val english: String, val french: String)

/** A batch's French, with the model that wrote it and the tokens it took. */
data class TranslatedBatch(val draft: TranslationDraft, val model: String, val inputTokens: Long, val outputTokens: Long)

/** Thrown when Claude returns no usable translation: a refusal, a truncated answer, or no structured output. */
class TranslatingException(message: String) : RuntimeException(message)

private val SYSTEM_PROMPT = """
    You translate Liminis, a history app, from English into French. Visitors step into a moment (one place over a
    few years), read its story one short card at a time over a painting of the period, and meet the people who
    lived there. The French site must read as if written in French for a general reader, not as a translation.

    Rules:
    - Translate each text given under "to translate", by its path, and nothing else. Keep the meaning and the facts:
      every name, year and number stays as it is. Add nothing, leave nothing out.
    - Keep the tone: plain, concrete, short sentences, the present tense where the English uses it.
    - Use the glossary's French for its terms, and the French names it gives for people and places.
    - Short labels stay short: an era is "Troisième République", a period "années 1870", "c. 750" is "vers 750",
      an occupation is lower case ("peintre"). A typical life starts with "Un" or "Une" as the English starts with "A".
    - French typography: the typographic apostrophe ’, « » around quotes and titles in running text where the
      English uses quotes, a non-breaking space before : ; ? !, and French capitalisation (only the first word of a
      work's title, "le Second Empire").
    - Kickers keep their " · " separator: "Setting the scene · Paris, 1870s" is "Le décor · Paris, années 1870".
    - A record with "rejected" had its last French turned down by the curator for that reason: avoid it.
    - A text under "update" changed in English since its French was written: you get the English and French as they
      were. Change the French only where the English changed (a year, a name, a clause) and keep the rest of it word
      for word: the curator chose that wording.

    The records come as JSON: each has its key, a label saying what it is, its English texts by path, and the paths
    to translate. Answer with the same keys and paths.
""".trimIndent()

/** Translates records of `data/` into French with Claude, a batch at a time, as structured output checked against a schema. */
@Component
class Translator(@Lazy private val client: AnthropicClient, private val properties: DraftingProperties, private val jsonMapper: JsonMapper) {

    fun translate(records: List<ToTranslate>, glossary: Map<String, String>): TranslatedBatch {
        val params = MessageCreateParams.builder()
            .model(properties.model)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                StructuredOutputConfig.builder<TranslationDraft>()
                    .format(TranslationDraft::class.java)
                    .effort(OutputConfig.Effort.MEDIUM)
                    .build()
            )
            .addUserMessage(userPrompt(records, glossary))
            // If a safety classifier declines, let the API retry on its recommended fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = client.messages().create(params)
        val what = records.joinToString { it.record }
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw TranslatingException("Claude declined to translate $what")
            StopReason.MAX_TOKENS -> throw TranslatingException("The translation of $what was cut off at $MAX_TOKENS tokens")
            else -> Unit
        }
        val draft = message.content().firstNotNullOfOrNull { block -> block.text().map { it.text() }.orElse(null) }
            ?: throw TranslatingException("Claude returned no translation of $what")
        val usage = message.usage()
        return TranslatedBatch(draft, message.model().asString(), usage.inputTokens(), usage.outputTokens())
    }

    /** The glossary and the records, as JSON: texts written by curators cannot pass for the prompt's structure. */
    private fun userPrompt(records: List<ToTranslate>, glossary: Map<String, String>): String = jsonMapper.writeValueAsString(
        mapOf(
            "glossary" to glossary,
            "records" to records.map { record ->
                listOfNotNull(
                    "key" to record.record,
                    "label" to record.label,
                    "english" to record.texts.toMap(),
                    "translate" to record.paths,
                    record.rejected?.let { "rejected" to it },
                    record.updates.takeIf { it.isNotEmpty() }?.let { updates ->
                        "update" to updates.mapValues { (_, before) -> mapOf("oldEnglish" to before.english, "oldFrench" to before.french) }
                    },
                ).toMap()
            },
        ),
    )

    private companion object {
        const val MAX_TOKENS = 16_000L
    }
}
