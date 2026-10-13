package art.liminis.repository

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import art.liminis.domain.Domain
import art.liminis.domain.Era
import art.liminis.domain.Life
import art.liminis.domain.Person
import art.liminis.domain.Region
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals

/** The year-range queries behind "who lived when". Both ends of a lifespan are inclusive; no death year means alive. */
@DataJpaTest
@Import(PostgresTestConfiguration::class)
class LifespanQueryTests(
    @Autowired private val entities: TestEntityManager,
    @Autowired private val people: PersonRepository,
    @Autowired private val lives: LifeRepository,
) {

    @BeforeEach
    fun persistTwoRegions() {
        val france = entities.persist(Region("FR", "France"))
        val japan = entities.persist(Region("JP", "Japan"))
        val republic = entities.persist(Era("fr-third-republic", france, "Third Republic", "Third Republic", 1870, 1940))

        entities.persist(person("dies-the-year-it-starts", france, 1800, 1870))
        entities.persist(person("born-the-year-it-ends", france, 1880, 1950))
        entities.persist(person("dies-the-year-before", france, 1800, 1869))
        entities.persist(person("born-the-year-after", france, 1881, 1950))
        entities.persist(person("still-alive", france, 1990, null))
        entities.persist(person("abroad", japan, 1850, 1900))

        entities.persist(Life("ends-the-year-it-starts", republic, "A", "", 1850, 1870))
        entities.persist(Life("starts-the-year-it-ends", republic, "B", "", 1880, 1900))
        entities.persist(Life("ends-the-year-before", republic, "C", "", 1850, 1869))
    }

    @Test
    fun `people alive at either end of a span are in it`() {
        val slugs = people.findAliveBetween("FR", 1870, 1880).map { it.slug }

        assertEquals(listOf("dies-the-year-it-starts", "born-the-year-it-ends"), slugs)
    }

    @Test
    fun `people with no death year are alive today`() {
        assertEquals(listOf("still-alive"), people.findAliveBetween("FR", 2020, 2030).map { it.slug })
    }

    @Test
    fun `alive elsewhere leaves out the region asked about`() {
        assertEquals(listOf("abroad"), people.findAliveElsewhere("FR", 1870).map { it.slug })
        assertEquals(listOf("dies-the-year-it-starts"), people.findAliveElsewhere("JP", 1870).map { it.slug })
    }

    @Test
    fun `people born the same year are listed by id, whatever order they were stored in`() {
        val france = entities.find(Region::class.java, "FR")!!
        entities.persist(person("twin-b", france, 1700, 1760))
        entities.persist(person("twin-a", france, 1700, 1760))

        assertEquals(listOf("twin-a", "twin-b"), people.findAliveBetween("FR", 1750, 1750).map { it.slug })
        assertEquals(listOf("twin-a", "twin-b"), people.findAliveElsewhere("JP", 1750).map { it.slug })
    }

    @Test
    fun `lives touching either end of a span are in it`() {
        val ids = lives.findLivedBetween("FR", 1870, 1880).map { it.id }

        assertEquals(listOf("ends-the-year-it-starts", "starts-the-year-it-ends"), ids)
    }

    private fun person(slug: String, region: Region, born: Int, died: Int?) =
        Person(slug, null, slug, slug, born, died, false, region, Domain.EVERYDAY, emptyList(), 0, 0, null)
}
