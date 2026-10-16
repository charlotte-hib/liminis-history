package art.liminis.web

import org.junit.jupiter.api.BeforeEach
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import art.liminis.curation.CurationFixtures
import art.liminis.dataset.DatasetStore
import art.liminis.drafting.StoryDrafter
import art.liminis.testing.PostgresTestConfiguration

/** The moment workspace: the release's moments, everyone alive in each with their claims, and proposing a person's facts. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class MomentsApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dataset: DatasetStore,
    @Autowired private val jdbc: JdbcTemplate,
) {

    // Never Claude itself, even with a key in the environment: a draft run started here fails without spending.
    @MockitoBean
    private lateinit var drafter: StoryDrafter

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
        CurationFixtures.map(dataset)
    }

    @Test
    fun `lists the release's moments with their people, their waiting claims and their story's cards`() {
        mockMvc.post("/api/people/{qid}/facts", "Q504") { signedIn() }
        mockMvc.get("/api/moments") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(10) }
            jsonPath("$[?(@.id == 'paris-1870s')].people") { value(3) }
            jsonPath("$[?(@.id == 'paris-1870s')].waiting") { value(1) }
            jsonPath("$[?(@.id == 'paris-1870s')].cards") { value(7) }
            jsonPath("$[?(@.id == 'paris-1870s')].story") { isEmpty() }
            jsonPath("$[?(@.id == 'edo-1830s')].people") { value(0) }
        }
    }

    @Test
    fun `a moment comes with the story the release has`() {
        mockMvc.get("/api/moments/{id}", "paris-1870s") { signedIn() }.andExpect {
            status { isOk() }
            jsonPath("$.hook") { value("A republic born out of defeat, and painters the Salon keeps rejecting.") }
            jsonPath("$.world.governs") { exists() }
            jsonPath("$.storyCards.length()") { value(7) }
            jsonPath("$.storyCards[2].type") { value("PERSON") }
            jsonPath("$.storyCards[2].person") { value("georges-clemenceau") }
            jsonPath("$.storyCards[2].label") { value("Georges Clemenceau") }
        }
        mockMvc.get("/api/moments/{id}", "atlantis-0") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `lists everyone tied to the moment's region by any place and alive during it, the best known first`() {
        mockMvc.post("/api/people/{qid}/facts", "Q504") { signedIn() }.andExpect {
            status { isOk() }
            header { exists(HttpHeaders.ETAG) }
            jsonPath("$.id") { value("person-facts:Q504") }
            jsonPath("$.status") { value("CANDIDATE") }
        }
        // Hugo was born in Besançon; Napoleon, born in France, died before; Rembrandt is Dutch.
        mockMvc.get("/api/moments/{id}/people", "paris-1870s") { signedIn(); param("limit", "2") }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(3) }
            jsonPath("$.people[0].person") { value("Q535") }
            jsonPath("$.people[0].sitelinks") { value(200) }
            jsonPath("$.people[0].claim") { doesNotExist() }
            jsonPath("$.people[1].person") { value("Q504") }
            jsonPath("$.people[1].claim") { value("CANDIDATE") }
        }
        mockMvc.get("/api/moments/{id}/people", "paris-1870s") { signedIn(); param("offset", "2") }.andExpect {
            jsonPath("$.people[0].person") { value("Q3008") }
        }
        mockMvc.post("/api/people/{qid}/facts", "Q404") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `lists only the claims about a moment's people and its story`() {
        for (qid in listOf("Q504", "Q5598")) mockMvc.post("/api/people/{qid}/facts", qid) { signedIn() }
        mockMvc.get("/api/claims") { signedIn(); param("moment", "paris-1870s") }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value("person-facts:Q504") }
        }
        mockMvc.get("/api/claims") { signedIn() }.andExpect { jsonPath("$.length()") { value(2) } }
    }

    @Test
    fun `a story's preview shows the people, lives and events its cards name, from the release or the dataset`() {
        jdbc.update(
            "insert into dataset.person_article (person, language, title, url, extract, thumbnail_url, image) values " +
                "('Q535', 'en', 'Victor Hugo', 'https://en.wikipedia.org/wiki/Victor_Hugo', ?, null, null), " +
                "('Q535', 'fr', 'Victor Hugo', 'https://fr.wikipedia.org/wiki/Victor_Hugo', 'Victor Hugo est un poète.', 'https://upload.example/hugo.jpg', 'Hugo.jpg')",
            "For the ship, see Victor Hugo (ship).\nVictor Hugo was a French writer. He wrote Les Misérables.",
        )
        mockMvc.get("/api/moments/{id}/preview", "paris-1870s") {
            signedIn()
            param("person", "georges-clemenceau", "Q535", "Q404")
            param("life", "a-washerwoman-on-the-seine")
            param("event", "first-impressionist-exhibition")
        }.andExpect {
            status { isOk() }
            jsonPath("$.art.credit") { value("Gustave Caillebotte, Paris Street; Rainy Day, 1877") }
            jsonPath("$.cardArt.length()") { value(0) }
            // Ordered by id; the Q-id nobody holds is left out.
            jsonPath("$.people.length()") { value(2) }
            jsonPath("$.people[0].id") { value("Q535") }
            jsonPath("$.people[0].name") { value("Victor Hugo") }
            jsonPath("$.people[0].occupation") { value("writer") }
            jsonPath("$.people[0].portrait") { value("https://upload.example/hugo.jpg") }
            jsonPath("$.people[0].bio") { value("Victor Hugo was a French writer. He wrote Les Misérables.") }
            jsonPath("$.people[1].id") { value("georges-clemenceau") }
            jsonPath("$.people[1].born") { value(1841) }
            jsonPath("$.people[1].bio") { value(startsWith("A physician turned politician")) }
            jsonPath("$.lives[0].label") { value("A washerwoman on the Seine") }
            jsonPath("$.lives[0].art.fit") { exists() }
            jsonPath("$.events[0].title") { value("The first Impressionist exhibition") }
            jsonPath("$.events[0].participants.length()") { value(3) }
            jsonPath("$.events[0].participants[0].role") { value("exhibitor") }
        }
        mockMvc.get("/api/moments/{id}/preview", "tokyo-1870s") { signedIn() }.andExpect {
            jsonPath("$.cardArt.length()") { value(1) }
            jsonPath("$.cardArt[0].type") { value("EVENT") }
            jsonPath("$.cardArt[0].event") { value("first-railway") }
            jsonPath("$.people.length()") { value(0) }
        }
        mockMvc.get("/api/moments/{id}/preview", "atlantis-0") { signedIn() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `drafting a moment's story starts the run its page then shows`() {
        mockMvc.post("/api/moments/{id}/story", "atlantis-0") { signedIn() }.andExpect { status { isNotFound() } }
        val run = mockMvc.post("/api/moments/{id}/story", "paris-1870s") { signedIn() }.andExpect {
            status { isAccepted() }
            jsonPath("$.kind") { value("STORY_DRAFT") }
            jsonPath("$.moment") { value("paris-1870s") }
        }.andReturn().response.contentAsString
        mockMvc.get("/api/moments/{id}", "paris-1870s") { signedIn() }.andExpect {
            jsonPath("$.draft.id") { value(Regex("\"id\":(\\d+)").find(run)!!.groupValues[1].toInt()) }
        }
        mockMvc.get("/api/moments/{id}", "paris-1890s") { signedIn() }.andExpect { jsonPath("$.draft") { doesNotExist() } }
    }
}
