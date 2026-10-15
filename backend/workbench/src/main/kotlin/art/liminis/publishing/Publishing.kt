package art.liminis.publishing

import org.springframework.stereotype.Service
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Story
import art.liminis.domain.CardType
import art.liminis.support.StaleException
import art.liminis.support.UnavailableException

/** How a record of a file publishing writes would change. */
enum class Change { ADDED, CHANGED, REMOVED }

/** One record's change: its line before and after, as JSON. */
data class RecordChange(val id: String, val change: Change, val before: String?, val after: String?)

/** The changes to the file at [path]: its records', or a moment's story, under the moment's id. */
data class FileChanges(val path: String, val changes: List<RecordChange>)

/** An approved story publishing leaves out, and why. */
data class HeldBack(val moment: String, val reason: String)

/**
 * What publishing makes of the data repository's [base] commit: the changes to each [DataFile] and to the moments'
 * files the approved stories go into, the publish pull request open, if any, the people written by hand whom a
 * correction would not change, and the approved stories left out.
 */
data class Publication(
    val base: String,
    val files: List<FileChanges>,
    val pullRequest: PullRequest?,
    val withoutEffect: List<String>,
    val heldBack: List<HeldBack>,
) {
    val unchanged: Boolean get() = files.all { it.changes.isEmpty() }
}

/**
 * Publishing the curator's decisions: the approved claims, written into the data repository as [DataFiles] and the
 * approved stories into their [MomentFiles], through a pull request the curator reads and merges. One publish pull
 * request at a time: publishing again while it is open rebuilds its branch on the latest base, so it always holds
 * every approval not merged yet.
 */
@Service
class Publishing(
    private val dataFiles: DataFiles,
    private val momentFiles: MomentFiles,
    private val claims: ClaimRepository,
    private val github: GitHub,
    private val properties: PublishProperties,
) {

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
        val changed = publication.files.filter { it.changes.isNotEmpty() }.associate { it.path to files.getValue(it.path) }
        val commit = github.commit(base, changed, "$TITLE\n\n$summary")
        github.moveBranch(properties.branch, commit)
        val body = "$summary\n\n$ABOUT"
        val pullRequest = open?.also { github.updatePullRequest(it.number, TITLE, body) }
            ?: github.createPullRequest(properties.branch, properties.baseBranch, TITLE, body)
        return publication.copy(pullRequest = pullRequest)
    }

    private fun base(): String =
        github.head(properties.baseBranch) ?: throw UnavailableException("The data repository has no branch ${properties.baseBranch}")

    /** What the approved claims change in [base]'s files, and the text of each file as publishing would write it. */
    private fun compare(base: String): Pair<Publication, Map<String, String?>> {
        val files = dataFiles.build()
        val changes = files.map { (file, lines) -> FileChanges(file.path, changes(dataFiles.read(file.path, github.file(file.path, base)), lines)) }
        val byHand = dataFiles.qids(github.file(PEOPLE, base))
        val withoutEffect = files.getValue(DataFile.CORRECTIONS).keys.filter { it in byHand }
        val texts = files.entries.associate { (file, lines) -> file.path to lines.text() }

        val heldBack = mutableListOf<HeldBack>()
        val stories = mutableListOf<Pair<FileChanges, String>>()
        for (story in approvedStories()) {
            val path = momentFiles.path(story.moment)
            val before = github.file(path, base)
            val reason = holdBack(story) ?: if (before == null) "There is no $path on the data repository's main" else null
            if (reason != null) {
                heldBack += HeldBack(story.moment, reason)
                continue
            }
            val after = momentFiles.write(before!!, story)
            val change = RecordChange(story.moment, Change.CHANGED, momentFiles.story(before), momentFiles.story(after)).takeIf { before != after }
            stories += FileChanges(path, listOfNotNull(change)) to after
        }
        val publication = Publication(base, changes + stories.map { it.first }, github.openPullRequest(properties.branch), withoutEffect, heldBack)
        return publication to texts + stories.associate { (file, text) -> file.path to text }
    }

    private fun approvedStories() = claims.findByTypeAndStatusOrderById(ClaimType.STORY, ClaimStatus.APPROVED).map { it.payload as Story }

    /**
     * Why [story] cannot be published yet: a card showing a fetched person (named by Q-id), whom the release holds only
     * once it carries the fetched dataset.
     */
    private fun holdBack(story: Story): String? {
        if (properties.fetchedPeople) return null
        val index = story.cards.indexOfFirst { it.type == CardType.PERSON && it.person.orEmpty().matches(QID) }
        if (index < 0) return null
        val card = story.cards[index]
        return "Card ${index + 1} shows ${card.label ?: card.person}, ${card.person}: the release holds fetched people " +
            "once it carries the dataset"
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
        "- `${file.path}`: ${counts.joinToString(", ")}"
    }

    private companion object {
        const val PEOPLE = "data/people.jsonl"
        val QID = Regex("Q[1-9][0-9]*")
        const val TITLE = "feat: publish the curator's decisions"
        const val ABOUT = "Opened by the workbench when the curator published from curate. Each file is written whole from " +
            "the approved claims. Merging packs a release; a `release.lock` pull request in the code repository puts it in production."
    }
}
