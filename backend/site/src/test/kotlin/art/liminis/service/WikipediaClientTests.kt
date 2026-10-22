package art.liminis.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Against a mock Wikipedia, with the real `app.wikipedia` settings from application.yaml. */
@RestClientTest(WikipediaClient::class)
@EnableConfigurationProperties(WikipediaProperties::class)
class WikipediaClientTests(
    @Autowired private val wikipedia: WikipediaClient,
    @Autowired private val server: MockRestServiceServer,
) {

    private val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/%C3%89mile_Zola"

    @Test
    fun `reads the extract, thumbnail and page link of an article`() {
        server.expect(requestTo(summaryUrl))
            .andExpect(header(HttpHeaders.USER_AGENT, "Liminis/0.1 (https://github.com/charlotte-hib/liminis-history)"))
            .andRespond(withSuccess(ZOLA, MediaType.APPLICATION_JSON))

        val summary = wikipedia.summary("Émile_Zola")

        assertEquals(
            ArticleSummary(
                extract = "Émile Zola was a French novelist.",
                thumbnailUrl = "https://upload.wikimedia.org/zola.jpg",
                pageUrl = "https://en.wikipedia.org/wiki/%C3%89mile_Zola",
            ),
            summary,
        )
        server.verify()
    }

    @Test
    fun `reads an article of the French Wikipedia`() {
        server.expect(requestTo("https://fr.wikipedia.org/api/rest_v1/page/summary/%C3%89mile_Zola"))
            .andRespond(withSuccess(ZOLA, MediaType.APPLICATION_JSON))

        assertEquals("Émile Zola was a French novelist.", wikipedia.summary("Émile_Zola", "fr")?.extract)
        server.verify()
    }

    @Test
    fun `waits as long as Wikipedia asks when rate limited, then tries again`() {
        server.expect(requestTo(summaryUrl))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "0"))
        server.expect(requestTo(summaryUrl)).andRespond(withSuccess(ZOLA, MediaType.APPLICATION_JSON))

        assertEquals("Émile Zola was a French novelist.", wikipedia.summary("Émile_Zola")?.extract)
        server.verify()
    }

    @Test
    fun `a missing article or a failing Wikipedia gives no summary instead of an error`() {
        server.expect(requestTo(summaryUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND))
        assertNull(wikipedia.summary("Émile_Zola"))

        server.reset()
        server.expect(requestTo(summaryUrl)).andRespond(withServerError())
        assertNull(wikipedia.summary("Émile_Zola"))
    }

    private companion object {
        val ZOLA = """
            {
              "title": "Émile Zola",
              "extract": "Émile Zola was a French novelist.",
              "thumbnail": { "source": "https://upload.wikimedia.org/zola.jpg", "width": 320 },
              "content_urls": { "desktop": { "page": "https://en.wikipedia.org/wiki/%C3%89mile_Zola" } }
            }
        """.trimIndent()
    }
}
