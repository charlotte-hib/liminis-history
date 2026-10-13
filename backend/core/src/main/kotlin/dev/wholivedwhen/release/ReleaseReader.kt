package dev.wholivedwhen.release

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Reads a release directory: a JSON Lines file per kind of record, sorted by id, and a JSON file per moment; and, when
 * there is one, the fetched dataset in `dataset/` ([FetchedDatasetReader]). `occupations.jsonl` and `dataset/` may be
 * missing: a release written by hand only has neither.
 * Jackson reads the records; this checks what Jackson cannot: the order and that each id appears once.
 * Strict, so that a typo fails here rather than going missing: unknown fields, missing fields, duplicates and
 * lines out of order are errors that name the file and the line.
 */
object ReleaseReader {

    private val mapper = jacksonMapperBuilder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    fun read(dir: Path): Release {
        if (!dir.isDirectory()) throw InvalidReleaseException("No release directory at ${dir.toAbsolutePath()}")
        return Release(
            regions = lines(dir, "regions") { listOf(it.id) },
            eras = lines(dir, "eras") { listOf(it.id) },
            people = lines(dir, "people") { listOf(it.id) },
            occupations = if (dir.resolve("occupations.jsonl").isRegularFile()) lines(dir, "occupations") { listOf(it.id) } else emptyList(),
            lives = lines(dir, "lives") { listOf(it.id) },
            events = lines(dir, "events") { listOf(it.id) },
            // Connections have no id: they are sorted by their two people, then by kind.
            connections = lines(dir, "connections") { it.people + it.kind },
            moments = moments(dir.resolve("moments")),
            dataset = dir.resolve("dataset").takeIf { it.isDirectory() }?.let(FetchedDatasetReader::read),
        )
    }

    private inline fun <reified T : Any> lines(dir: Path, name: String, key: (T) -> List<String>): List<T> {
        val file = "$name.jsonl"
        val path = dir.resolve(file)
        if (!Files.isRegularFile(path)) throw InvalidReleaseException("$file is missing")
        val records = mutableListOf<T>()
        var previous: List<String>? = null
        parse(file) {
            mapper.readerFor(T::class.java).readValues<T>(path).use { lines ->
                while (lines.hasNextValue()) {
                    val where = "$file:${lines.currentLocation().lineNr}"
                    val record = parse(where) { lines.nextValue() }
                    val current = key(record)
                    previous?.let {
                        val order = compareKeys(it, current)
                        if (order == 0) throw InvalidReleaseException("$where: ${current.joinToString(" ")} appears twice")
                        if (order > 0) throw InvalidReleaseException("$where: ${current.joinToString(" ")} is out of order")
                    }
                    previous = current
                    records += record
                }
            }
        }
        return records
    }

    private fun moments(dir: Path): List<MomentRecord> {
        if (!dir.isDirectory()) throw InvalidReleaseException("moments/ is missing")
        val files = Files.list(dir).use { paths -> paths.filter { it.extension == "json" }.sorted().toList() }
        return files.map { path ->
            val where = "moments/${path.name}"
            val moment = parse(where) { mapper.readValue(path, MomentRecord::class.java) }
            if (moment.id != path.nameWithoutExtension) throw InvalidReleaseException("$where: its id is ${moment.id}")
            moment
        }
    }

    /** Runs [read], naming [where] in any parsing error, with the line when [where] does not have it yet. */
    private inline fun <T> parse(where: String, read: () -> T): T =
        try {
            read()
        } catch (e: JacksonException) {
            val line = e.location?.lineNr?.takeIf { it > 0 && ':' !in where }?.let { ":$it" }.orEmpty()
            throw InvalidReleaseException("$where$line: ${e.originalMessage}")
        }

    private fun compareKeys(a: List<String>, b: List<String>): Int =
        a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
}
