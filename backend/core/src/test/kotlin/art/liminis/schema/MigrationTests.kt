package art.liminis.schema

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** core's migrations (db/migration) on an empty database; Hibernate then checks the entities against the schema. */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
@Import(PostgresTestConfiguration::class)
class MigrationTests(@Autowired private val flyway: Flyway) {

    @Test
    fun `every migration applies to an empty database, and the entities match the result`() {
        val info = flyway.info()

        assertTrue(info.all().isNotEmpty())
        assertEquals(info.all().map { it.version }, info.applied().map { it.version })
    }
}
