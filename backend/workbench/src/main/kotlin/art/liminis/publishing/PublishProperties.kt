package art.liminis.publishing

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("app.publish")
data class PublishProperties(
    /** The private data repository, as `owner/name`. */
    val repository: String,
    /** The branch the release is made from. */
    val baseBranch: String,
    /** The branch of the publish pull request: one at a time, rebuilt on [baseBranch] by every publish. */
    val branch: String,
    /** How recently the curator must have used their passkey to publish. */
    val maxAge: Duration,
    val github: GitHubProperties,
)

data class GitHubProperties(
    /** GitHub's REST API. */
    val api: String,
    /** The GitHub App's client id, which signs in as the App. When blank, publishing answers 503. */
    val clientId: String = "",
    /** The App's private key, PKCS#8 PEM: on the curator's computer, readable by them only, never in a repository. */
    val privateKey: Path,
)
