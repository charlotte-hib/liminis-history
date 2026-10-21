package art.liminis.people

import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import art.liminis.dataset.ArticleRow
import art.liminis.dataset.DatasetStore
import art.liminis.dataset.FoundPlace
import art.liminis.dataset.PersonSummary
import art.liminis.dataset.PlaceProperty
import art.liminis.dataset.Rank
import art.liminis.dataset.Statement
import art.liminis.dataset.WikidataEntity
import art.liminis.support.NotFoundException
import art.liminis.wikidata.RawStore

/** A field of a person's facts, and the Wikidata property it is read from. */
enum class EvidenceField(val property: String) {
    BORN("P569"),
    DIED("P570"),
    OCCUPATION("P106"),
    BIRTHPLACE(PlaceProperty.BIRTH.code),
    DEATHPLACE(PlaceProperty.DEATH.code),
    WORKPLACE(PlaceProperty.WORK.code),
    RESIDENCE(PlaceProperty.RESIDENCE.code),
}

/** What Wikidata and Wikipedia say about a person, as the last import fetched it. */
data class Evidence(val person: String, val revision: Long, val statements: List<EvidenceStatement>, val articles: List<ArticleRow>)

/** One Wikidata statement: a date ([year], [precision]) or an item ([item], [label]). */
data class EvidenceStatement(
    val field: EvidenceField,
    val property: String,
    val year: Int?,
    val precision: Int?,
    val circa: Boolean,
    val item: String?,
    val label: String?,
    val unknown: Boolean,
    val rank: Rank,
    val references: Int,
)

/** The fetched people, to find one by name and to review their claims against their sources. */
@Service
class People(private val raw: RawStore, private val dataset: DatasetStore, private val jsonMapper: JsonMapper) {

    fun find(query: String, limit: Int): List<PersonSummary> = dataset.find(query.trim(), limit)

    /** The places a person's may be chosen among. */
    fun findPlaces(query: String, limit: Int): List<FoundPlace> = dataset.findPlaces(query.trim(), limit)

    fun evidence(qid: String): Evidence {
        val json = raw.entities(listOf(qid), EvidenceField.entries.map { it.property }).singleOrNull()
            ?: throw NotFoundException("Unknown person: $qid")
        val entity = jsonMapper.readValue<WikidataEntity>(json)
        val statements = EvidenceField.entries.flatMap { field -> entity.statements(field.property).map { field to it } }
        val items = statements.mapNotNull { (_, statement) -> statement.mainsnak.id }.distinct()
        // Only labels are needed: no statements, as a city's entity runs to megabytes.
        val labels = raw.entities(items, emptyList()).map { jsonMapper.readValue<WikidataEntity>(it) }
            .associate { it.id to it.name("en", "fr", "mul") }
        return Evidence(
            person = qid,
            revision = raw.revisions(listOf(qid)).getValue(qid),
            statements = statements.map { (field, statement) -> statement(field, statement, labels) },
            articles = dataset.articles(qid),
        )
    }

    private fun statement(field: EvidenceField, statement: Statement, labels: Map<String, String>): EvidenceStatement {
        val snak = statement.mainsnak
        return EvidenceStatement(
            field = field,
            property = field.property,
            year = snak.time?.year,
            precision = snak.time?.precision,
            circa = statement.circa,
            item = snak.id,
            label = snak.id?.let { labels[it] ?: it },
            unknown = snak.unknown,
            rank = statement.rank,
            references = statement.sourcedReferences,
        )
    }
}
