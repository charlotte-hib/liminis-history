package art.liminis.curation

import art.liminis.domain.CardType
import art.liminis.support.InvalidRequestException
import art.liminis.support.slugify
import java.security.MessageDigest

/** What a claim holds that a decision or a refresh can change. */
data class ClaimState(val status: ClaimStatus, val payload: Payload, val proposal: Payload, val flags: List<FlagReason>)

/**
 * The rules of claims, apart from where they are stored: what a claim's id and subject are, what holds together, what a
 * decision does, and what a new proposal does to a claim already reviewed. [Claims] applies them in its transactions.
 * Breaking a rule throws [InvalidRequestException].
 */
object ClaimRules {

    /** Who the workbench's own decisions are logged as: proposing and refreshing claims. */
    const val RULES = "rule"

    /**
     * Whether [actor] may edit or decide on a claim held by [heldBy]: its holder may, and the admin; a claim nobody
     * holds goes to whoever writes it first.
     */
    fun mayWrite(heldBy: String?, actor: Actor): Boolean = heldBy == null || heldBy == actor.name || actor.admin

    /** Claims waiting or kept for later: those a curator takes to work on. */
    fun isOpen(status: ClaimStatus): Boolean = status == ClaimStatus.CANDIDATE || status == ClaimStatus.NEEDS_WORK

    private val CHANGED_SINCE_APPROVED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")
    private val CHANGED_SINCE_EDITED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_EDITED")
    private val ONE_SOURCE = FlagReason(Flag.SINGLE_SOURCE, "ONE_SOURCE")

    fun typeOf(payload: Payload): ClaimType = when (payload) {
        is PersonFacts -> ClaimType.PERSON_FACTS
        is Connection -> ClaimType.CONNECTION
        is Event -> ClaimType.EVENT
        is Anecdote -> ClaimType.ANECDOTE
        is OccupationDomain -> ClaimType.OCCUPATION_DOMAIN
        is Story -> ClaimType.STORY
        is MomentPage -> ClaimType.MOMENT_PAGE
        is Translation -> ClaimType.TRANSLATION
    }

    /**
     * A claim's id, from what it is about, so the same thing proposed again lands on the same claim:
     * `person-facts:Q535`, `connection:Q296|Q535:friends`, `event:FR:-52:vercingetorix-surrenders-at-alesia`,
     * `anecdote:Q535:1851:` and the start of the text's SHA-256, `occupation:Q1028181`, `story:paris-1870s`,
     * `moment-page:paris-1870s`, `translation:moment:paris-1870s`.
     */
    fun idOf(payload: Payload): String = when (payload) {
        is PersonFacts -> factsId(payload.person)
        is Connection -> "connection:${payload.people.sorted().joinToString("|")}:${slugify(payload.kind)}"
        is Event -> "event:${payload.region}:${payload.year}:${slugify(payload.title)}"
        is Anecdote -> "anecdote:${payload.person}:${payload.year}:${sha256(payload.text.trim()).take(12)}"
        is OccupationDomain -> occupationId(payload.occupation)
        is Story -> storyId(payload.moment)
        is MomentPage -> pageId(payload.moment)
        is Translation -> translationId(payload.record)
    }

    /** The id of the claim on [person]'s facts. */
    fun factsId(person: String) = "person-facts:$person"

    /** The id of the claim on [occupation]'s domain. */
    fun occupationId(occupation: String) = "occupation:$occupation"

    /** The id of the claim on [moment]'s story. */
    fun storyId(moment: String) = "story:$moment"

    /** The id of the claim on the text of [moment]'s page. */
    fun pageId(moment: String) = "moment-page:$moment"

    /** The id of the claim on the French of [record], a record of `data/` by kind and id: `moment:paris-1870s`. */
    fun translationId(record: String) = "translation:$record"

    /**
     * The cards of [story] showing a fetched person, named by Q-id, by their number: the site holds them only once its
     * release carries the fetched dataset.
     */
    fun fetchedPeople(story: Story): Map<Int, StoryCard> =
        story.cards.withIndex()
            .filter { (_, card) -> card.type == CardType.PERSON && card.person.orEmpty().matches(QID) }
            .associate { (index, card) -> index + 1 to card }

    /** Whom or where a claim is about: an edit must not change it. */
    fun subjectOf(payload: Payload): String = when (payload) {
        is PersonFacts -> payload.person
        is Connection -> payload.people.sorted().joinToString(" and ")
        is Event -> payload.region
        is Anecdote -> payload.person
        is OccupationDomain -> payload.occupation
        is Story -> payload.moment
        is MomentPage -> payload.moment
        is Translation -> payload.record
    }

