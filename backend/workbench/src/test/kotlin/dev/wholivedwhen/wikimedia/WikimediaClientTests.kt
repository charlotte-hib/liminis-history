package dev.wholivedwhen.wikimedia

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import org.wiremock.spring.WireMockConfigurationCustomizer
import dev.wholivedwhen.testing.PostgresTestConfiguration
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against a fake Wikimedia (WireMock) that answers with responses recorded from the real one, with the real pace from
 * application.yaml. The stubs ask to retry after 1 second, and the first wait without Retry-After is 200 ms, to keep
 * the tests short.
 */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.wikimedia.wikidata-api=\${wikimedia.base-url}/w/api.php",
        "app.wikimedia.wikipedia-api=\${wikimedia.base-url}/{language}/w/api.php",
        "app.wikimedia.wikidata-sparql=\${wikimedia.base-url}/sparql",
        "app.wikimedia.default-retry-wait=200ms",
        // Shorter than the slow answers below, which arrive all the same: the client has a read timeout of its own.
        "spring.http.clients.read-timeout=100ms",
    ],
)
@EnableWireMock(
    ConfigureWireMock(
        baseUrlProperties = ["wikimedia.base-url"],
        filesUnderClasspath = "wiremock",
        configurationCustomizers = [WikimediaClientTests.Http1::class],
    ),
)
@Import(PostgresTestConfiguration::class)
class WikimediaClientTests(@Autowired private val wikimedia: WikimediaClient) {

    @InjectWireMock
    private lateinit var server: WireMockServer

    private val wikidata = urlPathEqualTo("/w/api.php")
    private val wikipedia = urlPathEqualTo("/en/w/api.php")
    private val sparql = urlPathEqualTo("/sparql")
    private val entities = aResponse().withHeader("Content-Type", "application/json")
        .withBodyFile("wikidata/wbgetentities-Q1048-Q535.json")
    private val pages = aResponse().withHeader("Content-Type", "application/json")
        .withBodyFile("wikipedia/en-victor-hugo-julius-caesar-emile-zola.json")

    private fun caesarAndHugo() =
        wikimedia.entities(listOf("Q1048", "Q535"), listOf("info", "labels", "aliases", "claims", "sitelinks"), listOf("en", "fr", "ja"), listOf("enwiki", "frwiki"))

    private fun threePages() = wikimedia.pages("en", listOf("Victor_Hugo", "Julius_Caesar", "Émile_Zola"))

    /** When the fake Wikimedia received each request, in milliseconds, in order. */
    private fun received() = server.allServeEvents.map { it.request.loggedDate.time }.sorted()

    @Test
    fun `asks Wikidata for up to 50 entities, with maxlag, and returns them as they came`() {
        server.stubFor(get(wikidata).willReturn(entities))

        val answer = caesarAndHugo()

        assertEquals("Julius Caesar", answer.path("entities").path("Q1048").path("labels").path("en").path("value").asString())
        // Victor Hugo has no English label, only a "mul" one, which was not asked for: his English article's title.
        assertEquals("Victor Hugo", answer.path("entities").path("Q535").path("sitelinks").path("enwiki").path("title").asString())
        server.verify(
            getRequestedFor(wikidata)
                .withQueryParam("action", equalTo("wbgetentities"))
                .withQueryParam("ids", equalTo("Q1048|Q535"))
                .withQueryParam("props", equalTo("info|labels|aliases|claims|sitelinks"))
                .withQueryParam("languages", equalTo("en|fr|ja"))
                .withQueryParam("sitefilter", equalTo("enwiki|frwiki"))
                .withQueryParam("maxlag", equalTo("60"))
                .withQueryParam("format", equalTo("json"))
                .withHeader("User-Agent", containing("WhoLivedWhen/"))
                .withHeader("Accept-Encoding", containing("gzip")),
        )
    }

    @Test
    fun `asks a Wikipedia edition for the intros, page images, URLs and revisions of up to 20 pages`() {
        server.stubFor(get(wikipedia).willReturn(pages))

        val answer = threePages()

        // By the title asked for, though Wikipedia writes it with spaces.
        assertEquals(listOf("Victor_Hugo", "Julius_Caesar", "Émile_Zola"), answer.keys.toList())
        val zola = answer.getValue("Émile_Zola")
        assertEquals("Émile Zola", zola.path("title").asString())
        assertTrue(zola.path("extract").asString().startsWith("Émile Édouard Charles Antoine Zola"))
        assertEquals("Nadar_(atelier_de)_-_Emile_Zola,_13-556535.jpg", zola.path("pageimage").asString())
        assertEquals(400, zola.path("thumbnail").path("width").asInt())
        assertEquals("https://en.wikipedia.org/wiki/%C3%89mile_Zola", zola.path("fullurl").asString())
        server.verify(
            getRequestedFor(wikipedia)
                .withQueryParam("action", equalTo("query"))
                .withQueryParam("prop", equalTo("extracts|pageimages|info"))
                .withQueryParam("exintro", equalTo("1"))
                .withQueryParam("explaintext", equalTo("1"))
                .withQueryParam("piprop", equalTo("thumbnail|name"))
                .withQueryParam("pithumbsize", equalTo("400"))
                .withQueryParam("inprop", equalTo("url"))
                .withQueryParam("titles", equalTo("Victor_Hugo|Julius_Caesar|Émile_Zola"))
                .withQueryParam("redirects", equalTo("1"))
                .withQueryParam("maxlag", equalTo("5"))
                .withQueryParam("format", equalTo("json"))
                .withQueryParam("formatversion", equalTo("2"))
                .withHeader("User-Agent", containing("WhoLivedWhen/")),
        )
    }

