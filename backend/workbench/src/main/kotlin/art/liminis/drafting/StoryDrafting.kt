package art.liminis.drafting

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import art.liminis.jobs.WikipediaJob
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@ConfigurationProperties("app.drafting")
data class DraftingProperties(val model: String, val outputDir: String)

/** What a curator reviews: the draft, the sources it was written from, and anything the validator found. */
data class DraftForReview(
    val momentId: String,
    val model: String,
    val draftedAt: Instant,
    val problems: List<String>,
    val draft: StoryDraft,
    val sources: List<Source>,
)

/**
 * Drafts one moment's story with Claude: `wb draft edo-1830s`.
 *
 * Nothing is published. The draft is written to `drafts/<moment>.json` with the validator's findings, for a
 * curator to review, correct and copy into `sample/`. Needs an Anthropic API key in the environment.
 */
@Component
class StoryDrafting(
    private val properties: DraftingProperties,
    private val moments: MomentRepository,
    private val people: PersonRepository,
    private val wikipedia: WikipediaJob?,
    private val sources: DraftSources,
    private val drafter: StoryDrafter,
    private val jsonMapper: JsonMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Drafts [momentId]'s story and says where it wrote it. */
    fun draft(momentId: String): String {
        val moment = moments.findById(momentId).orElseThrow { IllegalArgumentException("Unknown moment: $momentId") }

        // The drafter works from Wikipedia leads, so make sure this moment's people have theirs first.
        wikipedia?.enrich(people.findAliveBetween(moment.region.code, moment.startYear, moment.endYear))

        val momentSources = sources.forMoment(moment)
        log.info("Drafting {} from {} sources with {}", momentId, momentSources.size, properties.model)
        val draft = drafter.draft(moment, momentSources)
        val problems = StoryDraftValidator.problems(draft, momentSources, moment.startYear, moment.endYear)

        val file = Path.of(properties.outputDir, "$momentId.json")
        Files.createDirectories(file.parent)
        jsonMapper.writerWithDefaultPrettyPrinter()
            .writeValue(file.toFile(), DraftForReview(momentId, properties.model, Instant.now(), problems, draft, momentSources))

        log.info("Wrote {} with {} cards and {} problems to review", file.toAbsolutePath(), draft.cards.size, problems.size)
        problems.forEach { log.warn("Review: {}", it) }
        return "Wrote ${file.toAbsolutePath()}: ${draft.cards.size} cards, ${problems.size} problems to review"
    }
}
