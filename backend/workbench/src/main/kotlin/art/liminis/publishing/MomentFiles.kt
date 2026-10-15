package art.liminis.publishing

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.stereotype.Component
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.JsonNode
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.Story
import art.liminis.release.CardRecord
import art.liminis.release.MomentRecord
import art.liminis.release.WorldRecord
import art.liminis.support.UnavailableException
import kotlin.reflect.full.primaryConstructor

/**
 * The moments' files of the data repository, `data/moments/<id>.json`, which publishing writes the approved stories
 * into. Only a story's fields change (the hook, the world, the cards); the rest of the file, written by hand, stays as
 * it is, in the same layout: two spaces per level, as the files were written.
 */
@Component
class MomentFiles(jsonMapper: JsonMapper) {

    // A record's fields in the order of its class, and without the ones it leaves out, as in the files written by hand.
    private val mapper = jsonMapper.rebuild()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
        .build()

    private val printer = DefaultPrettyPrinter(
        Separators.createDefaultInstance()
            .withObjectNameValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("")
            .withArrayEmptySeparator(""),
    ).withObjectIndenter(INDENT).withArrayIndenter(INDENT)

    fun path(moment: String) = "data/moments/$moment.json"

    /** [text], the moment's file, with [story] in place of its own: the fields in the release format's order. */
    fun write(text: String, story: Story): String {
        val file = read(text)
        val written = mapper.createObjectNode().apply {
            put(HOOK, story.hook.text)
            set(WORLD, mapper.valueToTree(with(story.world) { WorldRecord(governs.text, everyday.text, arts.text, meanwhile.text) }))
            set(CARDS, mapper.valueToTree(story.cards.map { CardRecord(it.type, it.kicker, it.title, it.text, it.year, it.person, it.life, it.event) }))
        }
        val names = (file.propertyNames() + written.propertyNames()).distinct()
            .sortedBy { FIELDS.indexOf(it).takeIf { index -> index >= 0 } ?: FIELDS.size }
        val merged = mapper.createObjectNode()
        for (name in names) merged.set(name, written[name] ?: file[name])
        return mapper.writer().with(printer).writeValueAsString(merged) + "\n"
    }

    /** The story in [text], a moment's file, as one line of JSON: to compare and to show. */
    fun story(text: String): String {
        val file = read(text)
        return mapper.writeValueAsString(mapper.createObjectNode().apply { for (name in listOf(HOOK, WORLD, CARDS)) file[name]?.let { set(name, it) } })
    }

    private fun read(text: String): ObjectNode =
        mapper.readTree(text) as? ObjectNode ?: throw UnavailableException("A moment's file holds no JSON object")

    private companion object {
        const val HOOK = "hook"
        const val WORLD = "world"
        const val CARDS = "cards"
        val INDENT = DefaultIndenter("  ", "\n")

        /** A moment's fields in the release format's order. */
        val FIELDS = MomentRecord::class.primaryConstructor!!.parameters.map { it.name!! }
    }
}
