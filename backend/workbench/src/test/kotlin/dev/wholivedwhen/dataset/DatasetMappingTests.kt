package dev.wholivedwhen.dataset

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import dev.wholivedwhen.testing.PostgresTestConfiguration
import dev.wholivedwhen.wikidata.BirthSlice
import dev.wholivedwhen.wikidata.EntityBatch
import dev.wholivedwhen.wikidata.PageBatch
import dev.wholivedwhen.wikidata.RawStore
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mapping from `raw` to `dataset`, on what an import of the 28 people born from 2000 to 1901 BCE stored: the
 * responses recorded from Wikidata on 2026-10-09 and from Wikipedia on 2026-10-10, put in `raw` as the import puts them. Natural Earth's borders are
 * the fixtures', already where the mapping keeps them.
 */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.natural-earth.dir=${NaturalEarthFixtures.DIR}",
        "app.natural-earth.countries.sha256=${NaturalEarthFixtures.COUNTRIES_SHA256}",
        "app.natural-earth.disputed-areas.sha256=${NaturalEarthFixtures.DISPUTED_AREAS_SHA256}",
    ],
)
@Import(PostgresTestConfiguration::class)
class DatasetMappingTests(
    @Autowired private val mapping: DatasetMapping,
    @Autowired private val raw: RawStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {

    @BeforeEach
    fun emptyCache() {
        jdbc.execute(
            "truncate raw.discovered, raw.entity, raw.import_item, raw.discovery_slice, raw.page, raw.page_item, raw.import_run restart identity",
        )
    }

    private fun recorded(file: String, folder: String = "wikidata") =
        jsonMapper.readTree(ClassPathResource("wiremock/__files/$folder/$file").inputStream)

    /** Stores the recorded people, places, occupations, classes and articles, as a finished import would. */
    private fun importRecorded() {
        val run = raw.currentRun()
        val people = recorded("sparql-born-2000-to-1901-bce.json").path("results").path("bindings").associate {
            it.path("person").path("value").asString().substringAfterLast('/') to it.path("sitelinks").path("value").asString().toInt()
        }
        raw.saveSlice(run, BirthSlice(LocalDate.of(-1999, 1, 1), LocalDate.of(-1899, 1, 1)), people)
        val files = listOf("born", "linked-to-born", "classes-1-of-born", "classes-2-of-born", "classes-3-of-born")
        files.map { "wbgetentities-$it-2000-to-1901-bce.json" }.forEach { file ->
            val entities = recorded(file).path("entities").values().toList()
            raw.saveBatch(run, EntityBatch(entities.map { it.path("id").asString() }, 1, entities, emptyList(), emptyList()))
        }
        listOf("en", "fr").forEach { language ->
            (1..2).forEach { i ->
                val pages = recorded("$language-born-2000-to-1901-bce-$i.json", "wikipedia").path("query").path("pages").values()
                val byTitle = pages.associateBy { it.path("title").asString() }
                raw.savePages(run, PageBatch(language, byTitle.keys.toList(), 1, byTitle, emptyList(), emptyList()))
            }
        }
        raw.finish(run)
    }

    private fun rows(sql: String) = jdbc.queryForList(sql).map { it.values.toList() }

    @Test
    fun `maps every person the last import found, their places and their occupations`() {
        importRecorded()

        val counts = mapping.map()

        // The 9 occupations and the classes above them, but for 2 of the 25 fetched, only reached through deprecated statements.
        assertEquals(DatasetCounts(people = 28, leftOut = emptyMap(), articles = 28 + 22, places = 2, occupations = 9 + 23), counts)
        assertEquals(
            listOf<Any?>("Sobekneferu", "Néférousobek", 47, -1900, 7, -1793, 9, false, true, "Q6581072", "Sobekneferu", "Néférousobek"),
            rows(
                """
                select label, label_fr, sitelinks, born, born_precision, died, died_precision, died_estimated,
                    dates_approximate, gender, enwiki, frwiki
                from dataset.person where qid = 'Q228951'
                """,
            ).single(),
        )
        val (url, extract, thumbnail, image) = jdbc.queryForList(
            "select url, extract, thumbnail_url, image from dataset.person_article where person = 'Q228951' and language = 'en'",
        ).single().values.map { it as String }
        assertEquals("https://en.wikipedia.org/wiki/Sobekneferu", url)
        assertTrue(extract.startsWith("Sobekneferu or Neferusobek (Egyptian: "), extract)
        // Smaller than 400 pixels: the image itself, not a thumbnail.
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/8/89/Statue_of_Sobekneferu_%28Berlin_Egyptian_Museum_14475%29.jpg" +
                "?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail_unscaled",
            thumbnail,
        )
        assertEquals("Statue_of_Sobekneferu_(Berlin_Egyptian_Museum_14475).jpg", image)
        assertEquals(
            listOf<Any?>("fr", "Néférousobek", "https://fr.wikipedia.org/wiki/N%C3%A9f%C3%A9rousobek"),
            rows("select language, title, url from dataset.person_article where person = 'Q228951' and language = 'fr'").single(),
        )
        // Naram-Sin of Eshnunna has no French article; Da Ding's English one has no page image.
        assertEquals(listOf("en"), jdbc.queryForList("select language from dataset.person_article where person = 'Q327523'", String::class.java))
        assertEquals(
            listOf<Any?>(null, null),
            rows("select thumbnail_url, image from dataset.person_article where person = 'Q888231'").single(),
        )
        // Amenemhat III's preferred occupation, pharaoh of the 12th dynasty, comes first, though Wikidata lists it second.
        assertEquals(
            listOf("Q110550681", "Q82955", "Q110550696", "Q37110"),
            jdbc.queryForList("select occupation from dataset.person_occupation where person = 'Q19244' order by position", String::class.java),
        )
        assertEquals(
            listOf("BIRTH_IMPRECISE", "BIRTH_UNREFERENCED", "DEATH_UNREFERENCED", "OCCUPATION_UNREFERENCED").map { listOf(it, "POORLY_DOCUMENTED") },
            rows("select reason, flag from dataset.person_flag where person = 'Q228951' order by reason"),
        )
        // Isin, where Damiq-ilishu died, lies in Iraq today, by its coordinates and by Wikidata.
        assertEquals(
            listOf<Any?>("Isin", "Isin", 31.93351249, 45.28520718, "IQ", null),
            rows("select label, label_fr, latitude, longitude, coordinates_country, disputed_area from dataset.place where qid = 'Q501259'").single(),
        )
        assertEquals(listOf<Any?>(0, "Q796", "IQ"), rows("select position, country, iso from dataset.place_country where place = 'Q501259'").single())
        assertEquals(0, jdbc.queryForObject("select count(*) from dataset.place_flag", Int::class.java))
        assertEquals(
            listOf("Q501259", "Q79"),
            jdbc.queryForList("select qid from dataset.place order by qid", String::class.java),
        )
        assertEquals(
            listOf<Any?>("monarch", "monarque", null),
            rows("select label, label_fr, female_label_fr from dataset.occupation where qid = 'Q116'").single(),
        )
        assertEquals(
            listOf<Any?>("statesperson", "personne d'État", "femme d'État"),
            rows("select label, label_fr, female_label_fr from dataset.occupation where qid = 'Q372436'").single(),
        )
        // A pharaoh is a ruler and a monarch.
        assertEquals(
            listOf("Q1097498", "Q116"),
            jdbc.queryForList("select parent from dataset.occupation_parent where occupation = 'Q37110' order by position", String::class.java),
        )
        // Three levels up, "profession" is kept without its own class, "occupation", which the import did not fetch.
        assertEquals("profession", jdbc.queryForObject("select label from dataset.occupation where qid = 'Q28640'", String::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from dataset.occupation_parent where occupation = 'Q28640'", Int::class.java))
    }

    @Test
    fun `mapping again replaces the dataset`() {
        importRecorded()
        mapping.map()

        mapping.map()

        assertEquals(28, jdbc.queryForObject("select count(*) from dataset.person", Int::class.java))
    }

    @Test
    fun `there is nothing to map before an import has finished`() {
        assertThrows<IllegalStateException> { mapping.map() }
    }
}
