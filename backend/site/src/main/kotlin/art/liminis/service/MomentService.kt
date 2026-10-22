package art.liminis.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.domain.CardType
import art.liminis.domain.Language
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
    fun list(language: Language): List<MomentSummaryDto> = summarize(published(), language)

    fun detail(id: String, language: Language): MomentDetailDto {
        val moment = find(id)
        val region = moment.region.code
        return MomentDetailDto(
            moment = summarize(listOf(moment), language).single(),
            world = mapper.world(moment, language),
            eras = eras.findByRegionCodeOrderByStartYearAscIdAsc(region)
                .filter { it.startYear <= moment.endYear && it.endYear >= moment.startYear }
                .map { mapper.toDto(it, language) },
            people = people(moment, moment.focusYear, 0, language),
            lives = lives.findLivedBetween(region, moment.startYear, moment.endYear).map { mapper.toDto(it, language) },
            events = events.findByEraRegionCodeAndYearBetweenOrderByYearAscIdAsc(region, moment.startYear, moment.endYear).map { mapper.toDto(it, language) },
            doors = doorsFrom(moment, language),
        )
    }

    /** A draw of the people alive in [year] of the moment [id] (see [PeopleDraws]). */
    fun people(id: String, year: Int, draw: Int, language: Language): MomentPeopleDto = people(find(id), year, draw, language)

    private fun people(moment: Moment, year: Int, draw: Int, language: Language): MomentPeopleDto {
        val related = connections.findConnectedIds() + events.findParticipantIds() +
            cards.findByMomentIdOrderByPosition(moment.id).mapNotNull { it.person?.id }
        val draws = PeopleDraws(people.findMomentCandidates(moment.region.code, year, moment.placeQid), related, seed = "${moment.id} $year")
        val ids = draws.ids(draw)
        val byId = people.findByIdIn(ids).associateBy { it.id }
        return MomentPeopleDto(year, draw % draws.count, draws.count, ids.map { mapper.toDto(byId.getValue(it), language) })
    }

    fun story(id: String, language: Language): StoryDto {
        val moment = find(id)
        val storyCards = cards.findByMomentIdOrderByPosition(id)
        if (storyCards.isEmpty()) throw NotFoundException("No story yet for: $id")
        return StoryDto(summarize(listOf(moment), language).single(), storyCards.map { mapper.toDto(it, language) }, doorsFrom(moment, language))
    }

    /** Published moments of a region that overlap a span of years, as summaries. */
    fun overlapping(region: String, start: Int, end: Int, language: Language): List<MomentSummaryDto> =
        summarize(published().filter { it.region.code == region && it.startYear <= end && it.endYear >= start }, language)

    fun summarize(list: List<Moment>, language: Language): List<MomentSummaryDto> {
        if (list.isEmpty()) return emptyList()
        val cardCounts = cards.countByMoment().associate { it.momentId to it.count.toInt() }
        val casts = cards.findByTypeOrderByPosition(CardType.PERSON)
            .groupBy({ it.moment.id }, { mapper.toDto(it.person!!, language) })
        val erasByRegion = eras.findAllByOrderByStartYearAscIdAsc().groupBy { it.region.code }
        return list.map { moment ->
            mapper.toSummary(
                moment,
                // Consecutive eras share their boundary year; the one that starts later wins it.
                governedBy = erasByRegion[moment.region.code].orEmpty()
                    .lastOrNull { moment.focusYear in it.startYear..it.endYear }?.let { mapper.toDto(it, language).label },
                storyCards = cardCounts[moment.id] ?: 0,
                cast = casts[moment.id].orEmpty().distinctBy { it.slug },
                language = language,
            )
        }
    }

    /** The curated doors of a story, or for a moment without one, the nearest moments here and elsewhere. */
    private fun doorsFrom(moment: Moment, language: Language): List<DoorDto> {
        val curated = doors.findByOriginIdOrderByPosition(moment.id)
        if (curated.isNotEmpty()) {
            val targets = summarize(curated.map { it.target }, language).associateBy { it.id }
            return curated.map { door -> mapper.toDto(door, targets.getValue(door.target.id), language) }
        }

        val others = summarize(published().filter { it.id != moment.id }, language)
        fun nearest(candidates: List<MomentSummaryDto>) = candidates.minByOrNull { abs(it.focusYear - moment.focusYear) }
        val here = others.filter { it.regionCode == moment.region.code }
        val kinds = NEAREST.getValue(language)
        // Worded here, in the language asked for; the hook of the moment it leads to is in that moment's.
        fun door(kind: String, target: MomentSummaryDto) = DoorDto(kind, target.hook, target, emptyList(), target.lang)
        return listOfNotNull(
            nearest(here.filter { it.focusYear > moment.focusYear })?.let { door(kinds.later, it) },
            nearest(others.filter { it.regionCode != moment.region.code })?.let { door(kinds.elsewhere, it) },
            nearest(here.filter { it.focusYear < moment.focusYear })?.let { door(kinds.earlier, it) },
        )
    }

    private fun published() = moments.findByStatusOrderByFocusYearAscIdAsc(PUBLISHED)

    private fun find(id: String) = moments.findByIdAndStatus(id, PUBLISHED)
        ?: throw NotFoundException("Unknown moment: $id")

    /** The kinds of the doors to the nearest moments, for a moment without curated ones. */
    private data class Nearest(val later: String, val elsewhere: String, val earlier: String)

    private companion object {
        val NEAREST = mapOf(
            Language.EN to Nearest("Later, same place", "Around the same time, elsewhere", "Earlier, same place"),
            Language.FR to Nearest("Plus tard, au même endroit", "À la même époque, ailleurs", "Plus tôt, au même endroit"),
        )
    }
}
