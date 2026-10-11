package dev.wholivedwhen.dataset

import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The workbench's schema `dataset` ([dev.wholivedwhen.WorkbenchSchemas]): the rows mapped from `raw`, rebuilt on every
 * mapping run.
 */
@Component
class DatasetStore(dataSource: DataSource) {

    private val jdbc = JdbcTemplate(dataSource)

    fun clear() {
        jdbc.execute(
            """
            truncate dataset.person, dataset.person_occupation, dataset.person_place, dataset.person_flag,
                dataset.person_article, dataset.occupation, dataset.occupation_parent, dataset.place,
                dataset.place_country, dataset.place_flag, dataset.mapping
            """.trimIndent(),
        )
    }

    /** Records that the rows come from the import [run]. */
    fun saveMapping(run: Long) {
        jdbc.update(
            """
            insert into dataset.mapping (import_run, import_started_at, import_finished_at)
            select id, started_at, finished_at from raw.import_run where id = ?
            """.trimIndent(),
            run,
        )
    }

    /** The import the rows come from, none before the first mapping run. */
    fun mappedImport(): MappedImport? =
        jdbc.query("select import_run, import_started_at, import_finished_at from dataset.mapping", { rs, _ ->
            MappedImport(rs.getLong(1), rs.instant(2), rs.instant(3))
        }).singleOrNull()

    fun savePeople(people: List<PersonRow>) {
        jdbc.batchUpdate(
            """
            insert into dataset.person (qid, label, label_fr, sitelinks, born, born_precision, died, died_precision,
                died_estimated, dates_approximate, gender, enwiki, frwiki)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            people.map {
                arrayOf<Any?>(
                    it.qid, it.label, it.labelFr, it.sitelinks, it.born, it.bornPrecision, it.died, it.diedPrecision,
                    it.diedEstimated, it.datesApproximate, it.gender, it.enwiki, it.frwiki,
                )
            },
        )
        jdbc.batchUpdate(
            "insert into dataset.person_occupation (person, position, occupation, referenced) values (?, ?, ?, ?)",
            people.flatMap { person -> person.occupations.mapIndexed { i, it -> arrayOf<Any?>(person.qid, i, it.occupation, it.referenced) } },
        )
        jdbc.batchUpdate(
            """
            insert into dataset.person_place (person, property, position, place, start_year, end_year, referenced)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            people.flatMap { person ->
                person.places.groupBy { it.property }.flatMap { (property, places) ->
                    places.mapIndexed { i, it -> arrayOf<Any?>(person.qid, property, i, it.place, it.startYear, it.endYear, it.referenced) }
                }
            },
        )
        jdbc.batchUpdate(
            "insert into dataset.person_flag (person, reason, flag) values (?, ?, ?)",
            people.flatMap { person -> person.flags.map { arrayOf<Any?>(person.qid, it.name, it.flag) } },
        )
    }

    /**
     * Saves the articles the people saved so far link to (`enwiki`, `frwiki`) from those stored in `raw.page`, as
     * Wikipedia gave them. Returns how many.
     */
    fun saveArticles(): Int =
        jdbc.update(
            """
            insert into dataset.person_article (person, language, title, url, extract, thumbnail_url, image)
            select p.qid, a.language, page.json ->> 'title', page.json ->> 'fullurl', page.json ->> 'extract',
                page.json -> 'thumbnail' ->> 'source', page.json ->> 'pageimage'
            from dataset.person p
            cross join lateral (values ('en', p.enwiki), ('fr', p.frwiki)) as a(language, title)
            join raw.page page on page.language = a.language and page.title = a.title
            """.trimIndent(),
        )

