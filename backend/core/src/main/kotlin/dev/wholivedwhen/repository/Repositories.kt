package dev.wholivedwhen.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import dev.wholivedwhen.domain.CardType
import dev.wholivedwhen.domain.Connection
import dev.wholivedwhen.domain.Door
import dev.wholivedwhen.domain.Era
import dev.wholivedwhen.domain.Event
import dev.wholivedwhen.domain.Life
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.domain.PublicationStatus
import dev.wholivedwhen.domain.Region
import dev.wholivedwhen.domain.StoryCard

// Every list is sorted down to a unique key (an id where nothing else decides), so the API answers the same
// whatever order the rows were stored in.

/** How many cards a moment's story has. */
data class CardCount(val momentId: String, val count: Long)

interface RegionRepository : JpaRepository<Region, String> {
    fun findAllByOrderByName(): List<Region>
}

interface EraRepository : JpaRepository<Era, String> {
    @EntityGraph(attributePaths = ["region"])
    fun findAllByOrderByStartYearAscIdAsc(): List<Era>

    @EntityGraph(attributePaths = ["region"])
    fun findByRegionCodeOrderByStartYearAscIdAsc(code: String): List<Era>
}

interface PersonRepository : JpaRepository<Person, String> {
    @EntityGraph(attributePaths = ["region"])
    @Query(
        """
        select p from Person p
        where p.region.code = :region
          and p.birthYear <= :end and (p.deathYear is null or p.deathYear >= :start)
        order by p.birthYear, p.id
        """
    )
    fun findAliveBetween(region: String, start: Int, end: Int): List<Person>

    /** Everyone alive in [year] outside [region]. */
    @EntityGraph(attributePaths = ["region"])
    @Query(
        """
        select p from Person p
        where p.region.code <> :region
          and p.birthYear <= :year and (p.deathYear is null or p.deathYear >= :year)
        order by p.birthYear, p.id
        """
    )
    fun findAliveElsewhere(region: String, year: Int): List<Person>

    @EntityGraph(attributePaths = ["region"])
    override fun findAll(): List<Person>

    /** People with an article to fetch: those the fetched dataset gave none, so with no link to it yet. */
    fun findByWikipediaTitleIsNotNullAndWikipediaUrlIsNull(): List<Person>

    /**
     * People whose name contains [pattern], ignoring case and accents: names that start with it first, then in order of
     * birth. [pattern] has `\`, `%` and `_` escaped for `like`. Served by the trigram index on `search_name`.
     */
    @Query(
        nativeQuery = true,
        value = """
        select p.* from {h-schema}person p
        where p.search_name like '%' || {h-schema}fold_for_search(:pattern) || '%' escape '\'
        order by p.search_name like {h-schema}fold_for_search(:pattern) || '%' escape '\' desc, p.birth_year, p.id
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
    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findByEraIdOrderByYearAscIdAsc(eraId: String): List<Event>

    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(code: String, start: Int, end: Int): List<Event>

    @EntityGraph(attributePaths = ["participants", "participants.person", "participants.person.region"])
    fun findDistinctByParticipantsPersonSlugOrderByYearAscIdAsc(slug: String): List<Event>
}

interface ConnectionRepository : JpaRepository<Connection, Long> {
    @EntityGraph(attributePaths = ["first", "first.region", "second", "second.region"])
    @Query(
        """
        select c from Connection c where c.first.slug = :slug or c.second.slug = :slug
        order by c.year, c.first.id, c.second.id, c.kind
        """
    )
    fun findInvolving(slug: String): List<Connection>
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

    @Query("select new dev.wholivedwhen.repository.CardCount(c.moment.id, count(c)) from StoryCard c group by c.moment.id")
    fun countByMoment(): List<CardCount>

    /** The person cards of every story: each story's cast. */
    @EntityGraph(attributePaths = ["person", "person.region"])
    fun findByTypeOrderByPosition(type: CardType): List<StoryCard>
}

interface DoorRepository : JpaRepository<Door, Long> {
    @EntityGraph(attributePaths = ["target", "target.region", "faces", "faces.region"])
    fun findByOriginIdOrderByPosition(momentId: String): List<Door>
}
