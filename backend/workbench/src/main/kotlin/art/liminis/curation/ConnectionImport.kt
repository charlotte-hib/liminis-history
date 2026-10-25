package art.liminis.curation

import org.springframework.stereotype.Service
import art.liminis.publishing.ConnectionLines
import art.liminis.publishing.DataFiles
import art.liminis.publishing.GitHub
import art.liminis.publishing.PublishProperties
import art.liminis.support.UnavailableException

/**
 * Brings the connections written by hand in the data repository into curation, once: each line of
 * `data/connections.jsonl` on `main` becomes an approved claim, as it stands, so that curate edits them with the rest
 * and publishing writes the file whole from the claims. A line whose claim exists is left as it is.
 */
@Service
class ConnectionImport(
    private val github: GitHub,
    private val publish: PublishProperties,
    private val dataFiles: DataFiles,
    private val claims: ClaimRepository,
    private val curation: Claims,
) {

    fun import(): String {
        val base = github.head(publish.baseBranch) ?: throw UnavailableException("The data repository has no branch ${publish.baseBranch}")
        val qids = dataFiles.qids(github.file(PEOPLE, base))
        // Every line is checked before any is added: a person without a Q-id stops the whole import.
        val connections = dataFiles.readConnections(github.file(CONNECTIONS, base)).map { ConnectionLines.claimOf(it, qids) }
        val added = connections.filter { !claims.existsById(ClaimRules.idOf(it)) }
        added.forEach { curation.addApproved(it, it, ClaimRules.RULES, "Imported from $CONNECTIONS") }
        return "${added.size} imported, ${connections.size - added.size} already there, from $CONNECTIONS at ${base.take(7)}"
    }

    private companion object {
        const val PEOPLE = "data/people.jsonl"
        const val CONNECTIONS = "data/connections.jsonl"
    }
}
