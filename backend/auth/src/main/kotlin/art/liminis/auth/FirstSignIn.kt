package art.liminis.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Component

/**
 * The way in before any passkey exists, or after losing one: a one-time sign-in link in auth's log, valid 5 minutes,
 * once. It is printed on a start with no passkey registered, or with `app.auth.one-time-link`: only someone who can
 * start auth on the curator's computer can read it. It lets the curator in only to register a passkey; curate still
 * asks for one.
 */
@Component
class FirstSignIn(
    private val oneTimeTokens: OneTimeTokenService,
    private val passkeyUsers: PublicKeyCredentialUserEntityRepository,
    private val passkeys: UserCredentialRepository,
    private val settings: AuthorizationServerSettings,
    private val properties: AuthProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun printLink() {
        if (!properties.oneTimeLink && hasPasskey()) return
        val token = oneTimeTokens.generate(GenerateOneTimeTokenRequest(CURATOR))
        log.info("To register a passkey, open {}/login/ott?token={} within 5 minutes. It works once.", settings.issuer, token.tokenValue)
    }

    private fun hasPasskey(): Boolean = passkeyUsers.findByUsername(CURATOR)?.let { passkeys.findByUserId(it.id).isNotEmpty() } ?: false
}
