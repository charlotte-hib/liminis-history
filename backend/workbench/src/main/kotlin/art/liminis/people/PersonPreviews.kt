package art.liminis.people

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.curation.Anecdote
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimSources
import art.liminis.curation.ClaimStatus
import art.liminis.curation.PersonFacts
import art.liminis.curation.PersonFactsMapper
import art.liminis.curation.PlaceRole
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.dataset.DatasetStore
import art.liminis.domain.Domain
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.LONG_TEXT
import art.liminis.release.OccupationDomains
import art.liminis.release.OccupationRecord
import art.liminis.repository.EraRepository
import art.liminis.repository.EventRepository
import art.liminis.repository.MomentRepository
import art.liminis.repository.RegionRepository
import art.liminis.support.InvalidRequestException
import art.liminis.support.NotFoundException
import art.liminis.support.dropHatnote
import art.liminis.support.leadSentences
import art.liminis.support.yearsBetween
import art.liminis.wikimedia.WikimediaClient
import art.liminis.wikimedia.answeredBy

/** What the site's person panel shows of someone beyond their name, dates and occupations. */
data class PersonPreview(
    val person: String,
    val portrait: String?,
    val about: String?,
    val wikipediaUrl: String?,
    val domain: Domain,
    val region: String?,
    val year: Int,
    val lifeline: List<PreviewLifeLine>,
)

enum class LifeLineKind { BIRTH, ERA, EVENT, OWN_EVENT, ANECDOTE, DEATH }

/** A line of "their life in their time"; [waiting] for an anecdote not approved yet. */
data class PreviewLifeLine(
    val year: Int,
    val age: Int?,
    val kind: LifeLineKind,
    val text: String,
    val role: String? = null,
    val source: SourceRef? = null,
    val waiting: Boolean = false,
)

/**
 * The facts a preview shows, as the curator is editing them: their years, first occupations' Q-ids, their birthplace's
 * Q-id and their English Wikipedia article's title, both blank for none.
 */
data class EditedFacts(
    val born: Int? = null,
    val died: Int? = null,
    val occupations: List<String>? = null,
    val birthplace: String? = null,
    val enwiki: String? = null,
)

/** The parts of an English Wikipedia article a preview shows. */
private data class PreviewArticle(val extract: String?, val thumbnailUrl: String?, val url: String?)

/**
 * A person as the site would show them once their facts are approved and published: from their claim, else the
 * dataset, with the facts being edited over them; their life told in the release's eras and events of the moment's
 * region, else of their birthplace's country, and the anecdotes told about them.
 */
