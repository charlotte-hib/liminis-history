package art.liminis.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall

/**
 * Every request needs an access token from auth (a JWT, checked against auth's published keys, its issuer, its audience
 * and its expiry) with the scope its endpoint asks for, and must be addressed to localhost: a page that rebinds its own
 * domain to 127.0.0.1 still sends its own name. An endpoint left out of the rules below is refused to everyone. No
 * sessions, no cookies, so no CSRF to guard against.
 */
@Configuration
class WorkbenchSecurity {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                for (path in listOf("/api/queues", "/api/claims", "/api/claims/*", "/api/people", "/api/people/*/evidence", "/api/occupations/*")) {
                    authorize(GET, path, hasAuthority(CURATION_READ))
                }
                for (path in listOf("/api/claims", "/api/claims/*/decisions", "/api/claims/*/undo")) {
                    authorize(POST, path, hasAuthority(CURATION_WRITE))
                }
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

    @Bean
    fun firewall() = StrictHttpFirewall().apply { setAllowedHostnames { it == "localhost" || it == "127.0.0.1" } }

    /** A request the firewall turns away gets a 400, not a 500. */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler = HttpStatusRequestRejectedHandler()

    companion object {
        /** Queues, claims, people and their evidence: curate, and wb's queues. */
        const val CURATION_READ = "SCOPE_curation:read"

        /** Adding claims, deciding, undoing: curate. */
        const val CURATION_WRITE = "SCOPE_curation:write"

        /** Starting and following runs: wb. */
        const val RUNS = "SCOPE_runs"

        /** Who sent the current request, for the decision log: the curator signed in to curate, or the wb client. */
        fun currentActor(): String = SecurityContextHolder.getContext().authentication!!.name
    }
}
