package dev.wholivedwhen.occupations

import org.springframework.stereotype.Component
import dev.wholivedwhen.dataset.DatasetStore
import dev.wholivedwhen.dataset.OccupationRow
import dev.wholivedwhen.dataset.PersonSummary
import dev.wholivedwhen.support.NotFoundException

/** An occupation, or a class of them, and how many of the dataset's people hold it. */
data class HeldOccupation(val occupation: OccupationRow, val people: Int)

data class OccupationEvidence(
    val occupation: String,
    val label: String,
    val labelFr: String?,
    val people: Int,
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

    /** Every occupation and class held by someone, the most held first. */
    fun held(): List<HeldOccupation> {
        val occupations = dataset.occupations().associateBy { it.qid }
        return holders(occupations).map { (qid, people) -> HeldOccupation(occupations.getValue(qid), people.size) }
            .sortedWith(compareByDescending<HeldOccupation> { it.people }.thenBy { it.occupation.qid })
    }

    fun evidence(qid: String): OccupationEvidence {
        val occupations = dataset.occupations().associateBy { it.qid }
        val occupation = occupations[qid] ?: throw NotFoundException("No occupation $qid in the dataset")
        val people = holders(occupations)[qid].orEmpty()
        return OccupationEvidence(
            occupation = qid,
            label = occupation.label,
            labelFr = occupation.labelFr,
            people = people.size,
            examples = dataset.summaries(people, EXAMPLES),
            parents = occupation.parents.mapNotNull { parent -> occupations[parent]?.let { OccupationLabel(parent, it.label) } },
        )
    }

    /** By occupation or class, the people who hold it. Classes deleted from Wikidata are in no table: left out. */
    private fun holders(occupations: Map<String, OccupationRow>): Map<String, Set<String>> {
        val holders = mutableMapOf<String, MutableSet<String>>()
        dataset.firstOccupations().forEach { (person, held) ->
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
