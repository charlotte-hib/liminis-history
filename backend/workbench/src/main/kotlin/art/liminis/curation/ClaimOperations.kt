package art.liminis.curation

// The inputs and results of the operations Claims offers, in the curation model. The controller turns the API's
// models (api/workbench.yaml) into these and back (ClaimApiMapper), so the service never depends on generated code.
// Never stored as such, so free to change with the service.

/** A claim the curator found, with at least one source, and why they add it. */
data class NewClaim(val payload: Payload, val sources: List<SourceRef>, val note: String? = null)

enum class Verdict { APPROVE, REJECT, NEEDS_WORK }

/** Approve (with an edited [payload], if given), reject or keep for later, with a note saying why. */
data class NewDecision(val verdict: Verdict, val payload: Payload? = null, val note: String? = null)

/** How many claims of a [type] wait, how many were kept for later, and how many waiting ones carry each flag. */
data class Queue(val type: ClaimType, val candidates: Int, val needsWork: Int, val flags: Map<String, Int>)

/** A claim with what it rests on and every decision taken on it, oldest first. */
data class ClaimDetail(val claim: Claim, val sources: List<SourceRef>, val decisions: List<Decision>)

/** A claim the workbench proposes: its record as fetched or drafted, where it came from, its flags and sources. */
data class Proposal(val payload: Payload, val origin: Origin, val flags: List<FlagReason>, val sources: List<SourceRef>)

/** What proposing came to: claims added, claims whose proposal changed, and claims left as they were. */
data class ProposalCounts(val added: Int, val refreshed: Int, val unchanged: Int) {
    override fun toString() = "$added added, $refreshed refreshed, $unchanged unchanged"
}
