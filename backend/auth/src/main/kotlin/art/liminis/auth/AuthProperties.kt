package art.liminis.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcScopes
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("app.auth")
data class AuthProperties(
    /** Where the clients' secrets are written, readable by the curator only. */
    val secretsDir: Path,
    /** curate's address, where the browser comes back after signing in or out. */
    val curateUrl: URI,
    /** Prints a one-time sign-in link on start even when a passkey exists, to register another. */
    val oneTimeLink: Boolean,
    /** The names a request may be addressed to (its Host), besides the issuer's: any other is turned away. */
    val allowedHosts: Set<String>,
    val signingKeys: SigningKeyProperties,
    val accounts: AccountProperties,
)

data class AccountProperties(
    /** How long an invitation's one-time link works. */
    val invitationValidFor: Duration,
    /** How long ago the admin may have used their passkey, at most, to invite or revoke. */
    val maxAge: Duration,
)

data class SigningKeyProperties(
    /** How long a key signs before a new one takes over. */
    val rotateAfter: Duration,
    /** How long a key stays published after it was made, for the tokens it signed to pass. */
    val keepFor: Duration,
    /** How often auth checks whether the newest key is due for rotation. */
    val checkEvery: Duration,
)

/** The admin's username: the account the curator had before there were others, its passkeys and decisions kept. */
const val CURATOR = "curator"

/** The API the access tokens are for: the workbench checks that it is in their audience. */
const val AUDIENCE = "workbench"

/** The accounts API, auth's own: the audience of the admin's access tokens too. */
const val ACCOUNTS_AUDIENCE = "auth"

/** The person's name, in curate's access tokens, for curate to show who is signed in. */
const val NAME = "name"

/** The claim of an access token that says when the curator last used their passkey, in seconds since 1970. */
const val AUTH_TIME = "auth_time"

/** What the workbench API lets a token do, endpoint by endpoint. */
object Scopes {
    /** Queues, claims, people and their evidence. */
    const val CURATION_READ = "curation:read"

    /** Adding claims, deciding on them, undoing a decision. */
    const val CURATION_WRITE = "curation:write"

    /** Starting runs (imports, mappings, exports, proposals, drafts) and following them. */
    const val RUNS = "runs"

    /** Publishing the decisions to the data repository: the workbench also asks for a recent passkey. */
    const val PUBLISH = "publish"

    /** The team: seeing it, inviting and revoking curators (the accounts API). */
    const val ADMIN = "admin"
}

/** What an account may do: the scopes its access tokens carry, whatever curate asks for. */
enum class Role(val scopes: Set<String>) {
    ADMIN(setOf(OidcScopes.OPENID, Scopes.CURATION_READ, Scopes.CURATION_WRITE, Scopes.PUBLISH, Scopes.ADMIN)),
    CURATOR(setOf(OidcScopes.OPENID, Scopes.CURATION_READ, Scopes.CURATION_WRITE, Scopes.PUBLISH)),
    ;

    /** As Spring Security keeps it, in the table authorities. */
    val authority = "ROLE_$name"

    companion object {
        fun of(authorities: Collection<GrantedAuthority>): Role? = entries.firstOrNull { role -> authorities.any { it.authority == role.authority } }
    }
}
