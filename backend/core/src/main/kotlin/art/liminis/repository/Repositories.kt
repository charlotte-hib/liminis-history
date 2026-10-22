package art.liminis.repository

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import art.liminis.domain.Anecdote
import art.liminis.domain.CardType
import art.liminis.domain.Connection
import art.liminis.domain.Domain
import art.liminis.domain.Door
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.Life
import art.liminis.domain.Moment
import art.liminis.domain.Person
import art.liminis.domain.PublicationStatus
import art.liminis.domain.Region
import art.liminis.domain.StoryCard

// Every list is sorted down to a unique key (an id where nothing else decides), so the API answers the same
// whatever order the rows were stored in.

/** How many cards a moment's story has. */
data class CardCount(val momentId: String, val count: Long)

/** Someone alive in a moment, as ranking needs them: how widely written about, and whether tied to its place. */
data class MomentCandidate(val id: String, val sitelinks: Int, val atPlace: Boolean)

// People tied to a region: born there (their own region), or with a place of their lives there.
private const val TIED_TO_REGION = """
    (p.region.code = :region or exists (select 1 from Person q join q.places pl where q = p and pl.regionCode = :region))
"""
private const val ALIVE_BETWEEN = "p.birthYear <= :end and (p.deathYear is null or p.deathYear >= :start)"

interface RegionRepository : JpaRepository<Region, String> {
    fun findAllByOrderByName(): List<Region>

    /** The regions whose eras are written: the others only hold the people born there. */
    @Query("select r from Region r where exists (select 1 from Era e where e.region = r) order by r.name, r.code")
    fun findWithEras(): List<Region>
}

interface EraRepository : JpaRepository<Era, String> {
    @EntityGraph(attributePaths = ["region"])
    fun findAllByOrderByStartYearAscIdAsc(): List<Era>

    @EntityGraph(attributePaths = ["region"])
    fun findByRegionCodeOrderByStartYearAscIdAsc(code: String): List<Era>
}

interface PersonRepository : JpaRepository<Person, String> {
    @EntityGraph(attributePaths = ["region"])
    override fun findAll(): List<Person>

    @EntityGraph(attributePaths = ["region"])
    fun findByIdIn(ids: Collection<String>): List<Person>

    /** Those of [qids] the release holds, by their Q-ids. */
    fun findByQidIn(qids: Collection<String>): List<Person>

    /** People alive at some point from [start] to [end] and tied to [region], the most written about first. */
    @EntityGraph(attributePaths = ["region"])
    @Query("select p from Person p where $ALIVE_BETWEEN and $TIED_TO_REGION order by p.sitelinks desc, p.id")
    fun findTiedAliveBetween(region: String, start: Int, end: Int, limit: Limit): List<Person>

    /** People of [region] (born there) alive in [year], the most written about first. */
    @EntityGraph(attributePaths = ["region"])
    @Query("select p from Person p where p.region.code = :region and p.birthYear <= :year and (p.deathYear is null or p.deathYear >= :year) order by p.sitelinks desc, p.id")
    fun findOfRegionAlive(region: String, year: Int, limit: Limit): List<Person>

    /** As [findTiedAliveBetween], in one [domain]. */
    @EntityGraph(attributePaths = ["region"])
    @Query("select p from Person p where p.domain = :domain and $ALIVE_BETWEEN and $TIED_TO_REGION order by p.sitelinks desc, p.id")
    fun findTiedAliveBetween(region: String, start: Int, end: Int, domain: Domain, limit: Limit): List<Person>

    /** Everyone alive in [year] and tied to [region], with whether a place of theirs is [place]. */
    @Query(
        """
        select new art.liminis.repository.MomentCandidate(
            p.id, p.sitelinks,
            case when exists (select 1 from Person q join q.places pl where q = p and pl.placeQid = :place) then true else false end
        )
        from Person p
        where p.birthYear <= :year and (p.deathYear is null or p.deathYear >= :year) and $TIED_TO_REGION
        """
    )
    fun findMomentCandidates(region: String, year: Int, place: String?): List<MomentCandidate>

    /** People with an article to fetch: those the fetched dataset gave none, so with no link to it yet. */
    fun findByWikipediaTitleIsNotNullAndWikipediaUrlIsNull(): List<Person>

    /** People with a French article to fetch, as [findByWikipediaTitleIsNotNullAndWikipediaUrlIsNull]. */
    fun findByWikipediaTitleFrIsNotNullAndWikipediaUrlFrIsNull(): List<Person>

