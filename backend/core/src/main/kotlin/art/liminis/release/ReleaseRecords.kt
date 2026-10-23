package art.liminis.release

import art.liminis.domain.ArtFit
import art.liminis.domain.CardType
import art.liminis.domain.Domain
import art.liminis.domain.LifeIcon
import art.liminis.domain.PlaceKind
import art.liminis.domain.PublicationStatus
import art.liminis.dataset.FetchedDataset

// The records of a release directory, one class per kind of line (see sample/README.md). Records point at each
// other by id; ReleaseCompiler resolves them. Years are integers, negative for BCE. Wikidata's ids (Q-ids) tie them to
// the fetched dataset in the directory's dataset/, when there is one. A record's text may have a French version beside
// it, `fr`, with each of the record's texts in French: the French site shows it, and the English where there is none.

/** A line of `regions.jsonl`: a place seen through today's borders, e.g. "FR", whose eras are written. */
data class RegionRecord(val id: String, val name: String, val fr: RegionFrenchRecord? = null)

data class RegionFrenchRecord(val name: String)

/** A line of `eras.jsonl`. Both years are inclusive; no end means it lasts until today. */
data class EraRecord(
    val id: String,
    val region: String,
    val label: String,
    /** Who governed, when the label does not say it. */
    val governedBy: String? = null,
    val start: Int,
    val end: Int? = null,
    val fr: EraFrenchRecord? = null,
)

/** Who governed is in French too when it is written in English. */
data class EraFrenchRecord(val label: String, val governedBy: String? = null)

/**
 * A line of `people.jsonl`: a person written by hand. With a [qid] found in the fetched dataset, the person takes its
 * places, sitelinks and Wikipedia article from there, and keeps everything written here.
 */
data class PersonRecord(
    val id: String,
    val qid: String? = null,
    val name: String,
    val born: Int,
    val died: Int? = null,
    val approximate: Boolean = false,
    val region: String,
    val domain: Domain,
    val occupation: String,
    /** The English Wikipedia article's title, when it is not the name with underscores. */
    val wikipedia: String? = null,
    val bio: String? = null,
    val fr: PersonFrenchRecord? = null,
)

/**
 * A person's name as French writes it, their occupation and bio in French (a bio when the English has one), and the
 * title of their French Wikipedia article, when there is one: the backend fetches its intro.
 */
data class PersonFrenchRecord(val name: String, val occupation: String, val bio: String? = null, val wikipedia: String? = null)

/**
 * A line of `lives.jsonl`: a typical existence, set in the era of its region that covers [start]. Drawn with its [art],
 * else its [icon], else a generic one.
 */
data class LifeRecord(
    val id: String,
    val region: String,
    val label: String,
    val description: String,
    val start: Int,
    val end: Int,
    val art: LifeArtRecord? = null,
    val icon: LifeIcon? = null,
    val fr: LifeFrenchRecord? = null,
)

data class LifeFrenchRecord(val label: String, val description: String)

/** A life's image. [position] is the point kept in view when cropping, as CSS `object-position`. */
data class LifeArtRecord(
    val url: String,
    val credit: String,
    val source: String,
    val position: String = "50% 50%",
    val fit: ArtFit = ArtFit.COVER,
)

/** A line of `events.jsonl`, set in the era of its region that covers [year]. */
data class EventRecord(
    val id: String,
    val region: String,
    val year: Int,
    val title: String,
    val description: String,
    val source: String,
    val participants: List<ParticipantRecord> = emptyList(),
    val fr: EventFrenchRecord? = null,
)

/** An event in French: its participants' roles are in French beside them too. */
data class EventFrenchRecord(val title: String, val description: String)

data class ParticipantRecord(val person: String, val role: String, val fr: ParticipantFrenchRecord? = null)

data class ParticipantFrenchRecord(val role: String)

/** A line of `connections.jsonl`: two different people, how they were linked, when, and the source. */
data class ConnectionRecord(
    val people: List<String>,
    val kind: String,
    val year: Int,
    val text: String,
    val source: String,
    val fr: ConnectionFrenchRecord? = null,
)

data class ConnectionFrenchRecord(val kind: String, val text: String)

/**
 * A line of `anecdotes.jsonl`: a short story about one person, by Q-id, in a [year], and where it was read or heard.
 * Written by the workbench's publish from the anecdotes the curator approved.
 */
data class AnecdoteRecord(
    val id: String,
    val person: String,
    val year: Int,
    val text: String,
    val source: AnecdoteSourceRecord,
    val fr: AnecdoteFrenchRecord? = null,
)

data class AnecdoteFrenchRecord(val text: String)

/**
 * Where an anecdote comes from: a book ([title], [page]), a podcast, a video or a documentary ([title], [episode],
 * [minute]), or a web page ([url]). It has a title or a URL.
 */
