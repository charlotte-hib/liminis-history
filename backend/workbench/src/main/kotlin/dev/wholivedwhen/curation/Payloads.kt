package dev.wholivedwhen.curation

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// The records claims are about, stored in the JSON columns payload and proposal, and in each decision: the same rules
// as ClaimModel.kt apply. Renaming a field here is a migration of the stored rows.

/** The record as it would ship, by claim type. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(PersonFacts::class, name = "PERSON_FACTS"),
    JsonSubTypes.Type(Connection::class, name = "CONNECTION"),
    JsonSubTypes.Type(Event::class, name = "EVENT"),
    JsonSubTypes.Type(Anecdote::class, name = "ANECDOTE"),
    JsonSubTypes.Type(OccupationDomain::class, name = "OCCUPATION_DOMAIN"),
)
sealed interface Payload

/** A person's facts as Wikidata states them. Years are negative for BCE, with no year 0. */
data class PersonFacts(
    val person: String,
    val label: String,
    val labelFr: String? = null,
    val born: Int,
    /** Wikidata's precision: 9 a year, 8 a decade, 7 a century, 10 and 11 a month and a day. */
    val bornPrecision: Int,
    val died: Int,
    /** None when the year of death is estimated. */
    val diedPrecision: Int? = null,
    val diedEstimated: Boolean,
    val datesApproximate: Boolean,
    val enwiki: String? = null,
    val frwiki: String? = null,
    /** In Wikidata's order, best rank first. The site shows the first. */
    val occupations: List<Occupation>,
    val places: List<Place>,
) : Payload

data class Occupation(val occupation: String, val label: String, val labelFr: String? = null)

data class Place(
    val role: PlaceRole,
    val place: String,
    val label: String,
    /** The ISO code of the country it lies in today. */
    val country: String? = null,
    val startYear: Int? = null,
    val endYear: Int? = null,
)

enum class PlaceRole { BIRTH, DEATH, WORK, RESIDENCE }

/** Two people linked at a date, in one plain sentence. */
data class Connection(val people: List<String>, val kind: String, val year: Int, val text: String) : Payload

/** Something that happened in a region in a year. */
data class Event(
    val region: String,
    val year: Int,
    val title: String,
    val description: String,
    val participants: List<Participant>,
) : Payload

data class Participant(val person: String, val role: String)

/** A short story about one person, at a date. */
data class Anecdote(val person: String, val year: Int, val text: String) : Payload

/**
 * The domain of the people who hold an occupation, or a class of occupations (it then covers the narrower ones not
 * mapped themselves). Proposed without one: the curator chooses it.
 */
data class OccupationDomain(val occupation: String, val label: String, val labelFr: String? = null, val domain: OccupationChoice? = null) : Payload

/** A domain of the site's, or none: the occupation says nothing of a life's work, so the person's next one decides. */
enum class OccupationChoice { POWER, ARTS, EVERYDAY, NONE }