    /**
     * Checks a claim the curator adds by hand, and returns the flags it starts with. A person's facts and the
     * occupations to map only come from Wikidata; a claim resting on one source is flagged, for support to be looked for.
     */
    fun flagsOfNew(claim: NewClaim): List<FlagReason> {
        if (claim.payload is PersonFacts) invalid("A person's facts are fetched from Wikidata, not added by hand")
        if (claim.payload is OccupationDomain) invalid("Occupations are proposed from the dataset, not added by hand")
        if (claim.payload is Story) invalid("Stories are drafted with Claude, not added by hand")
        if (claim.payload is MomentPage) invalid("A moment's page is written on the moment's page, from the release's text")
        if (claim.payload is Translation) invalid("Translations are drafted from the data repository's records, not added by hand")
        check(claim.payload)
        claim.sources.forEach(::check)
        return if (claim.sources.size == 1) listOf(ONE_SOURCE) else emptyList()
    }

    /**
     * What [decision] makes of a claim in [state]: the edited payload, if given, which must stay the same type about
     * the same subject; a note saying why, unless it approves. Deciding settles the conflicts a refresh found.
     */
    fun decide(state: ClaimState, decision: NewDecision): ClaimState {
        val payload = decision.payload ?: state.payload
        if (decision.payload != null) {
            if (typeOf(payload) != typeOf(state.payload) || subjectOf(payload) != subjectOf(state.payload)) {
                invalid("An edit must stay a ${typeOf(state.payload)} about ${subjectOf(state.payload)}")
            }
            if (payload is Translation && payload.texts.map { it.path to it.en } != (state.payload as Translation).texts.map { it.path to it.en }) {
                invalid("An edit of a translation changes its French, not its English")
            }
            check(payload)
            if (payload is PersonFacts) checkArticles(payload, state.proposal as PersonFacts)
        } else if (decision.verdict == Verdict.APPROVE && (payload is Story || payload is MomentPage || payload is Translation)) {
            // A draft is checked by the validator, which only flags: approved as drafted, it must still hold together.
            check(payload)
        }
        val status = when (decision.verdict) {
            Verdict.APPROVE -> ClaimStatus.APPROVED
            Verdict.REJECT -> ClaimStatus.REJECTED
            Verdict.NEEDS_WORK -> ClaimStatus.NEEDS_WORK
        }
        if (status != ClaimStatus.APPROVED && decision.note.isNullOrBlank()) invalid("Say why, in a note")
        if (status == ClaimStatus.APPROVED && payload is OccupationDomain && payload.domain == null) invalid("Choose a domain, or none")
        return state.copy(status = status, payload = payload, flags = fetchedFlags(state.flags))
    }

    /** Whether [proposal] differs from what the claim in [state] was last proposed with, and may change it. */
    fun changes(state: ClaimState, proposal: Proposal): Boolean =
        !keeps(state, proposal) && (state.proposal != proposal.payload || fetchedFlags(state.flags) != proposal.flags)

    /**
     * Whether the claim in [state] stays as it is whatever [proposal] says: an approved moment's page. Its story's
     * cards are drafted again with it, and the curator who asks for new cards keeps the page they approved; rejecting it
     * lets the next draft in.
     */
    private fun keeps(state: ClaimState, proposal: Proposal) = proposal.payload is MomentPage && state.status == ClaimStatus.APPROVED

    /**
     * What a new [proposal] makes of a claim in [state], keeping the conflicts earlier refreshes found until the
     * curator decides again. A claim not yet decided takes the new proposal, unless the curator edited it. An approved
     * one goes back to review, flagged: what it rests on changed. A rejected one stays rejected. A story and a moment's
     * page are drafted again only when the curator asks: [redraft]; an approved page is kept ([keeps]).
     */
    fun refresh(state: ClaimState, proposal: Proposal): ClaimState {
        if (keeps(state, proposal)) return state
        val conflicts = state.flags - fetchedFlags(state.flags).toSet()
        val flags = proposal.flags + conflicts
        if (state.proposal == proposal.payload) return state.copy(flags = flags)
        val refreshed = state.copy(proposal = proposal.payload)
        if (proposal.payload is Story || proposal.payload is MomentPage) return redraft(refreshed, state, flags)
        if (proposal.payload is Translation) return retranslated(refreshed, state, flags)
        return when {
            state.status == ClaimStatus.REJECTED -> refreshed.copy(flags = flags)
            state.status == ClaimStatus.APPROVED ->
                refreshed.copy(status = ClaimStatus.CANDIDATE, flags = (flags + CHANGED_SINCE_APPROVED).distinct())
            state.payload != state.proposal -> refreshed.copy(flags = (flags + CHANGED_SINCE_EDITED).distinct())
            else -> refreshed.copy(payload = proposal.payload, flags = flags)
        }
    }

