package art.liminis.auth

import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * The workbench API's two clients, registered from this code on every start. curate signs the curator in with a
 * passkey (authorization code with PKCE, then refresh tokens); wb acts on its own (client credentials). Each secret is
 * made once and written to a file only the curator can read, where curate and wb find it; auth keeps only its hash. A
 * secret whose file is gone is made anew.
 */
@Component
class Clients(
    private val repository: RegisteredClientRepository,
    private val passwordEncoder: PasswordEncoder,
    private val properties: AuthProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        register(CURATE) {
            clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            redirectUri("${properties.curateUrl}/auth/callback")
            postLogoutRedirectUri("${properties.curateUrl}/auth/signed-out")
            scopes { it += listOf(OidcScopes.OPENID, Scopes.CURATION_READ, Scopes.CURATION_WRITE, Scopes.PUBLISH) }
            clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
            tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(ACCESS_TOKEN)
                    // A working day: curate ends its session as long after the sign-in, whatever its refreshes.
                    .refreshTokenTimeToLive(Duration.ofHours(8))
                    // A new refresh token with every refresh: the one used is void from then on.
                    .reuseRefreshTokens(false)
                    .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                    .build(),
            )
        }
        register(WB) {
            clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            scopes { it += listOf(Scopes.CURATION_READ, Scopes.RUNS) }
            tokenSettings(TokenSettings.builder().accessTokenTimeToLive(ACCESS_TOKEN).build())
        }
    }

    private fun register(clientId: String, settings: RegisteredClient.Builder.() -> Unit) {
        val existing = repository.findByClientId(clientId)
        val file = properties.secretsDir.resolve("$clientId-client-secret").toAbsolutePath()
        val secret = existing?.clientSecret?.takeIf { Files.exists(file) } ?: newSecret(file)
        repository.save(RegisteredClient.withId(existing?.id ?: clientId).clientId(clientId).clientSecret(secret).apply(settings).build())
    }

    /** A new secret, written to [file] for its client to read, and its hash, for auth to keep. */
    private fun newSecret(file: Path): String {
        val secret = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        Files.createDirectories(file.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.deleteIfExists(file)
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, secret)
        log.info("Wrote a new client secret to {}", file)
        return passwordEncoder.encode(secret)!!
    }

    companion object {
        const val CURATE = "curate"
        const val WB = "wb"

        /** Short, as the workbench checks them without asking auth: a revoked sign-in still reads for this long. */
        val ACCESS_TOKEN: Duration = Duration.ofMinutes(10)

        private val random = SecureRandom()
    }
}
