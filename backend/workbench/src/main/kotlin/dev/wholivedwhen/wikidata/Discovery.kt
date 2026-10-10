package dev.wholivedwhen.wikidata

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import dev.wholivedwhen.wikimedia.SparqlTimeoutException
import dev.wholivedwhen.wikimedia.WikimediaClient
import java.time.Duration
import java.time.LocalDate

@ConfigurationProperties("app.wikidata")
data class WikidataProperties(
    /** The earliest year of birth, astronomical (0 is 1 BCE, -3499 is 3500 BCE), as the query service counts. */
    val bornFrom: Int,
    /** The year of birth to stop before. Without one, the year after the import started. */
    val bornUntil: Int? = null,
    /** The fewest sitelinks someone needs to be imported... */
    val sitelinks: Int,
    /** ...or this many, when born before [earlyUntil]: sitelinks favour recent people. */
    val earlySitelinks: Int,
    val earlyUntil: Int,
    /** People with no date of death born within this many years are taken to be alive, and left out. */
    val livingYears: Int,
    /** How many years of births to ask the query service for at once, before each [SliceYears.until]; then one. */
    val sliceYears: List<SliceYears>,
    /** The languages of labels and aliases to keep. `mul` is the label shared by all languages, when no other is set. */
    val languages: List<String>,
    /** The Wikipedia editions (`enwiki`) whose sitelinks to keep, and whose articles' intros to fetch. */
    val sites: List<String>,
    /** The properties whose values are fetched too: places of birth, death, work and residence, and occupations. */
    val linkedProperties: List<String>,
    /** How many levels of classes (P279) above the occupations are fetched too. */
    val classLevels: Int,
    /** How many times in a row the import resumes by itself, without progress, when Wikimedia keeps asking to wait. */
    val restarts: Int,
    /** How long it pauses first, on top of any `Retry-After`. */
    val restartPause: Duration,
)

data class SliceYears(val until: Int, val years: Int)

/**
 * People born from [from], up to but not including [until]. Dates as the query service writes them: astronomical
 * years, so 0 is 1 BCE. One year off does not matter here: it only decides which query finds someone, and dates
 * are read from the entities later.
 */
data class BirthSlice(val from: LocalDate, val until: LocalDate) {

    init {
        require(from < until) { "Empty slice from $from to $until" }
    }

    val days get() = until.toEpochDay() - from.toEpochDay()

    /** The two halves of this slice, which always split the same way, so a resumed run finds its halves again. */
    fun halves(): List<BirthSlice> {
        check(days >= 2) { "$this cannot be split any further" }
        val middle = from.plusDays(days / 2)
        return listOf(BirthSlice(from, middle), BirthSlice(middle, until))
    }

    operator fun contains(other: BirthSlice) = from <= other.from && other.until <= until

    override fun toString() = "born $from to $until"
}

/** The slices of birth dates one import asks the query service for, and the query for each. */
class DiscoveryPlan(private val properties: WikidataProperties, started: LocalDate) {

    private val bornUntil = properties.bornUntil ?: (started.year + 1)
    private val earlyUntil = yearStart(properties.earlyUntil)
    private val livingFrom = yearStart(started.year - properties.livingYears)

    /**
     * Each one small enough for the query service's 60 seconds: there are more people with dates of birth, human
     * or not, the closer to today. None crosses [WikidataProperties.earlyUntil], where the cut-off changes.
     */
    fun slices(): List<BirthSlice> {
        val boundaries = (properties.sliceYears.map { it.until } + properties.earlyUntil + bornUntil).sorted()
        val slices = mutableListOf<BirthSlice>()
        var year = properties.bornFrom
        while (year < bornUntil) {
            val years = properties.sliceYears.sortedBy { it.until }.firstOrNull { year < it.until }?.years ?: 1
            val next = minOf(year + years, boundaries.first { it > year })
            slices += BirthSlice(yearStart(year), yearStart(next))
            year = next
        }
        return slices
    }

    /** Humans born in [slice] above the cut-off, with their sitelinks, leaving out people who may be alive. */
    fun query(slice: BirthSlice): String {
        val sitelinks = if (slice.until <= earlyUntil) properties.earlySitelinks else properties.sitelinks
        // No date of death and born less than livingYears ago: alive, as far as Wikidata knows. A death recorded as
        // "unknown value" still counts as a date of death.
        val dead = if (slice.until <= livingFrom) "" else
            """FILTER(?born < ${literal(livingFrom)} || EXISTS { ?person wdt:P570 [] })"""
        // From the dates of birth in range (rangeSafe lets the service use its index of dates), then humans only.
        return """
            SELECT ?person ?sitelinks WHERE {
              ?person wdt:P569 ?born .
              hint:Prior hint:rangeSafe true .
              FILTER(${literal(slice.from)} <= ?born && ?born < ${literal(slice.until)})
              ?person wdt:P31 wd:Q5 ;
                      wikibase:sitelinks ?sitelinks .
              FILTER(?sitelinks >= $sitelinks)
              $dead
            }
        """.trimIndent()
    }

    private fun yearStart(year: Int) = LocalDate.of(year, 1, 1)

    // LocalDate writes years as ISO 8601 and XSD do: at least four digits, astronomical, "-1999-01-01".
    private fun literal(date: LocalDate) = "\"${date}T00:00:00Z\"^^xsd:dateTime"
}

/**
 * Step 1 of an import: asks the query service, one slice of birth dates after another, for the people above the
 * cut-off, and keeps their Q-ids and sitelinks in `raw.discovered`. A slice the service stops for taking too long is
 * asked for again in two halves. Each slice is stored as it is answered, so a resumed run skips those done.
 */
@Component
class Discovery(
    private val wikimedia: WikimediaClient,
    private val store: RawStore,
    private val properties: WikidataProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun discover(run: ImportRun) {
        val plan = DiscoveryPlan(properties, run.started)
        val done = store.doneSlices(run)
        plan.slices().forEach { discover(run, plan, it, done) }
        log.info("Discovered {} people", store.discoveredCount(run))
    }

    private fun discover(run: ImportRun, plan: DiscoveryPlan, slice: BirthSlice, done: List<BirthSlice>) {
        val doneWithin = done.filter { it in slice }
        if (doneWithin.sumOf { it.days } == slice.days) return
        // Halved in an earlier attempt of this run: ask for the halves still missing.
        if (doneWithin.isNotEmpty()) return slice.halves().forEach { discover(run, plan, it, done) }

        val answer = try {
            wikimedia.sparql(plan.query(slice))
        } catch (e: SparqlTimeoutException) {
            store.countRequest(run)
            log.info("Halving the slice {}: {}", slice, e.message)
            return slice.halves().forEach { discover(run, plan, it, done) }
        }
        val people = answer.path("results").path("bindings").associate { row ->
            row.path("person").path("value").asString().substringAfterLast('/') to row.path("sitelinks").path("value").asString().toInt()
        }
        store.saveSlice(run, slice, people)
        log.debug("{}: {} people", slice, people.size)
    }
}
