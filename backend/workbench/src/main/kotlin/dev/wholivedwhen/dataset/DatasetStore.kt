package dev.wholivedwhen.dataset

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The workbench's schema `dataset`: the rows mapped from `raw`, rebuilt on every mapping run. Its own migrations
 * (`db/dataset`), like `raw`'s.
 */
@Component
class DatasetStore(dataSource: DataSource) {

    private val jdbc = JdbcTemplate(dataSource)

    init {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/dataset")
            .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
            .load().migrate()
    }

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
    fun peopleAfter(after: String, limit: Int): List<PersonRow> {
        val people = jdbc.query("select * from dataset.person where qid > ? order by qid limit ?", { rs, _ ->
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
        }, after, limit)
        val ids = people.map { it.qid }.toTypedArray()
        val occupations = jdbc.query(
            "select person, occupation, referenced from dataset.person_occupation where person = any (?) order by person, position",
            { rs, _ -> rs.getString(1) to PersonOccupation(rs.getString(2), rs.getBoolean(3)) },
            ids,
        ).groupBy({ it.first }, { it.second })
        val places = jdbc.query(
            """
            select person, property, place, start_year, end_year, referenced
            from dataset.person_place
            where person = any (?)
            order by person, array_position(array['P19', 'P20', 'P937', 'P551'], property), position
            """.trimIndent(),
            { rs, _ ->
                rs.getString(1) to PersonPlace(
                    rs.getString(2), rs.getString(3), rs.getObject(4) as Int?, rs.getObject(5) as Int?, rs.getBoolean(6),
                )
            },
            ids,
        ).groupBy({ it.first }, { it.second })
        val flags = jdbc.query(
            "select person, reason from dataset.person_flag where person = any (?) order by person, reason",
            { rs, _ -> rs.getString(1) to PersonFlag.valueOf(rs.getString(2)) },
            ids,
        ).groupBy({ it.first }, { it.second })
        return people.map {
            it.copy(
                occupations = occupations[it.qid].orEmpty(),
                places = places[it.qid].orEmpty(),
                flags = flags[it.qid].orEmpty().toSet(),
            )
        }
    }

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
            { rs, _ ->
                ArticleRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7),
                )
            },
            after.first, after.second, limit,
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
            join dataset.person_place pp on pp.person = p.qid and pp.property = 'P19' and pp.position = 0
            join dataset.place pl on pl.qid = pp.place
            where $PLACE_COUNTRY = ? and p.born <= ? and p.died >= ?
            order by p.sitelinks desc, p.qid
            limit ?
            """.trimIndent(),
            { rs, _ -> rs.getString(1) }, region, end, start, limit,
        )

    /** The rows of those of [qids] in the dataset, with their occupations, places and flags, in no particular order. */
    fun people(qids: Collection<String>): List<DatasetPerson> {
        val ids = qids.toTypedArray()
        val occupations = jdbc.query(
            """
            select po.person, po.occupation, o.label, o.label_fr
            from dataset.person_occupation po
            left join dataset.occupation o on o.qid = po.occupation
            where po.person = any (?)
            order by po.person, po.position
            """.trimIndent(),
            { rs, _ ->
                rs.getString(1) to DatasetOccupation(rs.getString(2), rs.getString(3), rs.getString(4))
            },
            ids,
        ).groupBy({ it.first }, { it.second })
        val places = jdbc.query(
            """
            select pp.person, pp.property, pp.place, pp.start_year, pp.end_year, pl.label, $PLACE_COUNTRY
            from dataset.person_place pp
            left join dataset.place pl on pl.qid = pp.place
            where pp.person = any (?)
            order by pp.person, array_position(array['P19', 'P20', 'P937', 'P551'], pp.property), pp.position
            """.trimIndent(),
            { rs, _ ->
                rs.getString(1) to DatasetPlace(
                    property = rs.getString(2), place = rs.getString(3), label = rs.getString(6), country = rs.getString(7),
                    startYear = rs.getObject(4) as Int?, endYear = rs.getObject(5) as Int?,
                )
            },
            ids,
        ).groupBy({ it.first }, { it.second })
        val flags = jdbc.query(
            """
            select person, flag, reason from dataset.person_flag where person = any (?)
            union all
            select pp.person, pf.flag, pp.property || '_' || pf.reason
            from dataset.person_place pp
            join dataset.place_flag pf on pf.place = pp.place
            where pp.person = any (?)
            order by 1, 2, 3
            """.trimIndent(),
            { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getString(3)) },
            ids, ids,
        ).groupBy({ it.first }, { it.second })
        return jdbc.query("select * from dataset.person where qid = any (?)", { rs, _ ->
            val qid = rs.getString("qid")
            DatasetPerson(
                qid = qid,
                label = rs.getString("label"),
                labelFr = rs.getString("label_fr"),
                born = rs.getInt("born"),
                bornPrecision = rs.getInt("born_precision"),
                died = rs.getInt("died"),
                diedPrecision = rs.getObject("died_precision") as Int?,
                diedEstimated = rs.getBoolean("died_estimated"),
                datesApproximate = rs.getBoolean("dates_approximate"),
                enwiki = rs.getString("enwiki"),
                frwiki = rs.getString("frwiki"),
                occupations = occupations[qid].orEmpty(),
                places = places[qid].orEmpty(),
                flags = flags[qid].orEmpty().distinct(),
            )
        }, ids)
    }

    private fun ResultSet.instant(column: Int) = getObject(column, OffsetDateTime::class.java).toInstant()

    private companion object {
        const val SCHEMA = "dataset"

        /** The ISO code of the country a place lies in today: by its coordinates, else its first country with one. */
        const val PLACE_COUNTRY = """coalesce(pl.coordinates_country,
            (select pc.iso from dataset.place_country pc where pc.place = pl.qid and pc.iso is not null order by pc.position limit 1))"""
    }
}
