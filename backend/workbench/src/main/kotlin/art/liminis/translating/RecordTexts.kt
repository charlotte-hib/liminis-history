package art.liminis.translating

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.TranslatedText

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
        RecordKind.CONNECTION -> "${node["people"].values().joinToString("|") { it.asString() }}:${node.string("kind")}"
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

    /**
     * [record] with the French [from] had, where [record] has none: the record's own French, and each card's, door's or
     * participant's from the one of [from] it is: the item with the same English first, else, for an item that names
     * whom or what it shows, the one showing the same (a card the same person, life or event, in order; a door the same
     * moment; a participant the same person). A scene card, which names nothing, keeps French only with its English
     * unchanged. For a record whose English was written again (a moment's new story, an anecdote rebuilt from its
     * claim): its French stays until a new one is approved.
     */
    fun carry(kind: RecordKind, from: ObjectNode, record: ObjectNode): ObjectNode {
        val written = record.deepCopy()
        if (written[FR] == null) from[FR]?.let { written.set(FR, it.deepCopy()) }
        for ((list, inner) in kind.fields.mapNotNull { field -> split(field).first?.let { it to split(field).second } }.groupBy({ it.first }, { it.second })) {
            val english = { item: JsonNode -> inner.map { at(item, it) } }
            val unused = from[list]?.values().orEmpty().filter { it[FR] != null }.toMutableList()
            val items = written[list]?.values().orEmpty().filterIsInstance<ObjectNode>().filter { it[FR] == null }
            val matched = mutableMapOf<ObjectNode, JsonNode>()
            for (item in items) unused.firstOrNull { english(it) == english(item) && shows(it) == shows(item) }?.let { unused.remove(it); matched[item] = it }
            for (item in items.filter { it !in matched && named(it) }) unused.firstOrNull { shows(it) == shows(item) }?.let { unused.remove(it); matched[item] = it }
            matched.forEach { (item, same) -> item.set(FR, same[FR].deepCopy()) }
        }
        return consistent(kind, written)
    }

    /**
     * [record] without the French that does not hold together with its English: a `fr`, the record's or an item's,
     * that lacks a text its English has or has one its English lacks, goes. The release refuses one.
     */
    fun consistent(kind: RecordKind, record: ObjectNode): ObjectNode {
        val written = record.deepCopy()
        val english = english(kind, written).map { it.first }
        fun check(node: ObjectNode, inner: Set<String>) {
            val fr = node[FR] as? ObjectNode ?: return
            if (leaves(fr) - EXTRAS != inner) node.remove(FR)
        }
        check(written, english.filter { containerOf(it) == null }.toSet())
        for (list in kind.fields.mapNotNull { split(it).first }.distinct()) {
            written[list]?.values()?.withIndex()?.forEach { (index, item) ->
                if (item is ObjectNode) check(item, english.filter { it.startsWith("$list[$index].") }.map { it.substringAfter("].") }.toSet())
            }
        }
        // An event is in French with each of its participants' roles, or not at all.
        if (kind == RecordKind.EVENT) {
            val participants = written["participants"]?.values().orEmpty().filterIsInstance<ObjectNode>()
            if (participants.any { (it[FR] == null) != (written[FR] == null) }) return withFrench(kind, written, null)
        }
        return written
    }

    /** The paths of the texts in a `fr`: `hook`, `world.governs`. */
    private fun leaves(node: JsonNode, prefix: String = ""): Set<String> = node.properties().flatMap { (name, value) ->
        if (value.isObject) leaves(value, "$prefix$name.") else setOf("$prefix$name")
    }.toSet()

    /** What an item of a list shows, to match it with another: a card its type and record, a door its moment, a participant their person. */
    private fun shows(item: JsonNode): List<String?> =
        listOf("type", "person", "life", "event", "to").map { item[it]?.asString() }

    /** Whether an item names whom or what it shows: not a scene card. */
    private fun named(item: JsonNode) = listOf("person", "life", "event", "to").any { item[it] != null }

    /** The list item a path's French goes beside (`cards[2]`), or none for the record's own. */
    private fun containerOf(path: String): String? = ITEM.matchEntire(path)?.let { "${it.groupValues[1]}[${it.groupValues[2]}]" }

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

    /** What a `fr` may hold beside its texts: a person's French Wikipedia article. */
    private val EXTRAS = setOf("wikipedia")
    private val ITEM = Regex("""(\w+)\[(\d+)]\.(.+)""")
}

private fun ObjectNode.string(name: String): String? = get(name)?.takeIf { it.isString }?.asString()
