package art.liminis.publishing

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import art.liminis.curation.MomentPage
import art.liminis.curation.Painting
import art.liminis.curation.PaintingLicence
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.domain.CardType
import art.liminis.release.ArtRecord
import art.liminis.release.MomentRecord
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A header and a story written into a moment's file, the rest of the file as it was written by hand. */
class MomentFilesTests {

    private val mapper = jacksonMapperBuilder().build()
    private val files = MomentFiles(mapper)
    private val moments = Path.of(System.getProperty("app.release.dir"), "moments")

    private fun pageOf(record: MomentRecord) = MomentPage(
        moment = record.id,
        hook = StoryLine(record.hook),
        world = record.world!!.let { StoryWorld(StoryLine(it.governs), StoryLine(it.everyday), StoryLine(it.arts), StoryLine(it.meanwhile)) },
        art = record.art?.let(::paintingOf),
    )

    private fun storyOf(record: MomentRecord) = Story(
        moment = record.id,
        cards = record.cards.map {
            StoryCard(it.type, it.kicker, it.title, it.text, it.year, it.person, it.life, it.event, art = it.art?.let(::paintingOf))
        },
    )

    private fun paintingOf(art: ArtRecord) = Painting(art.url, art.credit, art.source)

    private val newWave = Painting("https://example.org/red-fuji.jpg", "Hokusai, Red Fuji", "https://example.org/red-fuji", PaintingLicence.PUBLIC_DOMAIN)

    @Test
    fun `writing back a moment's own header and story gives the same bytes, for every story of the sample, paintings included`() {
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
    fun `a header and a story go where the release format puts them, with their paintings, and only they change`() {
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
        val page = MomentPage("edo-1830s", StoryLine("Hokusai's Great Wave."), StoryWorld(line, line, line, line), newWave)
        val story = Story(
            moment = "edo-1830s",
            cards = listOf(StoryCard(CardType.PERSON, text = "A painter at seventy.", year = 1831, person = "Q5586", label = "Hokusai", art = newWave)),
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
                "url": "https://example.org/red-fuji.jpg",
                "credit": "Hokusai, Red Fuji",
                "source": "https://example.org/red-fuji"
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
                  "person": "Q5586",
                  "art": {
                    "url": "https://example.org/red-fuji.jpg",
                    "credit": "Hokusai, Red Fuji",
                    "source": "https://example.org/red-fuji"
                  }
                }
              ],
              "doors": []
            }

            """.trimIndent(),
            written,
        )
        val wave = """"art":{"url":"https://example.org/wave.jpg","credit":"Hokusai","source":"https://example.org"}"""
        val fuji = """"art":{"url":"https://example.org/red-fuji.jpg","credit":"Hokusai, Red Fuji","source":"https://example.org/red-fuji"}"""
        val world = """"world":{"governs":"A line.","everyday":"A line.","arts":"A line.","meanwhile":"A line."}"""
        val cards = """"cards":[{"type":"PERSON","text":"A painter at seventy.","year":1831,"person":"Q5586",$fuji}]"""
        assertEquals("""{"hook":"Hokusai's Great Wave.",$world,$fuji,$cards}""", files.momentText(written))
        assertEquals("""{"hook":"Old hook.",$wave}""", files.momentText(text))
        // Each alone leaves the other's fields as the file has them.
        assertEquals("""{"hook":"Old hook.",$wave,$cards}""", files.momentText(files.write(text, null, story)))
        assertEquals("""{"hook":"Hokusai's Great Wave.",$world,$fuji}""", files.momentText(files.write(text, page, null)))
        // A header without a painting takes the moment's away; a card without one shows the moment's.
        val bare = files.write(text, page.copy(art = null), story.copy(cards = story.cards.map { it.copy(art = null) }))
        assertEquals(
            """{"hook":"Hokusai's Great Wave.",$world,"cards":[{"type":"PERSON","text":"A painter at seventy.","year":1831,"person":"Q5586"}]}""",
            files.momentText(bare),
        )
    }
}
