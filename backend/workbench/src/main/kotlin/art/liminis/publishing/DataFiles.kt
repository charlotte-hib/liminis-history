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
import art.liminis.curation.Connection
import art.liminis.curation.OccupationChoice
import art.liminis.curation.OccupationDomain
import art.liminis.curation.PersonFacts
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.domain.Domain
import art.liminis.domain.PlaceKind
import art.liminis.release.AnecdoteRecord
import art.liminis.release.CitedSourceRecord
import art.liminis.release.ClearedField
import art.liminis.release.CorrectionPlaceRecord
import art.liminis.release.ConnectionRecord
import art.liminis.release.CorrectionRecord
import art.liminis.release.OccupationRecord
import art.liminis.support.UnavailableException
import java.net.URI

/** The files of the data repository that publishing writes, whole, from the approved claims. Nothing else is touched. */
enum class DataFile(val path: String) {
    OCCUPATIONS("data/occupations.jsonl"),
    CORRECTIONS("data/corrections.jsonl"),
    ANECDOTES("data/anecdotes.jsonl"),
    CONNECTIONS("data/connections.jsonl"),
}

/**
 * A file's records as lines of JSON, by id, sorted by id as the release format wants them: connections, which have
 * none, by [ConnectionLines.key], in [ConnectionLines.ORDER].
 */
typealias Lines = Map<String, String>

/** The file's text: one line per record, or none at all when there are no records, and the file goes. */
fun Lines.text(): String? = if (isEmpty()) null else values.joinToString("\n", postfix = "\n")

/** An approved anecdote, with the source the site names: none when it rests on none. */
data class ApprovedAnecdote(val id: String, val anecdote: Anecdote, val source: CitedSourceRecord?)

/** An approved connection, by its claim's id, as its line writes it. */
data class ApprovedConnection(val id: String, val record: ConnectionRecord)

/**
 * What the approved claims make of [DataFile]s: the domain of each occupation mapped, the curator's corrections to
 * people's facts, and the anecdotes and connections publishing does not hold back. A correction holds only the fields approved
 * otherwise than Wikidata proposed them; facts approved as proposed need none. Corrections for people written by hand
 * in `people.jsonl` are written too, though the release gives them no effect: they are the curator's decisions all the
 * same. A French name, an article or the death's precision removed in curate is cleared. The dates' precisions are
 * published though the site does not show them: they are the curator's decisions too.
 */
@Component
class DataFiles(private val claims: ClaimRepository, private val sources: ClaimSources, jsonMapper: JsonMapper) {

    // Each record's fields in the order of its class, id first, as in the files written by hand.
    private val lineMapper = jsonMapper.rebuild().disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build()

    fun build(anecdotes: List<AnecdoteRecord>, connections: List<ConnectionRecord>): Map<DataFile, Lines> = mapOf(
        DataFile.OCCUPATIONS to approved(ClaimType.OCCUPATION_DOMAIN).map { it.payload as OccupationDomain }
            .associate { it.occupation to occupation(it) },
        DataFile.CORRECTIONS to approved(ClaimType.PERSON_FACTS).mapNotNull { correction(it.payload as PersonFacts, it.proposal as PersonFacts) }
            .associate { it.id to lineMapper.writeValueAsString(it) },
        DataFile.ANECDOTES to anecdotes.associate { it.id to lineMapper.writeValueAsString(it) },
        DataFile.CONNECTIONS to connections.associate { ConnectionLines.key(it) to lineMapper.writeValueAsString(it) },
    ).mapValues { (file, lines) -> sorted(file, lines) }

    /**
     * The approved anecdotes, each with the source the site names: its first one but Wikidata (a book's page, a
     * programme's episode, a web page, a Wikipedia article's link), else its Wikidata entity's page. A book's quotes
     * stay in the workbench.
     */
    @Transactional(readOnly = true)
    fun anecdotes(): List<ApprovedAnecdote> = approved(ClaimType.ANECDOTE).map { claim ->
        val sources = sources.of(claim)
        val named = sources.firstOrNull { it.kind != SourceKind.WIKIDATA } ?: sources.firstOrNull()
        ApprovedAnecdote(claim.id, claim.payload as Anecdote, named?.let(::citedSource))
    }

    /**
     * The approved connections, as their lines write them: each with its source as the site names it, a book's quotes
     * left in the workbench.
     */
    fun connections(): List<ApprovedConnection> = approved(ClaimType.CONNECTION).map { claim ->
        val connection = claim.payload as Connection
        ApprovedConnection(claim.id, ConnectionRecord(connection.people, connection.kind, connection.year, connection.text, citedSource(connection.source)))
    }

    /** The lines of [text], [file] in the release format, by their records' ids. */
    fun read(file: DataFile, text: String?): Lines = sorted(file, records(text).associateBy { line ->
        if (file == DataFile.CONNECTIONS) return@associateBy ConnectionLines.key(readConnection(line))
        lineMapper.readTree(line)["id"]?.asString() ?: throw UnavailableException("${file.path} holds a line without an id: $line")
    })

