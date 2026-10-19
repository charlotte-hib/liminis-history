package art.liminis.web

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import art.liminis.curation.Claims
import art.liminis.curation.CurationFixtures
import art.liminis.curation.OccupationDomain
import art.liminis.curation.Origin
import art.liminis.curation.Proposal
import art.liminis.curation.OccupationProposals
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.OccupationRow
import art.liminis.dataset.PersonOccupation
import art.liminis.testing.PostgresTestConfiguration

/**
 * The people to review, with or without a moment, where each stands among them, and a person as the site would show
 * them, on the fixtures' dataset: in Paris in the 1870s, Hugo (200 sitelinks), Zola (150, his death unreferenced) and
 * someone lesser known (30).
 */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class PeopleReviewApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val occupations: OccupationProposals,
    @Autowired private val claims: Claims,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
    }

    private fun propose(vararg qids: String) = qids.forEach { mockMvc.post("/api/people/{qid}/facts", it) { signedIn() } }

    private fun approve(id: String, payload: String? = null) {
        val version = mockMvc.get("/api/claims/{id}", id) { signedIn() }.andReturn().response.getHeader(HttpHeaders.ETAG)!!
        mockMvc.post("/api/claims/{id}/decisions", id) {
            signedIn()
            header(HttpHeaders.IF_MATCH, version)
            contentType = MediaType.APPLICATION_JSON
            content = """{"action": "APPROVE"${payload?.let { ", \"payload\": $it" } ?: ""}}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `without a moment, lists everyone with a facts claim, the best known first, with how many each filter keeps`() {
        propose("Q504", "Q535", "Q5598")
        approve("person-facts:Q5598")
        mockMvc.get("/api/people/review") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.people[*].person") { value(contains("Q535", "Q504")) }
            jsonPath("$.people[0].label") { value("Victor Hugo") }
            jsonPath("$.people[0].sitelinks") { value(200) }
            jsonPath("$.people[0].occupation") { value("writer") }
            jsonPath("$.people[0].birthplace") { value("Besançon") }
            jsonPath("$.people[0].deathplace") { value("Paris") }
            jsonPath("$.people[0].claim") { value("CANDIDATE") }
            jsonPath("$.people[1].flags[0].flag") { value("POORLY_DOCUMENTED") }
            jsonPath("$.counts.waiting") { value(2) }
            jsonPath("$.counts.poorlyDocumented") { value(1) }
            jsonPath("$.counts.conflicts") { value(0) }
            jsonPath("$.counts.decided") { value(1) }
            jsonPath("$.counts.notProposed") { value(0) }
        }
        mockMvc.get("/api/people/review") { signedIn(); param("filter", "DECIDED") }.andExpect {
            jsonPath("$.people[*].person") { value(contains("Q5598")) }
            jsonPath("$.people[0].claim") { value("APPROVED") }
        }
        mockMvc.get("/api/people/review") { signedIn(); param("filter", "POORLY_DOCUMENTED") }.andExpect {
            jsonPath("$.people[*].person") { value(contains("Q504")) }
        }
    }

    @Test
    fun `in a moment, lists everyone alive there, those nobody proposed yet as the dataset has them`() {
        propose("Q504")
        mockMvc.get("/api/people/review") { signedIn(); param("moment", "paris-1870s"); param("filter", "NOT_PROPOSED") }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.people[*].person") { value(contains("Q535", "Q3008")) }
            jsonPath("$.people[0].birthplace") { value("Besançon") }
            jsonPath("$.people[0].claim") { doesNotExist() }
            jsonPath("$.people[0].flags.length()") { value(0) }
            jsonPath("$.counts.waiting") { value(1) }
            jsonPath("$.counts.notProposed") { value(2) }
        }
        // Accents ignored, as when finding people.
        mockMvc.get("/api/people/review") { signedIn(); param("moment", "paris-1870s"); param("q", "emile") }.andExpect {
            jsonPath("$.people[*].person") { value(contains("Q504")) }
            jsonPath("$.counts.notProposed") { value(0) }
        }
        mockMvc.get("/api/people/review") { signedIn(); param("moment", "atlantis-0") }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `a person's claim shows them as edited`() {
        propose("Q535")
        approve(
            "person-facts:Q535",
            """{"type": "PERSON_FACTS", "person": "Q535", "label": "Victor-Marie Hugo", "born": 1802, "bornPrecision": 11,
                "died": 1885, "diedPrecision": 11, "diedEstimated": false, "datesApproximate": false, "occupations": [], "places": []}""",
        )
        mockMvc.get("/api/people/review") { signedIn(); param("filter", "DECIDED") }.andExpect {
            jsonPath("$.people[0].label") { value("Victor-Marie Hugo") }
            jsonPath("$.people[0].occupation") { doesNotExist() }
        }
    }

    @Test
    fun `gives who comes before and after a person, even one the filter no longer keeps`() {
        propose("Q504")
        val notProposed = { qid: String -> mockMvc.get("/api/people/{qid}/neighbours", qid) { signedIn(); param("moment", "paris-1870s"); param("filter", "NOT_PROPOSED") } }
        notProposed("Q535").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.position") { value(1) }
            jsonPath("$.previous") { doesNotExist() }
            jsonPath("$.next") { value("Q3008") }
        }
        notProposed("Q504").andExpect {
            jsonPath("$.position") { doesNotExist() }
            jsonPath("$.previous") { value("Q535") }
            jsonPath("$.next") { value("Q3008") }
        }
        notProposed("Q3008").andExpect {
            jsonPath("$.position") { value(2) }
            jsonPath("$.next") { doesNotExist() }
        }
        mockMvc.get("/api/people/{qid}/neighbours", "Q404") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `previews a person's life in the moment's region, with the anecdotes told about them`() {
        mockMvc.post("/api/claims") {
            signedIn()
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"payload": {"type": "ANECDOTE", "person": "Q535", "year": 1851, "text": "Hugo fled to Brussels."},
                 "sources": [{"kind": "BOOK", "title": "A biography", "page": "12", "quote": "He fled."}]}
            """.trimIndent()
        }.andExpect { status { isCreated() } }
        jdbc.update(
            "insert into dataset.person_article (person, language, title, url, extract, thumbnail_url, image) values " +
                "('Q535', 'en', 'Victor Hugo', 'https://en.wikipedia.org/wiki/Victor_Hugo', ?, null, null), " +
                "('Q535', 'fr', 'Victor Hugo', 'https://fr.wikipedia.org/wiki/Victor_Hugo', 'Victor Hugo est un poète.', 'https://upload.example/hugo.jpg', 'Hugo.jpg')",
            "For the ship, see Victor Hugo (ship).\nVictor Hugo was a French writer.\nHe wrote Les Misérables.",
        )
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn(); param("moment", "paris-1870s") }.andExpect {
            status { isOk() }
            jsonPath("$.portrait") { value("https://upload.example/hugo.jpg") }
            jsonPath("$.about") { value("Victor Hugo was a French writer.") }
            jsonPath("$.wikipediaUrl") { value("https://en.wikipedia.org/wiki/Victor_Hugo") }
            jsonPath("$.region") { value("France") }
            jsonPath("$.domain") { value("EVERYDAY") }
            jsonPath("$.year") { value(1875) }
            jsonPath("$.lifeline[0].kind") { value("BIRTH") }
            jsonPath("$.lifeline[0].text") { value("Born under the First Republic") }
            jsonPath("$.lifeline[1].text") { value("The First Empire begins") }
            jsonPath("$.lifeline[1].age") { value(2) }
            jsonPath("$.lifeline[?(@.kind == 'EVENT')].text") { value(hasItem("The first Impressionist exhibition")) }
            jsonPath("$.lifeline[?(@.kind == 'ANECDOTE')].text") { value(contains("Hugo fled to Brussels.")) }
            jsonPath("$.lifeline[?(@.kind == 'ANECDOTE')].waiting") { value(contains(true)) }
            jsonPath("$.lifeline[?(@.kind == 'ANECDOTE')].source.title") { value(contains("A biography")) }
            jsonPath("$.lifeline[?(@.kind == 'ANECDOTE')].source.quote") { value(not(hasItem("He fled."))) }
            jsonPath("$.lifeline[-1:].text") { value(contains("Dies")) }
            jsonPath("$.lifeline[-1:].age") { value(contains(83)) }
        }
    }

    @Test
    fun `a preview follows the facts being edited, and the domain chosen for their occupation`() {
        occupations.propose()
        approve("occupation:Q36180", """{"type": "OCCUPATION_DOMAIN", "occupation": "Q36180", "label": "writer", "domain": "ARTS"}""")
        // Without a moment: the country their birthplace lies in, and their prime, as the year looked at.
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn(); param("born", "1800"); param("died", "1880") }.andExpect {
            status { isOk() }
            jsonPath("$.domain") { value("ARTS") }
            jsonPath("$.region") { value("France") }
            jsonPath("$.year") { value(1835) }
            jsonPath("$.lifeline[0].year") { value(1800) }
            jsonPath("$.lifeline[-1:].year") { value(contains(1880)) }
        }
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn(); param("occupation", "Q1") }.andExpect {
            jsonPath("$.domain") { value("EVERYDAY") }
        }
        mockMvc.get("/api/people/{qid}/preview", "Q404") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `a claim whose person a mapping run dropped stays in review`() {
        propose("Q504", "Q535")
        CurationFixtures.map(dataset, CurationFixtures.PEOPLE - CurationFixtures.HUGO)
        mockMvc.get("/api/people/review") { signedIn() }.andExpect {
            // As the claim has them, with the least known.
            jsonPath("$.people[*].person") { value(contains("Q504", "Q535")) }
            jsonPath("$.people[1].label") { value("Victor Hugo") }
            jsonPath("$.people[1].sitelinks") { value(0) }
        }
        mockMvc.get("/api/people/{qid}/neighbours", "Q535") { signedIn() }.andExpect {
            jsonPath("$.position") { value(2) }
            jsonPath("$.previous") { value("Q504") }
        }
    }

    @Test
    fun `a preview takes the domain approved for a class above the occupation, as the release does`() {
        val novelist = OccupationRow("Q6625963", "novelist", "romancier", null, listOf("Q36180"))
        CurationFixtures.map(dataset, listOf(CurationFixtures.HUGO.copy(occupations = listOf(PersonOccupation(novelist.qid, true)))))
        dataset.saveOccupations(listOf(novelist))
        // Saved apart from the fixtures' writer, the novelist's class is linked by hand.
        jdbc.update("insert into dataset.occupation_parent (occupation, position, parent) values ('Q6625963', 0, 'Q36180')")
        // Only the class has a domain: the novelist's comes from it.
        claims.propose(listOf(Proposal(OccupationDomain("Q36180", "writer"), Origin.WIKIDATA, emptyList(), emptyList())))
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn() }.andExpect { jsonPath("$.domain") { value("EVERYDAY") } }
        approve("occupation:Q36180", """{"type": "OCCUPATION_DOMAIN", "occupation": "Q36180", "label": "writer", "domain": "ARTS"}""")
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn() }.andExpect { jsonPath("$.domain") { value("ARTS") } }
    }

    @Test
    fun `refuses a preview born after the death, and an unknown moment`() {
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn(); param("born", "1890"); param("died", "1885") }.andExpect {
            status { isBadRequest() }
        }
        mockMvc.get("/api/people/{qid}/preview", "Q535") { signedIn(); param("moment", "atlantis-0") }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/people/{qid}/neighbours", "Q535") { signedIn(); param("moment", "atlantis-0") }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `filters the waiting with a conflict, and finds neighbours within a search`() {
        propose("Q504", "Q535", "Q3008")
        jdbc.update("update curation.claim set flags = '[{\"flag\": \"CONFLICT\", \"reason\": \"BIRTH_DISAGREES\"}]' where id = 'person-facts:Q3008'")
        mockMvc.get("/api/people/review") { signedIn(); param("filter", "CONFLICTS") }.andExpect {
            jsonPath("$.people[*].person") { value(contains("Q3008")) }
            jsonPath("$.counts.conflicts") { value(1) }
        }
        mockMvc.get("/api/people/{qid}/neighbours", "Q535") { signedIn(); param("q", "o") }.andExpect { status { isBadRequest() } }
        mockMvc.get("/api/people/{qid}/neighbours", "Q535") { signedIn(); param("q", "known") }.andExpect {
            // Only Lesser Known is named so: Hugo, outside the search, still has him after.
            jsonPath("$.total") { value(1) }
            jsonPath("$.position") { doesNotExist() }
            jsonPath("$.next") { value("Q3008") }
        }
    }
}