    fun savePlaces(places: List<PlaceRow>) {
        jdbc.batchUpdate(
            """
            insert into dataset.place (qid, label, label_fr, latitude, longitude, coordinates_country, disputed_area)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            places.map {
                arrayOf<Any?>(
                    it.qid, it.label, it.labelFr, it.coordinates?.latitude, it.coordinates?.longitude, it.coordinatesCountry,
                    it.disputedArea,
                )
            },
        )
        jdbc.batchUpdate(
            "insert into dataset.place_country (place, position, country, iso) values (?, ?, ?, ?)",
            places.flatMap { place -> place.countries.mapIndexed { i, it -> arrayOf<Any?>(place.qid, i, it.country, it.iso) } },
        )
        jdbc.batchUpdate(
            "insert into dataset.place_flag (place, reason, flag) values (?, ?, ?)",
            places.flatMap { place -> place.flags.map { arrayOf<Any?>(place.qid, it.name, it.flag) } },
        )
    }

    /** Saves [occupations], with only those of their parents that are among them. */
    fun saveOccupations(occupations: List<OccupationRow>) {
        jdbc.batchUpdate(
            "insert into dataset.occupation (qid, label, label_fr, female_label_fr) values (?, ?, ?, ?)",
            occupations.map { arrayOf<Any?>(it.qid, it.label, it.labelFr, it.femaleLabelFr) },
        )
        val saved = occupations.map { it.qid }.toSet()
        jdbc.batchUpdate(
            "insert into dataset.occupation_parent (occupation, position, parent) values (?, ?, ?)",
            occupations.flatMap { occupation ->
                occupation.parents.filter { it in saved }.distinct().mapIndexed { i, it -> arrayOf<Any?>(occupation.qid, i, it) }
            },
        )
    }

    /** The [limit] people after the Q-id [after], in Q-id order, with their occupations, places and flags. */
    fun peopleAfter(after: String, limit: Int): List<PersonRow> = personRows("qid > ? order by qid limit ?", after, limit)

    /** The people [where] selects from `dataset.person`, in its order, with their occupations, places and flags. */
    private fun personRows(where: String, vararg args: Any): List<PersonRow> {
        val people = jdbc.query("select * from dataset.person where $where", PERSON, *args)
        val ids = people.map { it.qid }.toTypedArray()
        val occupations = byFirstColumn(
            "select person, occupation, referenced from dataset.person_occupation where person = any (?) order by person, position",
            ids,
        ) { PersonOccupation(it.getString(2), it.getBoolean(3)) }
        val places = byFirstColumn(
            """
            select person, property, place, start_year, end_year, referenced
            from dataset.person_place pp
            where person = any (?)
            order by person, ${PlaceProperty.sqlOrder("pp")}, position
            """.trimIndent(),
            ids,
        ) { PersonPlace(it.getString(2), it.getString(3), it.getObject(4) as Int?, it.getObject(5) as Int?, it.getBoolean(6)) }
        val flags = byFirstColumn("select person, reason from dataset.person_flag where person = any (?) order by person, reason", ids) {
            PersonFlag.valueOf(it.getString(2))
        }
        return people.map {
            it.copy(occupations = occupations[it.qid].orEmpty(), places = places[it.qid].orEmpty(), flags = flags[it.qid].orEmpty().toSet())
        }
    }

    /** Each row of [sql], read by [read], under the value of its first column, in the query's order. */
    private fun <T> byFirstColumn(sql: String, vararg args: Any, read: (ResultSet) -> T): Map<String, List<T>> =
        jdbc.query(sql, { rs, _ -> rs.getString(1) to read(rs) }, *args).groupBy({ it.first }, { it.second })

    /** The [limit] articles after [after]'s person and language, in that order. */
    fun articlesAfter(after: Pair<String, String>, limit: Int): List<ArticleRow> =
        jdbc.query(
            """
            select person, language, title, url, extract, thumbnail_url, image
            from dataset.person_article
            where (person, language) > (?, ?)
            order by person, language
            limit ?
            """.trimIndent(),
            ARTICLE, after.first, after.second, limit,
        )

    /** Every place, in Q-id order, with its countries and flags. */
    fun places(): List<PlaceRow> {
        val countries = jdbc.query(
            "select place, country, iso from dataset.place_country order by place, position",
            { rs, _ -> rs.getString(1) to PlaceCountry(rs.getString(2), rs.getString(3)) },
        ).groupBy({ it.first }, { it.second })
        val flags = jdbc.query(
            "select place, reason from dataset.place_flag order by place, reason",
            { rs, _ -> rs.getString(1) to PlaceFlag.valueOf(rs.getString(2)) },
        ).groupBy({ it.first }, { it.second })
        return jdbc.query("select * from dataset.place order by qid") { rs, _ ->
            val qid = rs.getString("qid")
            val latitude = rs.getObject("latitude") as Double?
            val longitude = rs.getObject("longitude") as Double?
            PlaceRow(
                qid = qid,
                label = rs.getString("label"),
                labelFr = rs.getString("label_fr"),
                coordinates = if (latitude != null && longitude != null) Coordinates(latitude, longitude) else null,
                coordinatesCountry = rs.getString("coordinates_country"),
                disputedArea = rs.getString("disputed_area"),
                countries = countries[qid].orEmpty(),
                flags = flags[qid].orEmpty().toSet(),
            )
        }
    }

    /** Every occupation and class above one, in Q-id order, with the classes it belongs to. */
    fun occupations(): List<OccupationRow> {
        val parents = jdbc.query(
            "select occupation, parent from dataset.occupation_parent order by occupation, position",
            { rs, _ -> rs.getString(1) to rs.getString(2) },
        ).groupBy({ it.first }, { it.second })
        return jdbc.query("select qid, label, label_fr, female_label_fr from dataset.occupation order by qid") { rs, _ ->
            OccupationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), parents[rs.getString(1)].orEmpty())
        }
    }

    /**
     * The people born in [region] (by their place of birth's country today) and alive at some point from [start] to
     * [end], the [limit] best known first.
     */
    fun bornIn(region: String, start: Int, end: Int, limit: Int): List<String> =
        jdbc.query(
            """
            select p.qid
            from dataset.person p
            join dataset.person_place pp on pp.person = p.qid and pp.property = '${PlaceProperty.BIRTH.code}' and pp.position = 0
            join dataset.place pl on pl.qid = pp.place
            where $PLACE_COUNTRY = ? and p.born <= ? and p.died >= ?
            order by p.sitelinks desc, p.qid
            limit ?
            """.trimIndent(),
            { rs, _ -> rs.getString(1) }, region, end, start, limit,
        )

    /**
     * The rows of those of [qids] in the dataset, with the labels of their occupations and places and the country each
     * place lies in today, in no particular order.
     */
    fun people(qids: Collection<String>): List<DatasetPerson> {
        val rows = personRows("qid = any (?)", qids.toTypedArray())
        val occupationIds = rows.flatMap { row -> row.occupations.map { it.occupation } }.distinct().toTypedArray()
        val occupations = jdbc.query(
            "select qid, label, label_fr from dataset.occupation where qid = any (?)",
            { rs, _ -> DatasetOccupation(rs.getString(1), rs.getString(2), rs.getString(3)) },
            occupationIds,
        ).associateBy { it.occupation }
        val placeIds = rows.flatMap { row -> row.places.map { it.place } }.distinct().toTypedArray()
        val places = jdbc.query(
            "select pl.qid, pl.label, $PLACE_COUNTRY from dataset.place pl where pl.qid = any (?)",
            { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getString(3)) },
            placeIds,
        ).toMap()
        val placeFlags = byFirstColumn("select place, flag, reason from dataset.place_flag where place = any (?)", placeIds) {
            it.getString(2) to it.getString(3)
        }
        return rows.map { row ->
            DatasetPerson(
                qid = row.qid,
                label = row.label,
                labelFr = row.labelFr,
                born = row.born,
                bornPrecision = row.bornPrecision,
                died = row.died,
                diedPrecision = row.diedPrecision,
                diedEstimated = row.diedEstimated,
                datesApproximate = row.datesApproximate,
                enwiki = row.enwiki,
                frwiki = row.frwiki,
                // A deleted occupation or place is in no table: it keeps no label.
                occupations = row.occupations.map { occupations[it.occupation] ?: DatasetOccupation(it.occupation, null, null) },
                places = row.places.map {
                    val (label, country) = places[it.place] ?: (null to null)
                    DatasetPlace(it.property, it.place, label, country, it.startYear, it.endYear)
                },
                // The person's flags, then their places' under the place's property, such as P19_DISPUTED_AREA.
                flags = (
                    row.flags.map { it.flag to it.name } +
                        row.places.flatMap { place -> placeFlags[place.place].orEmpty().map { (flag, reason) -> flag to "${place.property}_$reason" } }
                    ).distinct().sortedWith(compareBy({ it.first }, { it.second })),
            )
        }
    }

    private fun ResultSet.instant(column: Int) = getObject(column, OffsetDateTime::class.java).toInstant()

    private companion object {
        /** A person's own columns; their occupations, places and flags are read apart. */
        val PERSON = RowMapper { rs, _ ->
            PersonRow(
                qid = rs.getString("qid"),
                label = rs.getString("label"),
                labelFr = rs.getString("label_fr"),
                sitelinks = rs.getInt("sitelinks"),
                born = rs.getInt("born"),
                bornPrecision = rs.getInt("born_precision"),
                died = rs.getInt("died"),
                diedPrecision = rs.getObject("died_precision") as Int?,
                diedEstimated = rs.getBoolean("died_estimated"),
                datesApproximate = rs.getBoolean("dates_approximate"),
                gender = rs.getString("gender"),
                enwiki = rs.getString("enwiki"),
                frwiki = rs.getString("frwiki"),
                occupations = emptyList(),
                places = emptyList(),
                flags = emptySet(),
            )
        }
        val ARTICLE = DataClassRowMapper(ArticleRow::class.java)

        /** The ISO code of the country a place lies in today: by its coordinates, else its first country with one. */
        const val PLACE_COUNTRY = """coalesce(pl.coordinates_country,
            (select pc.iso from dataset.place_country pc where pc.place = pl.qid and pc.iso is not null order by pc.position limit 1))"""
    }
}
