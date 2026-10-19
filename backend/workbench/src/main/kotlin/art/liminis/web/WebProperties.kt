package art.liminis.web

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.web")
data class WebProperties(
    /** The names a request may be addressed to (its Host): any other is turned away. */
    val allowedHosts: Set<String>,
)
