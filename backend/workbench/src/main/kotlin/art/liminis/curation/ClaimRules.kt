package art.liminis.curation

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

    private val CHANGED_SINCE_APPROVED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")
    private val CHANGED_SINCE_EDITED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_EDITED")
    private val ONE_SOURCE = FlagReason(Flag.SINGLE_SOURCE, "ONE_SOURCE")

    fun typeOf(payload: Payload): ClaimType = when (payload) {
        is PersonFacts -> ClaimType.PERSON_FACTS
        is Connection -> ClaimType.CONNECTION
        is Event -> ClaimType.EVENT
        is Anecdote -> ClaimType.ANECDOTE
        is OccupationDomain -> ClaimType.OCCUPATION_DOMAIN
    }

    /**
     * A claim's id, from what it is about, so the same thing proposed again lands on the same claim:
     * `person-facts:Q535`, `connection:Q296|Q535:friends`, `event:FR:-52:vercingetorix-surrenders-at-alesia`,
     * `anecdote:Q535:1851:` and the start of the text's SHA-256, `occupation:Q1028181`.
     */
    fun idOf(payload: Payload): String = when (payload) {
        is PersonFacts -> "person-facts:${payload.person}"
        is Connection -> "connection:${payload.people.sorted().joinToString("|")}:${slugify(payload.kind)}"
        is Event -> "event:${payload.region}:${payload.year}:${slugify(payload.title)}"
        is Anecdote -> "anecdote:${payload.person}:${payload.year}:${sha256(payload.text.trim()).take(12)}"
        is OccupationDomain -> "occupation:${payload.occupation}"
    }

    /** Whom or where a claim is about: an edit must not change it. */
    fun subjectOf(payload: Payload): String = when (payload) {
        is PersonFacts -> payload.person
        is Connection -> payload.people.sorted().joinToString(" and ")
        is Event -> payload.region
        is Anecdote -> payload.person
        is OccupationDomain -> payload.occupation
    }

    /**
     * Checks a claim the curator adds by hand, and returns the flags it starts with. A person's facts and the
     * occupations to map only come from Wikidata; a claim resting on one source is flagged, for support to be looked for.
     */
    fun flagsOfNew(claim: NewClaim): List<FlagReason> {
        if (claim.payload is PersonFacts) invalid("A person's facts are fetched from Wikidata, not added by hand")
        if (claim.payload is OccupationDomain) invalid("Occupations are proposed from the dataset, not added by hand")
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

    /** Whether [proposal] differs from what the claim in [state] was last proposed with. */
    fun changes(state: ClaimState, proposal: Proposal): Boolean =
        state.proposal != proposal.payload || fetchedFlags(state.flags) != proposal.flags

    /**
     * What a new [proposal] makes of a claim in [state], keeping the conflicts earlier refreshes found until the
     * curator decides again. A claim not yet decided takes the new proposal, unless the curator edited it. An approved
     * one goes back to review, flagged: what it rests on changed. A rejected one stays rejected.
     */
    fun refresh(state: ClaimState, proposal: Proposal): ClaimState {
        val conflicts = state.flags - fetchedFlags(state.flags).toSet()
        val flags = proposal.flags + conflicts
        if (state.proposal == proposal.payload) return state.copy(flags = flags)
        val refreshed = state.copy(proposal = proposal.payload)
        return when {
            state.status == ClaimStatus.REJECTED -> refreshed.copy(flags = flags)
            state.status == ClaimStatus.APPROVED ->
                refreshed.copy(status = ClaimStatus.CANDIDATE, flags = (flags + CHANGED_SINCE_APPROVED).distinct())
            state.payload != state.proposal -> refreshed.copy(flags = (flags + CHANGED_SINCE_EDITED).distinct())
            else -> refreshed.copy(payload = proposal.payload, flags = flags)
        }
    }

    /** The flags a claim's proposal brought, without the conflicts refreshes found. */
    private fun fetchedFlags(flags: List<FlagReason>) = flags - setOf(CHANGED_SINCE_APPROVED, CHANGED_SINCE_EDITED)

    /** What the spec cannot say: no year 0, and two different people in a connection. */
    private fun check(payload: Payload) {
        val years = when (payload) {
            is PersonFacts -> listOf(payload.born, payload.died)
            is Connection -> listOf(payload.year)
            is Event -> listOf(payload.year)
            is Anecdote -> listOf(payload.year)
            is OccupationDomain -> emptyList()
        }
        if (0 in years) invalid("There is no year 0: 1 BCE is followed by 1 CE")
        if (payload is Connection && payload.people.distinct().size != 2) invalid("A connection links two different people")
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

    private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
