package dev.wholivedwhen.wikidata

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.and
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.HttpServerErrorException
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import dev.wholivedwhen.testing.PostgresTestConfiguration
import dev.wholivedwhen.wikimedia.WikimediaBusyException
import dev.wholivedwhen.wikimedia.WikimediaClient
import dev.wholivedwhen.wikimedia.WikimediaException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import against a fake Wikimedia (WireMock), answering with responses recorded from the real one on 2026-10-09:
 * the 28 people born from 2000 to 1901 BCE with at least 10 sitelinks, their entities, the 11 places and
 * occupations they link to (Egypt's statements cut down to three, to keep the file small), and the 25 classes above
 * those occupations, three levels up; and on 2026-10-10, their 28 English and 22 French Wikipedia articles.
 */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.wikimedia.wikidata-api=\${wikimedia.base-url}/w/api.php",
        "app.wikimedia.wikidata-sparql=\${wikimedia.base-url}/sparql",
        "app.wikimedia.wikipedia-api=\${wikimedia.base-url}/{language}/w/api.php",
        // One slice, the 20th century BCE, as the default plan asks for it.
        "app.wikidata.born-from=-1999",
        "app.wikidata.born-until=-1899",
        // Each call tries twice, and the import resumes once, at once.
        "resilience4j.retry.instances.wikimedia.max-attempts=2",
        "app.wikidata.restarts=1",
        "app.wikidata.restart-pause=10ms",
    ],
)
@EnableWireMock(ConfigureWireMock(baseUrlProperties = ["wikimedia.base-url"], filesUnderClasspath = "wiremock"))
@Import(PostgresTestConfiguration::class)
class WikidataImportTests(
    @Autowired private val wikidataImport: WikidataImport,
    @Autowired private val store: RawStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val wikimedia: WikimediaClient,
    @Autowired private val discovery: Discovery,
    @Autowired private val properties: WikidataProperties,
    @Autowired private val retries: RetryRegistry,
) {

    @InjectWireMock
    private lateinit var server: WireMockServer

    private val sparql = urlPathEqualTo("/sparql")
    private val wikidata = urlPathEqualTo("/w/api.php")

    private fun json(file: String, folder: String = "wikidata"): ResponseDefinitionBuilder =
        aResponse().withHeader("Content-Type", "application/json").withBodyFile("$folder/$file")

    private fun entities(props: String): MappingBuilder =
        get(wikidata).withQueryParam("action", equalTo("wbgetentities")).withQueryParam("props", equalTo(props))

    /** The places and occupations the people link to: Egypt (Q79) among them. */
    private fun linked(): MappingBuilder = entities("info|labels|claims").withQueryParam("ids", containing("Q79"))

    /** The classes above the occupations, level by level ([CLASSES]). */
    private fun classes(level: Int, props: String = "info|labels|claims"): MappingBuilder =
        entities(props).withQueryParam("ids", equalTo(CLASSES[level - 1]))

    /** Wikipedia's [language] edition, asked for [prop] of the pages whose titles start with [titles]. */
    private fun pages(language: String, prop: String, titles: String = ""): MappingBuilder =
        get(urlPathEqualTo("/$language/w/api.php")).withQueryParam("prop", equalTo(prop))
            .withQueryParam("titles", matching("${Regex.escape(titles)}.*"))

    private fun stubFirstImport() {
        server.stubFor(get(sparql).willReturn(json("sparql-born-2000-to-1901-bce.json").withHeader("Content-Type", "application/sparql-results+json")))
        server.stubFor(entities("info|labels|aliases|claims|sitelinks").willReturn(json("wbgetentities-born-2000-to-1901-bce.json")))
        server.stubFor(linked().willReturn(json("wbgetentities-linked-to-born-2000-to-1901-bce.json")))
        (1..3).forEach { server.stubFor(classes(it).willReturn(json("wbgetentities-classes-$it-of-born-2000-to-1901-bce.json"))) }
        // Their articles, 20 a request, in the order of their titles.
        PAGES.forEach { (language, firstTitles) ->
            firstTitles.forEachIndexed { i, title ->
                server.stubFor(pages(language, FULL, title).willReturn(json("$language-born-2000-to-1901-bce-${i + 1}.json", "wikipedia")))
            }
            server.stubFor(pages(language, "info").willReturn(json("$language-born-2000-to-1901-bce-info.json", "wikipedia")))
        }
    }

    private fun count(table: String) = jdbc.queryForObject("select count(*) from raw.$table", Int::class.java)!!

    /** The requests the fake Wikimedia received on [path], in the order they came. */
    private fun requests(path: String) =
        server.allServeEvents.map { it.request }.filter { it.url.startsWith(path) }.sortedBy { it.loggedDate }

    @BeforeEach
    fun emptyCache() {
        jdbc.execute(
            "truncate raw.discovered, raw.entity, raw.import_item, raw.discovery_slice, raw.page, raw.page_item, raw.import_run restart identity",
        )
    }

    @Test
    fun `discovers people, fetches their entities, the places and occupations they link to, the classes above, then their articles`() {
        stubFirstImport()

        val run = wikidataImport.run()

        assertEquals(ImportPhase.DONE, run.phase)
        assertEquals(28, count("discovered"))
        assertEquals(28 + 11 + 25, count("entity"))
        assertEquals(48, jdbc.queryForObject("select sitelinks from raw.discovered where qid = 'Q19244'", Int::class.java))
        assertEquals(
            listOf("Amenemhat III", "2553827743"),
            jdbc.queryForList("select json -> 'labels' -> 'en' ->> 'value', revision::text from raw.entity where qid = 'Q19244'")
                .single().values.toList(),
        )
        assertEquals("Egypt", jdbc.queryForObject("select json -> 'labels' -> 'en' ->> 'value' from raw.entity where qid = 'Q79'", String::class.java))
        assertEquals(counts(requests = 6 + 4, fetched = 64, pagesFetched = 28 + 22), store.counts(run))

        val query = requests("/sparql").single().queryParams["query"]!!.firstValue()
        assertTrue("?sitelinks >= 10" in query, query)
        assertTrue("\"-1999-01-01T00:00:00Z\"^^xsd:dateTime <= ?born && ?born < \"-1899-01-01T00:00:00Z\"^^xsd:dateTime" in query, query)
        val (people, linked) = requests("/w/api.php")
        assertEquals(28, people.queryParams["ids"]!!.firstValue().split("|").size)
        assertEquals("en|fr|ja|mul", people.queryParams["languages"]!!.firstValue())
        assertEquals("enwiki|frwiki", people.queryParams["sitefilter"]!!.firstValue())
        assertEquals(
            "Q110550681|Q110550696|Q116|Q19643|Q2304859|Q37110|Q372436|Q501259|Q79|Q82955|Q863048",
            linked.queryParams["ids"]!!.firstValue(),
        )
        assertEquals(null, linked.queryParams["sitefilter"])
        // Each class once: "monarch", the parent of "pharaoh", is fetched as an occupation already.
        assertEquals(CLASSES, requests("/w/api.php").drop(2).map { it.queryParams["ids"]!!.firstValue() })

        // Every person's articles, by the title of their sitelink, 20 a request.
        assertEquals(listOf(20, 8), requests("/en/w/api.php").map { it.queryParams["titles"]!!.firstValue().split("|").size })
        assertEquals(listOf(20, 2), requests("/fr/w/api.php").map { it.queryParams["titles"]!!.firstValue().split("|").size })
        val (revision, extract) = jdbc.queryForList(
            "select revision::text, json ->> 'extract' from raw.page where language = 'fr' and title = 'Néférousobek'",
        ).single().values.map { it as String }
        assertEquals("229952391", revision)
        assertTrue(extract.startsWith("Néférousobek est la dernière souveraine de la XIIe dynastie"), extract)
        assertEquals(28, jdbc.queryForObject("select count(*) from raw.page where language = 'en'", Int::class.java))
    }

    @Test
    fun `fetches only as many levels of classes as asked for`() {
        stubFirstImport()

        val run = WikidataImport(wikimedia, discovery, store, properties.copy(classLevels = 1), retries).run()

        assertEquals(ImportPhase.DONE, run.phase)
        assertEquals(28 + 11 + 6, count("entity"))
    }

    @Test
    fun `a later run fetches again only the entities whose revision changed`() {
        stubFirstImport()
        wikidataImport.run()
        server.resetRequests()

        // Since then, Amenemhat III was edited, and one of the linked entities was deleted.
        val people = recorded("wbgetentities-born-2000-to-1901-bce-info.json")
        (people.path("entities").path("Q19244") as ObjectNode).put("lastrevid", 2553827743 + 1)
        val linked = recorded("wbgetentities-linked-to-born-2000-to-1901-bce-info.json")
        (linked.path("entities") as ObjectNode).putObject("Q110550696").put("id", "Q110550696").put("missing", "")
        server.stubFor(entities("info").withQueryParam("ids", containing("Q19244")).willReturn(okJson(people.toString())))
        server.stubFor(entities("info").withQueryParam("ids", containing("Q79")).willReturn(okJson(linked.toString())))
        (1..3).forEach { server.stubFor(classes(it, "info").willReturn(json("wbgetentities-classes-$it-of-born-2000-to-1901-bce-info.json"))) }
        // Sobekneferu's English article was edited too, and Âat's French one deleted.
        val english = recorded("en-born-2000-to-1901-bce-info.json", "wikipedia")
        (english.path("query").path("pages").values().single { it.path("title").asString() == "Sobekneferu" } as ObjectNode)
            .put("lastrevid", 1378630327 + 1)
        val french = recorded("fr-born-2000-to-1901-bce-info.json", "wikipedia")
        (french.path("query").path("pages").values().single { it.path("title").asString() == "Âat" } as ObjectNode)
            .removeAll().put("ns", 0).put("title", "Âat").put("missing", true)
        server.stubFor(pages("en", "info").willReturn(okJson(english.toString())))
        server.stubFor(pages("fr", "info").willReturn(okJson(french.toString())))
        server.stubFor(pages("en", FULL, "Sobekneferu").willReturn(json("en-born-2000-to-1901-bce-2.json", "wikipedia")))

        val run = wikidataImport.run()

        // The query, the people's revisions, Amenemhat III, the linked entities' revisions, each level of classes',
        // the English articles' revisions, Sobekneferu's, the French articles' revisions.
        assertEquals(
            counts(requests = 7 + 3, fetched = 1, unchanged = 62, missing = 1, pagesFetched = 1, pagesUnchanged = 48, pagesMissing = 1),
            store.counts(run),
        )
        val fetchedAgain = requests("/w/api.php").filter { it.queryParams["props"]!!.firstValue() != "info" }
        assertEquals(listOf("Q19244"), fetchedAgain.map { it.queryParams["ids"]!!.firstValue() })
        assertEquals(28 + 10 + 25, count("entity"))
        val pagesFetchedAgain = requests("/").filter { it.queryParams["prop"]?.firstValue() == FULL }
        assertEquals(listOf("Sobekneferu"), pagesFetchedAgain.map { it.queryParams["titles"]!!.firstValue() })
        assertEquals(28 + 21, count("page"))
    }

    @Test
    fun `a slice the query service stops is asked for in halves, and a run that failed resumes with the half it missed`() {
        stubFirstImport()
        val wholeSlice = and(containing(FROM_2000_BCE), containing(UNTIL_1900_BCE))
        server.stubFor(get(sparql).atPriority(1).withQueryParam("query", wholeSlice).willReturn(aResponse().withStatus(500).withBody(TIMEOUT)))
        server.stubFor(
            get(sparql).atPriority(2).withQueryParam("query", containing(UNTIL_1900_BCE)).willReturn(aResponse().withStatus(500).withBody(BROKEN)),
        )

        assertThrows<WikimediaException> { wikidataImport.run() }
        assertEquals(3, requests("/sparql").size)
        assertEquals(1, count("discovery_slice"))

        // The query service is back: only the second half is asked for again.
        server.resetRequests()
        server.stubFor(get(sparql).atPriority(2).withQueryParam("query", containing(UNTIL_1900_BCE)).willReturn(okJson(NO_ONE)))
        val run = wikidataImport.run()

        assertEquals(1L, run.id)
        assertEquals(ImportPhase.DONE, run.phase)
        assertTrue(UNTIL_1900_BCE in requests("/sparql").single().queryParams["query"]!!.firstValue())
        assertEquals(2, count("discovery_slice"))
        assertEquals(28, count("discovered"))
    }

    @Test
    fun `a run that stopped half way resumes at the next batch`() {
        stubFirstImport()
        server.stubFor(linked().atPriority(1).willReturn(aResponse().withStatus(500)))
        assertThrows<HttpServerErrorException> { wikidataImport.run() }

        server.resetRequests()
        server.stubFor(linked().atPriority(1).willReturn(json("wbgetentities-linked-to-born-2000-to-1901-bce.json")))
        val run = wikidataImport.run()

        assertEquals(1L, run.id)
        assertEquals(ImportPhase.DONE, run.phase)
        assertEquals(List(4) { "info|labels|claims" }, requests("/w/api.php").map { it.queryParams["props"]!!.firstValue() })
        assertEquals(28 + 11 + 25, count("entity"))
    }

    @Test
    fun `when Wikimedia asks to wait, the run counts it, and a resumed run waits as long too`() {
        stubFirstImport()
        server.stubFor(
            get(sparql).atPriority(1).inScenario("busy").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("waited"),
        )
        val run = wikidataImport.run()
        assertEquals(counts(requests = 7 + 4, busy = 1, fetched = 64, pagesFetched = 50), store.counts(run))
        assertTrue(run.notBefore != null)

        // A run that stopped while asked to wait 2 more seconds.
        server.resetRequests()
        server.stubFor(entities("info").willReturn(json("wbgetentities-born-2000-to-1901-bce-info.json")))
        val stopped = store.currentRun()
        jdbc.update("update raw.import_run set not_before = now() + interval '2 seconds' where id = ?", stopped.id)
        val resumed = System.currentTimeMillis()
        wikidataImport.run()

        assertTrue(requests("/").first().loggedDate.time - resumed >= 1900, "resumed without waiting")
    }

    @Test
    fun `when Wikidata stays behind longer than the retries, the import resumes by itself`() {
        stubFirstImport()
        stubLinkedLagging(times = 2)

        val run = wikidataImport.run()

        assertEquals(ImportPhase.DONE, run.phase)
        assertEquals(28 + 11 + 25, count("entity"))
    }

    @Test
    fun `the import gives up when Wikidata stays behind through every restart without progress`() {
        stubFirstImport()
        stubLinkedLagging(times = 4)

        assertThrows<WikimediaBusyException> { wikidataImport.run() }
        assertEquals(28, count("entity"))
    }

    /** The places and occupations answer `maxlag` [times] times, then as recorded. */
    private fun stubLinkedLagging(times: Int) {
        val lagging = aResponse().withHeader("Content-Type", "application/json").withHeader("Retry-After", "1")
            .withHeader("MediaWiki-API-Error", "maxlag").withBodyFile("wikidata/maxlag.json")
        (0 until times).forEach { i ->
            server.stubFor(
                linked().atPriority(1).inScenario("lag")
                    .whenScenarioStateIs(if (i == 0) Scenario.STARTED else "lagged $i")
                    .willReturn(lagging).willSetStateTo("lagged ${i + 1}"),
            )
        }
    }

    private fun recorded(file: String, folder: String = "wikidata") =
        jsonMapper.readTree(ClassPathResource("wiremock/__files/$folder/$file").inputStream) as ObjectNode

    /** What [RawStore.counts] returns. */
    private fun counts(
        requests: Int, busy: Int = 0, fetched: Int, unchanged: Int = 0, missing: Int = 0,
        pagesFetched: Int, pagesUnchanged: Int = 0, pagesMissing: Int = 0,
    ) = mapOf<String, Any?>(
        "requests" to requests, "busy" to busy, "fetched" to fetched, "unchanged" to unchanged, "missing" to missing,
        "pages_fetched" to pagesFetched, "pages_unchanged" to pagesUnchanged, "pages_missing" to pagesMissing,
    )

    private companion object {
        const val FROM_2000_BCE = "\"-1999-01-01T00:00:00Z\"^^xsd:dateTime <= ?born"
        const val UNTIL_1900_BCE = "?born < \"-1899-01-01T00:00:00Z\"^^xsd:dateTime"
        // What the query service answers when it stops a query after 60 seconds.
        const val TIMEOUT = "java.util.concurrent.ExecutionException: java.util.concurrent.TimeoutException"
        const val BROKEN = "java.lang.IllegalStateException"
        const val NO_ONE = """{"head":{"vars":["person","sitelinks"]},"results":{"bindings":[]}}"""
        const val FULL = "extracts|pageimages|info"
        // The first title of each request for articles, by language: 20 titles a request, in code point order.
        val PAGES = mapOf("en" to listOf("Aat (queen)", "Sargon I"), "fr" to listOf("Amenemhat III", "Yahdun-Lim"))
        // The classes above the occupations, a level a request, as the import asks for them.
        val CLASSES = listOf(
            "Q1097498|Q16511993|Q2478141|Q467|Q48352|Q702269",
            "Q113291098|Q1251441|Q129586023|Q16744001|Q327055|Q7313543|Q84048852|Q895526|Q9584157",
            "Q106559804|Q118947579|Q134480019|Q154954|Q215627|Q28640|Q43229|Q5|Q618532|Q80994",
        )
    }
}
