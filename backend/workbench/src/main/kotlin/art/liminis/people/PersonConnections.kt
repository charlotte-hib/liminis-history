package art.liminis.people

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.Connection
import art.liminis.dataset.DatasetStore
import art.liminis.repository.PersonRepository

/** The other person in a connection: as the dataset gives them, else as the release writes them, else by Q-id alone. */
data class ConnectionPerson(
    val person: String,
    val label: String,
    val born: Int?,
    val died: Int?,
    val occupation: String?,
    /** Whether the release doesn't hold them yet: the connection waits for them at publishing. */
    val fetched: Boolean,
)

/** A connection claim about a person, with the other person in it. */
data class PersonConnection(val claim: Claim, val other: ConnectionPerson)

/** The connections a person's page lists, for the curator to edit, add to or remove. */
@Service
class PersonConnections(
    private val claims: ClaimRepository,
    private val dataset: DatasetStore,
    private val releasePeople: PersonRepository,
) {

    /** The connection claims naming [qid] but those rejected, by year, each with the other person. */
    @Transactional(readOnly = true)
    fun of(qid: String): List<PersonConnection> {
        val found = claims.connectionsOf(qid).map { it to (it.payload as Connection).people.first { person -> person != qid } }
        val others = found.map { it.second }.distinct()
        val fetched = dataset.summaries(others, others.size).associateBy { it.person }
        val held = releasePeople.findByQidIn(others).associateBy { it.qid!! }
        return found.sortedBy { (claim, _) -> (claim.payload as Connection).year }.map { (claim, other) ->
            val summary = fetched[other]
            val written = held[other]
            val person = when {
                summary != null -> ConnectionPerson(other, summary.label, summary.born, summary.died, summary.occupation, written == null)
                written != null -> ConnectionPerson(other, written.name, written.birthYear, written.deathYear, written.occupations.firstOrNull(), false)
                else -> ConnectionPerson(other, other, null, null, null, true)
            }
            PersonConnection(claim, person)
        }
    }

    /** The Q-ids among [qids] that the release holds: the others are fetched people, not on the site yet. */
    fun held(qids: Collection<String>): Set<String> = releasePeople.findByQidIn(qids).mapNotNull { it.qid }.toSet()
}
