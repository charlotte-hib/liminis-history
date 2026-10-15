package art.liminis.curation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import art.liminis.domain.CardType
import art.liminis.support.InvalidRequestException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules of claims, without a database. */
class ClaimRulesTests {

    private val anecdote = Anecdote("Q535", 1851, "Hugo fled to Brussels after Louis-Napoléon's coup.")
    private val facts = PersonFacts(
        person = "Q535", label = "Victor Hugo", born = 1802, bornPrecision = 11, died = 1885, diedEstimated = false,
        datesApproximate = false, occupations = emptyList(), places = emptyList(),
    )
    private val unreferenced = FlagReason(Flag.POORLY_DOCUMENTED, "DEATH_UNREFERENCED")
    private val changedSinceApproved = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")
    private val changedSinceEdited = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_EDITED")

    private fun state(status: ClaimStatus, payload: Payload = facts, proposal: Payload = facts, flags: List<FlagReason> = emptyList()) =
        ClaimState(status, payload, proposal, flags)

    private fun proposal(payload: Payload, flags: List<FlagReason> = emptyList()) = Proposal(payload, Origin.WIKIDATA, flags, emptyList())

    @Test
    fun `ids come from what a claim is about`() {
        assertEquals("person-facts:Q535", ClaimRules.idOf(facts))
        assertEquals("anecdote:Q535:1851:fe915d885333", ClaimRules.idOf(anecdote))
        assertEquals(ClaimRules.idOf(anecdote), ClaimRules.idOf(anecdote.copy(text = "  ${anecdote.text}\n")))
        assertEquals(
            "connection:Q504|Q535:sculptor-and-subject",
            ClaimRules.idOf(Connection(listOf("Q535", "Q504"), "Sculptor and subject", 1878, "t")),
        )
        assertEquals(
            "event:FR:-52:vercingetorix-surrenders-at-alesia",
            ClaimRules.idOf(Event("FR", -52, "Vercingetorix surrenders at Alesia", "d", emptyList())),
        )
    }

    @Test
    fun `a claim added by hand holds together, and one source flags it`() {
        val book = SourceRef(SourceKind.BOOK, title = "Les Misérables, preface", page = "1")
        val web = SourceRef(SourceKind.WEB, url = "https://example.org")

        assertEquals(listOf(FlagReason(Flag.SINGLE_SOURCE, "ONE_SOURCE")), ClaimRules.flagsOfNew(NewClaim(anecdote, listOf(book))))
        assertEquals(emptyList(), ClaimRules.flagsOfNew(NewClaim(anecdote, listOf(book, web))))

        val refused = listOf(
            NewClaim(facts, listOf(web)),
            NewClaim(anecdote.copy(year = 0), listOf(web)),
            NewClaim(Connection(listOf("Q535", "Q535"), "self", 1850, "t"), listOf(web)),
            NewClaim(anecdote, listOf(SourceRef(SourceKind.BOOK, title = "No page"))),
            NewClaim(anecdote, listOf(SourceRef(SourceKind.MEDIA, title = "A podcast"))),
        )
        for (claim in refused) assertThrows<InvalidRequestException> { ClaimRules.flagsOfNew(claim) }
    }

    @Test
    fun `a decision sets the status, keeps the subject, and settles the refresh's conflicts`() {
        val conflicted = state(ClaimStatus.CANDIDATE, flags = listOf(unreferenced, changedSinceApproved))

        val approved = ClaimRules.decide(conflicted, NewDecision(Verdict.APPROVE, facts.copy(died = 1886)))
        assertEquals(ClaimStatus.APPROVED, approved.status)
        assertEquals(1886, (approved.payload as PersonFacts).died)
        assertEquals(facts, approved.proposal)
        assertEquals(listOf(unreferenced), approved.flags)

        assertThrows<InvalidRequestException> { ClaimRules.decide(conflicted, NewDecision(Verdict.REJECT)) }
        assertThrows<InvalidRequestException> { ClaimRules.decide(conflicted, NewDecision(Verdict.APPROVE, facts.copy(person = "Q504"))) }
        assertThrows<InvalidRequestException> { ClaimRules.decide(conflicted, NewDecision(Verdict.APPROVE, anecdote)) }
        assertEquals(ClaimStatus.NEEDS_WORK, ClaimRules.decide(conflicted, NewDecision(Verdict.NEEDS_WORK, note = "Check")).status)
    }

    @Test
    fun `a new proposal replaces a claim waiting for review`() {
        val newer = facts.copy(born = 1803)
        val refreshed = ClaimRules.refresh(state(ClaimStatus.CANDIDATE, flags = listOf(unreferenced)), proposal(newer))

        assertEquals(state(ClaimStatus.CANDIDATE, newer, newer), refreshed)
    }

