package art.liminis.publishing

import org.springframework.stereotype.Component
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.OccupationChoice
import art.liminis.curation.OccupationDomain
import art.liminis.curation.PersonFacts
import art.liminis.curation.Story
import art.liminis.dataset.DatasetStore
import art.liminis.domain.CardType
import art.liminis.domain.Domain
import art.liminis.domain.PlaceKind
import art.liminis.release.AnecdoteRecord
import art.liminis.release.ArtRecord
import art.liminis.release.CardRecord
import art.liminis.release.CitedSourceRecord
import art.liminis.release.ClearedField
import art.liminis.release.ConnectionRecord
import art.liminis.release.CorrectionPlaceRecord
import art.liminis.release.CorrectionRecord
import art.liminis.release.WorldRecord
import art.liminis.repository.EventRepository
import art.liminis.repository.LifeRepository
import art.liminis.repository.PersonRepository
import java.text.Collator
import java.util.Locale

/** A person whose correction changes, with each fact the site would show otherwise: in effect on main, then after. */
data class PersonChange(val person: String, val name: String, val change: Change, val facts: List<FactChange>)

data class FactChange(val fact: Fact, val before: String?, val after: String?)

enum class Fact { NAME, FRENCH_NAME, BORN, BORN_PRECISION, DIED, DIED_PRECISION, DIED_ESTIMATED, DATES_APPROXIMATE, OCCUPATIONS, BIRTHPLACE, DEATHPLACE, WORK_PLACES, RESIDENCES, WIKIPEDIA, WIKIPEDIA_FR }

/** An occupation whose domain changes: [before] none when main does not map it, [after] none when it no longer is. */
data class OccupationChange(val occupation: String, val label: String, val change: Change, val before: OccupationChoice?, val after: OccupationChoice?)

/**
 * A moment whose header or story changes: its hook and its painting (by its credit), if they change, the lines of its
 * world that do, and its cards.
 */
data class MomentChange(
    val moment: String,
    val title: String,
    val hook: TextChange?,
    val world: List<WorldChange>,
    val painting: TextChange?,
    val cards: List<CardChange>,
    val unchangedCards: Int,
)

data class TextChange(val before: String?, val after: String?)

data class WorldChange(val part: WorldPart, val before: String?, val after: String?)

enum class WorldPart { GOVERNS, EVERYDAY, ARTS, MEANWHILE }

/** A card added, removed, changed or [moved] among the others, numbered [from] on main and [to] once published. */
data class CardChange(
    val change: Change,
    val type: CardType,
    val shows: String?,
    val label: String?,
    val from: Int?,
    val to: Int?,
    val moved: Boolean,
    val fields: List<CardFieldChange>,
)

data class CardFieldChange(val field: CardField, val before: String?, val after: String?)

enum class CardField { KICKER, TITLE, TEXT, YEAR, PAINTING }

/** An anecdote added, removed or changed, about [person] in [year] (once published, else on main), with its fields that change. */
data class AnecdoteChange(val anecdote: String, val person: String, val name: String, val change: Change, val year: Int, val fields: List<AnecdoteFieldChange>)

data class AnecdoteFieldChange(val field: AnecdoteField, val before: String?, val after: String?)

enum class AnecdoteField { YEAR, TEXT, SOURCE }

/**
 * A connection added, removed or changed, between [people] (their [names]) as [kind], once published, else on main, with
 * its fields that change. One whose kind changes is removed under the old kind and added under the new.
 */
data class ConnectionChange(
    val connection: String,
    val people: List<String>,
    val names: List<String>,
    val kind: String,
    val change: Change,
    val fields: List<ConnectionFieldChange>,
)

data class ConnectionFieldChange(val field: ConnectionField, val before: String?, val after: String?)

enum class ConnectionField { YEAR, TEXT, SOURCE }

/**
 * The changes of a publish in words, for the curator to read before publishing and in the pull request: people by
 * name with the facts the site would show otherwise, occupations by label with their domains, anecdotes by whom they are
 * about, and each moment's header lines and cards. A correction holds only the fields corrected, so a fact's value in
 * effect is the correction's, else Wikidata's, from the person's claim.
 */
