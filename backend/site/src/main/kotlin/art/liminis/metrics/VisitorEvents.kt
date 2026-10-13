package art.liminis.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import art.liminis.service.MomentService

/**
 * One anonymous event from the browser, as posted to `/api/events`. Which fields an event uses depends on its
 * [name]; the rest are ignored. Nothing in it identifies a visitor.
 */
data class VisitorEvent(
    val name: String? = null,
    /** page_view: which page, by template (`home`, `moment`, `story`, `person`), never a URL. */
    val page: String? = null,
    /** A moment id, for page views of a moment or its story, and for story events. */
    val moment: String? = null,
    /** story_card_reached: the 1-based card. */
    val card: Int? = null,
    /** person_panel_opened, person_full_page_opened: where the visitor came from. */
    val source: String? = null,
    /** search_result_opened: what was picked (`person`, `moment`). */
    val kind: String? = null,
)

/** A counter to increment: one of the metrics below, with tags from fixed vocabularies only. */
data class Count(val metric: String, val tags: Map<String, String>)

/** Why an event was not counted, itself counted so that junk and abuse show up on the dashboard. */
enum class Rejection(val tag: String) {
    TOO_LARGE("too_large"),
    MALFORMED("malformed"),
    UNKNOWN_EVENT("unknown_event"),
    INVALID_LABEL("invalid_label"),
}

/**
 * Turns visitor events into Prometheus counters. Every tag value comes from a closed set (page templates,
 * published moments, card positions within their story, a few sources), so the number of series stays small
 * and a crafted request cannot create new ones: anything else is rejected.
 */
@Component
class VisitorEvents(private val registry: MeterRegistry, private val momentService: MomentService) {

    /** Published moments and the length of their story (0 without one). Seeded at startup, then fixed. */
    @Volatile
    private var storyCards: Map<String, Int> = emptyMap()

    /**
     * Registers every counter at zero once the release is loaded. Prometheus then sees each one before its first
     * event, so `increase()` counts that event too, and the dashboards show zeros rather than "no data".
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerAll() {
        val moments = moments()
        everyEvent(moments).forEach { counter(countFor(it, moments)!!) }
        Rejection.entries.forEach { registry.counter(REJECTED, "reason", it.tag) }
    }

    /** Counts the event, or returns why it was not counted. */
    fun record(event: VisitorEvent): Rejection? {
        if (event.name !in NAMES) return reject(Rejection.UNKNOWN_EVENT)
        val count = countFor(event, moments()) ?: return reject(Rejection.INVALID_LABEL)
        counter(count).increment()
        return null
    }

    fun reject(reason: Rejection): Rejection {
        registry.counter(REJECTED, "reason", reason.tag).increment()
        return reason
    }

    private fun counter(count: Count) = registry.counter(count.metric, Tags.of(count.tags.map { (key, value) -> Tag.of(key, value) }))

    private fun moments(): Map<String, Int> =
        storyCards.ifEmpty { momentService.list().associate { it.id to it.storyCards }.also { storyCards = it } }

    companion object {
        const val PAGE_VIEWS = "visitor.page.views"
        const val STORIES_STARTED = "visitor.stories.started"
        const val STORY_CARDS_REACHED = "visitor.story.cards.reached"
        const val STORIES_COMPLETED = "visitor.stories.completed"
        const val PERSON_OPENS = "visitor.person.opens"
        const val SEARCHES = "visitor.searches"
        const val SEARCH_PICKS = "visitor.search.picks"
        const val REJECTED = "visitor.events.rejected"

        val NAMES = setOf(
            "page_view", "story_started", "story_card_reached", "story_completed",
            "person_panel_opened", "person_full_page_opened", "search_used", "search_result_opened",
        )

        /** Where a person was opened from: the page underneath, the search box, another person in the panel, or outside the site. */
        val SOURCES = setOf("home", "moment", "story", "person", "search", "panel", "direct", "external")

        /** What a search result can be. */
        val KINDS = setOf("person", "moment")

        /** Every event that counts, once each, given the published moments and their story lengths. */
        fun everyEvent(storyCards: Map<String, Int>): List<VisitorEvent> = buildList {
            add(VisitorEvent("page_view", page = "home"))
            add(VisitorEvent("page_view", page = "person"))
            storyCards.forEach { (id, length) ->
                add(VisitorEvent("page_view", page = "moment", moment = id))
                if (length == 0) return@forEach
                add(VisitorEvent("page_view", page = "story", moment = id))
                add(VisitorEvent("story_started", moment = id))
                (1..length).forEach { add(VisitorEvent("story_card_reached", moment = id, card = it)) }
                add(VisitorEvent("story_completed", moment = id))
            }
            SOURCES.forEach {
                add(VisitorEvent("person_panel_opened", source = it))
                add(VisitorEvent("person_full_page_opened", source = it))
            }
            add(VisitorEvent("search_used"))
            KINDS.forEach { add(VisitorEvent("search_result_opened", kind = it)) }
        }

        /**
         * The counter for an event whose name is known, given the published moments and their story lengths;
         * null when a field is missing or outside its vocabulary.
         */
        fun countFor(event: VisitorEvent, storyCards: Map<String, Int>): Count? {
            val moment = event.moment?.takeIf { it in storyCards }
            val story = moment?.takeIf { storyCards.getValue(it) > 0 }
            return when (event.name) {
                "page_view" -> when (event.page) {
                    "home", "person" -> if (event.moment == null) Count(PAGE_VIEWS, mapOf("page" to event.page, "moment" to "none")) else null
                    "moment" -> moment?.let { Count(PAGE_VIEWS, mapOf("page" to "moment", "moment" to it)) }
                    "story" -> story?.let { Count(PAGE_VIEWS, mapOf("page" to "story", "moment" to it)) }
                    else -> null
                }
                "story_started" -> story?.let { Count(STORIES_STARTED, mapOf("moment" to it)) }
                "story_card_reached" -> story?.let { id ->
                    // Zero-padded, so cards sort in story order on the dashboard.
                    event.card?.takeIf { it in 1..storyCards.getValue(id) }
                        ?.let { Count(STORY_CARDS_REACHED, mapOf("moment" to id, "card" to "%02d".format(it))) }
                }
                "story_completed" -> story?.let { Count(STORIES_COMPLETED, mapOf("moment" to it)) }
                "person_panel_opened" -> event.source?.takeIf { it in SOURCES }
                    ?.let { Count(PERSON_OPENS, mapOf("view" to "panel", "source" to it)) }
                "person_full_page_opened" -> event.source?.takeIf { it in SOURCES }
                    ?.let { Count(PERSON_OPENS, mapOf("view" to "page", "source" to it)) }
                "search_used" -> Count(SEARCHES, emptyMap())
                "search_result_opened" -> event.kind?.takeIf { it in KINDS }
                    ?.let { Count(SEARCH_PICKS, mapOf("kind" to it)) }
                else -> null
            }
        }
    }
}
