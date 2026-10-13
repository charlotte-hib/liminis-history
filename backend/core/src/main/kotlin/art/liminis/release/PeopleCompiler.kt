package art.liminis.release

import art.liminis.dataset.ArticleRow
import art.liminis.dataset.OccupationRow
import art.liminis.dataset.PersonRow
import art.liminis.dataset.PlaceProperty
import art.liminis.dataset.PlaceRow
import art.liminis.domain.Domain
import art.liminis.domain.LONG_TEXT
import art.liminis.domain.Person
import art.liminis.domain.PersonPlace
import art.liminis.domain.PlaceKind
import art.liminis.support.cleanExtract
import art.liminis.support.dropHatnote
import art.liminis.support.leadSentences
import art.liminis.support.slugify
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The people of a release: those written by hand in `people.jsonl`, then every other person of the fetched dataset
 * whose region is known (the others wait for a correction). Someone written by hand keeps everything written there;
 * with a Q-id found in the dataset, they also take its places, sitelinks and English Wikipedia article.
 */
internal class PeopleCompiler(private val release: Release) {

    private val dataset = release.dataset
    private val rows = dataset?.people.orEmpty().associateBy { it.qid }
    private val domains = OccupationDomains(release.occupations, dataset?.occupations.orEmpty())

    init {
        release.people.mapNotNull { it.qid }.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
            throw InvalidReleaseException("people.jsonl: two people have the Q-id ${it[0]}")
        }
    }

    private val writtenQids = release.people.mapNotNull { it.qid }.toSet()

    /** The dataset's people not written by hand, each with their region: the country their birthplace lies in today. */
    private val fetched: List<Pair<PersonRow, String>> = dataset?.people.orEmpty()
        .filter { it.qid !in writtenQids }
        .mapNotNull { row -> homeRegion(row)?.let { row to it } }

    /** The regions fetched people come from that `regions.jsonl` does not list, named in English. They have no eras. */
    fun otherRegions(listed: Set<String>): Map<String, String> =
        fetched.map { it.second }.filter { it !in listed }.toSortedSet().associateWith { Locale.of("", it).getDisplayCountry(Locale.ENGLISH) }

    fun compile(references: References): List<Person> {
        val fame = fame(
            release.people.map { Ranked(it.id, it.region, it.born, rows[it.qid]?.sitelinks ?: 0) } +
                fetched.map { (row, region) -> Ranked(row.qid, region, row.born, row.sitelinks) },
        )
        val byHand = release.people.map { record ->
            references.at("people.jsonl ${record.id}") { writtenPerson(record, rows[record.qid], fame.getValue(record.id), references) }
        }
        byHand.groupBy { it.slug }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw InvalidReleaseException("people.jsonl: ${same.joinToString(" and ") { it.id }} have the same slug, ${same[0].slug}")
        }
        val slugs = fetchedSlugs(byHand.map { it.slug }.toSet())
        return byHand + fetched.map { (row, region) ->
            references.at("dataset/people.jsonl.gz ${row.qid}") {
                fetchedPerson(row, region, slugs.getValue(row.qid), fame.getValue(row.qid), references)
            }
        }
    }

    private fun writtenPerson(record: PersonRecord, row: PersonRow?, fame: Int, references: References): Person {
        val article = row?.let { dataset!!.articles[it.qid] }
        val text = article?.let(::intro)
        return Person(
            id = record.id,
            qid = record.qid,
            slug = slugify(record.name),
            name = record.name,
            birthYear = record.born,
            deathYear = record.died,
            datesApproximate = record.approximate,
            region = references.region(record.region),
            domain = record.domain,
            occupations = listOf(record.occupation),
            sitelinks = row?.sitelinks ?: 0,
            fame = fame,
            // Without an article from the dataset, WikipediaJob fills in the bio and portrait from this title.
            wikipediaTitle = article?.title ?: record.wikipedia ?: record.name.replace(' ', '_'),
            bioShort = record.bio ?: text?.let(::leadSentences),
            about = text?.let(::about),
            wikipediaUrl = article?.url,
            portraitUrl = article?.thumbnailUrl,
        ).apply { row?.let { places += places(it) } }
    }

    private fun fetchedPerson(row: PersonRow, region: String, slug: String, fame: Int, references: References): Person {
        val article = dataset!!.articles[row.qid]
        val text = article?.let(::intro)
        return Person(
            id = row.qid,
            qid = row.qid,
            slug = slug,
            name = row.label,
            birthYear = row.born,
            deathYear = row.died,
            datesApproximate = row.datesApproximate,
            region = references.region(region),
            domain = row.occupations.firstNotNullOfOrNull { domains.of(it.occupation) } ?: Domain.EVERYDAY,
            occupations = row.occupations.mapNotNull { dataset.occupations[it.occupation]?.label }.distinct().take(MAX_OCCUPATIONS),
            sitelinks = row.sitelinks,
            fame = fame,
            wikipediaTitle = article?.title,
            bioShort = text?.let(::leadSentences),
            about = text?.let(::about),
            wikipediaUrl = article?.url,
            portraitUrl = article?.thumbnailUrl,
        ).apply { places += places(row) }
    }

    private fun places(row: PersonRow): List<PersonPlace> = row.places.map {
        PersonPlace(
            placeQid = it.place,
            kind = PlaceKind.valueOf(PlaceProperty.of(it.property).name),
            regionCode = dataset!!.places[it.place]?.let(::country),
            startYear = it.startYear,
            endYear = it.endYear,
        )
    }

    /** Their birthplace's country today: by its coordinates, else by what Wikidata says. */
    private fun homeRegion(row: PersonRow): String? =
        row.places.firstOrNull { it.property == PlaceProperty.BIRTH.code }?.let { dataset!!.places[it.place] }?.let(::country)

    private fun country(place: PlaceRow): String? = place.coordinatesCountry ?: place.countries.firstNotNullOfOrNull { it.iso }

    /**
     * Slugs from their names. When several people share one, the most written about keeps it and the others add their
     * year of birth, then their Q-id. People written by hand keep theirs.
     */
    private fun fetchedSlugs(taken: Set<String>): Map<String, String> {
        val used = taken.toMutableSet()
        return fetched.map { it.first }
            .sortedWith(compareByDescending<PersonRow> { it.sitelinks }.thenBy { it.qid })
            .associate { row ->
                val name = slugify(row.label).ifEmpty { row.qid.lowercase() }
                val slug = sequenceOf(name, "$name-${birthLabel(row.born)}", "$name-${row.qid.lowercase()}").first { it !in used }
                used += slug
                row.qid to slug
            }
    }

    private fun birthLabel(year: Int) = if (year < 0) "${-year}-bce" else "$year"

    private data class Ranked(val id: String, val region: String, val born: Int, val sitelinks: Int)

    /**
     * Each person's percentile by sitelinks among the people of their region and century of birth: the share of them
     * with as many sitelinks or fewer. A poet near the top of Japan's 10th century scores high with few sitelinks.
     */
    private fun fame(people: List<Ranked>): Map<String, Int> =
        people.groupBy { it.region to century(it.born) }.values.flatMap { group ->
            val counts = group.map { it.sitelinks }.sorted()
            group.map { person ->
                val atMost = counts.binarySearchLast(person.sitelinks)
                person.id to (100.0 * atMost / counts.size).roundToInt()
            }
        }.toMap()

    /** How many of these sorted values are [value] or less. */
    private fun List<Int>.binarySearchLast(value: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) / 2
            if (this[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    /** The century a year is in, counted without a year 0: 1 to 100 is the 1st, 100 BCE to 1 BCE the -1st. */
    private fun century(year: Int) = if (year > 0) (year - 1) / 100 + 1 else -((-year - 1) / 100 + 1)

    private companion object {
        const val MAX_OCCUPATIONS = 3

        fun intro(article: ArticleRow): String? = dropHatnote(article.extract).takeIf { it.isNotBlank() }

        /** The intro's first paragraph, cut to whole sentences that fit the column. */
        fun about(intro: String): String {
            val paragraph = leadSentences(intro.substringBefore('\n'), LONG_TEXT)
            return if (paragraph.length <= LONG_TEXT) paragraph else paragraph.take(LONG_TEXT - 1) + "…"
        }
    }
}

/**
 * The domain of each occupation: from `occupations.jsonl`, else from the first of its classes there, up to three
 * levels up. An occupation (or class) mapped to no domain says nothing of a life's work: it gives none, and its classes
 * are not looked at.
 */
internal class OccupationDomains(records: List<OccupationRecord>, private val occupations: Map<String, OccupationRow>) {

    private val mapped = records.associate { it.id to it.domain }

    fun of(occupation: String): Domain? {
        if (occupation in mapped) return mapped[occupation]
        var level = listOf(occupation)
        repeat(CLASS_LEVELS) {
            level = level.flatMap { occupations[it]?.parents.orEmpty() }.distinct()
            level.firstNotNullOfOrNull { mapped[it] }?.let { return it }
            level = level.filter { it !in mapped }
        }
        return null
    }

    private companion object {
        const val CLASS_LEVELS = 3
    }
}
