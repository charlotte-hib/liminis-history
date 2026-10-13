package art.liminis.drafting

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredOutputConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import art.liminis.domain.Moment

/** Thrown when Claude returns no usable draft: a refusal, a truncated answer, or no structured output. */
class DraftingException(message: String) : RuntimeException(message)

private val SYSTEM_PROMPT = """
    You draft stories for Liminis, a history app where people step into a moment (one place over a few
    years) and meet the people who lived there, from rulers to washerwomen. A story is read one short card at a
    time over a painting of the period.

    Write 6 to 9 cards:
    - SCENE cards set the scene: who governs, how ordinary people live, what is being made. Kicker like
      "Setting the scene · Edo, 1830s"; a short title; two or three sentences.
    - PERSON cards introduce someone who lived it, as they are during the moment. Set personSlug and the year.
    - LIFE cards show a typical everyday life. Set lifeId.
    - EVENT cards tell a documented event. Set eventId; the text adds context and does not repeat the event.
    Mix powerful people, artists and thinkers, and everyday lives. Order the cards so they read as one story.

    Also write a hook of under 90 characters, and the world around the moment: one sentence each for who
    governs, everyday life, arts and ideas, and meanwhile elsewhere (from the "elsewhere" sources).

    Use only facts stated in the sources. Leave out anything they do not support: fewer true cards beat more
    vague ones. Every line cites the sources it rests on, with a quote copied exactly from the source text.
    Write plain, concrete English for a general reader, in short sentences and the present tense.
""".trimIndent()

@Configuration(proxyBeanMethods = false)
class AnthropicConfiguration {

    /**
     * One client for every draft, with its connection pool, closed on shutdown. Built on the first draft, from
     * `ANTHROPIC_API_KEY`: the workbench starts without one, and only drafting needs it.
     */
    @Bean
    @Lazy
    fun anthropicClient(): AnthropicClient = AnthropicOkHttpClient.fromEnv()
}

/** Drafts a moment's story with Claude, as structured output checked against a schema. */
@Component
class StoryDrafter(@Lazy private val client: AnthropicClient, private val properties: DraftingProperties) {

    fun draft(moment: Moment, sources: List<Source>): StoryDraft {
        val params = MessageCreateParams.builder()
            .model(properties.model)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                StructuredOutputConfig.builder<StoryDraft>()
                    .format(StoryDraft::class.java)
                    // Authoring is rare and correctness matters more than cost.
                    .effort(OutputConfig.Effort.HIGH)
                    .build()
            )
            .addUserMessage(userPrompt(moment, sources))
            // If a safety classifier declines, let the API retry on its recommended fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = client.messages().create(params)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw DraftingException("Claude declined to draft ${moment.id}")
            StopReason.MAX_TOKENS -> throw DraftingException("The draft for ${moment.id} was cut off at $MAX_TOKENS tokens")
            else -> Unit
        }
        return message.content().firstNotNullOfOrNull { block -> block.text().map { it.text() }.orElse(null) }
            ?: throw DraftingException("Claude returned no draft for ${moment.id}")
    }

    private fun userPrompt(moment: Moment, sources: List<Source>) = buildString {
        appendLine("Moment: ${moment.place}, ${moment.period} (${moment.region.name}, ${moment.startYear}–${moment.endYear}).")
        appendLine("Card years must fall between ${moment.startYear} and ${moment.endYear}.")
        appendLine()
        appendLine("Sources:")
        for (source in sources) {
            appendLine("<source id=\"${source.id}\" title=\"${source.title}\">")
            appendLine(source.text)
            appendLine("</source>")
        }
    }

    private companion object {
        const val MAX_TOKENS = 16_000L
    }
}
