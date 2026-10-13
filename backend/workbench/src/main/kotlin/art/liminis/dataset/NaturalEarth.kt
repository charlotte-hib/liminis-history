package art.liminis.dataset

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.operation.distance.DistanceOp
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@ConfigurationProperties("app.natural-earth")
data class NaturalEarthProperties(
    /** Where the downloaded files are kept, so that only the first mapping run downloads them. */
    val dir: Path,
    val countries: NaturalEarthFile,
    val disputedAreas: NaturalEarthFile,
    /** How far off the coast a place outside every country may lie and still take the nearest one. */
    val coastKm: Double,
)

/** A file of Natural Earth's, at a URL that never changes, with the hash of what that URL served when it was chosen. */
data class NaturalEarthFile(val url: URI, val sha256: String)

/** A country as Natural Earth draws it today, with its ISO 3166-1 code and its Wikidata item. */
class Country(val iso: String?, val wikidataId: String?, val geometry: PreparedGeometry)

/** An area Natural Earth marks as disputed, with who administers and who claims it, when it says. */
class DisputedArea(val name: String, val geometry: PreparedGeometry)

/**
 * Natural Earth's de facto borders: which country a point lies in today, and which disputed area, if any. A point just
 * off the coast, which borders at 1:10m cut off, takes the nearest country within [coastKm].
 */
class Borders(countries: List<Country>, disputedAreas: List<DisputedArea>, private val coastKm: Double) {

    private val countryIndex = STRtree().apply { countries.forEach { insert(it.geometry.geometry.envelopeInternal, it) } }
    private val disputedAreaIndex = STRtree().apply { disputedAreas.forEach { insert(it.geometry.geometry.envelopeInternal, it) } }
    private val byWikidataId = countries.filter { it.wikidataId != null }.associateBy { it.wikidataId }

    /** The country Natural Earth gives this Wikidata item, if it is one of its countries. */
    fun country(wikidataId: String): Country? = byWikidataId[wikidataId]

    fun countryAt(coordinates: Coordinates): Country? {
        val point = point(coordinates)
        return countryIndex.query<Country>(point.envelopeInternal).firstOrNull { it.geometry.covers(point) }
            ?: nearestCountry(coordinates)
    }

    fun disputedAreaAt(coordinates: Coordinates): DisputedArea? {
        val point = point(coordinates)
        return disputedAreaIndex.query<DisputedArea>(point.envelopeInternal).firstOrNull { it.geometry.covers(point) }
    }

    private fun nearestCountry(coordinates: Coordinates): Country? {
        // A degree of longitude shrinks towards the poles, to nothing at a pole, where every longitude is near.
        val latitudeDegrees = coastKm / KM_PER_DEGREE
        val longitudeDegrees = (coastKm / (KM_PER_DEGREE * cos(Math.toRadians(coordinates.latitude)))).coerceAtMost(180.0)
        val around = Envelope(
            coordinates.longitude - longitudeDegrees, coordinates.longitude + longitudeDegrees,
            coordinates.latitude - latitudeDegrees, coordinates.latitude + latitudeDegrees,
        )
        val point = point(coordinates)
        return countryIndex.query<Country>(around)
            .map { it to kmBetween(coordinates, DistanceOp.nearestPoints(it.geometry.geometry, point)[0]) }
            .filter { it.second <= coastKm }
            .minByOrNull { it.second }?.first
    }

    private fun point(coordinates: Coordinates) = GEOMETRIES.createPoint(Coordinate(coordinates.longitude, coordinates.latitude))

    /** The great-circle distance, by the haversine formula. */
    private fun kmBetween(a: Coordinates, b: Coordinate): Double {
        val dLatitude = Math.toRadians(b.y - a.latitude)
        val dLongitude = Math.toRadians(b.x - a.longitude)
        val h = sin(dLatitude / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.y)) * sin(dLongitude / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> STRtree.query(envelope: Envelope): List<T> = query(envelope) as List<T>

    private companion object {
        val GEOMETRIES = GeometryFactory()
        const val EARTH_RADIUS_KM = 6371.0088
        const val KM_PER_DEGREE = 111.19
    }
}

/**
 * Natural Earth's countries and disputed areas, downloaded once into `app.natural-earth.dir` and checked against the
 * hashes in application.yaml, which pin the version.
 */
@Component
class NaturalEarth(
    builder: RestClient.Builder,
    private val properties: NaturalEarthProperties,
    private val jsonMapper: JsonMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = builder.build()

    fun borders(): Borders {
        val countries = features(properties.countries).map { (feature, geometry) ->
            Country(feature.path("ISO_A2_EH").asString().takeUnless { it == NO_CODE }, feature.text("WIKIDATAID"), geometry)
        }
        val disputedAreas = features(properties.disputedAreas).map { (feature, geometry) ->
            val name = feature.path("NAME").asString()
            DisputedArea(feature.text("NOTE_BRK")?.let { "$name ($it)" } ?: name, geometry)
        }
        return Borders(countries, disputedAreas, properties.coastKm)
    }

    /** Each feature of [file]: its properties, and its geometry ready to be asked whether it covers a point. */
    private fun features(file: NaturalEarthFile): List<Pair<JsonNode, PreparedGeometry>> {
        val reader = GeoJsonReader()
        return jsonMapper.readTree(download(file).toFile()).path("features").values().map {
            it.path("properties") to PreparedGeometryFactory.prepare(reader.read(it.path("geometry").toString()))
        }
    }

    /** The file, downloaded unless already there with the hash expected. */
    private fun download(file: NaturalEarthFile): Path {
        val path = properties.dir.resolve(file.url.path.substringAfterLast('/'))
        if (Files.exists(path) && sha256(Files.readAllBytes(path)) == file.sha256) return path

        log.info("Downloading {}", file.url)
        val body = checkNotNull(restClient.get().uri(file.url).retrieve().body(ByteArray::class.java)) { "${file.url} sent nothing" }
        val hash = sha256(body)
        check(hash == file.sha256) { "${file.url} has the sha256 $hash, not ${file.sha256}: check what changed before pinning it" }
        Files.createDirectories(properties.dir)
        val downloading = Files.createTempFile(properties.dir, path.fileName.toString(), ".part")
        Files.write(downloading, body)
        return Files.move(downloading, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** A text property, null when Natural Earth leaves it empty. */
    private fun JsonNode.text(name: String): String? = path(name).takeIf { it.isString }?.asString()

    private companion object {
        /** Natural Earth's code for "none". */
        const val NO_CODE = "-99"
    }
}
