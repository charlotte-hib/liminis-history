package art.liminis.auth

import com.nimbusds.jose.jwk.ECKey
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The signing keys' rotation, and the clients' secrets. */
@AuthTest
class KeysAndSecretsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val keys: SigningKeys,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clients: RegisteredClientRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val properties: AuthProperties,
) {

    @BeforeEach
    fun oneKey() {
        jdbc.sql("delete from signing_key").update()
        keys.rotate()
    }

    private fun age(days: Int) = jdbc.sql("update signing_key set created_at = created_at - make_interval(days => ?)").param(days).update()

    /** The key id of a token auth signs now. */
    private fun signingKey(): String {
        val answer = mockMvc.post("/oauth2/token") {
            with(httpBasic("wb", secret("wb")))
            param("grant_type", "client_credentials")
        }.andReturn().response.contentAsString
        return mockMvc.verified(jsonMapper.readTree(answer)["access_token"].asString()).header.keyID
    }

    @Test
    fun `publishes only the public part of its key`() {
        val published = mockMvc.publishedKeys().keys.single() as ECKey
        assertEquals(false, published.isPrivate)
        assertEquals(published.keyID, signingKey())
    }

    @Test
    fun `makes a new key once the newest is 30 days old, and keeps the old one published`() {
        val old = signingKey()
        keys.rotate()
        assertEquals(old, signingKey())

        age(31)
        keys.rotate()
        val new = signingKey()
        assertNotEquals(old, new)
        assertEquals(setOf(old, new), mockMvc.publishedKeys().keys.map { it.keyID }.toSet())
    }

    @Test
    fun `stops publishing a key once it is 60 days old`() {
        val old = signingKey()
        age(61)
        keys.rotate()
        assertNull(mockMvc.publishedKeys().getKeyByKeyId(old))
        assertEquals(1, mockMvc.publishedKeys().keys.size)
    }

    @Test
    fun `writes each client's secret for the curator only, and keeps only its hash`() {
        for (clientId in listOf(Clients.CURATE, Clients.WB)) {
            val file = secretFile(clientId)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertTrue(passwordEncoder.matches(secret(clientId), clients.findByClientId(clientId)!!.clientSecret))
        }
    }

    @Test
    fun `keeps a secret across starts, and makes a new one when its file is gone`() {
        val kept = secret(Clients.WB)
        Clients(clients, passwordEncoder, properties)
        assertEquals(kept, secret(Clients.WB))

        Files.delete(secretFile(Clients.WB))
        Clients(clients, passwordEncoder, properties)
        val made = secret(Clients.WB)
        assertNotEquals(kept, made)
        assertTrue(passwordEncoder.matches(made, clients.findByClientId(Clients.WB)!!.clientSecret))
    }
}
