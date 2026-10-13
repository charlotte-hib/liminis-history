package dev.wholivedwhen.dataset

import java.time.Instant

// The fetched dataset's files, one class per kind of line: written by the workbench's export, read by the release
// compiler beside data/. Changing a class changes the files' format (DatasetManifest.format).

/**
 * The fetched dataset's files, as the workbench's export writes them and a release's `dataset/` holds them, with the
 * media type each is pushed with. backend/workbench/wb and sample_dataset.py name them too: keep them in step.
 */
enum class DatasetFile(val fileName: String, val mediaType: String) {
    MANIFEST("manifest.json", "application/vnd.wholivedwhen.dataset.manifest.v1+json"),
    PEOPLE("people.jsonl.gz", "application/vnd.wholivedwhen.dataset.people.v1.jsonl+gzip"),
    ARTICLES("articles.jsonl.gz", "application/vnd.wholivedwhen.dataset.articles.v1.jsonl+gzip"),
    PLACES("places.jsonl.gz", "application/vnd.wholivedwhen.dataset.places.v1.jsonl+gzip"),
    OCCUPATIONS("occupations.jsonl.gz", "application/vnd.wholivedwhen.dataset.occupations.v1.jsonl+gzip"),
}

/** `manifest.json`: what the fetched dataset holds, and from which import. */
data class DatasetManifest(val format: Int, val import: MappedImport, val counts: DatasetFileCounts)

/** The lines of each of the fetched dataset's files. */
data class DatasetFileCounts(val people: Int, val articles: Int, val places: Int, val occupations: Int)

/** The import a mapping run read: the facts are as Wikimedia gave them between its start and its end. */
data class MappedImport(val run: Long, val startedAt: Instant, val finishedAt: Instant)

/**
 * One person of the fetched dataset: facts as Wikidata states them, with no decision applied (corrections, regions and
 * domains come from `data/`, at compile time). Years count as the site does: negative for BCE, no year 0.
 */
data class PersonRow(
    val qid: String,
    /** The English label, else the English Wikipedia title, else the label shared by all languages, else any. */
    val label: String,
    /** The French label, else the label shared by all languages. */
    val labelFr: String?,
    val sitelinks: Int,
    val born: Int,
    /** Wikidata's precision: 9 a year, 8 a decade, 7 a century, 10 and 11 a month and a day. */
    val bornPrecision: Int,
    val died: Int,
    /** Null when the year of death is estimated. */
    val diedPrecision: Int?,
    val diedEstimated: Boolean,
    /** A date coarser than a year, a "circa", or an estimated death. */
    val datesApproximate: Boolean,
    /** Sex or gender (P21), as a Q-id: only to choose the feminine form of an occupation in French. */
    val gender: String?,
    val enwiki: String?,
    val frwiki: String?,
    val occupations: List<PersonOccupation>,
    val places: List<PersonPlace>,
    val flags: Set<PersonFlag>,
)

/** A person's Wikipedia article in one [language], `en` or `fr`: its intro as plain text, as Wikipedia gives it. */
data class ArticleRow(
    /** The person's Q-id. */
    val person: String,
    val language: String,
    /** The page's title, past any redirect from the sitelink's. */
    val title: String,
    val url: String,
    val extract: String,
    /** A thumbnail of the page image. */
    val thumbnailUrl: String?,
    /** The page image's file name on Commons, to credit it. */
    val image: String?,
)

/** One of a person's occupations (P106), in Wikidata's order, best rank first. */
data class PersonOccupation(val occupation: String, val referenced: Boolean)

/** A place of birth (P19), death (P20), work (P937) or residence (P551), with its start and end years when stated. */
data class PersonPlace(val property: String, val place: String, val startYear: Int?, val endYear: Int?, val referenced: Boolean)

/** The properties of a person's places, in the order the dataset lists them. */
enum class PlaceProperty(val code: String) {
    BIRTH("P19"),
    DEATH("P20"),
    WORK("P937"),
    RESIDENCE("P551");

    companion object {
        fun of(code: String): PlaceProperty = entries.first { it.code == code }

        /** Sorts rows of `dataset.person_place` (aliased [table]) in that order. */
        fun sqlOrder(table: String) = "array_position(array[${entries.joinToString { "'${it.code}'" }}], $table.property)"
    }
}

/** Why a person's facts need a person's eye: the flag of the review policy, and the reason for it. */
enum class PersonFlag(val flag: String) {
    BIRTH_UNREFERENCED(POORLY_DOCUMENTED),
    DEATH_UNREFERENCED(POORLY_DOCUMENTED),
    /** The first occupation, the one the site shows. */
    OCCUPATION_UNREFERENCED(POORLY_DOCUMENTED),
    BIRTH_IMPRECISE(POORLY_DOCUMENTED),
    DEATH_IMPRECISE(POORLY_DOCUMENTED),
    DEATH_ESTIMATED(POORLY_DOCUMENTED),
    /** Fewer than 25 sitelinks, and no article in English or French Wikipedia. */
    FEW_SITELINKS_NO_ARTICLE(POORLY_DOCUMENTED),
    /** Several dates of birth that disagree: the earliest was taken. */
    BIRTH_DISAGREES(CONFLICT),
    DEATH_DISAGREES(CONFLICT),
}

private const val POORLY_DOCUMENTED = "POORLY_DOCUMENTED"
private const val CONFLICT = "CONFLICT"

/** An occupation, or a class above one (P279), with its labels and its classes. */
data class OccupationRow(
    val qid: String,
    /** The English label, else the label shared by all languages, else any. */
    val label: String,
    val labelFr: String?,
    /** The French feminine form (P2521), for women. */
    val femaleLabelFr: String?,
    /** The classes it belongs to, best rank first. */
    val parents: List<String>,
)

/** A place people were born, died, worked or lived in, and where it lies today. */
data class PlaceRow(
    val qid: String,
    /** The English label, else the label shared by all languages, else any. */
    val label: String,
    val labelFr: String?,
    /** Its coordinates (P625), best rank first. Ancient places often have none. */
    val coordinates: Coordinates?,
    /** The ISO code of the country its coordinates lie in today, by Natural Earth's borders, or lie just off. */
    val coordinatesCountry: String?,
    /** The disputed area its coordinates lie in, by Natural Earth, with who administers and claims it. */
    val disputedArea: String?,
    /** The countries Wikidata says it is in today (P17 with no end time), best rank first. */
    val countries: List<PlaceCountry>,
    val flags: Set<PlaceFlag>,
)

/** A country of a place's (P17), with the ISO code Natural Earth gives it. None for a state of the past. */
data class PlaceCountry(val country: String, val iso: String?)

/** Why a place needs a person's eye: the flag of the review policy, and the reason for it. */
enum class PlaceFlag(val flag: String) {
    DISPUTED_AREA(SENSITIVE),
}

private const val SENSITIVE = "SENSITIVE"

/** A point on Earth, in degrees. */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * The fetched dataset as a release reads it: its people, and by Q-id their English Wikipedia articles (the site shows
 * no other language yet), places and occupations.
 */
class FetchedDataset(
    val manifest: DatasetManifest,
    val people: List<PersonRow>,
    val articles: Map<String, ArticleRow>,
    val places: Map<String, PlaceRow>,
    val occupations: Map<String, OccupationRow>,
)
