package art.liminis.curation

import org.springframework.stereotype.Component
import art.liminis.dataset.DatasetStore
import art.liminis.support.InvalidRequestException

/**
 * A person's facts as a curator edited them, held to the dataset: each place and occupation one it holds, labelled and
 * placed in its country as it says, whatever the edit said; a French name or an article left blank is none.
 */
@Component
class FactEdits(private val dataset: DatasetStore) {

    fun checked(facts: PersonFacts): PersonFacts {
        val places = dataset.foundPlaces(facts.places.map { it.place }.distinct()).associateBy { it.place }
        val occupations = dataset.foundOccupations(facts.occupations.map { it.occupation }.distinct()).associateBy { it.occupation }
        return facts.copy(
            labelFr = facts.labelFr?.trim()?.ifEmpty { null },
            enwiki = facts.enwiki?.trim()?.ifEmpty { null },
            frwiki = facts.frwiki?.trim()?.ifEmpty { null },
            occupations = facts.occupations.map { edited ->
                val found = occupations[edited.occupation] ?: invalid("No occupation ${edited.occupation} in the dataset")
                Occupation(found.occupation, found.label, found.labelFr)
            },
            places = facts.places.map { edited ->
                val found = places[edited.place] ?: invalid("No place ${edited.place} in the dataset")
                if (edited.startYear != null && edited.endYear != null && edited.startYear > edited.endYear) {
                    invalid("${found.label}: from ${edited.startYear}, after ${edited.endYear}")
                }
                edited.copy(label = found.label, country = found.country)
            },
        )
    }

    private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)
}
