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

/**
 * What the approved translations make of the records of `data/`: each one's French written beside its English, in the
 * same file, while its English is still the English that was translated. One whose English changed since is held
 * back, and the French the record had goes too, as it was of other English: the site shows it in English until it is
 * translated again. Records with no approved translation keep the French they have, if any. A person's French also
 * names their French Wikipedia article, from the dataset.
 */
@Component
class TranslationFiles(private val claims: ClaimRepository, private val dataset: DatasetStore, jsonMapper: JsonMapper) {

    // Records keep their fields in the order they have, as the files written by hand do.
    private val mapper = jsonMapper

    /** The approved translations, by the record they are of. */
    fun approved(): Map<String, Translation> =
        claims.findByTypeAndStatusOrderById(ClaimType.TRANSLATION, ClaimStatus.APPROVED).map { it.payload as Translation }.associateBy { it.record }

    /**
     * [text], a file of [kind]'s records one per line ([kind] is not a moment), as lines by record, with the French of
     * [approved] beside the English.
     */
    fun lines(kind: RecordKind, text: String?, approved: Map<String, Translation>): Translated {
        val records = text.orEmpty().lines().filter { it.isNotBlank() }.map { line ->
            DataRecord(kind, mapper.readTree(line) as? ObjectNode ?: throw UnavailableException("${kind.path} holds a line that is not a record: $line"))
        }
        val wikipedia = if (kind == RecordKind.PERSON) frenchArticles(records, approved) else emptyMap()
        val written = records.map { record -> translate(record, approved[record.key], wikipedia[record.key]) }
        return Translated(
            written.associate { (record, node) -> record.key to mapper.writeValueAsString(node) },
            written.mapNotNull { (record, node, _) -> change(record, node) },
            written.mapNotNull { it.third },
        )
    }

    /**
     * [after], a moment's file as an object once its approved header and story are in it, with the French of [approved]
     * beside its English; [before], as `main` has it. With no translation approved, a moment whose English changes loses
     * the French it had, of the English before.
     */
    fun moment(id: String, before: ObjectNode, after: ObjectNode, approved: Map<String, Translation>): Triple<ObjectNode, TranslationChange?, HeldBack?> {
        val was = DataRecord(RecordKind.MOMENT, before, id)
        val moment = DataRecord(RecordKind.MOMENT, after, id)
        val translation = approved[moment.key]
        val (_, node, held) = when {
            translation != null -> translate(moment, translation, null)
            was.texts != moment.texts -> Triple(moment, RecordTexts.withFrench(RecordKind.MOMENT, after, null), null)
            else -> Triple(moment, RecordTexts.withFrench(RecordKind.MOMENT, after, RecordTexts.french(RecordKind.MOMENT, before)), null)
        }
        return Triple(node, change(was, node), held)
    }

    private fun translate(record: DataRecord, translation: Translation?, article: String?): Triple<DataRecord, ObjectNode, HeldBack?> {
        if (translation == null) return Triple(record, record.node, null)
        if (!record.matches(translation.texts)) {
            val held = HeldBack(record.key, "Its English changed since its French was approved: translate it again. Until then the site shows it in English")
            return Triple(record, RecordTexts.withFrench(record.kind, record.node, null), held)
        }
        val french = translation.texts.associate { it.path to it.fr }
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
    private fun frenchArticles(records: List<DataRecord>, approved: Map<String, Translation>): Map<String, String> {
        val qids = records.filter { it.key in approved }.mapNotNull { record -> record.node["qid"]?.asString()?.let { record.key to it } }.toMap()
        val titles = dataset.frenchArticles(qids.values)
        return qids.mapNotNull { (key, qid) -> titles[qid]?.let { key to it.replace(' ', '_') } }.toMap()
    }
}
