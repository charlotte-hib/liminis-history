package art.liminis.service

import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.domain.Person
import art.liminis.domain.PublicationStatus.PUBLISHED
import art.liminis.repository.AnecdoteRepository
import art.liminis.repository.ConnectionRepository
import art.liminis.repository.EraRepository
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import art.liminis.repository.RegionRepository
import art.liminis.support.NotFoundException
import art.liminis.support.currentYear
import art.liminis.support.yearsBetween
import art.liminis.web.ApiMapper
import art.liminis.api.model.ConnectionDto
import art.liminis.api.model.PersonDetailDto
import art.liminis.api.model.PersonDto
import kotlin.math.abs

/** The age a person is shown at when no year is asked for: well into their adult life. */
private const val PRIME_AGE = 35

@Service
@Transactional(readOnly = true)
class PersonService(
    private val people: PersonRepository,
    private val regions: RegionRepository,
    private val lives: LifeRepository,
    private val eras: EraRepository,
    private val events: EventRepository,
    private val connections: ConnectionRepository,
    private val anecdotes: AnecdoteRepository,
    private val moments: MomentRepository,
    private val momentService: MomentService,
    private val mapper: ApiMapper,
) {
    /** A person and the world around them in [year], or in their prime if they were not alive then. */
    fun detail(slug: String, year: Int?): PersonDetailDto {
        val person = people.findBySlug(slug)
            ?: throw NotFoundException("Unknown person: $slug")
        val region = person.region.code
        val end = person.deathYear ?: currentYear()
        val ref = year?.takeIf { it in person.birthYear..end } ?: minOf(end, person.birthYear + PRIME_AGE)

        // The written moment of their place whose focus is closest to the year we look at.
        val worldMoment = moments.findByStatusOrderByFocusYearAscIdAsc(PUBLISHED)
            .filter { it.region.code == region && it.world != null && it.startYear <= end && it.endYear >= person.birthYear }
            .minByOrNull { abs(it.focusYear - ref) }

        return PersonDetailDto(
            person = mapper.toDto(person),
            about = person.about,
            wikipediaUrl = person.wikipediaUrl,
            year = ref,
            age = yearsBetween(person.birthYear, ref),
            world = worldMoment?.world?.let(mapper::toDto),
            worldMoment = worldMoment?.let { momentService.summarize(listOf(it)).single() },
            lifeline = lifeInTime(
                person,
                eras.findByRegionCodeOrderByStartYearAscIdAsc(region),
                events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, person.birthYear, end),
                anecdotes.findByPersonIdOrderByYearAscIdAsc(person.id),
                mapper,
            ),
            connections = connectionsOf(person),
            aroundPeople = people.findTiedAliveBetween(region, ref, ref, Limit.of(AROUND + 1)).filter { it.slug != slug }.take(AROUND).map(mapper::toDto),
            aroundLives = lives.findLivedBetween(region, ref, ref).map(mapper::toDto),
            elsewhere = aliveElsewhere(region, ref),
            moments = momentService.overlapping(region, person.birthYear, end),
        )
    }

    /**
     * Everyone [person] is documented to have known: curated connections, then the other people of each event
     * they took part in (with their role in it), unless a curated connection already links the pair. In order of year.
     */
    fun connectionsOf(person: Person): List<ConnectionDto> {
        val curated = connections.findInvolving(person.slug).map { connection ->
            mapper.toDto(connection, connection.other(person))
        }
        val linked = curated.map { it.person.slug }.toSet()
        val shared = events.findDistinctByParticipantsPersonSlugOrderByYearAscIdAsc(person.slug).flatMap { event ->
            event.participants
                .filter { it.person.slug != person.slug && it.person.slug !in linked }
                .map { ConnectionDto(mapper.toDto(it.person), it.role, event.year, "${event.title}. ${event.description}", event.sourceUrl) }
        }
        return (curated + shared).sortedBy { it.year }
    }

    /**
     * A few people alive in [year] in each other region with eras: among the most written about there, those in the
     * middle of their lives.
     */
    fun aliveElsewhere(region: String, year: Int, perRegion: Int = 2): List<PersonDto> =
        regions.findWithEras().map { it.code }.filter { it != region }.sorted()
            .flatMap { other ->
                people.findOfRegionAlive(other, year, Limit.of(POOL))
                    .sortedBy { abs(yearsBetween(it.birthYear, year) - MIDDLE_AGE) }
                    .take(perRegion)
            }
            .map(mapper::toDto)

    private companion object {
        const val MIDDLE_AGE = 40
        const val AROUND = 12
        /** How many of the most written about each region's few are picked from. */
        const val POOL = 10
    }
}
