package art.liminis.publishing

import com.fasterxml.jackson.annotation.JsonInclude
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import art.liminis.support.UnavailableException
import java.nio.file.Files
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/** A file of a new tree, with its text. */
private data class Blob(val path: String, val content: String, val mode: String = FILE, val type: String = "blob")

/** A file a new tree leaves out: GitHub wants its `sha` sent, as null, which the workbench's JSON would otherwise drop. */
private data class Removal(val path: String, val mode: String = FILE, val type: String = "blob") {
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val sha: String? = null
}

/** A plain file, not executable. */
private const val FILE = "100644"

/** A pull request on the data repository. */
data class PullRequest(val number: Int, val url: String)

/**
 * The data repository on GitHub, as the workbench's GitHub App: installed on that repository alone, it may read and
 * write its contents and pull requests, nothing else. The App signs a short JWT with its private key, swaps it for an
 * installation token (an hour long, kept until 5 minutes before it ends), and calls the REST API with that. Commits
 * are made through the Git database API, so no clone is needed: on the curator's computer and on a server alike.
 */
@Component
class GitHub(builder: RestClient.Builder, private val properties: PublishProperties) {

    private val github = properties.github

    private val rest = builder.clone()
        .baseUrl(github.api)
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader(API_VERSION, "2022-11-28")
        .defaultHeader(HttpHeaders.USER_AGENT, "Liminis workbench")
        .build()

    private val repo = "/repos/${properties.repository}"

    private data class Token(val value: String, val expiresAt: Instant)

    @Volatile
    private var token: Token? = null

    /** The commit [branch] points at, or nothing when there is no such branch. */
    fun head(branch: String): String? = call { getOrNull("$repo/git/ref/heads/$branch")?.path("object")?.path("sha")?.asString() }

    /** The text of the file at [path] in [commit], or nothing when it has no such file (see [getOrNull]). */
    fun file(path: String, commit: String): String? = call {
        val token = installationToken()
        try {
            rest.get().uri("$repo/contents/$path?ref={ref}", commit).headers { it.setBearerAuth(token) }
                .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json").retrieve().body<String>().orEmpty()
        } catch (_: HttpClientErrorException.NotFound) {
            null
        }
    }

    /**
     * A commit on top of [parent] that sets each file of [files] to its text, or removes it when there is none, and
     * leaves every other file as it is in [parent].
     */
    fun commit(parent: String, files: Map<String, String?>, message: String): String = call {
        val baseTree = get("$repo/git/commits/$parent").path("tree").path("sha").asString()
        val entries = files.map { (path, text) -> if (text == null) Removal(path) else Blob(path, text) }
        val tree = post("$repo/git/trees", mapOf("base_tree" to baseTree, "tree" to entries))["sha"].asString()
        post("$repo/git/commits", mapOf("message" to message, "tree" to tree, "parents" to listOf(parent)))["sha"].asString()
    }

    /** Points [branch] at [commit], making it if needed, whatever it pointed at before. */
    fun moveBranch(branch: String, commit: String): Unit = call {
        if (getOrNull("$repo/git/ref/heads/$branch") == null) {
            post("$repo/git/refs", mapOf("ref" to "refs/heads/$branch", "sha" to commit))
        } else {
            send(rest.patch().uri("$repo/git/refs/heads/$branch"), mapOf("sha" to commit, "force" to true))
        }
    }

    /** The open pull request from [branch], if there is one. */
    fun openPullRequest(branch: String): PullRequest? = call {
        val owner = properties.repository.substringBefore('/')
        rest.get().uri("$repo/pulls?state=open&head={head}", "$owner:$branch").headers(::authorized).retrieve().body<JsonNode>()!!
            .firstOrNull()?.let(::pullRequest)
    }

    fun createPullRequest(branch: String, base: String, title: String, body: String): PullRequest = call {
        pullRequest(post("$repo/pulls", mapOf("head" to branch, "base" to base, "title" to title, "body" to body)))
    }

    fun updatePullRequest(number: Int, title: String, body: String): Unit = call {
        send(rest.patch().uri("$repo/pulls/$number"), mapOf("title" to title, "body" to body))
    }

    fun closePullRequest(number: Int): Unit = call {
        send(rest.patch().uri("$repo/pulls/$number"), mapOf("state" to "closed"))
    }

    private fun pullRequest(json: JsonNode) = PullRequest(json["number"].asInt(), json["html_url"].asString())

    private fun get(path: String): JsonNode = rest.get().uri(path).headers(::authorized).retrieve().body<JsonNode>()!!

    /**
     * Nothing when [path] is not there. The token comes first, outside the catch: an App not installed on the
     * repository answers 404 too, and must not pass for a missing branch or file.
     */
    private fun getOrNull(path: String): JsonNode? {
        val token = installationToken()
        return try {
            rest.get().uri(path).headers { it.setBearerAuth(token) }.retrieve().body<JsonNode>()!!
        } catch (_: HttpClientErrorException.NotFound) {
            null
        }
    }

    private fun post(path: String, body: Any): JsonNode = send(rest.post().uri(path), body)

    private fun send(request: RestClient.RequestBodySpec, body: Any): JsonNode =
        request.headers(::authorized).body(body).retrieve().body<JsonNode>()!!

    private fun authorized(headers: HttpHeaders) = headers.setBearerAuth(installationToken())

    /** The installation's token, a new one when the one kept has less than 5 minutes left. */
    private fun installationToken(): String {
        token?.takeIf { it.expiresAt > Instant.now().plus(Duration.ofMinutes(5)) }?.let { return it.value }
        val app = appJwt()
        val installation = rest.get().uri("$repo/installation").headers { it.setBearerAuth(app) }.retrieve().body<JsonNode>()!!["id"].asLong()
        val answer = rest.post().uri("/app/installations/$installation/access_tokens").headers { it.setBearerAuth(app) }
            .retrieve().body<JsonNode>()!!
        return Token(answer["token"].asString(), Instant.parse(answer["expires_at"].asString())).also { token = it }.value
    }

    /** The App's own JWT, as GitHub asks: issued a minute ago against clock drift, for 9 minutes, by its client id. */
    private fun appJwt(): String {
        if (github.clientId.isBlank()) throw UnavailableException("No GitHub App to publish with: set app.publish.github.client-id")
        if (!Files.isReadable(github.privateKey)) throw UnavailableException("No key for the GitHub App at ${github.privateKey}")
        val key = Files.newInputStream(github.privateKey).use { RsaKeyConverters.pkcs8().convert(it) } as RSAPrivateKey
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder().issuer(github.clientId)
            .issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plus(Duration.ofMinutes(9)))).build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).apply { sign(RSASSASigner(key)) }.serialize()
    }

    /** Runs [request], turning GitHub's refusals and silences into a 503 that says what GitHub answered. */
    private fun <T> call(request: () -> T): T =
        try {
            request()
        } catch (e: HttpClientErrorException) {
            val status = HttpStatus.resolve(e.statusCode.value())
            throw UnavailableException("GitHub answered ${e.statusCode.value()} ${status?.reasonPhrase.orEmpty()}: ${e.responseBodyAsString.take(200)}", e)
        } catch (e: RestClientException) {
            throw UnavailableException("GitHub could not be reached: ${e.message}", e)
        }

    private companion object {
        const val API_VERSION = "X-GitHub-Api-Version"
    }
}
