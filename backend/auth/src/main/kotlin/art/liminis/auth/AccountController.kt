package art.liminis.auth

import art.liminis.auth.api.AccountsApi
import art.liminis.auth.api.model.AccountDto
import art.liminis.auth.api.model.AccountStatusDto
import art.liminis.auth.api.model.InvitationDto
import art.liminis.auth.api.model.NewAccountDto
import art.liminis.auth.api.model.RoleDto
import art.liminis.auth.api.model.TeamDto
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset

/**
 * The accounts API, for the admin's access tokens only (AuthSecurity). Inviting and revoking change who may sign in:
 * they take a passkey used in the last few minutes, as publishing does, and an older one gets the answer of RFC 9470,
 * for curate to ask for the passkey again with `max_age`.
 */
@RestController
class AccountController(private val accounts: Accounts, private val properties: AuthProperties) : AccountsApi {

    override fun listAccounts(): TeamDto = TeamDto(accounts.list().map(::dto), properties.accounts.maxAge.toSeconds().toInt())

    override fun invite(newAccountDto: NewAccountDto): InvitationDto {
        val by = requireRecentPasskey()
        return dto(accounts.invite(newAccountDto.username, newAccountDto.name, by))
    }

    override fun inviteAgain(username: String): InvitationDto {
        requireRecentPasskey()
        return dto(accounts.inviteAgain(username))
    }

    override fun revoke(username: String): AccountDto {
        requireRecentPasskey()
        return dto(accounts.revoke(username))
    }

    @ExceptionHandler
    fun notFound(exception: AccountNotFound): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message)

    @ExceptionHandler
    fun conflict(exception: AccountConflict): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message)

    @ExceptionHandler
    fun refused(exception: AccountRefused): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message)

    /** The admin's username, once their passkey is recent enough. */
    private fun requireRecentPasskey(): String {
        val token = (SecurityContextHolder.getContext().authentication as JwtAuthenticationToken).token
        val usedAt = token.getClaimAsInstant(AUTH_TIME)
        val maxAge = properties.accounts.maxAge
        if (usedAt != null && usedAt >= Instant.now().minus(maxAge)) return token.subject!!
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Managing the team needs your passkey again: sign in once more")
        throw ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null).apply {
            headers.set(
                HttpHeaders.WWW_AUTHENTICATE,
                """Bearer error="insufficient_user_authentication", error_description="A passkey used in the last ${maxAge.toSeconds()} seconds is needed", max_age=${maxAge.toSeconds()}""",
            )
        }
    }

    private fun dto(invitation: Invitation) = InvitationDto(dto(invitation.account), invitation.link, invitation.expires.atOffset(ZoneOffset.UTC))

    private fun dto(account: Account) = AccountDto(
        username = account.username,
        name = account.name,
        role = RoleDto.valueOf(account.role.name),
        status = AccountStatusDto.valueOf(account.status.name),
        passkeys = account.passkeys,
        created = account.created.atOffset(ZoneOffset.UTC),
        invitedBy = account.invitedBy,
        revoked = account.revoked?.atOffset(ZoneOffset.UTC),
    )
}
