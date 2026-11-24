package art.liminis.drafting

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import art.liminis.curation.Bio
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.Claims
import art.liminis.curation.CurationFixtures
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.Origin
import art.liminis.curation.SourceKind
import art.liminis.curation.SourceRef
import art.liminis.dataset.DatasetStore
import art.liminis.runs.Runs
import art.liminis.support.NotFoundException
import art.liminis.testing.PostgresTestConfiguration
import art.liminis.web.CURATOR_SCOPES
import art.liminis.web.signedIn
import art.liminis.workbench.api.model.RunDto
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import art.liminis.curation.Citation as BioCitation

/** Drafting a person's bio from their intros, with Claude stood in for, into their bio claim. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class BioDraftingTests(
    @Autowired private val drafting: BioDrafting,
    @Autowired private val claims: Claims,
    @Autowired private val claimRepository: ClaimRepository,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val runs: Runs,
) {

    @MockitoBean
    private lateinit var drafter: BioDrafter

    private val english = "Victor Hugo was a French Romantic writer and politician."
    private val french = "Victor Hugo est un poète, dramaturge et romancier français."

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
        for ((language, extract) in listOf("en" to english, "fr" to french)) {
            jdbc.update(
                "insert into dataset.person_article (person, language, title, url, extract) values ('Q535', ?, 'Victor Hugo', ?, ?)",
                language, "https://$language.wikipedia.org/wiki/Victor_Hugo", extract,
            )
        }
    }

    // Mockito's matchers answer null, which Kotlin refuses for a parameter that is not nullable.
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = ArgumentMatchers.any<T>() ?: null as T

    private fun answer(text: String, vararg citations: Citation) =
        doReturn(DraftedBio(BioDraft(text, citations.toList()), "claude-opus-5-5", 2_000, 600)).`when`(drafter).draft(anyOf(), anyOf())

    @Test
    fun `a draft becomes the person's bio claim, with its quotes, its cost, and what the check found in it`() {
        answer("A French writer and politician.", Citation("wikipedia:en", "French Romantic writer"), Citation("wikipedia:fr", "un poète inventé"))

        val result = drafting.draft("Q535")

        // 2,000 tokens at $4 a million and 600 at $20.
        assertTrue(result.endsWith("2,000 input and 600 output tokens, about \$0.02"), result)
        val detail = claims.detail("bio:Q535")
        assertEquals(ClaimStatus.CANDIDATE, detail.claim.status)
        assertEquals(Origin.CLAUDE, detail.claim.origin)
        assertEquals(
            Bio("Q535", "A French writer and politician.", listOf(BioCitation("wikipedia:en", "Victor Hugo", "French Romantic writer"), BioCitation("wikipedia:fr", "Victor Hugo", "un poète inventé"))),
            detail.claim.payload,
        )
        assertEquals(listOf(FlagReason(Flag.ODD, "The bio quotes text not found in 'wikipedia:fr': \"un poète inventé\"")), detail.claim.flags)
        assertEquals(listOf("en", "fr").map { SourceRef(SourceKind.WIKIPEDIA, language = it, title = "Victor Hugo") }, detail.sources)
    }

    @Test
    fun `a bio too long for the site is flagged, and the curator's edit stays when Claude drafts again`() {
        answer("a".repeat(300), Citation("wikipedia:en", "writer"))
        drafting.draft("Q535")
        assertEquals(
            listOf(FlagReason(Flag.ODD, "The bio has 300 characters, more than the site's 280: shorten it")),
            claimRepository.findById("bio:Q535").get().flags,
        )

        claimRepository.save(claimRepository.findById("bio:Q535").get().apply { payload = Bio("Q535", "Edited by hand.") })
        answer("Again.", Citation("wikipedia:en", "writer"))
        drafting.draft("Q535")
        assertEquals(Bio("Q535", "Edited by hand."), claimRepository.findById("bio:Q535").get().payload)
    }

    @Test
    fun `someone with no intro has nothing to draft from`() {
        assertThrows<NotFoundException> { drafting.draft("Q504") }
        mockMvc.post("/api/people/{qid}/bio", "Q504") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `drafting a bio starts the run the person's page then follows, unless someone else holds the bio`() {
        answer("A French writer.", Citation("wikipedia:en", "French Romantic writer"))
        mockMvc.get("/api/people/{qid}/bio", "Q535") { signedIn() }.andExpect { jsonPath("$.draft") { doesNotExist() } }

        val run = mockMvc.post("/api/people/{qid}/bio", "Q535") { signedIn() }.andExpect {
            status { isAccepted() }
            jsonPath("$.kind") { value("BIO_DRAFT") }
            jsonPath("$.person") { value("Q535") }
        }.andReturn().response.contentAsString
        val id = Regex("\"id\":(\\d+)").find(run)!!.groupValues[1].toLong()
        mockMvc.get("/api/people/{qid}/bio", "Q535") { signedIn() }.andExpect { jsonPath("$.draft.id") { value(id) } }
        while (runs.find(id).status == RunDto.Status.RUNNING) Thread.sleep(50)
        assertEquals(RunDto.Status.SUCCEEDED, runs.find(id).status, runs.find(id).result)

        claimRepository.save(claimRepository.findById("bio:Q535").get().apply { heldBy = "historian" })
        mockMvc.post("/api/people/{qid}/bio", "Q535") { signedIn(CURATOR_SCOPES, username = "someone") }.andExpect { status { isConflict() } }
    }
}
