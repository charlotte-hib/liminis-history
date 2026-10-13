package art.liminis.jobs

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import art.liminis.domain.Person
import art.liminis.repository.PersonRepository
import art.liminis.service.WikipediaClient
import art.liminis.support.cleanExtract
import art.liminis.support.leadSentences
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Fills missing portraits, bios and article links from each person's Wikipedia article, for the people written by hand
 * whom the release's fetched dataset does not cover (all of them, in a release without one).
 * Runs in the background so the API is up straight away; people are saved one by one as their article arrives.
 */
@Component
@Order(2)
@ConditionalOnBooleanProperty("app.wikipedia.enrich")
class WikipediaJob(
    private val people: PersonRepository,
    private val wikipedia: WikipediaClient,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        Thread.ofVirtual().name("wikipedia-enrichment").start { enrich(people.findByWikipediaTitleIsNotNullAndWikipediaUrlIsNull()) }
    }

    /** Fills in whichever of [candidates] still miss something, and waits until done. Returns how many were updated. */
    fun enrich(candidates: List<Person>): Int {
        val missing = candidates.filter { it.wikipediaTitle != null && (it.portraitUrl == null || it.bioShort == null || it.about == null) }

        // Each lookup is I/O bound, so each gets its own virtual thread. The semaphore keeps us polite to Wikipedia.
        val permits = Semaphore(MAX_CONCURRENT_REQUESTS)
        val enriched = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            missing
                .map { person ->
                    executor.submit<Boolean> {
                        permits.acquire()
                        try { enrichOne(person) } finally { permits.release() }
                    }
                }
                .count { it.get() }
        }
        if (missing.isNotEmpty()) log.info("Enriched {} of {} people from Wikipedia", enriched, missing.size)
        return enriched
    }

    private fun enrichOne(person: Person): Boolean {
        val summary = wikipedia.summary(person.wikipediaTitle!!) ?: return false
        person.portraitUrl = person.portraitUrl ?: summary.thumbnailUrl
        person.bioShort = person.bioShort ?: summary.extract?.let(::leadSentences)
        person.about = person.about ?: summary.extract?.let(::cleanExtract)
        person.wikipediaUrl = person.wikipediaUrl ?: summary.pageUrl
        people.save(person)
        return true
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
    }
}
