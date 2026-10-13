package art.liminis.schema

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the site's API may do in a release schema, as the reader role core's migrations grant to: read everything,
 * fill in the Wikipedia fields of people, and nothing else, whatever its code tries.
 */
class ReaderPrivilegesTests {

    private val reader = JdbcTemplate(DriverManagerDataSource("${postgres.jdbcUrl}&currentSchema=$SCHEMA", "site_reader", "reader"))

    @Test
    fun `the reader reads every table`() {
        assertEquals(listOf("Émile Zola"), reader.queryForList("select name from person", String::class.java))
        assertEquals(0, reader.queryForObject("select count(*) from story_card", Int::class.java))
    }

    @Test
    fun `the reader fills in a person's bio and portrait`() {
        assertEquals(1, reader.update("update person set bio_short = 'Novelist.', portrait_url = 'https://example.org/z.jpg'"))
    }

    @Test
    fun `the reader cannot change the facts`() {
        denied { reader.update("update person set name = 'Zola'") }
        denied { reader.update("insert into region values ('JP', 'Japan')") }
        denied { reader.update("delete from person") }
        denied { reader.execute("truncate table region cascade") }
    }

    @Test
    fun `the reader cannot change the schema`() {
        denied { reader.execute("create table note (text varchar(255))") }
        denied { reader.execute("drop table door_face") }
    }

    private fun denied(statement: () -> Any?) {
        val error = assertFailsWith<DataAccessException> { statement() }
        assertContains(error.mostSpecificCause.message.orEmpty(), Regex("permission denied|must be owner"))
    }

    companion object {
        private const val SCHEMA = "release_test"
        private val postgres = PostgresTestConfiguration().postgres()

        @JvmStatic
        @BeforeAll
        fun migrate() {
            postgres.start()
            val owner = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            owner.execute("create role site_reader login password 'reader'")
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .placeholders(mapOf("reader" to "site_reader"))
                .load().migrate()
            owner.execute("insert into $SCHEMA.region values ('FR', 'France')")
            owner.execute(
                "insert into $SCHEMA.person (id, slug, name, birth_year, death_year, dates_approximate, region_code, domain, occupations, sitelinks, fame) " +
                    "values ('zola', 'emile-zola', 'Émile Zola', 1840, 1902, false, 'FR', 'ARTS', '{writer}', 120, 99)"
            )
        }

        @JvmStatic
        @AfterAll
        fun stop() = postgres.stop()
    }
}
