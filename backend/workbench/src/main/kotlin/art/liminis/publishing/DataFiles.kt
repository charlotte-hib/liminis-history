package art.liminis.publishing

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import art.liminis.curation.Anecdote
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimSources
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.OccupationChoice
import art.liminis.curation.OccupationDomain
import art.liminis.curation.PersonFacts
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.domain.Domain
import art.liminis.release.AnecdoteRecord
import art.liminis.release.AnecdoteSourceRecord
import art.liminis.release.CorrectionRecord
import art.liminis.release.OccupationRecord
import art.liminis.support.UnavailableException
import java.net.URI

/** The files of the data repository that publishing writes, whole, from the approved claims. Nothing else is touched. */
enum class DataFile(val path: String) {
    OCCUPATIONS("data/occupations.jsonl"),
    CORRECTIONS("data/corrections.jsonl"),
    ANECDOTES("data/anecdotes.jsonl"),
}

/** A file's records as lines of JSON, by id, sorted by id as the release format wants them. */
typealias Lines = Map<String, String>

/** The file's text: one line per record, or none at all when there are no records, and the file goes. */
fun Lines.text(): String? = if (isEmpty()) null else values.joinToString("\n", postfix = "\n")

/** An approved anecdote, with the source the site names: none when it rests on none. */
data class ApprovedAnecdote(val id: String, val anecdote: Anecdote, val source: AnecdoteSourceRecord?)

/**
 * What the approved claims make of [DataFile]s: the domain of each occupation mapped, the curator's corrections to
 * people's facts, and the anecdotes publishing does not hold back. A correction holds only the fields approved
 * otherwise than Wikidata proposed them; facts approved as proposed need none. Corrections for people written by hand
 * in `people.jsonl` are written too, though the release gives them no effect: they are the curator's decisions all the
 * same. A correction can set a field, not clear it: a French name removed in curate, and a date's precision, which the
 * release does not use, are not published.
 */
@Component
class DataFiles(private val claims: ClaimRepository, private val sources: ClaimSources, jsonMapper: JsonMapper) {

    // Each record's fields in the order of its class, id first, as in the files written by hand.
    private val lineMapper = jsonMapper.rebuild().disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build()

    fun build(anecdotes: List<AnecdoteRecord>): Map<DataFile, Lines> = mapOf(
        DataFile.OCCUPATIONS to approved(ClaimType.OCCUPATION_DOMAIN).map { it.payload as OccupationDomain }
            .associate { it.occupation to occupation(it) },
        DataFile.CORRECTIONS to approved(ClaimType.PERSON_FACTS).mapNotNull { correction(it.payload as PersonFacts, it.proposal as PersonFacts) }
            .associate { it.id to lineMapper.writeValueAsString(it) },
        DataFile.ANECDOTES to anecdotes.associate { it.id to lineMapper.writeValueAsString(it) },
    ).mapValues { (_, lines) -> lines.toSortedMap() }

    /**
     * The approved anecdotes, each with the source the site names: its first one but Wikidata (a book's page, a
     * programme's episode, a web page, a Wikipedia article's link), else its Wikidata entity's page. A book's quotes
     * stay in the workbench.
     */
    @Transactional(readOnly = true)
    fun anecdotes(): List<ApprovedAnecdote> = approved(ClaimType.ANECDOTE).map { claim ->
        val sources = sources.of(claim)
        val named = sources.firstOrNull { it.kind != SourceKind.WIKIDATA } ?: sources.firstOrNull()
        ApprovedAnecdote(claim.id, claim.payload as Anecdote, named?.let(::anecdoteSource))
    }

    /** The lines of [text], the file at [path] in the release format, by their records' ids. */
    fun read(path: String, text: String?): Lines = records(text).associateBy { line ->
        lineMapper.readTree(line)["id"]?.asString() ?: throw UnavailableException("$path holds a line without an id: $line")
    }.toSortedMap()

    /** A line of `corrections.jsonl`. */
    fun readCorrection(line: String): CorrectionRecord = lineMapper.readValue(line, CorrectionRecord::class.java)

    /** A line of `anecdotes.jsonl`. */
    fun readAnecdote(line: String): AnecdoteRecord = lineMapper.readValue(line, AnecdoteRecord::class.java)

    /** A line of `occupations.jsonl`. */
    fun readOccupation(line: String): OccupationRecord = lineMapper.readValue(line, OccupationRecord::class.java)

    /** The names of the people of [text], `people.jsonl`, by Q-id: those written by hand who have one. */
    fun byHand(text: String?): Map<String, String> = records(text).map(lineMapper::readTree)
        .mapNotNull { person -> person["qid"]?.asString()?.let { it to person["name"].asString() } }.toMap()

    private fun records(text: String?) = text.orEmpty().lines().filter { it.isNotBlank() }

    private fun occupation(claim: OccupationDomain): String {
        val domain = when (claim.domain!!) {
            OccupationChoice.POWER -> Domain.POWER
            OccupationChoice.ARTS -> Domain.ARTS
            OccupationChoice.EVERYDAY -> Domain.EVERYDAY
            OccupationChoice.NONE -> null
        }
        return lineMapper.writeValueAsString(OccupationRecord(claim.occupation, domain))
    }

    private fun anecdoteSource(source: SourceRef) = when (source.kind) {
        SourceKind.WIKIDATA -> AnecdoteSourceRecord(url = "https://www.wikidata.org/wiki/${source.entity}")
        // Non-ASCII letters percent-encoded, as Wikipedia's own links are.
        SourceKind.WIKIPEDIA -> AnecdoteSourceRecord(url = URI("https", "${source.language}.wikipedia.org", "/wiki/${source.title!!.replace(' ', '_')}", null).toASCIIString())
        SourceKind.BOOK -> AnecdoteSourceRecord(title = source.title, page = source.page)
        SourceKind.WEB -> AnecdoteSourceRecord(title = source.title, url = source.url)
        SourceKind.MEDIA -> AnecdoteSourceRecord(title = source.title, episode = source.episode, minute = source.minute, url = source.url)
    }

    private fun approved(type: ClaimType) = claims.findByTypeAndStatusOrderById(type, ClaimStatus.APPROVED)

    /** The fields of [approved] that differ from what Wikidata [proposed], or nothing when none does. */
    private fun correction(approved: PersonFacts, proposed: PersonFacts): CorrectionRecord? {
        fun <T> changed(field: (PersonFacts) -> T): T? = field(approved).takeIf { it != field(proposed) }
        val occupations: (PersonFacts) -> List<String> = { facts -> facts.occupations.map { it.occupation } }
        val correction = CorrectionRecord(
            id = approved.person,
            name = changed(PersonFacts::label),
            nameFr = changed(PersonFacts::labelFr),
            born = changed(PersonFacts::born),
            died = changed(PersonFacts::died),
            approximate = changed(PersonFacts::datesApproximate),
            occupations = changed(occupations),
        )
        return correction.takeIf { it != CorrectionRecord(approved.person) }
    }
}
