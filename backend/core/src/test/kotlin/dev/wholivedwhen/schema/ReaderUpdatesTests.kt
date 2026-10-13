package dev.wholivedwhen.schema

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.repository.PersonRepository
import dev.wholivedwhen.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * JPA as the site's API runs it: Flyway as the owner, Hibernate as the reader role. Filling in a person's Wikipedia
 * fields, as WikipediaJob does, must only update those columns.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReaderUpdatesTests(@Autowired private val people: PersonRepository) {

    @Test
    fun `the reader saves a person's bio and portrait`() {
        val zola = people.findBySlug("emile-zola")!!
        zola.bioShort = "Novelist."
        zola.portraitUrl = "https://example.org/zola.jpg"
        people.saveAndFlush(zola)
    }

    @Test
    fun `the reader cannot save any other change`() {
        val zola = people.findBySlug("emile-zola")!!
        val renamed = Person(zola.id, zola.qid, zola.slug, "Zola", zola.birthYear, zola.deathYear, zola.datesApproximate, zola.region,
            zola.domain, zola.occupations, zola.sitelinks, zola.fame, zola.wikipediaTitle)

        assertFailsWith<DataAccessException> { people.saveAndFlush(renamed) }
    }

    @Test
    fun `the reader searches people's names`() {
        assertEquals(listOf("emile-zola"), people.findNameContaining("ÉMILE", 6).map { it.slug })
        assertEquals(listOf("emile-zola"), people.findNameSimilar("Émille", 6).map { it.slug })
    }

    companion object {
        private const val SCHEMA = "release_test"
        private val postgres = PostgresTestConfiguration().postgres().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val owner = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            owner.execute("create role site_reader login password 'reader'")
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { "site_reader" }
            registry.add("spring.datasource.password") { "reader" }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
            registry.add("spring.flyway.schemas") { SCHEMA }
            registry.add("spring.flyway.default-schema") { SCHEMA }
            registry.add("spring.flyway.placeholders.reader") { "site_reader" }
            registry.add("spring.jpa.properties.hibernate.default_schema") { SCHEMA }
            // core's migrations, then a person to update, written as the owner (db/reader-test).
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/reader-test" }
        }
    }
}
