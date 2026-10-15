package art.liminis.drafting

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import art.liminis.curation.Citation as StoryCitation
import art.liminis.curation.ClaimRules
import art.liminis.curation.Claims
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.Origin
import art.liminis.curation.Proposal
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.domain.CardType
import art.liminis.domain.Moment
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@ConfigurationProperties("app.drafting")
data class DraftingProperties(
    val model: String,
    /** How many of the best known people alive in the moment the drafter reads about. */
    val people: Int,
    /** The model's price in US dollars per million tokens, to say what each draft cost. */
    val price: Price,
)

data class Price(val input: BigDecimal, val output: BigDecimal)

/**
 * Drafts one moment's story with Claude (`wb draft edo-1830s`, or curate's moment page) and proposes it for review as
 * the moment's story claim, flagged with anything the validator found. Nothing is published until the curator
 * approves it and publishes. Needs an Anthropic API key in the environment.
 */
@Component
class StoryDrafting(
    private val properties: DraftingProperties,
    private val moments: MomentRepository,
    private val people: PersonRepository,
    private val sources: DraftSources,
    private val drafter: StoryDrafter,
    private val claims: Claims,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Drafts [momentId]'s story, proposes it, and says what it came to and cost. */
    fun draft(momentId: String): String {
        val moment = moments.findById(momentId).orElseThrow { IllegalArgumentException("Unknown moment: $momentId") }
        val momentSources = sources.forMoment(moment)
        log.info("Drafting {} from {} sources with {}", momentId, momentSources.size, properties.model)
        val drafted = drafter.draft(moment, momentSources)
        val problems = StoryDraftValidator.problems(drafted.draft, momentSources, moment.startYear, moment.endYear)
        val story = story(moment, drafted.draft, momentSources)
        val flags = (problems + fetchedPeople(story)).map { FlagReason(Flag.ODD, it) }
        val counts = claims.propose(listOf(Proposal(story, Origin.CLAUDE, flags, articles(drafted.draft, momentSources))))

        val cost = cost(drafted)
        log.info("Drafted {} with {}: {} cards, {} problems, {} input and {} output tokens, \${}", momentId, drafted.model,
            story.cards.size, flags.size, drafted.inputTokens, drafted.outputTokens, cost)
        // A refusal can hand the draft to a fallback model, at its own price: the cost is then only a guide.
        val priced = if (drafted.model == properties.model) "" else " at ${properties.model}'s price, though ${drafted.model} wrote it"
        return "Drafted ${ClaimRules.storyId(momentId)} ($counts): ${story.cards.size} cards, ${flags.size} to look at; " +
            "%,d input and %,d output tokens, about \$%s%s".format(Locale.ROOT, drafted.inputTokens, drafted.outputTokens, cost, priced)
    }

    /** The draft as the claim's record: each line with its quotes, and cards naming people written by hand by their id. */
    private fun story(moment: Moment, draft: StoryDraft, sources: List<Source>): Story {
        val byId = sources.associateBy { it.id }
        val qids = draft.cards.mapNotNull { it.personId }
        val byHand = people.findByQidIn(qids).filter { it.id != it.qid }.associate { it.qid to it.id }

        fun line(line: DraftLine) = StoryLine(line.text, citations(line.citations, byId))
        fun name(kind: String, id: String?) = id?.let { byId["$kind:$it"]?.name }

        return Story(
            moment = moment.id,
            hook = line(draft.hook),
            world = with(draft.world) { StoryWorld(line(governs), line(everyday), line(arts), line(meanwhile)) },
            cards = draft.cards.map { card ->
                StoryCard(
                    type = card.type,
                    kicker = card.kicker,
                    title = card.title,
                    text = card.text,
                    year = card.year,
                    person = card.personId?.takeIf { card.type == CardType.PERSON }?.let { byHand[it] ?: it },
                    life = card.lifeId?.takeIf { card.type == CardType.LIFE },
                    event = card.eventId?.takeIf { card.type == CardType.EVENT },
                    label = when (card.type) {
                        CardType.PERSON -> name("person", card.personId)
                        CardType.LIFE -> name("life", card.lifeId)
                        CardType.EVENT -> name("event", card.eventId)
                        CardType.SCENE -> null
                    },
                    citations = citations(card.citations, byId),
                )
            },
        )
    }

    private fun citations(citations: List<Citation>, byId: Map<String, Source>) =
        citations.map { StoryCitation(it.sourceId, byId[it.sourceId]?.title ?: it.sourceId, it.quote) }

    private fun fetchedPeople(story: Story): List<String> = ClaimRules.fetchedPeople(story).map { (number, card) ->
        "card $number shows ${card.label ?: card.person} (${card.person}), whom the site holds once its release carries the fetched dataset"
    }

    /** The English Wikipedia articles the draft quotes. */
    private fun articles(draft: StoryDraft, sources: List<Source>): List<SourceRef> {
        val byId = sources.associateBy { it.id }
        val lines = listOf(draft.hook, draft.world.governs, draft.world.everyday, draft.world.arts, draft.world.meanwhile)
        val cited = (lines.flatMap { it.citations } + draft.cards.flatMap { it.citations }).map { it.sourceId }.distinct()
        return cited.mapNotNull { byId[it]?.article }.distinct()
            .map { SourceRef(kind = SourceKind.WIKIPEDIA, language = "en", title = it) }
    }

    /** What the draft cost at the model's price, in dollars, to the cent. */
    private fun cost(drafted: Drafted): BigDecimal =
        (properties.price.input * BigDecimal(drafted.inputTokens) + properties.price.output * BigDecimal(drafted.outputTokens))
            .divide(MILLION, 2, RoundingMode.HALF_UP)

    private companion object {
        val MILLION = BigDecimal(1_000_000)
    }
}
