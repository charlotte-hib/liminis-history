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
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.release.CardRecord
import art.liminis.release.MomentRecord
import art.liminis.release.WorldRecord
import art.liminis.support.UnavailableException
import kotlin.reflect.full.primaryConstructor

/**
 * The moments' files of the data repository, `data/moments/<id>.json`, which publishing writes the approved pages and
 * stories into. Only their fields change (a page's hook and world, a story's cards); the rest of the file, written by
 * hand, stays as it is, in the same layout: two spaces per level, as the files were written. A card's painting, chosen
 * by hand, stays with a new card showing the same person, life or event.
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

    /**
     * [text], the moment's file, with [page]'s hook and world and [story]'s cards in place of its own, those given: the
     * fields in the release format's order.
     */
    fun write(text: String, page: MomentPage?, story: Story?): String {
        val file = read(text)
        val written = mapper.createObjectNode().apply {
            page?.let {
                put(HOOK, it.hook.text)
                set(WORLD, mapper.valueToTree(with(it.world) { WorldRecord(governs.text, everyday.text, arts.text, meanwhile.text) }))
            }
            story?.let {
                set(CARDS, mapper.valueToTree(it.cards.map { card -> CardRecord(card.type, card.kicker, card.title, card.text, card.year, card.person, card.life, card.event) }))
            }
        }
        val paintings = file[CARDS]?.values().orEmpty().filter { it.has(ART) }.associate { shown(it) to it[ART] }
        for (card in written[CARDS]?.values().orEmpty()) paintings[shown(card)]?.let { (card as ObjectNode).set(ART, it) }
        val names = (file.propertyNames() + written.propertyNames()).distinct()
            .sortedBy { FIELDS.indexOf(it).takeIf { index -> index >= 0 } ?: FIELDS.size }
        val merged = mapper.createObjectNode()
        for (name in names) merged.set(name, written[name] ?: file[name])
        return mapper.writer().with(printer).writeValueAsString(merged) + "\n"
    }

    /** The page and story in [text], a moment's file, as one line of JSON: to compare and to show. */
    fun story(text: String): String {
        val file = read(text)
        return mapper.writeValueAsString(mapper.createObjectNode().apply { for (name in listOf(HOOK, WORLD, CARDS)) file[name]?.let { set(name, it) } })
    }

    /** Whom or what a card shows: its type and the id it names. */
    private fun shown(card: JsonNode) = listOf(TYPE, PERSON, LIFE, EVENT).map { card[it]?.asString() }

    private fun read(text: String): ObjectNode =
        mapper.readTree(text) as? ObjectNode ?: throw UnavailableException("A moment's file holds no JSON object")

    private companion object {
        const val HOOK = "hook"
        const val WORLD = "world"
        const val CARDS = "cards"
        const val ART = "art"
        const val TYPE = "type"
        const val PERSON = "person"
        const val LIFE = "life"
        const val EVENT = "event"
        val INDENT = DefaultIndenter("  ", "\n")

        /** A moment's fields in the release format's order. */
        val FIELDS = MomentRecord::class.primaryConstructor!!.parameters.map { it.name!! }
    }
}
