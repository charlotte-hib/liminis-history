package art.liminis.translating

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Claims
import art.liminis.curation.Flag
import art.liminis.curation.FlagReason
import art.liminis.curation.Origin
import art.liminis.curation.Proposal
import art.liminis.curation.TranslatedText
import art.liminis.curation.Translation
import art.liminis.dataset.DatasetStore
import art.liminis.drafting.DraftingProperties
import art.liminis.publishing.GitHub
import art.liminis.publishing.PublishProperties
import art.liminis.support.UnavailableException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Drafts in French, with Claude, every record of `data/` whose texts have no French yet (`wb translate`, or curate's
 * Translations page), reading them on the data repository's `main`, and proposes each as a `TRANSLATION` claim flagged
 * with anything the validator found. A record already translated with the English it has now is left alone; one whose
 * English changed is translated again, keeping the French of the texts whose English stayed the same. People's names
 * are Wikidata's French labels, not translated. Short records go first, so the longer ones are translated with their
 * French in the glossary: regions, eras, people, lives, events, connections, anecdotes, then moments, a few at a time.
 * Needs an Anthropic API key in the environment.
 */
@Component
class Translating(
    private val github: GitHub,
    private val publish: PublishProperties,
    private val claims: Claims,
    private val reviewed: ClaimRepository,
    private val translator: Translator,
    private val dataset: DatasetStore,
    private val properties: DraftingProperties,
    private val jsonMapper: JsonMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Translates what has no French yet, proposes it, and says what it came to and cost. */
    fun translate(): String {
        val base = github.head(publish.baseBranch) ?: throw UnavailableException("The data repository has no branch ${publish.baseBranch}")
        val records = records(base)
        val known = reviewed.findByTypeOrderById(ClaimType.TRANSLATION).associate { (it.payload as Translation).record to it }
        val names = names(records)
        // Approved French first, then any other the curator has under review, as the glossary's.
        val glossary = glossary(known.values.sortedBy { it.status == ClaimStatus.APPROVED }.map { it.payload as Translation })

        var calls = 0
        var input = 0L
        var output = 0L
        var model = properties.model
        val proposals = mutableListOf<Proposal>()
        for (kind in RecordKind.entries) {
            val missing = records.filter { it.kind == kind }.mapNotNull { record ->
                val claim = known[record.key]
                missing(record, claim?.payload as Translation?, claim?.status == ClaimStatus.REJECTED, names)
            }
            for (batch in batches(kind, missing)) {
                val terms = glossary.filterKeys { term -> batch.any { (record, _) -> record.texts.any { (_, en) -> en.contains(term) } } }
                val toTranslate = batch.map { (record, french) -> ToTranslate(record.key, record.label, record.texts, record.texts.map { it.first }.filter { it !in french }) }
                val translated = if (toTranslate.all { it.paths.isEmpty() }) null else translator.translate(toTranslate.filter { it.paths.isNotEmpty() }, terms)
                translated?.let {
                    calls++
                    input += it.inputTokens
                    output += it.outputTokens
                    model = it.model
                }
                val byRecord = translated?.draft?.records.orEmpty().associate { it.record to it.texts.associate { text -> text.path to text.fr } }
                for ((record, french) in batch) {
                    val drafted = byRecord[record.key].orEmpty()
                    val texts = record.texts.map { (path, en) -> TranslatedText(path, en, french[path] ?: drafted[path].orEmpty()) }
                    val translation = Translation(record.key, record.label, texts)
                    val flags = (TranslationValidator.problems(texts, terms) + texts.filter { it.fr.isBlank() }.map { "${it.path}: not translated" })
                        .map { FlagReason(Flag.ODD, it) }
                    proposals += Proposal(translation, Origin.CLAUDE, flags, emptyList())
                    // Short labels translated now are the glossary of the longer records after them.
                    glossary += terms(translation)
                }
            }
        }
        val counts = claims.propose(proposals)
        val flagged = proposals.count { it.flags.isNotEmpty() }
        val cost = (properties.price.input * BigDecimal(input) + properties.price.output * BigDecimal(output)).divide(MILLION, 2, RoundingMode.HALF_UP)
        log.info("Translated {} records in {} calls with {}: {} input and {} output tokens, \${}", proposals.size, calls, model, input, output, cost)
        val priced = if (model == properties.model) "" else " at ${properties.model}'s price, though $model wrote some"
        return "Translated ${proposals.size} ${if (proposals.size == 1) "record" else "records"} ($counts), $flagged to look at, in $calls calls; " +
            "%,d input and %,d output tokens, about \$%s%s".format(Locale.ROOT, input, output, cost, priced)
    }

    /**
     * What of [record] still needs French, with the French known already, by path; nothing when it needs none: it is
     * translated, approved or under review, with the English it has now, or its file has French for all its texts. A
     * translation the curator [rejected] is drafted again, from nothing.
     */
    private fun missing(record: DataRecord, claim: Translation?, rejected: Boolean, names: Map<String, String>): Pair<DataRecord, Map<String, String>>? {
        if (record.texts.isEmpty()) return null
        val written = RecordTexts.french(record.kind, record.node).orEmpty()
        if (claim != null && !rejected && record.matches(claim.texts)) return null
        if (claim == null && written.keys == record.texts.map { it.first }.toSet()) return null
        val kept = claim?.texts.orEmpty().filter { !rejected && it.fr.isNotBlank() && (it.path to it.en) in record.texts }.associate { it.path to it.fr }
        val named = names[record.key]?.let { mapOf("name" to it) }.orEmpty()
        // The curator's French of a name wins over Wikidata's.
        return record to (written.filterKeys { path -> path in record.texts.map { it.first } } + named + kept)
    }

    /** Batches of records to translate in one call: a moment's texts are many, short records go a few dozen at a time. */
    private fun batches(kind: RecordKind, records: List<Pair<DataRecord, Map<String, String>>>): List<List<Pair<DataRecord, Map<String, String>>>> {
        val batches = mutableListOf<MutableList<Pair<DataRecord, Map<String, String>>>>()
        var texts = 0
        for (record in records) {
            val size = record.first.texts.size
            if (batches.isEmpty() || kind == RecordKind.MOMENT || texts + size > TEXTS_PER_CALL) {
                batches += mutableListOf<Pair<DataRecord, Map<String, String>>>()
                texts = 0
            }
            batches.last() += record
            texts += size
        }
        return batches
    }

    /** The records of `data/` with texts, as the data repository's [commit] has them. */
    private fun records(commit: String): List<DataRecord> = RecordKind.entries.flatMap { kind ->
        if (kind == RecordKind.MOMENT) {
            github.files(kind.path.removeSuffix("/"), commit).filter { it.endsWith(".json") }.mapNotNull { name ->
                github.file("${kind.path}$name", commit)?.let { DataRecord(kind, jsonMapper.readTree(it) as ObjectNode) }
            }
        } else {
            github.file(kind.path, commit).orEmpty().lines().filter { it.isNotBlank() }.map { DataRecord(kind, jsonMapper.readTree(it) as ObjectNode) }
        }
    }

    /** The French names of the people written by hand, by record, from Wikidata's labels through their Q-ids. */
    private fun names(records: List<DataRecord>): Map<String, String> {
        val qids = records.filter { it.kind == RecordKind.PERSON }.mapNotNull { record -> record.node["qid"]?.asString()?.let { record.key to it } }.toMap()
        val labels = dataset.frenchLabels(qids.values)
        return qids.mapNotNull { (key, qid) -> labels[qid]?.let { key to it } }.toMap()
    }

    /** The glossary of [translations]: each short label's French, by its English, the later ones winning. */
    private fun glossary(translations: List<Translation>): MutableMap<String, String> =
        translations.flatMap(::terms).toMap().toMutableMap()

    /** The terms a translation adds to the glossary: its short labels, names and periods. */
    private fun terms(translation: Translation): List<Pair<String, String>> =
        translation.texts.filter { it.path in TERMS && it.fr.isNotBlank() && it.en != it.fr && it.en.length <= TERM }.map { it.en to it.fr }

    private companion object {
        const val TEXTS_PER_CALL = 60
        const val TERM = 60
        val TERMS = setOf("name", "label", "period", "place", "kind")
        val MILLION = BigDecimal(1_000_000)
    }
}
