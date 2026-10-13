package dev.wholivedwhen.occupations

import org.springframework.stereotype.Component
import dev.wholivedwhen.dataset.DatasetStore
import dev.wholivedwhen.dataset.OccupationRow
import dev.wholivedwhen.dataset.PersonSummary
import dev.wholivedwhen.support.NotFoundException

/** An occupation, or a class of them, how many of the dataset's people hold it, and how many as their first one. */
data class HeldOccupation(val occupation: OccupationRow, val people: Int, val first: Int)

data class OccupationEvidence(
    val occupation: String,
    val label: String,
    val labelFr: String?,
    val people: Int,
    val first: Int,
    val examples: List<PersonSummary>,
    val parents: List<OccupationLabel>,
)

data class OccupationLabel(val occupation: String, val label: String)

/**
 * Who holds the dataset's occupations. A person holds their first three occupations (the ones the site shows and
 * takes their domain from) and every class above them, up to three levels: as the release's compiler looks a domain up.
 */
@Component
class Occupations(private val dataset: DatasetStore) {

    /**
     * Every occupation and class held by someone: those most held as a first occupation first, then the classes no one
     * holds directly ("artist", "person"), the widest first.
     */
    fun held(): List<HeldOccupation> {
        val occupations = dataset.occupations().associateBy { it.qid }
        val held = dataset.firstOccupations()
        val first = held.values.mapNotNull { it.firstOrNull() }.groupingBy { it }.eachCount()
        return holders(occupations, held).map { (qid, people) -> HeldOccupation(occupations.getValue(qid), people.size, first[qid] ?: 0) }
            .sortedWith(compareByDescending<HeldOccupation> { it.first }.thenByDescending { it.people }.thenBy { it.occupation.qid })
    }

    fun evidence(qid: String): OccupationEvidence {
        val occupations = dataset.occupations().associateBy { it.qid }
        val occupation = occupations[qid] ?: throw NotFoundException("No occupation $qid in the dataset")
        val held = dataset.firstOccupations()
        val people = holders(occupations, held)[qid].orEmpty()
        return OccupationEvidence(
            occupation = qid,
            label = occupation.label,
            labelFr = occupation.labelFr,
            people = people.size,
            first = held.values.count { it.firstOrNull() == qid },
            examples = dataset.summaries(people, EXAMPLES),
            parents = occupation.parents.mapNotNull { parent -> occupations[parent]?.let { OccupationLabel(parent, it.label) } },
        )
    }

    /** By occupation or class, the people who hold it. Classes deleted from Wikidata are in no table: left out. */
    private fun holders(occupations: Map<String, OccupationRow>, firstOccupations: Map<String, List<String>>): Map<String, Set<String>> {
        val holders = mutableMapOf<String, MutableSet<String>>()
        firstOccupations.forEach { (person, held) ->
            var level = held.filter { it in occupations }
            val all = level.toMutableSet()
            repeat(CLASS_LEVELS) {
                level = level.flatMap { occupations[it]?.parents.orEmpty() }.filter { it in occupations && it !in all }.distinct()
                all += level
            }
            all.forEach { holders.getOrPut(it) { mutableSetOf() } += person }
        }
        return holders
    }

    private companion object {
        const val CLASS_LEVELS = 3
        const val EXAMPLES = 8
    }
}
