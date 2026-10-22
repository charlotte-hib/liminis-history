package art.liminis.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import art.liminis.testing.PostgresTestConfiguration

/** Runs against the release in the repository's `sample/`. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class PersonApiTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `a person is shown in the world around them in the year asked for`() {
        mockMvc.get("/api/people/emile-zola?year=1875").andExpect {
            status { isOk() }
            jsonPath("$.year") { value(1875) }
            jsonPath("$.age") { value(35) }
            jsonPath("$.worldMoment.id") { value("paris-1870s") }
            jsonPath("$.world.arts") { exists() }
            jsonPath("$.aroundPeople[?(@.slug == 'victor-hugo')]") { exists() }
            jsonPath("$.aroundPeople[?(@.slug == 'emile-zola')]") { doesNotExist() }
            jsonPath("$.aroundPeople.length()") { value(12) }
            jsonPath("$.person.occupations[0]") { value("novelist") }
            jsonPath("$.elsewhere[*].regionCode") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("FR"))) }
            jsonPath("$.moments[*].id") { value(org.hamcrest.Matchers.hasItems("paris-1870s", "paris-1890s")) }
        }
    }

    @Test
    fun `their life in their time lists regimes and events with their age`() {
        mockMvc.get("/api/people/emile-zola").andExpect {
            jsonPath("$.lifeline[0].text") { value("Born under the July Monarchy") }
            jsonPath("$.lifeline[0].era.label") { value("July Monarchy") }
            jsonPath("$.lifeline[?(@.text == 'The Third Republic begins')].era.id") { value("fr-third-republic") }
            jsonPath("$.lifeline[?(@.kind == 'EVENT' && @.era != null)]") { isEmpty() }
            jsonPath("$.lifeline[?(@.text == 'The Third Republic begins')].age") { value(30) }
            jsonPath("$.lifeline[?(@.kind == 'OWN_EVENT' && @.year == 1898)].role") { value("author") }
            jsonPath("$.lifeline[-1].kind") { value("DEATH") }
        }
    }

    @Test
    fun `in French, a person written in French comes back in French, their connections and events too`() {
        mockMvc.get("/api/people/emile-zola?year=1875&lang=fr").andExpect {
            status { isOk() }
            jsonPath("$.person.lang") { value("fr") }
            jsonPath("$.person.occupations[0]") { value("romancier") }
            jsonPath("$.person.bioShort") { value("Il bâtit les vingt romans des Rougon-Macquart au fil des années 1870 et 1880.") }
            jsonPath("$.person.bioLang") { value("fr") }
            jsonPath("$.person.region") { value("France") }
            jsonPath("$.connections[?(@.person.slug == 'paul-cezanne')].kind") { value("amis d’école") }
            jsonPath("$.connections[?(@.person.slug == 'paul-cezanne')].lang") { value("fr") }
            jsonPath("$.connections[?(@.person.slug == 'edouard-manet')].lang") { value("en") }
            jsonPath("$.lifeline[?(@.kind == 'BIRTH')].era.label") { value("Monarchie de Juillet") }
            jsonPath("$.worldMoment.period") { value("années 1870") }
        }
        // Someone with no French in the release keeps their English name, with their French article's intro.
        mockMvc.get("/api/people/ada-lovelace?lang=fr").andExpect {
            jsonPath("$.person.lang") { value("en") }
            jsonPath("$.person.occupations[0]") { value("mathematician") }
            jsonPath("$.aboutLang") { value("fr") }
            jsonPath("$.wikipediaUrl") { value(org.hamcrest.Matchers.startsWith("https://fr.wikipedia.org/")) }
        }
        mockMvc.get("/api/people/ada-lovelace").andExpect {
            jsonPath("$.aboutLang") { value("en") }
            jsonPath("$.wikipediaUrl") { value(org.hamcrest.Matchers.startsWith("https://en.wikipedia.org/")) }
        }
    }

    @Test
    fun `in French, moments are searched in French`() {
        mockMvc.get("/api/search?q=années 1870&lang=fr").andExpect {
            jsonPath("$.moments[0].id") { value("paris-1870s") }
        }
    }

    @Test
    fun `without a year a person is shown in their prime`() {
        mockMvc.get("/api/people/emile-zola?year=1950").andExpect {
            jsonPath("$.year") { value(1875) }
        }
    }

    @Test
    fun `meanwhile lists people alive elsewhere that year`() {
        mockMvc.get("/api/years/1875/people?exclude=FR").andExpect {
            status { isOk() }
            jsonPath("$[?(@.regionCode == 'JP')]") { exists() }
            jsonPath("$[?(@.regionCode == 'FR')]") { isEmpty() }
        }
    }

    @Test
    fun `parameters outside the spec's constraints are refused`() {
        listOf(
            "/api/years/1875/people?exclude=fr",
            "/api/years/1875/people?exclude=FRA",
            "/api/years/1875/people?exclude=FR&perRegion=0",
            "/api/years/1875/people?exclude=FR&perRegion=6",
            "/api/years/1875/people",
            "/api/years/later/people?exclude=FR",
            "/api/search?q=${"a".repeat(101)}",
        ).forEach { path ->
            mockMvc.get(path).andExpect {
                status { isBadRequest() }
                content { contentType("application/problem+json") }
                jsonPath("$.status") { value(400) }
            }
        }
    }

    @Test
    fun `search ignores accents and finds people and moments`() {
        mockMvc.get("/api/search?q=zola").andExpect {
            jsonPath("$.people[0].slug") { value("emile-zola") }
        }
        mockMvc.get("/api/search?q=kyoto").andExpect {
            jsonPath("$.moments[0].id") { value("kyoto-1590s") }
        }
    }

    @Test
    fun `search forgives typos when too few names contain the query`() {
        mockMvc.get("/api/search?q=cezane").andExpect {
            jsonPath("$.people[0].slug") { value("paul-cezanne") }
        }
        mockMvc.get("/api/search?q=Monett").andExpect {
            jsonPath("$.people[0].slug") { value("claude-monet") }
        }
    }

    @Test
    fun `search lists names that start with the query first, then in order of birth`() {
        mockMvc.get("/api/search?q=napoleon").andExpect {
            jsonPath("$.people[0].name") { value("Napoleon") }
            jsonPath("$.people[1].name") { value("Napoleon III") }
        }
        mockMvc.get("/api/search?q=louis").andExpect {
            jsonPath("$.people[0].name") { value("Louis IX") }
            jsonPath("$.people[1].name") { value("Louis XIV") }
        }
    }

    @Test
    fun `search takes like's wildcards literally`() {
        listOf("%%", "__", "\\%").forEach { q ->
            mockMvc.get("/api/search") { param("q", q) }.andExpect {
                status { isOk() }
                jsonPath("$.people.length()") { value(0) }
            }
        }
    }

    @Test
    fun `a person's connections are curated links first, then shared events, in order of year`() {
        mockMvc.get("/api/people/emile-zola").andExpect {
            status { isOk() }
            jsonPath("$.connections[0].person.slug") { value("paul-cezanne") }
            jsonPath("$.connections[0].kind") { value("school friends") }
            jsonPath("$.connections.length()") { value(4) }
            jsonPath("$.connections[3].person.slug") { value("georges-clemenceau") }
            jsonPath("$.connections[3].kind") { value("chose the headline") }
        }
    }

    @Test
    fun `an era only lists people from its own region`() {
        mockMvc.get("/api/eras/jp-meiji-era").andExpect {
            status { isOk() }
            jsonPath("$.people.POWER[?(@.slug == 'emperor-meiji')]") { exists() }
            jsonPath("$.people.ARTS[?(@.slug == 'claude-monet')]") { doesNotExist() }
        }
    }

    @Test
    fun `an era lists the most written about of each domain, up to 12`() {
        mockMvc.get("/api/eras/fr-third-republic").andExpect {
            jsonPath("$.people.ARTS.length()") { value(12) }
        }
    }

    @Test
    fun `regions are those with eras, not every country people come from`() {
        mockMvc.get("/api/regions").andExpect {
            jsonPath("$[*].code") { value(org.hamcrest.Matchers.contains("GB", "CN", "FR", "JP")) }
        }
    }

    @Test
    fun `an era lists the moments set under it`() {
        mockMvc.get("/api/eras/jp-meiji-era").andExpect {
            jsonPath("$.moments.length()") { value(1) }
            jsonPath("$.moments[0].id") { value("tokyo-1870s") }
        }
    }
}
