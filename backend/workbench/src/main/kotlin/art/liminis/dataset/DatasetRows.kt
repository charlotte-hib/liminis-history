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
)
