package art.liminis.translating

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import art.liminis.curation.Anecdote
import art.liminis.curation.Approved
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.runs.Runs
import art.liminis.support.ConflictException
import art.liminis.workbench.api.model.RunKindDto

/**
 * The French follows the English: once a curator approves a new header, story or anecdote of a record that has a
 * translation, Claude updates its French (only the texts whose English changed, from the French approved before), and
 * it waits for review under the record's Français tab. Meanwhile the site keeps the French written before. While another run runs, nothing starts: the tab says the English
 * changed, and "Translate again" does it. English changed by hand in `data/` waits for "Translate everything missing".
 */
@Component
class FrenchFollows(private val claims: ClaimRepository, private val runs: Runs) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(fallbackExecution = true)
    fun approved(event: Approved) {
        val record = recordOf(event) ?: return
        if (!claims.existsById(ClaimRules.translationId(record))) return
        try {
            runs.start(RunKindDto.TRANSLATIONS, null, record)
        } catch (e: ConflictException) {
            log.info("The French of {} waits: {}", record, e.message)
        }
    }

    private fun recordOf(event: Approved): String? = when (val payload = event.payload) {
        is Story -> "${RecordKind.MOMENT.key}:${payload.moment}"
        is MomentPage -> "${RecordKind.MOMENT.key}:${payload.moment}"
        // An anecdote's line in data/ is named after its claim.
        is Anecdote -> "${RecordKind.ANECDOTE.key}:${event.claim}"
        else -> null
    }
}
