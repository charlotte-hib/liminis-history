package art.liminis.drafting

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.Claims
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.MomentPage
import art.liminis.curation.Origin
import art.liminis.curation.Painting
import art.liminis.curation.PaintingLicence
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.dataset.DatasetStore
import art.liminis.domain.CardType
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drafting a moment's story from the dataset's intros, with Claude stood in for, into the moment's story claim. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@Import(PostgresTestConfiguration::class)
class StoryDraftingTests(
    @Autowired private val drafting: StoryDrafting,
    @Autowired private val claims: Claims,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val claimRepository: ClaimRepository,
) {

    @MockitoBean
    private lateinit var drafter: StoryDrafter

    private val hugo = "Victor Hugo was a French Romantic writer and politician."
    private val lesser = "Lesser Known was a French writer of the nineteenth century."

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
        for ((qid, extract) in listOf("Q535" to hugo, "Q3008" to lesser)) {
            jdbc.update(
                "insert into dataset.person_article (person, language, title, url, extract) values (?, 'en', ?, ?, ?)",
                qid, "Title of $qid", "https://en.wikipedia.org/wiki/$qid", extract,
            )
        }
    }

    // Mockito's matchers answer null, which Kotlin refuses for a parameter that is not nullable.
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = ArgumentMatchers.any<T>() ?: null as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> capture(captor: ArgumentCaptor<T>): T = captor.capture() ?: null as T

    private fun line(text: String, source: String, quote: String) = DraftLine(text, listOf(Citation(source, quote)))

    private val draft = StoryDraft(
        hook = line("A republic, and its old poet.", "person:Q535", "French Romantic writer"),
        world = line("A line.", "person:Q3008", "a French writer").let { DraftWorld(it, it, it, it) },
        cards = listOf(
            DraftCard(CardType.PERSON, null, null, "Back from exile.", "Q535", null, null, 1871, listOf(Citation("person:Q535", "and politician"))),
            // Named by its whole source id, as Claude did once: the id alone is kept.
            DraftCard(CardType.PERSON, null, null, "Writing.", "person:Q3008", null, null, 1875, listOf(Citation("person:Q3008", "nineteenth century"))),
            DraftCard(CardType.LIFE, null, null, "Washing.", null, "life:a-washerwoman-on-the-seine", null, 1875, listOf(Citation("person:Q3008", "invented"))),
            DraftCard(CardType.SCENE, "Setting the scene", "A city rebuilt", "Boulevards.", null, null, null, 1875, listOf(Citation("person:Q535", "writer"))),
        ),
    )

    @Test
    fun `a draft becomes the moment's page and story claims, hand-written people by their id, each with what the validator found in it`() {
        doReturn(Drafted(draft, "claude-opus-5-5", 40_000, 8_000)).`when`(drafter).draft(anyOf(), anyOf())

        val result = drafting.draft("paris-1870s")

        // 40,000 tokens at $4 a million and 8,000 at $20.
        assertTrue(result.endsWith("40,000 input and 8,000 output tokens, about \$0.32"), result)
        val detail = claims.detail("story:paris-1870s")
        val story = detail.claim.payload as Story
        assertEquals(ClaimStatus.CANDIDATE, detail.claim.status)
        assertEquals(Origin.CLAUDE, detail.claim.origin)
        assertEquals(listOf("victor-hugo", "Q3008", null, null), story.cards.map { it.person })
        assertEquals(listOf("Victor Hugo", "Lesser Known", "A washerwoman on the Seine", null), story.cards.map { it.label })
        assertEquals("a-washerwoman-on-the-seine", story.cards[2].life)
        assertEquals(
            listOf(
                FlagReason(Flag.ODD, "card 3 (LIFE) quotes text not found in 'person:Q3008': \"invented\""),
                FlagReason(Flag.ODD, "card 2 shows Lesser Known (Q3008), whom the site holds once its release carries the fetched dataset"),
            ),
            detail.claim.flags,
        )
        val page = claims.detail("moment-page:paris-1870s")
        assertEquals(ClaimStatus.CANDIDATE, page.claim.status)
        assertEquals(Origin.CLAUDE, page.claim.origin)
        assertEquals("A republic, and its old poet.", (page.claim.payload as MomentPage).hook.text)
        assertEquals("Victor Hugo (1802–1885), writer", (page.claim.payload as MomentPage).hook.citations.single().title)
        assertEquals(emptyList(), page.claim.flags)
        // The release's painting, until the curator chooses another.
        assertEquals("Gustave Caillebotte, Paris Street; Rainy Day, 1877", (page.claim.payload as MomentPage).art?.credit)
        assertEquals(listOf(null, null, null, null), story.cards.map { it.art })
        assertEquals(
            listOf("Title of Q535", "Title of Q3008").map { SourceRef(SourceKind.WIKIPEDIA, language = "en", title = it) },
            detail.sources,
        )
    }

    @Test
    fun `a new draft keeps the paintings the curator chose, a scene card's by its order among the scenes`() {
        val chosen = { name: String -> Painting("https://example.org/$name.jpg", name, "https://example.org/$name", PaintingLicence.CC0) }
        val line = StoryLine("A line.")
        claimRepository.save(Claim.candidate(MomentPage("paris-1870s", line, StoryWorld(line, line, line, line), chosen("rain")), Origin.MANUAL, emptyList()))
        val story = Story(
            "paris-1870s",
            listOf(
                StoryCard(CardType.SCENE, title = "First", art = chosen("first-scene")),
                StoryCard(CardType.PERSON, person = "victor-hugo", art = chosen("hugo")),
                StoryCard(CardType.SCENE, title = "Second", art = chosen("second-scene")),
            ),
        )
        claimRepository.save(Claim.candidate(story, Origin.CLAUDE, emptyList()))
        val scene = { title: String -> DraftCard(CardType.SCENE, null, title, "A scene.", null, null, null, 1875, listOf(Citation("person:Q535", "writer"))) }
        val cards = listOf(scene("One"), draft.cards[0], scene("Two"), scene("Three"))
        doReturn(Drafted(draft.copy(cards = cards), "claude-opus-5-5", 1, 1)).`when`(drafter).draft(anyOf(), anyOf())

        drafting.draft("paris-1870s")

        assertEquals("rain", (claims.detail("moment-page:paris-1870s").claim.payload as MomentPage).art?.credit)
        val drafted = claims.detail("story:paris-1870s").claim.payload as Story
        assertEquals(listOf("first-scene", "hugo", "second-scene", null), drafted.cards.map { it.art?.credit })
    }

    @Test
    fun `a drafted card showing what one of the release's cards showed keeps its painting`() {
        val railway = DraftCard(CardType.EVENT, null, null, "A train.", null, null, "event:first-railway", 1872, listOf(Citation("person:Q535", "writer")))
        val scene = DraftCard(CardType.SCENE, null, "Rain", "Rain on the bridge.", null, null, null, 1876, listOf(Citation("person:Q535", "writer")))
        doReturn(Drafted(draft.copy(cards = listOf(scene, railway)), "claude-opus-5-5", 1, 1)).`when`(drafter).draft(anyOf(), anyOf())

        drafting.draft("tokyo-1870s")

        val story = claims.detail("story:tokyo-1870s").claim.payload as Story
        assertEquals(listOf(null, "Utagawa Hiroshige III, steam train at Yokohama, 1874"), story.cards.map { it.art?.credit })
        assertEquals(null, story.cards[1].art?.licence)
    }

    @Test
    fun `the drafter reads the intros of the best known people alive there, and the release's eras, lives and events`() {
        doReturn(Drafted(draft, "claude-opus-5-5", 1, 1)).`when`(drafter).draft(anyOf(), anyOf())
        drafting.draft("paris-1870s")

        @Suppress("UNCHECKED_CAST")
        val sources = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Source>>
        verify(drafter).draft(anyOf(), capture(sources))
        val ids = sources.value.map { it.id }
        // Hugo and Lesser Known have an intro; Zola, alive there too, has none in this dataset.
        assertEquals(listOf("person:Q535", "person:Q3008"), ids.filter { it.startsWith("person:") })
        assertTrue(ids.any { it.startsWith("era:") } && ids.any { it.startsWith("life:") } && ids.any { it.startsWith("event:") }, ids.toString())
        assertEquals(hugo, sources.value.first { it.id == "person:Q535" }.text)
    }
}
