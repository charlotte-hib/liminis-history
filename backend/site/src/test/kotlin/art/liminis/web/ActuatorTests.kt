package art.liminis.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import art.liminis.testing.PostgresTestConfiguration

/** Actuator on the main port, as in development; the prod profile moves it to a port of its own. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ActuatorTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `Prometheus gets HTTP latency buckets per route and JVM metrics`() {
        mockMvc.get("/api/moments").andExpect { status { isOk() } }

        mockMvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("""http_server_requests_seconds_bucket{error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/moments",le="0.25"}""")) }
            content { string(containsString("jvm_memory_used_bytes")) }
        }
    }

    @Test
    fun `only health and Prometheus are exposed`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/actuator/env").andExpect { status { isNotFound() } }
        mockMvc.get("/actuator/heapdump").andExpect { status { isNotFound() } }
    }
}
