package dev.wholivedwhen.curation

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import dev.wholivedwhen.support.ConflictException
import dev.wholivedwhen.support.NotFoundException

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

    /** Adds a claim the curator found, waiting for review. */
    fun add(claim: NewClaim, by: String): ClaimDetail = transactions.execute {
        val flags = ClaimRules.flagsOfNew(claim)
        val id = ClaimRules.idOf(claim.payload)
        if (claims.existsById(id)) throw ConflictException("$id exists already")
        val type = ClaimRules.typeOf(claim.payload)
        val added = claims.save(Claim(id, type, ClaimStatus.CANDIDATE, Origin.MANUAL, claim.payload, claim.payload, flags))
        addSources(added, claim.sources)
        decisions.save(Decision(id, by, null, ClaimStatus.CANDIDATE, null, claim.payload, claim.note))
        detail(added)
    }!!

    /** Approves (the edited payload, if given), rejects or keeps the claim for later. */
    fun decide(id: String, decision: NewDecision, by: String): ClaimDetail = transactions.execute {
        val claim = find(id)
        val decided = ClaimRules.decide(claim.state, decision)
        decisions.save(Decision(id, by, claim.status, decided.status, claim.payload, decided.payload, decision.note))
        claim.state = decided
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

    /** Adds the claims of [proposals] that are new, and refreshes those whose proposal changed ([ClaimRules.refresh]). */
    fun propose(proposals: List<Proposal>): ProposalCounts {
        var added = 0
        var refreshed = 0
        for (proposal in proposals) transactions.executeWithoutResult {
            val id = ClaimRules.idOf(proposal.payload)
            var claim = claims.findById(id).orElse(null)
            when {
                claim == null -> {
                    val type = ClaimRules.typeOf(proposal.payload)
                    claim = claims.save(Claim(id, type, ClaimStatus.CANDIDATE, proposal.origin, proposal.payload, proposal.payload, proposal.flags))
                    decisions.save(Decision(id, ClaimRules.RULES, null, ClaimStatus.CANDIDATE, null, proposal.payload, "Proposed"))
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
            addSources(claim, proposal.sources)
        }
        return ProposalCounts(added, refreshed, proposals.size - added - refreshed)
    }

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
}
