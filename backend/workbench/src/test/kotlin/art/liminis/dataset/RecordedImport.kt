package art.liminis.dataset

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import art.liminis.wikidata.BirthSlice
import art.liminis.wikidata.EntityBatch
import art.liminis.wikidata.PageBatch
import art.liminis.wikidata.RawStore
import java.time.LocalDate

/**
 * What an import of the 28 people born from 2000 to 1901 BCE stored: the responses recorded from Wikidata on 2026-10-09
 * and from Wikipedia on 2026-10-10, put in `raw` as the import puts them.
 */
class RecordedImport(private val raw: RawStore, private val jdbc: JdbcTemplate, private val jsonMapper: JsonMapper) {

    fun emptyCache() {
        jdbc.execute(
            "truncate raw.discovered, raw.entity, raw.import_item, raw.discovery_slice, raw.page, raw.page_item, raw.import_run restart identity",
        )
    }

    /** Stores the recorded people, places, occupations, classes and articles, as a finished import would. */
    fun store() {
        val run = raw.currentRun()
        val people = recorded("sparql-born-2000-to-1901-bce.json").path("results").path("bindings").associate {
            it.path("person").path("value").asString().substringAfterLast('/') to it.path("sitelinks").path("value").asString().toInt()
        }
        raw.saveSlice(run, BirthSlice(LocalDate.of(-1999, 1, 1), LocalDate.of(-1899, 1, 1)), people)
        val files = listOf("born", "linked-to-born", "classes-1-of-born", "classes-2-of-born", "classes-3-of-born")
        files.map { "wbgetentities-$it-2000-to-1901-bce.json" }.forEach { file ->
            val entities = recorded(file).path("entities").values().toList()
            raw.saveBatch(run, EntityBatch(entities.map { it.path("id").asString() }, 1, entities, emptyList(), emptyList()))
        }
        listOf("en", "fr").forEach { language ->
            (1..2).forEach { i ->
                val pages = recorded("$language-born-2000-to-1901-bce-$i.json", "wikipedia").path("query").path("pages").values()
                val byTitle = pages.associateBy { it.path("title").asString() }
                raw.savePages(run, PageBatch(language, byTitle.keys.toList(), 1, byTitle, emptyList(), emptyList()))
            }
        }
        raw.finish(run)
    }

    private fun recorded(file: String, folder: String = "wikidata") =
        jsonMapper.readTree(ClassPathResource("wiremock/__files/$folder/$file").inputStream)
}
