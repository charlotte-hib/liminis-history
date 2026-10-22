package art.liminis.web

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
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
import org.hamcrest.Matchers.not
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
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.curation.Anecdote
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimSources
import art.liminis.curation.ClaimStatus
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Occupation
import art.liminis.curation.OccupationChoice
import art.liminis.curation.MomentPage
import art.liminis.curation.OccupationDomain
import art.liminis.curation.Origin
import art.liminis.curation.Painting
import art.liminis.curation.PaintingLicence
import art.liminis.curation.Payload
import art.liminis.curation.PersonFacts
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.curation.TranslatedText
import art.liminis.curation.Translation
import art.liminis.dataset.DatasetStore
import art.liminis.domain.CardType
import art.liminis.publishing.GitHub
import art.liminis.publishing.PublishProperties
import art.liminis.support.UnavailableException
import art.liminis.testing.PostgresTestConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    @Autowired private val sources: ClaimSources,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val dataset: DatasetStore,
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
        file("data/anecdotes.jsonl", null)
        file("data/people.jsonl", """{"id":"victor-hugo","qid":"Q535","name":"Victor Hugo"}""" + "\n")
    }

    /** The file at [path] on main, or none. */
    private fun file(path: String, text: String?) {
        github.stubFor(
            get(urlPathEqualTo("$repo/contents/$path")).withQueryParam("ref", equalTo(base))
                .willReturn(text?.let { aResponse().withBody(it) } ?: notFound()),
        )
    }

    private val occupationLabels = mapOf("Q36180" to "writer", "Q82955" to "linguist")

    private fun facts(person: String, label: String, born: Int, died: Int, occupations: List<String> = listOf("Q36180")) = PersonFacts(
        person = person, label = label, born = born, bornPrecision = 9, died = died, diedEstimated = false,
        datesApproximate = false, occupations = occupations.map { Occupation(it, occupationLabels[it] ?: it) }, places = emptyList(),
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
            jsonPath("$.maxAge") { value(300) }
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
            // In words, by name: the value in effect is the correction's, else Wikidata's from the claim.
            jsonPath("$.people.length()") { value(2) }
            jsonPath("$.people[0].person") { value("Q1") }
            jsonPath("$.people[0].change") { value("REMOVED") }
            jsonPath("$.people[0].facts[0].fact") { value("NAME") }
            jsonPath("$.people[0].facts[0].before") { value("Someone corrected before") }
            jsonPath("$.people[1].name") { value("Victor Hugo") }
            jsonPath("$.people[1].facts.length()") { value(2) }
            jsonPath("$.people[1].facts[0].fact") { value("BORN") }
            jsonPath("$.people[1].facts[0].before") { value("1802") }
            jsonPath("$.people[1].facts[0].after") { value("1801") }
            jsonPath("$.people[1].facts[1].fact") { value("OCCUPATIONS") }
            jsonPath("$.people[1].facts[1].before") { value("writer, linguist") }
            jsonPath("$.people[1].facts[1].after") { value("linguist") }
            jsonPath("$.occupations[0].label") { value("aristocrat") }
            jsonPath("$.occupations[0].before") { doesNotExist() }
            jsonPath("$.occupations[0].after") { value("NONE") }
            jsonPath("$.occupations[1].label") { value("writer") }
            jsonPath("$.occupations[1].after") { value("ARTS") }
            jsonPath("$.moments.length()") { value(0) }
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
        // The pull request says what changes in words.
        github.verify(
            postRequestedFor(urlPathEqualTo("$repo/pulls"))
                .withRequestBody(matchingJsonPath("$.body", containing("- Victor Hugo (Q535): born 1802 → 1801; occupations writer, linguist → linguist")))
                .withRequestBody(matchingJsonPath("$.body", containing("- Arts and ideas (1): writer")))
                .withRequestBody(matchingJsonPath("$.body", containing("- None (1): aristocrat"))),
        )
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
    fun `leaves out of the commit a file that does not change, even one with no records that main does not have`() {
        approve(OccupationDomain("Q36180", "writer"), OccupationDomain("Q36180", "writer", domain = OccupationChoice.ARTS))
        file("data/corrections.jsonl", null)
        stubWrites()
        publish().andExpect { status { isOk() } }
        val tree = github.findAll(postRequestedFor(urlPathEqualTo("$repo/git/trees"))).single().bodyAsString
        assertTrue("data/occupations.jsonl" in tree)
        assertTrue("data/corrections.jsonl" !in tree, tree)
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
    fun `an App not installed on the data repository says so, rather than passing for a missing branch`() {
        // A fresh client: the one of the context keeps its installation token.
        val notInstalled = GitHub(RestClient.builder(), properties)
        github.stubFor(get("$repo/installation").willReturn(aResponse().withStatus(404).withBody("""{"message": "Not Found"}""")))
        val problem = assertFailsWith<UnavailableException> { notInstalled.head("main") }
        assertTrue("404" in problem.message!!, problem.message)
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

    private fun story(moment: String, person: String) = Story(
        moment = moment,
        cards = listOf(StoryCard(CardType.PERSON, text = "Someone there.", year = 1875, person = person, label = "Someone")),
    )

    private fun page(moment: String) = MomentPage(moment, StoryLine("A new hook."), StoryLine("A line.").let { StoryWorld(it, it, it, it) })

    @Test
    fun `writes an approved page and story into their moment's file, and holds back a story showing a fetched person, not its page`() {
        val paris = """{
  "id": "paris-1870s",
  "hook": "Old hook.",
  "doors": []
}
"""
        file("data/moments/paris-1870s.json", paris)
        file("data/moments/edo-1830s.json", "{}")
        val approved = { payload: Payload -> claims.save(Claim.candidate(payload, Origin.CLAUDE, emptyList()).apply { status = ClaimStatus.APPROVED }) }
        approved(story("paris-1870s", "victor-hugo"))
        approved(page("paris-1870s"))
        approved(story("edo-1830s", "Q5586").let { it.copy(cards = it.cards + StoryCard(CardType.PERSON, text = "A shogun.", person = "Q439717", label = "Tokugawa Ienari")) })
        approved(page("edo-1830s"))
        approved(story("london-1600", "william-shakespeare"))
        file("data/moments/london-1600.json", null)
        file("data/corrections.jsonl", null)

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.files[3].path") { value("data/moments/edo-1830s.json") }
            jsonPath("$.files[3].changes[0].after") { value(containsString(""""hook":"A new hook.""")) }
            jsonPath("$.files[3].changes[0].after") { value(not(containsString("cards"))) }
            jsonPath("$.files[4].path") { value("data/moments/paris-1870s.json") }
            jsonPath("$.files[4].changes[0].id") { value("paris-1870s") }
            jsonPath("$.files[4].changes[0].change") { value("CHANGED") }
            jsonPath("$.files[4].changes[0].before") { value("""{"hook":"Old hook."}""") }
            jsonPath("$.files[4].changes[0].after") { value(containsString(""""cards":[{"type":"PERSON","text":"Someone there.","year":1875,"person":"victor-hugo"}]""")) }
            jsonPath("$.files.length()") { value(5) }
            jsonPath("$.moments[1].moment") { value("paris-1870s") }
            jsonPath("$.moments[1].hook.before") { value("Old hook.") }
            jsonPath("$.moments[1].hook.after") { value("A new hook.") }
            jsonPath("$.moments[1].world.length()") { value(4) }
            jsonPath("$.moments[1].world[0].before") { doesNotExist() }
            jsonPath("$.moments[1].cards[0].change") { value("ADDED") }
            jsonPath("$.moments[1].cards[0].label") { value("Someone") }
            jsonPath("$.moments[1].cards[0].to") { value(1) }
            jsonPath("$.heldBack[0].id") { value("edo-1830s") }
            jsonPath("$.heldBack[0].reason") { value(containsString("2 cards show fetched people")) }
            jsonPath("$.heldBack[0].reason") { value(containsString("card 1, Someone (Q5586); card 2, Tokugawa Ienari (Q439717)")) }
            jsonPath("$.heldBack[1].id") { value("london-1600") }
            jsonPath("$.heldBack[1].reason") { value("There is no data/moments/london-1600.json on the data repository's main") }
        }

        stubWrites()
        publish().andExpect { status { isOk() } }
        val tree = github.findAll(postRequestedFor(urlPathEqualTo("$repo/git/trees"))).single().bodyAsString
        assertTrue("data/moments/paris-1870s.json" in tree && "data/moments/edo-1830s.json" in tree, tree)
        assertTrue("""\"hook\": \"A new hook.\"""" in tree, tree)
    }

    @Test
    fun `matches a story's cards by whom or what they show, and names a card that moved among the others`() {
        val card = { type: String, shows: String?, text: String -> """{"type": "$type"${shows?.let { """, "${type.lowercase()}": "$it"""" }.orEmpty()}, "text": "$text"}""" }
        val paris = """{"id": "paris-1870s", "place": "Paris", "period": "1870s", "hook": "Old hook.", "cards": [
            ${card("SCENE", null, "The city.")}, ${card("PERSON", "victor-hugo", "Hugo.")},
            ${card("PERSON", "emile-zola", "Zola.")}, ${card("LIFE", "paris-laundress", "A laundress.")}]}"""
        file("data/moments/paris-1870s.json", paris)
        file("data/corrections.jsonl", null)
        val story = Story(
            "paris-1870s",
            listOf(
                StoryCard(CardType.PERSON, text = "Zola writes.", person = "emile-zola", label = "Émile Zola"),
                StoryCard(CardType.SCENE, text = "The city."),
                StoryCard(CardType.PERSON, text = "Hugo.", person = "victor-hugo", label = "Victor Hugo"),
                StoryCard(CardType.EVENT, text = "A fair.", event = "paris-fair-1878", label = "The 1878 fair"),
            ),
        )
        claims.save(Claim.candidate(story, Origin.CLAUDE, emptyList()).apply { status = ClaimStatus.APPROVED })

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.moments[0].title") { value("Paris, 1870s") }
            jsonPath("$.moments[0].hook") { doesNotExist() }
            jsonPath("$.moments[0].unchangedCards") { value(2) }
            jsonPath("$.moments[0].cards.length()") { value(3) }
            jsonPath("$.moments[0].cards[0].shows") { value("emile-zola") }
            jsonPath("$.moments[0].cards[0].change") { value("CHANGED") }
            jsonPath("$.moments[0].cards[0].from") { value(3) }
            jsonPath("$.moments[0].cards[0].to") { value(1) }
            jsonPath("$.moments[0].cards[0].moved") { value(true) }
            jsonPath("$.moments[0].cards[0].fields[0].field") { value("TEXT") }
            jsonPath("$.moments[0].cards[0].fields[0].before") { value("Zola.") }
            jsonPath("$.moments[0].cards[0].fields[0].after") { value("Zola writes.") }
            jsonPath("$.moments[0].cards[1].change") { value("ADDED") }
            jsonPath("$.moments[0].cards[1].label") { value("The 1878 fair") }
            jsonPath("$.moments[0].cards[1].to") { value(4) }
            jsonPath("$.moments[0].cards[2].change") { value("REMOVED") }
            jsonPath("$.moments[0].cards[2].type") { value("LIFE") }
            jsonPath("$.moments[0].cards[2].from") { value(4) }
            jsonPath("$.moments[0].cards[2].fields[0].before") { value("A laundress.") }
        }
    }

    @Test
    fun `says which paintings change, by their credits, and writes the claims' paintings as they are`() {
        val art = { name: String, credit: String -> """{"url": "https://example.org/$name.jpg", "credit": "$credit", "source": "https://example.org/$name"}""" }
        val paris = """{"id": "paris-1870s", "place": "Paris", "period": "1870s", "hook": "Old hook.", "art": ${art("rain", "Caillebotte, Rainy Day")},
            "cards": [{"type": "SCENE", "title": "The city", "art": ${art("boulevard", "Pissarro, Boulevard")}},
                      {"type": "PERSON", "person": "victor-hugo", "text": "Hugo.", "art": ${art("hugo", "Bonnat, Hugo")}},
                      {"type": "PERSON", "person": "emile-zola", "text": "Zola."}]}"""
        file("data/moments/paris-1870s.json", paris)
        file("data/corrections.jsonl", null)
        val painting = { name: String, credit: String -> Painting("https://example.org/$name.jpg", credit, "https://example.org/$name", PaintingLicence.PUBLIC_DOMAIN) }
        val line = StoryLine("Old hook.")
        val header = MomentPage("paris-1870s", line, StoryWorld(line, line, line, line), painting("fuji", "Hokusai, Red Fuji"))
        val story = Story(
            "paris-1870s",
            listOf(
                StoryCard(CardType.SCENE, title = "The city"),
                StoryCard(CardType.PERSON, text = "Hugo.", person = "victor-hugo", art = painting("hugo-old", "Bonnat, Hugo")),
                StoryCard(CardType.PERSON, text = "Zola.", person = "emile-zola", art = painting("zola", "Manet, Zola")),
            ),
        )
        for (payload in listOf(header, story)) claims.save(Claim.candidate(payload, Origin.CLAUDE, emptyList()).apply { status = ClaimStatus.APPROVED })

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.moments[0].painting.before") { value("Caillebotte, Rainy Day") }
            jsonPath("$.moments[0].painting.after") { value("Hokusai, Red Fuji") }
            jsonPath("$.moments[0].unchangedCards") { value(0) }
            jsonPath("$.moments[0].cards[0].fields[0].field") { value("PAINTING") }
            jsonPath("$.moments[0].cards[0].fields[0].before") { value("Pissarro, Boulevard") }
            jsonPath("$.moments[0].cards[0].fields[0].after") { doesNotExist() }
            jsonPath("$.moments[0].cards[1].fields[0].after") { value("Bonnat, Hugo (another image)") }
            jsonPath("$.moments[0].cards[2].fields[0].before") { doesNotExist() }
            jsonPath("$.moments[0].cards[2].fields[0].after") { value("Manet, Zola") }
        }

        stubWrites()
        publish().andExpect { status { isOk() } }
        val tree = github.findAll(postRequestedFor(urlPathEqualTo("$repo/git/trees"))).single().bodyAsString
        assertTrue("fuji.jpg" in tree && "hugo-old.jpg" in tree && "zola.jpg" in tree, tree)
        assertFalse("rain.jpg" in tree || "boulevard.jpg" in tree, tree)
        assertFalse("PUBLIC_DOMAIN" in tree, tree)
        val body = github.findAll(postRequestedFor(urlPathEqualTo("$repo/pulls"))).single().bodyAsString
        assertTrue("painting changed" in body && "card 1, scene: painting changed" in body, body)
    }

    /** An approved anecdote, edited from [proposal] to [approved] if given, resting on [refs]. */
    private fun approveAnecdote(proposal: Anecdote, approved: Anecdote = proposal, refs: List<SourceRef>): String = transactions.execute {
        val claim = Claim.candidate(proposal, Origin.MANUAL, emptyList()).apply { status = ClaimStatus.APPROVED; payload = approved }
        sources.attach(claim, refs)
        claims.save(claim).id
    }!!

    @Test
    fun `writes the approved anecdotes with the source the site names, and holds back those it cannot show yet`() {
        CurationFixtures.map(dataset)
        file("data/corrections.jsonl", null)
        file("data/people.jsonl", """{"id":"katsushika-hokusai","qid":"Q5586","name":"Katsushika Hokusai"}""" + "\n" + """{"id":"victor-hugo","qid":"Q535","name":"Victor Hugo"}""" + "\n")
        val daruma = approveAnecdote(
            Anecdote("Q5586", 1804, "He paints Daruma."),
            refs = listOf(SourceRef(SourceKind.WIKIDATA, entity = "Q5586"), SourceRef(SourceKind.WIKIPEDIA, language = "en", title = "Katsushika Hokusai")),
        )
        val exile = approveAnecdote(
            Anecdote("Q535", 1851, "He leaves."), Anecdote("Q535", 1851, "He leaves for Brussels."),
            refs = listOf(SourceRef(SourceKind.BOOK, title = "Choses vues", page = "12", quote = "Never published")),
        )
        val removed = ClaimRules.idOf(Anecdote("Q535", 1802, "He is born."))
        file(
            "data/anecdotes.jsonl",
            """{"id":"$removed","person":"Q535","year":1802,"text":"He is born.","source":{"url":"https://example.org"}}""" + "\n" +
                """{"id":"$exile","person":"Q535","year":1851,"text":"He leaves.","source":{"title":"Choses vues","page":"12"}}""" + "\n",
        )
        val fetched = approveAnecdote(Anecdote("Q504", 1898, "He leaves for London."), refs = listOf(SourceRef(SourceKind.WEB, url = "https://example.org/zola")))
        val unsourced = approveAnecdote(Anecdote("Q535", 1885, "He dies."), refs = emptyList())

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.files[2].path") { value("data/anecdotes.jsonl") }
            jsonPath("$.files[2].changes.length()") { value(3) }
            jsonPath("$.files[2].changes[0].id") { value(removed) }
            jsonPath("$.files[2].changes[0].change") { value("REMOVED") }
            jsonPath("$.files[2].changes[1].after") { value("""{"id":"$exile","person":"Q535","year":1851,"text":"He leaves for Brussels.","source":{"title":"Choses vues","page":"12"}}""") }
            jsonPath("$.files[2].changes[2].after") {
                value("""{"id":"$daruma","person":"Q5586","year":1804,"text":"He paints Daruma.","source":{"url":"https://en.wikipedia.org/wiki/Katsushika_Hokusai"}}""")
            }
            // In words, by the name of whom they are about, then by year.
            jsonPath("$.anecdotes.length()") { value(3) }
            jsonPath("$.anecdotes[0].name") { value("Katsushika Hokusai") }
            jsonPath("$.anecdotes[0].change") { value("ADDED") }
            jsonPath("$.anecdotes[0].fields[2].field") { value("SOURCE") }
            jsonPath("$.anecdotes[0].fields[2].after") { value("https://en.wikipedia.org/wiki/Katsushika_Hokusai") }
            jsonPath("$.anecdotes[1].year") { value(1802) }
            jsonPath("$.anecdotes[1].change") { value("REMOVED") }
            jsonPath("$.anecdotes[2].year") { value(1851) }
            jsonPath("$.anecdotes[2].fields.length()") { value(1) }
            jsonPath("$.anecdotes[2].fields[0].field") { value("TEXT") }
            jsonPath("$.anecdotes[2].fields[0].before") { value("He leaves.") }
            jsonPath("$.anecdotes[2].fields[0].after") { value("He leaves for Brussels.") }
            jsonPath("$.heldBack.length()") { value(2) }
            jsonPath("$.heldBack[?(@.id == '$fetched')].reason") { value("It is about a fetched person, Émile Zola (Q504), whom the release holds once it carries the dataset") }
            jsonPath("$.heldBack[?(@.id == '$unsourced')].reason") { value("It rests on no source the site can name") }
        }

        stubWrites()
        publish().andExpect { status { isOk() } }
        val tree = github.findAll(postRequestedFor(urlPathEqualTo("$repo/git/trees"))).single().bodyAsString
        assertTrue("data/anecdotes.jsonl" in tree && "Never published" !in tree && "London" !in tree, tree)
        github.verify(
            postRequestedFor(urlPathEqualTo("$repo/pulls"))
                .withRequestBody(matchingJsonPath("$.body", containing("- Katsushika Hokusai (Q5586), 1804: added")))
                .withRequestBody(matchingJsonPath("$.body", containing("- Victor Hugo (Q535), 1802: removed")))
                .withRequestBody(matchingJsonPath("$.body", containing("- Victor Hugo (Q535), 1851: text changed"))),
        )
    }

    private fun translated(record: String, label: String, vararg texts: Triple<String, String, String>) =
        claims.save(
            Claim.candidate(Translation(record, label, texts.map { (path, en, fr) -> TranslatedText(path, en, fr) }), Origin.CLAUDE, emptyList())
                .apply { status = ClaimStatus.APPROVED },
        )

    @Test
    fun `writes approved French beside the English, holds back one whose English changed and takes its French away`() {
        file(
            "data/eras.jsonl",
            """{"id":"fr-second-empire","region":"FR","label":"Second Empire","start":1852,"end":1870,"fr":{"label":"Second Empire"}}""" + "\n" +
                """{"id":"fr-third-republic","region":"FR","label":"Third Republic","start":1870,"end":1940}""" + "\n",
        )
        file("data/people.jsonl", """{"id":"victor-hugo","qid":"Q535","name":"Victor Hugo","occupation":"novelist"}""" + "\n")
        jdbc.update("insert into dataset.person (qid, label, sitelinks, born, born_precision, died, died_precision, died_estimated, dates_approximate, frwiki) values ('Q535', 'Victor Hugo', 1, 1802, 9, 1885, 9, false, false, 'Victor Hugo') on conflict (qid) do update set frwiki = 'Victor Hugo'")
        val paris = """{
  "id": "paris-1870s",
  "place": "Paris",
  "period": "1870s",
  "hook": "Old hook.",
  "cards": [
    {
      "type": "SCENE",
      "title": "A republic",
      "art": {
        "url": "u",
        "credit": "c",
        "source": "s"
      }
    }
  ]
}
"""
        file("data/moments/paris-1870s.json", paris)
        translated("era:fr-third-republic", "Third Republic", Triple("label", "Third Republic", "Troisième République"))
        // Translated when the label was something else.
        translated("era:fr-second-empire", "Second Empire", Triple("label", "The Second Empire", "Le Second Empire"))
        translated("person:victor-hugo", "Victor Hugo", Triple("name", "Victor Hugo", "Victor Hugo"), Triple("occupation", "novelist", "romancier"))
        translated(
            "moment:paris-1870s", "Paris, 1870s",
            Triple("place", "Paris", "Paris"), Triple("period", "1870s", "années 1870"), Triple("hook", "Old hook.", "Ancienne accroche."),
            Triple("cards[0].title", "A republic", "Une république"),
        )

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.files[3].path") { value("data/eras.jsonl") }
            jsonPath("$.files[3].changes[0].id") { value("era:fr-second-empire") }
            jsonPath("$.files[3].changes[1].id") { value("era:fr-third-republic") }
            jsonPath("$.files[3].changes[1].after") {
                value("""{"id":"fr-third-republic","region":"FR","label":"Third Republic","start":1870,"end":1940,"fr":{"label":"Troisième République"}}""")
            }
            jsonPath("$.files[4].path") { value("data/people.jsonl") }
            jsonPath("$.files[4].changes[0].after") {
                value("""{"id":"victor-hugo","qid":"Q535","name":"Victor Hugo","occupation":"novelist","fr":{"name":"Victor Hugo","occupation":"romancier","wikipedia":"Victor_Hugo"}}""")
            }
            jsonPath("$.files[5].path") { value("data/moments/paris-1870s.json") }
            jsonPath("$.moments.length()") { value(0) }
            jsonPath("$.translations.length()") { value(4) }
            jsonPath("$.translations[0].record") { value("era:fr-second-empire") }
            jsonPath("$.translations[2].record") { value("moment:paris-1870s") }
            jsonPath("$.translations[0].change") { value("REMOVED") }
            jsonPath("$.translations[1].change") { value("ADDED") }
            jsonPath("$.translations[2].texts[1].path") { value("period") }
            jsonPath("$.translations[2].texts[1].after") { value("années 1870") }
            jsonPath("$.heldBack[0].id") { value("era:fr-second-empire") }
            jsonPath("$.heldBack[0].reason") { value(containsString("Its English changed")) }
        }

        stubWrites()
        publish().andExpect { status { isOk() } }
        val tree = github.findAll(postRequestedFor(urlPathEqualTo("$repo/git/trees"))).single().bodyAsString
        assertTrue("""\"title\": \"A republic\",\n      \"art\"""" in tree && """\"fr\": {\n        \"title\": \"Une république\"""" in tree, tree)
        val body = github.findAll(postRequestedFor(urlPathEqualTo("$repo/pulls"))).single().bodyAsString
        assertTrue("**French** (4)" in body && "Paris, 1870s (moment:paris-1870s): translated" in body, body)
    }

    @Test
    fun `a record whose new French waits for review loses its old French, and an anecdote's French already on main is no change`() {
        file("data/people.jsonl", """{"id":"katsushika-hokusai","qid":"Q5586","name":"Katsushika Hokusai"}""" + "\n")
        file("data/eras.jsonl", """{"id":"fr-third-republic","region":"FR","label":"The Republic","start":1870,"end":1940,"fr":{"label":"Troisième République"}}""" + "\n")
        // Translated again since the label changed: waiting for review.
        claims.save(
            Claim.candidate(Translation("era:fr-third-republic", "The Republic", listOf(TranslatedText("label", "The Republic", "La République"))), Origin.CLAUDE, emptyList()),
        )
        val daruma = approveAnecdote(Anecdote("Q5586", 1804, "He paints Daruma."), refs = listOf(SourceRef(SourceKind.WEB, url = "https://example.org/daruma")))
        file(
            "data/anecdotes.jsonl",
            """{"id":"$daruma","person":"Q5586","year":1804,"text":"He paints Daruma.","source":{"url":"https://example.org/daruma"},"fr":{"text":"Il peint Daruma."}}""" + "\n",
        )
        translated("anecdote:$daruma", "He paints Daruma.", Triple("text", "He paints Daruma.", "Il peint Daruma."))

        mockMvc.get("/api/publication") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.translations.length()") { value(1) }
            jsonPath("$.translations[0].record") { value("era:fr-third-republic") }
            jsonPath("$.translations[0].change") { value("REMOVED") }
            jsonPath("$.heldBack.length()") { value(0) }
            jsonPath("$.files[2].changes.length()") { value(0) }
        }
    }
}
