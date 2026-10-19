package art.liminis.auth

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.time.Duration

/**
 * A request limit per visitor, in memory: [PER_MINUTE] requests a minute. The visitor is the address
 * Caddy forwards on the server (Tomcat reads X-Forwarded-For from a proxy on a private address). Requests from loopback
 * and private addresses (the curator's computer, curate and the workbench on the Compose network) are never limited.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimit : OncePerRequestFilter() {

    private val buckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build<String, Bucket>()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val address = InetAddress.ofLiteral(request.remoteAddr)
        val local = address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        if (local || buckets.get(request.remoteAddr) { newBucket() }.tryConsume(1)) return chain.doFilter(request, response)
        // Answered here: sendError would forward to /error, which the sign-in chain sends to /login, itself limited.
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, "60")
    }

    private fun newBucket() = Bucket.builder().addLimit { it.capacity(PER_MINUTE).refillIntervally(PER_MINUTE, Duration.ofMinutes(1)) }.build()

    companion object {
        /** A sign-in loads a few pages and their scripts; a bot guessing links loads many more. */
        const val PER_MINUTE = 60L
    }
}
