package art.liminis.translating

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.TranslatedText
import art.liminis.support.slugify

/**
 * The kinds of records of `data/` with texts to translate, each in its file, and the fields of their texts, in the
 * order of the release format. A field in a list of objects is written `cards[].text`: each card's.
 */
enum class RecordKind(val key: String, val path: String, val fields: List<String>) {
    REGION("region", "data/regions.jsonl", listOf("name")),
    ERA("era", "data/eras.jsonl", listOf("label", "governedBy")),
    PERSON("person", "data/people.jsonl", listOf("name", "occupation", "bio")),
    LIFE("life", "data/lives.jsonl", listOf("label", "description")),
    EVENT("event", "data/events.jsonl", listOf("title", "description", "participants[].role")),
    CONNECTION("connection", "data/connections.jsonl", listOf("kind", "text")),
    ANECDOTE("anecdote", "data/anecdotes.jsonl", listOf("text")),
    MOMENT(
        "moment", "data/moments/",
        listOf(
            "place", "period", "hook", "world.governs", "world.everyday", "world.arts", "world.meanwhile",
            "cards[].kicker", "cards[].title", "cards[].text", "doors[].kind", "doors[].text",
        ),
    );

    companion object {
        fun of(record: String): RecordKind = entries.first { record.startsWith("${it.key}:") }
    }
}

/**
 * A record of `data/` as its file holds it, named by kind and id: `event:carmen-premiere`. A moment's id is its file's
 * name, given as [id].
 */
class DataRecord(val kind: RecordKind, val node: ObjectNode, id: String? = null) {

    val key: String = "${kind.key}:${id ?: id()}"

    /** Its texts in English, by path, in the release format's order: `hook`, `cards[2].text`. */
    val texts: List<Pair<String, String>> = RecordTexts.english(kind, node)

    /** What it is, in English, for reading. */
    val label: String
        get() = when (kind) {
            RecordKind.CONNECTION -> "${node["people"].values().joinToString(" and ") { it.asString() }}, ${node.string("kind")}"
            RecordKind.MOMENT -> "${node.string("place")}, ${node.string("period")}"
            RecordKind.ANECDOTE -> node.string("text")!!.let { if (it.length > LABEL) it.take(LABEL - 1) + "…" else it }
            else -> node.string(kind.fields.first())!!
        }

    /** Whether [texts] are this record's texts, each with the English it has now. */
    fun matches(texts: List<TranslatedText>) = texts.map { it.path to it.en } == this.texts

    private fun id(): String = when (kind) {
        // Connections have no id: by their two people and their kind, as the file sorts them.
        RecordKind.CONNECTION -> "${node["people"].values().joinToString("|") { it.asString() }}:${slugify(node.string("kind")!!)}"
        else -> node.string("id")!!
    }

    private companion object {
        const val LABEL = 60
    }
}

/**
 * A record's texts by path, on its JSON as the release format has it, and its French beside them: in a `fr` of its
 * own, last, holding the texts of the record outside its lists, and in a `fr` of each card, door or participant for
 * theirs. A record has French for all its texts or none.
 */
object RecordTexts {

    private val nodes = JsonNodeFactory.instance

    /** The English of [record]'s texts, by path, those it has. */
    fun english(kind: RecordKind, record: ObjectNode): List<Pair<String, String>> = kind.fields.flatMap { field ->
        val (list, inner) = split(field)
        if (list == null) listOfNotNull(at(record, inner)?.let { field to it })
        else record[list]?.values().orEmpty().withIndex().mapNotNull { (index, item) -> at(item, inner)?.let { "$list[$index].$inner" to it } }
    }

    /** The French [record] has now, by path, or nothing when it has none. */
    fun french(kind: RecordKind, record: ObjectNode): Map<String, String>? {
        val french = english(kind, record).mapNotNull { (path, _) ->
            val (container, inner) = locate(record, path)
            container[FR]?.let { at(it, inner) }?.let { path to it }
        }
        return french.toMap().takeIf { it.isNotEmpty() }
    }

    /**
     * [record] with [french] (by path) beside its English in place of the French it had, or none when [french] is
     * null. [extra] goes into the record's own `fr` after its texts: a person's French Wikipedia article.
     */
    fun withFrench(kind: RecordKind, record: ObjectNode, french: Map<String, String>?, extra: Map<String, String> = emptyMap()): ObjectNode {
        val written = record.deepCopy()
        written.remove(FR)
        for (list in kind.fields.mapNotNull { split(it).first }.distinct()) written[list]?.values()?.forEach { (it as? ObjectNode)?.remove(FR) }
        if (french == null) return written
        for ((path, _) in english(kind, written)) {
            val text = french[path] ?: continue
            val (container, inner) = locate(written, path)
            val fr = container[FR] as? ObjectNode ?: nodes.objectNode().also { container.set(FR, it) }
            put(fr, inner, text)
        }
        if (extra.isNotEmpty()) {
            val fr = written[FR] as? ObjectNode ?: nodes.objectNode().also { written.set(FR, it) }
            extra.forEach { (field, text) -> fr.put(field, text) }
        }
        return written
    }

    /** The list a field is in (`cards`), if any, and its path within each item (`text`), or within the record. */
    private fun split(field: String): Pair<String?, String> =
        if ("[]." in field) field.substringBefore("[].") to field.substringAfter("[].") else null to field

    /** The object a path's French goes beside (the record, or an item of one of its lists) and its path within it. */
    private fun locate(record: ObjectNode, path: String): Pair<ObjectNode, String> {
        val match = ITEM.matchEntire(path) ?: return record to path
        val (list, index, inner) = match.destructured
        return (record[list] as ArrayNode)[index.toInt()] as ObjectNode to inner
    }

    private fun at(node: JsonNode, path: String): String? =
        path.split('.').fold(node as JsonNode?) { current, name -> current?.get(name) }?.takeIf { it.isString }?.asString()

    private fun put(node: ObjectNode, path: String, text: String) {
        val names = path.split('.')
        val parent = names.dropLast(1).fold(node) { current, name -> current[name] as? ObjectNode ?: nodes.objectNode().also { current.set(name, it) } }
        parent.put(names.last(), text)
    }

    private const val FR = "fr"
    private val ITEM = Regex("""(\w+)\[(\d+)]\.(.+)""")
}

private fun ObjectNode.string(name: String): String? = get(name)?.takeIf { it.isString }?.asString()
