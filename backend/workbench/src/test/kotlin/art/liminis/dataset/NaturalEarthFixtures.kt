package art.liminis.dataset

import org.springframework.core.io.ClassPathResource
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.net.URI
import java.nio.file.Path

/**
 * Seven of Natural Earth's countries, as its GeoJSON v5.1.2 has them (Egypt, Iceland, Iraq, Italy, Kosovo, the
 * Netherlands, Serbia), and its disputed area of Kosovo: the real files, with only those features kept.
 */
object NaturalEarthFixtures {
    const val DIR = "src/test/resources/wiremock/__files/natural-earth"
    const val COUNTRIES = "ne_10m_admin_0_countries.geojson"
    const val COUNTRIES_SHA256 = "2e3af1bcb6f57ee288dfe5723eaa66c579abc4bfd8127608953cb1ecdbec52ba"
    const val DISPUTED_AREAS = "ne_10m_admin_0_disputed_areas.geojson"
    const val DISPUTED_AREAS_SHA256 = "3fa93a8adc74282567f100a1c7a379b77d627506399c0ed0011e3aa43bd9bf2c"

    fun properties(dir: Path, baseUrl: String, countriesSha256: String = COUNTRIES_SHA256) = NaturalEarthProperties(
        dir = dir,
        countries = NaturalEarthFile(URI("$baseUrl/$COUNTRIES"), countriesSha256),
        disputedAreas = NaturalEarthFile(URI("$baseUrl/$DISPUTED_AREAS"), DISPUTED_AREAS_SHA256),
        coastKm = 5.0,
    )

    fun naturalEarth(properties: NaturalEarthProperties) =
        NaturalEarth(RestClient.builder(), properties, jacksonMapperBuilder().build())

    /** The borders, read from the fixtures where they are: nothing to download. */
    fun borders(): Borders =
        naturalEarth(properties(Path.of(ClassPathResource("wiremock/__files/natural-earth").uri), "https://example.invalid")).borders()
}
