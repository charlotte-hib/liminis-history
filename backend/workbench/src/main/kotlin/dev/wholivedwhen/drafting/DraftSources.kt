package dev.wholivedwhen.drafting

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.repository.EraRepository
import dev.wholivedwhen.repository.EventRepository
import dev.wholivedwhen.repository.LifeRepository
import dev.wholivedwhen.repository.PersonRepository
import dev.wholivedwhen.support.yearsBetween
import kotlin.math.abs

/** People from other places to offer for the "meanwhile" line, per place. */
private const val ELSEWHERE_PER_REGION = 2

/** Everything the drafter may draw on for a moment: Wikipedia leads, dated events, eras and typical lives. */
@Component
class DraftSources(
    private val people: PersonRepository,
    private val lives: LifeRepository,
    private val events: EventRepository,
    private val eras: EraRepository,
) {
    @Transactional(readOnly = true)
    fun forMoment(moment: Moment): List<Source> {
        val region = moment.region.code
        val (start, end) = moment.startYear to moment.endYear

        val eraSources = eras.findByRegionCodeOrderByStartYearAscIdAsc(region)
            .filter { it.startYear <= end && it.endYear >= start }
            .map { Source("era:${it.id}", it.label, "${it.label}: who governed ${it.region.name} from ${it.startYear} to ${it.endYear}.", null) }

        val peopleSources = people.findAliveBetween(region, start, end).mapNotNull { person ->
            val text = person.about ?: person.bioShort ?: return@mapNotNull null
            Source("person:${person.slug}", "${person.name} (${person.birthYear}–${person.deathYear ?: ""}), ${person.occupations.joinToString()}", text, person.wikipediaUrl)
        }

        val lifeSources = lives.findLivedBetween(region, start, end).map {
            Source("life:${it.id}", it.label, "${it.label}, ${it.startYear}–${it.endYear}. ${it.description}", null)
        }

        val eventSources = events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, start, end).map { event ->
            val who = event.participants.joinToString { "${it.person.name} (${it.role})" }
            Source("event:${event.id}", event.title, "In ${event.year}: ${event.description}${if (who.isNotEmpty()) " Participants: $who." else ""}", event.sourceUrl)
        }

        val elsewhere = people.findAliveElsewhere(region, moment.focusYear)
            .groupBy { it.region.code }
            .flatMap { (_, group) -> group.sortedBy { abs(yearsBetween(it.birthYear, moment.focusYear) - 40) }.take(ELSEWHERE_PER_REGION) }
            .mapNotNull { person ->
                val text = person.about ?: person.bioShort ?: return@mapNotNull null
                Source("elsewhere:${person.slug}", "${person.name}, ${person.region.name} (${person.birthYear}–${person.deathYear ?: ""})", text, person.wikipediaUrl)
            }

        return eraSources + peopleSources + lifeSources + eventSources + elsewhere
    }
}
