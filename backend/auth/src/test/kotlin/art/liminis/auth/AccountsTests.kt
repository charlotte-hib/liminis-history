package art.liminis.auth

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The admin and the curators they invite: what each one's tokens may do, invitations, revocation. */
@AuthTest
class AccountsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val accounts: Accounts,
) {

    private val callback = "http://localhost:3100/auth/callback"
    private val verifier = "a-code-verifier-of-at-least-forty-three-characters-long"
    private val challenge = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private val everything = "openid curation:read curation:write publish admin"

    private fun ResultActionsDsl.json(): JsonNode = jsonMapper.readTree(andReturn().response.contentAsString)

    /** curate's tokens for [username], signed in with a passkey, asking for [scope]. */
    private fun signIn(username: String, scope: String = everything): JsonNode {
        val request = UriComponentsBuilder.fromPath("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", "curate")
            .queryParam("scope", scope)
            .queryParam("redirect_uri", callback)
            .queryParam("state", "some-state")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .encode().build().toUriString()
        val passkey = WebAuthnAuthentication(
            ImmutablePublicKeyCredentialUserEntity.builder().name(username).id(Bytes.random()).displayName(username).build(),
            listOf(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY).issuedAt(Instant.now()).build()),
        )
        val location = mockMvc.get(URI(request)) {
            header(HttpHeaders.ACCEPT, "text/html")
            with(authentication(passkey))
        }.andReturn().response.getHeader(HttpHeaders.LOCATION)!!
        val code = UriComponentsBuilder.fromUriString(location).build().queryParams.getFirst("code")!!
        return token("grant_type" to "authorization_code", "code" to code, "redirect_uri" to callback, "code_verifier" to verifier)
            .andExpect { status { isOk() } }.json()
    }

    private fun token(vararg params: Pair<String, String>) = mockMvc.post("/oauth2/token") {
        with(httpBasic("curate", secret("curate")))
        params.forEach { (name, value) -> param(name, value) }
    }

    private fun claims(answer: JsonNode) = mockMvc.verified(answer["access_token"].asString()).jwtClaimsSet

    /** The admin's access token, as the accounts API reads it, with their passkey used at [passkeyAt]. */
    private fun MockHttpServletRequestDsl.admin(passkeyAt: Instant = Instant.now()) =
        with(jwt().jwt { it.subject(CURATOR).claim(AUTH_TIME, passkeyAt.epochSecond) }.authorities(SimpleGrantedAuthority("SCOPE_admin")))

    private fun invite(username: String, name: String = "Marie Dupont") = mockMvc.post("/api/accounts") {
        admin()
        contentType = MediaType.APPLICATION_JSON
        content = """{"username": "$username", "name": "$name"}"""
    }

    private fun tokenOf(link: String) = UriComponentsBuilder.fromUriString(link).build().queryParams.getFirst("token")!!

    private fun useLink(link: String) = mockMvc.post("/login/ott") {
        param("token", tokenOf(link))
        with(csrf())
    }

    @Test
    fun `gives the admin every scope asked for, for the accounts API too, with their name`() {
        val access = claims(signIn(CURATOR))
        assertEquals(everything.split(" ").toSet(), access.getStringListClaim("scope").toSet())
        assertEquals(listOf(AUDIENCE, ACCOUNTS_AUDIENCE), access.audience)
        assertEquals("Curator", access.getStringClaim(NAME))
    }

    @Test
    fun `gives a curator no admin scope, whatever curate asks for`() {
        invite("anne.martin", "Anne Martin").andExpect { status { isCreated() } }
        val answer = signIn("anne.martin")
        val access = claims(answer)
        assertEquals(setOf("openid", "curation:read", "curation:write", "publish"), access.getStringListClaim("scope").toSet())
        assertEquals(listOf(AUDIENCE), access.audience)
        assertEquals("Anne Martin", access.getStringClaim(NAME))
        // A token for the workbench only, which the accounts API turns away.
        mockMvc.get("/api/accounts") { header(HttpHeaders.AUTHORIZATION, "Bearer ${answer["access_token"].asString()}") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lists the team for the admin's own token, the admin first`() {
        invite("louise.bernard").andExpect { status { isCreated() } }
        val access = signIn(CURATOR)["access_token"].asString()
        val team = mockMvc.get("/api/accounts") { header(HttpHeaders.AUTHORIZATION, "Bearer $access") }
            .andExpect { status { isOk() } }.json()
        assertEquals(300, team["maxAge"].asInt())
        assertEquals(CURATOR, team["accounts"][0]["username"].asString())
        assertEquals("ADMIN", team["accounts"][0]["role"].asString())
        val louise = team["accounts"].first { it["username"].asString() == "louise.bernard" }
        assertEquals("CURATOR", louise["role"].asString())
        assertEquals("INVITED", louise["status"].asString())
        assertEquals(CURATOR, louise["invitedBy"].asString())
    }

    @Test
    fun `refuses the accounts API without the admin scope`() {
        mockMvc.get("/api/accounts") { with(jwt().authorities(SimpleGrantedAuthority("SCOPE_curation:write"))) }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/api/accounts").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `invites a curator with a link that works once, for 7 days, to register a passkey`() {
        val invitation = invite("jeanne.petit", "Jeanne Petit").andExpect { status { isCreated() } }.json()
        assertEquals("jeanne.petit", invitation["account"]["username"].asString())
        assertEquals("Jeanne Petit", invitation["account"]["name"].asString())
        val link = invitation["link"].asString()
        assertTrue(link.startsWith("http://localhost:9000/login/ott?token="), link)
        val expires = OffsetDateTime.parse(invitation["expires"].asString()).toInstant()
        assertTrue(Duration.between(Instant.now(), expires) > Duration.ofDays(7).minusMinutes(1), "$expires")
        useLink(link).andExpect { redirectedUrl("/webauthn/register") }
        useLink(link).andExpect { redirectedUrl("/login?error") }
    }

    @Test
    fun `refuses a username taken, reserved or malformed, and a blank name`() {
        invite("paul.durand").andExpect { status { isCreated() } }
        invite("paul.durand").andExpect { status { isConflict() } }
        invite(CURATOR).andExpect { status { isConflict() } }
        invite("wb").andExpect { status { isConflict() } }
        invite("rule").andExpect { status { isConflict() } }
        invite("Paul Durand").andExpect { status { isBadRequest() } }
        invite("paul.blank", " ").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `asks for the admin's passkey again when it is older than 5 minutes`() {
        mockMvc.post("/api/accounts") {
            admin(passkeyAt = Instant.now().minus(Duration.ofMinutes(6)))
            contentType = MediaType.APPLICATION_JSON
            content = """{"username": "late.invite", "name": "Late"}"""
        }.andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("""error="insufficient_user_authentication"""")) }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("max_age=300")) }
        }
        mockMvc.post("/api/accounts/$CURATOR/revocation") { admin(passkeyAt = Instant.now().minus(Duration.ofMinutes(6))) }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `makes a new invitation, after which the one before no longer works`() {
        val first = invite("claire.roux").json()["link"].asString()
        val second = mockMvc.post("/api/accounts/claire.roux/invitation") { admin() }
            .andExpect { status { isCreated() } }.json()["link"].asString()
        useLink(first).andExpect { redirectedUrl("/login?error") }
        useLink(second).andExpect { redirectedUrl("/webauthn/register") }
        mockMvc.post("/api/accounts/$CURATOR/invitation") { admin() }.andExpect { status { isConflict() } }
        mockMvc.post("/api/accounts/nobody/invitation") { admin() }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `revokes a curator at once, with no refresh, passkey or link left, but keeps the account`() {
        val link = invite("henri.moreau").json()["link"].asString()
        val tokens = signIn("henri.moreau")
        jdbc.sql("insert into user_entities (id, name, display_name) values ('aGVucmk', 'henri.moreau', 'henri.moreau')").update()
        jdbc.sql(
            """
            insert into user_credentials (credential_id, user_entity_user_id, public_key, signature_count, uv_initialized, backup_eligible,
                                          authenticator_transports, public_key_credential_type, backup_state, created, last_used, label)
            values ('a2V5MQ', 'aGVucmk', '\x00', 0, true, false, 'internal', 'public-key', false, now(), now(), 'Henri''s phone')
            """,
        ).update()
        assertEquals(AccountStatus.ACTIVE, accounts.find("henri.moreau").status)

        val revoked = mockMvc.post("/api/accounts/henri.moreau/revocation") { admin() }.andExpect { status { isOk() } }.json()
        assertEquals("REVOKED", revoked["status"].asString())
        assertEquals(0, revoked["passkeys"].asInt())
        assertTrue(revoked.has("revoked"))

        token("grant_type" to "refresh_token", "refresh_token" to tokens["refresh_token"].asString())
            .andExpect { status { isBadRequest() }; jsonPath("$.error") { value("invalid_grant") } }
        useLink(link).andExpect { redirectedUrl("/login?error") }
        assertEquals(0, jdbc.sql("select count(*) from user_entities where name = 'henri.moreau'").query(Int::class.java).single())
        // Signing in again, had a passkey survived, gives no token either.
        assertFalse(runCatching { signIn("henri.moreau") }.isSuccess)
        // Revoking again changes nothing; inviting again is refused.
        mockMvc.post("/api/accounts/henri.moreau/revocation") { admin() }.andExpect { status { isOk() } }
        mockMvc.post("/api/accounts/henri.moreau/invitation") { admin() }.andExpect { status { isConflict() } }
    }

    @Test
    fun `never revokes the admin`() {
        mockMvc.post("/api/accounts/$CURATOR/revocation") { admin() }.andExpect { status { isConflict() } }
        mockMvc.post("/api/accounts/nobody/revocation") { admin() }.andExpect { status { isNotFound() } }
    }
}
