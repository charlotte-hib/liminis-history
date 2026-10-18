package art.liminis.curation

import org.springframework.jdbc.core.JdbcTemplate
import art.liminis.dataset.Coordinates
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.OccupationRow
import art.liminis.dataset.PersonFlag
import art.liminis.dataset.PersonOccupation
import art.liminis.dataset.PersonPlace
import art.liminis.dataset.PersonRow
import art.liminis.dataset.PlaceCountry
import art.liminis.dataset.PlaceFlag
import art.liminis.dataset.PlaceRow

/**
 * A small fetched dataset around the release's French moments (`sample/`: Paris 1870 to 1880 and 1890 to 1900,
 * Versailles 1682 to 1700, Orléans 1425 to 1431), as a mapping run would leave it.
 */
object CurationFixtures {

    /** The admin, as the API's tests sign in. */
    val CURATOR = Actor("curator", admin = true)

    val HUGO = person("Q535", "Victor Hugo", 1802, 1885, sitelinks = 200, birthplace = "Q37776", deathplace = "Q90")
    val ZOLA = person("Q504", "Émile Zola", 1840, 1902, sitelinks = 150, birthplace = "Q90", flags = setOf(PersonFlag.DEATH_UNREFERENCED))
    /** French, alive in the 1870s, but less known than Hugo and Zola: left out when only two are proposed per moment. */
    val LESSER = person("Q3008", "Lesser Known", 1830, 1885, sitelinks = 30, birthplace = "Q90")
    /** Born in France, but alive in none of its moments. */
    val NAPOLEON = person("Q517", "Napoleon", 1769, 1821, sitelinks = 250, birthplace = "Q40104")
    /** Born in the Netherlands. */
    val REMBRANDT = person("Q5598", "Rembrandt", 1606, 1669, sitelinks = 180, birthplace = "Q727")
    /** Born at Versailles' time, in a place that lies in a disputed area. */
    val BORN_IN_DISPUTED_AREA = person("Q1001", "Born In A Disputed Area", 1650, 1720, sitelinks = 40, birthplace = "Q1002")

    val PEOPLE = listOf(HUGO, ZOLA, LESSER, NAPOLEON, REMBRANDT, BORN_IN_DISPUTED_AREA)

    val PLACES = listOf(
        place("Q37776", "Besançon", "FR"),
        // No coordinates: France comes from Wikidata's country (P17).
        PlaceRow("Q90", "Paris", "Paris", null, null, null, listOf(PlaceCountry("Q142", "FR")), emptySet()),
        place("Q40104", "Ajaccio", "FR"),
        place("Q727", "Amsterdam", "NL"),
        place("Q1002", "A town in a disputed area", "FR").copy(disputedArea = "A disputed area", flags = setOf(PlaceFlag.DISPUTED_AREA)),
    )

    val OCCUPATIONS = listOf(OccupationRow("Q36180", "writer", "écrivain", "écrivaine", emptyList()))

    fun person(
        qid: String, label: String, born: Int, died: Int, sitelinks: Int, birthplace: String, deathplace: String? = null,
        flags: Set<PersonFlag> = emptySet(),
    ) = PersonRow(
        qid = qid, label = label, labelFr = label, sitelinks = sitelinks, born = born, bornPrecision = 11, died = died,
        diedPrecision = 11, diedEstimated = false, datesApproximate = false, gender = null, enwiki = label, frwiki = label,
        occupations = listOf(PersonOccupation("Q36180", true)),
        places = listOfNotNull(PersonPlace("P19", birthplace, null, null, true), deathplace?.let { PersonPlace("P20", it, null, null, true) }),
        flags = flags,
    )

    private fun place(qid: String, label: String, iso: String) =
        PlaceRow(qid, label, label, Coordinates(0.0, 0.0), iso, null, emptyList(), emptySet())

    /** Replaces the dataset with [people] and the fixtures' places and occupations. */
    fun map(dataset: DatasetStore, people: List<PersonRow> = PEOPLE) {
        dataset.clear()
        dataset.savePeople(people)
        dataset.savePlaces(PLACES)
        dataset.saveOccupations(OCCUPATIONS)
    }

    fun clearClaims(jdbc: JdbcTemplate) {
        jdbc.execute("truncate curation.claim, curation.source, curation.claim_source, curation.decision restart identity")
    }
}
