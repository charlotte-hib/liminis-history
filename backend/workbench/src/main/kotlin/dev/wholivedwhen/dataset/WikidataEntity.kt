package dev.wholivedwhen.dataset

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

/**
 * A Wikidata entity as `wbgetentities` returns it and `raw.entity` keeps it, with only what the mapping reads. See
 * https://doc.wikimedia.org/Wikibase/master/php/docs_topics_json.html.
 */
data class WikidataEntity(
    val id: String,
    val labels: Map<String, Term> = emptyMap(),
    val claims: Map<String, List<Statement>> = emptyMap(),
    val sitelinks: Map<String, Sitelink> = emptyMap(),
) {
    /** The first of [languages] this entity has a label in. */
    fun label(vararg languages: String): String? = languages.firstNotNullOfOrNull { labels[it]?.value }

    /** The first of [languages] this entity has a label in, else any label, else its Q-id. */
    fun name(vararg languages: String): String = label(*languages) ?: labels.values.firstOrNull()?.value ?: id

    /** The statements of [property] in Wikidata's order, best rank first, without the deprecated ones. */
    fun statements(property: String): List<Statement> =
        claims[property].orEmpty().filter { it.rank != Rank.DEPRECATED }.sortedBy { it.rank }

    /** The Q-ids [property] points to, best rank first. */
    fun ids(property: String): List<String> = statements(property).mapNotNull { it.mainsnak.id }
}

data class Term(val value: String)

data class Sitelink(val title: String)

data class Statement(
    val mainsnak: Snak,
    val rank: Rank,
    val qualifiers: Map<String, List<Snak>> = emptyMap(),
    val references: List<JsonNode> = emptyList(),
) {
    /**
     * The references naming a source. One that only says which Wikimedia project the statement was copied from (P143,
     * with its URL or retrieval date) does not count: it says where the value came from, not who stated it.
     */
    val sourcedReferences get() = references.count { reference -> reference.path("snaks").propertyNames().any { it !in WIKIMEDIA_IMPORT } }

    /** Has a reference naming a source. */
    val referenced get() = sourcedReferences > 0

    /** Qualified "circa" (P1480, sourcing circumstances). */
    val circa get() = qualifiers[SOURCING_CIRCUMSTANCES].orEmpty().any { it.id == CIRCA }

    /** The year of the time qualifier [property], such as a start (P580) or end (P582) time. */
    fun qualifierYear(property: String): Int? = qualifiers[property].orEmpty().firstNotNullOfOrNull { it.time?.year }

    private companion object {
        const val SOURCING_CIRCUMSTANCES = "P1480"
        const val CIRCA = "Q5727902"

        /** Imported from Wikimedia project (P143), Wikimedia import URL (P4656), retrieved (P813). */
        val WIKIMEDIA_IMPORT = setOf("P143", "P4656", "P813")
    }
}

/** Declared in this order, so that sorting by rank puts the preferred statements first. */
enum class Rank {
    @JsonProperty("preferred") PREFERRED,
    @JsonProperty("normal") NORMAL,
    @JsonProperty("deprecated") DEPRECATED,
}

/** A statement's value, or a qualifier's: a known value, an "unknown value" (`somevalue`), or "no value". */
data class Snak(val snaktype: String, val datavalue: DataValue? = null) {
    val unknown get() = snaktype == "somevalue"

    private val value get() = datavalue?.value?.takeIf { snaktype == "value" }

    /** The Q-id of an item value. */
    val id: String? get() = value?.get("id")?.asString()

    /** A point in time. */
    val time: WikidataTime?
        get() = value?.takeIf { it.has("time") }?.let { WikidataTime.of(it.path("time").asString(), it.path("precision").asInt()) }

    /** A point on Earth. Null for one on another globe, such as a crater on the Moon. */
    val coordinates: Coordinates?
        get() = value?.takeIf { it.has("latitude") && it.path("globe").asString() == EARTH }
            ?.let { Coordinates(it.path("latitude").asDouble(), it.path("longitude").asDouble()) }

    /** A text in one language, as `language` to `text`. */
    val text: Pair<String, String>?
        get() = value?.takeIf { it.has("text") }?.let { it.path("language").asString() to it.path("text").asString() }
}

private const val EARTH = "http://www.wikidata.org/entity/Q2"

data class DataValue(val value: JsonNode)

/**
 * A point in time, as Wikidata writes it: `+1802-02-26T00:00:00Z` or `-0100-07-12T00:00:00Z`, with a precision. Years
 * count as the site does, with no year 0 (-100 is 100 BCE), so they are taken as they are.
 */
data class WikidataTime(val year: Int, val precision: Int) {

    /** Coarser than a year: a decade, a century, a millennium. */
    val imprecise get() = precision < YEAR

    companion object {
        const val YEAR = 9

        /** Null for the year 0, which does not exist, and for years beyond history (the age of the universe). */
        fun of(time: String, precision: Int): WikidataTime? {
            val year = time.substring(0, time.indexOf('-', 1)).toIntOrNull()
            return if (year == null || year == 0) null else WikidataTime(year, precision)
        }
    }
}
