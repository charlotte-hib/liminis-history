package art.liminis.paintings

import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import art.liminis.curation.Painting
import art.liminis.curation.PaintingLicence
import art.liminis.support.UnavailableException
import art.liminis.wikimedia.WikimediaBusyException
import art.liminis.wikimedia.WikimediaClient
import art.liminis.wikimedia.WikimediaException
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.springframework.web.client.RestClientException
import java.net.URLDecoder

/** An image found on Commons, as a card or a moment would show it, with a small copy to choose it by. */
data class FoundPainting(val painting: Painting, val title: String, val thumbnail: String, val width: Int, val height: Int)

/** The images found that a card or a moment may show, and how many others were left out, under which licences. */
data class PaintingSearch(val paintings: List<FoundPainting>, val leftOut: Int, val leftOutLicences: List<String>)

/**
 * Finds paintings on Wikimedia Commons for a story card or a moment: by searching, or the one file a link or a file
 * name points at. Only CC0 images and those in the public domain are kept, as none of them asks for attribution: the
 * site shows no licence. A file public domain in the United States only is left out, as the site is read in Europe:
 * a work published there before 1931 can still be under copyright in Europe, 70 years after its artist's death. Each
 * gets a credit from what Commons says of its artist, title and date, for the curator to edit.
 */
@Service
class Paintings(private val wikimedia: WikimediaClient) {

    fun find(q: String, limit: Int): PaintingSearch {
        val text = q.trim()
        val files = try {
            fileNamed(text)?.let { listOfNotNull(wikimedia.commonsFile(it, WIDTH)) } ?: wikimedia.commonsSearch(text, limit, WIDTH)
        } catch (e: WikimediaBusyException) {
            throw UnavailableException("Wikimedia Commons is busy: ${e.message}")
        } catch (e: RuntimeException) {
            // An error Commons answered with, a failed request, or the shared pace's wait running out.
            if (e !is WikimediaException && e !is RestClientException && e !is RequestNotPermitted && e !is BulkheadFullException) throw e
            throw UnavailableException("Wikimedia Commons did not answer: ${e.message}")
        }
        val (free, others) = files.partition { licence(it) != null }
        return PaintingSearch(
            paintings = free.map(::found),
            leftOut = others.size,
            leftOutLicences = others.map(::otherLicence).distinct(),
        )
    }

    private fun found(file: JsonNode): FoundPainting {
        val info = file.path("imageinfo").path(0)
        val url = withoutQuery(info.path("thumburl").asString(null) ?: info.path("url").asString())
        val title = file.path("title").asString()
        return FoundPainting(
            painting = Painting(url, credit(file, title), info.path("descriptionurl").asString(), licence(file)),
            title = title,
            thumbnail = url.replace("/${WIDTH}px-", "/${THUMBNAIL}px-"),
            width = info.path("width").asInt(),
            height = info.path("height").asInt(),
        )
    }

    /**
     * CC0, or public domain: Commons' licence `pd`, unless its hidden categories give only a reason that holds in the
     * United States (`PD-US-expired`, `PD-1996`…) and no PD-old one (the artist dead 70 years or more). Old works are
     * often marked by other templates (`PD-Art (Yorck Project)`, `PD-Japan`, the Public Domain Mark alone), so a PD-old
     * template is not asked for. None for any other licence.
     */
    private fun licence(file: JsonNode): PaintingLicence? {
        val code = metadata(file, "License")?.lowercase() ?: return null
        return when {
            code == "cc0" -> PaintingLicence.CC0
            (code == "pd" || code.startsWith("pd-")) && !unitedStatesOnly(file) -> PaintingLicence.PUBLIC_DOMAIN
            else -> null
        }
    }

    /** Why a file was left out, by its licence. */
    private fun otherLicence(file: JsonNode): String {
        val code = metadata(file, "License")?.lowercase()
        return when {
            code == null -> "no licence stated"
            code == "pd" || code.startsWith("pd-") -> "public domain in the United States only"
            else -> metadata(file, "LicenseShortName")?.let(::plain) ?: code
        }
    }

    private fun unitedStatesOnly(file: JsonNode): Boolean {
        val categories = categories(file)
        return categories.any { PD_US.containsMatchIn(it) } && categories.none { PD_OLD.containsMatchIn(it) }
    }

    private fun categories(file: JsonNode) = file.path("categories").values().map { it.path("title").asString() }

