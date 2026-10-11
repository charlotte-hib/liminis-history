package dev.wholivedwhen.curation

import org.hibernate.cfg.MappingSettings
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
class CurationJson {

    /** JSON columns written with Spring's mapper: nulls left out, Kotlin's classes understood. */
    @Bean
    fun curationJsonFormat(jsonMapper: JsonMapper) =
        HibernatePropertiesCustomizer { it[MappingSettings.JSON_FORMAT_MAPPER] = Jackson3JsonFormatMapper(jsonMapper) }
}
