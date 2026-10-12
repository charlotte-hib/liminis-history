package dev.wholivedwhen.curation

// A claim's values as the workbench keeps them in the schema curation, apart from the API's models
// (api/workbench.yaml): a change to the contract cannot make the claims already stored unreadable. The JSON columns
// hold these types as Spring's JsonMapper writes them (CurationJson), nulls left out, and the text columns hold the
// enums' names. Renaming a field or a constant here is a migration of the stored rows (db/curation). The payloads are
// in Payloads.kt; what the service takes and gives back, in ClaimRequests.kt.

enum class ClaimType { PERSON_FACTS, CONNECTION, EVENT, ANECDOTE }

/** `CANDIDATE` waits for review; `NEEDS_WORK` was kept for later, with a note. */
enum class ClaimStatus { CANDIDATE, APPROVED, REJECTED, NEEDS_WORK }

enum class Origin { WIKIDATA, BOOK, CLAUDE, MANUAL }

/** Why a claim needs a person's eye, whatever the error rate of its kind. */
enum class Flag { POORLY_DOCUMENTED, CONFLICT, ODD, SENSITIVE, SINGLE_SOURCE }

data class FlagReason(val flag: Flag, val reason: String)

/**
 * What a claim rests on: a Wikidata entity (`entity`, `revision`), a Wikipedia article (`language`, `title`,
 * `revision`), a book (`title`, `page`), a web page (`url`), or a podcast, a video or a documentary (`title`,
 * `episode`, `minute`, `url`), with the passage the claim rests on.
 */
data class SourceRef(
    val kind: SourceKind,
    val entity: String? = null,
    val revision: Long? = null,
    val language: String? = null,
    val title: String? = null,
    val page: String? = null,
    val episode: String? = null,
    val minute: String? = null,
    val url: String? = null,
    val quote: String? = null,
)

enum class SourceKind { WIKIDATA, WIKIPEDIA, BOOK, WEB, MEDIA }
