package art.liminis.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.PersonFacts
import art.liminis.curation.PersonFactsMapper
import art.liminis.curation.PlaceRole
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.DatasetStore.Companion.ALIVE_IN
import art.liminis.dataset.DatasetStore.Companion.NAMED
import art.liminis.repository.MomentRepository
import art.liminis.support.NotFoundException

/** Which people to review: everyone, or by where their facts claim stands, those nobody proposed yet included. */
enum class ReviewFilter(val sql: String) {
    EVERYONE("true"),
    WAITING("status = 'CANDIDATE'"),
    CONFLICTS("status = 'CANDIDATE' and flags @> '[{\"flag\": \"${Flag.CONFLICT}\"}]'"),
    POORLY_DOCUMENTED("status = 'CANDIDATE' and flags @> '[{\"flag\": \"${Flag.POORLY_DOCUMENTED}\"}]'"),
    KEPT_FOR_LATER("status = 'NEEDS_WORK'"),
    DECIDED("status in ('APPROVED', 'REJECTED')"),
    NOT_PROPOSED("status is null"),
}

/** Everyone, or everyone alive in [moment]; those [query] names, if given; those [filter] keeps. */
data class ReviewScope(val moment: String?, val filter: ReviewFilter, val query: String?)

/** A person to review, as their claim has them, else as the dataset does. */
data class PersonToReview(
    val person: String,
    val label: String,
    val labelFr: String?,
    val born: Int,
    val died: Int,
    val sitelinks: Int,
    val occupation: String?,
    val birthplace: String?,
    val deathplace: String?,
    val claim: ClaimStatus?,
    val heldBy: String?,
    val flags: List<FlagReason>,
)

data class PeopleToReview(val total: Int, val counts: Map<ReviewFilter, Int>, val people: List<PersonToReview>)

/** Where a person stands among those a scope keeps: [position] from 1, none when it doesn't keep them. */
data class ReviewNeighbours(val total: Int, val position: Int?, val previous: String?, val next: String?)

/**
 * The people to review, the best known first: everyone the dataset holds, or, in a moment, everyone alive there (as
 * [art.liminis.moments.Moments.people] lists them), each with their facts claim if one was proposed.
 */
