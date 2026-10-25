package art.liminis.curation

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import art.liminis.domain.Artwork
import art.liminis.domain.CardType

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
    JsonSubTypes.Type(Story::class, name = "STORY"),
    JsonSubTypes.Type(MomentPage::class, name = "MOMENT_PAGE"),
    JsonSubTypes.Type(Translation::class, name = "TRANSLATION"),
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

/**
 * Two people linked at a date, in one plain sentence, and where it was read or heard: the site names its [source] under
 * it. [people] by Q-id, in the order [kind] reads ("sculptor and subject": the sculptor first).
 */
data class Connection(val people: List<String>, val kind: String, val year: Int, val text: String, val source: SourceRef) : Payload

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

/**
 * A moment's story, drafted with Claude: the cards that publishing writes into the moment's file. Each card keeps the
 * quotes it was written from, which are not published.
 */
data class Story(val moment: String, val cards: List<StoryCard>) : Payload

/**
 * The top of a moment's page, its header: the hook under its title, the world around it and the moment's painting,
 * which publishing writes into the moment's file. Drafted with Claude beside the story (the painting kept from the
 * release), or written from the release's on the header's page. Each line keeps the quotes it was written from, if
 * any, which are not published.
 */
data class MomentPage(val moment: String, val hook: StoryLine, val world: StoryWorld, val art: Painting? = null) : Payload

data class StoryLine(val text: String, val citations: List<Citation> = emptyList())

data class StoryWorld(val governs: StoryLine, val everyday: StoryLine, val arts: StoryLine, val meanwhile: StoryLine)

/**
 * One card. A person card names a person by the id of one written by hand, else by Q-id; [label] is the name of
 * whoever or whatever the card shows, for reading.
 */
data class StoryCard(
    val type: CardType,
    val kicker: String? = null,
    val title: String? = null,
    val text: String? = null,
    val year: Int? = null,
    val person: String? = null,
    val life: String? = null,
    val event: String? = null,
    val label: String? = null,
    val citations: List<Citation> = emptyList(),
    /** The card's own painting. Without one, the card shows the moment's. */
    val art: Painting? = null,
)

/**
 * An image a card or a moment shows: its address, the credit under it and the page it is published on, as publishing
 * writes them. Its [licence], checked when the curator chose it, is not published: none for a painting the release
 * had, chosen by hand.
 */
data class Painting(val url: String, val credit: String, val source: String, val licence: PaintingLicence? = null) {
    companion object {
        /** A painting of the release's, chosen by hand: its licence unrecorded. */
        fun of(artwork: Artwork) = Painting(artwork.url, artwork.credit, artwork.sourceUrl)
    }
}

/** The licences a painting may have: none asks for attribution. */
enum class PaintingLicence { PUBLIC_DOMAIN, CC0 }

/**
 * A record of `data/` in French: each of its texts, by its [TranslatedText.path] in the record, beside its English as
 * the record had it when it was translated. [record] names it by kind and id (`moment:paris-1870s`,
 * `event:carmen-premiere`, `connection:claude-monet|georges-clemenceau:friends`); [label] says what it is, in English,
 * for reading. Publishing writes the French beside the English, while the English is still the same.
 */
data class Translation(val record: String, val label: String, val texts: List<TranslatedText>) : Payload

/**
 * One text of a record: where it is (`hook`, `world.governs`, `cards[2].text`, `participants[0].role`), its English and
 * its French.
 */
data class TranslatedText(val path: String, val en: String, val fr: String)

/** A passage copied from the text [source] (`person:Q535`, `event:carmen-premiere`), titled [title]. */
data class Citation(val source: String, val title: String, val quote: String)
