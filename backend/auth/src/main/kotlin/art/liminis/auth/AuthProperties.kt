package art.liminis.auth

import org.springframework.boot.context.properties.ConfigurationProperties
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
    val signingKeys: SigningKeyProperties,
)

data class SigningKeyProperties(
    /** How long a key signs before a new one takes over. */
    val rotateAfter: Duration,
    /** How long a key stays published after it was made, for the tokens it signed to pass. */
    val keepFor: Duration,
    /** How often auth checks whether the newest key is due for rotation. */
    val checkEvery: Duration,
)

/** Who signs in: the curator alone, until historians get accounts of their own. */
const val CURATOR = "curator"

/** The API the access tokens are for: the workbench checks that it is in their audience. */
const val AUDIENCE = "workbench"

/** What the workbench API lets a token do, endpoint by endpoint. */
object Scopes {
    /** Queues, claims, people and their evidence. */
    const val CURATION_READ = "curation:read"

    /** Adding claims, deciding on them, undoing a decision. */
    const val CURATION_WRITE = "curation:write"

    /** Starting runs (imports, mappings, exports, proposals, drafts) and following them. */
    const val RUNS = "runs"
}
