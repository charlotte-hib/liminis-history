package art.liminis.publishing

import org.springframework.stereotype.Service
import art.liminis.support.StaleException
import art.liminis.support.UnavailableException

/** How a record of a [DataFile] would change. */
enum class Change { ADDED, CHANGED, REMOVED }

/** One record's change: its line before and after, as JSON. */
data class RecordChange(val id: String, val change: Change, val before: String?, val after: String?)

data class FileChanges(val file: DataFile, val changes: List<RecordChange>)

/**
 * What publishing makes of the data repository's [base] commit: the changes to each [DataFile], the publish pull
 * request open, if any, and the people written by hand whom a correction would not change.
 */
data class Publication(
    val base: String,
    val files: List<FileChanges>,
    val pullRequest: PullRequest?,
    val withoutEffect: List<String>,
) {
    val unchanged: Boolean get() = files.all { it.changes.isEmpty() }
}

/**
 * Publishing the curator's decisions: the approved claims, written into the data repository as [DataFiles], through a
 * pull request the curator reads and merges. One publish pull request at a time: publishing again while it is open
 * rebuilds its branch on the latest base, so it always holds every approval not merged yet.
 */
@Service
class Publishing(private val dataFiles: DataFiles, private val github: GitHub, private val properties: PublishProperties) {

    /** What a publish would change now. */
    fun preview(): Publication = compare(base()).first

    /**
     * Commits what [preview] showed on [base], on the publish branch, and opens its pull request or updates the one
     * open. Refused when the data repository moved since the preview. Claims decided since (in another tab) go out
     * with the rest: the pull request shows them before they merge. With nothing to change, closes the pull request
     * left open, if any. Two publishes at once could both open a pull request, and the second fail: the one curator
     * presses one button, disabled while it publishes.
     */
    fun publish(base: String): Publication {
        if (base() != base) throw StaleException("The data repository changed since you looked: look again, then publish")
        val (publication, files) = compare(base)
        val open = publication.pullRequest
        if (publication.unchanged) {
            open?.let { github.closePullRequest(it.number) }
            return publication.copy(pullRequest = null)
        }
        val summary = summary(publication)
        // Only the files that change: GitHub refuses to remove a file that is not there, as an emptied one may not be.
        val changed = publication.files.filter { it.changes.isNotEmpty() }.associate { it.file.path to files.getValue(it.file).text() }
        val commit = github.commit(base, changed, "$TITLE\n\n$summary")
        github.moveBranch(properties.branch, commit)
        val body = "$summary\n\n$ABOUT"
        val pullRequest = open?.also { github.updatePullRequest(it.number, TITLE, body) }
            ?: github.createPullRequest(properties.branch, properties.baseBranch, TITLE, body)
        return publication.copy(pullRequest = pullRequest)
    }

    private fun base(): String =
        github.head(properties.baseBranch) ?: throw UnavailableException("The data repository has no branch ${properties.baseBranch}")

    /** What the approved claims change in [base]'s files, and those files as publishing would write them. */
    private fun compare(base: String): Pair<Publication, Map<DataFile, Lines>> {
        val files = dataFiles.build()
        val changes = files.map { (file, lines) -> FileChanges(file, changes(dataFiles.read(file.path, github.file(file.path, base)), lines)) }
        val byHand = dataFiles.qids(github.file(PEOPLE, base))
        val withoutEffect = files.getValue(DataFile.CORRECTIONS).keys.filter { it in byHand }
        return Publication(base, changes, github.openPullRequest(properties.branch), withoutEffect) to files
    }

    /**
     * Records compared line for line, as JSON text: the workbench writes these files whole, in one format, so a line
     * changes only when its record does.
     */
    private fun changes(before: Lines, after: Lines): List<RecordChange> =
        (before.keys + after.keys).toSortedSet().mapNotNull { id ->
            val old = before[id]
            val new = after[id]
            when {
                old == new -> null
                old == null -> RecordChange(id, Change.ADDED, null, new)
                new == null -> RecordChange(id, Change.REMOVED, old, null)
                else -> RecordChange(id, Change.CHANGED, old, new)
            }
        }

    /** The changes counted, file by file, for the commit and the pull request. */
    private fun summary(publication: Publication): String = publication.files.filter { it.changes.isNotEmpty() }.joinToString("\n") { file ->
        val counts = Change.entries.mapNotNull { change ->
            file.changes.count { it.change == change }.takeIf { it > 0 }?.let { "$it ${change.name.lowercase()}" }
        }
        "- `${file.file.path}`: ${counts.joinToString(", ")}"
    }

    private companion object {
        const val PEOPLE = "data/people.jsonl"
        const val TITLE = "feat: publish the curator's decisions"
        const val ABOUT = "Opened by the workbench when the curator published from curate. Each file is written whole from " +
            "the approved claims. Merging packs a release; a `release.lock` pull request in the code repository puts it in production."
    }
}
