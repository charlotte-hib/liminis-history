package art.liminis.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Path

/**
 * Postgres for any test that needs the database: `@Import(PostgresTestConfiguration::class)`. Testcontainers starts
 * it, once per Spring test context, and Spring Boot connects to it. It is the db service of the repository's
 * `docker-compose.yml`, same image and same collation, so the tests run on the Postgres the site runs on.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer {
        val db = YAMLMapper().readTree(compose.toFile()).path("services").path("db")
        // Testcontainers only accepts images it knows as Postgres, and does not recognise a name with both a tag and a
        // digest ("postgres:18.6-trixie@sha256:...") as one: say it is.
        val image = DockerImageName.parse(db.path("image").asString()).asCompatibleSubstituteFor("postgres")
        return PostgreSQLContainer(image)
            .withEnv("POSTGRES_INITDB_ARGS", db.path("environment").path("POSTGRES_INITDB_ARGS").asString())
    }

    private companion object {
        val compose: Path = Path.of(System.getProperty("compose.file") ?: error("Run with Gradle, which sets compose.file"))
    }
}
