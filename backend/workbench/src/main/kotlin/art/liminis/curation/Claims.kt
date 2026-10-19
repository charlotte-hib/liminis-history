package art.liminis.curation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import art.liminis.support.ConflictException
import art.liminis.support.NotFoundException
import art.liminis.support.StaleException
import java.time.OffsetDateTime

/**
 * Claims and the curators' decisions on them. Every change of status or payload is logged with who made it: a curator
 * (through the API), or the workbench's rules when it proposes or refreshes a claim. A curator works on the claims they
 * hold: writing one nobody holds takes it ([ClaimRules.mayWrite]).
 */
@Service
class Claims(
    private val claims: ClaimRepository,
    private val decisions: DecisionRepository,
    private val sources: ClaimSources,
    private val transactions: TransactionTemplate,
    private val jsonMapper: JsonMapper,
) {

    @Transactional(readOnly = true)
    fun detail(id: String): ClaimDetail = detail(find(id))

    /**
     * Claims after [after] in the order they were proposed, at most [limit], of [type], [status] and [flag] if given,
     * and among [ids] if given.
     */
    fun list(type: ClaimType?, status: ClaimStatus?, flag: Flag?, ids: List<String>?, after: Long, limit: Int): List<Claim> =
        claims.list(type?.name, status?.name, flag?.let { """[{"flag": "${it.name}"}]""" }, ids?.let(jsonMapper::writeValueAsString), after, limit)

    /** For each claim type that has claims: how many wait, how many were kept for later, and the waiting ones' flags. */
    fun queues(): List<Queue> {
        val flags = claims.flagCounts().groupBy({ it.type }, { it.flag to it.count.toInt() })
        return claims.queueCounts().map {
            Queue(ClaimType.valueOf(it.type), it.waiting.toInt(), it.needsWork.toInt(), flags[it.type].orEmpty().toMap())
        }
    }

    /** Adds a claim the curator found, waiting for review. */
    @Transactional
    fun add(claim: NewClaim, by: String): ClaimDetail {
        val candidate = Claim.candidate(claim.payload, Origin.MANUAL, ClaimRules.flagsOfNew(claim))
        if (claims.existsById(candidate.id)) throw ConflictException("${candidate.id} exists already")
        val added = claims.save(candidate.also { hold(it, by) })
        sources.attach(added, claim.sources)
        decisions.save(Decision(added.id, by, null, added.status, null, added.payload, claim.note))
        return detail(added)
    }

    /**
     * Adds a claim the curator wrote from [proposal], the text as it stands, and approves it at once as [payload]: the
     * log keeps both. Refused when the claim exists: decide on it instead.
     */
    @Transactional
    fun addApproved(payload: Payload, proposal: Payload, by: String, note: String?): ClaimDetail {
        val id = ClaimRules.idOf(payload)
        if (claims.existsById(id)) throw ConflictException("$id exists already: decide on it")
        // Approved by the same rules as a decision on the proposal, edited.
        val approved = ClaimRules.decide(ClaimState(ClaimStatus.CANDIDATE, proposal, proposal, emptyList()), NewDecision(Verdict.APPROVE, payload, note))
        val claim = Claim(id, ClaimRules.typeOf(payload), approved.status, Origin.MANUAL, approved.payload, proposal, approved.flags)
        val added = claims.save(claim.also { hold(it, by) })
        decisions.save(Decision(id, by, null, added.status, proposal, added.payload, note))
        return detail(added)
    }

    /** Approves (the edited payload, if given), rejects or keeps the claim for later, if it is still at [version]. */
    @Transactional
    fun decide(id: String, version: Long, decision: NewDecision, by: Actor): ClaimDetail {
        val claim = find(id, version)
        write(claim, by)
        val decided = ClaimRules.decide(claim.state, decision)
        decisions.save(Decision(id, by.name, claim.status, decided.status, claim.payload, decided.payload, decision.note))
        claim.state = decided
        return detail(claim)
    }

    /**
     * Puts back the claim as it was before the curator's last decision, if that is the claim's last decision and the
     * claim is still at [version].
     */
    @Transactional
    fun undo(id: String, version: Long, by: Actor): ClaimDetail {
        val claim = find(id, version)
        write(claim, by)
        val last = decisions.findByClaimOrderById(id).last()
        val from = last.from
        if (last.by != by.name || from == null || last.undoes != null) {
            throw ConflictException("The claim's last decision is not one to undo")
        }
        val before = last.payloadBefore!!
        decisions.save(Decision(id, by.name, claim.status, from, claim.payload, before, "Undone", undoes = last.id))
        claim.status = from
        claim.payload = before
        return detail(claim)
    }

    /**
     * Adds the claims of [proposals] that are new, and refreshes those whose proposal changed ([ClaimRules.refresh]):
     * one transaction per claim, so a run stopped half way keeps the claims it got through.
     */
    fun propose(proposals: List<Proposal>): ProposalCounts {
        var added = 0
        var refreshed = 0
        for (proposal in proposals) transactions.executeWithoutResult {
            val id = ClaimRules.idOf(proposal.payload)
            var claim = claims.findById(id).orElse(null)
            when {
                claim == null -> {
                    claim = claims.save(Claim.candidate(proposal.payload, proposal.origin, proposal.flags))
                    decisions.save(Decision(id, ClaimRules.RULES, null, claim.status, null, claim.payload, "Proposed"))
                    added++
                }
                ClaimRules.changes(claim.state, proposal) -> {
                    refreshed++
                    val before = claim.state
                    val after = ClaimRules.refresh(before, proposal)
                    if (after.status != before.status || after.payload != before.payload) {
                        decisions.save(Decision(id, ClaimRules.RULES, before.status, after.status, before.payload, after.payload, "Refreshed"))
                    }
                    claim.state = after
                }
            }
            sources.attach(claim, proposal.sources)
        }
        return ProposalCounts(added, refreshed, proposals.size - added - refreshed)
    }

    /**
     * Makes [holder] the claim's holder, [by] asking: themselves, or, the admin asking, anyone. A claim someone else
     * holds is the admin's alone to hand on.
     */
    @Transactional
    fun take(id: String, by: Actor, holder: String = by.name): ClaimDetail {
        val claim = find(id)
        if (!ClaimRules.mayWrite(claim.heldBy, by)) throw held(claim)
        hold(claim, holder)
        return detail(claim)
    }

    /** Gives the claim back to the queue: its holder's call, or the admin's. */
    @Transactional
    fun release(id: String, by: Actor): ClaimDetail {
        val claim = find(id)
        if (!ClaimRules.mayWrite(claim.heldBy, by)) throw held(claim)
        hold(claim, null)
        return detail(claim)
    }

    /** Takes the open claims among [ids] for [by], leaving those someone else holds to them. */
    @Transactional
    fun takeAll(ids: List<String>, by: Actor): Holds {
        val (free, others) = claims.findAllById(ids).filter { ClaimRules.isOpen(it.status) }
            .partition { it.heldBy == null || it.heldBy == by.name }
        val taken = free.filter { it.heldBy == null }
        taken.forEach { hold(it, by.name) }
        return Holds(taken.size, others.groupingBy { it.heldBy!! }.eachCount())
    }

    /** Releases the claims among [ids] that [by] holds, or, the admin asking, everyone's. */
    @Transactional
    fun releaseAll(ids: List<String>, by: Actor): Holds {
        val held = claims.findAllById(ids).filter { it.heldBy != null && (it.heldBy == by.name || by.admin) }
        held.forEach { hold(it, null) }
        return Holds(held.size)
    }

    /** Releases every claim [username] holds: when the admin revokes them. */
    @Transactional
    fun releaseAllHeldBy(username: String): Holds = Holds(claims.releaseAllHeldBy(username))

    /** Refuses unless [by] may write every claim among [ids] that exists: before a run rewrites them. */
    fun requireWritable(ids: List<String>, by: Actor) {
        claims.findAllById(ids).firstOrNull { !ClaimRules.mayWrite(it.heldBy, by) }?.let { throw held(it) }
    }

    /** Refuses unless [by] may write the claim; one nobody holds becomes theirs. */
    private fun write(claim: Claim, by: Actor) {
        if (!ClaimRules.mayWrite(claim.heldBy, by)) throw held(claim)
        if (claim.heldBy == null) hold(claim, by.name)
    }

    private fun hold(claim: Claim, holder: String?) {
        claim.heldBy = holder
        claim.heldSince = holder?.let { OffsetDateTime.now() }
    }

    private fun held(claim: Claim) = ConflictException("${claim.id} is held by ${claim.heldBy}: only they, or the admin, can change it")

    private fun find(id: String): Claim =
        claims.findById(id).orElseThrow { NotFoundException("No claim $id") }

    /** The claim, if it is still at the [version] the request was based on. */
    private fun find(id: String, version: Long): Claim = find(id).also {
        if (it.version != version) throw StaleException("$id changed since version $version: it is at ${it.version}, read it again")
    }

    private fun detail(claim: Claim) = ClaimDetail(claim, sources.of(claim), decisions.findByClaimOrderById(claim.id))
}