    @Test
    fun `a new proposal sends an approved claim back to review, keeping what was approved`() {
        val newer = facts.copy(born = 1803)
        val refreshed = ClaimRules.refresh(state(ClaimStatus.APPROVED), proposal(newer))

        assertEquals(state(ClaimStatus.CANDIDATE, facts, newer, listOf(changedSinceApproved)), refreshed)
        // Proposed again, the conflict stays until the curator decides.
        assertEquals(refreshed, ClaimRules.refresh(refreshed, proposal(newer)))
        assertFalse(ClaimRules.changes(refreshed, proposal(newer)))
    }

    @Test
    fun `a new proposal keeps the curator's edit, flagged, and leaves a rejected claim rejected`() {
        val edited = facts.copy(died = 1886)
        val newer = facts.copy(born = 1803)

        assertEquals(
            state(ClaimStatus.NEEDS_WORK, edited, newer, listOf(changedSinceEdited)),
            ClaimRules.refresh(state(ClaimStatus.NEEDS_WORK, edited), proposal(newer)),
        )
        assertEquals(
            state(ClaimStatus.REJECTED, facts, newer, listOf(unreferenced)),
            ClaimRules.refresh(state(ClaimStatus.REJECTED), proposal(newer, listOf(unreferenced))),
        )
    }

    @Test
    fun `only a different payload or different fetched flags change a claim`() {
        val approved = state(ClaimStatus.APPROVED, flags = listOf(unreferenced))

        assertFalse(ClaimRules.changes(approved, proposal(facts, listOf(unreferenced))))
        assertTrue(ClaimRules.changes(approved, proposal(facts)))
        assertEquals(approved.copy(flags = emptyList()), ClaimRules.refresh(approved, proposal(facts)))
    }

    private fun story(hook: String) = Story(
        moment = "paris-1870s",
        hook = StoryLine(hook, listOf(Citation("person:Q535", "Victor Hugo", "a French writer"))),
        world = StoryLine("A line.").let { StoryWorld(it, it, it, it) },
        cards = listOf(StoryCard(CardType.PERSON, text = "Back from exile.", year = 1871, person = "victor-hugo", label = "Victor Hugo")),
    )

    @Test
    fun `a story is one claim per moment, and its cards name what they show`() {
        val draft = story("A republic born out of defeat.")
        assertEquals("story:paris-1870s", ClaimRules.idOf(draft))
        assertThrows<InvalidRequestException> { ClaimRules.flagsOfNew(NewClaim(draft, listOf(SourceRef(SourceKind.WEB, url = "https://example.org")))) }

        val unnamed = draft.copy(cards = listOf(StoryCard(CardType.PERSON, text = "Someone.")))
        assertThrows<InvalidRequestException> { ClaimRules.decide(state(ClaimStatus.CANDIDATE, draft, draft), NewDecision(Verdict.APPROVE, unnamed)) }
        // As drafted too: the validator only flags a card naming nobody.
        assertThrows<InvalidRequestException> { ClaimRules.decide(state(ClaimStatus.CANDIDATE, unnamed, unnamed), NewDecision(Verdict.APPROVE)) }
        ClaimRules.decide(state(ClaimStatus.CANDIDATE, unnamed, unnamed), NewDecision(Verdict.REJECT, note = "Nobody on card 1"))
        val untitled = draft.copy(cards = listOf(StoryCard(CardType.SCENE, text = "A scene.")))
        assertThrows<InvalidRequestException> { ClaimRules.decide(state(ClaimStatus.CANDIDATE, draft, draft), NewDecision(Verdict.APPROVE, untitled)) }
        assertThrows<InvalidRequestException> {
            ClaimRules.decide(state(ClaimStatus.CANDIDATE, draft, draft), NewDecision(Verdict.APPROVE, draft.copy(moment = "paris-1890s")))
        }
    }

    @Test
    fun `a new draft of a story waits for review, whatever became of the last one, unless the curator edited it`() {
        val first = story("First draft.")
        val edited = story("Edited by the curator.")
        val second = Proposal(story("Second draft."), Origin.CLAUDE, listOf(FlagReason(Flag.ODD, "card 1 quotes text not found")), emptyList())

        for (status in ClaimStatus.entries) {
            val redrafted = ClaimRules.refresh(state(status, first, first), second)
            assertEquals(ClaimStatus.CANDIDATE, redrafted.status, "after $status")
            assertEquals(second.payload, redrafted.payload, "after $status")
            assertEquals(second.payload, redrafted.proposal)
            assertEquals(second.flags + listOfNotNull(changedSinceApproved.takeIf { status == ClaimStatus.APPROVED }), redrafted.flags)
        }

        val keptEdits = ClaimRules.refresh(state(ClaimStatus.APPROVED, edited, first), second)
        assertEquals(ClaimStatus.CANDIDATE, keptEdits.status)
        assertEquals(edited, keptEdits.payload)
        assertEquals(second.flags + changedSinceEdited, keptEdits.flags)

        // Rejected: the edits went with it.
        assertEquals(second.payload, ClaimRules.refresh(state(ClaimStatus.REJECTED, edited, first), second).payload)
    }
}
