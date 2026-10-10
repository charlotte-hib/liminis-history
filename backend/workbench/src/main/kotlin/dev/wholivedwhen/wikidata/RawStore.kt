package dev.wholivedwhen.wikidata

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/** The steps of an import, in order. */
enum class ImportPhase { DISCOVER, PEOPLE, LINKED, CLASSES, PAGES, DONE }

/**
 * The entities an import fetches: people first, then what their claims link to, then the classes their occupations
 * belong to, one level above another.
 */
enum class EntityKind { PEOPLE, LINKED, CLASSES }

data class ImportRun(val id: Long, val startedAt: Instant, val phase: ImportPhase, val notBefore: Instant?) {
    val started: LocalDate get() = startedAt.atOffset(ZoneOffset.UTC).toLocalDate()
}

/** What one batch of entities came to. */
data class EntityBatch(
    val ids: List<String>,
    val requests: Int,
    val fetched: List<JsonNode>,
    val unchanged: List<String>,
    val missing: List<String>,
)

/** What one batch of Wikipedia pages in one [language] came to, the pages fetched by the title asked for. */
data class PageBatch(
    val language: String,
    val titles: List<String>,
    val requests: Int,
    val fetched: Map<String, JsonNode>,
    val unchanged: List<String>,
    val missing: List<String>,
)

/**
 * The workbench's schema `raw`: what it fetched from Wikimedia, and how far each import got. Its own migrations
 * (`db/raw`), apart from the release schema's: the release is built again on every start, this is never dropped.
 */
@Component
class RawStore(dataSource: DataSource, private val transactions: TransactionTemplate) {

    private val jdbc = JdbcTemplate(dataSource)

    // Reads the people a few hundred at a time rather than all at once: tens of thousands of entities do not fit in memory.
    private val streaming = JdbcTemplate(dataSource).apply { fetchSize = 500 }

    init {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/raw")
            .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
            .load().migrate()
    }

    /** The last import that finished, if any. */
    fun lastFinishedRun(): ImportRun? =
        jdbc.query("select * from raw.import_run where finished_at is not null order by id desc limit 1", ::toRun).firstOrNull()

    /**
     * The people [run] discovered, in Q-id order, each with their sitelinks and their entity's JSON, cut down to its id,
     * labels, sitelinks and the statements of [properties]. Streamed, so it must run in a transaction: Postgres only
     * reads a few rows at a time inside one.
     */
    fun people(run: ImportRun, properties: List<String>, each: (sitelinks: Int, json: String) -> Unit) {
        streaming.query(
            """
            select d.sitelinks, $CUT_DOWN
            from raw.discovered d
            join raw.entity e on e.qid = d.qid
            where d.seen_at >= ?
            order by d.qid
            """.trimIndent(),
            { rs -> each(rs.getInt(1), rs.getString(2)) },
            properties.toTypedArray(), run.startedAt.utc(),
        )
    }

    /**
     * The JSON of those of [ids] stored, in no particular order, cut down to its id, labels, sitelinks and the
     * statements of [properties]: a city's entity runs to megabytes.
     */
    fun entities(ids: Collection<String>, properties: List<String>): List<String> =
        jdbc.query(
            "select $CUT_DOWN from raw.entity e where qid = any (?)",
            { rs, _ -> rs.getString(1) }, properties.toTypedArray(), ids.toTypedArray(),
        )

    /** The revision stored of each of [qids] the workbench fetched. */
    fun revisions(qids: Collection<String>): Map<String, Long> =
        jdbc.query("select qid, revision from raw.entity where qid = any (?)", { rs, _ -> rs.getString(1) to rs.getLong(2) }, qids.toTypedArray())
            .toMap()

    /** The import that has not finished, if one stopped half way, else a new one. */
    fun currentRun(): ImportRun =
        jdbc.query("select * from raw.import_run where finished_at is null order by id desc limit 1", ::toRun).firstOrNull()
            ?: jdbc.queryForObject("insert into raw.import_run (phase) values (?) returning *", ::toRun, ImportPhase.DISCOVER.name)!!

