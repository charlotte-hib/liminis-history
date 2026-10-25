package art.liminis.translating

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import art.liminis.curation.Anecdote
import art.liminis.curation.Approved
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.Connection
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.publishing.ConnectionLines
import art.liminis.runs.Runs
import art.liminis.support.ConflictException
import art.liminis.workbench.api.model.RunKindDto

/**
 * The French follows the English: once a curator approves a new header, story, anecdote or connection of a record that has a
 * translation, Claude updates its French (only the texts whose English changed, from the French approved before), and
 * it waits for review under the record's Français tab. Meanwhile the site keeps the French written before. While another run runs, nothing starts: the tab says the English
 * changed, and "Translate again" does it. English changed by hand in `data/` waits for "Translate everything missing".
 * Undoing the approval does not undo the update: its French then waits beside English publishing no longer writes,
 * held back until it is translated again.
 */
@Component
class FrenchFollows(private val claims: ClaimRepository, private val runs: Runs) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(fallbackExecution = true)
    fun approved(event: Approved) {
        val record = recordOf(event) ?: return
        val translated = try {
            claims.existsById(ClaimRules.translationId(record))
        } catch (e: Exception) {
            log.warn("Could not tell whether {} has French", record, e)
            false
        }
        if (!translated) return
        // The approval is made: nothing here may fail it.
        try {
            runs.start(RunKindDto.TRANSLATIONS, null, record)
        } catch (e: ConflictException) {
            log.info("The French of {} waits: {}", record, e.message)
        } catch (e: Exception) {
            log.warn("Could not start updating the French of {}", record, e)
        }
    }

    private fun recordOf(event: Approved): String? = when (val payload = event.payload) {
        is Story -> "${RecordKind.MOMENT.key}:${payload.moment}"
        is MomentPage -> "${RecordKind.MOMENT.key}:${payload.moment}"
        // An anecdote's line in data/ is named after its claim.
        is Anecdote -> "${RecordKind.ANECDOTE.key}:${event.claim}"
        // A connection's, after its people and its kind.
        is Connection -> "${RecordKind.CONNECTION.key}:${ConnectionLines.key(payload.people, payload.kind)}"
        else -> null
    }
}
