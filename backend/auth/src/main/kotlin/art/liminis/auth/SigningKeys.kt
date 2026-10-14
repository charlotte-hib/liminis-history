package art.liminis.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * The keys that sign every token: EC P-256 (ES256), kept in schema auth so that a restart changes nothing. On start,
 * then every `check-every`, a new key is made once the newest is older than `rotate-after`, and it signs from then on.
 * Every key younger than `keep-for` stays published at /oauth2/jwks, so a token signed just before a rotation still
 * passes, and the workbench fetches the keys again when it meets a key id it does not know: no restart, no sign-out.
 */
@Component
class SigningKeys(private val jdbc: JdbcClient, properties: AuthProperties) : JWKSource<SecurityContext> {

    private val log = LoggerFactory.getLogger(javaClass)
    private val settings = properties.signingKeys

    @Volatile
    private var keys: List<ECKey> = emptyList()

    init {
        rotate()
    }

    @Synchronized
    @Scheduled(fixedDelayString = "\${app.auth.signing-keys.check-every}", initialDelayString = "\${app.auth.signing-keys.check-every}")
    fun rotate() {
        val now = Instant.now()
        val current = jdbc.sql("select exists (select from signing_key where created_at > ?)")
            .param((now - settings.rotateAfter).atOffset(ZoneOffset.UTC)).query(Boolean::class.java).single()
        if (!current) {
            val key = ECKeyGenerator(Curve.P_256)
                .keyID(UUID.randomUUID().toString())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .issueTime(Date.from(now))
                .generate()
            jdbc.sql("insert into signing_key (id, jwk, created_at) values (?, ?, ?)")
                .params(key.keyID, key.toJSONString(), now.atOffset(ZoneOffset.UTC))
                .update()
            log.info("Made a new signing key, {}", key.keyID)
        }
        jdbc.sql("delete from signing_key where created_at < ?").param((now - settings.keepFor).atOffset(ZoneOffset.UTC)).update()
        keys = stored()
    }

    private fun stored(): List<ECKey> =
        jdbc.sql("select jwk from signing_key order by created_at desc").query(String::class.java).list().map(ECKey::parse)

    /** Every key still published, private parts included: the JWKS endpoint publishes only their public parts. */
    override fun get(selector: JWKSelector, context: SecurityContext?): List<JWK> = selector.select(JWKSet(keys))

    /** Of the keys that could sign a token, the newest. */
    fun newest(candidates: List<JWK>): JWK = candidates.maxBy { it.issueTime }
}