    @Test
    fun `asks a Wikipedia edition for the revisions of up to 50 pages, through redirects, leaving out missing ones`() {
        server.stubFor(
            get(wikipedia).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                    .withBodyFile("wikipedia/en-info-sesostris-iii-amenemhat-iii-and-a-missing-page.json"),
            ),
        )

        val revisions = wikimedia.pageRevisions("en", listOf("Sesostris III", "Amenemhat_III", "No such page for Who Lived When"))

        // Sesostris III redirects to Senusret III.
        assertEquals(mapOf("Sesostris III" to 1377432851L, "Amenemhat_III" to 1378375224L), revisions)
        server.verify(
            getRequestedFor(wikipedia)
                .withQueryParam("action", equalTo("query"))
                .withQueryParam("prop", equalTo("info"))
                .withQueryParam("titles", equalTo("Sesostris III|Amenemhat_III|No such page for Who Lived When"))
                .withQueryParam("redirects", equalTo("1"))
                .withQueryParam("maxlag", equalTo("5")),
        )
    }

    @Test
    fun `an answer cut short is refused`() {
        server.stubFor(get(wikipedia).willReturn(okJson("""{"continue":{"excontinue":20,"continue":"||"},"query":{"pages":[]}}""")))

        assertThrows<WikimediaException> { threePages() }
    }

    @Test
    fun `batches beyond the APIs' limits are refused before any request`() {
        assertThrows<IllegalArgumentException> { wikimedia.entities(List(51) { "Q${it + 1}" }, listOf("info"), listOf("en"), listOf("enwiki")) }
        assertThrows<IllegalArgumentException> { wikimedia.entities(emptyList(), listOf("info"), listOf("en"), listOf("enwiki")) }
        assertThrows<IllegalArgumentException> { wikimedia.pages("en", List(21) { "Page $it" }) }
        assertThrows<IllegalArgumentException> { wikimedia.pageRevisions("en", List(51) { "Page $it" }) }

        assertTrue(server.allServeEvents.isEmpty())
    }

    @Test
    fun `after a 429, waits as long as Retry-After asks, then tries again`() {
        server.stubFor(
            get(wikidata).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("waited"),
        )
        server.stubFor(get(wikidata).inScenario("429").whenScenarioStateIs("waited").willReturn(entities))

        assertTrue(caesarAndHugo().path("entities").has("Q1048"))

        val (first, second) = received()
        assertTrue(second - first >= 1000, "retried after ${second - first} ms")
    }

    @Test
    fun `when Wikidata is lagging (maxlag), waits as long as Retry-After asks, then tries again`() {
        server.stubFor(
            get(wikidata).inScenario("maxlag").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withHeader("Retry-After", "1")
                        .withHeader("MediaWiki-API-Error", "maxlag").withBodyFile("wikidata/maxlag.json"),
                )
                .willSetStateTo("waited"),
        )
        server.stubFor(get(wikidata).inScenario("maxlag").whenScenarioStateIs("waited").willReturn(entities))

        assertTrue(caesarAndHugo().path("entities").has("Q535"))

        val (first, second) = received()
        assertTrue(second - first >= 1000, "retried after ${second - first} ms")
    }

    @Test
    fun `when Wikimedia cannot answer for now (502, 503, 504), waits longer at each attempt, then tries again`() {
        listOf(502, 503, 504).forEachIndexed { i, status ->
            server.stubFor(
                get(wikidata).inScenario("down").whenScenarioStateIs(if (i == 0) Scenario.STARTED else "failed $i")
                    .willReturn(aResponse().withStatus(status)).willSetStateTo("failed ${i + 1}"),
            )
        }
        server.stubFor(get(wikidata).inScenario("down").whenScenarioStateIs("failed 3").willReturn(entities))

        assertTrue(caesarAndHugo().path("entities").has("Q1048"))

        val waits = received().zipWithNext { previous, next -> next - previous }
        assertEquals(3, waits.size)
        listOf(200, 400, 800).zip(waits).forEach { (wait, waited) -> assertTrue(waited >= wait - TOLERANCE_MS, "waited $waited ms, not $wait") }
    }

    @Test
    fun `when the connection is lost before the whole answer came, tries again`() {
        listOf(Fault.CONNECTION_RESET_BY_PEER, Fault.MALFORMED_RESPONSE_CHUNK).forEachIndexed { i, fault ->
            server.stubFor(
                get(wikipedia).inScenario("lost").whenScenarioStateIs(if (i == 0) Scenario.STARTED else "lost $i")
                    .willReturn(aResponse().withFault(fault)).willSetStateTo("lost ${i + 1}"),
            )
        }
        server.stubFor(get(wikipedia).inScenario("lost").whenScenarioStateIs("lost 2").willReturn(pages))

        assertEquals(3, threePages().size)
        assertEquals(3, server.allServeEvents.size)
    }

    @Test
    fun `other API errors, and waits longer than the client accepts, fail at once`() {
        server.stubFor(get(wikidata).willReturn(okJson("""{"error":{"code":"no-such-entity","info":"Could not find an entity with the ID \"Q0\"."}}""")))
        val error = assertThrows<WikimediaException> { caesarAndHugo() }
        assertTrue("no-such-entity" in error.message!!, error.message)

        server.stubFor(get(wikipedia).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600")))
        assertThrows<WikimediaException> { threePages() }

        assertEquals(2, server.allServeEvents.size)
    }

    @Test
    fun `callers in parallel are served one at a time`() {
        server.stubFor(get(wikipedia).willReturn(pages.withFixedDelay(SLOW_ANSWER_MS)))

        Executors.newVirtualThreadPerTaskExecutor().use { executor -> List(3) { executor.submit { threePages() } }.forEach { it.get() } }

        // Each request reaches the server only once the one before it has been answered.
        received().zipWithNext { previous, next -> assertTrue(next - previous >= SLOW_ANSWER_MS - TOLERANCE_MS, "${next - previous} ms apart") }
    }

    @Test
    fun `requests go out two a second at most`() {
        server.stubFor(get(wikipedia).willReturn(pages))

        repeat(6) { threePages() }

        // The rate limiter gives one permit per half second, so n requests span at least n - 2 half seconds.
        val times = received()
        assertTrue(times.last() - times.first() >= (times.size - 2) * 500 - TOLERANCE_MS, "${times.size} requests in ${times.last() - times.first()} ms")
    }

    @Test
    fun `asks the query service for SPARQL results, and tells a query it stopped from other failures`() {
        server.stubFor(
            get(sparql).willReturn(
                aResponse().withHeader("Content-Type", "application/sparql-results+json")
                    .withBodyFile("wikidata/sparql-born-2000-to-1901-bce.json"),
            ),
        )
        val rows = wikimedia.sparql("SELECT ?person ?sitelinks WHERE { }").path("results").path("bindings")
        assertEquals("http://www.wikidata.org/entity/Q19244", rows.first().path("person").path("value").asString())
        server.verify(
            getRequestedFor(sparql)
                .withQueryParam("query", equalTo("SELECT ?person ?sitelinks WHERE { }"))
                .withHeader("Accept", equalTo("application/sparql-results+json"))
                .withHeader("User-Agent", containing("WhoLivedWhen/")),
        )

        server.stubFor(get(sparql).willReturn(aResponse().withStatus(500).withBody("java.util.concurrent.TimeoutException")))
        assertThrows<SparqlTimeoutException> { wikimedia.sparql("SELECT * WHERE { }") }
        server.stubFor(get(sparql).willReturn(aResponse().withStatus(504)))
        assertThrows<SparqlTimeoutException> { wikimedia.sparql("SELECT * WHERE { }") }
        server.stubFor(get(sparql).willReturn(aResponse().withStatus(500).withBody("MalformedQueryException")))
        assertThrows<WikimediaException> { wikimedia.sparql("SELECT * WHERE {") }

        // Overloaded: answered once it is not.
        server.resetAll()
        server.stubFor(get(sparql).inScenario("502").whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(502)).willSetStateTo("up"))
        server.stubFor(get(sparql).inScenario("502").whenScenarioStateIs("up").willReturn(okJson("""{"results":{"bindings":[]}}""")))
        assertTrue(wikimedia.sparql("SELECT * WHERE { }").path("results").path("bindings").isEmpty)
    }

    /** WireMock drops connections only over HTTP/1.1: the client would otherwise talk HTTP/2 to it. */
    class Http1 : WireMockConfigurationCustomizer {
        override fun customize(configuration: WireMockConfiguration, options: ConfigureWireMock) {
            configuration.http2PlainDisabled(true)
        }
    }

    private companion object {
        const val SLOW_ANSWER_MS = 700
        const val TOLERANCE_MS = 20
    }
}
