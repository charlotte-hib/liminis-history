package art.liminis.release

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sample/release.schema.json: current with the records, and every record in sample/ valid against it. */
class ReleaseSchemaTests {

    private val sample = Path.of(System.getProperty("sample.dir") ?: error("Run with Gradle, which sets sample.dir"))
    private val schemaFile = sample.resolve("release.schema.json")
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    @Test
    fun `the schema is generated from the current records`() {
        assertEquals(ReleaseSchema.generate(), schemaFile.readText(), "Run ./gradlew :core:releaseSchema to update it")
    }

    @Test
    fun `every record in sample matches its definition`() {
        val files = mapOf(
            "regions" to "RegionRecord",
            "eras" to "EraRecord",
            "people" to "PersonRecord",
            "occupations" to "OccupationRecord",
            "lives" to "LifeRecord",
            "events" to "EventRecord",
            "connections" to "ConnectionRecord",
            "anecdotes" to "AnecdoteRecord",
        )
        val lines = files.flatMap { (file, record) ->
            val schema = schemaFor(record)
            sample.resolve("$file.jsonl").readLines().mapIndexed { index, line -> "$file.jsonl:${index + 1}" to schema.check(line) }
        }
        val moment = schemaFor("MomentRecord")
        val moments = Files.list(sample.resolve("moments")).use { paths -> paths.sorted().toList() }
            .map { "moments/${it.name}" to moment.check(it.readText()) }

        assertEquals(emptyList(), (lines + moments).filter { it.second.isNotEmpty() })
    }

    @Test
    fun `the schema rejects unknown and missing fields`() {
        assertTrue(schemaFor("RegionRecord").check("""{"id":"FR","name":"France","nmae":"Gaul"}""").isNotEmpty())
        assertTrue(schemaFor("RegionRecord").check("""{"id":"FR"}""").isNotEmpty())
    }

    /** The schema of one record: its definition, with the others it refers to. */
    private fun schemaFor(record: String): Schema {
        val root = JsonMapper().readTree(schemaFile.readText())
        val schema = JsonMapper().createObjectNode()
            .put("\$schema", root.get("\$schema").asString())
            .put("\$ref", "#/\$defs/$record")
        schema.set("\$defs", root.get("\$defs"))
        return registry.getSchema(schema.toString(), InputFormat.JSON)
    }

    private fun Schema.check(json: String): List<String> = validate(json, InputFormat.JSON).map { it.toString() }
}