@Component
class ChangesInWords(
    private val dataFiles: DataFiles,
    private val momentFiles: MomentFiles,
    private val claims: ClaimRepository,
    private val releasePeople: PersonRepository,
    private val releaseLives: LifeRepository,
    private val releaseEvents: EventRepository,
    private val dataset: DatasetStore,
) {

    private val byName: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    /** The people of [changes], those of `corrections.jsonl`, by name. */
    fun people(changes: List<RecordChange>): List<PersonChange> {
        val facts = claims.findAllById(changes.map { ClaimRules.factsId(it.id) }).associateBy { it.id }
        return changes.map { change ->
            val claim = facts[ClaimRules.factsId(change.id)]
            val proposal = claim?.proposal as PersonFacts?
            val approved = claim?.payload as PersonFacts?
            val before = change.before?.let(dataFiles::readCorrection)
            val after = change.after?.let(dataFiles::readCorrection)
            val labels = (proposal?.occupations.orEmpty() + approved?.occupations.orEmpty()).associate { it.occupation to it.label }
            val places = placeLabels(listOfNotNull(proposal, approved), listOfNotNull(before, after))

            fun <T> fact(fact: Fact, corrected: (CorrectionRecord) -> T?, wikidata: (PersonFacts) -> T?, cleared: ClearedField? = null, words: (T) -> String): FactChange? {
                fun inEffect(correction: CorrectionRecord?) =
                    if (cleared != null && correction?.cleared.orEmpty().contains(cleared)) null else correction?.let(corrected) ?: proposal?.let(wikidata)
                val was = inEffect(before)
                val will = inEffect(after)
                return if (was == will) null else FactChange(fact, was?.let(words), will?.let(words))
            }
            fun places(fact: Fact, kind: PlaceKind) = fact(
                fact,
                { correction -> correction.places?.filter { it.kind == kind } },
                { facts -> facts.places.filter { it.role.name == kind.name }.map { CorrectionPlaceRecord(kind, it.place, it.startYear, it.endYear) } },
            ) { held -> held.joinToString(", ") { placeInWords(it, places) }.ifEmpty { "none" } }
            val name = approved?.label ?: proposal?.label ?: after?.name ?: before?.name ?: change.id
            PersonChange(
                change.id, name, change.change,
                listOfNotNull(
                    fact(Fact.NAME, CorrectionRecord::name, PersonFacts::label) { it },
                    fact(Fact.FRENCH_NAME, CorrectionRecord::nameFr, PersonFacts::labelFr, ClearedField.NAME_FR) { it },
                    fact(Fact.BORN, CorrectionRecord::born, PersonFacts::born, words = ::year),
                    fact(Fact.BORN_PRECISION, CorrectionRecord::bornPrecision, PersonFacts::bornPrecision, words = ::precision),
                    fact(Fact.DIED, CorrectionRecord::died, PersonFacts::died, words = ::year),
                    fact(Fact.DIED_PRECISION, CorrectionRecord::diedPrecision, PersonFacts::diedPrecision, ClearedField.DIED_PRECISION, ::precision),
                    fact(Fact.DIED_ESTIMATED, CorrectionRecord::diedEstimated, PersonFacts::diedEstimated) { if (it) "yes" else "no" },
                    fact(Fact.DATES_APPROXIMATE, CorrectionRecord::approximate, PersonFacts::datesApproximate) { if (it) "yes" else "no" },
                    fact(Fact.OCCUPATIONS, CorrectionRecord::occupations, { facts -> facts.occupations.map { it.occupation } }) { ids ->
                        ids.joinToString(", ") { labels[it] ?: it }
                    },
                    places(Fact.BIRTHPLACE, PlaceKind.BIRTH),
                    places(Fact.DEATHPLACE, PlaceKind.DEATH),
                    places(Fact.WORK_PLACES, PlaceKind.WORK),
                    places(Fact.RESIDENCES, PlaceKind.RESIDENCE),
                    fact(Fact.WIKIPEDIA, { null }, PersonFacts::enwiki, ClearedField.WIKIPEDIA) { it },
                    fact(Fact.WIKIPEDIA_FR, { null }, PersonFacts::frwiki, ClearedField.WIKIPEDIA_FR) { it },
                ),
            )
        }.sortedWith(compareBy(byName) { it: PersonChange -> it.name }.thenBy { it.person })
    }

    /** The labels of the places [facts] and [corrections] name: from the claims, else from the dataset. */
    private fun placeLabels(facts: List<PersonFacts>, corrections: List<CorrectionRecord>): Map<String, String> {
        val known = facts.flatMap { it.places }.associate { it.place to it.label }
        val unknown = corrections.flatMap { it.places.orEmpty() }.map { it.place }.filter { it !in known }
        return known + if (unknown.isEmpty()) emptyMap() else dataset.placeLabels(unknown)
    }

    private fun placeInWords(place: CorrectionPlaceRecord, labels: Map<String, String>): String {
        val (start, end) = place.start to place.end
        val years = when {
            start != null && end != null -> " (${year(start)}–${year(end)})"
            start != null -> " (from ${year(start)})"
            end != null -> " (until ${year(end)})"
            else -> ""
        }
        return (labels[place.place] ?: place.place) + years
    }

    /** The occupations of [changes], those of `occupations.jsonl`, by label. */
    fun occupations(changes: List<RecordChange>): List<OccupationChange> {
        val labels = claims.findAllById(changes.map { ClaimRules.occupationId(it.id) })
            .map { it.payload as OccupationDomain }.associate { it.occupation to it.label }
        return changes.map { change ->
            OccupationChange(
                change.id, labels[change.id] ?: change.id, change.change,
                change.before?.let { choice(dataFiles.readOccupation(it).domain) },
                change.after?.let { choice(dataFiles.readOccupation(it).domain) },
            )
        }.sortedWith(compareBy(byName) { it: OccupationChange -> it.label }.thenBy { it.occupation })
    }

    /** The anecdotes of [changes], those of `anecdotes.jsonl`, by the name of whom they are about, then by year. */
    fun anecdotes(changes: List<RecordChange>, byHand: Map<String, String>): List<AnecdoteChange> {
        val records = changes.associate { it.id to (it.before?.let(dataFiles::readAnecdote) to it.after?.let(dataFiles::readAnecdote)) }
        val names = names(records.values.map { (before, after) -> (after ?: before)!!.person }, byHand)
        return changes.map { change ->
            val (before, after) = records.getValue(change.id)
            val anecdote = (after ?: before)!!
            val fields = listOf(
                AnecdoteField.YEAR to { record: AnecdoteRecord -> year(record.year) },
                AnecdoteField.TEXT to { record -> record.text },
                AnecdoteField.SOURCE to { record -> source(record.source) },
            ).mapNotNull { (field, value) ->
                val old = before?.let(value)
                val new = after?.let(value)
                if (old == new) null else AnecdoteFieldChange(field, old, new)
            }
            AnecdoteChange(change.id, anecdote.person, names.getValue(anecdote.person), change.change, anecdote.year, fields)
        // A line whose French alone changes is told with the translations.
        }.filter { it.change != Change.CHANGED || it.fields.isNotEmpty() }.sortedWith(compareBy(byName) { it: AnecdoteChange -> it.name }.thenBy { it.year }.thenBy { it.anecdote })
    }

    /**
     * The connections of [changes], those of `connections.jsonl`, by the names of their people, then by kind. A line
     * written by hand on main names its people by id: they are named as such.
     */
    fun connections(changes: List<RecordChange>, byHand: Map<String, String>): List<ConnectionChange> {
        val records = changes.associate { it.id to (it.before?.let(dataFiles::readConnection) to it.after?.let(dataFiles::readConnection)) }
        val names = names(records.values.flatMap { (before, after) -> (after ?: before)!!.people }, byHand)
        return changes.map { change ->
            val (before, after) = records.getValue(change.id)
            val connection = (after ?: before)!!
            val fields = listOf(
                ConnectionField.YEAR to { record: ConnectionRecord -> year(record.year) },
                ConnectionField.TEXT to { record -> record.text },
                ConnectionField.SOURCE to { record -> source(record.source) },
            ).mapNotNull { (field, value) ->
                val old = before?.let(value)
                val new = after?.let(value)
                if (old == new) null else ConnectionFieldChange(field, old, new)
            }
            ConnectionChange(change.id, connection.people, connection.people.map(names::getValue), connection.kind, change.change, fields)
        // A line whose French alone changes is told with the translations.
        }.filter { it.change != Change.CHANGED || it.fields.isNotEmpty() }
            .sortedWith(compareBy(byName) { it: ConnectionChange -> it.names[0] }.thenBy(byName) { it.names[1] }.thenBy { it.kind })
    }

    /** The names of the people of [qids]: as written by hand in `people.jsonl` ([byHand]), else as the dataset has them, else their Q-id. */
    fun names(qids: Collection<String>, byHand: Map<String, String>): Map<String, String> {
        val fetched = qids.filter { it !in byHand }.distinct()
        val labels = dataset.summaries(fetched, fetched.size).associate { it.person to it.label }
        return qids.associateWith { byHand[it] ?: labels[it] ?: it }
    }

    /** What changes in [moment]'s file from [before] to [after], its [story] naming whom or what its new cards show. */
    fun moment(moment: String, before: String, after: String, story: Story?): MomentChange {
        val was = momentFiles.content(before)
        val will = momentFiles.content(after)
        val labels = story?.cards.orEmpty().mapNotNull { card -> card.label?.let { shown(card.type, card.person, card.life, card.event) to it } }.toMap()
        val (cards, unchanged) = cards(was.cards, will.cards, labels)
        val world = listOf(
            WorldPart.GOVERNS to { w: WorldRecord -> w.governs },
            WorldPart.EVERYDAY to { w -> w.everyday },
            WorldPart.ARTS to { w -> w.arts },
            WorldPart.MEANWHILE to { w -> w.meanwhile },
        ).mapNotNull { (part, line) ->
            val old = was.world?.let(line)
            val new = will.world?.let(line)
            if (old == new) null else WorldChange(part, old, new)
        }
        return MomentChange(
            moment, will.title, TextChange(was.hook, will.hook).takeIf { was.hook != will.hook }, world,
            painting(was.art, will.art), cards, unchanged,
        )
    }

    /**
     * The cards of [after] against those of [before]: the same card is the next one showing the same person, life or
     * event (a scene card, the next of its type). A card moved is one out of the longest run that keeps its order.
     */
    private fun cards(before: List<CardRecord>, after: List<CardRecord>, known: Map<Shown, String>): Pair<List<CardChange>, Int> {
        val unused = before.indices.toMutableList()
        val matched = after.map { card -> unused.firstOrNull { shown(before[it]) == shown(card) }?.also { unused.remove(it) } }
        val kept = inOrder(matched.filterNotNull())
        val labels = labels((before + after).map(::shown).filter { it !in known }.toSet()) + known

        var unchanged = 0
        val changes = mutableListOf<CardChange>()
        after.forEachIndexed { to, card ->
            val from = matched[to]
            val old = from?.let(before::get)
            val fields = fields(old, card)
            val moved = from != null && from !in kept
            if (old != null && fields.isEmpty() && !moved) unchanged++
            else changes += change(if (old == null) Change.ADDED else Change.CHANGED, card, labels, from?.plus(1), to + 1, moved, fields)
        }
        for (from in unused) changes += change(Change.REMOVED, before[from], labels, from + 1, null, false, fields(before[from], null))
        return changes to unchanged
    }

    private fun change(change: Change, card: CardRecord, labels: Map<Shown, String>, from: Int?, to: Int?, moved: Boolean, fields: List<CardFieldChange>) =
        shown(card).let { CardChange(change, card.type, it.id, labels[it], from, to, moved, fields) }

    private fun fields(before: CardRecord?, after: CardRecord?): List<CardFieldChange> = listOf(
        CardField.KICKER to { card: CardRecord -> card.kicker },
        CardField.TITLE to { card -> card.title },
        CardField.TEXT to { card -> card.text },
        CardField.YEAR to { card -> card.year?.let(::year) },
    ).mapNotNull { (field, value) ->
        val old = before?.let(value)
        val new = after?.let(value)
        if (old == new) null else CardFieldChange(field, old, new)
    } + listOfNotNull(painting(before?.art, after?.art)?.let { CardFieldChange(CardField.PAINTING, it.before, it.after) })

    /** A painting that changes, by its credit: another image under the same credit says so. */
    private fun painting(before: ArtRecord?, after: ArtRecord?): TextChange? {
        if (before == after) return null
        val credit = after?.credit?.let { if (it == before?.credit) "$it (another image)" else it }
        return TextChange(before?.credit, credit)
    }

    /** The names of whom and what [shown] cards show: the release's people, lives and events, else the dataset's people. */
    private fun labels(shown: Set<Shown>): Map<Shown, String> {
        val ids = { type: CardType -> shown.filter { it.type == type }.mapNotNull { it.id } }
        val people = releasePeople.findAllById(ids(CardType.PERSON)).associate { it.id to it.name }
        val fetched = ids(CardType.PERSON).filter { it !in people }
        val names = people +
            dataset.summaries(fetched, fetched.size).associate { it.person to it.label } +
            releaseLives.findAllById(ids(CardType.LIFE)).associate { it.id to it.label } +
            releaseEvents.findAllById(ids(CardType.EVENT)).associate { it.id to it.title }
        return shown.mapNotNull { card -> card.id?.let(names::get)?.let { card to it } }.toMap()
    }

    /**
     * The publication in words, as Markdown for the pull request and its commit: each person's facts, the
     * occupations changed or removed one by one and the ones added by domain, each anecdote, and each moment's header
     * and cards. Cut short within GitHub's limit on a pull request's body.
     */
    fun summary(
        people: List<PersonChange>,
        occupations: List<OccupationChange>,
        anecdotes: List<AnecdoteChange>,
        connections: List<ConnectionChange>,
        moments: List<MomentChange>,
        translations: List<TranslationChange>,
    ): String {
        val lines = mutableListOf<String>()
        if (people.isNotEmpty()) {
            lines += "**People's facts** (${people.size})"
            lines += people.map { person ->
                "- ${person.name} (${person.person}): " +
                    person.facts.joinToString("; ") { "${FACTS.getValue(it.fact)} ${it.before ?: "none"} → ${it.after ?: "none"}" }
                        .ifEmpty { "no change the site shows" }
            }
        }
        if (occupations.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "**Occupations** (${occupations.size})"
            lines += occupations.filter { it.before != null }.map { "- ${it.label}: ${domain(it.before)} → ${domain(it.after)}" }
            lines += occupations.filter { it.before == null }.groupBy { it.after!! }.toSortedMap().map { (choice, added) ->
                val shown = added.take(NAMES_PER_DOMAIN).joinToString(", ") { it.label }
                val more = (added.size - NAMES_PER_DOMAIN).takeIf { it > 0 }?.let { " and $it more" }.orEmpty()
                "- ${domain(choice)} (${added.size}): $shown$more"
            }
        }
        if (anecdotes.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "**Anecdotes** (${anecdotes.size})"
            lines += anecdotes.map { anecdote ->
                val change = when (anecdote.change) {
                    Change.ADDED -> "added"
                    Change.REMOVED -> "removed"
                    Change.CHANGED -> anecdote.fields.joinToString(", ") { ANECDOTE_FIELDS.getValue(it.field) } + " changed"
                }
                "- ${anecdote.name} (${anecdote.person}), ${year(anecdote.year)}: $change"
            }
        }
        if (connections.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "**Connections** (${connections.size})"
            lines += connections.map { connection ->
                val change = when (connection.change) {
                    Change.ADDED -> "added"
                    Change.REMOVED -> "removed"
                    Change.CHANGED -> connection.fields.joinToString(", ") { CONNECTION_FIELDS.getValue(it.field) } + " changed"
                }
                "- ${connection.names[0]} and ${connection.names[1]}, ${connection.kind}: $change"
            }
        }
        if (moments.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "**Moments** (${moments.size})"
            lines += moments.map { moment ->
                val parts = listOfNotNull(moment.hook?.let { "hook changed" }) +
                    moment.world.map { "${WORLD.getValue(it.part)} changed" } +
                    listOfNotNull(moment.painting?.let { "painting changed" }) +
                    moment.cards.map(::cardInWords)
                "- ${moment.title} (${moment.moment}): ${parts.joinToString("; ")}"
            }
        }
        if (translations.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "**French** (${translations.size})"
            lines += translations.map { translation ->
                val change = when (translation.change) {
                    Change.ADDED -> "translated"
                    Change.REMOVED -> "French removed, its English changed"
                    Change.CHANGED -> "${translation.texts.size} ${if (translation.texts.size == 1) "text" else "texts"} changed"
                }
                "- ${translation.label} (${translation.record}): $change"
            }
        }
        val text = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            if (text.length + line.length > MAX_SUMMARY) {
                text.append("- … and ${lines.size - index} more lines: the files show every change.\n")
                break
            }
            text.append(line).append('\n')
        }
        return text.toString().trimEnd()
    }

    private fun cardInWords(card: CardChange): String {
        val shows = card.label ?: card.shows ?: card.type.name.lowercase()
        val name = "card ${card.to}, $shows"
        return when (card.change) {
            Change.ADDED -> "$name added"
            Change.REMOVED -> "$shows removed (card ${card.from} before)"
            Change.CHANGED -> listOfNotNull(
                card.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") { CARD_FIELDS.getValue(it.field) }?.let { "$it changed" },
                "moved from card ${card.from}".takeIf { card.moved },
            ).joinToString(", ").let { "$name: $it" }
        }
    }

    /** A source in words: its title, where in it, and its link. */
    private fun source(source: CitedSourceRecord) = listOfNotNull(
        source.title, source.page?.let { "p. $it" }, source.episode?.let { "episode $it" }, source.minute?.let { "at $it" }, source.url,
    ).joinToString(", ")

    private fun choice(domain: Domain?) = domain?.let { OccupationChoice.valueOf(it.name) } ?: OccupationChoice.NONE

    /** A domain as curate names it when the curator chooses it. */
    private fun domain(choice: OccupationChoice?) = when (choice) {
        null -> "not mapped"
        OccupationChoice.POWER -> "Power"
        OccupationChoice.ARTS -> "Arts and ideas"
        OccupationChoice.EVERYDAY -> "Everyday"
        OccupationChoice.NONE -> "None"
    }

    /** Whom or what a card shows: its type and the id it names. */
    private data class Shown(val type: CardType, val id: String?)

    private fun shown(card: CardRecord) = shown(card.type, card.person, card.life, card.event)

    private fun shown(type: CardType, person: String?, life: String?, event: String?) = Shown(type, person ?: life ?: event)

    private companion object {
        const val NAMES_PER_DOMAIN = 100

        /** Below GitHub's 65,536 characters for a pull request's body, with room for its last paragraph. */
        const val MAX_SUMMARY = 60_000

        val FACTS = mapOf(
            Fact.NAME to "name", Fact.FRENCH_NAME to "French name", Fact.BORN to "born", Fact.DIED to "died",
            Fact.DATES_APPROXIMATE to "dates approximate", Fact.OCCUPATIONS to "occupations",
            Fact.BORN_PRECISION to "birth known to the", Fact.DIED_PRECISION to "death known to the", Fact.DIED_ESTIMATED to "death estimated",
            Fact.BIRTHPLACE to "birthplace", Fact.DEATHPLACE to "death place", Fact.WORK_PLACES to "work places",
            Fact.RESIDENCES to "residences", Fact.WIKIPEDIA to "English Wikipedia", Fact.WIKIPEDIA_FR to "French Wikipedia",
        )
        // As curate names them.
        val WORLD = mapOf(
            WorldPart.GOVERNS to "who governs", WorldPart.EVERYDAY to "everyday life", WorldPart.ARTS to "arts and ideas",
            WorldPart.MEANWHILE to "meanwhile, elsewhere",
        )
        val ANECDOTE_FIELDS = mapOf(AnecdoteField.YEAR to "year", AnecdoteField.TEXT to "text", AnecdoteField.SOURCE to "source")
        val CONNECTION_FIELDS = mapOf(ConnectionField.YEAR to "year", ConnectionField.TEXT to "text", ConnectionField.SOURCE to "source")
        val CARD_FIELDS = mapOf(
            CardField.KICKER to "kicker", CardField.TITLE to "title", CardField.TEXT to "what visitors read", CardField.YEAR to "year",
            CardField.PAINTING to "painting",
        )

        /** Wikidata's precision of a date in words: "day", "decade". */
        fun precision(precision: Int) = PRECISIONS[precision] ?: "precision $precision"

        private val PRECISIONS = mapOf(11 to "day", 10 to "month", 9 to "year", 8 to "decade", 7 to "century")

        /** A year in words: BCE for the negative ones. */
        fun year(year: Int) = if (year < 0) "${-year} BCE" else "$year"

        /**
         * The before-indices of [matched] (in the order of the cards after) that keep their order: the longest
         * increasing run. The others moved.
         */
        fun inOrder(matched: List<Int>): Set<Int> {
            if (matched.isEmpty()) return emptySet()
            val length = IntArray(matched.size) { 1 }
            val previous = IntArray(matched.size) { -1 }
            for (i in matched.indices) for (j in 0 until i) {
                if (matched[j] < matched[i] && length[j] + 1 > length[i]) {
                    length[i] = length[j] + 1
                    previous[i] = j
                }
            }
            val run = mutableSetOf<Int>()
            var at = length.indices.maxBy { length[it] }
            while (at >= 0) {
                run += matched[at]
                at = previous[at]
            }
            return run
        }
    }
}