    fun findRun(id: Long): ImportRun = jdbc.queryForObject("select * from raw.import_run where id = ?", ::toRun, id)!!

    fun counts(run: ImportRun): Map<String, Any?> =
        jdbc.queryForMap(
            """
            select requests, busy, fetched, unchanged, missing, pages_fetched, pages_unchanged, pages_missing
            from raw.import_run where id = ?
            """.trimIndent(),
            run.id,
        )

    fun countRequest(run: ImportRun) {
        jdbc.update("update raw.import_run set requests = requests + 1 where id = ?", run.id)
    }

    /** Wikimedia asked to wait [wait]: another request, and a wait any resumed run honours too. */
    fun busy(run: ImportRun, wait: Duration) {
        jdbc.update(
            """
            update raw.import_run set requests = requests + 1, busy = busy + 1,
                not_before = greatest(not_before, now() + ? * interval '1 millisecond')
            where id = ?
            """.trimIndent(),
            wait.toMillis(), run.id,
        )
    }

    fun doneSlices(run: ImportRun): List<BirthSlice> =
        jdbc.query("select born_from, born_until from raw.discovery_slice where run_id = ?", { rs, _ ->
            BirthSlice(LocalDate.parse(rs.getString(1)), LocalDate.parse(rs.getString(2)))
        }, run.id)

    /** Stores the people one slice found, with the slice, so a resumed run does not ask for it again. */
    fun saveSlice(run: ImportRun, slice: BirthSlice, people: Map<String, Int>) {
        transactions.executeWithoutResult {
            jdbc.batchUpdate(
                """
                insert into raw.discovered (qid, sitelinks, discovered_at, seen_at) values (?, ?, now(), now())
                on conflict (qid) do update set sitelinks = excluded.sitelinks, seen_at = excluded.seen_at
                """.trimIndent(),
                people.map { (qid, sitelinks) -> arrayOf<Any>(qid, sitelinks) },
            )
            jdbc.update(
                "insert into raw.discovery_slice (run_id, born_from, born_until, people) values (?, ?, ?, ?)",
                run.id, slice.from.toString(), slice.until.toString(), people.size,
            )
            jdbc.update("update raw.import_run set requests = requests + 1 where id = ?", run.id)
        }
    }

    fun discoveredCount(run: ImportRun): Int =
        jdbc.queryForObject("select count(*) from raw.discovered where seen_at >= ?", Int::class.java, run.startedAt.utc())!!

    /** Discovery is done: every person it found this run is to be fetched, or checked for a new revision. */
    fun queuePeople(run: ImportRun) = transactions.executeWithoutResult {
        jdbc.update(
            """
            insert into raw.import_item (run_id, qid, kind)
            select ?, qid, ? from raw.discovered where seen_at >= ?
            on conflict do nothing
            """.trimIndent(),
            run.id, EntityKind.PEOPLE.name, run.startedAt.utc(),
        )
        phase(run, ImportPhase.PEOPLE)
    }

    /** The people are fetched: the entities their claims in [properties] point to are next. */
    fun queueLinked(run: ImportRun, properties: List<String>) = transactions.executeWithoutResult {
        jdbc.update(
            """
            insert into raw.import_item (run_id, qid, kind)
            select distinct i.run_id, claim -> 'mainsnak' -> 'datavalue' -> 'value' ->> 'id', ?
            from raw.import_item i
            join raw.entity e on e.qid = i.qid
            cross join unnest(string_to_array(?, ',')) as p(property)
            cross join jsonb_array_elements(coalesce(e.json -> 'claims' -> p.property, '[]')) as claim
            where i.run_id = ? and i.kind = ? and claim -> 'mainsnak' ->> 'snaktype' = 'value'
            on conflict do nothing
            """.trimIndent(),
            EntityKind.LINKED.name, properties.joinToString(","), run.id, EntityKind.PEOPLE.name,
        )
        phase(run, ImportPhase.LINKED)
    }

    /** The places and occupations are fetched: the classes above the occupations are next. */
    fun startClasses(run: ImportRun) = phase(run, ImportPhase.CLASSES)

