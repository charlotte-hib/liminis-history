package dev.wholivedwhen.dataset

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

@ConfigurationProperties("app.dataset")
data class DatasetProperties(val exportDir: String)

/** `manifest.json`: what the fetched dataset holds, and from which import. */
data class DatasetManifest(val format: Int, val import: MappedImport, val counts: DatasetFileCounts)

/** The lines of each of the fetched dataset's files. */
data class DatasetFileCounts(val people: Int, val articles: Int, val places: Int, val occupations: Int)

/**
 * Writes the schema `dataset` to the fetched dataset's files, the input of a release beside `data/`: `manifest.json`,
 * then `people`, `articles`, `places` and `occupations` as gzipped JSON Lines, one row per line, in Q-id order. The
 * same rows always make the same bytes, so pushing them again changes nothing. Replaces the files of the last export.
 */
@Component
class DatasetExport(
    private val dataset: DatasetStore,
    private val jsonMapper: JsonMapper,
    private val properties: DatasetProperties,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // One snapshot of the whole dataset, read in pages.
    private val snapshot = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    /** Exports the dataset into [DatasetProperties.exportDir], and returns its manifest. */
    fun export(): Pair<Path, DatasetManifest> {
        val dir = Path.of(properties.exportDir).toAbsolutePath()
        Files.createDirectories(dir.parent)
        val written = Files.createTempDirectory(dir.parent, "${dir.fileName}-")
        try {
            val manifest = snapshot.execute { write(written) }!!
            dir.toFile().deleteRecursively()
            Files.move(written, dir)
            log.info("Exported {} to {}", manifest, dir)
            return dir to manifest
        } finally {
            written.toFile().deleteRecursively()
        }
    }

    private fun write(dir: Path): DatasetManifest {
        val import = checkNotNull(dataset.mappedImport()) { "Nothing is mapped yet: run a mapping first (wb map)" }
        val counts = DatasetFileCounts(
            people = writeLines(dir.resolve("people.jsonl.gz"), pages { last: PersonRow? -> dataset.peopleAfter(last?.qid ?: "", BATCH) }),
            articles = writeLines(
                dir.resolve("articles.jsonl.gz"),
                pages { last: ArticleRow? -> dataset.articlesAfter((last?.person ?: "") to (last?.language ?: ""), BATCH) },
            ),
            places = writeLines(dir.resolve("places.jsonl.gz"), sequenceOf(dataset.places())),
            occupations = writeLines(dir.resolve("occupations.jsonl.gz"), sequenceOf(dataset.occupations())),
        )
        return DatasetManifest(FORMAT, import, counts).also {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(), it)
        }
    }

    /** The pages [next] reads, each from the last row of the one before, until one comes back short. */
    private fun <T : Any> pages(next: (last: T?) -> List<T>): Sequence<List<T>> =
        generateSequence(next(null)) { page -> if (page.size < BATCH) null else next(page.last()) }

    /** Writes each row of [pages] as a line of JSON, gzipped. Returns how many. */
    private fun writeLines(file: Path, pages: Sequence<List<Any>>): Int {
        var lines = 0
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use { out ->
            pages.flatten().forEach {
                out.write(jsonMapper.writeValueAsString(it))
                out.write("\n")
                lines++
            }
        }
        return lines
    }

    private companion object {
        /** The files' format: raised when a change would break a reader of the last one. */
        const val FORMAT = 1
        const val BATCH = 1000
    }
}
