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
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Flag
import art.liminis.curation.Translation
import art.liminis.testing.PostgresTestConfiguration
import java.time.Duration
import java.time.Instant
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
@Import(PostgresTestConfiguration::class)
class TranslatingTests(
    @Autowired private val translating: Translating,
    @Autowired private val claims: ClaimRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @InjectWireMock
    private lateinit var github: WireMockServer

    @MockitoBean
    private lateinit var translator: Translator

    private val repo = "/repos/charlotte-hib/liminis-history-data"
    private val base = "b".repeat(40)

    /** What each call asked Claude to translate: record, then paths. */
    private val asked = mutableListOf<Map<String, List<String>>>()

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

    private fun moment(hook: String) = file(
        "data/moments/paris-1870s.json",
        """{"id": "paris-1870s", "place": "Paris", "period": "1870s", "hook": "$hook", "cards": [{"type": "SCENE", "title": "Paris in 1871", "text": "A siege."}]}""",
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
        val again = translation("moment:paris-1870s")
        assertEquals(ClaimStatus.CANDIDATE, again.status)
        assertEquals("Paris (édité)", (again.payload as Translation).texts.first().fr)
        assertEquals("FR A republic born of defeat.", (again.payload as Translation).texts.single { it.path == "hook" }.fr)
        assertTrue(again.flags.any { it.flag == Flag.CONFLICT })
    }
}
