package art.liminis.curation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.publishing.ConnectionLines
import art.liminis.publishing.DataFiles
import art.liminis.publishing.GitHub
import art.liminis.publishing.PublishProperties
import art.liminis.support.InvalidRequestException
import art.liminis.support.UnavailableException

/**
 * Brings the connections written by hand in the data repository into curation, once: each line of
 * `data/connections.jsonl` on `main` becomes an approved claim, as it stands, held by nobody, so that curate edits them
 * with the rest and publishing writes the file whole from the claims. A line a claim already holds (the same people and
 * kind, whatever its id) is left as it is. All the lines, or none.
 */
@Service
class ConnectionImport(
    private val github: GitHub,
    private val publish: PublishProperties,
    private val dataFiles: DataFiles,
    private val claims: ClaimRepository,
    private val curation: Claims,
) {

    @Transactional
    fun import(): String {
        val base = github.head(publish.baseBranch) ?: throw UnavailableException("The data repository has no branch ${publish.baseBranch}")
        val qids = dataFiles.qids(github.file(PEOPLE, base))
        val claimed = claims.findByTypeOrderById(ClaimType.CONNECTION).map { ConnectionLines.key(it.payload as Connection) }.toSet()
        val lines = dataFiles.readConnections(github.file(CONNECTIONS, base))
        val added = lines.filter { ConnectionLines.key(ConnectionLines.byQid(it, qids)) !in claimed }.map { line ->
            val where = "$CONNECTIONS, ${line.people.joinToString(" and ")}, ${line.kind}"
            try {
                ConnectionLines.claimOf(line, qids).also { curation.addApproved(it, it, ClaimRules.RULES, "Imported from $CONNECTIONS", held = false) }
            } catch (e: InvalidRequestException) {
                throw UnavailableException("$where: ${e.message}")
            } catch (e: UnavailableException) {
                throw UnavailableException("$where: ${e.message}")
            }
        }
        return "${added.size} imported, ${lines.size - added.size} already there, from $CONNECTIONS at ${base.take(7)}"
    }

    private companion object {
        const val PEOPLE = "data/people.jsonl"
        const val CONNECTIONS = "data/connections.jsonl"
    }
}
