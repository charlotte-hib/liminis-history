package art.liminis.curation

import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers
import art.liminis.dataset.DatasetOccupation
import art.liminis.dataset.DatasetPerson
import art.liminis.dataset.DatasetPlace
import kotlin.test.assertEquals

class PersonFactsMapperTests {

    private val mapper = Mappers.getMapper(PersonFactsMapper::class.java)

    @Test
    fun `maps a person's facts, a deleted occupation or place keeping its Q-id as its label`() {
        val person = DatasetPerson(
            qid = "Q535", label = "Victor Hugo", labelFr = null, born = 1802, bornPrecision = 11, died = 1885,
            diedPrecision = 11, diedEstimated = false, datesApproximate = false, enwiki = "Victor Hugo", frwiki = null,
            occupations = listOf(DatasetOccupation("Q36180", "writer", "écrivain"), DatasetOccupation("Q999", null, null)),
            places = listOf(
                DatasetPlace("P19", "Q37776", "Besançon", "FR", null, null),
                DatasetPlace("P551", "Q998", null, null, 1855, 1870),
            ),
            flags = listOf("POORLY_DOCUMENTED" to "DEATH_UNREFERENCED"),
        )

        assertEquals(
            PersonFacts(
                person = "Q535", label = "Victor Hugo", born = 1802, bornPrecision = 11, died = 1885, diedPrecision = 11,
                diedEstimated = false, datesApproximate = false, enwiki = "Victor Hugo",
                occupations = listOf(Occupation("Q36180", "writer", "écrivain"), Occupation("Q999", "Q999")),
                places = listOf(
                    Place(PlaceRole.BIRTH, "Q37776", "Besançon", "FR"),
                    Place(PlaceRole.RESIDENCE, "Q998", "Q998", startYear = 1855, endYear = 1870),
                ),
            ),
            mapper.facts(person),
        )
    }
}
