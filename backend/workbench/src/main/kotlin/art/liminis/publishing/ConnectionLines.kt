package art.liminis.publishing

import art.liminis.curation.ClaimRules
import art.liminis.curation.Connection
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.release.CitedSourceRecord
import art.liminis.release.ConnectionRecord
import art.liminis.support.UnavailableException

/**
 * The lines of `connections.jsonl` and the claims they come from. A line has no id: it is named by its two people and
 * its kind, `Q296|Q171730:friends`, as its translation is, and the file is sorted by them.
 */
object ConnectionLines {

    fun key(people: List<String>, kind: String) = "${people.joinToString("|")}:$kind"

    fun key(record: ConnectionRecord) = key(record.people, record.kind)

    /** The order of the file: by the first person, then the second, then the kind. */
    val ORDER: Comparator<String> = compareBy<String> { split(it).first[0] }.thenBy { split(it).first[1] }.thenBy { split(it).second }

    /**
     * The claim [record] is, its people named by Q-id: one named by their id in `people.jsonl` by the Q-id written
     * there, in [qids]. Refused when that person has none.
     */
    fun claimOf(record: ConnectionRecord, qids: Map<String, String>): Connection {
        val people = record.people.map { person ->
            if (QID.matches(person)) person
            else qids[person] ?: throw UnavailableException("data/people.jsonl gives $person no Q-id: add the Q-ids of the people written by hand first")
        }
        return Connection(people, record.kind, record.year, record.text, sourceOf(record.source))
    }

    /** The claim's id of each line of [lines], `connections.jsonl` as written. */
    fun claimIds(lines: Collection<ConnectionRecord>, qids: Map<String, String>): List<String> = lines.map { ClaimRules.idOf(claimOf(it, qids)) }

    /** A source as the site names it, as a claim keeps it: a book's page, a programme's episode, else a web page. */
    private fun sourceOf(source: CitedSourceRecord): SourceRef = when {
        source.page != null -> SourceRef(SourceKind.BOOK, title = source.title, page = source.page)
        source.episode != null || source.minute != null ->
            SourceRef(SourceKind.MEDIA, title = source.title, episode = source.episode, minute = source.minute, url = source.url)
        source.url != null -> SourceRef(SourceKind.WEB, title = source.title, url = source.url)
        else -> SourceRef(SourceKind.BOOK, title = source.title)
    }

    private fun split(key: String): Pair<List<String>, String> {
        val (people, kind) = key.split(":", limit = 2)
        return people.split("|") to kind
    }

    private val QID = Regex("Q[1-9][0-9]*")
}
