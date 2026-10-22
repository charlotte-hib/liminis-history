package art.liminis.translating

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import art.liminis.curation.Claim
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Claims
import art.liminis.curation.DecisionRepository
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.Origin
import art.liminis.curation.Proposal
import art.liminis.curation.ProposalCounts
import art.liminis.curation.TranslatedText
import art.liminis.curation.Translation
import art.liminis.dataset.DatasetStore
import art.liminis.drafting.DraftingProperties
import art.liminis.support.NotFoundException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Drafts in French, with Claude, every record of `data/` whose texts have no French yet, or one record
 * (`wb translate`, curate's Français tabs and its Publish page), with the English publishing will write
 * ([TranslationSources]), and proposes each as a `TRANSLATION` claim flagged with anything the validator found. A
 * record translated, approved or under review with the English it has now is left alone; one whose English changed is
 * translated again, keeping the French of the texts whose English stayed the same; one the curator rejected is
 * drafted again from nothing, Claude told why. People's names are Wikidata's French labels, not translated. Short
 * records go first, so the longer ones are translated with their French in the glossary: regions, eras, people, lives,
 * events, connections, anecdotes, then moments. Each batch is proposed as soon as it is translated: a batch that fails
 * is named in the result and loses no other. Needs an Anthropic API key in the environment.
 */
@Component
class Translating(
    private val sources: TranslationSources,
    private val claims: Claims,
    private val reviewed: ClaimRepository,
    private val decisions: DecisionRepository,
    private val translator: Translator,
    private val dataset: DatasetStore,
    private val properties: DraftingProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Translates what has no French yet, or only [record] if given, proposes it, and says what it came to and cost. */
    fun translate(record: String? = null): String {
        val base = sources.base()
        val records = if (record == null) sources.records(base) else listOf(sources.record(base, record) ?: throw NotFoundException("No record $record"))
        val known = reviewed.findByTypeOrderById(ClaimType.TRANSLATION).associateBy { (it.payload as Translation).record }
        val names = names(records)
        // Approved French last, so it wins over French still under review for the same English.
        val glossary = known.values.sortedBy { it.status == ClaimStatus.APPROVED }.flatMap { terms(it.payload as Translation) }.toMap().toMutableMap()

        val cost = Cost()
        val failed = mutableListOf<String>()
        var counts = ProposalCounts(0, 0, 0)
        var proposed = 0
        var flagged = 0
        for (kind in RecordKind.entries) {
            val missing = records.filter { it.kind == kind }.mapNotNull { missing(it, known[it.key], names) }
            for (batch in batches(kind, missing)) {
                val terms = glossary.filterKeys { term -> batch.any { it.record.texts.any { (_, en) -> en.contains(term) } } }
                val asked = batch.filter { it.toTranslate.paths.isNotEmpty() }
                val drafted = try {
                    if (asked.isEmpty()) emptyMap() else translator.translate(asked.map { it.toTranslate }, terms).also(cost::add).draft.records
                        .associate { it.record to it.texts.associate { text -> text.path to text.fr } }
                } catch (e: Exception) {
                    log.warn("Could not translate {}", asked.joinToString { it.record.key }, e)
                    failed += asked.map { it.record.key }
                    continue
                }
                val proposals = batch.map { missing ->
                    val french = drafted[missing.record.key].orEmpty()
                    val texts = missing.record.texts.map { (path, en) -> TranslatedText(path, en, missing.known[path] ?: french[path].orEmpty()) }
                    val translation = Translation(missing.record.key, missing.record.label, texts)
                    // Short labels translated now are the glossary of the longer records after them.
                    glossary += terms(translation)
                    val problems = TranslationValidator.problems(texts, terms) + texts.filter { it.fr.isBlank() }.map { "${it.path}: not translated" }
                    Proposal(translation, Origin.CLAUDE, problems.map { FlagReason(Flag.ODD, it) }, emptyList())
                }
                counts += claims.propose(proposals)
                proposed += proposals.size
                flagged += proposals.count { it.flags.isNotEmpty() }
            }
        }
        log.info("Translated {} records with {}: {} input and {} output tokens, \${}; {} failed", proposed, cost.model, cost.input, cost.output, cost.dollars(), failed.size)
        val priced = if (cost.model == properties.model) "" else " at ${properties.model}'s price, though ${cost.model} wrote some"
        val failures = if (failed.isEmpty()) "" else "; ${failed.size} could not be translated, try again: ${failed.joinToString(", ")}"
        return "Translated $proposed ${if (proposed == 1) "record" else "records"} ($counts), $flagged to look at, in ${cost.calls} calls; " +
            "%,d input and %,d output tokens, about \$%s%s%s".format(Locale.ROOT, cost.input, cost.output, cost.dollars(), priced, failures)
    }

    /** A record to translate, with the French known already, by path, and what Claude is to translate. */
    private class Missing(val record: DataRecord, val known: Map<String, String>, val toTranslate: ToTranslate)

    /**
     * What of [record] still needs French, with the French known already; nothing when it needs none: it is
     * translated, approved or under review, with the English it has now, or, with no claim, its file has French for all
     * its texts (written by hand). French known already: the claim's for the texts whose English it had, the curator's
     * name or else Wikidata's, and, with no claim, the file's. A rejected claim keeps none: drafted again from nothing,
     * with the curator's note.
     */
    private fun missing(record: DataRecord, claim: Claim?, names: Map<String, String>): Missing? {
        if (record.texts.isEmpty()) return null
        val translation = claim?.payload as Translation?
        val rejected = claim?.status == ClaimStatus.REJECTED
        if (translation != null && !rejected && record.matches(translation.texts)) return null
        val written = RecordTexts.french(record.kind, record.node).orEmpty()
        if (translation == null && written.keys == record.texts.map { it.first }.toSet()) return null
        val kept = translation?.texts.orEmpty().filter { !rejected && it.fr.isNotBlank() && (it.path to it.en) in record.texts }.associate { it.path to it.fr }
        val named = names[record.key]?.takeIf { record.texts.any { (path, _) -> path == "name" } }?.let { mapOf("name" to it) }.orEmpty()
        val known = (if (translation == null) written else emptyMap()) + named + kept
        val note = if (rejected) decisions.findByClaimOrderById(claim.id).lastOrNull()?.note else null
        val paths = record.texts.map { it.first }.filter { it !in known }
        // The French written for the English before, of a text that changed since: Claude updates it.
        val updates = if (rejected) emptyMap() else translation?.texts.orEmpty()
            .filter { it.path in paths && it.fr.isNotBlank() }
            .associate { it.path to Before(it.en, it.fr) }
        return Missing(record, known, ToTranslate(record.key, record.label, record.texts, paths, note, updates))
    }

    /** Batches of records to translate in one call: a moment's texts are many, short records go a few dozen at a time. */
    private fun batches(kind: RecordKind, records: List<Missing>): List<List<Missing>> {
        val batches = mutableListOf<MutableList<Missing>>()
        var texts = 0
        for (record in records) {
            val size = record.record.texts.size
            if (batches.isEmpty() || kind == RecordKind.MOMENT || texts + size > TEXTS_PER_CALL) {
                batches += mutableListOf<Missing>()
                texts = 0
            }
            batches.last() += record
            texts += size
        }
        return batches
    }

    /** The French names of the people written by hand, by record, from Wikidata's labels through their Q-ids. */
    private fun names(records: List<DataRecord>): Map<String, String> {
        val qids = records.filter { it.kind == RecordKind.PERSON }.mapNotNull { record -> record.node["qid"]?.asString()?.let { record.key to it } }.toMap()
        val labels = dataset.frenchLabels(qids.values)
        return qids.mapNotNull { (key, qid) -> labels[qid]?.let { key to it } }.toMap()
    }

    /** The terms a translation adds to the glossary: its short labels, names and periods. */
    private fun terms(translation: Translation): List<Pair<String, String>> =
        translation.texts.filter { it.path in TERMS && it.fr.isNotBlank() && it.en != it.fr && it.en.length <= TERM }.map { it.en to it.fr }

    /** The calls a run made, the tokens they took and the model that answered. */
    private inner class Cost {
        var calls = 0
        var input = 0L
        var output = 0L
        var model = properties.model

        fun add(batch: TranslatedBatch) {
            calls++
            input += batch.inputTokens
            output += batch.outputTokens
            model = batch.model
        }

        /** At the model's price, in dollars, to the cent. */
        fun dollars(): BigDecimal =
            (properties.price.input * BigDecimal(input) + properties.price.output * BigDecimal(output)).divide(MILLION, 2, RoundingMode.HALF_UP)
    }

    private operator fun ProposalCounts.plus(other: ProposalCounts) = ProposalCounts(added + other.added, refreshed + other.refreshed, unchanged + other.unchanged)

    private companion object {
        const val TEXTS_PER_CALL = 60
        const val TERM = 60
        val TERMS = setOf("name", "label", "period", "place", "kind")
        val MILLION = BigDecimal(1_000_000)
    }
}
