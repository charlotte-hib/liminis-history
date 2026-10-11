package dev.wholivedwhen.curation

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// Claims as the workbench keeps them in the schema curation, apart from the API's models (api/workbench.yaml): a change
// to the contract cannot make the claims already stored unreadable. The JSON columns hold these types as Spring's
// JsonMapper writes them (CurationJson), nulls left out, and the text columns hold the enums' names. Renaming a field
// or a constant here is a migration of the stored rows (db/curation).

enum class ClaimType { PERSON_FACTS, CONNECTION, EVENT, ANECDOTE }

/** `CANDIDATE` waits for review; `NEEDS_WORK` was kept for later, with a note. */
enum class ClaimStatus { CANDIDATE, APPROVED, REJECTED, NEEDS_WORK }

enum class Origin { WIKIDATA, BOOK, CLAUDE, MANUAL }

/** Why a claim needs a person's eye, whatever the error rate of its kind. */
enum class Flag { POORLY_DOCUMENTED, CONFLICT, ODD, SENSITIVE, SINGLE_SOURCE }

data class FlagReason(val flag: Flag, val reason: String)

/** The record as it would ship, by claim type. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(PersonFacts::class, name = "PERSON_FACTS"),
    JsonSubTypes.Type(Connection::class, name = "CONNECTION"),
    JsonSubTypes.Type(Event::class, name = "EVENT"),
    JsonSubTypes.Type(Anecdote::class, name = "ANECDOTE"),
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
 * What a claim rests on: a Wikidata entity (`entity`, `revision`), a Wikipedia article (`language`, `title`,
 * `revision`), a book (`title`, `page`), a web page (`url`), or a podcast, a video or a documentary (`title`,
 * `episode`, `minute`, `url`), with the passage the claim rests on.
 */
data class SourceRef(
    val kind: SourceKind,
    val entity: String? = null,
    val revision: Long? = null,
    val language: String? = null,
    val title: String? = null,
    val page: String? = null,
    val episode: String? = null,
    val minute: String? = null,
    val url: String? = null,
    val quote: String? = null,
)

enum class SourceKind { WIKIDATA, WIKIPEDIA, BOOK, WEB, MEDIA }

/** A claim the curator found, with at least one source, and why they add it. */
data class NewClaim(val payload: Payload, val sources: List<SourceRef>, val note: String? = null)

enum class Verdict { APPROVE, REJECT, NEEDS_WORK }

/** Approve (with an edited [payload], if given), reject or keep for later, with a note saying why. */
data class NewDecision(val verdict: Verdict, val payload: Payload? = null, val note: String? = null)

/** How many claims of a [type] wait, how many were kept for later, and how many waiting ones carry each flag. */
data class Queue(val type: ClaimType, val candidates: Int, val needsWork: Int, val flags: Map<String, Int>)

/** A claim with what it rests on and every decision taken on it, oldest first. */
data class ClaimDetail(val claim: Claim, val sources: List<SourceRef>, val decisions: List<Decision>)
