package art.liminis.service

import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.domain.Language
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
import art.liminis.web.dto
import art.liminis.api.model.CitedSourceDto
import art.liminis.api.model.ConnectionDto
import art.liminis.api.model.PersonDetailDto
import art.liminis.api.model.PersonDto
import java.util.Locale
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
    fun detail(slug: String, year: Int?, language: Language): PersonDetailDto {
        val person = people.findBySlug(slug)
            ?: throw NotFoundException("Unknown person: $slug")
        val region = person.region.code
        val end = person.deathYear ?: currentYear()
        val ref = year?.takeIf { it in person.birthYear..end } ?: minOf(end, person.birthYear + PRIME_AGE)

        // The written moment of their place whose focus is closest to the year we look at.
        val worldMoment = moments.findByStatusOrderByFocusYearAscIdAsc(PUBLISHED)
            .filter { it.region.code == region && it.world != null && it.startYear <= end && it.endYear >= person.birthYear }
            .minByOrNull { abs(it.focusYear - ref) }

        // Their French article's intro on the French site, and its link, when it was fetched.
        val aboutLanguage = if (language == Language.FR && person.aboutFr != null) Language.FR else Language.EN
        return PersonDetailDto(
            person = mapper.toDto(person, language),
            about = if (aboutLanguage == Language.FR) person.aboutFr else person.about,
            wikipediaUrl = if (aboutLanguage == Language.FR) person.wikipediaUrlFr ?: person.wikipediaUrl else person.wikipediaUrl,
            year = ref,
            age = yearsBetween(person.birthYear, ref),
            world = worldMoment?.let { mapper.world(it, language) },
            worldMoment = worldMoment?.let { momentService.summarize(listOf(it), language).single() },
            lifeline = lifeInTime(
                person,
                eras.findByRegionCodeOrderByStartYearAscIdAsc(region),
                events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, person.birthYear, end),
                anecdotes.findByPersonIdOrderByYearAscIdAsc(person.id),
                mapper,
                language,
            ),
            places = placesOf(person, language, countries(person, language)),
            connections = connectionsOf(person, language),
            aroundPeople = people.findTiedAliveBetween(region, ref, ref, Limit.of(AROUND + 1)).filter { it.slug != slug }.take(AROUND)
                .map { mapper.toDto(it, language) },
            aroundLives = lives.findLivedBetween(region, ref, ref).map { mapper.toDto(it, language) },
            elsewhere = aliveElsewhere(region, ref, language = language),
            moments = momentService.overlapping(region, person.birthYear, end, language),
            aboutLang = aboutLanguage.dto(),
        )
    }

    /**
     * The names of the countries [person]'s places lie in: the release's regions where it holds them in [language],
     * else the country's name in [language].
     */
    private fun countries(person: Person, language: Language): (String) -> String? {
        val held = regions.findAllById(person.places.mapNotNull { it.regionCode }.toSet()).associateBy { it.code }
        return { code ->
            val named = Locale.of("", code).getDisplayCountry(if (language == Language.FR) Locale.FRENCH else Locale.ENGLISH).takeIf { it != code }
            when (val region = held[code]) {
                null -> named
                // A region the release names in English only is named in French after its code.
                else -> if (language == Language.FR && region.nameFr == null) named ?: region.name else mapper.regionName(region, language)
            }
        }
    }

    /**
     * Everyone [person] is documented to have known: curated connections, then the other people of each event
     * they took part in (with their role in it), unless a curated connection already links the pair. In order of year.
     */
    fun connectionsOf(person: Person, language: Language): List<ConnectionDto> {
        val curated = connections.findInvolving(person.slug).map { connection ->
            mapper.toDto(connection, connection.other(person), language)
        }
        val linked = curated.map { it.person.slug }.toSet()
        val shared = events.findDistinctByParticipantsPersonSlugOrderByYearAscIdAsc(person.slug).flatMap { event ->
            val told = mapper.toDto(event, language)
            told.participants
                .filter { it.person.slug != person.slug && it.person.slug !in linked }
                .map { ConnectionDto(it.person, it.role, event.year, "${told.title}. ${told.description}", CitedSourceDto(null, null, null, null, event.sourceUrl), told.lang) }
        }
        return (curated + shared).sortedBy { it.year }
    }

    /**
     * A few people alive in [year] in each other region with eras: among the most written about there, those in the
     * middle of their lives.
     */
    fun aliveElsewhere(region: String, year: Int, perRegion: Int = 2, language: Language): List<PersonDto> =
        regions.findWithEras().map { it.code }.filter { it != region }.sorted()
            .flatMap { other ->
                people.findOfRegionAlive(other, year, Limit.of(POOL))
                    .sortedBy { abs(yearsBetween(it.birthYear, year) - MIDDLE_AGE) }
                    .take(perRegion)
            }
            .map { mapper.toDto(it, language) }

    private companion object {
        const val MIDDLE_AGE = 40
        const val AROUND = 12
        /** How many of the most written about each region's few are picked from. */
        const val POOL = 10
    }
}
