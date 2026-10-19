package art.liminis.curation

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import art.liminis.curation.CurationFixtures.HUGO
import art.liminis.curation.CurationFixtures.REMBRANDT
import art.liminis.curation.CurationFixtures.ZOLA
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.OccupationRow
import art.liminis.dataset.PersonOccupation
import art.liminis.occupations.OccupationLabel
import art.liminis.occupations.Occupations
import art.liminis.support.InvalidRequestException
import art.liminis.support.NotFoundException
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The occupations the dataset's people hold, and their classes, proposed for the curator to choose their domain. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@Import(PostgresTestConfiguration::class)
class OccupationProposalsTests(
    @Autowired private val proposals: OccupationProposals,
    @Autowired private val occupations: Occupations,
    @Autowired private val claims: Claims,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
) {

    private val painter = OccupationRow("Q1028181", "painter", "peintre", "peintre", listOf("Q3391743"))
    private val visualArtist = OccupationRow("Q3391743", "visual artist", null, null, listOf("Q483501"))
    private val artist = OccupationRow("Q483501", "artist", "artiste", null, emptyList())
    /** Held by nobody: not proposed. */
    private val sculptor = OccupationRow("Q1281618", "sculptor", null, null, listOf("Q3391743"))

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset, listOf(HUGO, ZOLA, REMBRANDT.copy(occupations = listOf(PersonOccupation(painter.qid, true)))))
        dataset.saveOccupations(listOf(painter, visualArtist, artist, sculptor))
    }

    @Test
    fun `proposes every occupation held, the most held first, then the classes above them, without a domain`() {
        assertEquals(ProposalCounts(added = 4, refreshed = 0, unchanged = 0), proposals.propose())

        val proposed = claims.list(ClaimType.OCCUPATION_DOMAIN, null, null, null, 0, 100)
        assertEquals(listOf("occupation:Q36180", "occupation:Q1028181", "occupation:Q3391743", "occupation:Q483501"), proposed.map { it.id })
        assertEquals(OccupationDomain("Q1028181", "painter", "peintre"), proposed[1].payload)
    }

    @Test
    fun `approving needs a domain, or none, and a refresh keeps the choice`() {
        proposals.propose()
        val id = "occupation:Q483501"
        val claim = claims.detail(id).claim

        assertFailsWith<InvalidRequestException> { claims.decide(id, claim.version!!, NewDecision(Verdict.APPROVE), CurationFixtures.CURATOR) }
        val chosen = OccupationDomain("Q483501", "artist", "artiste", OccupationChoice.ARTS)
        claims.decide(id, claim.version!!, NewDecision(Verdict.APPROVE, payload = chosen), CurationFixtures.CURATOR)

        assertEquals(ProposalCounts(added = 0, refreshed = 0, unchanged = 4), proposals.propose())
        assertEquals(ClaimStatus.APPROVED, claims.detail(id).claim.status)
        assertEquals(chosen, claims.detail(id).claim.payload)
    }

    @Test
    fun `an occupation's evidence counts the people who hold it or a narrower one, the best known first`() {
        val evidence = occupations.evidence(visualArtist.qid)

        assertEquals(1, evidence.people)
        assertEquals(0, evidence.first)
        assertEquals(listOf("Rembrandt"), evidence.examples.map { it.label })
        assertEquals(listOf(OccupationLabel("Q483501", "artist")), evidence.parents)
        assertEquals(2, occupations.evidence("Q36180").people)
        assertFailsWith<NotFoundException> { occupations.evidence("Q1") }
    }
}
