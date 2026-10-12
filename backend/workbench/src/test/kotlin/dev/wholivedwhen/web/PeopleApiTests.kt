package dev.wholivedwhen.web

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import dev.wholivedwhen.dataset.DatasetMapping
import dev.wholivedwhen.dataset.NaturalEarthFixtures
import dev.wholivedwhen.dataset.RecordedImport
import dev.wholivedwhen.testing.PostgresTestConfiguration
import dev.wholivedwhen.wikidata.RawStore
import java.nio.file.Files
import java.nio.file.Path

/** Finding people and reading their evidence, on what an import of the 28 people born from 2000 to 1901 BCE stored. */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.natural-earth.dir=${NaturalEarthFixtures.DIR}",
        "app.natural-earth.countries.sha256=${NaturalEarthFixtures.COUNTRIES_SHA256}",
        "app.natural-earth.disputed-areas.sha256=${NaturalEarthFixtures.DISPUTED_AREAS_SHA256}",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class PeopleApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val mapping: DatasetMapping,
    @Autowired private val raw: RawStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {

    private val token = Files.readString(Path.of(System.getProperty("app.workbench.token-file")))

    @BeforeEach
    fun importAndMap() {
        RecordedImport(raw, jdbc, jsonMapper).apply {
            emptyCache()
            store()
        }
        mapping.map()
    }

    private fun MockHttpServletRequestDsl.signedIn() = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    @Test
    fun `finds people by a part of their name, those it starts with first, then the best known`() {
        mockMvc.get("/api/people") {
            signedIn()
            param("q", "NEFER")
        }.andExpect {
            status { isOk() }
            // Neferuptah and Neferthenut start with the query; Sobekneferu has the most sitelinks of the others.
            jsonPath("$[*].label") {
                value(contains("Neferuptah", "Neferthenut", "Sobekneferu", "Khenemetneferhedjet I", "Khenemetneferhedjet III"))
            }
            jsonPath("$[2].person") { value("Q228951") }
            jsonPath("$[2].labelFr") { value("Néférousobek") }
            jsonPath("$[2].born") { value(-1900) }
            jsonPath("$[2].died") { value(-1793) }
            jsonPath("$[2].occupation") { value("statesperson") }
        }
    }

    @Test
    fun `finds a person by Q-id, and by their French label`() {
        mockMvc.get("/api/people") {
            signedIn()
            param("q", "Q228951")
        }.andExpect {
            jsonPath("$[0].label") { value("Sobekneferu") }
        }
        mockMvc.get("/api/people") {
            signedIn()
            param("q", "néférou")
        }.andExpect {
            jsonPath("$[*].person") { value(contains("Q228951")) }
        }
    }

    @Test
    fun `refuses a query of one letter`() {
        mockMvc.get("/api/people") {
            signedIn()
            param("q", "a")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `gives every statement of a person's dates and occupations, with their labels, and their articles`() {
        mockMvc.get("/api/people/{qid}/evidence", "Q228951") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.person") { value("Q228951") }
            jsonPath("$.statements[*].field") { value(contains("BORN", "DIED", "OCCUPATION", "OCCUPATION")) }
            jsonPath("$.statements[0].property") { value("P569") }
            jsonPath("$.statements[0].year") { value(-1900) }
            jsonPath("$.statements[0].precision") { value(7) }
            jsonPath("$.statements[0].rank") { value("NORMAL") }
            // Only "imported from Wikimedia project": no source named.
            jsonPath("$.statements[0].references") { value(0) }
            jsonPath("$.statements[2].item") { value("Q372436") }
            jsonPath("$.statements[2].label") { value("statesperson") }
            jsonPath("$.statements[3].label") { value("queen regnant") }
            jsonPath("$.articles[*].language") { value(contains("en", "fr")) }
            jsonPath("$.articles[0].url") { value("https://en.wikipedia.org/wiki/Sobekneferu") }
            jsonPath("$.articles[0].extract") { value(startsWith("Sobekneferu or Neferusobek")) }
        }
    }

    @Test
    fun `gives both dates of birth when Wikidata states two`() {
        mockMvc.get("/api/people/{qid}/evidence", "Q888231") { signedIn() }.andExpect {
            jsonPath("$.statements[?(@.field == 'BORN')].year") { value(contains(-1900, -1800)) }
        }
    }

    @Test
    fun `answers 404 for a person the import never fetched`() {
        mockMvc.get("/api/people/{qid}/evidence", "Q1") { signedIn() }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Unknown person: Q1") }
        }
    }
}
