package art.liminis.drafting

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.PersonSummary
import art.liminis.domain.Moment
import art.liminis.repository.EraRepository
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.support.yearsBetween
import kotlin.math.abs

/** People from other places to offer for the "meanwhile" line, per place, chosen among its best known. */
private const val ELSEWHERE_PER_REGION = 2
private const val ELSEWHERE_POOL = 10

/**
 * Everything the drafter may draw on for a moment: the English Wikipedia intros of the best known people alive there
 * and of a few alive elsewhere, from the fetched dataset; and the release's eras, typical lives and dated events.
 */
@Component
class DraftSources(
    private val dataset: DatasetStore,
    private val lives: LifeRepository,
    private val events: EventRepository,
    private val eras: EraRepository,
    private val properties: DraftingProperties,
) {
    @Transactional(readOnly = true)
    fun forMoment(moment: Moment): List<Source> {
        val region = moment.region.code
        val (start, end) = moment.startYear to moment.endYear
        val allEras = eras.findAllByOrderByStartYearAscIdAsc()

        val eraSources = allEras.filter { it.region.code == region && it.startYear <= end && it.endYear >= start }
            .map { Source("era:${it.id}", it.label, "${it.label}: who governed ${it.region.name} from ${it.startYear} to ${it.endYear}.", null) }

        val alive = dataset.aliveIn(region, start, end, properties.people)

        val lifeSources = lives.findLivedBetween(region, start, end).map {
            Source("life:${it.id}", it.label, "${it.label}, ${it.startYear}–${it.endYear}. ${it.description}", null)
        }

        val eventSources = events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, start, end).map { event ->
            val who = event.participants.joinToString { "${it.person.name} (${it.role})" }
            Source("event:${event.id}", event.title, "In ${event.year}: ${event.description}${if (who.isNotEmpty()) " Participants: $who." else ""}", event.sourceUrl)
        }

        // A few people of each other place with a history in the release, alive in the focus year, nearest 40 years old.
        val elsewhere = allEras.map { it.region }.distinctBy { it.code }.filter { it.code != region }.flatMap { other ->
            dataset.aliveIn(other.code, moment.focusYear, moment.focusYear, ELSEWHERE_POOL)
                .sortedBy { abs(yearsBetween(it.born, moment.focusYear) - 40) }
                .take(ELSEWHERE_PER_REGION)
                .map { it to other.name }
        }

        val intros = dataset.intros(alive.map { it.person } + elsewhere.map { it.first.person })
        val peopleSources = alive.mapNotNull { person ->
            val intro = intros[person.person] ?: return@mapNotNull null
            Source("person:${person.person}", "${person.label} ${lifespan(person)}${person.occupation?.let { ", $it" } ?: ""}", intro.extract, intro.url, person.label, intro.title)
        }
        val elsewhereSources = elsewhere.mapNotNull { (person, place) ->
            val intro = intros[person.person] ?: return@mapNotNull null
            Source("elsewhere:${person.person}", "${person.label}, $place ${lifespan(person)}", intro.extract, intro.url, person.label, intro.title)
        }

        return eraSources + peopleSources + lifeSources + eventSources + elsewhereSources
    }

    private fun lifespan(person: PersonSummary) = "(${person.born}–${person.died})"
}
