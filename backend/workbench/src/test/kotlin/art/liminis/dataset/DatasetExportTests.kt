package art.liminis.dataset

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import art.liminis.testing.PostgresTestConfiguration
import art.liminis.wikidata.RawStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export of the dataset mapped from [RecordedImport] to the fetched dataset's files, read back as 11's compiler will. */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "app.natural-earth.dir=${NaturalEarthFixtures.DIR}",
        "app.natural-earth.countries.sha256=${NaturalEarthFixtures.COUNTRIES_SHA256}",
        "app.natural-earth.disputed-areas.sha256=${NaturalEarthFixtures.DISPUTED_AREAS_SHA256}",
    ],
)
@Import(PostgresTestConfiguration::class)
class DatasetExportTests(
    @Autowired private val export: DatasetExport,
    @Autowired private val mapping: DatasetMapping,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val raw: RawStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {

    companion object {
        @TempDir
        lateinit var exports: Path

        @JvmStatic
        @DynamicPropertySource
        fun exportDir(registry: DynamicPropertyRegistry) {
            registry.add("app.dataset.export-dir") { exports.resolve("dataset-export").toString() }
        }
    }

    private val recorded = RecordedImport(raw, jdbc, jsonMapper)

    @BeforeEach
    fun emptyCache() {
        recorded.emptyCache()
        dataset.clear()
    }

    private fun lines(dir: Path, file: String): List<String> =
        GZIPInputStream(Files.newInputStream(dir.resolve(file))).bufferedReader().readLines()

    @Test
    fun `writes the manifest and every row, in Q-id order, readable as the mapping's rows`() {
        recorded.store()
        mapping.map()

        val (dir, manifest) = export.export()

        assertEquals(exports.resolve("dataset-export"), dir)
        assertEquals(DatasetFileCounts(people = 28, articles = 28 + 22, places = 2, occupations = 9 + 23), manifest.counts)
        assertEquals(1, manifest.import.run)
        assertEquals(manifest, jsonMapper.readValue<DatasetManifest>(dir.resolve("manifest.json").toFile()))
        assertEquals(
            setOf("manifest.json", "people.jsonl.gz", "articles.jsonl.gz", "places.jsonl.gz", "occupations.jsonl.gz"),
            Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList().toSet() },
        )

        val people = lines(dir, "people.jsonl.gz").map { jsonMapper.readValue<PersonRow>(it) }
        assertEquals(people.map { it.qid }.sorted(), people.map { it.qid })
        assertEquals(dataset.peopleAfter("", 100), people)
        val sobekneferu = people.single { it.qid == "Q228951" }
        assertEquals(-1900 to 7, sobekneferu.born to sobekneferu.bornPrecision)
        assertEquals(setOf(PersonFlag.BIRTH_IMPRECISE, PersonFlag.BIRTH_UNREFERENCED, PersonFlag.DEATH_UNREFERENCED, PersonFlag.OCCUPATION_UNREFERENCED), sobekneferu.flags)

        val articles = lines(dir, "articles.jsonl.gz").map { jsonMapper.readValue<ArticleRow>(it) }
        assertEquals(articles.sortedWith(compareBy({ it.person }, { it.language })), articles)
        val french = articles.single { it.person == "Q228951" && it.language == "fr" }
        assertEquals("https://fr.wikipedia.org/wiki/N%C3%A9f%C3%A9rousobek", french.url)
        // No page image: no thumbnail, and the line leaves both out.
        val withoutImage = lines(dir, "articles.jsonl.gz").single { "\"Q888231\"" in it }
        assertTrue("thumbnailUrl" !in withoutImage && "image" !in withoutImage, withoutImage)

        val places = lines(dir, "places.jsonl.gz").map { jsonMapper.readValue<PlaceRow>(it) }
        assertEquals(listOf("Q501259", "Q79"), places.map { it.qid })
        assertEquals(listOf(PlaceCountry("Q796", "IQ")), places.first().countries)

        val occupations = lines(dir, "occupations.jsonl.gz").map { jsonMapper.readValue<OccupationRow>(it) }
        assertEquals(listOf("Q1097498", "Q116"), occupations.single { it.qid == "Q37110" }.parents)
    }

    @Test
    fun `the same rows make the same bytes`() {
        recorded.store()
        mapping.map()
        val (dir, _) = export.export()
        val first = Files.list(dir).use { files -> files.toList().associate { it.fileName.toString() to Files.readAllBytes(it) } }

        mapping.map()
        export.export()

        first.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(file)), file) }
    }

    @Test
    fun `there is nothing to export before a mapping`() {
        assertThrows<IllegalStateException> { export.export() }
    }
}
