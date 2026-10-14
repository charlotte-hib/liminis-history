package art.liminis.web

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import art.liminis.testing.PostgresTestConfiguration
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Access tokens as auth signs them, checked against keys published by a fake auth (WireMock): the issuer, the
 * audience, the expiry and the signing key all count, and a key published after a rotation is fetched when a token
 * names it.
 */
@SpringBootTest(
    properties = [
        "app.wikipedia.enrich=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=\${auth.base-url}/oauth2/jwks",
    ],
)
@AutoConfigureMockMvc
@EnableWireMock(ConfigureWireMock(baseUrlProperties = ["auth.base-url"]))
@Import(PostgresTestConfiguration::class)
class AccessTokenTests(@Autowired private val mockMvc: MockMvc) {

    @InjectWireMock
    private lateinit var auth: WireMockServer

    private val published = key()

    private fun key(): ECKey = ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate()

    private fun publish(vararg keys: ECKey) {
        auth.stubFor(get("/oauth2/jwks").willReturn(okJson(JWKSet(keys.toList()).toString())))
    }

    private fun token(
        key: ECKey = published,
        issuer: String = "http://localhost:9000",
        audience: String = "workbench",
        expires: Instant = Instant.now() + Duration.ofMinutes(10),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("curator")
            .claim("scope", listOf("curation:read"))
            .issueTime(Date.from(expires - Duration.ofMinutes(10)))
            .expirationTime(Date.from(expires))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).build(), claims)
            .apply { sign(ECDSASigner(key)) }
            .serialize()
    }

    private fun queues(token: String) = mockMvc.get("/api/queues") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }

    @Test
    fun `lets a token from auth through`() {
        publish(published)
        queues(token()).andExpect { status { isOk() } }
    }

    @Test
    fun `refuses a token for another audience, from another issuer, expired, or signed with another key`() {
        publish(published)
        queues(token(audience = "curate")).andExpect { status { isUnauthorized() } }
        queues(token(issuer = "http://localhost:9001")).andExpect { status { isUnauthorized() } }
        queues(token(expires = Instant.now() - Duration.ofMinutes(5))).andExpect { status { isUnauthorized() } }
        queues(token(key = key())).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `takes a key auth published after a rotation, and still the one before`() {
        publish(published)
        queues(token()).andExpect { status { isOk() } }
        val next = key()
        publish(next, published)
        queues(token(key = next)).andExpect { status { isOk() } }
        queues(token()).andExpect { status { isOk() } }
    }
}
