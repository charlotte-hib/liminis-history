package dev.wholivedwhen.release

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import dev.wholivedwhen.dataset.ArticleRow
import dev.wholivedwhen.dataset.DatasetManifest
import dev.wholivedwhen.dataset.FetchedDataset
import dev.wholivedwhen.dataset.OccupationRow
import dev.wholivedwhen.dataset.PersonRow
import dev.wholivedwhen.dataset.PlaceRow
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.isRegularFile

/**
 * Reads a release's `dataset/`: the files the workbench's export writes (`manifest.json`, then gzipped JSON Lines).
 * Strict like [ReleaseReader]: a format it does not know, or a field it does not know, is an error.
 */
object FetchedDatasetReader {

    /** The files' format this reader understands (`DatasetManifest.format`). */
    const val FORMAT = 1
    private const val ENGLISH = "en"

    private val mapper = jacksonMapperBuilder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    fun read(dir: Path): FetchedDataset {
        val manifest = parse("dataset/manifest.json") { mapper.readValue(file(dir, "manifest.json").toFile(), DatasetManifest::class.java) }
        if (manifest.format != FORMAT) {
            throw InvalidReleaseException("dataset/manifest.json: format ${manifest.format}, this code reads format $FORMAT")
        }
        return FetchedDataset(
            manifest = manifest,
            people = lines<PersonRow>(dir, "people"),
            articles = lines<ArticleRow>(dir, "articles").filter { it.language == ENGLISH }.associateBy { it.person },
            places = lines<PlaceRow>(dir, "places").associateBy { it.qid },
            occupations = lines<OccupationRow>(dir, "occupations").associateBy { it.qid },
        )
    }

    private inline fun <reified T : Any> lines(dir: Path, name: String): List<T> {
        val where = "dataset/$name.jsonl.gz"
        return parse(where) {
            GZIPInputStream(Files.newInputStream(file(dir, "$name.jsonl.gz"))).use { input ->
                mapper.readerFor(T::class.java).readValues<T>(input).use { it.readAll() }
            }
        }
    }

    private fun file(dir: Path, name: String): Path =
        dir.resolve(name).takeIf { it.isRegularFile() } ?: throw InvalidReleaseException("dataset/$name is missing")

    private inline fun <T> parse(where: String, read: () -> T): T =
        try {
            read()
        } catch (e: JacksonException) {
            val line = e.location?.lineNr?.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            throw InvalidReleaseException("$where$line: ${e.originalMessage}")
        }
}
