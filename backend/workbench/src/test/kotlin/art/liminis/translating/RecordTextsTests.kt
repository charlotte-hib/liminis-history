package art.liminis.translating

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A record's texts by path, and its French beside them, on the JSON of the release format. */
class RecordTextsTests {

    private val mapper = JsonMapper.builder().build()

    private fun node(json: String) = mapper.readTree(json) as ObjectNode

    private val moment = node(
        """
        {"id":"paris-1870s","place":"Paris","period":"1870s","hook":"A republic.","art":{"credit":"Caillebotte"},
         "world":{"governs":"G.","everyday":"E.","arts":"A.","meanwhile":"M."},
         "cards":[{"type":"SCENE","title":"T","text":"One."},{"type":"LIFE","life":"a-laundress"},{"type":"PERSON","person":"x","text":"Two."}],
         "doors":[{"to":"tokyo-1870s","kind":"Meanwhile","text":"Japan"}]}
        """,
    )

    @Test
    fun `a record's texts are those it has, by path, in the release format's order`() {
        assertEquals(
            listOf(
                "place" to "Paris", "period" to "1870s", "hook" to "A republic.", "world.governs" to "G.", "world.everyday" to "E.",
                "world.arts" to "A.", "world.meanwhile" to "M.", "cards[0].title" to "T", "cards[0].text" to "One.", "cards[2].text" to "Two.",
                "doors[0].kind" to "Meanwhile", "doors[0].text" to "Japan",
            ),
            RecordTexts.english(RecordKind.MOMENT, moment),
        )
        assertEquals("moment:paris-1870s", DataRecord(RecordKind.MOMENT, moment).key)
        val connection = node("""{"people":["paul-cezanne","emile-zola"],"kind":"school friends","year":1852,"text":"Friends.","source":"s"}""")
        assertEquals("connection:paul-cezanne|emile-zola:school friends", DataRecord(RecordKind.CONNECTION, connection).key)
    }

    @Test
    fun `French goes last, beside the record's texts and in each card and door, and comes back by path`() {
        val french = RecordTexts.english(RecordKind.MOMENT, moment).associate { (path, en) -> path to "fr $en" }
        val written = RecordTexts.withFrench(RecordKind.MOMENT, moment, french)

        assertEquals(
            """{"place":"fr Paris","period":"fr 1870s","hook":"fr A republic.","world":{"governs":"fr G.","everyday":"fr E.","arts":"fr A.","meanwhile":"fr M."}}""",
            mapper.writeValueAsString(written["fr"]),
        )
        assertEquals("""{"type":"SCENE","title":"T","text":"One.","fr":{"title":"fr T","text":"fr One."}}""", mapper.writeValueAsString(written["cards"][0]))
        assertNull(written["cards"][1]["fr"])
        assertEquals(listOf("id", "place", "period", "hook", "art", "world", "cards", "doors", "fr"), written.propertyNames().toList())
        assertEquals(french, RecordTexts.french(RecordKind.MOMENT, written))
        assertNull(moment["fr"])

        val removed = RecordTexts.withFrench(RecordKind.MOMENT, written, null)
        assertEquals(moment, removed)
        assertNull(RecordTexts.french(RecordKind.MOMENT, removed))
    }

    @Test
    fun `a participant's role is in French beside it, and a person's French names their article after their texts`() {
        val event = node("""{"id":"carmen","title":"Carmen","description":"An opera.","participants":[{"person":"georges-bizet","role":"composer"}]}""")
        val written = RecordTexts.withFrench(RecordKind.EVENT, event, mapOf("title" to "Carmen", "description" to "Un opéra.", "participants[0].role" to "compositeur"))
        assertEquals("""{"person":"georges-bizet","role":"composer","fr":{"role":"compositeur"}}""", mapper.writeValueAsString(written["participants"][0]))

        val person = node("""{"id":"emile-zola","name":"Émile Zola","occupation":"novelist"}""")
        val french = RecordTexts.withFrench(RecordKind.PERSON, person, mapOf("name" to "Émile Zola", "occupation" to "romancier"), mapOf("wikipedia" to "Émile_Zola"))
        assertEquals("""{"name":"Émile Zola","occupation":"romancier","wikipedia":"Émile_Zola"}""", mapper.writeValueAsString(french["fr"]))
    }
}
