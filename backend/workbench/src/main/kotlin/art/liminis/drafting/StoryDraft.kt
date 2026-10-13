package art.liminis.drafting

import art.liminis.domain.CardType

// The shape Claude must answer in. The JSON schema sent to the API is derived from these classes,
// so every line of a draft arrives with the quotes it rests on.

/** A passage copied exactly from one of the sources the drafter was given. */
data class Citation(val sourceId: String, val quote: String)

/** One sentence of the draft and the sources behind it. */
data class DraftLine(val text: String, val citations: List<Citation>)

data class DraftWorld(val governs: DraftLine, val everyday: DraftLine, val arts: DraftLine, val meanwhile: DraftLine)

data class DraftCard(
    val type: CardType,
    val kicker: String?,
    val title: String?,
    val text: String,
    /** For PERSON cards: the slug from the person's source id. */
    val personSlug: String?,
    /** For LIFE cards: the id from the life's source id. */
    val lifeId: String?,
    /** For EVENT cards: the id from the event's source id. */
    val eventId: String?,
    /** The year the card is set in. */
    val year: Int?,
    val citations: List<Citation>,
)

data class StoryDraft(val hook: DraftLine, val world: DraftWorld, val cards: List<DraftCard>)

/** A text the drafter may draw on, under a stable id it cites, e.g. `person:emile-zola`. */
data class Source(val id: String, val title: String, val text: String, val url: String?)
