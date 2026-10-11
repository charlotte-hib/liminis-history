package dev.wholivedwhen.curation

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import dev.wholivedwhen.curation.CurationFixtures.HUGO
import dev.wholivedwhen.curation.CurationFixtures.PEOPLE
import dev.wholivedwhen.curation.CurationFixtures.ZOLA
import dev.wholivedwhen.dataset.DatasetStore
import dev.wholivedwhen.testing.PostgresTestConfiguration
import dev.wholivedwhen.workbench.api.model.ClaimStatusDto
import dev.wholivedwhen.workbench.api.model.FlagDto
import dev.wholivedwhen.workbench.api.model.FlagReasonDto
import dev.wholivedwhen.workbench.api.model.NewDecisionDto
import dev.wholivedwhen.workbench.api.model.OccupationDto
import dev.wholivedwhen.workbench.api.model.OriginDto
import dev.wholivedwhen.workbench.api.model.PersonFactsDto
import dev.wholivedwhen.workbench.api.model.PlaceDto
import dev.wholivedwhen.workbench.api.model.SourceDto
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

    private fun proposed() = claims.list(null, null, null, 0, 100).map { it.id }

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

        val hugo = claims.detail("person-facts:Q535")
        assertEquals(ClaimStatusDto.CANDIDATE, hugo.status)
        assertEquals(OriginDto.WIKIDATA, hugo.origin)
        assertEquals(
            PersonFactsDto(
                type = "PERSON_FACTS", person = "Q535", label = "Victor Hugo", labelFr = "Victor Hugo",
                born = 1802, bornPrecision = 11, died = 1885, diedPrecision = 11, diedEstimated = false, datesApproximate = false,
                enwiki = "Victor Hugo", frwiki = "Victor Hugo",
                occupations = listOf(OccupationDto("Q36180", "writer", "écrivain")),
                places = listOf(
                    PlaceDto(PlaceDto.Role.BIRTH, "Q37776", "Besançon", "FR"),
                    PlaceDto(PlaceDto.Role.DEATH, "Q90", "Paris", "FR"),
                ),
            ),
            hugo.payload,
        )
        assertEquals(hugo.payload, hugo.proposal)
        // Stored as the API writes it: the places' missing years left out, not null.
        val stored = jdbc.queryForObject("select payload::text from curation.claim where id = ?", String::class.java, hugo.id)
        assertEquals(jsonMapper.valueToTree<JsonNode>(hugo.payload), jsonMapper.readTree(stored))
        assertEquals(
            listOf(
                SourceDto(kind = SourceDto.Kind.WIKIDATA, entity = "Q535"),
                SourceDto(kind = SourceDto.Kind.WIKIPEDIA, language = "en", title = "Victor Hugo"),
                SourceDto(kind = SourceDto.Kind.WIKIPEDIA, language = "fr", title = "Victor Hugo"),
            ),
            hugo.sources,
        )
        assertEquals(listOf("rule" to "Proposed"), hugo.decisions.map { it.by to it.note })

        assertEquals(listOf(FlagReasonDto(FlagDto.POORLY_DOCUMENTED, "DEATH_UNREFERENCED")), claims.detail("person-facts:Q504").flags)
        assertEquals(listOf(FlagReasonDto(FlagDto.SENSITIVE, "P19_DISPUTED_AREA")), claims.detail("person-facts:Q1001").flags)
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
        claims.decide("person-facts:Q535", NewDecisionDto(NewDecisionDto.Action.APPROVE), "curator")

        CurationFixtures.map(dataset, PEOPLE.map {
            when (it) {
                HUGO -> it.copy(born = 1803)
                ZOLA -> it.copy(died = 1903)
                else -> it
            }
        })
        assertEquals(ProposalCounts(added = 0, refreshed = 2, unchanged = 1), proposals.propose())

        val zola = claims.detail("person-facts:Q504")
        assertEquals(ClaimStatusDto.CANDIDATE, zola.status)
        assertEquals(1903, (zola.payload as PersonFactsDto).died)

        // The approved facts stay as approved until the curator looks at what changed.
        val hugo = claims.detail("person-facts:Q535")
        assertEquals(ClaimStatusDto.CANDIDATE, hugo.status)
        assertEquals(1802, (hugo.payload as PersonFactsDto).born)
        assertEquals(1803, (hugo.proposal as PersonFactsDto).born)
        assertEquals(listOf(FlagReasonDto(FlagDto.CONFLICT, "CHANGED_SINCE_APPROVED")), hugo.flags)
        assertEquals(listOf("Proposed", null, "Refreshed"), hugo.decisions.map { it.note })

        // Proposing again keeps the conflict, until the curator decides.
        proposals.propose()
        assertEquals(listOf(FlagReasonDto(FlagDto.CONFLICT, "CHANGED_SINCE_APPROVED")), claims.detail("person-facts:Q535").flags)
        claims.decide("person-facts:Q535", NewDecisionDto(NewDecisionDto.Action.APPROVE, hugo.proposal), "curator")
        assertEquals(emptyList(), claims.detail("person-facts:Q535").flags)
    }

    @Test
    fun `a rejected claim stays rejected, and an edited one keeps the edit`() {
        proposals.propose()
        claims.decide("person-facts:Q535", NewDecisionDto(NewDecisionDto.Action.REJECT, note = "Not this person"), "curator")
        val zola = claims.detail("person-facts:Q504").payload as PersonFactsDto
        claims.decide("person-facts:Q504", NewDecisionDto(NewDecisionDto.Action.NEEDS_WORK, zola.copy(died = 1901), "Check the year"), "curator")

        CurationFixtures.map(dataset, PEOPLE.map { if (it == HUGO || it == ZOLA) it.copy(born = it.born - 1) else it })
        proposals.propose()

        assertEquals(ClaimStatusDto.REJECTED, claims.detail("person-facts:Q535").status)
        val edited = claims.detail("person-facts:Q504")
        assertEquals(ClaimStatusDto.NEEDS_WORK, edited.status)
        assertEquals(1901, (edited.payload as PersonFactsDto).died)
        assertEquals(1839, (edited.proposal as PersonFactsDto).born)
        assertEquals(FlagReasonDto(FlagDto.CONFLICT, "CHANGED_SINCE_EDITED"), edited.flags.last())
    }
}