data class AnecdoteSourceRecord(
    val title: String? = null,
    val page: String? = null,
    val episode: String? = null,
    val minute: String? = null,
    val url: String? = null,
)

/** A file in `moments/`, named after its id. People tied to the place [placeQid] come first in its lists. */
data class MomentRecord(
    val id: String,
    val region: String,
    val place: String,
    val placeQid: String? = null,
    val period: String,
    val start: Int,
    val end: Int,
    val focus: Int,
    val status: PublicationStatus,
    val featured: Boolean = false,
    val hook: String,
    val art: ArtRecord? = null,
    val world: WorldRecord? = null,
    val cards: List<CardRecord> = emptyList(),
    val doors: List<DoorRecord> = emptyList(),
    val fr: MomentFrenchRecord? = null,
)

/** The top of a moment's page in French, the world around it when the English has one. Its cards and doors have theirs. */
data class MomentFrenchRecord(val place: String, val period: String, val hook: String, val world: WorldRecord? = null)

data class ArtRecord(val url: String, val credit: String, val source: String)

data class WorldRecord(val governs: String, val everyday: String, val arts: String, val meanwhile: String)

/** A story card. Person, life and event cards name the record they show. */
data class CardRecord(
    val type: CardType,
    val kicker: String? = null,
    val title: String? = null,
    val text: String? = null,
    val year: Int? = null,
    val person: String? = null,
    val life: String? = null,
    val event: String? = null,
    val art: ArtRecord? = null,
    val fr: CardFrenchRecord? = null,
)

/** A card's kicker, title and text in French, those the English has. */
data class CardFrenchRecord(val kicker: String? = null, val title: String? = null, val text: String? = null)

/** A way out of the moment into the moment [to], showing [faces] when it follows people. */
data class DoorRecord(val to: String, val kind: String, val text: String, val faces: List<String> = emptyList(), val fr: DoorFrenchRecord? = null)

data class DoorFrenchRecord(val kind: String, val text: String)

/**
 * A line of `occupations.jsonl`: the domain of the people with this occupation (a Q-id), or none when it says nothing
 * of a life's work, so that their next occupation decides.
 */
data class OccupationRecord(val id: String, val domain: Domain? = null)

/**
 * A line of `corrections.jsonl`: the curator's corrections to a person of the fetched dataset, by Q-id. Only the fields
 * corrected are written; the others stay as Wikidata states them, but those [cleared], which hold nothing then. A
 * correction for someone written by hand in `people.jsonl`, or for a Q-id the dataset does not hold, changes nothing.
 */
data class CorrectionRecord(
    val id: String,
    val name: String? = null,
    /** The French name, for the French site: the English one compiles without it. */
    val nameFr: String? = null,
    val born: Int? = null,
    /** Wikidata's precision of the year of birth: 9 a year, 8 a decade, 7 a century, 10 and 11 a month and a day. */
    val bornPrecision: Int? = null,
    val died: Int? = null,
    val diedPrecision: Int? = null,
    /** The year of death is estimated: it then has no precision. */
    val diedEstimated: Boolean? = null,
    val approximate: Boolean? = null,
    /** Q-ids, in the order the site takes them: the first mapped one gives the domain. */
    val occupations: List<String>? = null,
    /** All their places, each a place of the dataset: the first birthplace gives their region. */
    val places: List<CorrectionPlaceRecord>? = null,
    /** The English Wikipedia article's title: the site fetches its intro and portrait from Wikipedia. */
    val wikipedia: String? = null,
    /** The French Wikipedia article's title. */
    val wikipediaFr: String? = null,
    val cleared: Set<ClearedField>? = null,
)

/** One of a person's places, by its Q-id, with the years they were there when known. */
data class CorrectionPlaceRecord(val kind: PlaceKind, val place: String, val start: Int? = null, val end: Int? = null)

/** The fields a correction can empty. */
enum class ClearedField { NAME_FR, DIED_PRECISION, WIKIPEDIA, WIKIPEDIA_FR }

/** A release as read from its directory, before it is compiled. */
data class Release(
    val regions: List<RegionRecord>,
    val eras: List<EraRecord>,
    val people: List<PersonRecord>,
    val occupations: List<OccupationRecord> = emptyList(),
    val corrections: List<CorrectionRecord> = emptyList(),
    /** Facts from Wikidata and Wikipedia, for people beyond those written by hand. */
    val dataset: FetchedDataset? = null,
    val lives: List<LifeRecord>,
    val events: List<EventRecord>,
    val connections: List<ConnectionRecord>,
    val anecdotes: List<AnecdoteRecord> = emptyList(),
    val moments: List<MomentRecord>,
)

/** What is wrong with a release, naming the file and the record. */
class InvalidReleaseException(message: String) : RuntimeException(message)
