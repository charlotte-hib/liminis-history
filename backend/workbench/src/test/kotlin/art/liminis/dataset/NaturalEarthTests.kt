package art.liminis.dataset

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import art.liminis.dataset.NaturalEarthFixtures.COUNTRIES
import art.liminis.dataset.NaturalEarthFixtures.DISPUTED_AREAS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The download of Natural Earth's borders, from a fake GitHub (WireMock) serving the fixtures. */
class NaturalEarthTests {

    @RegisterExtension
    private val server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort().usingFilesUnderClasspath("wiremock")).build()

    @TempDir
    private lateinit var dir: Path

    @BeforeEach
    fun serveTheFixtures() {
        listOf(COUNTRIES, DISPUTED_AREAS).forEach {
            server.stubFor(get(urlPathEqualTo("/geojson/$it")).willReturn(aResponse().withBodyFile("natural-earth/$it")))
        }
    }

    private fun naturalEarth(countriesSha256: String = NaturalEarthFixtures.COUNTRIES_SHA256) =
        NaturalEarthFixtures.naturalEarth(NaturalEarthFixtures.properties(dir, "${server.baseUrl()}/geojson", countriesSha256))

    @Test
    fun `downloads the borders on the first run only`() {
        naturalEarth().borders()

        val borders = naturalEarth().borders()

        assertEquals("NL", borders.country("Q55")?.iso)
        server.verify(1, getRequestedFor(urlPathEqualTo("/geojson/$COUNTRIES")))
        server.verify(1, getRequestedFor(urlPathEqualTo("/geojson/$DISPUTED_AREAS")))
        assertEquals(listOf(COUNTRIES, DISPUTED_AREAS), dir.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `refuses a file other than the one pinned, and keeps nothing of it`() {
        val error = assertThrows<IllegalStateException> { naturalEarth(countriesSha256 = "0".repeat(64)).borders() }

        assertTrue(NaturalEarthFixtures.COUNTRIES_SHA256 in error.message!!, error.message)
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `downloads again a file that is not the one pinned`() {
        Files.writeString(dir.resolve(COUNTRIES), "{}")

        val borders = naturalEarth().borders()

        assertEquals("IS", borders.country("Q189")?.iso)
        server.verify(1, getRequestedFor(urlPathEqualTo("/geojson/$COUNTRIES")))
    }
}
