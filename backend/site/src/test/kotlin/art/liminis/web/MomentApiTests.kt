package art.liminis.web

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against the release in the repository's `sample/`. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class MomentApiTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `lists moments with who governed, the story length and its cast`() {
        mockMvc.get("/api/moments").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(10) }
            jsonPath("$[?(@.id == 'paris-1870s')].governedBy") { value("Third Republic") }
            jsonPath("$[?(@.id == 'paris-1870s')].storyCards") { value(7) }
            jsonPath("$[?(@.id == 'paris-1870s')].cast[0].slug") { value("georges-clemenceau") }
            jsonPath("$[?(@.id == 'paris-1870s')].cast[1].slug") { value("berthe-morisot") }
            jsonPath("$[?(@.id == 'edo-1830s')].storyCards") { value(0) }
        }
    }

    @Test
    fun `a moment has the world around it, its people in its focus year, and its doors`() {
        mockMvc.get("/api/moments/paris-1870s").andExpect {
            status { isOk() }
            jsonPath("$.world.governs") { exists() }
            jsonPath("$.eras[?(@.id == 'fr-third-republic')]") { exists() }
            jsonPath("$.people.year") { value(1875) }
            jsonPath("$.people.draw") { value(0) }
            jsonPath("$.people.people.length()") { value(24) }
            jsonPath("$.people.people[?(@.slug == 'victor-hugo')]") { exists() }
            jsonPath("$.people.people[?(@.slug == 'emperor-meiji')]") { doesNotExist() }
            jsonPath("$.lives[?(@.id == 'a-washerwoman-on-the-seine')]") { exists() }
            jsonPath("$.events[?(@.id == 'carmen-premiere')].year") { value(1875) }
            jsonPath("$.doors[0].target.id") { value("tokyo-1870s") }
            jsonPath("$.doors[1].faces[0].slug") { value("emile-zola") }
            jsonPath("$.doors[1].faces[1].slug") { value("georges-clemenceau") }
        }
    }

    @Test
    fun `a moment's people come in draws, the related first, then others, nobody twice before everyone is shown`() {
        val draws = (0..2).map { draw ->
            mockMvc.get("/api/moments/paris-1870s/people?year=1875&draw=$draw").andExpect { status { isOk() } }
                .andReturn().response.contentAsString.let { JsonPath.read<List<String>>(it, "$.people[*].slug") }
        }
        val count = JsonPath.read<Int>(mockMvc.get("/api/moments/paris-1870s/people?year=1875").andReturn().response.contentAsString, "$.draws")

        // Clemenceau is in the story, Zola in curated connections: both rank before anyone more written about.
        assertTrue(draws[0].containsAll(listOf("georges-clemenceau", "emile-zola")))
        assertEquals(2, count)
        assertTrue(draws[1].none { it in draws[0] })
        assertEquals(draws[0], draws[2], "the draw after the last starts over")
    }

    @Test
    fun `a moment's people need a year and a draw from 0, of a moment that exists`() {
        mockMvc.get("/api/moments/paris-1870s/people").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/moments/paris-1870s/people?year=1875&draw=-1").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/moments/atlantis/people?year=1875").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a moment without a written story suggests the nearest moments as doors`() {
        mockMvc.get("/api/moments/edo-1830s").andExpect {
            status { isOk() }
            jsonPath("$.doors[0].kind") { value("Later, same place") }
            jsonPath("$.doors[0].target.id") { value("tokyo-1870s") }
            jsonPath("$.doors[2].kind") { value("Earlier, same place") }
        }
    }

    @Test
    fun `a story is its cards in order, then its doors`() {
        mockMvc.get("/api/moments/paris-1870s/story").andExpect {
            status { isOk() }
            jsonPath("$.cards[0].type") { value("SCENE") }
            jsonPath("$.cards[2].person.slug") { value("georges-clemenceau") }
            jsonPath("$.cards[4].life.id") { value("a-washerwoman-on-the-seine") }
            jsonPath("$.cards[5].event.participants.length()") { value(3) }
            jsonPath("$.cards[5].event.participants[0].person.slug") { isString() }
            jsonPath("$.cards[5].event.participants[0].role") { isString() }
            jsonPath("$.doors.length()") { value(3) }
        }
    }

    @Test
    fun `a card can carry its own artwork`() {
        mockMvc.get("/api/moments/tokyo-1870s/story").andExpect {
            jsonPath("$.cards[5].art.credit") { value("Utagawa Hiroshige III, steam train at Yokohama, 1874") }
        }
    }

    @Test
    fun `missing stories and unknown moments are 404s`() {
        mockMvc.get("/api/moments/edo-1830s/story").andExpect { status { isNotFound() } }
        mockMvc.get("/api/moments/atlantis").andExpect {
            status { isNotFound() }
            content { contentType("application/problem+json") }
            jsonPath("$.type") { doesNotExist() }
            jsonPath("$.title") { value("Not Found") }
            jsonPath("$.status") { value(404) }
            jsonPath("$.detail") { value("Unknown moment: atlantis") }
            jsonPath("$.instance") { value("/api/moments/atlantis") }
        }
    }
}