@Service
class PeopleReview(
    private val jdbc: JdbcTemplate,
    private val moments: MomentRepository,
    private val claims: ClaimRepository,
    private val dataset: DatasetStore,
    private val factsMapper: PersonFactsMapper,
) {

    @Transactional(readOnly = true)
    fun list(scope: ReviewScope, offset: Int, limit: Int): PeopleToReview {
        val (base, args) = base(scope)
        val counts = jdbc.queryForMap(
            "with base as ($base) select ${ReviewFilter.entries.joinToString { "count(*) filter (where ${it.sql}) as ${it.name}" }} from base",
            *args.toTypedArray(),
        ).let { row -> ReviewFilter.entries.associateWith { (row[it.name] as Number).toInt() } }
        val page = jdbc.query(
            "with base as ($base) select qid, sitelinks from base where ${scope.filter.sql} order by sitelinks desc, qid limit ? offset ?",
            { rs, _ -> rs.getString("qid") to rs.getInt("sitelinks") },
            *(args + limit + offset).toTypedArray(),
        )
        return PeopleToReview(counts.getValue(scope.filter), counts, rows(page))
    }

    /**
     * Who comes just before and after [qid] among the people [scope] keeps, by where they stand or would stand: one
     * just decided has left the waiting, but the curator moves on from them.
     */
    @Transactional(readOnly = true)
    fun neighbours(qid: String, scope: ReviewScope): ReviewNeighbours {
        // Someone whose dataset row a mapping run dropped stands with the least known, as the list has them.
        val sitelinks = jdbc.query("select sitelinks from dataset.person where qid = ?", { rs, _ -> rs.getInt(1) }, qid).singleOrNull()
            ?: 0.takeIf { claims.existsById(ClaimRules.factsId(qid)) }
            ?: throw NotFoundException("Unknown person: $qid")
        val (base, args) = base(scope)
        // Best known first, then by Q-id: before [qid] are those with more sitelinks, or as many and a lower Q-id.
        val before = "(sitelinks > ? or (sitelinks = ? and qid < ?))"
        val after = "(sitelinks < ? or (sitelinks = ? and qid > ?))"
        val key = listOf(sitelinks, sitelinks, qid)
        return jdbc.queryForObject(
            """
            with base as ($base), kept as (select qid, sitelinks from base where ${scope.filter.sql})
            select (select count(*) from kept) as total,
                case when exists (select 1 from kept where qid = ?) then (select count(*) + 1 from kept where $before) end as position,
                (select qid from kept where $before order by sitelinks, qid desc limit 1) as previous,
                (select qid from kept where $after order by sitelinks desc, qid limit 1) as next
            """.trimIndent(),
            { rs, _ -> ReviewNeighbours(rs.getInt("total"), (rs.getObject("position") as Number?)?.toInt(), rs.getString("previous"), rs.getString("next")) },
            *(args + qid + key + key + key).toTypedArray(),
        )!!
    }

    /**
     * The people [scope] reviews, before its filter, each with their claim's status and flags: SQL and its parameters.
     * Without a moment, the claims too, so a claim whose person a mapping run dropped stays in review.
     */
    private fun base(scope: ReviewScope): Pair<String, List<Any>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (scope.moment != null) {
            val moment = moments.findById(scope.moment).orElseThrow { NotFoundException("No moment ${scope.moment}") }
            where += ALIVE_IN
            args.addAll(listOf(moment.endYear, moment.startYear, moment.region.code))
        }
        scope.query?.trim()?.let { query ->
            where += "($NAMED)"
            args.addAll(listOf(query.uppercase(), query, query))
        }
        val clause = if (where.isEmpty()) "" else where.joinToString(" and ", prefix = "where ")
        val sql = if (scope.moment != null) {
            """
            select p.qid, p.sitelinks, c.status, c.flags
            from dataset.person p
            left join curation.claim c on c.id = '${ClaimRules.factsId("")}' || p.qid
            $clause
            """.trimIndent()
        } else {
            """
            select coalesce(p.qid, c.payload ->> 'person') as qid, coalesce(p.sitelinks, 0) as sitelinks, c.status, c.flags
            from dataset.person p
            full join (select * from curation.claim where type = '${ClaimType.PERSON_FACTS}') c
                on c.id = '${ClaimRules.factsId("")}' || p.qid
            $clause
            """.trimIndent()
        }
        return sql to args
    }

    /** The people of [page], in its order: as their claim has them, else as the dataset does. */
    private fun rows(page: List<Pair<String, Int>>): List<PersonToReview> {
        val claimed = claims.findAllById(page.map { (qid) -> ClaimRules.factsId(qid) }).associateBy { (it.payload as PersonFacts).person }
        val fetched = dataset.people(page.map { it.first } - claimed.keys).associate { it.qid to factsMapper.facts(it) }
        return page.mapNotNull { (qid, sitelinks) ->
            val claim = claimed[qid]
            val facts = claim?.payload as PersonFacts? ?: fetched[qid] ?: return@mapNotNull null
            PersonToReview(
                person = qid,
                label = facts.label,
                labelFr = facts.labelFr,
                born = facts.born,
                died = facts.died,
                sitelinks = sitelinks,
                occupation = facts.occupations.firstOrNull()?.label,
                birthplace = facts.places.firstOrNull { it.role == PlaceRole.BIRTH }?.label,
                deathplace = facts.places.firstOrNull { it.role == PlaceRole.DEATH }?.label,
                claim = claim?.status,
                heldBy = claim?.heldBy,
                flags = claim?.flags.orEmpty(),
            )
        }
    }
}
