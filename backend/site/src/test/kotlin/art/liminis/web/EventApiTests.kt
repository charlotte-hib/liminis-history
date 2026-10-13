package art.liminis.web

import io.micrometer.core.instrument.MeterRegistry
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import art.liminis.metrics.VisitorEvents
import art.liminis.testing.PostgresTestConfiguration

/** Runs against the release in the repository's `sample/`: paris-1870s has a 7-card story, edo-1830s has none. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class EventApiTests(@Autowired private val mockMvc: MockMvc, @Autowired private val registry: MeterRegistry) {

    private fun post(body: String, type: MediaType = MediaType.APPLICATION_JSON) =
        mockMvc.post("/api/events") {
            contentType = type
            content = body
        }

    private fun count(metric: String, vararg tags: String) =
        registry.find(metric).tags(*tags).counter()?.count() ?: 0.0

    @Test
    fun `a known event is counted and shows up for Prometheus`() {
        val before = count(VisitorEvents.STORIES_STARTED, "moment", "paris-1870s")

        post("""{"name":"story_started","moment":"paris-1870s"}""").andExpect { status { isNoContent() } }

        assertEquals(before + 1, count(VisitorEvents.STORIES_STARTED, "moment", "paris-1870s"))
        mockMvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(containsString("""visitor_stories_started_total{moment="paris-1870s"}""")) }
        }
    }

    @Test
    fun `a beacon sent as plain text is accepted, and unknown fields are ignored`() {
        val before = count(VisitorEvents.PERSON_OPENS, "view", "panel", "source", "story")

        post("""{"name":"person_panel_opened","source":"story","url":"https://example.com/?id=42"}""", MediaType.TEXT_PLAIN)
            .andExpect { status { isNoContent() } }

        assertEquals(before + 1, count(VisitorEvents.PERSON_OPENS, "view", "panel", "source", "story"))
    }

    @Test
    fun `page views are counted by page template, and by moment where there is one`() {
        val home = count(VisitorEvents.PAGE_VIEWS, "page", "home", "moment", "none")
        val moment = count(VisitorEvents.PAGE_VIEWS, "page", "moment", "moment", "edo-1830s")
        val searches = count(VisitorEvents.SEARCHES)

        post("""{"name":"page_view","page":"home"}""").andExpect { status { isNoContent() } }
        post("""{"name":"page_view","page":"moment","moment":"edo-1830s"}""").andExpect { status { isNoContent() } }
        post("""{"name":"search_used"}""").andExpect { status { isNoContent() } }

        assertEquals(home + 1, count(VisitorEvents.PAGE_VIEWS, "page", "home", "moment", "none"))
        assertEquals(moment + 1, count(VisitorEvents.PAGE_VIEWS, "page", "moment", "moment", "edo-1830s"))
        assertEquals(searches + 1, count(VisitorEvents.SEARCHES))
    }

    @Test
    fun `story cards are counted by their position within the story`() {
        val before = count(VisitorEvents.STORY_CARDS_REACHED, "moment", "paris-1870s", "card", "07")
        post("""{"name":"story_card_reached","moment":"paris-1870s","card":7}""").andExpect { status { isNoContent() } }
        assertEquals(before + 1, count(VisitorEvents.STORY_CARDS_REACHED, "moment", "paris-1870s", "card", "07"))

        post("""{"name":"story_card_reached","moment":"paris-1870s","card":8}""").andExpect { status { isBadRequest() } }
        post("""{"name":"story_card_reached","moment":"paris-1870s","card":0}""").andExpect { status { isBadRequest() } }
        post("""{"name":"story_card_reached","moment":"paris-1870s"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `events outside the vocabularies are refused and counted as rejected`() {
        val before = count(VisitorEvents.REJECTED, "reason", "invalid_label")

        post("""{"name":"story_started","moment":"atlantis"}""").andExpect { status { isBadRequest() } }
        post("""{"name":"story_started","moment":"edo-1830s"}""").andExpect { status { isBadRequest() } }
        post("""{"name":"page_view","page":"/person/emile-zola?year=1875"}""").andExpect { status { isBadRequest() } }
        post("""{"name":"page_view","page":"home","moment":"paris-1870s"}""").andExpect { status { isBadRequest() } }
        post("""{"name":"person_full_page_opened","source":"https://example.com"}""").andExpect { status { isBadRequest() } }
        post("""{"name":"search_result_opened","kind":"zola"}""").andExpect { status { isBadRequest() } }

        assertEquals(before + 6, count(VisitorEvents.REJECTED, "reason", "invalid_label"))
        mockMvc.get("/actuator/prometheus").andExpect {
            content { string(org.hamcrest.Matchers.not(containsString("atlantis"))) }
            content { string(org.hamcrest.Matchers.not(containsString("example.com"))) }
        }
    }

    @Test
    fun `unknown events, malformed and oversized bodies are refused`() {
        val unknown = count(VisitorEvents.REJECTED, "reason", "unknown_event")
        val malformed = count(VisitorEvents.REJECTED, "reason", "malformed")
        val tooLarge = count(VisitorEvents.REJECTED, "reason", "too_large")

        post("""{"name":"login","moment":"paris-1870s"}""").andExpect { status { isBadRequest() } }
        post("""{}""").andExpect { status { isBadRequest() } }
        post("""not json""").andExpect { status { isBadRequest() } }
        post("""null""").andExpect { status { isBadRequest() } }
        post("""{"name":"search_used","padding":"${"x".repeat(2000)}"}""").andExpect { status { isEqualTo(413) } }

        assertEquals(unknown + 2, count(VisitorEvents.REJECTED, "reason", "unknown_event"))
        assertEquals(malformed + 2, count(VisitorEvents.REJECTED, "reason", "malformed"))
        assertEquals(tooLarge + 1, count(VisitorEvents.REJECTED, "reason", "too_large"))
    }

    @Test
    fun `every counter exists from startup, so Prometheus sees each first event`() {
        mockMvc.get("/actuator/prometheus").andExpect {
            content { string(containsString("""visitor_story_cards_reached_total{card="01",moment="tokyo-1870s"} 0.0""")) }
            content { string(containsString("""visitor_page_views_total{moment="edo-1830s",page="moment"}""")) }
            content { string(org.hamcrest.Matchers.not(containsString("""moment="edo-1830s",page="story""""))) }
            content { string(containsString("""visitor_person_opens_total{source="external",view="page"}""")) }
            content { string(containsString("""visitor_events_rejected_total{reason="too_large"}""")) }
        }
    }
}
