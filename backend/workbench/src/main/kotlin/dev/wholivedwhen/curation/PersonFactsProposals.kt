package dev.wholivedwhen.curation

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import dev.wholivedwhen.dataset.DatasetPerson
import dev.wholivedwhen.dataset.DatasetStore
import dev.wholivedwhen.repository.MomentRepository
import dev.wholivedwhen.wikidata.RawStore

@ConfigurationProperties("app.curation")
data class CurationProperties(
    /** How many people of each moment have their facts proposed for review, the best known first. */
    val peoplePerMoment: Int,
)

/**
 * Proposes the facts of the people of the release's moments, one claim per person, for the curator's first review:
 * the people born in the moment's region and alive during it, the best known first. What the curator corrects there
 * tells which fetched fields can be trusted.
 */
@Component
class PersonFactsProposals(
    private val moments: MomentRepository,
    private val dataset: DatasetStore,
    private val raw: RawStore,
    private val claims: Claims,
    private val properties: CurationProperties,
    private val mapper: PersonFactsMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun propose(): ProposalCounts {
        val qids = moments.findAll().sortedBy { it.id }
            .flatMap { dataset.bornIn(it.region.code, it.startYear, it.endYear, properties.peoplePerMoment) }
            .distinct()
        val people = dataset.people(qids).associateBy { it.qid }
        val revisions = raw.revisions(qids)
        val counts = claims.propose(qids.mapNotNull(people::get).map { proposal(it, revisions[it.qid]) })
        log.info("Proposed the facts of {} people: {}", qids.size, counts)
        return counts
    }

    private fun proposal(person: DatasetPerson, revision: Long?) = Proposal(
        payload = mapper.facts(person),
        origin = Origin.WIKIDATA,
        flags = person.flags.map { (flag, reason) -> FlagReason(Flag.valueOf(flag), reason) },
        sources = listOfNotNull(
            SourceRef(kind = SourceKind.WIKIDATA, entity = person.qid, revision = revision),
            person.enwiki?.let { SourceRef(kind = SourceKind.WIKIPEDIA, language = "en", title = it) },
            person.frwiki?.let { SourceRef(kind = SourceKind.WIKIPEDIA, language = "fr", title = it) },
        ),
    )
}
