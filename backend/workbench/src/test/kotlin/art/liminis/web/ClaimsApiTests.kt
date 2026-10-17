package art.liminis.web

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import art.liminis.curation.Citation
import art.liminis.curation.ClaimRepository
import art.liminis.curation.Claim
import art.liminis.curation.CurationFixtures
import art.liminis.curation.MomentPage
import art.liminis.curation.Origin
import art.liminis.curation.Story
import art.liminis.curation.StoryCard
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.domain.CardType
import art.liminis.dataset.DatasetStore
import art.liminis.testing.PostgresTestConfiguration
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The claims and runs API, as `wb` and `curate` call it. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false", "app.curation.people-per-moment=2"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ClaimsApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val claimRepository: ClaimRepository,
) {

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
    }

    private fun MockHttpServletRequestDsl.ifMatch(etag: String) = header(HttpHeaders.IF_MATCH, etag)

    /** The claim's ETag, as a client reads it before deciding. */
    private fun etag(id: String): String =
        mockMvc.get("/api/claims/{id}", id) { signedIn() }.andReturn().response.getHeader(HttpHeaders.ETAG)!!

    private fun MockHttpServletRequestDsl.json(body: String) {
        signedIn()
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private val anecdote = """
        {
          "payload": {"type": "ANECDOTE", "person": "Q535", "year": 1851,
                      "text": "Hugo fled to Brussels after Louis-Napoléon's coup, disguised as a worker."},
          "sources": [{"kind": "MEDIA", "title": "Some podcast", "episode": "Victor Hugo in exile", "minute": "12:40"}],
          "note": "Heard on a podcast"
        }
    """.trimIndent()

    private val anecdoteId = "anecdote:Q535:1851:ee94071e7248"

    @Test
    fun `adds an anecdote heard on a podcast, flagged as having a single source`() {
        mockMvc.post("/api/claims") { json(anecdote) }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(anecdoteId) }
            jsonPath("$.type") { value("ANECDOTE") }
            jsonPath("$.status") { value("CANDIDATE") }
            jsonPath("$.origin") { value("MANUAL") }
            jsonPath("$.flags[0].flag") { value("SINGLE_SOURCE") }
            jsonPath("$.payload.text") { value(containsString("Brussels")) }
            jsonPath("$.sources[0].kind") { value("MEDIA") }
            jsonPath("$.sources[0].minute") { value("12:40") }
            jsonPath("$.sources[0].url") { doesNotExist() }
            jsonPath("$.decisions[0].by") { value("curator") }
            jsonPath("$.decisions[0].to") { value("CANDIDATE") }
            jsonPath("$.decisions[0].note") { value("Heard on a podcast") }
        }

        // The same anecdote again lands on the same claim.
        mockMvc.post("/api/claims") { json(anecdote) }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Conflict") }
            jsonPath("$.detail") { value("$anecdoteId exists already") }
            jsonPath("$.instance") { value("/api/claims") }
        }
    }

    @Test
    fun `adds a connection with two sources, the same whichever order its people come in`() {
        val connection = """
            {"payload": {"type": "CONNECTION", "people": ["Q535", "Q504"], "kind": "friends", "year": 1878,
                         "text": "Zola wrote to Hugo in 1878."},
             "sources": [{"kind": "BOOK", "title": "The Timetables of History", "page": "214", "quote": "a quote"},
                         {"kind": "WEB", "url": "https://example.org/zola"}]}
        """.trimIndent()

        mockMvc.post("/api/claims") { json(connection) }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("connection:Q504|Q535:friends") }
            jsonPath("$.flags") { isEmpty() }
            jsonPath("$.sources[0].quote") { value("a quote") }
        }
        mockMvc.post("/api/claims") { json(connection.replace("[\"Q535\", \"Q504\"]", "[\"Q504\", \"Q535\"]")) }
            .andExpect { status { isConflict() } }
    }

    @Test
    fun `refuses a claim that does not hold together`() {
        val refused = listOf(
            // No year 0.
            anecdote.replace("1851", "0"),
            // A connection links two different people.
            """{"payload": {"type": "CONNECTION", "people": ["Q535", "Q535"], "kind": "self", "year": 1850, "text": "t"},
                "sources": [{"kind": "WEB", "url": "https://example.org"}]}""",
            // A person's facts come from Wikidata only.
            """{"payload": {"type": "PERSON_FACTS", "person": "Q535", "label": "Hugo", "born": 1802, "bornPrecision": 9,
                "died": 1885, "diedEstimated": false, "datesApproximate": false, "occupations": [], "places": []},
                "sources": [{"kind": "WEB", "url": "https://example.org"}]}""",
            // A book source needs its page.
            anecdote.replace(""""kind": "MEDIA"""", """"kind": "BOOK""""),
            // At least one source.
            anecdote.replace(Regex(""""sources": \[.*]"""), """"sources": []"""),
            // Not a Q-id.
            anecdote.replace("Q535", "Victor Hugo"),
        )
        for (body in refused) {
            mockMvc.post("/api/claims") { json(body) }.andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
        }
    }

    @Test
    fun `approves with an edit, then undoes it`() {
        mockMvc.post("/api/claims") { json(anecdote) }
        val edited = """
            {"action": "APPROVE",
             "payload": {"type": "ANECDOTE", "person": "Q535", "year": 1851,
                         "text": "After Louis-Napoléon's coup, Hugo fled to Brussels disguised as a worker."}}
        """.trimIndent()

        val proposed = etag(anecdoteId)
        val approved = mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json(edited)
            ifMatch(proposed)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.payload.text") { value(containsString("After Louis-Napoléon's coup")) }
            jsonPath("$.proposal.text") { value(containsString("Hugo fled")) }
            jsonPath("$.decisions[1].from") { value("CANDIDATE") }
            jsonPath("$.decisions[1].edited") { value(true) }
        }.andReturn().response.getHeader(HttpHeaders.ETAG)!!
        assertNotEquals(proposed, approved)

        mockMvc.post("/api/claims/{id}/undo", anecdoteId) {
            signedIn()
            ifMatch(approved)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANDIDATE") }
            jsonPath("$.payload.text") { value(containsString("Hugo fled")) }
            jsonPath("$.decisions.length()") { value(3) }
            jsonPath("$.decisions[2].note") { value("Undone") }
        }
        // An undo is not undone, and the claim's creation is not either.
        mockMvc.post("/api/claims/{id}/undo", anecdoteId) {
            signedIn()
            ifMatch(etag(anecdoteId))
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `a rejection needs a reason, and an edit must stay about the same person`() {
        mockMvc.post("/api/claims") { json(anecdote) }
        val proposed = etag(anecdoteId)

        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json("""{"action": "REJECT"}""")
            ifMatch(proposed)
        }.andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.title") { value("Bad Request") }
                jsonPath("$.detail") { value("Say why, in a note") }
            }
        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json("""{"action": "APPROVE", "payload": {"type": "ANECDOTE", "person": "Q504", "year": 1851, "text": "t"}}""")
            ifMatch(proposed)
        }.andExpect { status { isBadRequest() } }
        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json("""{"action": "REJECT", "note": "Legend"}""")
            ifMatch(proposed)
        }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("REJECTED") }
            }
        mockMvc.post("/api/claims/{id}/decisions", "anecdote:Q1:1:none") {
            json("""{"action": "APPROVE"}""")
            ifMatch(proposed)
        }.andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.detail") { value("No claim anecdote:Q1:1:none") }
            }
    }

    @Test
    fun `a decision names the version of the claim it was taken on`() {
        val added = mockMvc.post("/api/claims") { json(anecdote) }.andReturn().response.getHeader(HttpHeaders.ETAG)!!
        assertTrue(added.matches(Regex("\"[0-9]+\"")), added)
        assertEquals(added, etag(anecdoteId))
        val approve = """{"action": "APPROVE"}"""

        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) { json(approve) }.andExpect {
            status { isEqualTo(428) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Send the claim's ETag in If-Match") }
        }
        mockMvc.post("/api/claims/{id}/undo", anecdoteId) { signedIn() }.andExpect { status { isEqualTo(428) } }
        for (other in listOf("\"999\"", "W/$added", "*")) {
            mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
                json(approve)
                ifMatch(other)
            }.andExpect {
                status { isPreconditionFailed() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
        }

        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json(approve)
            ifMatch(added)
        }.andExpect { status { isOk() } }
        // Someone else, still on the version they read, does not overwrite that decision.
        mockMvc.post("/api/claims/{id}/decisions", anecdoteId) {
            json("""{"action": "REJECT", "note": "Legend"}""")
            ifMatch(added)
        }.andExpect {
            status { isPreconditionFailed() }
            jsonPath("$.detail") { value(containsString("$anecdoteId changed since version ${added.trim('"')}")) }
        }
        mockMvc.get("/api/claims/{id}", anecdoteId) { signedIn() }.andExpect { jsonPath("$.status") { value("APPROVED") } }
    }

    @Test
    fun `proposes people's facts through a run, then lists them by queue, status and flag`() {
        mockMvc.post("/api/runs") { json("""{"kind": "STORY_DRAFT"}""") }.andExpect { status { isBadRequest() } }
        mockMvc.post("/api/runs") { json("""{"kind": "STORY_DRAFT", "moment": "atlantis-1000"}""") }.andExpect { status { isBadRequest() } }

        mockMvc.post("/api/runs") { json("""{"kind": "PERSON_FACTS_CLAIMS"}""") }.andExpect {
            status { isAccepted() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.status") { value("RUNNING") }
        }
        awaitRun(1)
        mockMvc.get("/api/runs/1") { signedIn() }.andExpect {
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.result") { value("3 added, 0 refreshed, 0 unchanged") }
        }
        mockMvc.post("/api/claims") { json(anecdote) }

        mockMvc.get("/api/queues") { signedIn() }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].type") { value("ANECDOTE") }
            jsonPath("$[0].candidates") { value(1) }
            jsonPath("$[1].type") { value("PERSON_FACTS") }
            jsonPath("$[1].candidates") { value(3) }
            jsonPath("$[1].needsWork") { value(0) }
            jsonPath("$[1].flags.POORLY_DOCUMENTED") { value(1) }
            jsonPath("$[1].flags.SENSITIVE") { value(1) }
        }
        mockMvc.get("/api/claims?type=PERSON_FACTS&limit=2") { signedIn() }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].id") { value("person-facts:Q535") }
            jsonPath("$[1].id") { value("person-facts:Q504") }
        }
        mockMvc.get("/api/claims?type=PERSON_FACTS&after=2") { signedIn() }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value("person-facts:Q1001") }
        }
        mockMvc.get("/api/claims?flag=SENSITIVE") { signedIn() }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value("person-facts:Q1001") }
        }
        mockMvc.get("/api/claims?status=APPROVED") { signedIn() }.andExpect { jsonPath("$") { isEmpty() } }
        mockMvc.get("/api/claims?limit=500") { signedIn() }.andExpect { status { isBadRequest() } }
    }

    private fun awaitRun(id: Long) {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        while (Instant.now() < deadline) {
            val body = mockMvc.get("/api/runs/$id") { signedIn() }.andReturn().response.contentAsString
            if (!body.contains("\"RUNNING\"")) return
            Thread.sleep(50)
        }
        error("Run $id still running")
    }

    @Test
    fun `a story claim reads with its quotes, and approves as edited`() {
        val citations = listOf(Citation("person:Q535", "Victor Hugo (1802–1885), writer", "a writer"))
        val story = Story("paris-1870s", listOf(StoryCard(CardType.SCENE, title = "A city", text = "Boulevards.", citations = citations)))
        claimRepository.save(Claim.candidate(story, Origin.CLAUDE, emptyList()))

        mockMvc.get("/api/claims/{id}", "story:paris-1870s") { signedIn() }.andExpect {
            jsonPath("$.payload.type") { value("STORY") }
            jsonPath("$.payload.cards[0].type") { value("SCENE") }
            jsonPath("$.payload.cards[0].citations[0].title") { value("Victor Hugo (1802–1885), writer") }
        }
        val edited = """
            {"action": "APPROVE", "payload": {"type": "STORY", "moment": "paris-1870s",
              "cards": [{"type": "PERSON", "person": "victor-hugo", "text": "Back.", "citations": []}]}}
        """.trimIndent()
        mockMvc.post("/api/claims/{id}/decisions", "story:paris-1870s") { json(edited); ifMatch(etag("story:paris-1870s")) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.payload.cards[0].person") { value("victor-hugo") }
        }
    }

    @Test
    fun `a moment's page drafted with Claude approves as edited`() {
        val line = StoryLine("A line.", listOf(Citation("person:Q535", "Victor Hugo (1802–1885), writer", "a writer")))
        claimRepository.save(Claim.candidate(MomentPage("paris-1870s", line, StoryWorld(line, line, line, line)), Origin.CLAUDE, emptyList()))

        mockMvc.get("/api/claims/{id}", "moment-page:paris-1870s") { signedIn() }.andExpect {
            jsonPath("$.type") { value("MOMENT_PAGE") }
            jsonPath("$.payload.type") { value("MOMENT_PAGE") }
            jsonPath("$.payload.hook.citations[0].title") { value("Victor Hugo (1802–1885), writer") }
        }
        val edited = """
            {"action": "APPROVE", "payload": {"type": "MOMENT_PAGE", "moment": "paris-1870s",
              "hook": {"text": "A better hook.", "citations": []},
              "world": {"governs": {"text": "g", "citations": []}, "everyday": {"text": "e", "citations": []},
                        "arts": {"text": "a", "citations": []}, "meanwhile": {"text": "m", "citations": []}}}}
        """.trimIndent()
        mockMvc.post("/api/claims/{id}/decisions", "moment-page:paris-1870s") { json(edited); ifMatch(etag("moment-page:paris-1870s")) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.payload.hook.text") { value("A better hook.") }
        }
        // Drafted already: written on the moment's page, it is refused.
        mockMvc.post("/api/moments/{id}/page", "paris-1870s") { json(pageText("Mine.")) }.andExpect { status { isConflict() } }
    }

    @Test
    fun `a moment's page written from the release's text is a claim approved at once, the release's text as its proposal`() {
        mockMvc.post("/api/moments/{id}/page", "paris-1870s") { json(pageText("A republic, and its painters.")) }.andExpect {
            status { isCreated() }
            header { exists(HttpHeaders.ETAG) }
            jsonPath("$.id") { value("moment-page:paris-1870s") }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.origin") { value("MANUAL") }
            jsonPath("$.payload.hook.text") { value("A republic, and its painters.") }
            jsonPath("$.proposal.hook.text") { value("A republic born out of defeat, and painters the Salon keeps rejecting.") }
            jsonPath("$.decisions[0].to") { value("APPROVED") }
            jsonPath("$.decisions[0].edited") { value(true) }
        }
        mockMvc.get("/api/moments/{id}", "paris-1870s") { signedIn() }.andExpect { jsonPath("$.page") { value("APPROVED") } }
        mockMvc.post("/api/moments/{id}/page", "paris-1870s") { json(pageText("Again.")) }.andExpect { status { isConflict() } }
        mockMvc.post("/api/moments/{id}/page", "atlantis-1000") { json(pageText("Nowhere.")) }.andExpect { status { isNotFound() } }
        mockMvc.post("/api/moments/{id}/page", "paris-1890s") { json(pageText("")) }.andExpect { status { isBadRequest() } }
    }

    private fun pageText(hook: String) =
        """{"hook": "$hook", "world": {"governs": "g", "everyday": "e", "arts": "a", "meanwhile": "m"}}"""
}