    /** The highest level of classes this run has queued, 0 before the first. */
    fun classLevel(run: ImportRun): Int =
        jdbc.queryForObject(
            "select coalesce(max(level), 0) from raw.import_item where run_id = ? and kind = ?",
            Int::class.java, run.id, EntityKind.CLASSES.name,
        )!!

    /**
     * Queues the classes (P279) of the entities one level below [level]: the people's occupations (P106) for the
     * first level, the classes queued at the level before for the next ones. An entity is queued once a run, whatever
     * its kind, so a class reached twice, or a loop of classes, is fetched once. Returns how many were queued.
     */
    fun queueClasses(run: ImportRun, level: Int): Int {
        val (below, belowArgs) = if (level == 1) {
            """
            select claim -> 'mainsnak' -> 'datavalue' -> 'value' ->> 'id' as qid
            from raw.import_item i
            join raw.entity e on e.qid = i.qid
            cross join jsonb_array_elements(coalesce(e.json -> 'claims' -> 'P106', '[]')) as claim
            where i.run_id = ? and i.kind = ? and claim -> 'mainsnak' ->> 'snaktype' = 'value'
            """ to arrayOf<Any>(run.id, EntityKind.PEOPLE.name)
        } else {
            "select qid from raw.import_item where run_id = ? and kind = ? and level = ?" to
                arrayOf<Any>(run.id, EntityKind.CLASSES.name, level - 1)
        }
        return jdbc.update(
            """
            insert into raw.import_item (run_id, qid, kind, level)
            select distinct ?, claim -> 'mainsnak' -> 'datavalue' -> 'value' ->> 'id', ?, ?
            from ($below) b
            join raw.entity e on e.qid = b.qid
            cross join jsonb_array_elements(coalesce(e.json -> 'claims' -> 'P279', '[]')) as claim
            where claim -> 'mainsnak' ->> 'snaktype' = 'value'
            on conflict do nothing
            """.trimIndent(),
            run.id, EntityKind.CLASSES.name, level, *belowArgs,
        )
    }

    /**
     * The entities are done: the Wikipedia articles the people's sitelinks to [sites] name are next, each in its
     * [languages] edition (the same position in both lists).
     */
    fun queuePages(run: ImportRun, sites: List<String>, languages: List<String>) = transactions.executeWithoutResult {
        jdbc.update(
            """
            insert into raw.page_item (run_id, language, title)
            select distinct i.run_id, s.language, e.json -> 'sitelinks' -> s.site ->> 'title'
            from raw.import_item i
            join raw.entity e on e.qid = i.qid
            cross join unnest(?::text[], ?::text[]) as s(site, language)
            where i.run_id = ? and i.kind = ? and e.json -> 'sitelinks' -> s.site is not null
            on conflict do nothing
            """.trimIndent(),
            sites.toTypedArray(), languages.toTypedArray(), run.id, EntityKind.PEOPLE.name,
        )
        phase(run, ImportPhase.PAGES)
    }

    fun finish(run: ImportRun) {
        jdbc.update("update raw.import_run set phase = ?, finished_at = now() where id = ?", ImportPhase.DONE.name, run.id)
    }

    /** The next [size] entities of [kind] this run has not done yet, in a fixed order. */
    fun nextBatch(run: ImportRun, kind: EntityKind, size: Int): List<String> =
        jdbc.query(
            "select qid from raw.import_item where run_id = ? and kind = ? and done_at is null order by qid limit ?",
            { rs, _ -> rs.getString(1) }, run.id, kind.name, size,
        )

    /** The revisions stored for those of [ids] already fetched once. */
    fun revisions(ids: List<String>): Map<String, Long> =
        jdbc.query(
            "select qid, revision from raw.entity where qid = any (?)",
            { rs, _ -> rs.getString(1) to rs.getLong(2) }, ids.toTypedArray(),
        ).toMap()

    /** The next [size] pages in [language] this run has not done yet, in a fixed order. */
    fun nextPages(run: ImportRun, language: String, size: Int): List<String> =
        jdbc.query(
            "select title from raw.page_item where run_id = ? and language = ? and done_at is null order by title limit ?",
            { rs, _ -> rs.getString(1) }, run.id, language, size,
        )

