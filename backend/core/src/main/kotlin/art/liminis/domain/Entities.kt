package art.liminis.domain

import jakarta.persistence.AttributeOverride
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.OrderColumn
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class Domain { POWER, ARTS, EVERYDAY }

/** Column length for prose and URLs, which outgrow the 255-character default. */
const val LONG_TEXT = 2000

/** The languages the site is read in. A record's text is in English, and in French when translated. */
enum class Language { EN, FR }

// A record's French, beside its English: in columns of their own, suffixed _fr, all null where there is none, and
// embedded when the record has several texts. Each record has every one of its texts in French, or none.

/**
 * A place, seen through today's borders, e.g. "FR" for France. Who governed it changes over time: see [Era]. Only
 * regions whose eras are written have pages; the others hold the people born there.
 */
@Entity
class Region @Default constructor(
    @Id val code: String,
    val name: String,
    val nameFr: String? = null,
)

/** A region at a time span, named after who governed it. Never a bare year. Both years are inclusive. */
@Entity
class Era @Default constructor(
    @Id val id: String,
    @ManyToOne(fetch = FetchType.LAZY) val region: Region,
    val label: String,
    val governedBy: String,
    val startYear: Int,
    val endYear: Int,
    @Embedded val fr: EraFrench? = null,
)

@Embeddable
data class EraFrench @Default constructor(
    @Column(name = "label_fr") val label: String,
    @Column(name = "governed_by_fr") val governedBy: String,
)

/**
 * Updates set only the columns that changed: the site's API may update a person's Wikipedia fields and nothing else
 * (core's V2__reader.sql), and Hibernate would otherwise set every column.
 */
@Entity
@DynamicUpdate
class Person @Default constructor(
    /** The id in `people.jsonl` for a person written by hand, else the Q-id. */
    @Id val id: String,
    /** Wikidata's id, e.g. "Q535". */
    @Column(unique = true) val qid: String?,
    @Column(unique = true) val slug: String,
    val name: String,
    val birthYear: Int,
    val deathYear: Int?,
    /** True when the birth or death year is only known approximately. */
    val datesApproximate: Boolean,
    @ManyToOne(fetch = FetchType.LAZY) val region: Region,
    @Enumerated(EnumType.STRING) val domain: Domain,
    /** One to three, the main one first, e.g. "painter". None when Wikidata states none. */
    @JdbcTypeCode(SqlTypes.ARRAY) val occupations: List<String>,
    /** How many Wikipedias and other Wikimedia sites have a page on them: how widely they are written about today. */
    val sitelinks: Int,
    /** Their percentile by [sitelinks] among the people of their region and century of birth, from 0 to 100. */
    val fame: Int,
    val wikipediaTitle: String?,
    /** One or two sentences: hand-written, or the lead of the Wikipedia article. */
    @Column(length = LONG_TEXT) var bioShort: String? = null,
    /** The first paragraph of the Wikipedia article. Filled by [art.liminis.jobs.WikipediaJob]. */
    @Column(length = LONG_TEXT) var about: String? = null,
    @Column(length = LONG_TEXT) var wikipediaUrl: String? = null,
    @Column(length = LONG_TEXT) var portraitUrl: String? = null,
    /** Their name as French writes it, when it is written. */
    val nameFr: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) val occupationsFr: List<String>? = null,
    /** The French Wikipedia article's, filled like the English ones: hand-written, or by the job. */
    val wikipediaTitleFr: String? = null,
    @Column(length = LONG_TEXT) var bioShortFr: String? = null,
    @Column(length = LONG_TEXT) var aboutFr: String? = null,
    @Column(length = LONG_TEXT) var wikipediaUrlFr: String? = null,
) {
    /** Where they were born, died, worked and lived, in that order: they tie them to moments beyond their own region. */
    @ElementCollection
    @CollectionTable(name = "person_place")
    @OrderColumn(name = "position")
    val places: MutableList<PersonPlace> = mutableListOf()
}

enum class PlaceKind { BIRTH, DEATH, WORK, RESIDENCE }

/** One of a person's places, with the years they were there when known. */
@Embeddable
data class PersonPlace @Default constructor(
    /** Wikidata's id of the place, e.g. "Q90" for Paris. */
    val placeQid: String,
    /** Its name as Wikidata labels it in English, else in any language. */
    val name: String,
    val nameFr: String?,
    @Enumerated(EnumType.STRING) val kind: PlaceKind,
    /** The region it lies in today, when known: any country, not only those with eras. */
    val regionCode: String?,
    val startYear: Int?,
    val endYear: Int?,
)

/** A typical existence in an era, shown as representative of the period rather than as a real person. */
@Entity
class Life @Default constructor(
    @Id val id: String,
    @ManyToOne(fetch = FetchType.LAZY) val era: Era,
    val label: String,
    @Column(length = LONG_TEXT) val description: String,
    val startYear: Int,
    val endYear: Int,
    @Embedded val art: LifeArt? = null,
    /** Drawn when there is no [art]. */
    @Enumerated(EnumType.STRING) val icon: LifeIcon? = null,
    @Embedded val fr: LifeFrench? = null,
)

