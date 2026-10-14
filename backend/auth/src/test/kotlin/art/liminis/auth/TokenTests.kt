package art.liminis.auth

import com.nimbusds.jose.JWSAlgorithm
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tokens auth issues: to wb on its own, and to curate for the curator, after a passkey. */
@AuthTest
class TokenTests(@Autowired private val mockMvc: MockMvc, @Autowired private val jsonMapper: JsonMapper) {

    private val callback = "http://localhost:3100/auth/callback"
    private val verifier = "a-code-verifier-of-at-least-forty-three-characters-long"
    private val challenge = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** The curator, as signing in with a passkey at [at] leaves them. */
    private fun passkeySignIn(at: Instant = Instant.now()) = WebAuthnAuthentication(
        ImmutablePublicKeyCredentialUserEntity.builder().name(CURATOR).id(Bytes.random()).displayName(CURATOR).build(),
        listOf(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY).issuedAt(at).build()),
    )

    private fun ResultActionsDsl.json(): JsonNode = jsonMapper.readTree(andReturn().response.contentAsString)

    private fun token(clientId: String, vararg params: Pair<String, String>) = mockMvc.post("/oauth2/token") {
        with(httpBasic(clientId, secret(clientId)))
        params.forEach { (name, value) -> param(name, value) }
    }

    /** curate's authorization request, its parameters in the query string, where the authorization endpoint reads them. */
    private fun authorizationRequest(pkce: Boolean = true, maxAge: Int? = null): String = UriComponentsBuilder.fromPath("/oauth2/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", "curate")
        .queryParam("scope", "openid curation:read curation:write publish")
        .apply { maxAge?.let { queryParam("max_age", it) } }
        .queryParam("redirect_uri", callback)
        .queryParam("state", "some-state")
        .queryParam("nonce", "some-nonce")
        .apply { if (pkce) queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256") }
        .encode().build().toUriString()

    private fun authorize(pkce: Boolean = true, maxAge: Int? = null, signedIn: Instant = Instant.now()) =
        mockMvc.get(URI(authorizationRequest(pkce, maxAge))) {
            header(HttpHeaders.ACCEPT, "text/html")
            with(authentication(passkeySignIn(signedIn)))
        }

    /** The code curate gets back, after the curator signed in with a passkey at [signedIn]. */
    private fun code(signedIn: Instant = Instant.now(), maxAge: Int? = null): String {
        val location = authorize(maxAge = maxAge, signedIn = signedIn).andExpect { status { is3xxRedirection() } }.andReturn().response.getHeader(HttpHeaders.LOCATION)!!
        val query = UriComponentsBuilder.fromUriString(location).build().queryParams
        assertEquals("some-state", query.getFirst("state"))
        return assertNotNull(query.getFirst("code"), location)
    }

    @Test
    fun `gives wb a token of its own, signed with ES256, for the workbench, with wb's scopes`() {
        val answer = token("wb", "grant_type" to "client_credentials", "scope" to "curation:read runs")
            .andExpect { status { isOk() } }.json()
        val jwt = mockMvc.verified(answer["access_token"].asString())
        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertEquals("wb", jwt.jwtClaimsSet.subject)
        assertEquals(listOf(AUDIENCE), jwt.jwtClaimsSet.audience)
        assertEquals("http://localhost:9000", jwt.jwtClaimsSet.issuer)
        assertEquals(setOf("curation:read", "runs"), jwt.jwtClaimsSet.getStringListClaim("scope").toSet())
        assertNull(jwt.jwtClaimsSet.getClaim(AUTH_TIME))
        assertTrue(answer["expires_in"].asInt() in 598..600)
    }

    @Test
    fun `says in its OpenID discovery that it signs ID tokens with ES256`() {
        mockMvc.get("/.well-known/openid-configuration").andExpect {
            jsonPath("$.issuer") { value("http://localhost:9000") }
            jsonPath("$.id_token_signing_alg_values_supported.length()") { value(1) }
            jsonPath("$.id_token_signing_alg_values_supported[0]") { value("ES256") }
        }
    }

    @Test
    fun `refuses wb a scope it was not given, and a wrong secret`() {
        token("wb", "grant_type" to "client_credentials", "scope" to "curation:write")
            .andExpect { status { isBadRequest() }; jsonPath("$.error") { value("invalid_scope") } }
        mockMvc.post("/oauth2/token") {
            with(httpBasic("wb", "not-the-secret"))
            param("grant_type", "client_credentials")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `gives curate a code after a passkey, then tokens for the code and its verifier`() {
        val answer = token("curate", "grant_type" to "authorization_code", "code" to code(), "redirect_uri" to callback, "code_verifier" to verifier)
            .andExpect { status { isOk() } }.json()
        val access = mockMvc.verified(answer["access_token"].asString()).jwtClaimsSet
        assertEquals(CURATOR, access.subject)
        assertEquals(listOf(AUDIENCE), access.audience)
        assertEquals(setOf("openid", "curation:read", "curation:write", "publish"), access.getStringListClaim("scope").toSet())
        val id = mockMvc.verified(answer["id_token"].asString()).jwtClaimsSet
        assertEquals(listOf("curate"), id.audience)
        assertEquals("some-nonce", id.getStringClaim("nonce"))
        assertNotNull(answer["refresh_token"])
    }

    @Test
    fun `says in curate's access tokens when the passkey was used, and keeps it on refresh`() {
        val signedIn = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS)
        val first = token("curate", "grant_type" to "authorization_code", "code" to code(signedIn), "redirect_uri" to callback, "code_verifier" to verifier)
            .json()
        assertEquals(signedIn.epochSecond, mockMvc.verified(first["access_token"].asString()).jwtClaimsSet.getLongClaim(AUTH_TIME))
        val refreshed = token("curate", "grant_type" to "refresh_token", "refresh_token" to first["refresh_token"].asString()).json()
        assertEquals(signedIn.epochSecond, mockMvc.verified(refreshed["access_token"].asString()).jwtClaimsSet.getLongClaim(AUTH_TIME))
    }

    @Test
    fun `asks for the passkey again when it is older than the request's max_age`() {
        authorize(maxAge = 300, signedIn = Instant.now().minus(Duration.ofMinutes(6)))
            .andExpect { header { string(HttpHeaders.LOCATION, containsString("/login")) } }
        code(Instant.now().minus(Duration.ofMinutes(4)), maxAge = 300)
        // max_age=0 would never pass right after the passkey: a passkey of the last minute does.
        code(Instant.now().minusSeconds(5), maxAge = 0)
    }

    @Test
    fun `refuses a code without its verifier, and a request without PKCE`() {
        token("curate", "grant_type" to "authorization_code", "code" to code(), "redirect_uri" to callback, "code_verifier" to "$verifier-not")
            .andExpect { status { isBadRequest() }; jsonPath("$.error") { value("invalid_grant") } }
        authorize(pkce = false).andExpect { header { string(HttpHeaders.LOCATION, containsString("error=invalid_request")) } }
    }

    @Test
    fun `swaps a refresh token for a new one, after which the old one is void`() {
        val first = token("curate", "grant_type" to "authorization_code", "code" to code(), "redirect_uri" to callback, "code_verifier" to verifier)
            .json()["refresh_token"].asString()
        val second = token("curate", "grant_type" to "refresh_token", "refresh_token" to first)
            .andExpect { status { isOk() } }.json()["refresh_token"].asString()
        assertNotEquals(first, second)
        token("curate", "grant_type" to "refresh_token", "refresh_token" to first)
            .andExpect { status { isBadRequest() }; jsonPath("$.error") { value("invalid_grant") } }
    }

    @Test
    fun `sends the curator to sign in, with a passkey even after the one-time link`() {
        mockMvc.get(URI(authorizationRequest())) { header(HttpHeaders.ACCEPT, "text/html") }
            .andExpect { redirectedUrl("/login") }
        mockMvc.get(URI(authorizationRequest())) {
            header(HttpHeaders.ACCEPT, "text/html")
            with(user(CURATOR).authorities(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.OTT_AUTHORITY)))
        }.andExpect { header { string(HttpHeaders.LOCATION, containsString("/login?factor.type=webauthn")) } }
    }
}
