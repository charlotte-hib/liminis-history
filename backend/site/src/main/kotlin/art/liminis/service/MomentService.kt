package art.liminis.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.domain.CardType
import art.liminis.domain.Moment
import art.liminis.domain.PublicationStatus.PUBLISHED
import art.liminis.repository.ConnectionRepository
import art.liminis.repository.DoorRepository
import art.liminis.repository.EraRepository
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import art.liminis.repository.StoryCardRepository
import art.liminis.support.NotFoundException
import art.liminis.web.ApiMapper
import art.liminis.api.model.DoorDto
import art.liminis.api.model.MomentDetailDto
import art.liminis.api.model.MomentPeopleDto
import art.liminis.api.model.MomentSummaryDto
import art.liminis.api.model.StoryDto
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
    private val connections: ConnectionRepository,
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
            people = people(moment, moment.focusYear, 0),
            lives = lives.findLivedBetween(region, moment.startYear, moment.endYear).map(mapper::toDto),
            events = events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, moment.startYear, moment.endYear).map(mapper::toDto),
            doors = doorsFrom(moment),
        )
    }

    /** A draw of the people alive in [year] of the moment [id] (see [PeopleDraws]). */
    fun people(id: String, year: Int, draw: Int): MomentPeopleDto = people(find(id), year, draw)

    private fun people(moment: Moment, year: Int, draw: Int): MomentPeopleDto {
        val related = connections.findConnectedIds() + events.findParticipantIds() +
            cards.findByMomentIdOrderByPosition(moment.id).mapNotNull { it.person?.id }
        val draws = PeopleDraws(people.findMomentCandidates(moment.region.code, year, moment.placeQid), related, seed = "${moment.id} $year")
        val ids = draws.ids(draw)
        val byId = people.findByIdIn(ids).associateBy { it.id }
        return MomentPeopleDto(year, draw % draws.count, draws.count, ids.map { mapper.toDto(byId.getValue(it)) })
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
            mapper.toSummary(
                moment,
                // Consecutive eras share their boundary year; the one that starts later wins it.
                governedBy = erasByRegion[moment.region.code].orEmpty()
                    .lastOrNull { moment.focusYear in it.startYear..it.endYear }?.label,
                storyCards = cardCounts[moment.id] ?: 0,
                cast = casts[moment.id].orEmpty().distinctBy { it.slug },
            )
        }
    }

    /** The curated doors of a story, or for a moment without one, the nearest moments here and elsewhere. */
    private fun doorsFrom(moment: Moment): List<DoorDto> {
        val curated = doors.findByOriginIdOrderByPosition(moment.id)
        if (curated.isNotEmpty()) {
            val targets = summarize(curated.map { it.target }).associateBy { it.id }
            return curated.map { door -> mapper.toDto(door, targets.getValue(door.target.id)) }
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
