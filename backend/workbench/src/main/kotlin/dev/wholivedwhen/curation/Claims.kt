package dev.wholivedwhen.curation

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import dev.wholivedwhen.support.ConflictException
import dev.wholivedwhen.support.InvalidRequestException
import dev.wholivedwhen.support.NotFoundException
import dev.wholivedwhen.support.slugify
import java.security.MessageDigest

/** A claim the workbench proposes: its record as fetched or drafted, where it came from, its flags and sources. */
data class Proposal(val payload: Payload, val origin: Origin, val flags: List<FlagReason>, val sources: List<SourceRef>)

/** What proposing came to: claims added, claims whose proposal changed, and claims left as they were. */
data class ProposalCounts(val added: Int, val refreshed: Int, val unchanged: Int) {
    override fun toString() = "$added added, $refreshed refreshed, $unchanged unchanged"
}

/**
 * Claims and the curator's decisions on them. Every change of status or payload is logged with who made it: the
 * curator (through the API), or the workbench's rules when it proposes or refreshes a claim.
 */
@Service
class Claims(
    private val claims: ClaimRepository,
    private val sources: SourceRepository,
    private val decisions: DecisionRepository,
    private val transactions: TransactionTemplate,
    private val jsonMapper: JsonMapper,
) {

    fun detail(id: String): ClaimDetail = transactions.execute { detail(find(id)) }!!

    /** Claims after [after] in the order they were proposed, at most [limit], of [type], [status] and [flag] if given. */
    fun list(type: ClaimType?, status: ClaimStatus?, flag: Flag?, after: Long, limit: Int): List<Claim> =
        claims.list(type?.name, status?.name, flag?.let { json(listOf(mapOf("flag" to it))) }, after, limit)

    /** For each claim type that has claims: how many wait, how many were kept for later, and the waiting ones' flags. */
    fun queues(): List<Queue> {
        val flags = claims.flagCounts().groupBy({ it.type }, { it.flag to it.count.toInt() })
        return claims.queueCounts().map {
            Queue(ClaimType.valueOf(it.type), it.waiting.toInt(), it.needsWork.toInt(), flags[it.type].orEmpty().toMap())
        }
    }

    /** Adds a claim the curator found, waiting for review. Only one source: flagged, for support to be looked for. */
    fun add(claim: NewClaim, by: String): ClaimDetail = transactions.execute {
        if (claim.payload is PersonFacts) badRequest("A person's facts are fetched from Wikidata, not added by hand")
        check(claim.payload)
        claim.sources.forEach(::check)
        val flags = if (claim.sources.size == 1) listOf(FlagReason(Flag.SINGLE_SOURCE, "ONE_SOURCE")) else emptyList()
        val id = idOf(claim.payload)
        if (claims.existsById(id)) throw ConflictException("$id exists already")
        val added = claims.save(Claim(id, typeOf(claim.payload), ClaimStatus.CANDIDATE, Origin.MANUAL, claim.payload, claim.payload, flags))
        addSources(added, claim.sources)
        decisions.save(Decision(id, by, null, ClaimStatus.CANDIDATE, null, claim.payload, claim.note))
        detail(added)
    }!!

    /** Approves (the edited payload, if given), rejects or keeps the claim for later. */
    fun decide(id: String, decision: NewDecision, by: String): ClaimDetail = transactions.execute {
        val claim = find(id)
        val payload = decision.payload ?: claim.payload
        if (decision.payload != null) {
            if (typeOf(payload) != claim.type || subjectOf(payload) != subjectOf(claim.payload)) {
                badRequest("An edit must stay a ${claim.type} about ${subjectOf(claim.payload)}")
            }
            check(payload)
        }
        val status = when (decision.verdict) {
            Verdict.APPROVE -> ClaimStatus.APPROVED
            Verdict.REJECT -> ClaimStatus.REJECTED
            Verdict.NEEDS_WORK -> ClaimStatus.NEEDS_WORK
        }
        if (status != ClaimStatus.APPROVED && decision.note.isNullOrBlank()) badRequest("Say why, in a note")
        decisions.save(Decision(id, by, claim.status, status, claim.payload, payload, decision.note))
        claim.status = status
        claim.payload = payload
        // Deciding settles the conflicts a refresh found.
        claim.flags = fetchedFlags(claim)
        detail(claim)
    }!!

    /** Puts back the claim as it was before the curator's last decision, if that is the claim's last decision. */
    fun undo(id: String, by: String): ClaimDetail = transactions.execute {
        val claim = find(id)
        val last = decisions.findByClaimOrderById(id).last()
        val from = last.from
        if (last.by != by || from == null || last.undoes != null) {
            throw ConflictException("The claim's last decision is not one to undo")
        }
        val before = last.payloadBefore!!
        decisions.save(Decision(id, by, claim.status, from, claim.payload, before, "Undone", undoes = last.id))
        claim.status = from
        claim.payload = before
        detail(claim)
    }!!

    /**
     * Adds the claims of [proposals] that are new, and refreshes those whose proposal changed. A claim not yet decided
     * takes the new proposal, unless the curator edited it. An approved one goes back to review, flagged: what it
     * rests on changed. A rejected one stays rejected.
     */
    fun propose(proposals: List<Proposal>): ProposalCounts {
        var added = 0
        var refreshed = 0
        for (proposal in proposals) transactions.executeWithoutResult {
            val id = idOf(proposal.payload)
            var claim = claims.findById(id).orElse(null)
            when {
                claim == null -> {
                    claim = claims.save(
                        Claim(id, typeOf(proposal.payload), ClaimStatus.CANDIDATE, proposal.origin, proposal.payload, proposal.payload, proposal.flags),
                    )
                    decisions.save(Decision(id, RULES, null, ClaimStatus.CANDIDATE, null, proposal.payload, "Proposed"))
                    added++
                }
                claim.proposal != proposal.payload || fetchedFlags(claim) != proposal.flags -> {
                    refreshed++
                    val (status, payload) = claim.status to claim.payload
                    refresh(claim, proposal)
                    if (claim.status != status || claim.payload != payload) {
                        decisions.save(Decision(id, RULES, status, claim.status, payload, claim.payload, "Refreshed"))
                    }
                }
            }
            addSources(claim, proposal.sources)
        }
        return ProposalCounts(added, refreshed, proposals.size - added - refreshed)
    }

    /** Takes [proposal], keeping the conflicts earlier refreshes found until the curator decides again. */
    private fun refresh(claim: Claim, proposal: Proposal) {
        val conflicts = claim.flags - fetchedFlags(claim).toSet()
        val flags = proposal.flags + conflicts
        if (claim.proposal == proposal.payload) {
            claim.flags = flags
            return
        }
        when {
            claim.status == ClaimStatus.REJECTED -> claim.flags = flags
            claim.status == ClaimStatus.APPROVED -> {
                claim.status = ClaimStatus.CANDIDATE
                claim.flags = (flags + CHANGED_SINCE_APPROVED).distinct()
            }
            claim.payload != claim.proposal -> claim.flags = (flags + CHANGED_SINCE_EDITED).distinct()
            else -> {
                claim.payload = proposal.payload
                claim.flags = flags
            }
        }
        claim.proposal = proposal.payload
    }

    /** The flags the claim's proposal brought, without the conflicts refreshes found. */
    private fun fetchedFlags(claim: Claim) = claim.flags - setOf(CHANGED_SINCE_APPROVED, CHANGED_SINCE_EDITED)

    /** Adds those of [added] the claim does not already rest on, after the ones it has. */
    private fun addSources(claim: Claim, added: List<SourceRef>) {
        val attached = claim.sources.map(::toRef).toMutableList()
        for (source in added.filter { it !in attached }) {
            val locator = json(locatorOf(source))
            sources.addIfMissing(source.kind.name, locator)
            claim.sources += ClaimSource(sources.find(source.kind.name, locator), source.quote)
            attached += source
        }
    }

    private fun find(id: String): Claim =
        claims.findById(id).orElseThrow { NotFoundException("No claim $id") }

    private fun detail(claim: Claim) = ClaimDetail(claim, claim.sources.map(::toRef), decisions.findByClaimOrderById(claim.id))

    private fun toRef(source: ClaimSource): SourceRef =
        jsonMapper.convertValue(source.source.locator + mapOf("kind" to source.source.kind.name, "quote" to source.quote), SourceRef::class.java)

    /** A source's fields but its kind and quote, which the source's identity does not include. */
    private fun locatorOf(source: SourceRef): Map<*, *> =
        jsonMapper.convertValue(source, Map::class.java).filterKeys { it != "kind" && it != "quote" }.filterValues { it != null }

    private fun json(value: Any): String = jsonMapper.writeValueAsString(value)

    /** What the spec cannot say: no year 0, and two different people in a connection. */
    private fun check(payload: Payload) {
        val years = when (payload) {
            is PersonFacts -> listOf(payload.born, payload.died)
            is Connection -> listOf(payload.year)
            is Event -> listOf(payload.year)
            is Anecdote -> listOf(payload.year)
        }
        if (0 in years) badRequest("There is no year 0: 1 BCE is followed by 1 CE")
        if (payload is Connection && payload.people.distinct().size != 2) badRequest("A connection links two different people")
    }

    private fun check(source: SourceRef) {
        val complete = when (source.kind) {
            SourceKind.WIKIDATA -> source.entity != null
            SourceKind.WIKIPEDIA -> source.language != null && source.title != null
            SourceKind.BOOK -> source.title != null && source.page != null
            SourceKind.WEB -> source.url != null
            SourceKind.MEDIA -> source.title != null && (source.url != null || source.episode != null)
        }
        if (!complete) badRequest("A ${source.kind} source needs ${REQUIRED.getValue(source.kind)}")
    }

    private fun badRequest(reason: String): Nothing = throw InvalidRequestException(reason)

    companion object {
        /** Who the workbench's own decisions are logged as: proposing and refreshing claims. */
        const val RULES = "rule"

        private val CHANGED_SINCE_APPROVED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")
        private val CHANGED_SINCE_EDITED = FlagReason(Flag.CONFLICT, "CHANGED_SINCE_EDITED")

        private val REQUIRED = mapOf(
            SourceKind.WIKIDATA to "an entity",
            SourceKind.WIKIPEDIA to "a language and a title",
            SourceKind.BOOK to "a title and a page",
            SourceKind.WEB to "a URL",
            SourceKind.MEDIA to "a title, and a URL or an episode",
        )

        fun typeOf(payload: Payload): ClaimType = when (payload) {
            is PersonFacts -> ClaimType.PERSON_FACTS
            is Connection -> ClaimType.CONNECTION
            is Event -> ClaimType.EVENT
            is Anecdote -> ClaimType.ANECDOTE
        }

        /**
         * A claim's id, from what it is about, so the same thing proposed again lands on the same claim:
         * `person-facts:Q535`, `connection:Q296|Q535:friends`, `event:FR:-52:vercingetorix-surrenders-at-alesia`,
         * `anecdote:Q535:1851:` and the start of the text's SHA-256.
         */
        fun idOf(payload: Payload): String = when (payload) {
            is PersonFacts -> "person-facts:${payload.person}"
            is Connection -> "connection:${payload.people.sorted().joinToString("|")}:${slugify(payload.kind)}"
            is Event -> "event:${payload.region}:${payload.year}:${slugify(payload.title)}"
            is Anecdote -> "anecdote:${payload.person}:${payload.year}:${sha256(payload.text.trim()).take(12)}"
        }

        /** Whom or where a claim is about: an edit must not change it. */
        private fun subjectOf(payload: Payload): String = when (payload) {
            is PersonFacts -> payload.person
            is Connection -> payload.people.sorted().joinToString(" and ")
            is Event -> payload.region
            is Anecdote -> payload.person
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
