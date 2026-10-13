package art.liminis.wikidata

import io.github.resilience4j.retry.RetryRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import art.liminis.wikimedia.WikimediaBusyException
import art.liminis.wikimedia.WikimediaClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

/**
 * Fetches Wikidata's people into the workbench's `raw` schema: discovery (who is above the cut-off), then each
 * person's entity, then the places and occupations their claims point to, then the classes above those occupations,
 * through which they map to the site's domains, then the intros of their Wikipedia articles. Entities and pages already
 * stored are only fetched again when their revision has changed, so running it again a month later refreshes the cache
 * cheaply.
 *
 * Every request goes through [WikimediaClient]'s shared pace. Progress is in the database: a run that stopped resumes
 * at the next slice or batch, and first waits out any `Retry-After` Wikimedia gave before it stopped.
 *
 * Wikidata can stay too far behind for minutes on end (`maxlag`), and the query service overloaded, longer than the
 * client's retries last. The import then pauses and resumes by itself, up to [WikidataProperties.restarts] times in a
 * row without progress.
 */
@Component
class WikidataImport(
    private val wikimedia: WikimediaClient,
    private val discovery: Discovery,
    private val store: RawStore,
    private val properties: WikidataProperties,
    retries: RetryRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The Wikipedia editions of the sitelinks kept: `en` for `enwiki`. */
    private val languages = properties.sites.map { it.removeSuffix("wiki") }

    @Volatile
    private var current: ImportRun? = null

    init {
        // Each retry is another request, after Wikimedia asked to wait or could not answer.
        retries.retry(WikimediaClient.PACE).eventPublisher.onRetry { event ->
            log.info("Wikimedia: {}. Trying again in {}", event.lastThrowable?.message, event.waitInterval)
            current?.let { store.busy(it, event.waitInterval) }
        }
    }

    fun run(): ImportRun {
        var restarts = 0
        var progress = progress()
        while (true) {
            try {
                return resume()
            } catch (e: WikimediaBusyException) {
                val now = progress()
                if (now != progress) restarts = 0
                progress = now
                if (++restarts > properties.restarts) throw e
                log.warn("Wikimedia still asks to wait after every retry ({}): resuming in {}, {} of {}",
                    e.message, properties.restartPause, restarts, properties.restarts)
                Thread.sleep(properties.restartPause)
            }
        }
    }

    /** Entities, pages and slices done so far, by any run: the import moved on when this grows. */
    private fun progress(): Long {
        val run = store.currentRun()
        val counts = store.counts(run)
        return DONE.sumOf { (counts[it] as Number).toLong() } + store.doneSlices(run).size
    }

    private fun resume(): ImportRun {
        var run = store.currentRun()
        if (run.phase != ImportPhase.DISCOVER || store.doneSlices(run).isNotEmpty()) log.info("Resuming import {} at {}", run.id, run.phase)
        waitUntil(run.notBefore)
        current = run
        try {
            // Each step does its phase's work, then moves the run to the next phase: a resumed run starts at its own.
            for ((phase, step) in steps) {
                if (run.phase != phase) continue
                step(run)
                run = store.findRun(run.id)
            }
        } finally {
            current = null
        }
        log.info("Import {} done: {}", run.id, store.counts(run))
        return run
    }

    /** The phases in order, each with its work and what it queues for the next one. */
    private val steps: List<Pair<ImportPhase, (ImportRun) -> Unit>> = listOf(
        ImportPhase.DISCOVER to { run ->
            discovery.discover(run)
            store.queuePeople(run)
        },
        ImportPhase.PEOPLE to { run ->
            fetchAll(run, EntityKind.PEOPLE)
            store.queueLinked(run, properties.linkedProperties)
        },
        ImportPhase.LINKED to { run ->
            fetchAll(run, EntityKind.LINKED)
            store.startClasses(run)
        },
        ImportPhase.CLASSES to { run ->
            fetchClasses(run)
            store.queuePages(run, properties.sites, languages)
        },
        ImportPhase.PAGES to { run ->
            languages.forEach { fetchPages(run, it) }
            store.finish(run)
        },
    )

    private fun fetchAll(run: ImportRun, kind: EntityKind) {
        var done = 0
        while (true) {
            val ids = store.nextBatch(run, kind, WikimediaClient.MAX_ENTITIES).ifEmpty { break }
            store.saveBatch(run, fetch(kind, ids))
            done += ids.size
            if (done % PROGRESS_EVERY < ids.size) log.info("{}: {} entities checked or fetched", kind, done)
        }
    }

    /** The classes above the occupations, one level after another, until the last level or no class is new. */
    private fun fetchClasses(run: ImportRun) {
        while (true) {
            fetchAll(run, EntityKind.CLASSES)
            val level = store.classLevel(run) + 1
            if (level > properties.classLevels || store.queueClasses(run, level) == 0) return
        }
    }

    /**
     * One batch: asks for the latest revision of the entities already stored, then fetches in full those that are
     * new or changed. Two requests at most, of 50 entities each.
     */
    private fun fetch(kind: EntityKind, ids: List<String>): EntityBatch {
        val stored = store.revisions(ids)
        var requests = 0
        val (unchanged, missing) = compare(stored) { qids ->
            requests++
            val latest = wikimedia.entities(qids, listOf("info")).path("entities")
            qids.filterNot { latest.path(it).has("missing") }.associateWith { latest.path(it).path("lastrevid").asLong() }
        }

        val wanted = ids - unchanged.toSet() - missing.toSet()
        if (wanted.isEmpty()) return EntityBatch(ids, requests, emptyList(), unchanged, missing)
        val entities = when (kind) {
            EntityKind.PEOPLE -> wikimedia.entities(wanted, PEOPLE_PROPS, properties.languages, properties.sites)
            EntityKind.LINKED, EntityKind.CLASSES -> wikimedia.entities(wanted, LINKED_PROPS, properties.languages)
        }.path("entities")
        requests++
        val (gone, found) = wanted.partition { entities.path(it).isMissingNode || entities.path(it).has("missing") }
        return EntityBatch(ids, requests, found.map { entities.path(it) }, unchanged, missing + gone)
    }

    private fun fetchPages(run: ImportRun, language: String) {
        var done = 0
        while (true) {
            val titles = store.nextPages(run, language, WikimediaClient.MAX_TITLES).ifEmpty { break }
            store.savePages(run, fetchPages(language, titles))
            done += titles.size
            if (done % PROGRESS_EVERY < titles.size) log.info("{} Wikipedia: {} pages checked or fetched", language, done)
        }
    }

    /**
     * One batch of pages, as [fetch] for entities: asks for the latest revision of those already stored, then fetches
     * those that are new or changed, [WikimediaClient.MAX_PAGES] a request.
     */
    private fun fetchPages(language: String, titles: List<String>): PageBatch {
        val stored = store.pageRevisions(language, titles)
        var requests = 0
        val (unchanged, gone) = compare(stored) {
            requests++
            wikimedia.pageRevisions(language, it)
        }
        val missing = gone.toMutableList()

        val fetched = mutableMapOf<String, JsonNode>()
        (titles - unchanged.toSet() - missing.toSet()).chunked(WikimediaClient.MAX_PAGES).forEach { chunk ->
            val pages = wikimedia.pages(language, chunk)
            requests++
            chunk.forEach { title -> pages[title]?.let { fetched[title] = it } ?: missing.add(title) }
        }
        return PageBatch(language, titles, requests, fetched, unchanged, missing)
    }

    /**
     * Which of the [stored] revisions are still the latest, and which items are gone, from the [latest] revisions of
     * the stored items (none for an item gone). Asks nothing when nothing is stored.
     */
    private fun compare(stored: Map<String, Long>, latest: (List<String>) -> Map<String, Long>): Pair<List<String>, List<String>> {
        if (stored.isEmpty()) return emptyList<String>() to emptyList()
        val revisions = latest(stored.keys.toList())
        return stored.filter { (id, revision) -> revisions[id] == revision }.keys.toList() to stored.keys.filter { it !in revisions }
    }

    private fun waitUntil(notBefore: Instant?) {
        val wait = notBefore?.let { Duration.between(Instant.now(), it) } ?: return
        if (wait.isNegative) return
        log.info("Wikimedia asked to wait until {}: waiting {}", notBefore, wait)
        Thread.sleep(wait)
    }

    private companion object {
        /** A person's labels and aliases, statements and Wikipedia articles, and the revision. */
        val PEOPLE_PROPS = listOf("info", "labels", "aliases", "claims", "sitelinks")
        /**
         * A place's, an occupation's or a class's labels and statements (coordinates, country, the classes it belongs
         * to, the feminine form of an occupation).
         */
        val LINKED_PROPS = listOf("info", "labels", "claims")
        /** The counts of [RawStore.counts] that grow with every entity or page done. */
        val DONE = listOf("fetched", "unchanged", "missing", "pages_fetched", "pages_unchanged", "pages_missing")
        const val PROGRESS_EVERY = 1000
    }
}
