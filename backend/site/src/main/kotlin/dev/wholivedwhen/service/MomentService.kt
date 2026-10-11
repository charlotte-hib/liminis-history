package dev.wholivedwhen.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import dev.wholivedwhen.domain.CardType
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.domain.PublicationStatus.PUBLISHED
import dev.wholivedwhen.repository.DoorRepository
import dev.wholivedwhen.repository.EraRepository
import dev.wholivedwhen.repository.EventRepository
import dev.wholivedwhen.repository.LifeRepository
import dev.wholivedwhen.repository.MomentRepository
import dev.wholivedwhen.repository.PersonRepository
import dev.wholivedwhen.repository.StoryCardRepository
import dev.wholivedwhen.support.NotFoundException
import dev.wholivedwhen.web.ApiMapper
import dev.wholivedwhen.api.model.DoorDto
import dev.wholivedwhen.api.model.MomentDetailDto
import dev.wholivedwhen.api.model.MomentSummaryDto
import dev.wholivedwhen.api.model.StoryDto
import kotlin.math.abs

@Service
@Transactional(readOnly = true)
class MomentService(
    private val moments: MomentRepository,
    private val cards: StoryCardRepository,
    private val doors: DoorRepository,
    private val eras: EraRepository,
    private val people: PersonRepository,
    private val lives: LifeRepository,
    private val events: EventRepository,
    private val mapper: ApiMapper,
) {
    fun list(): List<MomentSummaryDto> = summarize(published())

    fun detail(id: String): MomentDetailDto {
        val moment = find(id)
        val region = moment.region.code
        return MomentDetailDto(
            moment = summarize(listOf(moment)).single(),
            world = moment.world?.let(mapper::toDto),
            eras = eras.findByRegionCodeOrderByStartYearAscIdAsc(region)
                .filter { it.startYear <= moment.endYear && it.endYear >= moment.startYear }
                .map(mapper::toDto),
            people = people.findAliveBetween(region, moment.startYear, moment.endYear).map(mapper::toDto),
            lives = lives.findLivedBetween(region, moment.startYear, moment.endYear).map(mapper::toDto),
            events = events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, moment.startYear, moment.endYear).map(mapper::toDto),
            doors = doorsFrom(moment),
        )
    }

    fun story(id: String): StoryDto {
        val moment = find(id)
        val storyCards = cards.findByMomentIdOrderByPosition(id)
        if (storyCards.isEmpty()) throw NotFoundException("No story yet for: $id")
        return StoryDto(summarize(listOf(moment)).single(), storyCards.map(mapper::toDto), doorsFrom(moment))
    }

    /** Published moments of a region that overlap a span of years, as summaries. */
    fun overlapping(region: String, start: Int, end: Int): List<MomentSummaryDto> =
        summarize(published().filter { it.region.code == region && it.startYear <= end && it.endYear >= start })

    fun summarize(list: List<Moment>): List<MomentSummaryDto> {
        if (list.isEmpty()) return emptyList()
        val cardCounts = cards.countByMoment().associate { it.momentId to it.count.toInt() }
        val casts = cards.findByTypeOrderByPosition(CardType.PERSON)
            .groupBy({ it.moment.id }, { mapper.toDto(it.person!!) })
        val erasByRegion = eras.findAllByOrderByStartYearAscIdAsc().groupBy { it.region.code }
        return list.map { moment ->
            MomentSummaryDto(
                id = moment.id,
                regionCode = moment.region.code,
                region = moment.region.name,
                place = moment.place,
                period = moment.period,
                startYear = moment.startYear,
                endYear = moment.endYear,
                focusYear = moment.focusYear,
                hook = moment.hook,
                art = moment.art?.let(mapper::toDto),
                // Consecutive eras share their boundary year; the one that starts later wins it.
                governedBy = erasByRegion[moment.region.code].orEmpty()
                    .lastOrNull { moment.focusYear in it.startYear..it.endYear }?.label,
                storyCards = cardCounts[moment.id] ?: 0,
                cast = casts[moment.id].orEmpty().distinctBy { it.slug },
                featured = moment.featured,
            )
        }
    }

    /** The curated doors of a story, or for a moment without one, the nearest moments here and elsewhere. */
    private fun doorsFrom(moment: Moment): List<DoorDto> {
        val curated = doors.findByOriginIdOrderByPosition(moment.id)
        if (curated.isNotEmpty()) {
            val targets = summarize(curated.map { it.target }).associateBy { it.id }
            return curated.map { door -> DoorDto(door.kind, door.text, targets.getValue(door.target.id), door.faces.map(mapper::toDto)) }
        }

        val others = summarize(published().filter { it.id != moment.id })
        fun nearest(candidates: List<MomentSummaryDto>) = candidates.minByOrNull { abs(it.focusYear - moment.focusYear) }
        val here = others.filter { it.regionCode == moment.region.code }
        return listOfNotNull(
            nearest(here.filter { it.focusYear > moment.focusYear })?.let { DoorDto("Later, same place", it.hook, it, emptyList()) },
            nearest(others.filter { it.regionCode != moment.region.code })?.let { DoorDto("Around the same time, elsewhere", it.hook, it, emptyList()) },
            nearest(here.filter { it.focusYear < moment.focusYear })?.let { DoorDto("Earlier, same place", it.hook, it, emptyList()) },
        )
    }

    private fun published() = moments.findByStatusOrderByFocusYearAscIdAsc(PUBLISHED)

    private fun find(id: String) = moments.findByIdAndStatus(id, PUBLISHED)
        ?: throw NotFoundException("Unknown moment: $id")
}
