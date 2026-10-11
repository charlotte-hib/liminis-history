package dev.wholivedwhen.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.domain.PublicationStatus.PUBLISHED
import dev.wholivedwhen.repository.ConnectionRepository
import dev.wholivedwhen.repository.EraRepository
import dev.wholivedwhen.repository.EventRepository
import dev.wholivedwhen.repository.LifeRepository
import dev.wholivedwhen.repository.MomentRepository
import dev.wholivedwhen.repository.PersonRepository
import dev.wholivedwhen.support.NotFoundException
import dev.wholivedwhen.support.currentYear
import dev.wholivedwhen.support.yearsBetween
import dev.wholivedwhen.web.ApiMapper
import dev.wholivedwhen.api.model.ConnectionDto
import dev.wholivedwhen.api.model.PersonDetailDto
import dev.wholivedwhen.api.model.PersonDto
import kotlin.math.abs

/** The age a person is shown at when no year is asked for: well into their adult life. */
private const val PRIME_AGE = 35

@Service
@Transactional(readOnly = true)
class PersonService(
    private val people: PersonRepository,
    private val lives: LifeRepository,
    private val eras: EraRepository,
    private val events: EventRepository,
    private val connections: ConnectionRepository,
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
                mapper::toDto,
            ),
            connections = connectionsOf(person),
            aroundPeople = people.findAliveBetween(region, ref, ref).filter { it.slug != slug }.map(mapper::toDto),
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
            ConnectionDto(mapper.toDto(connection.other(person)), connection.kind, connection.year, connection.text, connection.sourceUrl)
        }
        val linked = curated.map { it.person.slug }.toSet()
        val shared = events.findDistinctByParticipantsPersonSlugOrderByYearAscIdAsc(person.slug).flatMap { event ->
            event.participants
                .filter { it.person.slug != person.slug && it.person.slug !in linked }
                .map { ConnectionDto(mapper.toDto(it.person), it.role, event.year, "${event.title}. ${event.description}", event.sourceUrl) }
        }
        return (curated + shared).sortedBy { it.year }
    }

    /** A few people alive in [year] in each other region, preferring those in the middle of their lives. */
    fun aliveElsewhere(region: String, year: Int, perRegion: Int = 2): List<PersonDto> =
        people.findAliveElsewhere(region, year)
            .groupBy { it.region.code }
            .toSortedMap()
            .flatMap { (_, group) -> group.sortedBy { abs(yearsBetween(it.birthYear, year) - MIDDLE_AGE) }.take(perRegion) }
            .map(mapper::toDto)

    private companion object {
        const val MIDDLE_AGE = 40
    }
}