    /**
     * A new draft of a story or a moment's page, whatever became of the last one, waits for review: in place of the last
     * one, unless the curator edited it (and did not reject it), which keeps the edits, flagged. One approved is flagged
     * too.
     */
    private fun redraft(refreshed: ClaimState, state: ClaimState, flags: List<FlagReason>): ClaimState {
        val edited = state.payload != state.proposal && state.status != ClaimStatus.REJECTED
        val conflict = when {
            edited -> CHANGED_SINCE_EDITED
            state.status == ClaimStatus.APPROVED -> CHANGED_SINCE_APPROVED
            else -> null
        }
        return refreshed.copy(
            status = ClaimStatus.CANDIDATE,
            payload = if (edited) state.payload else refreshed.proposal,
            flags = (flags + listOfNotNull(conflict)).distinct(),
        )
    }

    /**
     * A record translated again, because its English changed since: the new French waits for review, in place of the
     * old, which was of another English. It keeps the curator's French of the texts whose English stayed the same (the
     * run drafts only the others). One approved is flagged.
     */
    private fun retranslated(refreshed: ClaimState, state: ClaimState, flags: List<FlagReason>): ClaimState {
        val conflict = CHANGED_SINCE_APPROVED.takeIf { state.status == ClaimStatus.APPROVED }
        return refreshed.copy(status = ClaimStatus.CANDIDATE, payload = refreshed.proposal, flags = (flags + listOfNotNull(conflict)).distinct())
    }

    /** The flags a claim's proposal brought, without the conflicts refreshes found. */
    private fun fetchedFlags(flags: List<FlagReason>) = flags - setOf(CHANGED_SINCE_APPROVED, CHANGED_SINCE_EDITED)

    /**
     * What the spec cannot say: no year 0, two different people in a connection with a whole source, a story's cards naming what they
     * show, a moment's page with every line written, and every painting credited.
     */
    private fun check(payload: Payload) {
        val years = when (payload) {
            is PersonFacts -> listOf(payload.born, payload.died) + payload.places.flatMap { listOfNotNull(it.startYear, it.endYear) }
            is Connection -> listOf(payload.year)
            is Event -> listOf(payload.year)
            is Anecdote -> listOf(payload.year)
            is OccupationDomain -> emptyList()
            is Story -> payload.cards.mapNotNull { it.year }
            is MomentPage -> emptyList()
            is Translation -> emptyList()
        }
        if (0 in years) invalid("There is no year 0: 1 BCE is followed by 1 CE")
        if (payload is Connection) {
            if (payload.people.distinct().size != 2) invalid("A connection links two different people")
            check(payload.source)
        }
        if (payload is Story) payload.cards.forEachIndexed { index, card -> check(index + 1, card) }
        if (payload is MomentPage) {
            val lines = with(payload.world) { listOf(payload.hook, governs, everyday, arts, meanwhile) }
            if (lines.any { it.text.isBlank() }) invalid("Write the hook and every line of the world around")
            if (payload.art?.credit?.isBlank() == true) invalid("The moment's painting needs a credit")
        }
        if (payload is Translation && payload.texts.any { it.fr.isBlank() }) invalid("Write every text in French")
    }

    /** A person's Wikipedia articles are Wikidata's: an edit may remove one that is not theirs, never name another. */
    private fun checkArticles(edited: PersonFacts, proposed: PersonFacts) {
        if (edited.enwiki !in setOf(null, proposed.enwiki) || edited.frwiki !in setOf(null, proposed.frwiki)) {
            invalid("A person's Wikipedia article is the one Wikidata links: remove it, or correct it in Wikidata")
        }
    }

    private fun check(number: Int, card: StoryCard) {
        val (named, needs) = when (card.type) {
            CardType.SCENE -> !card.title.isNullOrBlank() to "a title"
            CardType.PERSON -> !card.person.isNullOrBlank() to "a person"
            CardType.LIFE -> !card.life.isNullOrBlank() to "a life"
            CardType.EVENT -> !card.event.isNullOrBlank() to "an event"
        }
        if (!named) invalid("Card $number, ${card.type}, needs $needs")
        if (card.art?.credit?.isBlank() == true) invalid("Card $number's painting needs a credit")
    }

    private fun check(source: SourceRef) {
        val (complete, needs) = when (source.kind) {
            SourceKind.WIKIDATA -> (source.entity != null) to "an entity"
            SourceKind.WIKIPEDIA -> (source.language != null && source.title != null) to "a language and a title"
            SourceKind.BOOK -> (source.title != null && source.page != null) to "a title and a page"
            SourceKind.WEB -> (source.url != null) to "a URL"
            SourceKind.MEDIA -> (source.title != null && (source.url != null || source.episode != null)) to "a title, and a URL or an episode"
        }
        if (!complete) invalid("A ${source.kind} source needs $needs")
    }

    private val QID = Regex("Q[1-9][0-9]*")

    private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
