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
import java.net.URLDecoder

/** An image found on Commons, as a card or a moment would show it, with a small copy to choose it by. */
data class FoundPainting(val painting: Painting, val title: String, val thumbnail: String, val width: Int, val height: Int)

/** The images found that a card or a moment may show, and how many others were left out, under which licences. */
data class PaintingSearch(val paintings: List<FoundPainting>, val leftOut: Int, val leftOutLicences: List<String>)

/**
 * Finds paintings on Wikimedia Commons for a story card or a moment: by searching, or the one file a link or a file
 * name points at. Only public domain and CC0 images are kept, as none of them asks for attribution: the site shows
 * no licence. Each gets a credit from what Commons says of its artist, title and date, for the curator to edit.
 */
@Service
class Paintings(private val wikimedia: WikimediaClient) {

    fun find(q: String, limit: Int): PaintingSearch {
        val text = q.trim()
        val files = try {
            fileNamed(text)?.let { listOfNotNull(wikimedia.commonsFile(it, WIDTH)) } ?: wikimedia.commonsSearch(text, limit, WIDTH)
        } catch (e: WikimediaException) {
            throw UnavailableException("Wikimedia Commons did not answer: ${e.message}")
        } catch (e: WikimediaBusyException) {
            throw UnavailableException("Wikimedia Commons is busy: ${e.message}")
        }
        val (free, others) = files.partition { licence(it) != null }
        return PaintingSearch(
            paintings = free.map(::found),
            leftOut = others.size,
            leftOutLicences = others.map { metadata(it, "LicenseShortName")?.let(::plain) ?: "no licence stated" }.distinct(),
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

    /** Public domain or CC0, as Commons' licence templates name them; none for any other licence. */
    private fun licence(file: JsonNode): PaintingLicence? {
        val code = metadata(file, "License")?.lowercase() ?: return null
        return when {
            code == "cc0" -> PaintingLicence.CC0
            code == "pd" || code.startsWith("pd-") -> PaintingLicence.PUBLIC_DOMAIN
            else -> null
        }
    }

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
        val COMMONS_PAGE = Regex("""^https?://commons\.(?:m\.)?wikimedia\.org/wiki/(File:[^?#]+)""", RegexOption.IGNORE_CASE)
        val UPLOAD = Regex("""^https?://upload\.wikimedia\.org/wikipedia/commons/(?:thumb/)?[0-9a-f]/[0-9a-f]{2}/([^/?#]+)""")
        val FILE = Regex("""^file:(.+)$""", RegexOption.IGNORE_CASE)

        /**
         * The Commons file [text] names: a link to its page, a link to the image itself or a copy of it, or `File:`
         * and its name. None for words to search for.
         */
        fun fileNamed(text: String): String? {
            val name = COMMONS_PAGE.find(text)?.groupValues?.get(1)?.removePrefix("File:")?.removePrefix("file:")
                ?: UPLOAD.find(text)?.groupValues?.get(1)
                ?: FILE.find(text)?.groupValues?.get(1)
                ?: return null
            return "File:" + URLDecoder.decode(name.replace("+", "%2B"), Charsets.UTF_8).replace('_', ' ').trim()
        }

        /** Wikimedia adds tracking parameters to the addresses it hands out: the image is the same without them. */
        fun withoutQuery(url: String) = url.substringBefore('?')
    }
}
