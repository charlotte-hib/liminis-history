package art.liminis.web

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.client.RestClient
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Occupation
import art.liminis.curation.OccupationChoice
import art.liminis.curation.OccupationDomain
import art.liminis.curation.Origin
import art.liminis.curation.Payload
import art.liminis.curation.PersonFacts
import art.liminis.publishing.GitHub
import art.liminis.publishing.PublishProperties
import art.liminis.testing.PostgresTestConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Publishing, against a fake GitHub (WireMock) holding the data repository: what a publish would change, the passkey
 * it needs, and the commit, branch and pull request it makes through the Git database API.
 */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.publish.github.api=\${github.base-url}",
        "app.publish.github.client-id=Iv23-test-app",
    ],
)
@AutoConfigureMockMvc
@EnableWireMock(ConfigureWireMock(baseUrlProperties = ["github.base-url"]))
@Import(PostgresTestConfiguration::class)
class PublicationApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val claims: ClaimRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val properties: PublishProperties,
) {

    @InjectWireMock
    private lateinit var github: WireMockServer

    private val repo = "/repos/charlotte-hib/liminis-history-data"
    private val base = "a".repeat(40)

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        github.stubFor(get("$repo/installation").willReturn(okJson("""{"id": 7}""")))
        github.stubFor(
            post("/app/installations/7/access_tokens")
                .willReturn(okJson("""{"token": "ghs_installation", "expires_at": "${Instant.now().plus(Duration.ofHours(1))}"}""")),
        )
        github.stubFor(get("$repo/git/ref/heads/main").willReturn(okJson("""{"object": {"sha": "$base"}}""")))
        github.stubFor(get("$repo/git/ref/heads/publish").willReturn(notFound()))
        github.stubFor(get(urlPathEqualTo("$repo/pulls")).willReturn(okJson("[]")))
        file("data/occupations.jsonl", null)
        file("data/corrections.jsonl", """{"id":"Q1","name":"Someone corrected before"}""" + "\n")
        file("data/people.jsonl", """{"id":"victor-hugo","qid":"Q535","name":"Victor Hugo"}""" + "\n")
    }

    /** The file at [path] on main, or none. */
    private fun file(path: String, text: String?) {
        github.stubFor(
            get(urlPathEqualTo("$repo/contents/$path")).withQueryParam("ref", equalTo(base))
                .willReturn(text?.let { aResponse().withBody(it) } ?: notFound()),
        )
    }

    private fun facts(person: String, label: String, born: Int, died: Int, occupations: List<String> = listOf("Q36180")) = PersonFacts(
        person = person, label = label, born = born, bornPrecision = 9, died = died, diedEstimated = false,
        datesApproximate = false, occupations = occupations.map { Occupation(it, it) }, places = emptyList(),
    )

    private fun approve(proposal: Payload, approved: Payload = proposal) {
        claims.save(Claim.candidate(proposal, Origin.WIKIDATA, emptyList()).apply { status = ClaimStatus.APPROVED; payload = approved })
    }

    private fun approveSome() {
        approve(OccupationDomain("Q36180", "writer", domain = null), OccupationDomain("Q36180", "writer", domain = OccupationChoice.ARTS))
        approve(OccupationDomain("Q2478141", "aristocrat", domain = null), OccupationDomain("Q2478141", "aristocrat", domain = OccupationChoice.NONE))
        // Approved as Wikidata proposed it: nothing to correct.
        approve(facts("Q504", "Émile Zola", 1840, 1902))
        approve(facts("Q535", "Victor Hugo", 1802, 1885, listOf("Q36180", "Q82955")), facts("Q535", "Victor Hugo", 1801, 1885, listOf("Q82955")))
        // Waiting: never published.
        claims.save(Claim.candidate(facts("Q517", "Napoleon", 1769, 1821), Origin.WIKIDATA, emptyList()))
    }

    private fun publish(passkeyAt: Instant? = Instant.now().minusSeconds(30), base: String = this.base) = mockMvc.post("/api/publication") {
        signedIn(passkeyAt = passkeyAt)
        contentType = MediaType.APPLICATION_JSON
        content = """{"base": "$base"}"""
    }

    private fun stubWrites(openPullRequest: Boolean = false) {
        github.stubFor(get("$repo/git/commits/$base").willReturn(okJson("""{"tree": {"sha": "tree-of-main"}}""")))
        github.stubFor(post("$repo/git/trees").willReturn(okJson("""{"sha": "new-tree"}""")))
        github.stubFor(post("$repo/git/commits").willReturn(okJson("""{"sha": "new-commit"}""")))
        github.stubFor(post("$repo/git/refs").willReturn(okJson("{}")))
        github.stubFor(post("$repo/pulls").willReturn(okJson("""{"number": 4, "html_url": "https://github.com/pr/4"}""")))
        if (openPullRequest) {
            github.stubFor(get("$repo/git/ref/heads/publish").willReturn(okJson("""{"object": {"sha": "older"}}""")))
            github.stubFor(get(urlPathEqualTo("$repo/pulls")).withQueryParam("head", equalTo("charlotte-hib:publish"))
                .willReturn(okJson("""[{"number": 3, "html_url": "https://github.com/pr/3"}]""")))
            github.stubFor(patch(urlPathEqualTo("$repo/git/refs/heads/publish")).willReturn(okJson("{}")))
            github.stubFor(patch(urlPathEqualTo("$repo/pulls/3")).willReturn(okJson("{}")))
        }
    }

    @Test
    fun `shows each record publishing would add, change or remove, from the approved claims only`() {
        approveSome()
        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.base") { value(base) }
            jsonPath("$.files[0].path") { value("data/occupations.jsonl") }
            jsonPath("$.files[0].changes.length()") { value(2) }
            jsonPath("$.files[0].changes[0].id") { value("Q2478141") }
            jsonPath("$.files[0].changes[0].after") { value("""{"id":"Q2478141"}""") }
            jsonPath("$.files[0].changes[1].change") { value("ADDED") }
            jsonPath("$.files[0].changes[1].after") { value("""{"id":"Q36180","domain":"ARTS"}""") }
            jsonPath("$.files[1].path") { value("data/corrections.jsonl") }
            jsonPath("$.files[1].changes[0].id") { value("Q1") }
            jsonPath("$.files[1].changes[0].change") { value("REMOVED") }
            jsonPath("$.files[1].changes[1].after") { value("""{"id":"Q535","born":1801,"occupations":["Q82955"]}""") }
            jsonPath("$.files[1].changes.length()") { value(2) }
            jsonPath("$.pullRequest") { doesNotExist() }
            // Victor Hugo is written by hand in people.jsonl: his record wins.
            jsonPath("$.withoutEffect[0]") { value("Q535") }
        }
    }

    @Test
    fun `asks for the passkey again when it was used more than 5 minutes ago`() {
        for (passkeyAt in listOf(null, Instant.now().minus(Duration.ofMinutes(6)))) {
            publish(passkeyAt).andExpect {
                status { isUnauthorized() }
                header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("""error="insufficient_user_authentication"""")) }
                header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("max_age=300")) }
            }
        }
        github.verify(0, postRequestedFor(urlPathEqualTo("$repo/git/commits")))
    }

    @Test
    fun `refuses to publish when the data repository moved since the curator looked`() {
        approveSome()
        publish(base = "b".repeat(40)).andExpect { status { isPreconditionFailed() } }
        github.verify(0, postRequestedFor(urlPathEqualTo("$repo/git/commits")))
    }

    @Test
    fun `commits the files whole on the publish branch, built on main, and opens its pull request`() {
        approveSome()
        stubWrites()
        publish().andExpect {
            status { isOk() }
            jsonPath("$.pullRequest.number") { value(4) }
            jsonPath("$.pullRequest.url") { value("https://github.com/pr/4") }
        }
        github.verify(
            postRequestedFor(urlPathEqualTo("$repo/git/trees")).withRequestBody(
                equalToJson(
                    """
                    {"base_tree": "tree-of-main", "tree": [
                      {"path": "data/occupations.jsonl", "mode": "100644", "type": "blob",
                       "content": "{\"id\":\"Q2478141\"}\n{\"id\":\"Q36180\",\"domain\":\"ARTS\"}\n"},
                      {"path": "data/corrections.jsonl", "mode": "100644", "type": "blob",
                       "content": "{\"id\":\"Q535\",\"born\":1801,\"occupations\":[\"Q82955\"]}\n"}
                    ]}
                    """,
                ),
            ),
        )
        github.verify(postRequestedFor(urlPathEqualTo("$repo/git/commits")).withRequestBody(equalToJson("""{"tree": "new-tree", "parents": ["$base"]}""", true, true)))
        github.verify(postRequestedFor(urlPathEqualTo("$repo/git/refs")).withRequestBody(equalToJson("""{"ref": "refs/heads/publish", "sha": "new-commit"}""")))
        github.verify(postRequestedFor(urlPathEqualTo("$repo/pulls")).withRequestBody(equalToJson("""{"head": "publish", "base": "main"}""", true, true)))
        // Every call as the App's installation.
        github.verify(getRequestedFor(urlPathEqualTo("$repo/contents/data/people.jsonl")).withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer ghs_installation")))
    }

    @Test
    fun `publishing again moves the open pull request's branch, and an empty file goes`() {
        approve(OccupationDomain("Q36180", "writer"), OccupationDomain("Q36180", "writer", domain = OccupationChoice.ARTS))
        stubWrites(openPullRequest = true)
        publish().andExpect { jsonPath("$.pullRequest.number") { value(3) } }
        github.verify(
            postRequestedFor(urlPathEqualTo("$repo/git/trees"))
                .withRequestBody(equalToJson("""{"tree": [{}, {"path": "data/corrections.jsonl", "sha": null}]}""", true, true)),
        )
        github.verify(patchRequestedFor(urlPathEqualTo("$repo/git/refs/heads/publish")).withRequestBody(equalToJson("""{"sha": "new-commit", "force": true}""")))
        github.verify(patchRequestedFor(urlPathEqualTo("$repo/pulls/3")))
        github.verify(0, postRequestedFor(urlPathEqualTo("$repo/pulls")))
    }

    @Test
    fun `with nothing left to publish, closes the open pull request and commits nothing`() {
        file("data/corrections.jsonl", null)
        stubWrites(openPullRequest = true)
        publish().andExpect {
            status { isOk() }
            jsonPath("$.pullRequest") { doesNotExist() }
        }
        github.verify(patchRequestedFor(urlPathEqualTo("$repo/pulls/3")).withRequestBody(equalToJson("""{"state": "closed"}""")))
        github.verify(0, postRequestedFor(urlPathEqualTo("$repo/git/commits")))
    }

    @Test
    fun `answers 503 with what GitHub said when it refuses`() {
        github.stubFor(get("$repo/git/ref/heads/main").willReturn(aResponse().withStatus(403).withBody("""{"message": "Resource not accessible by integration"}""")))
        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value(containsString("Resource not accessible")) }
        }
    }

    @Test
    fun `signs in to GitHub as the App, with a JWT signed by its key and issued by its client id`() {
        GitHub(RestClient.builder(), properties).head("main")
        val app = github.findAll(getRequestedFor(urlPathEqualTo("$repo/installation"))).single().getHeader(HttpHeaders.AUTHORIZATION)
        val jwt = SignedJWT.parse(app.removePrefix("Bearer "))
        assertTrue(jwt.verify(RSASSAVerifier(appKey)))
        assertEquals("Iv23-test-app", jwt.jwtClaimsSet.issuer)
        assertTrue(jwt.jwtClaimsSet.expirationTime.toInstant() <= Instant.now().plus(Duration.ofMinutes(10)))
    }

    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val appKey = keys.public as RSAPublicKey

        /** The App's private key, as the curator converts it: PKCS#8 PEM, in a file of its own. */
        private val keyFile: Path = Files.createTempFile("github-app", ".pem").also {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded)
            Files.writeString(it, "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n")
            it.toFile().deleteOnExit()
        }

        @JvmStatic
        @DynamicPropertySource
        fun key(registry: DynamicPropertyRegistry) {
            registry.add("app.publish.github.private-key") { keyFile.toString() }
        }
    }
}
