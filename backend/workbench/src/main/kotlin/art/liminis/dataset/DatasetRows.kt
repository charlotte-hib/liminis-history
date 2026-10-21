package art.liminis.dataset

/** A person of the dataset as read back, with the labels of their occupations and places. */
data class DatasetPerson(
    val qid: String,
    val label: String,
    val labelFr: String?,
    val born: Int,
    val bornPrecision: Int,
    val died: Int,
    val diedPrecision: Int?,
    val diedEstimated: Boolean,
    val datesApproximate: Boolean,
    val enwiki: String?,
    val frwiki: String?,
    val occupations: List<DatasetOccupation>,
    val places: List<DatasetPlace>,
    /** Each flag with its reason: the person's, then their places', such as `P19_DISPUTED_AREA`. */
    val flags: List<Pair<String, String>>,
)

/** An occupation with its labels, none when Wikidata deleted it. */
data class DatasetOccupation(val occupation: String, val label: String?, val labelFr: String?)

/** A place of the person's, with its label (none when Wikidata deleted it) and the country it lies in today. */
data class DatasetPlace(
    val property: String,
    val place: String,
    val label: String?,
    val country: String?,
    val startYear: Int?,
    val endYear: Int?,
)

/** A person found by name, as a list of matches shows them. */
data class PersonSummary(
    val person: String,
    val label: String,
    val labelFr: String?,
    val born: Int,
    val died: Int,
    /** The first occupation's label, the one the site shows. */
    val occupation: String?,
    /** How many Wikimedia sites have an article about them: how well known they are. */
    val sitelinks: Int,
)

/** A person as a story card shows them: [portrait] is a Wikipedia thumbnail, [intro] the English article's. */
data class PersonPortrait(
    val person: String,
    val label: String,
    val labelFr: String?,
    val born: Int,
    val died: Int,
    val occupation: String?,
    val sitelinks: Int,
    val portrait: String?,
    val intro: String?,
)

/**
 * A person a search found for a story card: [portrait] is a Wikipedia thumbnail; [exact] when the search named their
 * Q-id, [here] when they lived in the card's moment, [starts] when their label starts with the search.
 */
data class FoundPerson(
    val person: String,
    val label: String,
    val labelFr: String?,
    val born: Int,
    val died: Int,
    val occupation: String?,
    val sitelinks: Int,
    val portrait: String?,
    val exact: Boolean,
    val here: Boolean,
    val starts: Boolean,
)

/** A person's English Wikipedia intro, for drafting from. */
data class Intro(val person: String, val title: String, val url: String, val extract: String)

/** A place of the dataset, with the country it lies in today and how many people it is a place of. */
data class FoundPlace(val place: String, val label: String, val labelFr: String?, val country: String?, val people: Int)

/** An occupation of the dataset, or a class above some, with how many people hold it. */
data class FoundOccupation(val occupation: String, val label: String, val labelFr: String?, val people: Int)
