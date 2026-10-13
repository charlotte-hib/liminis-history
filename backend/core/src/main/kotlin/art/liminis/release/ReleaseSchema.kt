package art.liminis.release

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.migration.Context
import org.flywaydb.core.api.migration.JavaMigration
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * [dir] is a release directory, in the format of the repository's `sample/`. [id] names the database schema it is
 * loaded into: the deployed commit, in production. Without one (local runs, tests), the schema is `release_local`,
 * built again on every start.
 */
@ConfigurationProperties("app.release")
data class ReleaseProperties(val dir: Path, val id: String? = null) {

    val local get() = id.isNullOrBlank()

    val schema: String
        get() {
            if (local) return "release_local"
            require(id!!.matches(Regex("[0-9a-f]{12,40}"))) { "app.release.id must be a commit hash, not $id" }
            return "release_" + id.take(12)
        }
}

/**
 * Each release lives in a schema of its own, which Flyway creates, migrates and loads once: core's SQL migrations,
 * then [LoadRelease]. A restart, or a rollback to a release still kept, finds its schema loaded and starts at once.
 * Hibernate then works in that schema. Flyway runs as the schemas' owner; the app may use a role that only reads
 * (`app.release.reader`, granted by the migrations).
 */
@Configuration(proxyBeanMethods = false)
class ReleaseSchemaConfiguration(private val release: ReleaseProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun releaseSchemaFlyway() = FlywayConfigurationCustomizer { flyway ->
        flyway.schemas(release.schema).defaultSchema(release.schema).createSchemas(true)
            // Local schemas are built again on every start: dropping them must be allowed there, and only there.
            .cleanDisabled(!release.local)
            // The role the migrations grant reading to. Locally the app and Flyway are one user.
            .placeholders(mapOf("reader" to "current_user") + flyway.placeholders)
    }

    @Bean
    fun releaseSchemaHibernate() = HibernatePropertiesCustomizer { it["hibernate.default_schema"] = release.schema }

    @Bean
    fun releaseSchemaMigration() = FlywayMigrationStrategy { flyway ->
        if (release.local) flyway.clean()
        flyway.migrate()
        dropOldSchemas(flyway)
    }

    /** Keeps the current schema and the two newest others, for rollbacks; drops the rest of the owner's release schemas. */
    private fun dropOldSchemas(flyway: Flyway) {
        val jdbc = JdbcTemplate(flyway.configuration.dataSource)
        val others = jdbc.queryForList(
            """
            select nspname from pg_namespace
            where nspname like 'release\_%' and nspname <> ? and pg_get_userbyid(nspowner) = current_user
            """.trimIndent(),
            String::class.java,
            release.schema,
        )
        // When each was loaded: its first migration. A schema without a history table is the oldest.
        val newestFirst = others.sortedByDescending { schema ->
            jdbc.queryForList("""select min(installed_on) from "$schema".flyway_schema_history""", java.sql.Timestamp::class.java)
                .firstOrNull()?.time ?: 0L
        }
        newestFirst.drop(KEPT_BESIDES_CURRENT).forEach { schema ->
            jdbc.execute("""drop schema "$schema" cascade""")
            log.info("Dropped the old release schema {}", schema)
        }
    }

    private companion object {
        const val KEPT_BESIDES_CURRENT = 2
    }
}

/**
 * The last migration of every release schema: compiles the release in `app.release.dir` and inserts it, in batches,
 * in the migration's transaction. Its version comes after any SQL migration's: a schema belongs to one commit, so no
 * migration is ever added to it later.
 */
@Component
class LoadRelease(private val release: ReleaseProperties) : JavaMigration {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getVersion(): MigrationVersion = MigrationVersion.fromVersion("999")
    override fun getDescription() = "load the release"
    override fun getChecksum(): Int? = null
    override fun canExecuteInTransaction() = true

    override fun migrate(context: Context) {
        val compiled = ReleaseCompiler.compile(ReleaseReader.read(release.dir))
        val rows = rowMapper.rows(compiled)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(context.connection, true))
        val schema = context.configuration.defaultSchema

        rows.tables.forEach { (table, tableRows) ->
            if (tableRows.isEmpty()) return@forEach
            val sources = tableRows.map(::RowParameters)
            // The rows' columns rather than the table's: generated ones (person's search_name) take no value.
            val columns = sources.first().parameterNames.filter { it != "class" }.map(JdbcUtils::convertPropertyNameToUnderscoreName)
            SimpleJdbcInsert(jdbc).withSchemaName(schema).withTableName(table).usingColumns(*columns.toTypedArray())
                .executeBatch(*sources.toTypedArray())
        }
        // Hibernate takes its ids 50 at a time from these: start them after the ids given here.
        for (table in listOf("event_participant", "connection", "story_card", "door")) {
            jdbc.queryForObject("""select setval('"$schema".${table}_seq', greatest(max(id), 1)) from "$schema".$table""", Long::class.java)
        }
        log.info("Loaded the release in {} into {}: {} people, {} moments", release.dir, schema, rows.count("person"), rows.count("moment"))
    }

    /** A row's properties as parameters; lists become arrays (person's occupations). */
    private class RowParameters(row: Any) : BeanPropertySqlParameterSource(row) {
        override fun getValue(paramName: String): Any? =
            when (val value = super.getValue(paramName)) {
                is List<*> -> SqlArrayValue("varchar", *value.filterNotNull().toTypedArray())
                else -> value
            }
    }

    private companion object {
        val rowMapper: ReleaseRowMapper = org.mapstruct.factory.Mappers.getMapper(ReleaseRowMapper::class.java)
    }
}
