package art.liminis.moments

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimStatus
import art.liminis.curation.StoryCard
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.PersonSummary
import art.liminis.domain.Moment
import art.liminis.domain.WorldAround
import art.liminis.repository.MomentRepository
import art.liminis.repository.StoryCardRepository
import art.liminis.support.NotFoundException

/** A moment of the release, with how many people lived there, the claims about them and its story that wait. */
data class MomentOverview(
    val moment: Moment,
    val people: Int,
    val waiting: Int,
    /** The cards of the story the release has. */
    val cards: Int,
    /** Where the moment's story claim stands, if one was drafted. */
    val story: ClaimStatus?,
)

/** A moment with the story the release has: its hook, the world around it and its cards. */
data class MomentStory(val overview: MomentOverview, val hook: String, val world: WorldAround?, val cards: List<StoryCard>)

/** A person alive in a moment, with where their facts claim stands, if they have one. */
data class MomentPerson(val person: PersonSummary, val claim: ClaimStatus?)

data class MomentPeople(val total: Int, val people: List<MomentPerson>)

/**
 * The curator's workspace for one moment: everyone alive there, the claims about them, and its story. The people are
 * the fetched dataset's, tied to the moment's region by any place of theirs, as the site ties them.
 */
@Service
class Moments(
    private val moments: MomentRepository,
    private val cards: StoryCardRepository,
    private val dataset: DatasetStore,
    private val claims: ClaimRepository,
) {

    @Transactional(readOnly = true)
    fun all(): List<MomentOverview> {
        val cardCounts = cards.countByMoment().associate { it.momentId to it.count.toInt() }
        return moments.findAll().sortedBy { it.id }.map { overview(it, cardCounts[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    fun story(id: String): MomentStory {
        val moment = find(id)
        val storyCards = cards.findByMomentIdOrderByPosition(id).map { card ->
            StoryCard(
                type = card.type, kicker = card.kicker, title = card.title, text = card.text, year = card.year,
                person = card.person?.id, life = card.life?.id, event = card.event?.id,
                label = card.person?.name ?: card.life?.label ?: card.event?.title,
            )
        }
        return MomentStory(overview(moment, storyCards.size), moment.hook, moment.world, storyCards)
    }

    /** A page of the people alive in the moment, the best known first. */
    @Transactional(readOnly = true)
    fun people(id: String, offset: Int, limit: Int): MomentPeople {
        val moment = find(id)
        val people = dataset.aliveIn(moment.region.code, moment.startYear, moment.endYear, limit, offset)
        val status = claims.findAllById(people.map { factsOf(it.person) }).associate { it.id to it.status }
        return MomentPeople(
            total = dataset.countAliveIn(moment.region.code, moment.startYear, moment.endYear),
            people = people.map { MomentPerson(it, status[factsOf(it.person)]) },
        )
    }

    /** The ids of the claims about the moment: its people's facts and its story. */
    @Transactional(readOnly = true)
    fun claimIds(id: String): List<String> = claimIds(find(id))

    private fun overview(moment: Moment, cards: Int): MomentOverview {
        val ids = claimIds(moment)
        val found: List<Claim> = claims.findAllById(ids)
        return MomentOverview(
            moment = moment,
            people = ids.size - 1,
            waiting = found.count { it.status == ClaimStatus.CANDIDATE },
            cards = cards,
            story = found.firstOrNull { it.id == storyOf(moment) }?.status,
        )
    }

    private fun claimIds(moment: Moment): List<String> =
        dataset.qidsAliveIn(moment.region.code, moment.startYear, moment.endYear).map(::factsOf) + storyOf(moment)

    /** Throws [NotFoundException] unless the release has the moment [id]. */
    fun requireExists(id: String) {
        if (!moments.existsById(id)) throw NotFoundException("No moment $id")
    }

    private fun find(id: String): Moment = moments.findById(id).orElseThrow { NotFoundException("No moment $id") }

    private fun factsOf(qid: String) = ClaimRules.factsId(qid)

    private fun storyOf(moment: Moment) = ClaimRules.storyId(moment.id)
}
