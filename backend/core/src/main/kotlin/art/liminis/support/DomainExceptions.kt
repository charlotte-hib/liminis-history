package art.liminis.support

// What the services of every app throw when a request cannot be served, without knowing about HTTP.
// ProblemDetailsHandler (art.liminis.web) answers each with its status: the message becomes the detail.

/** Nothing has this id. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request does not hold together: what the contract cannot say, such as no year 0. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** The request conflicts with the current state: the claim exists already, a run is still running. */
class ConflictException(message: String) : RuntimeException(message)

/** What the request was based on changed since it was read: a claim decided on meanwhile. */
class StaleException(message: String) : RuntimeException(message)

/** Something the request needs elsewhere is missing or not answering: GitHub, or the settings to reach it. */
class UnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
