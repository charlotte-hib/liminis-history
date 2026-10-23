package art.liminis.jobs

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import art.liminis.domain.Language
import art.liminis.domain.Person
import art.liminis.repository.PersonRepository
import art.liminis.service.ArticleSummary
import art.liminis.service.WikipediaClient
import art.liminis.support.cleanExtract
import art.liminis.support.leadSentences
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Fills missing portraits, bios and article links from each person's English and French Wikipedia articles, for the
 * people written by hand whom the release's fetched dataset does not cover (all of them, in a release without one).
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
        Thread.ofVirtual().name("wikipedia-enrichment").start {
            enrich(people.findByWikipediaTitleIsNotNullAndWikipediaUrlIsNull())
            enrich(people.findByWikipediaTitleFrIsNotNullAndWikipediaUrlFrIsNull(), Language.FR)
        }
    }

    /**
     * Fills in whichever of [candidates] still miss something from their article in [language], and waits until done.
     * Returns how many were updated.
     */
    fun enrich(candidates: List<Person>, language: Language = Language.EN): Int {
        val missing = candidates.filter { title(it, language) != null && missing(it, language) }

        // Each lookup is I/O bound, so each gets its own virtual thread. The semaphore keeps us polite to Wikipedia.
        val permits = Semaphore(MAX_CONCURRENT_REQUESTS)
        val enriched = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            missing
                .map { person ->
                    executor.submit<Boolean> {
                        permits.acquire()
                        try { enrichOne(person, language) } finally { permits.release() }
                    }
                }
                .count { it.get() }
        }
        if (missing.isNotEmpty()) log.info("Enriched {} of {} people from {} Wikipedia", enriched, missing.size, NAMES.getValue(language))
        return enriched
    }

    private fun title(person: Person, language: Language) = if (language == Language.EN) person.wikipediaTitle else person.wikipediaTitleFr

    private fun missing(person: Person, language: Language) = when (language) {
        Language.EN -> person.portraitUrl == null || person.bioShort == null || person.about == null
        Language.FR -> person.bioShortFr == null || person.aboutFr == null
    }

    private fun enrichOne(person: Person, language: Language): Boolean {
        val summary = wikipedia.summary(title(person, language)!!, language.name.lowercase()) ?: return false
        when (language) {
            Language.EN -> fill(person, summary)
            Language.FR -> fillFrench(person, summary)
        }
        people.save(person)
        return true
    }

    private fun fill(person: Person, summary: ArticleSummary) {
        person.portraitUrl = person.portraitUrl ?: summary.thumbnailUrl
        person.bioShort = person.bioShort ?: summary.extract?.let(::leadSentences)
        person.about = person.about ?: summary.extract?.let(::cleanExtract)
        person.wikipediaUrl = person.wikipediaUrl ?: summary.pageUrl
    }

    private fun fillFrench(person: Person, summary: ArticleSummary) {
        person.bioShortFr = person.bioShortFr ?: summary.extract?.let(::leadSentences)
        person.aboutFr = person.aboutFr ?: summary.extract?.let(::cleanExtract)
        person.wikipediaUrlFr = person.wikipediaUrlFr ?: summary.pageUrl
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
        val NAMES = mapOf(Language.EN to "English", Language.FR to "French")
    }
}
