package art.liminis.auth

import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * auth's pages, in curate's look (templates/, static/auth.css): signing in with a passkey, the one-time link, and the
 * passkeys registered. Spring Security's own script (/login/webauthn.js) asks the browser for the passkey.
 */
@Controller
class AuthPages(
    private val properties: AuthProperties,
    private val passkeyUsers: PublicKeyCredentialUserEntityRepository,
    private val passkeys: UserCredentialRepository,
) {

    @ModelAttribute
    fun common(model: Model, csrf: CsrfToken) {
        model.addAttribute("curateUrl", properties.curateUrl)
        model.addAttribute("csrf", csrf)
    }

    @GetMapping("/login")
    fun signIn(@RequestParam(required = false) error: String?, model: Model): String {
        model.addAttribute("failed", error != null)
        return "sign-in"
    }

    /** Where the one-time link leads: a button that posts its token, so that a link preview can't use it up. */
    @GetMapping("/login/ott")
    fun oneTimeLink(@RequestParam token: String, model: Model): String {
        model.addAttribute("token", token)
        return "one-time-link"
    }

    @GetMapping("/webauthn/register")
    fun passkeys(authentication: Authentication, model: Model): String {
        val user = passkeyUsers.findByUsername(authentication.name)
        val registered = user?.let { passkeys.findByUserId(it.id) }.orEmpty().sortedBy { it.created }
        model.addAttribute("passkeys", registered.map { Passkey(it.credentialId.toBase64UrlString(), it.label, day(it.created), time(it.lastUsed)) })
        return "passkeys"
    }

    /** Nothing to see here: the curator works in curate. */
    @GetMapping("/")
    fun home() = RedirectView(properties.curateUrl.toString())

    data class Passkey(val id: String, val label: String, val registered: String, val lastUsed: String)

    private companion object {
        val day: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())
        val dayAndTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

        fun day(instant: Instant): String = day.format(instant)
        fun time(instant: Instant): String = dayAndTime.format(instant)
    }
}
