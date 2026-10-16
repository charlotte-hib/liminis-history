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
import art.liminis.domain.Artwork
import art.liminis.domain.CardType
import art.liminis.domain.Life
import art.liminis.domain.Moment
import art.liminis.domain.WorldAround
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import art.liminis.repository.StoryCardRepository
import art.liminis.support.NotFoundException
import art.liminis.support.dropHatnote
import art.liminis.support.leadSentences

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

/** What a moment's story cards show: its painting, the paintings its cards carry, and the people, lives and events named. */
data class StoryPreview(
    val art: Artwork?,
    val cardArt: List<CardArtwork>,
    val people: List<PreviewPerson>,
    val lives: List<Life>,
    val events: List<PreviewEvent>,
)

/** A painting one of the release's cards carries, with what that card shows. */
data class CardArtwork(val type: CardType, val person: String?, val life: String?, val event: String?, val art: Artwork)

/** A person as a card shows them, by the id the card names them by. */
data class PreviewPerson(
    val id: String,
    val name: String,
    val born: Int,
    val died: Int?,
    val occupation: String?,
    val portrait: String?,
    val bio: String?,
)

data class PreviewEvent(val id: String, val title: String, val year: Int, val description: String, val sourceUrl: String, val participants: List<EventRole>)

data class EventRole(val name: String, val role: String)

/**
 * The curator's workspace for one moment: everyone alive there, the claims about them, and its story. The people are
 * the fetched dataset's, tied to the moment's region by any place of theirs, as the site ties them.
 */
@Service
class Moments(
    private val moments: MomentRepository,
    private val cards: StoryCardRepository,
    private val releasePeople: PersonRepository,
    private val releaseLives: LifeRepository,
    private val releaseEvents: EventRepository,
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

    /**
     * What [id]'s story cards show, for the [people], [lives] and [events] they name: a person written by hand from the
     * release, a fetched one from the dataset. Ids found nowhere are left out.
     */
    @Transactional(readOnly = true)
    fun preview(id: String, people: Collection<String>, lives: Collection<String>, events: Collection<String>): StoryPreview {
        val moment = find(id)
        val byHand = releasePeople.findAllById(people).map {
            PreviewPerson(it.id, it.name, it.birthYear, it.deathYear, it.occupations.firstOrNull(), it.portraitUrl, it.bioShort)
        }
        val fetched = dataset.portraits(people - byHand.map { it.id }.toSet()).map {
            PreviewPerson(it.person, it.label, it.born, it.died, it.occupation, it.portrait, it.intro?.let(::bioOf))
        }
        return StoryPreview(
            art = moment.art,
            cardArt = moment.cards.mapNotNull { card ->
                card.art?.let { CardArtwork(card.type, card.person?.id, card.life?.id, card.event?.id, it) }
            },
            people = (byHand + fetched).sortedBy { it.id },
            lives = releaseLives.findAllById(lives).sortedBy { it.id },
            events = releaseEvents.findAllById(events).sortedBy { it.id }.map { event ->
                PreviewEvent(
                    event.id, event.title, event.year, event.description, event.sourceUrl,
                    event.participants.map { EventRole(it.person.name, it.role) },
                )
            },
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

    /** A fetched person's bio, as the release makes it from their English intro. */
    private fun bioOf(intro: String) = dropHatnote(intro).takeIf { it.isNotBlank() }?.let(::leadSentences)

    private fun storyOf(moment: Moment) = ClaimRules.storyId(moment.id)
}
