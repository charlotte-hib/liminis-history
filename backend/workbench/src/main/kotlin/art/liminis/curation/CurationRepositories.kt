package art.liminis.curation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** How many claims of a type wait, and how many were kept for later. */
interface QueueCount {
    val type: String
    val waiting: Long
    val needsWork: Long
}

/** How many waiting claims of a type carry a flag. */
interface FlagCount {
    val type: String
    val flag: String
    val count: Long
}

interface ClaimRepository : JpaRepository<Claim, String> {

    /**
     * Claims after [after] in the order they were proposed, at most [limit], of [type], [status] and [flags] if given,
     * and among [ids] (a JSON array) if given.
     */
    @Query(
        nativeQuery = true,
        value = """
            select * from curation.claim
            where seq > :after and (cast(:type as text) is null or type = :type)
                and (cast(:status as text) is null or status = :status)
                and (cast(:flags as jsonb) is null or flags @> cast(:flags as jsonb))
                and (cast(:ids as jsonb) is null or jsonb_exists(cast(:ids as jsonb), id))
            order by seq
            limit :limit
            """,
    )
    fun list(type: String?, status: String?, flags: String?, ids: String?, after: Long, limit: Int): List<Claim>

    /** Every claim of [type] in [status], by id. */
    fun findByTypeAndStatusOrderById(type: ClaimType, status: ClaimStatus): List<Claim>

    @Query(
        nativeQuery = true,
        value = """
            select type, count(*) filter (where status = 'CANDIDATE') as waiting,
                count(*) filter (where status = 'NEEDS_WORK') as "needsWork"
            from curation.claim
            group by type
            order by type
            """,
    )
    fun queueCounts(): List<QueueCount>

    @Query(
        nativeQuery = true,
        value = """
            select c.type, f ->> 'flag' as flag, count(distinct c.id) as count
            from curation.claim c, jsonb_array_elements(c.flags) f
            where c.status = 'CANDIDATE'
            group by 1, 2
            order by 1, 2
            """,
    )
    fun flagCounts(): List<FlagCount>
}

interface SourceRepository : JpaRepository<Source, Long> {

    /** Adds the source unless it is there already: another claim may rest on it, or be adding it right now. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "insert into curation.source (kind, locator) values (:kind, cast(:locator as jsonb)) on conflict (kind, locator) do nothing",
    )
    fun addIfMissing(kind: String, locator: String)

    @Query(nativeQuery = true, value = "select * from curation.source where kind = :kind and locator = cast(:locator as jsonb)")
    fun find(kind: String, locator: String): Source
}

interface DecisionRepository : JpaRepository<Decision, Long> {

    /** The claim's decisions, oldest first. */
    fun findByClaimOrderById(claim: String): List<Decision>
}
