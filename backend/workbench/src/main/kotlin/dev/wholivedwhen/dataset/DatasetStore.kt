package dev.wholivedwhen.dataset

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
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
                dataset.place_country, dataset.place_flag
            """.trimIndent(),
        )
    }

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

    private companion object {
        const val SCHEMA = "dataset"

        /** The ISO code of the country a place lies in today: by its coordinates, else its first country with one. */
        const val PLACE_COUNTRY = """coalesce(pl.coordinates_country,
            (select pc.iso from dataset.place_country pc where pc.place = pl.qid and pc.iso is not null order by pc.position limit 1))"""
    }
}
