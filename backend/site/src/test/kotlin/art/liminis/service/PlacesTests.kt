package art.liminis.service

import org.junit.jupiter.api.Test
import art.liminis.domain.Domain
import art.liminis.domain.Language
import art.liminis.domain.Person
import art.liminis.domain.PersonPlace
import art.liminis.domain.PlaceKind
import art.liminis.domain.Region
import art.liminis.api.model.LanguageDto
import art.liminis.api.model.PlaceKindDto
import art.liminis.api.model.PlaceRoleDto
import kotlin.test.assertEquals

class PlacesTests {

    private val france = Region("FR", "France")
    private val monet = Person("claude-monet", "Q296", "claude-monet", "Claude Monet", 1840, 1926, false, france, Domain.ARTS, listOf("painter"), 300, 100, null)
        .apply {
            places += listOf(
                place("Q90", "Paris", PlaceKind.BIRTH),
                place("Q165061", "Giverny", PlaceKind.DEATH),
                place("Q90", "Paris", PlaceKind.WORK),
                place("Q84", "London", PlaceKind.WORK, region = "GB", nameFr = "Londres"),
                place("Q165061", "Giverny", PlaceKind.WORK),
                place("Q165061", "Giverny", PlaceKind.RESIDENCE, 1883, 1926),
                place("Q181946", "Argenteuil", PlaceKind.RESIDENCE, end = 1878),
                // Wikidata states some places twice, and two items can share a name.
                place("Q181946", "Argenteuil", PlaceKind.RESIDENCE, end = 1878),
                place("Q999", "Argenteuil", PlaceKind.WORK),
            )
        }
    private val country = { code: String -> mapOf("FR" to "France", "GB" to "United Kingdom")[code] }

    @Test
    fun `a place is one line with all its roles, in the order they were there, undated places last`() {
        val lines = placesOf(monet, Language.EN, country)
        assertEquals(listOf("Paris", "Argenteuil", "Giverny", "London"), lines.map { it.name })
        assertEquals(
            listOf(PlaceRoleDto(PlaceKindDto.BIRTH, 1840, 1840), PlaceRoleDto(PlaceKindDto.WORK, null, null)),
            lines[0].roles,
        )
        assertEquals(listOf(PlaceKindDto.WORK, PlaceKindDto.RESIDENCE), lines[1].roles.map { it.kind })
        assertEquals(listOf(PlaceKindDto.WORK, PlaceKindDto.RESIDENCE, PlaceKindDto.DEATH), lines[2].roles.map { it.kind })
        assertEquals(PlaceRoleDto(PlaceKindDto.DEATH, 1926, 1926), lines[2].roles.last())
        assertEquals("United Kingdom", lines[3].country)
    }

    @Test
    fun `in French, a place takes its French name where Wikidata has one`() {
        val london = placesOf(monet, Language.FR, country).single { it.name == "Londres" }
        assertEquals(LanguageDto.fr, london.lang)
        assertEquals(LanguageDto.en, placesOf(monet, Language.FR, country).first().lang)
    }

    @Test
    fun `a merged place takes the French name any of its items has`() {
        val two = person(place("Q1", "Argenteuil", PlaceKind.WORK), place("Q2", "Argenteuil", PlaceKind.RESIDENCE, nameFr = "Argenteuil-sur-Seine"))
        assertEquals("Argenteuil-sur-Seine", placesOf(two, Language.FR, country).single().name)
    }

    @Test
    fun `a death place with no death year is undated, and BCE years order like the others`() {
        val alive = person(place("Q3", "Thebes", PlaceKind.DEATH), place("Q4", "Memphis", PlaceKind.RESIDENCE, -1200, -1150), born = -1250, died = null)
        val lines = placesOf(alive, Language.EN, country)
        assertEquals(listOf("Memphis", "Thebes"), lines.map { it.name })
        assertEquals(PlaceRoleDto(PlaceKindDto.DEATH, null, null), lines[1].roles.single())
    }

    private fun person(vararg places: PersonPlace, born: Int = 1840, died: Int? = 1926) =
        Person("p", "Q0", "p", "P", born, died, false, france, Domain.ARTS, listOf("painter"), 1, 1, null).apply { this.places += places }

    private fun place(qid: String, name: String, kind: PlaceKind, start: Int? = null, end: Int? = null, region: String = "FR", nameFr: String? = null) =
        PersonPlace(qid, name, nameFr, kind, region, start, end)
}
