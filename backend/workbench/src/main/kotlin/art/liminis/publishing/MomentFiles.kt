package art.liminis.publishing

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.stereotype.Component
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.MomentPage
import art.liminis.curation.Painting
import art.liminis.curation.Story
import art.liminis.release.ArtRecord
import art.liminis.release.CardRecord
import art.liminis.release.MomentRecord
import art.liminis.release.WorldRecord
import art.liminis.support.UnavailableException
import art.liminis.translating.RecordKind
import art.liminis.translating.RecordTexts
import kotlin.reflect.full.primaryConstructor

/** What a moment's file says of it at the top of its page, "Paris, 1870s", its hook, world and painting, and in its story. */
data class MomentContent(val title: String, val hook: String?, val world: WorldRecord?, val art: ArtRecord?, val cards: List<CardRecord>)

/**
 * The moments' files of the data repository, `data/moments/<id>.json`, which publishing writes the approved headers and
 * stories into. Only their fields change (a header's hook, world and painting, a story's cards with theirs); the rest
 * of the file, written by hand, stays as it is, in the same layout: two spaces per level, as the files were written.
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
     * [text], the moment's file, with [page]'s hook, world and painting and [story]'s cards in place of its own, those
     * given: the fields in the release format's order. A header without a painting takes the moment's away.
     */
    fun write(text: String, page: MomentPage?, story: Story?): String {
        val file = read(text)
        val written = mapper.createObjectNode().apply {
            page?.let {
                put(HOOK, it.hook.text)
                set(WORLD, mapper.valueToTree(with(it.world) { WorldRecord(governs.text, everyday.text, arts.text, meanwhile.text) }))
                it.art?.let { art -> set(ART, mapper.valueToTree(record(art))) }
            }
            story?.let {
                set(CARDS, mapper.valueToTree(it.cards.map { card ->
                    CardRecord(card.type, card.kicker, card.title, card.text, card.year, card.person, card.life, card.event, card.art?.let(::record))
                }))
            }
        }
        val removed = setOfNotNull(ART.takeIf { page != null && page.art == null })
        val names = (file.propertyNames() + written.propertyNames()).distinct().filter { it !in removed }
            .sortedBy { FIELDS.indexOf(it).takeIf { index -> index >= 0 } ?: FIELDS.size }
        val merged = mapper.createObjectNode()
        for (name in names) merged.set(name, written[name] ?: file[name])
        // A header and story written back as the file has them keep the French beside them; any other change of their
        // English leaves the French to the moment's translation.
        val french = RecordTexts.french(RecordKind.MOMENT, file)
        if (french != null && RecordTexts.english(RecordKind.MOMENT, merged) == RecordTexts.english(RecordKind.MOMENT, file)) {
            return print(RecordTexts.withFrench(RecordKind.MOMENT, merged, french))
        }
        return print(merged)
    }

    /**
     * The header and story in [text], a moment's file, as one line of JSON: to compare and to show. With [french], the
     * French beside them too; without, the English alone.
     */
    fun momentText(text: String, french: Boolean = true): String {
        val file = read(text).let { if (french) it else RecordTexts.withFrench(RecordKind.MOMENT, it, null) }
        val names = listOf(HOOK, WORLD, ART, CARDS) + listOf(FR).filter { french }
        return mapper.writeValueAsString(mapper.createObjectNode().apply { for (name in names) file[name]?.let { set(name, it) } })
    }

    /** [text], a moment's file, as an object. */
    fun node(text: String): ObjectNode = read(text)

    /** [text], a moment's file, as [change] makes it, in the same layout. */
    fun edit(text: String, change: (ObjectNode) -> ObjectNode): String = print(change(read(text)))

    private fun print(node: ObjectNode): String = mapper.writer().with(printer).writeValueAsString(node) + "\n"

    /** What [text], a moment's file, says at the top of the moment's page and in its story. */
    fun content(text: String): MomentContent {
        val file = read(text)
        return MomentContent(
            title = listOfNotNull(file[PLACE]?.asString(), file[PERIOD]?.asString()).joinToString(", "),
            hook = file[HOOK]?.asString(),
            world = file[WORLD]?.let { mapper.treeToValue(it, WorldRecord::class.java) },
            art = file[ART]?.let { mapper.treeToValue(it, ArtRecord::class.java) },
            cards = file[CARDS]?.values().orEmpty().map { mapper.treeToValue(it, CardRecord::class.java) },
        )
    }

    private fun record(painting: Painting) = ArtRecord(painting.url, painting.credit, painting.source)

    private fun read(text: String): ObjectNode =
        mapper.readTree(text) as? ObjectNode ?: throw UnavailableException("A moment's file holds no JSON object")

    private companion object {
        const val PLACE = "place"
        const val PERIOD = "period"
        const val HOOK = "hook"
        const val WORLD = "world"
        const val CARDS = "cards"
        const val ART = "art"
        const val FR = "fr"
        val INDENT = DefaultIndenter("  ", "\n")

        /** A moment's fields in the release format's order. */
        val FIELDS = MomentRecord::class.primaryConstructor!!.parameters.map { it.name!! }
    }
}