@Embeddable
data class LifeFrench @Default constructor(
    @Column(name = "label_fr") val label: String,
    @Column(name = "description_fr", length = LONG_TEXT) val description: String,
)

/** A public-domain image of a typical life, and how to frame it in a circle. */
@Embeddable
data class LifeArt @Default constructor(
    @Column(length = LONG_TEXT) val url: String,
    val credit: String,
    @Column(length = LONG_TEXT) val sourceUrl: String,
    /** The point kept in view when cropping, as CSS `object-position`, e.g. "50% 70%". */
    val position: String,
    @Enumerated(EnumType.STRING) val fit: ArtFit,
)

/** How an image fills its frame: cropped to fill it, or whole, with a margin around it. */
enum class ArtFit { COVER, CONTAIN }

/** The icons a typical life without an image can show, after the work it stands for. */
enum class LifeIcon {
    AMPHORA, BRIEFCASE, CAR, COINS, CONCIERGE_BELL, FACTORY, FISH, HAMMER, LEAF, PICKAXE, SHELL, SHIELD, SHIRT, SHOVEL, SPOOL,
    SPROUT, WHEAT,
}

/** A dated, sourced event. The only way two people get connected. */
@Entity
class Event @Default constructor(
    @Id val id: String,
    @ManyToOne(fetch = FetchType.LAZY) val era: Era,
    val title: String,
    @Column(name = "event_year") val year: Int,
    @Column(length = LONG_TEXT) val description: String,
    @Column(length = LONG_TEXT) val sourceUrl: String,
    @Embedded val fr: EventFrench? = null,
) {
    /** In the order they were stored. */
    @OneToMany(mappedBy = "event", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("id")
    val participants: MutableList<EventParticipant> = mutableListOf()

    fun participant(person: Person, role: String, roleFr: String? = null) = apply {
        participants += EventParticipant(event = this, person = person, role = role, roleFr = roleFr)
    }
}

/** An event's title and description in French; its participants' roles are beside them. */
@Embeddable
data class EventFrench @Default constructor(
    @Column(name = "title_fr") val title: String,
    @Column(name = "description_fr", length = LONG_TEXT) val description: String,
)

@Entity
class EventParticipant(
    @Id @GeneratedValue val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) val event: Event,
    @ManyToOne(fetch = FetchType.LAZY) val person: Person,
    /** Specific role in the event, e.g. "author", "placed 1st". */
    val role: String,
    /** The role in French, in an event translated. */
    val roleFr: String? = null,
)

/**
 * How two people knew each other, in one sourced sentence: friends, rivals, a portrait, a commission.
 * Stored once per pair; [year] is when the link is documented.
 */
@Entity
class Connection @Default constructor(
    @Id @GeneratedValue val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) val first: Person,
    @ManyToOne(fetch = FetchType.LAZY) val second: Person,
    /** A few words, e.g. "school friends". */
    val kind: String,
    @Column(name = "connection_year") val year: Int,
    @Column(length = LONG_TEXT) val text: String,
    @Embedded
    @AttributeOverride(name = "title", column = Column(name = "source_title", length = LONG_TEXT))
    @AttributeOverride(name = "page", column = Column(name = "source_page"))
    @AttributeOverride(name = "episode", column = Column(name = "source_episode"))
    @AttributeOverride(name = "minute", column = Column(name = "source_minute"))
    @AttributeOverride(name = "url", column = Column(name = "source_url", length = LONG_TEXT))
    val source: CitedSource,
    @Embedded val fr: ConnectionFrench? = null,
) {
    /** The person on the other side of the link from [person]. */
    fun other(person: Person) = if (first.slug == person.slug) second else first
}

@Embeddable
data class ConnectionFrench @Default constructor(
    @Column(name = "kind_fr") val kind: String,
    @Column(name = "text_fr", length = LONG_TEXT) val text: String,
)

/** A short story about a person, in a year, approved by the curator, with where it was read or heard. */
@Entity
class Anecdote @Default constructor(
    @Id val id: String,
    @ManyToOne(fetch = FetchType.LAZY) val person: Person,
    @Column(name = "anecdote_year") val year: Int,
    @Column(length = LONG_TEXT) val text: String,
    @Embedded val source: CitedSource,
    @Column(length = LONG_TEXT) val textFr: String? = null,
)

/**
 * Where an anecdote or a connection comes from: a book (title, page), a podcast, a video or a documentary (title,
 * episode, minute), or a web page (url).
 */
@Embeddable
data class CitedSource @Default constructor(
    @Column(length = LONG_TEXT) val title: String?,
    val page: String?,
    val episode: String?,
    val minute: String?,
    @Column(length = LONG_TEXT) val url: String?,
)

