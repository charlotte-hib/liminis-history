package art.liminis.publishing

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Translation
import art.liminis.dataset.DatasetStore
import art.liminis.support.UnavailableException
import art.liminis.translating.DataRecord
import art.liminis.translating.RecordKind
import art.liminis.translating.RecordTexts

/** A record whose French changes, by its key, what it is, and each text whose French changes. */
data class TranslationChange(val record: String, val label: String, val change: Change, val texts: List<TranslatedTextChange>)

/** One text's French on `main` ([before]) and once published ([after]), beside its English. */
data class TranslatedTextChange(val path: String, val en: String, val before: String?, val after: String?)

/** A file's records with the approved French beside them, the changes to their French, and the translations held back. */
data class Translated(val lines: Map<String, String>, val changes: List<TranslationChange>, val heldBack: List<HeldBack>)

/** A record's translation claim, as publishing reads it: where it stands, and its French. */
data class TranslationClaim(val status: ClaimStatus, val translation: Translation)

/**
 * What the translations make of the records of `data/`: an approved one's French written beside its English, in the
 * same file, while its English is still the English that was translated. Any other record keeps the French it has,
 * even when its English changed since (the curator: a French page never turns English): an approved translation of
 * other English is held back, and curate says the French is behind until a new one is approved. A record whose English
 * is written again (a moment's new story, an anecdote rebuilt from its claim) keeps the French of the one before, card
 * by card, as far as it holds together with the new English. A person's French also names their French Wikipedia
 * article, from the dataset.
 */
@Component
class TranslationFiles(private val claims: ClaimRepository, private val dataset: DatasetStore, jsonMapper: JsonMapper) {

    // Records keep their fields in the order they have, as the files written by hand do.
    private val mapper = jsonMapper

    /** Every translation claim, by the record it is of. */
    fun claims(): Map<String, TranslationClaim> =
        claims.findByTypeOrderById(ClaimType.TRANSLATION).map { TranslationClaim(it.status, it.payload as Translation) }.associateBy { it.translation.record }

    /**
     * [text], a file of [kind]'s records one per line ([kind] is not a moment), as lines by record, with the French of
     * [translations] beside the English; its French compared with [before]'s, the file on `main`, when it is another
     * text (anecdotes, written whole from their claims), else with [text]'s.
     */
    fun lines(kind: RecordKind, text: String?, translations: Map<String, TranslationClaim>, before: String? = text): Translated {
        val records = records(kind, text)
        val was = records(kind, before).associateBy { it.key }
        val wikipedia = if (kind == RecordKind.PERSON) frenchArticles(records, translations) else emptyMap()
        val written = records.map { record ->
            val before = was[record.key]
            val carried = before?.let { DataRecord(kind, RecordTexts.carry(kind, it.node, record.node)) } ?: record
            val (translated, node, held) = translate(carried, translations[record.key], wikipedia[record.key])
            Triple(translated, node, held ?: before?.let { lost(it, node) })
        }
        return Translated(
            written.associate { (record, node) -> record.key to mapper.writeValueAsString(node) },
            written.mapNotNull { (record, node, _) -> change(was[record.key] ?: DataRecord(kind, RecordTexts.withFrench(kind, record.node, null)), node) },
            written.mapNotNull { it.third },
        )
    }

    /**
     * [after], a moment's file as an object once its approved header and story are in it, with the French of
     * [translations] beside its English, else the French [before] had (`main`'s), each card keeping its own.
     */
    fun moment(id: String, before: ObjectNode, after: ObjectNode, translations: Map<String, TranslationClaim>): Triple<ObjectNode, TranslationChange?, HeldBack?> {
        val was = DataRecord(RecordKind.MOMENT, before, id)
        val moment = DataRecord(RecordKind.MOMENT, RecordTexts.carry(RecordKind.MOMENT, before, after), id)
        val (_, node, held) = translate(moment, translations[moment.key], null)
        return Triple(node, change(was, node), held ?: lost(was, node))
    }

    /**
     * Why a record that was in French ([before]) is now partly in English ([after]): a text added (a card, a line of the
     * world around, a participant) has no French yet, or French that no longer fits the English had to go, as the
     * release takes French that holds together with its English only.
     */
    private fun lost(before: DataRecord, after: ObjectNode): HeldBack? {
        val had = RecordTexts.french(before.kind, before.node).orEmpty()
        if (had.isEmpty()) return null
        // Texts that were in English before stay out of it: only those that lost their French, or are new.
        val wasEnglish = before.texts.map { it.first }.filter { it !in had }.toSet()
        val french = RecordTexts.french(before.kind, after).orEmpty()
        val untranslated = RecordTexts.english(before.kind, after).count { (path, _) -> path !in french && path !in wasEnglish }
        return HeldBack(before.key, "$untranslated of its texts have no French that fits their English: French visitors read them in English until you approve its update")
            .takeIf { untranslated > 0 }
    }

    private fun records(kind: RecordKind, text: String?): List<DataRecord> {
        val records = text.orEmpty().lines().filter { it.isNotBlank() }.map { line ->
            DataRecord(kind, mapper.readTree(line) as? ObjectNode ?: throw UnavailableException("${kind.path} holds a line that is not a record: $line"))
        }
        // Each line is written back under its key: two records with one key would be one.
        records.groupBy { it.key }.values.firstOrNull { it.size > 1 }?.let { throw UnavailableException("${kind.path} holds ${it[0].key} twice") }
        return records
    }

    private fun translate(record: DataRecord, claim: TranslationClaim?, article: String?): Triple<DataRecord, ObjectNode, HeldBack?> {
        if (claim == null || claim.status != ClaimStatus.APPROVED) return Triple(record, record.node, null)
        if (!record.matches(claim.translation.texts)) {
            val held = HeldBack(record.key, "Its English changed since its French was approved: French visitors read the French of the English before until you approve its update")
            return Triple(record, record.node, held)
        }
        val french = claim.translation.texts.associate { it.path to it.fr }
        return Triple(record, RecordTexts.withFrench(record.kind, record.node, french, listOfNotNull(article?.let { "wikipedia" to it }).toMap()), null)
    }

    private fun change(before: DataRecord, after: ObjectNode): TranslationChange? {
        val was = RecordTexts.french(before.kind, before.node).orEmpty()
        val will = RecordTexts.french(before.kind, after).orEmpty()
        if (was == will) return null
        // In the order of the record once published, then the texts it no longer has.
        val english = before.texts.toMap() + RecordTexts.english(before.kind, after).toMap()
        val paths = RecordTexts.english(before.kind, after).map { it.first } + before.texts.map { it.first }
        val texts = paths.distinct().mapNotNull { path ->
            TranslatedTextChange(path, english.getValue(path), was[path], will[path]).takeIf { was[path] != will[path] }
        }
        val change = when {
            was.isEmpty() -> Change.ADDED
            will.isEmpty() -> Change.REMOVED
            else -> Change.CHANGED
        }
        return TranslationChange(before.key, before.label, change, texts)
    }

    /** The titles of the French Wikipedia articles of the people of [records] being translated, by record, from their Q-ids. */
    private fun frenchArticles(records: List<DataRecord>, translations: Map<String, TranslationClaim>): Map<String, String> {
        val qids = records.filter { translations[it.key]?.status == ClaimStatus.APPROVED }.mapNotNull { record -> record.node["qid"]?.asString()?.let { record.key to it } }.toMap()
        val titles = dataset.frenchArticles(qids.values)
        return qids.mapNotNull { (key, qid) -> titles[qid]?.let { key to it.replace(' ', '_') } }.toMap()
    }
}
