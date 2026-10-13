package art.liminis.dataset

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.treeToValue
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mapping of places, on entities recorded from Wikidata on 2026-10-10 and Natural Earth's borders of the countries
 * they lie in ([NaturalEarthFixtures]).
 */
class PlaceMapperTests {

    private val mapper = PlaceMapper(NaturalEarthFixtures.borders())

    private val jsonMapper = jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val entities = jsonMapper.readTree(ClassPathResource("wiremock/__files/wikidata/wbgetentities-places.json").inputStream)
        .path("entities").properties().associate { (qid, json) -> qid to jsonMapper.treeToValue<WikidataEntity>(json) }

    private fun place(qid: String): PlaceRow = mapper.map(entities.getValue(qid))

    @Test
    fun `a place inside a country lies in it, by its coordinates as by Wikidata`() {
        assertEquals(
            PlaceRow(
                qid = "Q15883237",
                label = "Van Gogh House",
                labelFr = "maison de Vincent van Gogh à Nieuw-Amsterdam",
                coordinates = Coordinates(52.715833333333, 6.8497222222222),
                coordinatesCountry = "NL",
                disputedArea = null,
                // The Netherlands of Natural Earth, Q55: Wikidata gives its ISO code only to the Kingdom, Q29999.
                countries = listOf(PlaceCountry("Q55", "NL")),
                flags = emptySet(),
            ),
            place("Q15883237"),
        )
    }

    @Test
    fun `an island just off the coast takes the nearest country`() {
        // Engey, 1.2 km off Reykjavík, which the borders at 1:10m leave out. Its first coordinates of three.
        val engey = place("Q1342321")

        assertEquals(Coordinates(64.17111111, -21.91388889), engey.coordinates)
        assertEquals("IS", engey.coordinatesCountry)
    }

    @Test
    fun `islands further out take no country from their coordinates`() {
        // The Tremiti islands, 22 km off Italy.
        val tremiti = place("Q51930")

        assertNull(tremiti.coordinatesCountry)
        assertEquals(listOf(PlaceCountry("Q38", "IT")), tremiti.countries)
    }

    @Test
    fun `a place in a disputed area is flagged, whatever its countries`() {
        val kacanik = place("Q248378")

        assertEquals("XK", kacanik.coordinatesCountry)
        assertEquals("Kosovo", kacanik.disputedArea)
        assertEquals(listOf(PlaceCountry("Q1246", "XK"), PlaceCountry("Q403", "RS")), kacanik.countries)
        assertEquals(setOf(PlaceFlag.DISPUTED_AREA), kacanik.flags)
    }

    @Test
    fun `its countries are those of today, each once, best rank first`() {
        // Strojkovce: Serbia preferred, the kingdoms and states before it ended, and Serbia again at normal rank.
        assertEquals(listOf(PlaceCountry("Q403", "RS")), place("Q1888291").countries)
        // Bavaria-Straubing: Germany deprecated, the Holy Roman Empire with no end time stated.
        assertEquals(listOf(PlaceCountry("Q12548", null)), place("Q567719").countries)
    }

    @Test
    fun `an ancient place has no coordinates, and its country of the past no ISO code`() {
        val puteoli = place("Q10646925")

        assertNull(puteoli.coordinates)
        assertNull(puteoli.coordinatesCountry)
        assertEquals(listOf(PlaceCountry("Q1747689", null)), puteoli.countries)
    }
}
