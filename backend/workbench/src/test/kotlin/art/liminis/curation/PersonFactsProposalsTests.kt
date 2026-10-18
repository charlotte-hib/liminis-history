package art.liminis.curation

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import art.liminis.curation.CurationFixtures.HUGO
import art.liminis.curation.CurationFixtures.PEOPLE
import art.liminis.curation.CurationFixtures.ZOLA
import art.liminis.dataset.DatasetStore
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals

/** The facts of the people of the release's moments, proposed for review, and proposed again after a refresh. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false", "app.curation.people-per-moment=2"])
@Import(PostgresTestConfiguration::class)
class PersonFactsProposalsTests(
    @Autowired private val proposals: PersonFactsProposals,
    @Autowired private val claims: Claims,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
    }

    private fun claim(id: String) = claims.detail(id).claim

    private fun version(id: String) = claim(id).version!!

    private fun proposed() = claims.list(null, null, null, null, 0, 100).map { it.id }

    @Test
    fun `proposes the best known people born in each moment's region and alive during it`() {
        val counts = proposals.propose()

        assertEquals(ProposalCounts(added = 3, refreshed = 0, unchanged = 0), counts)
        // Hugo and Zola in Paris in the 1870s, not the less known one; Zola again in the 1890s; nobody in Orléans.
        assertEquals(listOf("person-facts:Q535", "person-facts:Q504", "person-facts:Q1001"), proposed())
    }

    @Test
    fun `a person's claim holds their facts, their flags and their sources`() {
        proposals.propose()

        val detail = claims.detail("person-facts:Q535")
        val hugo = detail.claim
        assertEquals(ClaimStatus.CANDIDATE, hugo.status)
        assertEquals(Origin.WIKIDATA, hugo.origin)
        assertEquals(
            PersonFacts(
                person = "Q535", label = "Victor Hugo", labelFr = "Victor Hugo",
                born = 1802, bornPrecision = 11, died = 1885, diedPrecision = 11, diedEstimated = false, datesApproximate = false,
                enwiki = "Victor Hugo", frwiki = "Victor Hugo",
                occupations = listOf(Occupation("Q36180", "writer", "écrivain")),
                places = listOf(
                    Place(PlaceRole.BIRTH, "Q37776", "Besançon", "FR"),
                    Place(PlaceRole.DEATH, "Q90", "Paris", "FR"),
                ),
            ),
            hugo.payload,
        )
        assertEquals(hugo.payload, hugo.proposal)
        // Stored with its type, the places' missing years left out, not null.
        val stored = jdbc.queryForObject("select payload::text from curation.claim where id = ?", String::class.java, hugo.id)
        assertEquals(jsonMapper.valueToTree<JsonNode>(hugo.payload), jsonMapper.readTree(stored))
        assertEquals("PERSON_FACTS", jsonMapper.readTree(stored).path("type").asString())
        assertEquals(false, jsonMapper.readTree(stored).path("places").path(0).has("startYear"))
        assertEquals(
            listOf(
                SourceRef(kind = SourceKind.WIKIDATA, entity = "Q535"),
                SourceRef(kind = SourceKind.WIKIPEDIA, language = "en", title = "Victor Hugo"),
                SourceRef(kind = SourceKind.WIKIPEDIA, language = "fr", title = "Victor Hugo"),
            ),
            detail.sources,
        )
        assertEquals(listOf("rule" to "Proposed"), detail.decisions.map { it.by to it.note })

        assertEquals(listOf(FlagReason(Flag.POORLY_DOCUMENTED, "DEATH_UNREFERENCED")), claim("person-facts:Q504").flags)
        assertEquals(listOf(FlagReason(Flag.SENSITIVE, "P19_DISPUTED_AREA")), claim("person-facts:Q1001").flags)
    }

    @Test
    fun `proposing again changes nothing when the facts have not changed`() {
        proposals.propose()

        assertEquals(ProposalCounts(added = 0, refreshed = 0, unchanged = 3), proposals.propose())
        assertEquals(1, claims.detail("person-facts:Q535").decisions.size)
    }

    @Test
    fun `a refresh updates a claim waiting for review, and sends an approved one back to review`() {
        proposals.propose()
        claims.decide("person-facts:Q535", version("person-facts:Q535"), NewDecision(Verdict.APPROVE), CurationFixtures.CURATOR)

        CurationFixtures.map(dataset, PEOPLE.map {
            when (it) {
                HUGO -> it.copy(born = 1803)
                ZOLA -> it.copy(died = 1903)
                else -> it
            }
        })
        assertEquals(ProposalCounts(added = 0, refreshed = 2, unchanged = 1), proposals.propose())

        val zola = claim("person-facts:Q504")
        assertEquals(ClaimStatus.CANDIDATE, zola.status)
        assertEquals(1903, (zola.payload as PersonFacts).died)

        // The approved facts stay as approved until the curator looks at what changed.
        val hugoDetail = claims.detail("person-facts:Q535")
        val hugo = hugoDetail.claim
        assertEquals(ClaimStatus.CANDIDATE, hugo.status)
        assertEquals(1802, (hugo.payload as PersonFacts).born)
        assertEquals(1803, (hugo.proposal as PersonFacts).born)
        assertEquals(listOf(FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")), hugo.flags)
        assertEquals(listOf("Proposed", null, "Refreshed"), hugoDetail.decisions.map { it.note })

        // Proposing again keeps the conflict, until the curator decides.
        proposals.propose()
        assertEquals(listOf(FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")), claim("person-facts:Q535").flags)
        claims.decide("person-facts:Q535", version("person-facts:Q535"), NewDecision(Verdict.APPROVE, hugo.proposal), CurationFixtures.CURATOR)
        assertEquals(emptyList(), claim("person-facts:Q535").flags)
    }

    @Test
    fun `a rejected claim stays rejected, and an edited one keeps the edit`() {
        proposals.propose()
        claims.decide("person-facts:Q535", version("person-facts:Q535"), NewDecision(Verdict.REJECT, note = "Not this person"), CurationFixtures.CURATOR)
        val zola = claim("person-facts:Q504").payload as PersonFacts
        claims.decide("person-facts:Q504", version("person-facts:Q504"), NewDecision(Verdict.NEEDS_WORK, zola.copy(died = 1901), "Check the year"), CurationFixtures.CURATOR)

        CurationFixtures.map(dataset, PEOPLE.map { if (it == HUGO || it == ZOLA) it.copy(born = it.born - 1) else it })
        proposals.propose()

        assertEquals(ClaimStatus.REJECTED, claim("person-facts:Q535").status)
        val edited = claim("person-facts:Q504")
        assertEquals(ClaimStatus.NEEDS_WORK, edited.status)
        assertEquals(1901, (edited.payload as PersonFacts).died)
        assertEquals(1839, (edited.proposal as PersonFacts).born)
        assertEquals(FlagReason(Flag.CONFLICT, "CHANGED_SINCE_EDITED"), edited.flags.last())
    }
}
