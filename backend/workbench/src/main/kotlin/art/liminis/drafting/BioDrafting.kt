package art.liminis.drafting

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredOutputConfig
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import art.liminis.curation.Bio
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.Claims
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.Origin
import art.liminis.curation.PersonFacts
import art.liminis.curation.PersonFactsMapper
import art.liminis.curation.Proposal
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.dataset.DatasetStore
import art.liminis.release.BIO_LENGTH
import art.liminis.support.NotFoundException
import art.liminis.support.dropHatnote
import java.util.Locale
import art.liminis.curation.Citation as BioCitation

// The shape Claude must answer in: the JSON schema sent to the API is derived from this class.

/** A bio and the passages of the intros it rests on. */
data class BioDraft(val text: String, val citations: List<Citation>)

/** A bio drafted, with the model that wrote it and the tokens it took. */
data class DraftedBio(val draft: BioDraft, val model: String, val inputTokens: Long, val outputTokens: Long)

private val BIO_PROMPT = """
    You write the opening of a person's panel for Liminis, a history app where people step into a moment (one place
    over a few years) and meet the people who lived there. The panel opens with a few lines on who the person was;
    the same lines stand in for them on story cards and in link previews.

    Write one to three sentences, $DRAFT_LENGTH characters at most: who they were and what they did, in their place
    and time, with the one or two things a general reader should remember them by. Plain, concrete English, in short
    sentences and the present tense. No dates of birth and death (the panel shows them), no list of honours, no
    praise the sources do not give.

    Use only facts stated in the sources. Cite the passages the bio rests on, each copied exactly from its source.
""".trimIndent()

/** Drafts a person's bio with Claude, as structured output checked against a schema. */
@Component
class BioDrafter(@Lazy private val client: AnthropicClient, private val properties: DraftingProperties) {

    fun draft(person: String, sources: List<Source>): DraftedBio {
        val params = MessageCreateParams.builder()
            .model(properties.model)
            .maxTokens(MAX_TOKENS)
            .system(BIO_PROMPT)
            .outputConfig(
                StructuredOutputConfig.builder<BioDraft>()
                    .format(BioDraft::class.java)
                    // A few sentences from two intros: the default depth, written explicitly as Opus 5.5's.
                    .effort(OutputConfig.Effort.MEDIUM)
                    .build()
            )
            .addUserMessage(userPrompt(person, sources))
            // If a safety classifier declines, let the API retry on its recommended fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = client.messages().create(params)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw DraftingException("Claude declined to draft $person's bio")
            StopReason.MAX_TOKENS -> throw DraftingException("$person's bio was cut off at $MAX_TOKENS tokens")
            else -> Unit
        }
        val draft = message.content().firstNotNullOfOrNull { block -> block.text().map { it.text() }.orElse(null) }
            ?: throw DraftingException("Claude returned no bio for $person")
        val usage = message.usage()
        return DraftedBio(draft, message.model().asString(), usage.inputTokens(), usage.outputTokens())
    }

    private fun userPrompt(person: String, sources: List<Source>) = buildString {
        appendLine("Person: $person.")
        appendLine()
        appendLine("Sources:")
        for (source in sources) {
            appendLine("<source id=\"${source.id}\" title=\"${source.title}\">")
            appendLine(source.text)
            appendLine("</source>")
        }
    }

    private companion object {
        const val MAX_TOKENS = 8_000L
    }
}

/**
 * Drafts one person's bio with Claude from their English and French Wikipedia intros (curate's person page) and
 * proposes it for review as their `BIO` claim, flagged with anything the check found: too long, or a passage not in
 * its intro. Nothing is published until the curator approves it and publishes. Needs an Anthropic API key in the
 * environment.
 */
