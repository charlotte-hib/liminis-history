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

// The shape Claude must answer in: the JSON schema sent to the API is derived from these classes.

/** A text's French, by the path it was given under. */
data class DraftText(val path: String, val fr: String)

/** A record's texts in French, by the key it was given under. */
data class DraftRecord(val record: String, val texts: List<DraftText>)

data class TranslationDraft(val records: List<DraftRecord>)

/** A record to translate: its key, what it is, all its texts in English for context, and the paths of those to translate. */
data class ToTranslate(val record: String, val label: String, val texts: List<Pair<String, String>>, val paths: List<String>)

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
""".trimIndent()

/** Translates records of `data/` into French with Claude, a batch at a time, as structured output checked against a schema. */
@Component
class Translator(@Lazy private val client: AnthropicClient, private val properties: DraftingProperties) {

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

    private fun userPrompt(records: List<ToTranslate>, glossary: Map<String, String>) = buildString {
        if (glossary.isNotEmpty()) {
            appendLine("Glossary:")
            glossary.forEach { (en, fr) -> appendLine("- $en → $fr") }
            appendLine()
        }
        for (record in records) {
            appendLine("<record key=\"${record.record}\" label=\"${record.label}\">")
            appendLine("English:")
            record.texts.forEach { (path, en) -> appendLine("$path: $en") }
            appendLine("To translate: ${record.paths.joinToString(", ")}")
            appendLine("</record>")
        }
    }

    private companion object {
        const val MAX_TOKENS = 16_000L
    }
}
