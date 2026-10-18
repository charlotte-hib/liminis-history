package art.liminis.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import art.liminis.curation.CurationFixtures
import art.liminis.dataset.DatasetStore
import art.liminis.drafting.StoryDrafter
import art.liminis.testing.PostgresTestConfiguration

/** Who works on a claim: its holder, or the admin. Nobody's claim goes to whoever writes it first. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ClaimHoldersApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
) {

    // Never Claude itself, even with a key in the environment.
    @MockitoBean
    private lateinit var drafter: StoryDrafter

    private val zola = "person-facts:Q504"
    private val hugo = "person-facts:Q535"

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
        // Both alive in Paris in the 1870s.
        mockMvc.post("/api/people/{qid}/facts", "Q504") { signedIn() }
        mockMvc.post("/api/people/{qid}/facts", "Q535") { signedIn() }
    }

    private fun MockHttpServletRequestDsl.curator(username: String) = signedIn(CURATOR_SCOPES, username = username)

    private fun etag(id: String): String = mockMvc.get("/api/claims/{id}", id) { signedIn() }.andReturn().response.getHeader(HttpHeaders.ETAG)!!

    private fun keepForLater(id: String, by: MockHttpServletRequestDsl.() -> Unit) = mockMvc.post("/api/claims/{id}/decisions", id) {
        by()
        header(HttpHeaders.IF_MATCH, etag(id))
        contentType = MediaType.APPLICATION_JSON
        content = """{"action": "NEEDS_WORK", "note": "Check the dates"}"""
    }

    @Test
    fun `a decision takes a claim nobody holds, and then only its holder or the admin decides on it`() {
        keepForLater(zola) { curator("anne") }.andExpect {
            status { isOk() }
            jsonPath("$.heldBy") { value("anne") }
            jsonPath("$.heldSince") { exists() }
        }
        keepForLater(zola) { curator("bob") }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("held by anne")) }
        }
        mockMvc.post("/api/claims/{id}/undo", zola) {
            curator("bob")
            header(HttpHeaders.IF_MATCH, etag(zola))
        }.andExpect { status { isConflict() } }
        // The admin may, and the claim stays anne's.
        keepForLater(zola) { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.heldBy") { value("anne") }
        }
        mockMvc.get("/api/claims") { signedIn() }.andExpect {
            jsonPath("$[?(@.id == '$zola')].heldBy") { value("anne") }
            jsonPath("$[?(@.id == '$hugo')].heldBy") { isEmpty() }
        }
    }

    @Test
    fun `a curator takes and releases a claim, and only the admin hands one to someone else`() {
        mockMvc.put("/api/claims/{id}/holder", zola) { curator("anne") }.andExpect {
            status { isOk() }
            header { exists(HttpHeaders.ETAG) }
            jsonPath("$.heldBy") { value("anne") }
        }
        mockMvc.put("/api/claims/{id}/holder", zola) { curator("bob") }.andExpect { status { isConflict() } }
        mockMvc.delete("/api/claims/{id}/holder", zola) { curator("bob") }.andExpect { status { isConflict() } }
        mockMvc.delete("/api/claims/{id}/holder", zola) { curator("anne") }.andExpect {
            status { isOk() }
            jsonPath("$.heldBy") { doesNotExist() }
        }
        mockMvc.put("/api/claims/{id}/holder", zola) {
            curator("bob")
            contentType = MediaType.APPLICATION_JSON
            content = """{"username": "anne"}"""
        }.andExpect { status { isForbidden() } }
        mockMvc.put("/api/claims/{id}/holder", zola) { curator("bob") }.andExpect { jsonPath("$.heldBy") { value("bob") } }
        mockMvc.put("/api/claims/{id}/holder", zola) {
            signedIn()
            contentType = MediaType.APPLICATION_JSON
            content = """{"username": "anne"}"""
        }.andExpect { jsonPath("$.heldBy") { value("anne") } }
        mockMvc.put("/api/claims/{id}/holder", "person-facts:Q1") { curator("anne") }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `a curator takes a moment's open claims, leaving another's to them`() {
        mockMvc.put("/api/claims/{id}/holder", hugo) { curator("bob") }
        mockMvc.put("/api/moments/{id}/holder", "paris-1870s") { curator("anne") }.andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(1) }
            jsonPath("$.heldByOthers[0].username") { value("bob") }
            jsonPath("$.heldByOthers[0].claims") { value(1) }
        }
        mockMvc.get("/api/claims/{id}", zola) { signedIn() }.andExpect { jsonPath("$.heldBy") { value("anne") } }
        // Releasing a moment releases only one's own, but all of them for the admin.
        mockMvc.delete("/api/moments/{id}/holder", "paris-1870s") { curator("anne") }.andExpect { jsonPath("$.changed") { value(1) } }
        mockMvc.get("/api/claims/{id}", hugo) { signedIn() }.andExpect { jsonPath("$.heldBy") { value("bob") } }
        mockMvc.delete("/api/moments/{id}/holder", "paris-1870s") { signedIn() }.andExpect { jsonPath("$.changed") { value(1) } }
        mockMvc.get("/api/claims/{id}", hugo) { signedIn() }.andExpect { jsonPath("$.heldBy") { doesNotExist() } }
    }

    @Test
    fun `the admin releases everything someone holds`() {
        mockMvc.put("/api/moments/{id}/holder", "paris-1870s") { curator("anne") }
        mockMvc.delete("/api/holders/{username}", "anne") { curator("anne") }.andExpect { status { isForbidden() } }
        mockMvc.delete("/api/holders/{username}", "anne") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.changed") { value(2) }
        }
        mockMvc.get("/api/claims/{id}", zola) { signedIn() }.andExpect { jsonPath("$.heldBy") { doesNotExist() } }
        // Its version moved, as any change's does: a tab open on it reads it again.
        keepForLater(zola) { curator("bob") }.andExpect { status { isOk() } }
    }

    @Test
    fun `a claim written by hand is its author's, and a draft is refused while someone else holds the header`() {
        mockMvc.post("/api/moments/{id}/page", "paris-1870s") {
            curator("anne")
            contentType = MediaType.APPLICATION_JSON
            content = """{"hook": "A republic.", "world": {"governs": "g", "everyday": "e", "arts": "a", "meanwhile": "m"}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.heldBy") { value("anne") }
        }
        mockMvc.post("/api/moments/{id}/story", "paris-1870s") { curator("bob") }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(containsString("held by anne")) }
        }
        mockMvc.post("/api/moments/{id}/story", "atlantis-0") { curator("bob") }.andExpect { status { isNotFound() } }
    }
}
