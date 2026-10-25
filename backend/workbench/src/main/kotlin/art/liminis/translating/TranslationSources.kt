package art.liminis.translating

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.publishing.DataFiles
import art.liminis.publishing.GitHub
import art.liminis.publishing.MomentFiles
import art.liminis.publishing.PublishProperties
import art.liminis.release.AnecdoteRecord
import art.liminis.support.UnavailableException

/**
 * The records of `data/` to translate, with the English publishing will write: the data repository's `main`, with the
 * headers, stories, anecdotes and connections approved since in it. French translated from them still matches once they are
 * published.
 */
@Component
class TranslationSources(
    private val github: GitHub,
    private val publish: PublishProperties,
    private val claims: ClaimRepository,
    private val momentFiles: MomentFiles,
    private val dataFiles: DataFiles,
    private val jsonMapper: JsonMapper,
) {

    /** The commit of the data repository's `main` the records are read from. */
    fun base(): String = github.head(publish.baseBranch) ?: throw UnavailableException("The data repository has no branch ${publish.baseBranch}")

    fun records(base: String): List<DataRecord> = RecordKind.entries.flatMap { records(base, it) }

    /** The record [key] names, or nothing. A person may be named by Q-id, `person:Q535`: the one written by hand with it. */
    fun record(base: String, key: String): DataRecord? {
        val kind = RecordKind.of(key)
        val records = if (kind == RecordKind.MOMENT) listOfNotNull(moment(base, key.removePrefix("moment:"))) else records(base, kind)
        val qid = key.removePrefix("person:").takeIf { kind == RecordKind.PERSON && QID.matches(it) }
        return records.firstOrNull { it.key == key || (qid != null && it.node["qid"]?.asString() == qid) }
    }

    private fun records(base: String, kind: RecordKind): List<DataRecord> = when (kind) {
        RecordKind.MOMENT -> github.files(kind.path.removeSuffix("/"), base).filter { it.endsWith(".json") }.mapNotNull { moment(base, it.removeSuffix(".json")) }
        RecordKind.ANECDOTE -> {
            // Rebuilt from their claims, as publishing writes them, with the French main has for them.
            val published = lines(github.file(kind.path, base)).associateBy { it["id"]?.asString() }
            dataFiles.anecdotes().filter { it.source != null }.map { approved ->
                val record = approved.anecdote
                val node = jsonMapper.valueToTree<ObjectNode>(AnecdoteRecord(approved.id, record.person, record.year, record.text, approved.source!!))
                DataRecord(kind, published[approved.id]?.let { RecordTexts.carry(kind, it, node) } ?: node)
            }
        }
        RecordKind.CONNECTION -> {
            // Rebuilt from their claims too, once those written by hand were imported; else as main has them.
            val published = lines(github.file(kind.path, base))
            val approved = dataFiles.connections()
            if (approved.isEmpty()) return published.map { DataRecord(kind, it) }
            val french = published.associateBy { DataRecord(kind, it).key }
            approved.map { connection ->
                val node = jsonMapper.valueToTree<ObjectNode>(connection.record)
                val record = DataRecord(kind, node)
                DataRecord(kind, french[record.key]?.let { RecordTexts.carry(kind, it, node) } ?: node)
            }
        }
        else -> lines(github.file(kind.path, base)).map { DataRecord(kind, it) }
    }

    /** The moment [id]'s file with its approved header and story in it, as publishing writes them. */
    private fun moment(base: String, id: String): DataRecord? {
        val text = github.file("${RecordKind.MOMENT.path}$id.json", base) ?: return null
        val page = approved<MomentPage>(ClaimType.MOMENT_PAGE).firstOrNull { it.moment == id }
        val story = approved<Story>(ClaimType.STORY).firstOrNull { it.moment == id }
        val written = if (page == null && story == null) text else momentFiles.write(text, page, story)
        val node = momentFiles.node(written)
        // French written for the English main has, not for an approved header or story's: none to build on.
        val english = RecordTexts.english(RecordKind.MOMENT, node) == RecordTexts.english(RecordKind.MOMENT, momentFiles.node(text))
        return DataRecord(RecordKind.MOMENT, if (english) node else RecordTexts.withFrench(RecordKind.MOMENT, node, null), id)
    }

    private inline fun <reified T> approved(type: ClaimType): List<T> =
        claims.findByTypeAndStatusOrderById(type, ClaimStatus.APPROVED).map { it.payload as T }

    private fun lines(text: String?): List<ObjectNode> = text.orEmpty().lines().filter { it.isNotBlank() }.map { jsonMapper.readTree(it) as ObjectNode }

    private companion object {
        val QID = Regex("Q[1-9][0-9]*")
    }
}
