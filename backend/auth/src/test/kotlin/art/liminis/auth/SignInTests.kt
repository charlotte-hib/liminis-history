package art.liminis.auth

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The way in: a passkey, or once, to register one, a link auth prints. */
@AuthTest
@ExtendWith(OutputCaptureExtension::class)
class SignInTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val oneTimeTokens: OneTimeTokenService,
    @Autowired private val firstSignIn: FirstSignIn,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `offers a passkey sign-in, and nothing else`() {
        mockMvc.get("/login").andExpect {
            status { isOk() }
            content { string(containsString("Sign in with a passkey")) }
            content { string(containsString("setupLogin({\"X-CSRF-TOKEN\":")) }
        }
        mockMvc.get("/login/webauthn.js").andExpect { status { isOk() } }
    }

    @Test
    fun `prints a one-time link while no passkey is registered`(output: CapturedOutput) {
        firstSignIn.printLink()
        assertContains(output.out, "To register a passkey, open http://localhost:9000/login/ott?token=")
    }

    @Test
    fun `lets the curator in once with the link, to register a passkey`() {
        val token = oneTimeTokens.generate(GenerateOneTimeTokenRequest(CURATOR)).tokenValue
        mockMvc.post("/login/ott") {
            param("token", token)
            with(csrf())
        }.andExpect { redirectedUrl("/webauthn/register") }
        mockMvc.post("/login/ott") {
            param("token", token)
            with(csrf())
        }.andExpect { redirectedUrl("/login?error") }
    }

    @Test
    fun `makes no link on request`() {
        val before = links()
        mockMvc.post("/ott/generate") {
            param("username", CURATOR)
            with(csrf())
        }.andExpect { status { is3xxRedirection() } }
        assertEquals(before, links())
    }

    @Test
    fun `answers only requests addressed to this computer`() {
        mockMvc.get("/login") { with { it.serverName = "rebound.example"; it } }.andExpect { status { isBadRequest() } }
    }

    private fun links() = jdbc.sql("select count(*) from one_time_tokens").query(Int::class.java).single()
}
