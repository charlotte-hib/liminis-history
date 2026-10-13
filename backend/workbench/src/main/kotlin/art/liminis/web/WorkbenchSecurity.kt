package art.liminis.web

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@ConfigurationProperties("app.workbench")
data class WorkbenchProperties(
    /** Where the workbench writes its token on every start, readable by the curator only. */
    val tokenFile: Path,
)

/**
 * The token every request needs until the authorization server (`auth`) issues JWTs: random, new on every start, and
 * written to a file only the curator can read, for `wb` and `curate` to send. A web page open in the curator's browser
 * can send requests to localhost, but it cannot read the file.
 */
@Component
class WorkbenchToken(properties: WorkbenchProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val value = ByteArray(32).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    init {
        val file = properties.tokenFile.toAbsolutePath()
        Files.createDirectories(file.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.deleteIfExists(file)
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, value)
        log.info("Wrote the workbench's token to {}", file)
    }

    fun matches(candidate: String): Boolean = MessageDigest.isEqual(candidate.toByteArray(), value.toByteArray())
}

/**
 * Every request needs the workbench's token as a bearer token, and must be addressed to localhost: a page that
 * rebinds its own domain to 127.0.0.1 still sends its own name. No sessions, no cookies, so no CSRF to guard against.
 */
@Configuration
class WorkbenchSecurity {

    @Bean
    fun filterChain(http: HttpSecurity, introspector: OpaqueTokenIntrospector): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, authenticated) }
            oauth2ResourceServer { opaqueToken { this.introspector = introspector } }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    @Bean
    fun introspector(token: WorkbenchToken) = OpaqueTokenIntrospector { candidate ->
        if (!token.matches(candidate)) throw BadOpaqueTokenException("Not the workbench's token")
        OAuth2IntrospectionAuthenticatedPrincipal(CURATOR, mapOf("sub" to CURATOR), emptyList())
    }

    @Bean
    fun firewall() = StrictHttpFirewall().apply { setAllowedHostnames { it == "localhost" || it == "127.0.0.1" } }

    /** A request the firewall turns away gets a 400, not a 500. */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler = HttpStatusRequestRejectedHandler()

    companion object {
        /** Who the token stands for, until each client signs in on its own. */
        const val CURATOR = "curator"

        /** Who sent the current request, for the decision log. */
        fun currentActor(): String = SecurityContextHolder.getContext().authentication!!.name
    }
}
