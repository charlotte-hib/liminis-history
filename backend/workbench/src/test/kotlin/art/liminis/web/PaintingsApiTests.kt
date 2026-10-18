package art.liminis.web

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.testing.PostgresTestConfiguration
import art.liminis.web.WorkbenchSecurity.Companion.RUNS

/** Paintings found on Wikimedia Commons, against a fake Commons answering with responses recorded from the real one. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false", "app.wikimedia.commons-api=\${wikimedia.base-url}/commons/w/api.php"])
@AutoConfigureMockMvc
@EnableWireMock(ConfigureWireMock(baseUrlProperties = ["wikimedia.base-url"], filesUnderClasspath = "wiremock"))
@Import(PostgresTestConfiguration::class)
class PaintingsApiTests(@Autowired private val mockMvc: MockMvc) {

    @InjectWireMock
    private lateinit var commons: WireMockServer

    private val api = urlPathEqualTo("/commons/w/api.php")

    private fun recorded(file: String) = aResponse().withHeader("Content-Type", "application/json").withBodyFile("commons/$file")

    private val greatWave = "File:Tsunami by hokusai 19th century.jpg"

    @Test
    fun `a search lists the images public domain in Europe and CC0, at 1,280 pixels, with a credit to edit, and counts the others`() {
        commons.stubFor(get(api).withQueryParam("gsrsearch", equalTo("Caillebotte Paris street filetype:bitmap")).willReturn(recorded("search-caillebotte.json")))

        mockMvc.get("/api/paintings") { signedIn(); param("q", "  Caillebotte Paris street "); param("limit", "8") }.andExpect {
            status { isOk() }
            jsonPath("$.paintings.length()") { value(4) }
            jsonPath("$.paintings[0].title") { value(startsWith("File:Gustave Caillebotte - Paris Street; Rainy Day")) }
            jsonPath("$.paintings[0].painting.url") { value(matchesPattern("https://thumb\\.wikimedia\\.org/.+/1280px-[^?]+")) }
            jsonPath("$.paintings[0].thumbnail") { value(matchesPattern("https://thumb\\.wikimedia\\.org/.+/330px-[^?]+")) }
            jsonPath("$.paintings[0].painting.source") { value(startsWith("https://commons.wikimedia.org/wiki/File:Gustave_Caillebotte")) }
            jsonPath("$.paintings[0].painting.licence") { value("PUBLIC_DOMAIN") }
            // The English title when Commons gives several; a title taken from the file's name, or an upload's date, left out.
            jsonPath("$.paintings[*].painting.credit") {
                value(
                    contains(
                        "Gustave Caillebotte, Paris Street in Rainy Weather, 1877",
                        "Gustave Caillebotte, Paris Street in Rainy Weather, 1877",
                        "Gustave Caillebotte, Paris Street; Rainy Day, 1877",
                        "Gustave Caillebotte",
                    ),
                )
            }
            jsonPath("$.paintings[0].width") { value(greaterThan(1280)) }
            // Public domain without Commons saying the artist died 70 years ago or more is left out too.
            jsonPath("$.leftOut") { value(4) }
            jsonPath("$.leftOutLicences") { value(contains("CC BY-SA 4.0", "CC BY 2.0", "public domain, the artist's death not stated", "CC BY 3.0")) }
        }
    }

    @Test
    fun `a link to a file's page, to the image or a copy of it, or its name finds that one file`() {
        commons.stubFor(get(api).withQueryParam("titles", equalTo(greatWave)).willReturn(recorded("file-great-wave.json")))

        for (q in listOf(
            "https://commons.wikimedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a5/Tsunami_by_hokusai_19th_century.jpg/1280px-Tsunami_by_hokusai_19th_century.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/a/a5/Tsunami_by_hokusai_19th_century.jpg",
            "file:Tsunami by hokusai 19th century.jpg",
        )) {
            mockMvc.get("/api/paintings") { signedIn(); param("q", q) }.andExpect {
                status { isOk() }
                jsonPath("$.paintings.length()") { value(1) }
                jsonPath("$.paintings[0].title") { value(greatWave) }
                jsonPath("$.paintings[0].painting.url") {
                    value("https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a5/Tsunami_by_hokusai_19th_century.jpg/1280px-Tsunami_by_hokusai_19th_century.jpg")
                }
                jsonPath("$.paintings[0].painting.source") { value("https://commons.wikimedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg") }
                // Commons' title in Japanese and English, and its hidden Wikidata statements, come down to the English.
                jsonPath("$.paintings[0].painting.credit") { value("Katsushika Hokusai, The Great Wave off Kanagawa, between c. 1830 and c. 1832") }
                jsonPath("$.leftOut") { value(0) }
            }
        }
    }

    @Test
    fun `a file under a licence that asks for attribution is left out, its licence named`() {
        val title = "File:\"Paris Street, Rainy Day\" by Gustave Caillebotte - Joy of Museums - Art Institute of Chicago.jpg"
        commons.stubFor(get(api).withQueryParam("titles", equalTo(title)).willReturn(recorded("file-cc-by-sa.json")))

        mockMvc.get("/api/paintings") { signedIn(); param("q", title) }.andExpect {
            status { isOk() }
            jsonPath("$.paintings.length()") { value(0) }
            jsonPath("$.leftOut") { value(1) }
            jsonPath("$.leftOutLicences") { value(contains("CC BY-SA 4.0")) }
        }
    }

    @Test
    fun `a file public domain in the United States only is left out, as the site is read in Europe`() {
        val title = "File:\" A Black Mark for the Squad. A.W.O.L.\" - NARA - 512701.tif"
        commons.stubFor(get(api).withQueryParam("titles", equalTo(title)).willReturn(recorded("file-pd-us.json")))

        mockMvc.get("/api/paintings") { signedIn(); param("q", title) }.andExpect {
            status { isOk() }
            jsonPath("$.paintings.length()") { value(0) }
            jsonPath("$.leftOutLicences") { value(contains("public domain in the United States only")) }
        }
    }

    @Test
    fun `Commons failing answers 503, and the search needs the curation read scope`() {
        commons.stubFor(get(api).willReturn(okJson("""{"error": {"code": "internal_api_error", "info": "Something broke"}}""")))

        mockMvc.get("/api/paintings") { signedIn(); param("q", "Hokusai") }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value(containsString("Something broke")) }
        }
        // An HTTP error Commons answers with, other than the busy ones the client waits out.
        commons.stubFor(get(api).willReturn(aResponse().withStatus(500)))
        mockMvc.get("/api/paintings") { signedIn(); param("q", "Hokusai") }.andExpect { status { isServiceUnavailable() } }
        mockMvc.get("/api/paintings") { signedIn(listOf(RUNS)); param("q", "Hokusai") }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/paintings") { signedIn(); param("q", "H") }.andExpect { status { isBadRequest() } }
    }
}