@Component
class BioDrafting(
    private val properties: DraftingProperties,
    private val dataset: DatasetStore,
    private val factsMapper: PersonFactsMapper,
    private val reviewed: ClaimRepository,
    private val drafter: BioDrafter,
    private val claims: Claims,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The intros [qid]'s bio is drafted from, by language: 404 when they have none. */
    fun sources(qid: String): List<Source> {
        val sources = dataset.articles(qid).filter { it.language in LANGUAGES }.mapNotNull { article ->
            val text = dropHatnote(article.extract).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Source("wikipedia:${article.language}", article.title, text, article.url, article = article.title)
        }
        if (sources.isEmpty()) throw NotFoundException("$qid has no Wikipedia intro to draft a bio from")
        return sources
    }

    /** Drafts [qid]'s bio, proposes it, and says what it came to and cost. */
    fun draft(qid: String): String {
        val sources = sources(qid)
        // Who they are as the curator has them: their facts' claim, else the dataset's.
        val facts = reviewed.findById(ClaimRules.factsId(qid)).orElse(null)?.payload as PersonFacts?
            ?: dataset.people(listOf(qid)).singleOrNull()?.let(factsMapper::facts)
            ?: throw NotFoundException("Unknown person: $qid")
        val who = "${facts.label} (${facts.born}–${facts.died})${facts.occupations.firstOrNull()?.let { ", ${it.label}" } ?: ""}"
        log.info("Drafting {}'s bio from {} intros with {}", qid, sources.size, properties.model)
        val drafted = drafter.draft(who, sources)
        val byId = sources.associateBy { it.id }
        val bio = Bio(qid, drafted.draft.text.trim(), drafted.draft.citations.map { BioCitation(it.sourceId, byId[it.sourceId]?.title ?: it.sourceId, it.quote) })
        val flags = problems(drafted.draft, byId).map { FlagReason(Flag.ODD, it) }
        val cited = drafted.draft.citations.mapNotNull { byId[it.sourceId] }.distinct()
        val counts = claims.propose(listOf(Proposal(bio, Origin.CLAUDE, flags, cited.map { SourceRef(kind = SourceKind.WIKIPEDIA, language = it.id.removePrefix("wikipedia:"), title = it.title) })))

        val cost = properties.price.dollars(drafted.inputTokens, drafted.outputTokens)
        log.info("Drafted {}'s bio with {}: {} characters, {} problems, {} input and {} output tokens, \${}", qid, drafted.model,
            bio.text.length, flags.size, drafted.inputTokens, drafted.outputTokens, cost)
        // A refusal can hand the draft to a fallback model, at its own price: the cost is then only a guide.
        val priced = if (drafted.model == properties.model) "" else " at ${properties.model}'s price, though ${drafted.model} wrote it"
        return "Drafted ${ClaimRules.bioId(qid)} ($counts): ${bio.text.length} characters, ${flags.size} to look at; " +
            "%,d input and %,d output tokens, about \$%s%s".format(Locale.ROOT, drafted.inputTokens, drafted.outputTokens, cost, priced)
    }

    /** What the curator should look at: a bio too long for the site, a passage not cited or not in its intro. */
    private fun problems(draft: BioDraft, byId: Map<String, Source>): List<String> {
        val problems = mutableListOf<String>()
        if (draft.text.trim().length > BIO_LENGTH) problems += "The bio has ${draft.text.trim().length} characters, more than the site's $BIO_LENGTH: shorten it"
        if (draft.citations.isEmpty()) problems += "The bio cites no passage of the intros"
        for (citation in draft.citations) {
            val source = byId[citation.sourceId]
            when {
                source == null -> problems += "The bio cites an unknown source '${citation.sourceId}'"
                !quotes(source.text, citation.quote) -> problems += "The bio quotes text not found in '${citation.sourceId}': \"${citation.quote}\""
            }
        }
        return problems
    }

    private companion object {
        val LANGUAGES = setOf("en", "fr")
    }
}

/** Shorter than the site allows, for the model's count to have room. */
private const val DRAFT_LENGTH = 240
