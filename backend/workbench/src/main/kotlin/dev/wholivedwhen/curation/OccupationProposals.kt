package dev.wholivedwhen.curation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import dev.wholivedwhen.occupations.HeldOccupation
import dev.wholivedwhen.occupations.Occupations
import dev.wholivedwhen.wikidata.RawStore

/**
 * Proposes a domain claim for every occupation the dataset's people hold, and for the classes above them, the most
 * held first: mapping "visual artist" once covers painters, sculptors and engravers not mapped themselves.
 */
@Component
class OccupationProposals(private val occupations: Occupations, private val raw: RawStore, private val claims: Claims) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun propose(): ProposalCounts {
        val held = occupations.held()
        val revisions = raw.revisions(held.map { it.occupation.qid })
        val counts = claims.propose(held.map { proposal(it, revisions[it.occupation.qid]) })
        log.info("Proposed the domains of {} occupations: {}", held.size, counts)
        return counts
    }

    private fun proposal(held: HeldOccupation, revision: Long?) = Proposal(
        payload = OccupationDomain(held.occupation.qid, held.occupation.label, held.occupation.labelFr),
        origin = Origin.WIKIDATA,
        flags = emptyList(),
        sources = listOf(SourceRef(kind = SourceKind.WIKIDATA, entity = held.occupation.qid, revision = revision)),
    )
}
