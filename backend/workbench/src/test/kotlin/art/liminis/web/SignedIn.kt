package art.liminis.web

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_READ
import art.liminis.web.WorkbenchSecurity.Companion.CURATION_WRITE
import art.liminis.web.WorkbenchSecurity.Companion.RUNS

/** Every scope auth can grant. */
val ALL_SCOPES = listOf(CURATION_READ, CURATION_WRITE, RUNS)

/** Sent with an access token from auth for the curator, with [scopes]: every one by default. */
fun MockHttpServletRequestDsl.signedIn(scopes: List<String> = ALL_SCOPES) =
    with(jwt().jwt { it.subject("curator") }.authorities(scopes.map(::SimpleGrantedAuthority)))
