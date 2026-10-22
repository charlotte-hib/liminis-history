package art.liminis.translating

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.curation.Actor
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.Claims
import art.liminis.curation.NewDecision
import art.liminis.curation.Origin
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.Verdict
import art.liminis.domain.CardType
import art.liminis.runs.Runs
import art.liminis.curation.ClaimStatus
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Flag
import art.liminis.curation.Translation
import art.liminis.testing.PostgresTestConfiguration
import art.liminis.web.signedIn
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Translating the records of the data repository's main (a fake GitHub), with Claude stood in for. */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.publish.github.api=\${github.base-url}",
        "app.publish.github.client-id=Iv23-test-app",
    ],
)
@EnableWireMock(ConfigureWireMock(baseUrlProperties = ["github.base-url"]))
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class TranslatingTests(
    @Autowired private val translating: Translating,
    @Autowired private val claims: ClaimRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val decisions: Claims,
    @Autowired private val runs: Runs,
) {

    @InjectWireMock
    private lateinit var github: WireMockServer

    @MockitoBean
    private lateinit var translator: Translator

    private val repo = "/repos/charlotte-hib/liminis-history-data"
    private val base = "b".repeat(40)

    /** What each call asked Claude to translate: record, then paths. */
    private val asked = mutableListOf<Map<String, List<String>>>()

    /** The French each call asked Claude to update, by record and path. */
    private val updates = mutableMapOf<String, Map<String, Before>>()

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        asked.clear()
        github.stubFor(get("$repo/installation").willReturn(okJson("""{"id": 7}""")))
        github.stubFor(
            post("/app/installations/7/access_tokens")
                .willReturn(okJson("""{"token": "ghs_installation", "expires_at": "${Instant.now().plus(Duration.ofHours(1))}"}""")),
        )
        github.stubFor(get("$repo/git/ref/heads/main").willReturn(okJson("""{"object": {"sha": "$base"}}""")))
        file("data/regions.jsonl", """{"id":"FR","name":"France"}""" + "\n")
        // Written in French by hand: nothing to translate.
        file("data/eras.jsonl", """{"id":"fr-third-republic","region":"FR","label":"Third Republic","start":1870,"fr":{"label":"Troisième République"}}""" + "\n")
        file("data/people.jsonl", """{"id":"joan-of-arc","qid":"Q7226","name":"Joan of Arc","born":1412,"died":1431,"region":"FR","domain":"POWER","occupation":"military leader"}""" + "\n")
        for (path in listOf("data/lives.jsonl", "data/events.jsonl", "data/connections.jsonl", "data/anecdotes.jsonl")) file(path, null)
        github.stubFor(
            get(urlPathEqualTo("$repo/contents/data/moments")).withQueryParam("ref", equalTo(base))
                .willReturn(okJson("""[{"name": "paris-1870s.json", "type": "file"}, {"name": "README", "type": "file"}]""")),
        )
        moment("A republic born out of defeat.")
        jdbc.update("delete from dataset.person where qid = 'Q7226'")
        jdbc.update(
            "insert into dataset.person (qid, label, label_fr, sitelinks, born, born_precision, died, died_precision, died_estimated, dates_approximate) " +
                "values ('Q7226', 'Joan of Arc', 'Jeanne d''Arc', 1, 1412, 9, 1431, 9, false, false)",
        )
        // Claude answers each text it is asked for with its English, marked French, and keeps the years.
        doAnswer { call ->
            val records = call.getArgument<List<ToTranslate>>(0)
            asked += records.associate { it.record to it.paths }
            records.filter { it.updates.isNotEmpty() }.forEach { updates[it.record] = it.updates }
            TranslatedBatch(
                TranslationDraft(records.map { record ->
                    DraftRecord(record.record, record.paths.map { path -> DraftText(path, "FR " + record.texts.toMap().getValue(path)) })
                }),
                "claude-test", 100, 50,
            )
        }.`when`(translator).translate(ArgumentMatchers.anyList() ?: emptyList(), ArgumentMatchers.anyMap() ?: emptyMap())
    }

    private fun file(path: String, text: String?) {
        github.stubFor(
            get(urlPathEqualTo("$repo/contents/$path")).withQueryParam("ref", equalTo(base))
                .willReturn(text?.let { aResponse().withBody(it) } ?: notFound()),
        )
    }

    private fun moment(hook: String, french: String = "") = file(
        "data/moments/paris-1870s.json",
        """{"id": "paris-1870s", "place": "Paris", "period": "1870s", "hook": "$hook", "cards": [{"type": "SCENE", "title": "Paris in 1871", "text": "A siege."}]$french}""",
    )

    private fun translation(record: String) = claims.findById("translation:$record").orElseThrow()

    @Test
    fun `drafts what has no French, short records first, names from Wikidata, and the rest is left alone`() {
        val result = translating.translate()

        assertTrue(result.startsWith("Translated 3 records (3 added, 0 refreshed, 0 unchanged)"), result)
        assertEquals(listOf("region:FR", "person:joan-of-arc", "moment:paris-1870s"), asked.flatMap { it.keys })
        assertEquals(listOf("occupation"), asked.first { "person:joan-of-arc" in it }.getValue("person:joan-of-arc"))
        val joan = translation("person:joan-of-arc").payload as Translation
        assertEquals(listOf("Jeanne d'Arc", "FR military leader"), joan.texts.map { it.fr })
        val paris = translation("moment:paris-1870s")
        assertEquals(ClaimStatus.CANDIDATE, paris.status)
        assertEquals(
            listOf("place", "period", "hook", "cards[0].title", "cards[0].text"),
            (paris.payload as Translation).texts.map { it.path },
        )
        // The year in the card's title is kept: nothing to look at there.
        assertTrue(paris.flags.none { "cards[0].title" in it.reason }, paris.flags.toString())

        asked.clear()
        assertTrue(translating.translate().startsWith("Translated 0 records"))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a record whose English changed is translated again, its other texts keeping their French`() {
        translating.translate()
        val approved = translation("moment:paris-1870s").apply {
            status = ClaimStatus.APPROVED
            payload = (payload as Translation).let { it.copy(texts = it.texts.map { text -> if (text.path == "place") text.copy(fr = "Paris (édité)") else text }) }
        }
        claims.save(approved)

        moment("A republic born of defeat.")
        asked.clear()
        translating.translate()

        assertEquals(listOf(mapOf("moment:paris-1870s" to listOf("hook"))), asked)
        // Claude updates the French approved for the hook before, rather than translating it anew.
        assertEquals(mapOf("hook" to Before("A republic born out of defeat.", "FR A republic born out of defeat.")), updates["moment:paris-1870s"])
        val again = translation("moment:paris-1870s")
        assertEquals(ClaimStatus.CANDIDATE, again.status)
        assertEquals("Paris (édité)", (again.payload as Translation).texts.first().fr)
        assertEquals("FR A republic born of defeat.", (again.payload as Translation).texts.single { it.path == "hook" }.fr)
        assertTrue(again.flags.any { it.flag == Flag.CONFLICT })
    }

    @Test
    fun `a record translated alone, and French in its file not reused once a claim knows other English`() {
        // Published French, of the hook as it was.
        moment("A republic born of defeat.", """, "fr": {"place": "Paris", "period": "années 1870", "hook": "Une république née de la défaite."}""")
        translating.translate("moment:paris-1870s")
        assertEquals(listOf("moment:paris-1870s"), asked.flatMap { it.keys })
        // The claim knows the file's French; then the hook changes on main.
        moment("A republic, and painters.", """, "fr": {"place": "Paris", "period": "années 1870", "hook": "Une république née de la défaite."}""")
        asked.clear()
        translating.translate("moment:paris-1870s")
        assertTrue("hook" in asked.single().getValue("moment:paris-1870s"), asked.toString())
    }

    @Test
    fun `a record's French, by its key or a person by Q-id, and French written by hand approved at once`() {
        mockMvc.get("/api/translations/person:Q7226") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.record") { value("person:joan-of-arc") }
            jsonPath("$.texts[1].path") { value("occupation") }
            jsonPath("$.claim") { doesNotExist() }
        }
        mockMvc.post("/api/translations/person:joan-of-arc") {
            signedIn()
            contentType = MediaType.APPLICATION_JSON
            content = """{"texts": [{"path": "name", "fr": "Jeanne d'Arc"}]}"""
        }.andExpect { status { isBadRequest() } }
        mockMvc.post("/api/translations/person:joan-of-arc") {
            signedIn()
            contentType = MediaType.APPLICATION_JSON
            content = """{"texts": [{"path": "name", "fr": "Jeanne d'Arc"}, {"path": "occupation", "fr": "cheffe de guerre"}]}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.payload.texts[1].fr") { value("cheffe de guerre") }
        }
        mockMvc.get("/api/translations/person:joan-of-arc") { signedIn() }.andExpect {
            jsonPath("$.claim") { value("translation:person:joan-of-arc") }
        }
        mockMvc.get("/api/translations/era:nowhere") { signedIn() }.andExpect { status { isNotFound() } }
        // Its French approved, a run leaves it alone.
        translating.translate()
        assertTrue(asked.none { "person:joan-of-arc" in it }, asked.toString())
    }

    @Test
    fun `a batch that fails is named in the result, and the others are proposed`() {
        doAnswer { throw TranslatingException("Claude declined to translate region:FR") }
            .`when`(translator).translate(ArgumentMatchers.argThat { records: List<ToTranslate>? -> records?.any { it.record == "region:FR" } == true } ?: emptyList(), ArgumentMatchers.anyMap() ?: emptyMap())

        val result = translating.translate()

        assertTrue(result.endsWith("1 could not be translated, try again: region:FR"), result)
        assertEquals(false, claims.existsById("translation:region:FR"))
        assertEquals(true, claims.existsById("translation:moment:paris-1870s"))
    }

    @Test
    fun `approving a new story of a translated moment starts its French's update`() {
        translating.translate("moment:paris-1870s")
        val story = Story("paris-1870s", listOf(StoryCard(CardType.SCENE, title = "Paris in 1871", text = "A long siege.")))
        claims.save(Claim.candidate(story, Origin.CLAUDE, emptyList()))
        val version = claims.findById("story:paris-1870s").orElseThrow().version!!

        decisions.decide("story:paris-1870s", version, NewDecision(Verdict.APPROVE, null, null), Actor("curator", admin = true))

        val run = runs.lastTranslation("moment:paris-1870s")
        assertEquals("moment:paris-1870s", run?.record)
    }

    companion object {
        /** A key of the App's, made for the tests: the curator's own is not on CI. */
        private val keyFile: Path = Files.createTempFile("github-app", ".pem").also {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            Files.writeString(it, "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n")
        }

        @JvmStatic
        @DynamicPropertySource
        fun key(registry: DynamicPropertyRegistry) {
            registry.add("app.publish.github.private-key") { keyFile.toString() }
        }
    }
}
