package art.liminis.service

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Duration

@ConfigurationProperties("app.wikipedia")
data class WikipediaProperties(
    val baseUrl: String,
    val userAgent: String,
    val enrich: Boolean = true,
)

/** The parts of a Wikipedia article summary the app uses. */
data class ArticleSummary(val extract: String?, val thumbnailUrl: String?, val pageUrl: String?)

/**
 * Thin client over the Wikipedia REST API. Lookups are best effort and never throw.
 * Timeouts come from `spring.http.clients` in application.yaml.
 */
@Component
class WikipediaClient(builder: RestClient.Builder, properties: WikipediaProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader("User-Agent", properties.userAgent)
        .build()

    fun summary(title: String): ArticleSummary? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return restClient.get()
                    .uri("/page/summary/{title}", title)
                    .retrieve()
                    .body<PageSummary>()
                    ?.let { ArticleSummary(it.extract, it.thumbnail?.source, it.contentUrls?.desktop?.page) }
            } catch (e: HttpClientErrorException.TooManyRequests) {
                // Wait as long as Wikipedia asks, or back off a little more each time.
                val seconds = e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull() ?: (attempt + 1L) * 2
                Thread.sleep(Duration.ofSeconds(seconds))
            } catch (e: Exception) {
                log.warn("No summary for '{}': {}", title, e.message)
                return null
            }
        }
        log.warn("No summary for '{}': still rate limited after {} attempts", title, MAX_ATTEMPTS)
        return null
    }

    private data class PageSummary(
        val extract: String?,
        val thumbnail: Image?,
        @JsonProperty("content_urls") val contentUrls: ContentUrls?,
    )
    private data class Image(val source: String)
    private data class ContentUrls(val desktop: PageLink?)
    private data class PageLink(val page: String?)

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
