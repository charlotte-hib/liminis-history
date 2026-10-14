package art.liminis.auth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import art.liminis.testing.PostgresTestConfiguration
import java.nio.file.Files
import java.nio.file.Path

/** One Spring context for every test of auth, on Postgres in a container, its secrets in Gradle's temporary directory. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
annotation class AuthTest

/** The secret auth wrote for [clientId]. */
fun secret(clientId: String): String = Files.readString(secretFile(clientId))

fun secretFile(clientId: String): Path = Path.of(System.getProperty("app.auth.secrets-dir")).resolve("$clientId-client-secret")

/** The keys auth publishes, as a client fetches them. */
fun MockMvc.publishedKeys(): JWKSet = JWKSet.parse(get("/oauth2/jwks").andReturn().response.contentAsString)

/** The token, once its signature checks out against one of the keys auth publishes. */
fun MockMvc.verified(token: String): SignedJWT {
    val jwt = SignedJWT.parse(token)
    val key = publishedKeys().getKeyByKeyId(jwt.header.keyID) as ECKey
    check(jwt.verify(ECDSAVerifier(key))) { "Not signed by ${key.keyID}" }
    return jwt
}
