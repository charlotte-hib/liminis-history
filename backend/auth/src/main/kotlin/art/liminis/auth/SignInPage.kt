package art.liminis.auth

import org.springframework.http.MediaType
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.view.RedirectView
import tools.jackson.databind.json.JsonMapper

/**
 * The sign-in page: one button that asks the browser for a passkey, through Spring Security's own script and styles
 * (/login/webauthn.js, /default-ui.css). After it, the browser goes on to what asked for the sign-in: curate's
 * authorization request.
 */
@Controller
class SignInPage(private val jsonMapper: JsonMapper, private val properties: AuthProperties) {

    @GetMapping("/login", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun signIn(csrf: CsrfToken, @RequestParam(required = false) error: String?): String {
        val headers = jsonMapper.writeValueAsString(mapOf(csrf.headerName to csrf.token))
        val failed = if (error != null) """<div class="alert alert-danger" role="alert">That did not sign you in. Try again.</div>""" else ""
        return """
            <!DOCTYPE html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Sign in · Liminis</title>
                <link href="/default-ui.css" rel="stylesheet">
                <script src="/login/webauthn.js"></script>
                <script>
                  document.addEventListener("DOMContentLoaded", () => setupLogin($headers, "", document.getElementById("passkey-signin")));
                </script>
              </head>
              <body>
                <div class="content">
                  <div class="login-form">
                    <h2>Liminis curation</h2>
                    $failed
                    <button id="passkey-signin" type="submit" class="primary">Sign in with a passkey</button>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()
    }

    /** Nothing to see here: the curator works in curate. */
    @GetMapping("/")
    fun home() = RedirectView(properties.curateUrl.toString())
}
