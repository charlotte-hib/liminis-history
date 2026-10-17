package art.liminis.drafting

import org.junit.jupiter.api.Test
import art.liminis.domain.CardType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryDraftValidatorTests {

    private val sources = listOf(
        Source("person:emile-zola", "Émile Zola", "Zola was a French novelist and a major figure in the exoneration of Alfred Dreyfus.", null),
        Source("event:jaccuse", "J'accuse", "In 1898: Zola's open letter fills the front page of L'Aurore.", null),
    )
    private fun line(text: String, sourceId: String, quote: String) = DraftLine(text, listOf(Citation(sourceId, quote)))
    private val world = DraftWorld(
        governs = line("A republic.", "event:jaccuse", "front page of L’Aurore"),
        everyday = line("Papers sell.", "event:jaccuse", "open letter"),
        arts = line("Novels.", "person:emile-zola", "French novelist"),
        meanwhile = line("Elsewhere.", "person:emile-zola", "  French   NOVELIST "),
    )
    private fun card(type: CardType, year: Int? = 1898, citation: Citation = Citation("person:emile-zola", "French novelist"), person: String? = "emile-zola", event: String? = "jaccuse") =
        DraftCard(type, "Kicker", "Title", "Text.", person, null, event, year, listOf(citation))

    @Test
    fun `a draft whose quotes are all in its sources passes, whatever the spacing, case or quote style`() {
        val draft = StoryDraft(line("Hook.", "person:emile-zola", "exoneration of Alfred Dreyfus"), world, List(4) { card(CardType.PERSON) })
        assertEquals(DraftProblems(emptyList(), emptyList()), StoryDraftValidator.problems(draft, sources, 1890, 1900))
    }

    @Test
    fun `invented quotes, unknown references and years outside the moment are reported, the page's apart from the cards'`() {
        val draft = StoryDraft(
            line("Hook.", "person:emile-zola", "won the Nobel Prize"),
            world,
            listOf(
                card(CardType.PERSON, person = "victor-hugo"),
                card(CardType.EVENT, year = 1850, event = "jaccuse"),
                card(CardType.EVENT, citation = Citation("person:nobody", "anything")),
                card(CardType.PERSON),
            ),
        )
        val found = StoryDraftValidator.problems(draft, sources, 1890, 1900)
        assertEquals(listOf("hook quotes text not found in 'person:emile-zola': \"won the Nobel Prize\""), found.page)
        val problems = found.cards
        assertTrue(problems.any { "unknown person 'victor-hugo'" in it }, problems.toString())
        assertTrue(problems.any { "set in 1850" in it }, problems.toString())
        assertTrue(problems.any { "unknown source 'person:nobody'" in it }, problems.toString())
    }
}