    /** A line of `corrections.jsonl`. */
    fun readCorrection(line: String): CorrectionRecord = lineMapper.readValue(line, CorrectionRecord::class.java)

    /** A line of `anecdotes.jsonl`. */
    fun readAnecdote(line: String): AnecdoteRecord = lineMapper.readValue(line, AnecdoteRecord::class.java)

    /** A line of `connections.jsonl`. */
    fun readConnection(line: String): ConnectionRecord = lineMapper.readValue(line, ConnectionRecord::class.java)

    /** The lines of `connections.jsonl`'s [text]. */
    fun readConnections(text: String?): List<ConnectionRecord> = records(text).map(::readConnection)

    /** The Q-ids of the people of [text], `people.jsonl`, by their ids: those written by hand who have one. */
    fun qids(text: String?): Map<String, String> = records(text).map(lineMapper::readTree)
        .mapNotNull { person -> person["qid"]?.asString()?.let { person["id"].asString() to it } }.toMap()

    /** A line of `occupations.jsonl`. */
    fun readOccupation(line: String): OccupationRecord = lineMapper.readValue(line, OccupationRecord::class.java)

    /** The names of the people of [text], `people.jsonl`, by Q-id: those written by hand who have one. */
    fun byHand(text: String?): Map<String, String> = records(text).map(lineMapper::readTree)
        .mapNotNull { person -> person["qid"]?.asString()?.let { it to person["name"].asString() } }.toMap()

    private fun records(text: String?) = text.orEmpty().lines().filter { it.isNotBlank() }

    private fun sorted(file: DataFile, lines: Lines): Lines = if (file == DataFile.CONNECTIONS) lines.toSortedMap(ConnectionLines.ORDER) else lines.toSortedMap()

    private fun occupation(claim: OccupationDomain): String {
        val domain = when (claim.domain!!) {
            OccupationChoice.POWER -> Domain.POWER
            OccupationChoice.ARTS -> Domain.ARTS
            OccupationChoice.EVERYDAY -> Domain.EVERYDAY
            OccupationChoice.NONE -> null
        }
        return lineMapper.writeValueAsString(OccupationRecord(claim.occupation, domain))
    }

    private fun citedSource(source: SourceRef) = when (source.kind) {
        SourceKind.WIKIDATA -> CitedSourceRecord(url = "https://www.wikidata.org/wiki/${source.entity}")
        // Non-ASCII letters percent-encoded, as Wikipedia's own links are.
        SourceKind.WIKIPEDIA -> CitedSourceRecord(url = URI("https", "${source.language}.wikipedia.org", "/wiki/${source.title!!.replace(' ', '_')}", null).toASCIIString())
        SourceKind.BOOK -> CitedSourceRecord(title = source.title, page = source.page)
        SourceKind.WEB -> CitedSourceRecord(title = source.title, url = source.url)
        SourceKind.MEDIA -> CitedSourceRecord(title = source.title, episode = source.episode, minute = source.minute, url = source.url)
    }

    private fun approved(type: ClaimType) = claims.findByTypeAndStatusOrderById(type, ClaimStatus.APPROVED)

    /** The fields of [approved] that differ from what Wikidata [proposed], or nothing when none does. */
    private fun correction(approved: PersonFacts, proposed: PersonFacts): CorrectionRecord? {
        fun <T> changed(field: (PersonFacts) -> T): T? = field(approved).takeIf { it != field(proposed) }
        fun cleared(field: (PersonFacts) -> Any?, name: ClearedField) = name.takeIf { field(approved) == null && field(proposed) != null }
        val occupations: (PersonFacts) -> List<String> = { facts -> facts.occupations.map { it.occupation } }
        val places: (PersonFacts) -> List<CorrectionPlaceRecord> = { facts ->
            facts.places.map { CorrectionPlaceRecord(PlaceKind.valueOf(it.role.name), it.place, it.startYear, it.endYear) }
        }
        val correction = CorrectionRecord(
            id = approved.person,
            name = changed(PersonFacts::label),
            nameFr = changed(PersonFacts::labelFr),
            born = changed(PersonFacts::born),
            bornPrecision = changed(PersonFacts::bornPrecision),
            died = changed(PersonFacts::died),
            diedPrecision = changed(PersonFacts::diedPrecision),
            diedEstimated = changed(PersonFacts::diedEstimated),
            approximate = changed(PersonFacts::datesApproximate),
            occupations = changed(occupations),
            places = changed(places),
            cleared = setOfNotNull(
                cleared(PersonFacts::labelFr, ClearedField.NAME_FR),
                cleared(PersonFacts::diedPrecision, ClearedField.DIED_PRECISION),
                cleared(PersonFacts::enwiki, ClearedField.WIKIPEDIA),
                cleared(PersonFacts::frwiki, ClearedField.WIKIPEDIA_FR),
            ).ifEmpty { null },
        )
        return correction.takeIf { it != CorrectionRecord(approved.person) }
    }
}
