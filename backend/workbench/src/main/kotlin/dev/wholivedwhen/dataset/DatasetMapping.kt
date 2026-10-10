package dev.wholivedwhen.dataset

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import dev.wholivedwhen.support.currentYear
import dev.wholivedwhen.wikidata.ImportRun
import dev.wholivedwhen.wikidata.RawStore
import dev.wholivedwhen.wikidata.WikidataProperties

/** What a mapping run wrote, and whom it left out. */
data class DatasetCounts(val people: Int, val leftOut: Map<LeftOut, Int>, val articles: Int, val places: Int, val occupations: Int)

/**
 * Rebuilds the schema `dataset` from `raw`: the people the last finished import discovered, their Wikipedia articles,
 * their places with the countries they lie in today, then their occupations and the classes above them, as far up as
 * the import fetched them. In one transaction, so the dataset is never seen half built. Calls no one, once Natural
 * Earth's borders are downloaded: run it as often as the mapping changes.
 */
@Component
class DatasetMapping(
    private val raw: RawStore,
    private val dataset: DatasetStore,
    private val naturalEarth: NaturalEarth,
    private val jsonMapper: JsonMapper,
    private val properties: WikidataProperties,
    private val transactions: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun map(): DatasetCounts {
        val run = checkNotNull(raw.lastFinishedRun()) { "No import has finished yet: run one first (wb import)" }
        val placeMapper = PlaceMapper(naturalEarth.borders())
        return transactions.execute { map(run, placeMapper) }!!
    }

    private fun map(run: ImportRun, placeMapper: PlaceMapper): DatasetCounts {
        val mapper = PersonMapper(properties, currentYear())
        dataset.clear()

        var people = 0
        val leftOut = sortedMapOf<LeftOut, Int>()
        val places = mutableSetOf<String>()
        val occupations = mutableSetOf<String>()
        val batch = mutableListOf<PersonRow>()
        raw.people(run, PersonMapper.PROPERTIES) { sitelinks, json ->
            when (val mapped = mapper.map(jsonMapper.readValue<WikidataEntity>(json), sitelinks)) {
                is Mapped.Person -> {
                    batch += mapped.row
                    places += mapped.row.places.map { it.place }
                    occupations += mapped.row.occupations.map { it.occupation }
                }
                is Mapped.Out -> leftOut.merge(mapped.reason, 1, Int::plus)
            }
            if (batch.size == BATCH) {
                dataset.savePeople(batch)
                people += batch.size
                batch.clear()
            }
        }
        dataset.savePeople(batch)
        people += batch.size
        val articles = dataset.saveArticles()

        val placeRows = places.sorted().chunked(BATCH).sumOf { ids ->
            val rows = raw.entities(ids, PlaceMapper.PROPERTIES).map { placeMapper.map(jsonMapper.readValue(it)) }
            dataset.savePlaces(rows)
            rows.size
        }

        val classes = classes(occupations)
        dataset.saveOccupations(classes)
        return DatasetCounts(people, leftOut, articles, placeRows, classes.size).also { log.info("Mapped {}", it) }
    }

    /** The [occupations] stored in `raw`, and the classes above them, [WikidataProperties.classLevels] levels up. */
    private fun classes(occupations: Set<String>): List<OccupationRow> {
        val rows = mutableMapOf<String, OccupationRow>()
        var level = occupations
        repeat(properties.classLevels + 1) {
            val found = level.chunked(BATCH).flatMap { raw.entities(it, OCCUPATION_PROPERTIES) }.map { occupation(jsonMapper.readValue(it)) }
            found.forEach { rows[it.qid] = it }
            level = found.flatMap { it.parents }.filterNot { it in rows }.toSet()
        }
        return rows.values.sortedBy { it.qid }
    }

    private fun occupation(entity: WikidataEntity) = OccupationRow(
        qid = entity.id,
        label = entity.name("en", "mul"),
        labelFr = entity.label("fr", "mul"),
        femaleLabelFr = entity.statements(FEMALE_FORM).mapNotNull { it.mainsnak.text }.firstOrNull { it.first == "fr" }?.second,
        parents = entity.ids(SUBCLASS_OF),
    )

    private companion object {
        const val BATCH = 1000
        const val FEMALE_FORM = "P2521"
        const val SUBCLASS_OF = "P279"
        val OCCUPATION_PROPERTIES = listOf(FEMALE_FORM, SUBCLASS_OF)
    }
}
