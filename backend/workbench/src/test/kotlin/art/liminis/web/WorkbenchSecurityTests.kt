package art.liminis.web

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import art.liminis.testing.PostgresTestConfiguration
import art.liminis.web.WorkbenchSecurity.Companion.ADMIN
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_READ
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_WRITE
import art.liminis.web.WorkbenchSecurity.Companion.PUBLISH
import art.liminis.web.WorkbenchSecurity.Companion.RUNS
import kotlin.test.assertEquals

/** Every endpoint needs an access token with its own scope, and answers only requests addressed to localhost. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class WorkbenchSecurityTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {

    /** The scope each endpoint asks for: a new endpoint fails the test below until it is added here. */
    private val scopes = mapOf(
        "GET /api/queues" to CURATION_READ,
        "GET /api/claims" to CURATION_READ,
        "GET /api/claims/{id}" to CURATION_READ,
        "GET /api/people" to CURATION_READ,
        "GET /api/people/review" to CURATION_READ,
        "GET /api/people/{qid}/evidence" to CURATION_READ,
        "GET /api/people/{qid}/neighbours" to CURATION_READ,
        "GET /api/people/{qid}/preview" to CURATION_READ,
        "GET /api/places" to CURATION_READ,
        "GET /api/occupations" to CURATION_READ,
        "GET /api/occupations/{qid}" to CURATION_READ,
        "GET /api/paintings" to CURATION_READ,
        "GET /api/translations/run" to CURATION_READ,
        "POST /api/translations/run" to CURATION_WRITE,
        "GET /api/translations/{record}" to CURATION_READ,
        "POST /api/translations/{record}" to CURATION_WRITE,
        "POST /api/translations/{record}/run" to CURATION_WRITE,
        "GET /api/moments" to CURATION_READ,
        "GET /api/moments/{id}" to CURATION_READ,
        "GET /api/moments/{id}/people" to CURATION_READ,
        "GET /api/moments/{id}/preview" to CURATION_READ,
        "GET /api/moments/{id}/subjects" to CURATION_READ,
        "POST /api/people/{qid}/facts" to CURATION_WRITE,
        "POST /api/moments/{id}/story" to CURATION_WRITE,
        "POST /api/moments/{id}/page" to CURATION_WRITE,
        "POST /api/claims" to CURATION_WRITE,
        "POST /api/claims/{id}/decisions" to CURATION_WRITE,
        "POST /api/claims/{id}/undo" to CURATION_WRITE,
        "PUT /api/claims/{id}/holder" to CURATION_WRITE,
        "DELETE /api/claims/{id}/holder" to CURATION_WRITE,
        "PUT /api/moments/{id}/holder" to CURATION_WRITE,
        "DELETE /api/moments/{id}/holder" to CURATION_WRITE,
        "DELETE /api/holders/{username}" to ADMIN,
        "GET /api/runs" to RUNS,
        "POST /api/runs" to RUNS,
        "GET /api/runs/{id}" to RUNS,
        "GET /api/publication" to CURATION_READ,
        "POST /api/publication" to PUBLISH,
    )

    /** Every endpoint of the API, as "METHOD /path". */
    private fun endpoints() = mappings.handlerMethods.keys.flatMap { info ->
        info.methodsCondition.methods.flatMap { method -> info.patternValues.map { "${method.name} $it" } }
    }.toSet()

    /** The endpoint's method and path, its path variables filled in. */
    private fun call(endpoint: String) = endpoint.split(" ").let { (method, path) ->
        HttpMethod.valueOf(method) to path.replace("{id}", "1").replace("{qid}", "Q1").replace("{username}", "someone").replace("{record}", "era:x")
    }

    @Test
    fun `every endpoint has its scope`() {
        assertEquals(scopes.keys, endpoints())
    }

    @Test
    fun `every endpoint answers 401 without a token or with a bad one`() {
        for (endpoint in endpoints()) {
            val (method, path) = call(endpoint)
            mockMvc.request(method, path).andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
            }
            mockMvc.request(method, path) { header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token") }.andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")) }
            }
        }
    }

    @Test
    fun `every endpoint answers 403 to a token without its scope, and lets its scope through`() {
        for ((endpoint, scope) in scopes) {
            val (method, path) = call(endpoint)
            mockMvc.request(method, path) { signedIn(ALL_SCOPES - scope) }.andExpect {
                status { isForbidden() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")) }
            }
            val status = mockMvc.request(method, path) { signedIn(listOf(scope)) }.andReturn().response.status
            assert(status != 401 && status != 403) { "$endpoint answered $status with $scope" }
        }
    }

    @Test
    fun `turns away a request addressed to another host, even signed in`() {
        mockMvc.get("/api/queues") {
            signedIn()
            with { it.serverName = "rebound.example"; it }
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sets no cookie and allows no cross-origin call`() {
        mockMvc.get("/api/queues") {
            signedIn()
            header(HttpHeaders.ORIGIN, "http://localhost:3100")
        }.andExpect {
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }
}
