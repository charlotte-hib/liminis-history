package art.liminis.repository

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import art.liminis.domain.Era
import art.liminis.domain.Life
import art.liminis.domain.Region
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals

/** The year-range queries: which typical lives were lived when. Both ends of a span are inclusive. */
@DataJpaTest
@Import(PostgresTestConfiguration::class)
class LifespanQueryTests(
    @Autowired private val entities: TestEntityManager,
    @Autowired private val lives: LifeRepository,
) {

    @BeforeEach
    fun persistLives() {
        val france = entities.persist(Region("FR", "France"))
        val republic = entities.persist(Era("fr-third-republic", france, "Third Republic", "Third Republic", 1870, 1940))

        entities.persist(Life("ends-the-year-it-starts", republic, "A", "", 1850, 1870))
        entities.persist(Life("starts-the-year-it-ends", republic, "B", "", 1880, 1900))
        entities.persist(Life("ends-the-year-before", republic, "C", "", 1850, 1869))
    }

    @Test
    fun `lives touching either end of a span are in it`() {
        val ids = lives.findLivedBetween("FR", 1870, 1880).map { it.id }

        assertEquals(listOf("ends-the-year-it-starts", "starts-the-year-it-ends"), ids)
    }
}