/** Draft content comes from the drafting job and is only served once a curator publishes it. */
enum class PublicationStatus { DRAFT, PUBLISHED }

/** A public-domain image with its credit and the page it comes from. */
@Embeddable
data class Artwork @Default constructor(
    @Column(length = LONG_TEXT) val url: String,
    val credit: String,
    @Column(length = LONG_TEXT) val sourceUrl: String,
)

/** The world around a moment: one line per domain, and what is happening elsewhere at the same time. */
@Embeddable
data class WorldAround @Default constructor(
    @Column(length = LONG_TEXT) val governs: String,
    @Column(length = LONG_TEXT) val everyday: String,
    @Column(length = LONG_TEXT) val arts: String,
    @Column(length = LONG_TEXT) val meanwhile: String,
)

/** A place over a few years, small enough to tell as one story. The unit people step into. */
@Entity
class Moment @Default constructor(
    @Id val id: String,
    @ManyToOne(fetch = FetchType.LAZY) val region: Region,
    /** Where, e.g. "Paris". */
    val place: String,
    /** Wikidata's id of [place], e.g. "Q90": people tied to it come first. */
    val placeQid: String?,
    /** When, as people say it, e.g. "1870s" or "c. 1000". */
    val period: String,
    val startYear: Int,
    val endYear: Int,
    /** The year the moment opens on. */
    val focusYear: Int,
    @Column(length = LONG_TEXT) var hook: String,
    @Embedded var art: Artwork?,
    @Embedded var world: WorldAround?,
    @Enumerated(EnumType.STRING) var status: PublicationStatus,
    /** Shown first on the start page. */
    var featured: Boolean = false,
    @Embedded val fr: MomentFrench? = null,
) {
    @OneToMany(mappedBy = "moment", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position")
    val cards: MutableList<StoryCard> = mutableListOf()

    @OneToMany(mappedBy = "origin", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position")
    val doors: MutableList<Door> = mutableListOf()
}

/** The top of a moment's page in French; its cards and doors have theirs. */
@Embeddable
data class MomentFrench @Default constructor(
    @Column(name = "place_fr") val place: String,
    @Column(name = "period_fr") val period: String,
    @Column(name = "hook_fr", length = LONG_TEXT) val hook: String,
    @Embedded
    @AttributeOverride(name = "governs", column = Column(name = "governs_fr", length = LONG_TEXT))
    @AttributeOverride(name = "everyday", column = Column(name = "everyday_fr", length = LONG_TEXT))
    @AttributeOverride(name = "arts", column = Column(name = "arts_fr", length = LONG_TEXT))
    @AttributeOverride(name = "meanwhile", column = Column(name = "meanwhile_fr", length = LONG_TEXT))
    val world: WorldAround?,
)

enum class CardType { SCENE, PERSON, LIFE, EVENT }

/** One screen of a moment's story. Person, life and event cards point at the records they are about. */
@Entity
class StoryCard @Default constructor(
    @Id @GeneratedValue val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) val moment: Moment,
    val position: Int,
    @Enumerated(EnumType.STRING) val type: CardType,
    val kicker: String? = null,
    val title: String? = null,
    @Column(length = LONG_TEXT) val text: String? = null,
    /** The year the card is set in, used for ages. */
    @Column(name = "card_year") val year: Int? = null,
    @ManyToOne(fetch = FetchType.LAZY) val person: Person? = null,
    @ManyToOne(fetch = FetchType.LAZY) val life: Life? = null,
    @ManyToOne(fetch = FetchType.LAZY) val event: Event? = null,
    /** Replaces the moment's artwork while this card is shown. */
    @Embedded val art: Artwork? = null,
    /** In French, with the texts the card has in English: a card with none, showing only its record, has no French. */
    @Embedded val fr: CardFrench? = null,
)

@Embeddable
data class CardFrench @Default constructor(
    @Column(name = "kicker_fr") val kicker: String?,
    @Column(name = "title_fr") val title: String?,
    @Column(name = "text_fr", length = LONG_TEXT) val text: String?,
)

/** A curated way out of a moment's story into another moment, e.g. "Meanwhile, elsewhere". */
@Entity
class Door @Default constructor(
    @Id @GeneratedValue val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) val origin: Moment,
    @ManyToOne(fetch = FetchType.LAZY) val target: Moment,
    val position: Int,
    val kind: String,
    val text: String,
    /** People to show on the door, when it follows someone. */
    @ManyToMany
    @JoinTable(name = "door_face")
    @OrderColumn(name = "position")
    val faces: List<Person> = emptyList(),
    @Embedded val fr: DoorFrench? = null,
)

@Embeddable
data class DoorFrench @Default constructor(
    @Column(name = "kind_fr") val kind: String,
    @Column(name = "text_fr") val text: String,
)
