package art.liminis.publishing

import org.springframework.stereotype.Component
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.OccupationChoice
import art.liminis.curation.OccupationDomain
import art.liminis.curation.PersonFacts
import art.liminis.domain.Domain
import art.liminis.release.CorrectionRecord
import art.liminis.release.OccupationRecord

/** The files of the data repository that publishing writes, whole, from the approved claims. Nothing else is touched. */
enum class DataFile(val path: String) {
    OCCUPATIONS("data/occupations.jsonl"),
    CORRECTIONS("data/corrections.jsonl"),
}

/** A file's records as lines of JSON, by id, sorted by id as the release format wants them. */
typealias Lines = Map<String, String>

/** The file's text: one line per record, or none at all when there are no records, and the file goes. */
fun Lines.text(): String? = if (isEmpty()) null else values.joinToString("\n", postfix = "\n")

/**
 * What the approved claims make of [DataFile]s: the domain of each occupation mapped, and the curator's corrections to
 * people's facts. A correction holds only the fields approved otherwise than Wikidata proposed them; facts approved as
 * proposed need none. Corrections for people written by hand in `people.jsonl` are written too, though the release
 * gives them no effect: they are the curator's decisions all the same.
 */
@Component
class DataFiles(private val claims: ClaimRepository, jsonMapper: JsonMapper) {

    // Each record's fields in the order of its class, id first, as in the files written by hand.
    private val lineMapper = jsonMapper.rebuild().disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build()

    fun build(): Map<DataFile, Lines> = mapOf(
        DataFile.OCCUPATIONS to approved(ClaimType.OCCUPATION_DOMAIN).map { it.payload as OccupationDomain }
            .associate { it.occupation to occupation(it) },
        DataFile.CORRECTIONS to approved(ClaimType.PERSON_FACTS).mapNotNull { correction(it.payload as PersonFacts, it.proposal as PersonFacts) }
            .associate { it.id to lineMapper.writeValueAsString(it) },
    ).mapValues { (_, lines) -> lines.toSortedMap() }

    /** The lines of [text], a file of the release format, by their records' ids. */
    fun read(text: String?): Lines = records(text).associateBy { lineMapper.readTree(it)["id"].asString() }.toSortedMap()

    /** The Q-ids of the people of [text], `people.jsonl`: those written by hand who have one. */
    fun qids(text: String?): Set<String> = records(text).mapNotNull { lineMapper.readTree(it)["qid"]?.asString() }.toSet()

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
