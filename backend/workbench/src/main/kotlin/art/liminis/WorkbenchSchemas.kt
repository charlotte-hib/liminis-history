package art.liminis

import org.flywaydb.core.Flyway
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * The workbench's own schemas, each with its own migrations (`db/<schema>`), apart from the release schema that core's
 * Flyway builds again on every start: `raw`, what it fetched from Wikimedia; `dataset`, the rows mapped from `raw`,
 * emptied by every mapping run; `curation`, the curator's claims and decisions, the one part that cannot be fetched
 * again. Migrated on start, in that order, before any store uses them.
 */
@Component
class WorkbenchSchemas(dataSource: DataSource) {

    init {
        for (schema in SCHEMAS) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/$schema")
                .schemas(schema).defaultSchema(schema).createSchemas(true)
                .load().migrate()
        }
    }

    private companion object {
        val SCHEMAS = listOf("raw", "dataset", "curation")
    }
}

@Configuration(proxyBeanMethods = false)
class WorkbenchSchemasConfiguration {

    /** Hibernate checks the curation entities against their tables on start: the tables must be there by then. */
    @Bean
    fun entityManagerFactoryAfterWorkbenchSchemas() = EntityManagerFactoryDependsOnPostProcessor(WorkbenchSchemas::class.java)
}
