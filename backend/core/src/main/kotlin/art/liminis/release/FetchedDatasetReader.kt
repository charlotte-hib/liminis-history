package art.liminis.release

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import art.liminis.dataset.ArticleRow
import art.liminis.dataset.DatasetFile
import art.liminis.dataset.DatasetManifest
import art.liminis.dataset.FetchedDataset
import art.liminis.dataset.OccupationRow
import art.liminis.dataset.PersonRow
import art.liminis.dataset.PlaceRow
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
    private const val FRENCH = "fr"

    private val mapper = jacksonMapperBuilder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    fun read(dir: Path): FetchedDataset {
        val manifest = parse(where(DatasetFile.MANIFEST)) {
            mapper.readValue(file(dir, DatasetFile.MANIFEST).toFile(), DatasetManifest::class.java)
        }
        if (manifest.format != FORMAT) {
            throw InvalidReleaseException("${where(DatasetFile.MANIFEST)}: format ${manifest.format}, this code reads format $FORMAT")
        }
        val articles = lines<ArticleRow>(dir, DatasetFile.ARTICLES)
        return FetchedDataset(
            manifest = manifest,
            people = lines<PersonRow>(dir, DatasetFile.PEOPLE),
            articles = articles.filter { it.language == ENGLISH }.associateBy { it.person },
            articlesFr = articles.filter { it.language == FRENCH }.associateBy { it.person },
            places = lines<PlaceRow>(dir, DatasetFile.PLACES).associateBy { it.qid },
            occupations = lines<OccupationRow>(dir, DatasetFile.OCCUPATIONS).associateBy { it.qid },
        )
    }

    private inline fun <reified T : Any> lines(dir: Path, file: DatasetFile): List<T> =
        parse(where(file)) {
            GZIPInputStream(Files.newInputStream(file(dir, file))).use { input ->
                mapper.readerFor(T::class.java).readValues<T>(input).use { it.readAll() }
            }
        }

    private fun file(dir: Path, file: DatasetFile): Path =
        dir.resolve(file.fileName).takeIf { it.isRegularFile() } ?: throw InvalidReleaseException("${where(file)} is missing")

    /** How errors name a file: under the release's dataset/. */
    private fun where(file: DatasetFile) = "dataset/${file.fileName}"

    private inline fun <T> parse(where: String, read: () -> T): T =
        try {
            read()
        } catch (e: JacksonException) {
            val line = e.location?.lineNr?.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            throw InvalidReleaseException("$where$line: ${e.originalMessage}")
        }
}
