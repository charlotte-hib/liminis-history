package dev.wholivedwhen.wikimedia

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.toEntity
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.time.Duration

@ConfigurationProperties("app.wikimedia")
data class WikimediaProperties(
    /** Wikidata's Action API. */
    val wikidataApi: String,
    /** Wikipedia's Action API, with `{language}` for the edition, e.g. `en`. */
    val wikipediaApi: String,
    /** Wikidata's query service, for SPARQL. */
    val wikidataSparql: String,
    /** How long a SPARQL query may take: the query service stops each one after 60 seconds. */
    val sparqlTimeout: Duration,
    /** How long an Action API answer may take to arrive in full: 50 people's entities run to megabytes. */
    val readTimeout: Duration,
    /** Wikimedia asks API clients for a contact in the User-Agent. */
    val userAgent: String,
    /** Seconds of replication lag past which the servers answer with the `maxlag` error rather than work. */
    val maxlag: Int,
    /**
     * The same for Wikidata, whose lag also counts its query service catching up with edits: it slows down bots that
     * edit, and reading does not add to it.
     */
    val wikidataMaxlag: Int,
    /** How long to wait when Wikimedia asks to retry later without saying how long, doubled at each attempt. */
    val defaultRetryWait: Duration,
    /** The longest wait the client accepts. Asked to wait longer, it gives up rather than retry any sooner. */
    val maxRetryWait: Duration,
)

/**
 * Wikimedia asked to retry later (HTTP 429, or the Action API's `maxlag` error), or could not answer for now (HTTP
 * 502 or 503, a 504 from the Action APIs, or a connection lost before the whole answer came). Retried after
 * [retryAfter], when Wikimedia said how long.
 */
class WikimediaBusyException(message: String, val retryAfter: Duration?) : RuntimeException(message)

/** An error the Action API answered with, other than `maxlag`, or a wait longer than the client accepts. Not retried. */
class WikimediaException(message: String) : RuntimeException(message)

/** The query service stopped a SPARQL query that took too long. Not retried: ask for less instead. */
class SparqlTimeoutException(message: String) : RuntimeException(message)

/**
 * The workbench's way to Wikimedia: Wikidata's and Wikipedia's Action APIs, which answer many entities or pages per
 * request, and Wikidata's query service. Returns their JSON as it came.
 *
 * Every call shares one pace, the Resilience4j instances named `wikimedia` in application.yaml: one request at a time,
 * two a second at most. When Wikimedia asks to retry later (HTTP 429, or the `maxlag` error when its servers are
 * behind), the call waits as long as `Retry-After` says, holding back every other call meanwhile, then tries again. When
 * it cannot answer for now, as the query service often cannot when overloaded (HTTP 502, or a connection dropped), the
 * call waits longer at each attempt, then tries again.
 */
