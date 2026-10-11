package dev.wholivedwhen.curation

import org.flywaydb.core.Flyway
import org.hibernate.cfg.MappingSettings
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource

/**
 * The workbench's schema `curation`: claims, their sources and the decisions taken on them. Its own migrations
 * (`db/curation`). Never dropped: this is the curator's work, the one part that cannot be fetched again.
 */
@Component
class CurationSchema(dataSource: DataSource) {

    init {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/curation")
            .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
            .load().migrate()
    }

    private companion object {
        const val SCHEMA = "curation"
    }
}

@Configuration(proxyBeanMethods = false)
class CurationSchemaConfiguration {

    /** Hibernate checks the entities against their tables on start: the curation ones must be there by then. */
    @Bean
    fun entityManagerFactoryAfterCurationSchema() = EntityManagerFactoryDependsOnPostProcessor(CurationSchema::class.java)

    /** JSON columns written as the API writes them, with Spring's mapper: nulls left out, Kotlin's classes understood. */
    @Bean
    fun curationJson(jsonMapper: JsonMapper) =
        HibernatePropertiesCustomizer { it[MappingSettings.JSON_FORMAT_MAPPER] = Jackson3JsonFormatMapper(jsonMapper) }
}