@Service
class PersonPreviews(
    private val claims: ClaimRepository,
    private val sources: ClaimSources,
    private val dataset: DatasetStore,
    private val factsMapper: PersonFactsMapper,
    private val moments: MomentRepository,
    private val regions: RegionRepository,
    private val eras: EraRepository,
    private val events: EventRepository,
    private val wikimedia: WikimediaClient,
) {

    @Transactional(readOnly = true)
    fun preview(qid: String, momentId: String?, edited: EditedFacts): PersonPreview {
        val facts = claims.findById(ClaimRules.factsId(qid)).map { it.payload as PersonFacts }.orElse(null)
            ?: dataset.people(listOf(qid)).singleOrNull()?.let(factsMapper::facts)
            ?: throw NotFoundException("Unknown person: $qid")
        val moment = momentId?.let { id -> moments.findById(id).orElseThrow { NotFoundException("No moment $id") } }
        val born = edited.born ?: facts.born
        val died = edited.died ?: facts.died
        if (born > died) throw InvalidRequestException("Born in $born, after the death in $died")
        val birthCountry = when {
            edited.birthplace == null -> facts.places.firstOrNull { it.role == PlaceRole.BIRTH }?.country
            edited.birthplace.isBlank() -> null
            else -> dataset.foundPlaces(listOf(edited.birthplace)).singleOrNull()?.country
        }
        val region = moment?.region?.code ?: birthCountry
        val articles = dataset.articles(qid)
        val english = if (edited.enwiki == null || edited.enwiki == facts.enwiki) {
            articles.firstOrNull { it.language == "en" }?.let { PreviewArticle(it.extract, it.thumbnailUrl, it.url) }
        } else {
            edited.enwiki.takeIf { it.isNotBlank() }?.let(::fetched)
        }
        val anecdotes = claims.anecdotesAbout(qid).filter { it.status != ClaimStatus.REJECTED }.map { claim ->
            val told = sources.of(claim)
            // The source publishing names, without a book's quote, which stays in the workbench.
            val named = (told.firstOrNull { it.kind != SourceKind.WIKIDATA } ?: told.firstOrNull())?.copy(quote = null)
            ToldAnecdote(claim.payload as Anecdote, named, claim.status != ClaimStatus.APPROVED)
        }
        return PersonPreview(
            person = qid,
            // Another English article than the claim's gives its own portrait, or none: as the release has it.
            portrait = if (edited.enwiki == null || edited.enwiki == facts.enwiki) articles.firstNotNullOfOrNull { it.thumbnailUrl } else english?.thumbnailUrl,
            about = english?.extract?.let(::dropHatnote)?.takeIf { it.isNotBlank() }?.let(::about),
            wikipediaUrl = english?.url,
            domain = domainOf(edited.occupations ?: facts.occupations.map { it.occupation }),
            region = region?.let { code -> regions.findById(code).map { it.name }.orElse(code) },
            year = moment?.focusYear?.takeIf { it in born..died } ?: minOf(died, born + PRIME_AGE),
            lifeline = lifeInTime(
                qid, born, died,
                region?.let(eras::findByRegionCodeOrderByStartYearAscIdAsc).orEmpty(),
                region?.let { events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(it, born, died) }.orEmpty(),
                anecdotes,
            ),
        )
    }

    /** The English Wikipedia article titled [title], fetched now; none when there is no such page. */
    private fun fetched(title: String): PreviewArticle? {
        val page = answeredBy("Wikipedia") { wikimedia.pages("en", listOf(title))[title] } ?: return null
        return PreviewArticle(
            page.path("extract").asString(null),
            page.path("thumbnail").path("source").asString(null),
            page.path("fullurl").asString(null),
        )
    }

    /**
     * Their domain as the release gives it: from their first occupation with a domain approved, for it or for a class
     * above it, as publishing writes them; everyday life by default.
     */
    private fun domainOf(occupations: List<String>): Domain {
        val records = claims.approvedDomains().map { OccupationRecord(it.occupation, it.domain?.let(Domain::valueOf)) }
        val domains = OccupationDomains(records, dataset.occupations().associateBy { it.qid })
        return occupations.firstNotNullOfOrNull(domains::of) ?: Domain.EVERYDAY
    }

    private data class ToldAnecdote(val anecdote: Anecdote, val source: SourceRef?, val waiting: Boolean)

    // Kept alike with the site's lifeInTime (backend/site, LifeInTime.kt), which the workbench can't reach: the same
    // lines, from the same records, for someone the site doesn't hold yet.
    private fun lifeInTime(qid: String, born: Int, died: Int, regionEras: List<Era>, regionEvents: List<Event>, anecdotes: List<ToldAnecdote>): List<PreviewLifeLine> {
        // An anecdote may tell of a year outside their life (a legend, a burial): it then has no age.
        val age = { year: Int -> yearsBetween(born, year).takeIf { year in born..died } }
        val lines = mutableListOf<PreviewLifeLine>()

        val bornUnder = regionEras.lastOrNull { born in it.startYear..it.endYear }
        lines += PreviewLifeLine(born, null, LifeLineKind.BIRTH, "Born" + (bornUnder?.let { " ${regimePhrase(it.label)}" } ?: ""))

        regionEras
            .filter { it.startYear > born && it.startYear <= died }
            .forEach { lines += PreviewLifeLine(it.startYear, age(it.startYear), LifeLineKind.ERA, "${withArticle(it.label)} begins") }

        var background = 0
        regionEvents
            .filter { it.year in born..died }
            .sortedBy { it.year }
            .forEach { event ->
                val own = event.participants.firstOrNull { it.person.qid == qid }
                when {
                    own != null -> lines += PreviewLifeLine(event.year, age(event.year), LifeLineKind.OWN_EVENT, event.title, own.role)
                    background++ < MAX_BACKGROUND_EVENTS -> lines += PreviewLifeLine(event.year, age(event.year), LifeLineKind.EVENT, event.title)
                }
            }

        anecdotes.forEach { (anecdote, source, waiting) ->
            lines += PreviewLifeLine(anecdote.year, age(anecdote.year), LifeLineKind.ANECDOTE, anecdote.text, source = source, waiting = waiting)
        }

        lines += PreviewLifeLine(died, age(died), LifeLineKind.DEATH, "Dies")
        return lines.sortedWith(compareBy({ it.year }, { it.kind.ordinal }))
    }

    private companion object {
        /** The age a person is shown at when the moment's year is outside their life: well into their adult life. */
        const val PRIME_AGE = 35

        /** How many events the person is not part of may appear, so their own moments stand out. */
        const val MAX_BACKGROUND_EVENTS = 4

        // Place names read without an article ("in Roman Gaul"); regimes take one ("under the Third Republic").
        val PLACE_LIKE = "^(Celtic|Roman|Iron Age|Anglo-Saxon|Frankish|West Francia|Modern)".toRegex()
        val REGIME = "(republic|empire|monarchy|restoration|government|regime|shogunate)".toRegex(RegexOption.IGNORE_CASE)

        /** The intro's first paragraph, cut to whole sentences that fit, as the release cuts it (core's PeopleCompiler). */
        fun about(intro: String): String {
            val paragraph = leadSentences(intro.substringBefore('\n'), LONG_TEXT)
            return if (paragraph.length <= LONG_TEXT) paragraph else paragraph.take(LONG_TEXT - 1) + "…"
        }

        fun withArticle(label: String) =
            if (PLACE_LIKE.containsMatchIn(label) && !REGIME.containsMatchIn(label)) label else "The $label"

        fun regimePhrase(label: String) = when {
            REGIME.containsMatchIn(label) -> "under the $label"
            PLACE_LIKE.containsMatchIn(label) -> "in $label"
            else -> "in the $label"
        }
    }
}
