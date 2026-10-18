package art.liminis.publishing

import org.springframework.stereotype.Service
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.MomentPage
import art.liminis.curation.Story
import art.liminis.release.AnecdoteRecord
import art.liminis.support.StaleException
import art.liminis.support.UnavailableException

/** How a record of a file publishing writes would change. */
enum class Change { ADDED, CHANGED, REMOVED }

/** One record's change: its line before and after, as JSON. */
data class RecordChange(val id: String, val change: Change, val before: String?, val after: String?)

/** The changes to the file at [path]: its records', or a moment's page and story, under the moment's id. */
data class FileChanges(val path: String, val changes: List<RecordChange>)

/** An approved story, page or anecdote publishing leaves out, under its moment's id or the anecdote's, and why. */
data class HeldBack(val id: String, val reason: String)

/**
 * What publishing makes of the data repository's [base] commit: the changes to each [DataFile] and to the moments'
 * files the approved pages and stories go into, the same in words ([people], [occupations], [anecdotes], [moments]), the
 * publish pull request open, if any, the people written by hand whom a correction would not change, and the approved
 * stories and anecdotes left out.
 */
data class Publication(
    val base: String,
    val files: List<FileChanges>,
    val people: List<PersonChange>,
    val occupations: List<OccupationChange>,
    val anecdotes: List<AnecdoteChange>,
    val moments: List<MomentChange>,
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
    private val words: ChangesInWords,
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
        val byHand = dataFiles.byHand(github.file(PEOPLE, base))
        val anecdotes = dataFiles.anecdotes()
        val anecdotesHeldBack = holdBack(anecdotes, byHand)
        val published = anecdotes.filter { anecdote -> anecdotesHeldBack.none { it.id == anecdote.id } }
            .map { AnecdoteRecord(it.id, it.anecdote.person, it.anecdote.year, it.anecdote.text, it.source!!) }

        val files = dataFiles.build(published)
        val changes = files.map { (file, lines) -> FileChanges(file.path, changes(dataFiles.read(file.path, github.file(file.path, base)), lines)) }
        val changesOf = { file: DataFile -> changes.single { it.path == file.path }.changes }
        val withoutEffect = files.getValue(DataFile.CORRECTIONS).keys.filter { it in byHand }
        val texts = files.entries.associate { (file, lines) -> file.path to lines.text() }

        val heldBack = mutableListOf<HeldBack>()
        val moments = mutableListOf<Pair<FileChanges, String>>()
        val momentsInWords = mutableListOf<MomentChange>()
        val pages = approved<MomentPage>(ClaimType.MOMENT_PAGE).associateBy { it.moment }
        val stories = approved<Story>(ClaimType.STORY).associateBy { it.moment }
        for (moment in (pages.keys + stories.keys).sorted()) {
            val path = momentFiles.path(moment)
            val before = github.file(path, base)
            if (before == null) {
                heldBack += HeldBack(moment, "There is no $path on the data repository's main")
                continue
            }
            // A story held back leaves its moment's page to go alone.
            val reason = stories[moment]?.let(::holdBack)
            if (reason != null) heldBack += HeldBack(moment, reason)
            val story = stories[moment].takeIf { reason == null }
            val page = pages[moment]
            if (page == null && story == null) continue
            val after = momentFiles.write(before, page, story)
            val change = RecordChange(moment, Change.CHANGED, momentFiles.momentText(before), momentFiles.momentText(after)).takeIf { before != after }
            moments += FileChanges(path, listOfNotNull(change)) to after
            if (change != null) momentsInWords += words.moment(moment, before, after, story)
        }
        val publication = Publication(
            base, changes + moments.map { it.first },
            words.people(changesOf(DataFile.CORRECTIONS)), words.occupations(changesOf(DataFile.OCCUPATIONS)),
            words.anecdotes(changesOf(DataFile.ANECDOTES), byHand), momentsInWords,
            github.openPullRequest(properties.branch), withoutEffect, heldBack + anecdotesHeldBack,
        )
        return publication to texts + moments.associate { (file, text) -> file.path to text }
    }

    /**
     * The approved pages or stories, every one written on every publish: the claim is their source, so a hand edit of
     * their fields in `data/` shows in the preview as a change back, and goes with the next publish.
     */
    private inline fun <reified T> approved(type: ClaimType): List<T> =
        claims.findByTypeAndStatusOrderById(type, ClaimStatus.APPROVED).map { it.payload as T }

    /**
     * Why [story] cannot be published yet: cards showing fetched people (named by Q-id), whom the release holds only
     * once it carries the fetched dataset. Every one is named, to remove them in one edit.
     */
    private fun holdBack(story: Story): String? {
        if (properties.fetchedPeople) return null
        val cards = ClaimRules.fetchedPeople(story).takeIf { it.isNotEmpty() } ?: return null
        val shown = cards.entries.joinToString("; ") { (number, card) -> "card $number, ${card.label ?: card.person} (${card.person})" }
        return "${if (cards.size == 1) "One card shows a fetched person" else "${cards.size} cards show fetched people"}, " +
            "whom the release holds once it carries the dataset: $shown"
    }

    /**
     * The approved anecdotes that cannot be published yet: one resting on no source, and one about a fetched person,
     * named, whom the release holds only once it carries the fetched dataset (not one of [byHand]'s, the people written
     * by hand).
     */
    private fun holdBack(anecdotes: List<ApprovedAnecdote>, byHand: Map<String, String>): List<HeldBack> {
        val fetched = anecdotes.filter { !properties.fetchedPeople && it.anecdote.person !in byHand }
        val names = words.names(fetched.map { it.anecdote.person }, byHand)
        return anecdotes.mapNotNull { approved ->
            val person = approved.anecdote.person
            when {
                approved.source == null -> HeldBack(approved.id, "It rests on no source the site can name")
                approved in fetched ->
                    HeldBack(approved.id, "It is about a fetched person, ${names.getValue(person)} ($person), whom the release holds once it carries the dataset")
                else -> null
            }
        }
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

    /** The changes in words, for the commit and the pull request. */
    private fun summary(publication: Publication): String =
        words.summary(publication.people, publication.occupations, publication.anecdotes, publication.moments)

    private companion object {
        const val PEOPLE = "data/people.jsonl"
        const val TITLE = "feat: publish the curator's decisions"
        const val ABOUT = "Opened by the workbench when the curator published from curate. Each file is written whole from " +
            "the approved claims. Merging packs a release; a `release.lock` pull request in the code repository puts it in production."
    }
}
