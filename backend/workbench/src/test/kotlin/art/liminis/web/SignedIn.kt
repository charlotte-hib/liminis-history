package art.liminis.web

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_READ
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_WRITE
import art.liminis.web.WorkbenchSecurity.Companion.PUBLISH
import art.liminis.web.WorkbenchSecurity.Companion.RUNS
import java.time.Instant

/** Every scope auth can grant. */
val ALL_SCOPES = listOf(CURATION_READ, CURATION_WRITE, RUNS, PUBLISH)

/**
 * Sent with an access token from auth for the curator, with [scopes]: every one by default; and, when given, the time
 * they last used their passkey.
 */
fun MockHttpServletRequestDsl.signedIn(scopes: List<String> = ALL_SCOPES, passkeyAt: Instant? = null) =
    with(
        jwt().jwt { token ->
            token.subject("curator")
            passkeyAt?.let { token.claim("auth_time", it.epochSecond) }
        }.authorities(scopes.map(::SimpleGrantedAuthority)),
    )