    /**
     * "Artist, title, date", from what the file's page on Commons says of them, else the file's name. A title taken
     * from the file's name, and a date with a time of day (when the photo was taken or uploaded), are left out.
     */
    private fun credit(file: JsonNode, title: String): String {
        val artist = described(file, "Artist")?.let { firstLine(it) }?.replace(LIFE_DATES, "")?.trim()
        val name = described(file, "ObjectName")?.let(::titleOf)
        val date = described(file, "DateTimeOriginal")?.let { firstLine(it) }
            ?.removePrefix("First publication:")?.trim()?.removeSuffix(".")?.replace(CIRCA, "c. ")
            ?.takeUnless { TIME_OF_DAY.containsMatchIn(it) }
        val credit = listOfNotNull(artist, name, date).filter { it.isNotBlank() }.distinct().joinToString(", ")
        return credit.ifBlank { title.removePrefix("File:").substringBeforeLast('.') }.take(MAX_CREDIT)
    }

    /** A title in English when Commons gives one in several languages, or links one, else the whole. */
    private fun titleOf(html: String): String {
        val body = visible(html)
        return (body.selectFirst("[lang=en]") ?: body.selectFirst("a[href*=//en.wikipedia.org/]") ?: body.selectFirst("i") ?: body).text().trim()
    }

    /** The text before the first line break, as Commons puts details on the lines after. */
    private fun firstLine(html: String) = visible(html.substringBefore("<br")).text().trim()

    private fun plain(html: String) = visible(html).text().trim()

    // Commons' templates add hidden Wikidata statements ("title QS:P1476,…") and "Edit this at Wikidata" links.
    private fun visible(html: String) =
        Jsoup.parseBodyFragment(html).body().apply { select("[style*=display: none], [style*=display:none], .noprint").remove() }

    private fun metadata(file: JsonNode, name: String): String? =
        file.path("imageinfo").path(0).path("extmetadata").path(name).path("value").asString(null)?.takeIf { it.isNotBlank() }

    /** What the file's page says, not what Commons read in the file itself (its name, a camera's date). */
    private fun described(file: JsonNode, name: String): String? =
        metadata(file, name)?.takeIf { file.path("imageinfo").path(0).path("extmetadata").path(name).path("source").asString() != "mediawiki-metadata" }

    private companion object {
        /** As wide as the site shows a painting, as the release's paintings are. */
        const val WIDTH = 1280
        /** One of the sizes Wikimedia keeps ready, for the grid to choose from. */
        const val THUMBNAIL = 330
        const val MAX_CREDIT = 300

        val LIFE_DATES = Regex("""\s*\(\d{3,4}\s*[-–]\s*\d{3,4}\)""")
        val TIME_OF_DAY = Regex("""\d{1,2}:\d{2}""")
        val CIRCA = Regex("""\b(circa|about|ca\.)\s+""", RegexOption.IGNORE_CASE)
        /** Licence templates among a file's hidden categories: the artist dead 70 years or more; free in the US. */
        val PD_OLD = Regex("""^Category:PD[ -]old|\(PD-old""", RegexOption.IGNORE_CASE)
        val PD_US = Regex("""^Category:PD[ -](US|1996)\b""")

        /** Wikimedia adds tracking parameters to the addresses it hands out: the image is the same without them. */
        fun withoutQuery(url: String) = url.substringBefore('?')
    }
}

private val FILE_PAGE = Regex(
    """^https?://(?:commons\.(?:m\.)?wikimedia|[a-z-]+\.(?:m\.)?wikipedia)\.org/(?:wiki/|w/index\.php\?(?:[^#]*&)?title=)((?:file|image):[^?#&]+)""",
    RegexOption.IGNORE_CASE,
)
private val UPLOAD = Regex("""^https?://(?:upload|thumb)\.wikimedia\.org/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?#]+)""")
private val FILE = Regex("""^(?:file|image):(.+)$""", RegexOption.IGNORE_CASE)

/**
 * The Commons file [text] names, as `File:` and its name with spaces: a link to its page (on Commons or a Wikipedia), a
 * link to the image itself or a copy of it, or `File:` and its name. None for words to search for. A link's name is
 * decoded; a typed name is taken as it is.
 */
internal fun fileNamed(text: String): String? {
    val linked = FILE_PAGE.find(text)?.groupValues?.get(1)?.substringAfter(':') ?: UPLOAD.find(text)?.groupValues?.get(1)
    val name = linked?.let(::decoded) ?: FILE.find(text)?.groupValues?.get(1) ?: return null
    return "File:" + name.replace('_', ' ').trim()
}

/** A name as a link spells it: percent escapes decoded, a malformed one left as it is. */
private fun decoded(name: String): String =
    try {
        URLDecoder.decode(name.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        name
    }
