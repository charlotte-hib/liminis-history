package art.liminis.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** A visitor from outside gets so many requests a minute, then waits; the server's own callers never do. */
@AuthTest
class RateLimitTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `refuses a visitor past the limit`() {
        repeat(RateLimit.PER_MINUTE.toInt()) { visit("203.0.113.7").andExpect { status { isOk() } } }
        visit("203.0.113.7").andExpect { status { isTooManyRequests() }; header { string("Retry-After", "60") } }
        visit("203.0.113.8").andExpect { status { isOk() } }
    }

    @Test
    fun `never limits loopback or the Compose network`() {
        for (address in listOf("127.0.0.1", "172.18.0.5")) {
            repeat(RateLimit.PER_MINUTE.toInt() + 1) { visit(address).andExpect { status { isOk() } } }
        }
    }

    private fun visit(address: String) = mockMvc.get("/auth.css") { with { it.remoteAddr = address; it } }
}
