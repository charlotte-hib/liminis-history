package art.liminis.moments

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.dataset.DatasetStore
import art.liminis.domain.Moment
import art.liminis.domain.Person
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.repository.MomentRepository
import art.liminis.repository.PersonRepository
import art.liminis.support.NotFoundException
import art.liminis.support.foldForSearch

/**
 * Someone or something a story card can show, as a search lists them: [from] and [to] are a person's or a life's years,
 * or an event's; [fetched] when the dataset holds the person and the site doesn't yet.
 */
data class CardSubject(
    val id: String,
    val label: String,
    val from: Int?,
    val to: Int?,
    val detail: String?,
    val picture: String?,
    val fetched: Boolean,
)

/** What a card shows, by its type: a scene card shows no one. */
enum class SubjectType { PERSON, LIFE, EVENT }

/**
 * Finds what a moment's story cards can show, by name: the release's people, lives and events, and the dataset's
 * people. The moment's own come first, then those whose name starts with the search, then the best known.
 */
@Service
class CardSubjects(
    private val moments: MomentRepository,
    private val releasePeople: PersonRepository,
    private val releaseLives: LifeRepository,
    private val releaseEvents: EventRepository,
    private val dataset: DatasetStore,
) {

    @Transactional(readOnly = true)
    fun find(momentId: String, type: SubjectType, query: String, limit: Int): List<CardSubject> {
        val moment = moments.findById(momentId).orElseThrow { NotFoundException("No moment $momentId") }
        val search = Search(query.trim(), moment)
        val found = when (type) {
            SubjectType.PERSON -> people(search, limit)
            SubjectType.LIFE -> releaseLives.findAll().filter { search.names(it.id, it.label) }.map {
                search.rank(
                    CardSubject(it.id, it.label, it.startYear, it.endYear, null, it.art?.url, fetched = false),
                    here = it.era.region.code == moment.region.code && it.startYear <= moment.endYear && it.endYear >= moment.startYear,
                )
            }
            SubjectType.EVENT -> releaseEvents.findAll().filter { search.names(it.id, it.title) }.map {
                search.rank(
                    CardSubject(it.id, it.title, it.year, null, null, null, fetched = false),
                    here = it.era.region.code == moment.region.code && it.year in moment.startYear..moment.endYear,
                )
            }
        }
        return found.sortedWith(BEST_FIRST).take(limit).map { it.subject }
    }

    /** The release's people, and the dataset's but those the release holds, who would otherwise show twice. */
    private fun people(search: Search, limit: Int): List<Ranked> {
        val held = releasePeople.findAll()
        val moment = search.moment
        val fromRelease = held.filter { search.names(it.id, it.name) || it.qid.equals(search.query, ignoreCase = true) }.map {
            search.rank(
                CardSubject(it.id, it.name, it.birthYear, it.deathYear, it.occupations.firstOrNull(), it.portraitUrl, fetched = false),
                here = livedIn(it, moment),
                exact = it.qid.equals(search.query, ignoreCase = true),
                fame = it.sitelinks,
            )
        }
        val fetched = dataset.findAround(search.query, moment.region.code, moment.startYear, moment.endYear, held.mapNotNull { it.qid }, limit).map {
            Ranked(CardSubject(it.person, it.label, it.born, it.died, it.occupation, it.portrait, fetched = true), it.exact, it.here, it.starts, it.sitelinks)
        }
        return fromRelease + fetched
    }

    /** Alive during the [moment] and tied to its region by their own or by any of their places, as the dataset's people are. */
    private fun livedIn(person: Person, moment: Moment): Boolean =
        person.birthYear <= moment.endYear && (person.deathYear ?: moment.startYear) >= moment.startYear &&
            (person.region.code == moment.region.code || person.places.any { it.regionCode == moment.region.code })

    private class Search(val query: String, val moment: Moment) {
        private val folded = foldForSearch(query)

        fun names(id: String, name: String) = id.equals(query, ignoreCase = true) || foldForSearch(name).contains(folded)

        fun rank(subject: CardSubject, here: Boolean, exact: Boolean = subject.id.equals(query, ignoreCase = true), fame: Int = 0) =
            Ranked(subject, exact, here, foldForSearch(subject.label).startsWith(folded), fame)
    }

    /** A subject with what orders it: [fame] is how many Wikimedia sites write about a person. */
    private class Ranked(val subject: CardSubject, val exact: Boolean, val here: Boolean, val starts: Boolean, val fame: Int)

    private companion object {
        // The same order as DatasetStore.findAround's, so the release's and the dataset's people merge into one list.
        val BEST_FIRST = compareByDescending<Ranked> { it.exact }
            .thenByDescending { it.here }
            .thenByDescending { it.starts }
            .thenByDescending { it.fame }
            .thenBy { it.subject.id }
    }
}
