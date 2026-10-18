package art.liminis.auth

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Who may sign in: the admin, and the curators the admin invites. An invitation is a one-time link that lets its holder
 * in once, to register a passkey, as the admin's own first link did (FirstSignIn). Revoking takes everything away at
 * once (passkeys, links, sessions) but keeps the account, revoked, so that the decisions it took keep its name.
 */
@Service
class Accounts(
    private val jdbc: JdbcClient,
    private val oneTimeTokens: OneTimeTokenService,
    private val passkeyUsers: PublicKeyCredentialUserEntityRepository,
    private val passkeys: UserCredentialRepository,
    private val settings: AuthorizationServerSettings,
    private val properties: AuthProperties,
) {

    /** Every account, the admin's first, then by when they were invited. */
    fun list(): List<Account> = jdbc.sql("$ACCOUNTS order by a.authority = 'ROLE_ADMIN' desc, u.created, u.username").query { row, _ -> account(row) }.list()

    fun find(username: String): Account = jdbc.sql("$ACCOUNTS where u.username = ?").param(username).query { row, _ -> account(row) }.optional()
        .orElseThrow { AccountNotFound("No account $username") }

    /** Adds a curator's account, invited by [by], and their invitation. */
    @Transactional
    fun invite(username: String, name: String, by: String): Invitation {
        if (name.isBlank()) throw AccountRefused("Give their name")
        // The workbench logs its own decisions under these: an account of the same name would sign as them.
        if (username in RESERVED) throw AccountConflict("$username is reserved")
        val added = jdbc.sql("insert into users (username, password, enabled, display_name, invited_by) values (?, '', true, ?, ?) on conflict do nothing")
            .params(username, name.trim(), by).update()
        if (added == 0) throw AccountConflict("$username is taken")
        jdbc.sql("insert into authorities (username, authority) values (?, ?)").params(username, Role.CURATOR.authority).update()
        return invitation(username)
    }

    /** A new invitation for a curator, the one before made void. */
    @Transactional
    fun inviteAgain(username: String): Invitation {
        val account = find(username)
        if (account.role == Role.ADMIN) throw AccountConflict("The admin registers a passkey with the link auth prints on start")
        if (account.status == AccountStatus.REVOKED) throw AccountConflict("$username is revoked")
        return invitation(username)
    }

    /**
     * Revokes a curator's account: it can no longer sign in, and its passkeys, invitation and sessions are deleted, so
     * that curate's next refresh fails. An access token already given lasts its 10 minutes.
     */
    @Transactional
    fun revoke(username: String): Account {
        val account = find(username)
        if (account.role == Role.ADMIN) throw AccountConflict("The admin's account can't be revoked")
        if (account.status == AccountStatus.REVOKED) return account
        jdbc.sql("update users set enabled = false, revoked = now() where username = ?").param(username).update()
        deleteLinks(username)
        passkeyUsers.findByUsername(username)?.let { user ->
            passkeys.findByUserId(user.id).forEach { passkeys.delete(it.credentialId) }
            passkeyUsers.delete(user.id)
        }
        // Every sign-in and its refresh token: Spring's authorization service finds them by token only.
        jdbc.sql("delete from oauth2_authorization where principal_name = ?").param(username).update()
        return find(username)
    }

    private fun invitation(username: String): Invitation {
        deleteLinks(username)
        val token = oneTimeTokens.generate(GenerateOneTimeTokenRequest(username, properties.accounts.invitationValidFor))
        return Invitation(find(username), "${settings.issuer}/login/ott?token=${token.tokenValue}", token.expiresAt)
    }

    private fun deleteLinks(username: String) {
        jdbc.sql("delete from one_time_tokens where username = ?").param(username).update()
    }

    private fun account(row: ResultSet): Account {
        val passkeys = row.getInt("passkeys")
        return Account(
            username = row.getString("username"),
            name = row.getString("display_name"),
            role = Role.entries.single { it.authority == row.getString("authority") },
            status = when {
                !row.getBoolean("enabled") -> AccountStatus.REVOKED
                passkeys > 0 -> AccountStatus.ACTIVE
                else -> AccountStatus.INVITED
            },
            passkeys = passkeys,
            invitedBy = row.getString("invited_by"),
            created = row.getObject("created", OffsetDateTime::class.java).toInstant(),
            revoked = row.getObject("revoked", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    private companion object {
        /** Each account with its one role and how many passkeys it registered (Spring's passkey tables, by username). */
        const val ACCOUNTS = """
            select u.username, u.display_name, u.enabled, u.invited_by, u.created, u.revoked, a.authority,
                   (select count(*) from user_credentials c join user_entities e on e.id = c.user_entity_user_id where e.name = u.username) as passkeys
            from users u join authorities a on a.username = u.username
            """

        /** wb's client id, and the name the workbench's rules sign their proposals with. */
        val RESERVED = setOf(Clients.WB, Clients.CURATE, "rule")
    }
}

data class Account(
    val username: String,
    val name: String,
    val role: Role,
    val status: AccountStatus,
    val passkeys: Int,
    val invitedBy: String?,
    val created: Instant,
    val revoked: Instant?,
)

/** `INVITED`: no passkey yet. `ACTIVE`: at least one. `REVOKED`: never again. */
enum class AccountStatus { INVITED, ACTIVE, REVOKED }

/** The one-time link to send, and when it stops working. */
data class Invitation(val account: Account, val link: String, val expires: Instant)

class AccountNotFound(message: String) : RuntimeException(message)

class AccountConflict(message: String) : RuntimeException(message)

class AccountRefused(message: String) : RuntimeException(message)
