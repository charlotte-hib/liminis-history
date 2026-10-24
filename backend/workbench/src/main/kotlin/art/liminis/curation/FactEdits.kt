package art.liminis.curation

import org.springframework.stereotype.Component
import art.liminis.dataset.DatasetStore
import art.liminis.support.InvalidRequestException

/**
 * A person's facts as a curator edited them, held to the dataset: each place and occupation they add one it holds,
 * labelled and placed in its country as it says, whatever the edit said; those the claim already [held] stay as they
 * are, even one Wikidata has since deleted. A French name or an article left blank is none.
 */
@Component
class FactEdits(private val dataset: DatasetStore) {

    fun checked(facts: PersonFacts, held: List<PersonFacts>): PersonFacts {
        val heldPlaces = held.flatMap { it.places }.associateBy { it.place }
        val heldOccupations = held.flatMap { it.occupations }.associateBy { it.occupation }
        val newPlaces = facts.places.map { it.place }.filter { it !in heldPlaces }.distinct()
        val newOccupations = facts.occupations.map { it.occupation }.filter { it !in heldOccupations }.distinct()
        val places = dataset.foundPlaces(newPlaces).associateBy { it.place }
        val occupations = dataset.foundOccupations(newOccupations).associateBy { it.occupation }
        return facts.copy(
            labelFr = facts.labelFr?.trim()?.ifEmpty { null },
            enwiki = facts.enwiki?.trim()?.ifEmpty { null },
            frwiki = facts.frwiki?.trim()?.ifEmpty { null },
            occupations = facts.occupations.map { edited ->
                heldOccupations[edited.occupation]
                    ?: occupations[edited.occupation]?.let { Occupation(it.occupation, it.label, it.labelFr) }
                    ?: invalid("No occupation ${edited.occupation} in the dataset")
            },
            places = facts.places.map { edited ->
                val (label, country) = heldPlaces[edited.place]?.let { it.label to it.country }
                    ?: places[edited.place]?.let { it.label to it.country }
                    ?: invalid("No place ${edited.place} in the dataset")
                if (edited.startYear != null && edited.endYear != null && edited.startYear > edited.endYear) {
                    invalid("$label: from ${edited.startYear}, after ${edited.endYear}")
                }
                edited.copy(label = label, country = country)
            },
        )
    }

    private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)
}