    /**
     * People whose name contains [pattern], ignoring case and accents: names that start with it first, then the most
     * famous in their time and place, then in order of birth. [pattern] has `\`, `%` and `_` escaped for `like`. Served by the trigram index on `search_name`.
     */
    @Query(
        nativeQuery = true,
        value = """
        select p.* from {h-schema}person p
        where p.search_name like '%' || {h-schema}fold_for_search(:pattern) || '%' escape '\'
        order by p.search_name like {h-schema}fold_for_search(:pattern) || '%' escape '\' desc, p.fame desc, p.birth_year, p.id
        limit :limit
        """,
    )
    fun findNameContaining(pattern: String, limit: Int): List<Person>

    /**
     * People whose name nearly contains [query], for typos ("cezane" finds Paul Cézanne): pg_trgm's word similarity, at
     * its default threshold, closest first. Served by the trigram index, but slow for short, common queries.
     */
    @Query(
        nativeQuery = true,
        value = """
        select p.* from {h-schema}person p
        where {h-schema}fold_for_search(:query) operator(extensions.<%) p.search_name
        order by extensions.word_similarity({h-schema}fold_for_search(:query), p.search_name) desc, p.birth_year, p.id
        limit :limit
        """,
    )
    fun findNameSimilar(query: String, limit: Int): List<Person>

    @EntityGraph(attributePaths = ["region"])
    fun findBySlug(slug: String): Person?
}

interface LifeRepository : JpaRepository<Life, String> {
    @Query(
        """
        select l from Life l
        where l.era.region.code = :region and l.startYear <= :end and l.endYear >= :start
        order by l.startYear, l.id
        """
    )
    fun findLivedBetween(region: String, start: Int, end: Int): List<Life>
}

interface EventRepository : JpaRepository<Event, String> {
    /** Everyone who took part in an event. */
    @Query("select distinct ep.person.id from EventParticipant ep")
    fun findParticipantIds(): Set<String>

    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findByEraIdOrderByYearAscIdAsc(eraId: String): List<Event>

    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(code: String, start: Int, end: Int): List<Event>

    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findDistinctByParticipantsPersonSlugOrderByYearAscIdAsc(slug: String): List<Event>
}

interface ConnectionRepository : JpaRepository<Connection, Long> {
    /** Everyone in a curated connection. */
    @Query("select c.first.id from Connection c union select c.second.id from Connection c")
    fun findConnectedIds(): Set<String>

    @EntityGraph(attributePaths = ["first", "first.region", "second", "second.region"])
    @Query(
        """
        select c from Connection c where c.first.slug = :slug or c.second.slug = :slug
        order by c.year, c.first.id, c.second.id, c.kind
        """
    )
    fun findInvolving(slug: String): List<Connection>
}

interface AnecdoteRepository : JpaRepository<Anecdote, String> {
    fun findByPersonIdOrderByYearAscIdAsc(personId: String): List<Anecdote>
}

interface MomentRepository : JpaRepository<Moment, String> {
    @EntityGraph(attributePaths = ["region"])
    override fun findById(id: String): Optional<Moment>

    @EntityGraph(attributePaths = ["region"])
    fun findByStatusOrderByFocusYearAscIdAsc(status: PublicationStatus): List<Moment>

    @EntityGraph(attributePaths = ["region"])
    fun findByIdAndStatus(id: String, status: PublicationStatus): Moment?
}

interface StoryCardRepository : JpaRepository<StoryCard, Long> {
    @EntityGraph(attributePaths = ["person", "person.region", "life", "event", "event.participants", "event.participants.person", "event.participants.person.region"])
    fun findByMomentIdOrderByPosition(momentId: String): List<StoryCard>

    @Query("select new art.liminis.repository.CardCount(c.moment.id, count(c)) from StoryCard c group by c.moment.id")
    fun countByMoment(): List<CardCount>

    /** The person cards of every story: each story's cast. */
    @EntityGraph(attributePaths = ["person", "person.region"])
    fun findByTypeOrderByPosition(type: CardType): List<StoryCard>
}

interface DoorRepository : JpaRepository<Door, Long> {
    @EntityGraph(attributePaths = ["target", "target.region", "faces", "faces.region"])
    fun findByOriginIdOrderByPosition(momentId: String): List<Door>
}