@Component
class WikimediaClient(
    builder: RestClient.Builder,
    clientSettings: HttpClientSettings,
    private val properties: WikimediaProperties,
    rateLimiters: RateLimiterRegistry,
    bulkheads: BulkheadRegistry,
    retries: RetryRegistry,
) {

    // The JDK's client, as everywhere in the workbench (application.yaml), asking for answers gzipped, as Wikidata asks
    // of its clients: 50 people's entities run to megabytes of JSON.
    private val requestFactories = ClientHttpRequestFactoryBuilder.jdk().withCustomizer { it.enableCompression(true) }

    private val restClient = builder.clone()
        .requestFactory(requestFactories.build(clientSettings.withReadTimeout(properties.readTimeout)))
        .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent)
        .defaultStatusHandler({ it == HttpStatus.TOO_MANY_REQUESTS }) { _, response -> throw busy("HTTP 429", response.headers) }
        .defaultStatusHandler({ it in UNAVAILABLE }) { _, response -> throw busy("HTTP ${response.statusCode.value()}", response.headers) }
        .build()

    // A query may run up to the query service's own limit, longer than any other call is given. When it runs over, the
    // service stops it with a 500 naming a TimeoutException, or its proxy gives up first with a 504. An overloaded
    // service answers 502 instead, to queries that take seconds a minute later.
    private val sparqlClient = builder.clone()
        .requestFactory(requestFactories.build(clientSettings.withReadTimeout(properties.sparqlTimeout)))
        .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent)
        .defaultStatusHandler({ it == HttpStatus.TOO_MANY_REQUESTS }) { _, response -> throw busy("HTTP 429", response.headers) }
        .defaultStatusHandler({ it.is5xxServerError }) { _, response ->
            val status = response.statusCode
            val body = response.body.readNBytes(MAX_ERROR_BYTES).decodeToString()
            when {
                status == HttpStatus.GATEWAY_TIMEOUT || "TimeoutException" in body ->
                    throw SparqlTimeoutException("The query service stopped the query: HTTP ${status.value()}")
                status in UNAVAILABLE -> throw busy("The query service is unavailable: HTTP ${status.value()}", response.headers)
                else -> throw WikimediaException("The query service failed: HTTP ${status.value()} ${body.take(200)}")
            }
        }
        .build()

    private val rateLimiter: RateLimiter = rateLimiters.rateLimiter(PACE)
    private val bulkhead: Bulkhead = bulkheads.bulkhead(PACE)
    private val retry: Retry = retries.retry(PACE)

    /**
     * Entities by Q-id, [MAX_ENTITIES] at most (`wbgetentities`), with labels and aliases in [languages] only and
     * sitelinks to [sites] only, or all of them when empty. Returns the whole answer, entities under `entities`.
     */
    fun entities(ids: List<String>, props: List<String>, languages: List<String> = emptyList(), sites: List<String> = emptyList()): JsonNode {
        require(ids.size in 1..MAX_ENTITIES) { "Between 1 and $MAX_ENTITIES ids a request, not ${ids.size}" }
        val filters = mapOf("languages" to languages, "sitefilter" to sites).filterValues { it.isNotEmpty() }
        return get(
            properties.wikidataApi,
            emptyMap(),
            properties.wikidataMaxlag,
            mapOf(
                "action" to "wbgetentities",
                "ids" to ids.joinToString("|"),
                "props" to props.joinToString("|"),
            ) + filters.mapValues { it.value.joinToString("|") },
        )
    }

    /**
     * The intro as plain text, the page image (its thumbnail and its file name on Commons), the URL and the latest
     * revision of Wikipedia pages, [MAX_PAGES] at most, in the [language] edition (`action=query`). Returns each page
     * as it came, by the title asked for, following redirects. Pages that do not exist are left out.
     */
    fun pages(language: String, titles: List<String>): Map<String, JsonNode> {
        require(titles.size in 1..MAX_PAGES) { "Between 1 and $MAX_PAGES titles a request, not ${titles.size}" }
        return query(
            language,
            titles,
            mapOf(
                "prop" to "extracts|pageimages|info",
                "exintro" to "1",
                "explaintext" to "1",
                "piprop" to "thumbnail|name",
                "pithumbsize" to "400",
                "inprop" to "url",
            ),
        )
    }

    /**
     * The latest revision of Wikipedia pages, [MAX_TITLES] at most, in the [language] edition, by the title asked for,
     * following redirects. Pages that do not exist are left out.
     */
    fun pageRevisions(language: String, titles: List<String>): Map<String, Long> {
        require(titles.size in 1..MAX_TITLES) { "Between 1 and $MAX_TITLES titles a request, not ${titles.size}" }
        return query(language, titles, mapOf("prop" to "info")).mapValues { it.value.path("lastrevid").asLong() }
    }

    private fun query(language: String, titles: List<String>, props: Map<String, String>): Map<String, JsonNode> {
        val answer = get(
            properties.wikipediaApi,
            mapOf("language" to language),
            properties.maxlag,
            mapOf("action" to "query") + props + mapOf("titles" to titles.joinToString("|"), "redirects" to "1", "formatversion" to "2"),
        )
        // More pages than a module answers for at once: never with these batch sizes, and the rest would be missing.
        if (answer.has("continue")) throw WikimediaException("Wikipedia ($language) answered only in part: ${answer.path("continue")}")
        val query = answer.path("query")
        // The title asked for, as Wikipedia writes it (underscores to spaces, a capital first), then where it redirects.
        val normalized = query.path("normalized").values().associate { it.path("from").asString() to it.path("to").asString() }
        val redirects = query.path("redirects").values().associate { it.path("from").asString() to it.path("to").asString() }
        val pages = query.path("pages").values().filterNot { it.has("missing") || it.has("invalid") }.associateBy { it.path("title").asString() }
        return titles.mapNotNull { title ->
            val normal = normalized[title] ?: title
            pages[redirects[normal] ?: normal]?.let { title to it }
        }.toMap()
    }

    /**
     * A SPARQL `SELECT` on Wikidata's query service. Returns the results as they came, rows under `results.bindings`.
     * Throws [SparqlTimeoutException] when the service stops the query for taking too long.
     */
    fun sparql(query: String): JsonNode = paced {
        val response = sparqlClient.get().uri(properties.wikidataSparql + "?query={query}", query)
            .accept(SPARQL_RESULTS).retrieve().toEntity<JsonNode>()
        checkNotNull(response.body) { "Empty answer from ${properties.wikidataSparql}" }
    }

    private fun get(api: String, apiVariables: Map<String, String>, maxlag: Int, parameters: Map<String, String>): JsonNode {
        val query = parameters + mapOf("maxlag" to maxlag.toString(), "format" to "json")
        // Every value goes in as a URI variable, so the client encodes it: the pipes between ids, accents in titles.
        val uri = api + query.keys.joinToString("&", prefix = "?") { "$it={$it}" }
        return paced {
            val response = restClient.get().uri(uri, apiVariables + query).retrieve().toEntity<JsonNode>()
            val body = checkNotNull(response.body) { "Empty answer from $api" }
            val error = body.path("error")
            when {
                error.isMissingNode -> body
                error.path("code").asString() == "maxlag" -> throw busy("maxlag: ${error.path("info").asString()}", response.headers)
                else -> throw WikimediaException("${error.path("code").asString()}: ${error.path("info").asString()}")
            }
        }
    }

    // The bulkhead outermost: a call that waits out Retry-After keeps every other call waiting too. Each attempt then
    // takes its own permit from the rate limiter.
    private fun <T> paced(call: () -> T): T =
        Bulkhead.decorateSupplier(bulkhead, Retry.decorateSupplier(retry, RateLimiter.decorateSupplier(rateLimiter) { busyWhenDisconnected(call) })).get()

    // A connection lost before the whole answer came, such as an HTTP/2 stream the server cancelled: try again later.
    private fun <T> busyWhenDisconnected(call: () -> T): T =
        try {
            call()
        } catch (e: RestClientException) {
            val lost = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException } ?: throw e
            throw WikimediaBusyException("Connection lost: $lost", null)
        }

    private fun busy(reason: String, headers: HttpHeaders): RuntimeException {
        val retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim()?.toLongOrNull()?.let(Duration::ofSeconds)
        if (retryAfter != null && retryAfter > properties.maxRetryWait) {
            return WikimediaException("$reason, asked to wait $retryAfter, longer than ${properties.maxRetryWait}")
        }
        return WikimediaBusyException(reason, retryAfter)
    }

    companion object {
        /** The name of the Resilience4j rate limiter, bulkhead and retry every call to Wikimedia shares. */
        const val PACE = "wikimedia"
        const val MAX_ENTITIES = 50
        /** Wikipedia's intros come 20 pages a request at most. */
        const val MAX_PAGES = 20
        const val MAX_TITLES = 50
        private const val MAX_ERROR_BYTES = 4096
        /** Bad gateway, unavailable, gateway timeout: Wikimedia cannot answer for now. */
        private val UNAVAILABLE = setOf(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)
        private val SPARQL_RESULTS = MediaType("application", "sparql-results+json")
    }
}
