package art.liminis.publishing

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.domain.CardType
import art.liminis.release.MomentRecord
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A page and a story written into a moment's file, the rest of the file as it was written by hand. */
class MomentFilesTests {

    private val mapper = jacksonMapperBuilder().build()
    private val files = MomentFiles(mapper)
    private val moments = Path.of(System.getProperty("app.release.dir"), "moments")

    private fun pageOf(record: MomentRecord) = MomentPage(
        moment = record.id,
        hook = StoryLine(record.hook),
        world = record.world!!.let { StoryWorld(StoryLine(it.governs), StoryLine(it.everyday), StoryLine(it.arts), StoryLine(it.meanwhile)) },
    )

    private fun storyOf(record: MomentRecord) = Story(
        moment = record.id,
        cards = record.cards.map { StoryCard(it.type, it.kicker, it.title, it.text, it.year, it.person, it.life, it.event) },
    )

    @Test
    fun `writing back a moment's own page and story gives the same bytes, for every story of the sample, its cards' paintings kept`() {
        val stories = moments.listDirectoryEntries("*.json").map { it to mapper.readValue<MomentRecord>(it.readText()) }
            .filter { (_, record) -> record.cards.isNotEmpty() }
        assertEquals(4, stories.size)
        assertTrue(stories.any { (_, record) -> record.cards.any { it.art != null } })
        for ((file, record) in stories) {
            val text = file.readText()
            assertEquals(text, files.write(text, pageOf(record), storyOf(record)), file.toString())
        }
    }

    @Test
    fun `a page and a story go where the release format puts them, and only they change`() {
        val text = """
            {
              "id": "edo-1830s",
              "region": "JP",
              "place": "Edo",
              "period": "1830s",
              "start": 1825,
              "end": 1849,
              "focus": 1831,
              "status": "PUBLISHED",
              "hook": "Old hook.",
              "art": {
                "url": "https://example.org/wave.jpg",
                "credit": "Hokusai",
                "source": "https://example.org"
              },
              "doors": []
            }

        """.trimIndent()
        val line = StoryLine("A line.")
        val page = MomentPage("edo-1830s", StoryLine("Hokusai's Great Wave."), StoryWorld(line, line, line, line))
        val story = Story(
            moment = "edo-1830s",
            cards = listOf(StoryCard(CardType.PERSON, text = "A painter at seventy.", year = 1831, person = "Q5586", label = "Hokusai")),
        )

        val written = files.write(text, page, story)

        assertEquals(
            """
            {
              "id": "edo-1830s",
              "region": "JP",
              "place": "Edo",
              "period": "1830s",
              "start": 1825,
              "end": 1849,
              "focus": 1831,
              "status": "PUBLISHED",
              "hook": "Hokusai's Great Wave.",
              "art": {
                "url": "https://example.org/wave.jpg",
                "credit": "Hokusai",
                "source": "https://example.org"
              },
              "world": {
                "governs": "A line.",
                "everyday": "A line.",
                "arts": "A line.",
                "meanwhile": "A line."
              },
              "cards": [
                {
                  "type": "PERSON",
                  "text": "A painter at seventy.",
                  "year": 1831,
                  "person": "Q5586"
                }
              ],
              "doors": []
            }

            """.trimIndent(),
            written,
        )
        assertEquals(
            """{"hook":"Hokusai's Great Wave.","world":{"governs":"A line.","everyday":"A line.","arts":"A line.","meanwhile":"A line."},""" +
                """"cards":[{"type":"PERSON","text":"A painter at seventy.","year":1831,"person":"Q5586"}]}""",
            files.story(written),
        )
        assertEquals("""{"hook":"Old hook."}""", files.story(text))
        // Each alone leaves the other's fields as the file has them.
        assertEquals("""{"hook":"Old hook.","cards":[{"type":"PERSON","text":"A painter at seventy.","year":1831,"person":"Q5586"}]}""", files.story(files.write(text, null, story)))
        assertEquals(
            """{"hook":"Hokusai's Great Wave.","world":{"governs":"A line.","everyday":"A line.","arts":"A line.","meanwhile":"A line."}}""",
            files.story(files.write(text, page, null)),
        )
    }
}
