package art.liminis.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import art.liminis.metrics.Rejection
import art.liminis.metrics.VisitorEvent
import art.liminis.metrics.VisitorEvents

/**
 * Anonymous usage events from the frontend (`navigator.sendBeacon`), counted by [VisitorEvents] and nothing else:
 * no IP address, user agent, cookie or id is read or kept. The body is read by hand, up to [MAX_BYTES], so a large
 * one is refused without being buffered, and so a beacon sent as text/plain is accepted too.
 */
@RestController
@RequestMapping("/api/events")
class EventController(private val events: VisitorEvents, private val jsonMapper: JsonMapper) {

    @PostMapping
    fun record(request: HttpServletRequest): ResponseEntity<Void> {
        if (request.contentLengthLong > MAX_BYTES) return refuse(Rejection.TOO_LARGE)
        val body = request.inputStream.readNBytes(MAX_BYTES + 1)
        if (body.size > MAX_BYTES) return refuse(Rejection.TOO_LARGE)

        val event = try {
            jsonMapper.readValue(body, VisitorEvent::class.java)
        } catch (_: JacksonException) {
            return refuse(Rejection.MALFORMED)
        } ?: return refuse(Rejection.MALFORMED)

        // record() counts its own rejections.
        return if (events.record(event) == null) ResponseEntity.noContent().build() else ResponseEntity.badRequest().build()
    }

    private fun refuse(reason: Rejection): ResponseEntity<Void> {
        events.reject(reason)
        val status = if (reason == Rejection.TOO_LARGE) HttpStatus.CONTENT_TOO_LARGE else HttpStatus.BAD_REQUEST
        return ResponseEntity.status(status).build()
    }

    companion object {
        /** A real event is well under 200 bytes. */
        const val MAX_BYTES = 1024
    }
}