    /** The revisions stored for those of [titles] in [language] already fetched once. */
    fun pageRevisions(language: String, titles: List<String>): Map<String, Long> =
        jdbc.query(
            "select title, revision from raw.page where language = ? and title = any (?)",
            { rs, _ -> rs.getString(1) to rs.getLong(2) }, language, titles.toTypedArray(),
        ).toMap()

    /** Stores a batch of pages and marks them done, in one transaction, as [saveBatch] does entities. */
    fun savePages(run: ImportRun, batch: PageBatch) = transactions.executeWithoutResult {
        jdbc.batchUpdate(
            """
            insert into raw.page (language, title, revision, fetched_at, json) values (?, ?, ?, now(), ?::jsonb)
            on conflict (language, title) do update set revision = excluded.revision, fetched_at = excluded.fetched_at,
                json = excluded.json
            """.trimIndent(),
            batch.fetched.map { (title, page) -> arrayOf<Any>(batch.language, title, page.path("lastrevid").asLong(), page.toString()) },
        )
        jdbc.batchUpdate("delete from raw.page where language = ? and title = ?", batch.missing.map { arrayOf<Any>(batch.language, it) })
        jdbc.batchUpdate(
            "update raw.page_item set done_at = now() where run_id = ? and language = ? and title = ?",
            batch.titles.map { arrayOf<Any>(run.id, batch.language, it) },
        )
        jdbc.update(
            """
            update raw.import_run set requests = requests + ?, pages_fetched = pages_fetched + ?,
                pages_unchanged = pages_unchanged + ?, pages_missing = pages_missing + ?
            where id = ?
            """.trimIndent(),
            batch.requests, batch.fetched.size, batch.unchanged.size, batch.missing.size, run.id,
        )
    }

    /** Stores a batch and marks its entities done, in one transaction: a crash never leaves half a batch. */
    fun saveBatch(run: ImportRun, batch: EntityBatch) = transactions.executeWithoutResult {
        jdbc.batchUpdate(
            """
            insert into raw.entity (qid, revision, fetched_at, json) values (?, ?, now(), ?::jsonb)
            on conflict (qid) do update set revision = excluded.revision, fetched_at = excluded.fetched_at, json = excluded.json
            """.trimIndent(),
            batch.fetched.map { arrayOf<Any>(it.path("id").asString(), it.path("lastrevid").asLong(), it.toString()) },
        )
        jdbc.batchUpdate("delete from raw.entity where qid = ?", batch.missing.map { arrayOf<Any>(it) })
        jdbc.batchUpdate(
            "update raw.import_item set done_at = now() where run_id = ? and qid = ?",
            batch.ids.map { arrayOf<Any>(run.id, it) },
        )
        jdbc.update(
            """
            update raw.import_run set requests = requests + ?, fetched = fetched + ?, unchanged = unchanged + ?,
                missing = missing + ?
            where id = ?
            """.trimIndent(),
            batch.requests, batch.fetched.size, batch.unchanged.size, batch.missing.size, run.id,
        )
    }

    private fun phase(run: ImportRun, phase: ImportPhase) {
        jdbc.update("update raw.import_run set phase = ? where id = ?", phase.name, run.id)
    }

    private fun toRun(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = ImportRun(
        rs.getLong("id"),
        rs.getObject("started_at", OffsetDateTime::class.java).toInstant(),
        ImportPhase.valueOf(rs.getString("phase")),
        rs.getObject("not_before", OffsetDateTime::class.java)?.toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        const val SCHEMA = "raw"

        /** An entity `e` cut down to its id, labels, sitelinks and the statements of the properties given as parameter. */
        const val CUT_DOWN = """
            jsonb_strip_nulls(jsonb_build_object(
                'id', e.json -> 'id', 'labels', e.json -> 'labels', 'sitelinks', e.json -> 'sitelinks',
                'claims', (select jsonb_object_agg(key, value) from jsonb_each(e.json -> 'claims') where key = any (?))
            ))::text"""
    }
}
