package art.liminis.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import art.liminis.curation.Actor
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall

/**
 * Every request needs an access token from auth (a JWT, checked against auth's published keys, its issuer, its audience
 * and its expiry) with the scope its endpoint asks for, and must be addressed to one of its own names (localhost, and on
 * the server its name on the Compose network): a page that rebinds its own domain to 127.0.0.1 still sends its own
 * name. An endpoint left out of the rules below is refused to everyone. No
 * sessions, no cookies, so no CSRF to guard against. A curator the admin revoked is refused at once, not when their
 * last token expires.
 */
@Configuration
class WorkbenchSecurity {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                for (path in listOf(
                    "/api/queues", "/api/claims", "/api/claims/*", "/api/people", "/api/people/review", "/api/people/*/evidence",
                    "/api/people/*/neighbours", "/api/people/*/preview", "/api/occupations/*",
                    "/api/moments", "/api/moments/*", "/api/moments/*/people", "/api/moments/*/preview", "/api/moments/*/subjects",
                    "/api/paintings", "/api/translations/run",
                )) {
                    authorize(GET, path, hasAuthority(CURATION_READ))
                }
                for (path in listOf("/api/claims", "/api/claims/*/decisions", "/api/claims/*/undo", "/api/people/*/facts", "/api/moments/*/story", "/api/moments/*/page", "/api/translations/run")) {
                    authorize(POST, path, hasAuthority(CURATION_WRITE))
                }
                for (path in listOf("/api/claims/*/holder", "/api/moments/*/holder")) {
                    authorize(PUT, path, hasAuthority(CURATION_WRITE))
                    authorize(DELETE, path, hasAuthority(CURATION_WRITE))
                }
                authorize(DELETE, "/api/holders/*", hasAuthority(ADMIN))
                authorize(GET, "/api/publication", hasAuthority(CURATION_READ))
                // And a recent passkey, which PublicationController checks.
                authorize(POST, "/api/publication", hasAuthority(PUBLISH))
                authorize("/api/runs", hasAuthority(RUNS))
                authorize("/api/runs/*", hasAuthority(RUNS))
                // Where Spring sends an unexpected failure to become a 500 Problem, which the rule below would turn
                // into a 403.
                authorize("/error", permitAll)
                authorize(anyRequest, denyAll)
            }
            oauth2ResourceServer { jwt { } }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    /**
     * Refuses the token of an account auth keeps disabled: a revoked curator. Read from auth's own table, in the same
     * Postgres, on every request; a subject auth has no account for (the wb client) passes. Spring Boot adds it to the
     * token's other checks.
     */
    @Bean
    fun notRevoked(jdbc: JdbcClient): OAuth2TokenValidator<Jwt> = OAuth2TokenValidator { token ->
        val enabled = jdbc.sql("select enabled from auth.users where username = ?").param(token.subject).query(Boolean::class.javaObjectType).optional()
        if (enabled.orElse(true)) OAuth2TokenValidatorResult.success()
        else OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "${token.subject} no longer has access", null))
    }

    @Bean
    fun firewall(web: WebProperties) = StrictHttpFirewall().apply { setAllowedHostnames { it in web.allowedHosts } }

    /** A request the firewall turns away gets a 400, not a 500. */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler = HttpStatusRequestRejectedHandler()

    companion object {
        /** Queues, claims, people and their evidence, moments, paintings on Commons: curate, and wb's queues. */
        const val CURATION_READ = "SCOPE_curation:read"

        /** Adding claims, proposing a person's facts or a moment's story, deciding, undoing: curate. */
        const val CURATION_WRITE = "SCOPE_curation:write"

        /** Starting and following runs: wb. */
        const val RUNS = "SCOPE_runs"

        /** Publishing to the data repository: curate, after a recent passkey. */
        const val PUBLISH = "SCOPE_publish"

        /** Writing anyone's claims, handing them on, releasing them: the admin, signed in to curate. */
        const val ADMIN = "SCOPE_admin"

        /**
         * Who sent the current request, for the decision log and the claims they hold: a curator signed in to curate,
         * the admin, or the wb client.
         */
        fun currentActor(): Actor {
            val authentication = SecurityContextHolder.getContext().authentication!!
            return Actor(authentication.name, authentication.authorities.any { it.authority == ADMIN })
        }
    }
}
