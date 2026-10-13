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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import art.liminis.testing.PostgresTestConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every endpoint needs the workbench's token, and answers only requests addressed to localhost. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class WorkbenchSecurityTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {

    private val tokenFile = Path.of(System.getProperty("app.workbench.token-file"))
    private val token = Files.readString(tokenFile)

    /** Every endpoint of the API, with its path variables filled in. */
    private fun endpoints() = mappings.handlerMethods.keys.flatMap { info ->
        info.methodsCondition.methods.flatMap { method ->
            info.patternValues.map { HttpMethod.valueOf(method.name) to it.replace("{id}", "1").replace("{qid}", "Q1") }
        }
    }

    @Test
    fun `writes a new token on start, that only the curator can read`() {
        assertEquals(43, token.length)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile)))
    }

    @Test
    fun `every endpoint answers 401 without the token or with another one`() {
        val endpoints = endpoints()
        assertTrue(endpoints.size >= 9, "Found only $endpoints")
        for ((method, path) in endpoints) {
            mockMvc.perform(request(method, path))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
            mockMvc.perform(request(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer not-the-token"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
        }
    }

    @Test
    fun `answers with the token`() {
        mockMvc.get("/api/queues") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andExpect { status { isOk() } }
    }

    @Test
    fun `turns away a request addressed to another host, even with the token`() {
        mockMvc.get("/api/queues") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            with { it.serverName = "rebound.example"; it }
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sets no cookie and allows no cross-origin call`() {
        mockMvc.get("/api/queues") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            header(HttpHeaders.ORIGIN, "http://localhost:3002")
        }.andExpect {
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }
}
