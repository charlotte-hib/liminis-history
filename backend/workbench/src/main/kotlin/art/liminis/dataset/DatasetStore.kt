package art.liminis.dataset

import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The workbench's schema `dataset` ([art.liminis.WorkbenchSchemas]): the rows mapped from `raw`, rebuilt on every
 * mapping run.
 */
@Component
class DatasetStore(dataSource: DataSource, private val mapper: DatasetPersonMapper) {

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
            MappedImport(rs.getLong("import_run"), rs.instant("import_started_at"), rs.instant("import_finished_at"))
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
        val occupations = grouped(
            "person",
            "select person, occupation, referenced from dataset.person_occupation where person = any (?) order by person, position",
            DataClassRowMapper(PersonOccupation::class.java),
            ids,
        )
        val places = grouped(
            "person",
            """
            select person, property, place, start_year, end_year, referenced
            from dataset.person_place pp
            where person = any (?)
            order by person, ${PlaceProperty.sqlOrder("pp")}, position
            """.trimIndent(),
            DataClassRowMapper(PersonPlace::class.java),
            ids,
        )
        val flags = grouped(
            "person",
            "select person, reason from dataset.person_flag where person = any (?) order by person, reason",
            { rs, _ -> PersonFlag.valueOf(rs.getString("reason")) },
            ids,
        )
        return people.map {
            it.copy(occupations = occupations[it.qid].orEmpty(), places = places[it.qid].orEmpty(), flags = flags[it.qid].orEmpty().toSet())
        }
    }

    /** Each row of [sql], read by [read], under the value of its column [key], in the query's order. */
    private fun <T : Any> grouped(key: String, sql: String, read: RowMapper<T>, vararg args: Any): Map<String, List<T>> =
        jdbc.query(sql, { rs, row -> rs.getString(key) to read.mapRow(rs, row)!! }, *args)
            .groupBy({ it.first }, { it.second })

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
        val countries = grouped(
            "place",
            "select place, country, iso from dataset.place_country order by place, position",
            DataClassRowMapper(PlaceCountry::class.java),
        )
        val flags = grouped("place", "select place, reason from dataset.place_flag order by place, reason", { rs, _ ->
            PlaceFlag.valueOf(rs.getString("reason"))
        })
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
        val parents = grouped(
            "occupation",
            "select occupation, parent from dataset.occupation_parent order by occupation, position",
            { rs, _ -> rs.getString("parent") },
        )
        return jdbc.query("select qid, label, label_fr, female_label_fr from dataset.occupation order by qid") { rs, _ ->
            val qid = rs.getString("qid")
            OccupationRow(qid, rs.getString("label"), rs.getString("label_fr"), rs.getString("female_label_fr"), parents[qid].orEmpty())
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
            { rs, _ -> rs.getString("qid") }, region, end, start, limit,
        )

    /**
     * The rows of those of [qids] in the dataset, with the labels of their occupations and places and the country each
     * place lies in today, in no particular order.
     */
    fun people(qids: Collection<String>): List<DatasetPerson> {
        val rows = personRows("qid = any (?)", qids.toTypedArray())
        val occupationIds = rows.flatMap { row -> row.occupations.map { it.occupation } }.distinct().toTypedArray()
        val occupations = jdbc.query(
            "select qid as occupation, label, label_fr from dataset.occupation where qid = any (?)",
            DataClassRowMapper(DatasetOccupation::class.java),
            occupationIds,
        ).associateBy { it.occupation }
        val placeIds = rows.flatMap { row -> row.places.map { it.place } }.distinct().toTypedArray()
        val places = jdbc.query(
            "select pl.qid, pl.label, $PLACE_COUNTRY as country from dataset.place pl where pl.qid = any (?)",
            { rs, _ -> rs.getString("qid") to (rs.getString("label") to rs.getString("country")) },
            placeIds,
        ).toMap()
        val placeFlags = grouped("place", "select place, flag, reason from dataset.place_flag where place = any (?)", { rs, _ ->
            rs.getString("flag") to rs.getString("reason")
        }, placeIds)
        return rows.map { row ->
            mapper.person(
                row,
                // A deleted occupation or place is in no table: it keeps no label.
                labelledOccupations = row.occupations.map { occupations[it.occupation] ?: DatasetOccupation(it.occupation, null, null) },
                labelledPlaces = row.places.map {
                    val (label, country) = places[it.place] ?: (null to null)
                    DatasetPlace(it.property, it.place, label, country, it.startYear, it.endYear)
                },
                // The person's flags, then their places' under the place's property, such as P19_DISPUTED_AREA.
                allFlags = (
                    row.flags.map { it.flag to it.name } +
                        row.places.flatMap { place -> placeFlags[place.place].orEmpty().map { (flag, reason) -> flag to "${place.property}_$reason" } }
                    ).distinct().sortedWith(compareBy({ it.first }, { it.second })),
            )
        }
    }

    /**
     * The people whose English or French label contains [query], ignoring case and accents, those it starts with first,
     * then the best known; the person [query] names first when it is a Q-id.
     */
    fun find(query: String, limit: Int): List<PersonSummary> =
        jdbc.query(
            """
            select $SUMMARY
            from dataset.person p
            where $NAMED
            order by p.qid = ? desc, starts_with(${fold("p.label")}, ${fold("?")}) desc, p.sitelinks desc, p.qid
            limit ?
            """.trimIndent(),
            DataClassRowMapper(PersonSummary::class.java), query, query, query, query, query, limit,
        )

    /**
     * The people [find] finds, but those in [except], as a story card of a moment lists them: the person [query]
     * names first when it is a Q-id, then those [aliveIn] the moment's [region] from [start] to [end], then as [find]
     * orders them.
     */
    fun findAround(query: String, region: String, start: Int, end: Int, except: Collection<String>, limit: Int): List<FoundPerson> =
        jdbc.query(
            // The moment's people once, materialized: as a subquery per person found, Postgres took seconds.
            """
            with here as materialized (select p.qid from dataset.person p where $ALIVE_IN),
            found as (
                select p.qid, p.sitelinks, p.qid = ? as exact, p.qid in (select qid from here) as here,
                    starts_with(${fold("p.label")}, ${fold("?")}) as starts
                from dataset.person p
                where ($NAMED) and p.qid <> all (?)
                order by exact desc, here desc, starts desc, p.sitelinks desc, p.qid
                limit ?
            )
            select $SUMMARY, f.exact, f.here, f.starts,
                (select a.thumbnail_url from dataset.person_article a
                 where a.person = p.qid and a.thumbnail_url is not null order by a.language limit 1) as portrait
            from found f join dataset.person p on p.qid = f.qid
            order by f.exact desc, f.here desc, f.starts desc, f.sitelinks desc, f.qid
            """.trimIndent(),
            DataClassRowMapper(FoundPerson::class.java),
            end, start, region, query.uppercase(), query, query.uppercase(), query, query, except.toTypedArray(), limit,
        )

    /** Each person's first three occupations, the ones the site shows and takes their domain from, in order. */
    fun firstOccupations(): Map<String, List<String>> =
        grouped(
            "person",
            "select person, occupation from dataset.person_occupation where position < 3 order by person, position",
            { rs, _ -> rs.getString("occupation") },
        )

    /** Those of [qids] in the dataset, the [limit] best known first. */
    fun summaries(qids: Collection<String>, limit: Int): List<PersonSummary> =
        jdbc.query(
            """
            select $SUMMARY
            from dataset.person p
            where p.qid = any (?)
            order by p.sitelinks desc, p.qid
            limit ?
            """.trimIndent(),
            DataClassRowMapper(PersonSummary::class.java), qids.toTypedArray(), limit,
        )

    /**
     * The people tied to [region] by a place of theirs (where they were born, died, worked or lived, by the country it
     * lies in today) and alive at some time from [start] to [end]: the best known first, [limit] after [offset].
     */
    fun aliveIn(region: String, start: Int, end: Int, limit: Int, offset: Int = 0): List<PersonSummary> =
        jdbc.query(
            "select $SUMMARY from dataset.person p where $ALIVE_IN order by p.sitelinks desc, p.qid limit ? offset ?",
            DataClassRowMapper(PersonSummary::class.java), end, start, region, limit, offset,
        )

    /** How many people [aliveIn] finds. */
    fun countAliveIn(region: String, start: Int, end: Int): Int =
        jdbc.queryForObject("select count(*) from dataset.person p where $ALIVE_IN", Int::class.java, end, start, region)!!

    /** The Q-ids of everyone [aliveIn] finds. */
    fun qidsAliveIn(region: String, start: Int, end: Int): List<String> =
        jdbc.query("select p.qid from dataset.person p where $ALIVE_IN order by p.qid", { rs, _ -> rs.getString("qid") }, end, start, region)

    /** The English Wikipedia intros of those of [qids] who have one, by person. */
    fun intros(qids: Collection<String>): Map<String, Intro> =
        jdbc.query(
            "select person, title, url, extract from dataset.person_article where person = any (?) and language = 'en' and extract <> ''",
            DataClassRowMapper(Intro::class.java), qids.toTypedArray(),
        ).associateBy { it.person }

    /**
     * Those of [qids] the dataset holds, as a story card shows them: their first occupation, a portrait from their
     * English Wikipedia article, else the French one, and the English intro.
     */
    fun portraits(qids: Collection<String>): List<PersonPortrait> =
        jdbc.query(
            """
            select $SUMMARY,
                (select a.thumbnail_url from dataset.person_article a
                 where a.person = p.qid and a.thumbnail_url is not null order by a.language limit 1) as portrait,
                (select a.extract from dataset.person_article a where a.person = p.qid and a.language = 'en') as intro
            from dataset.person p where p.qid = any (?) order by p.qid
            """.trimIndent(),
            DataClassRowMapper(PersonPortrait::class.java), qids.toTypedArray(),
        )

    /** The person's Wikipedia articles, by language. */
    /** The titles of the French Wikipedia articles of those of [qids] who have one, by Q-id. */
    fun frenchArticles(qids: Collection<String>): Map<String, String> =
        jdbc.query(
            "select qid, frwiki from dataset.person where qid = any (?) and frwiki is not null",
            { rs, _ -> rs.getString("qid") to rs.getString("frwiki") },
            qids.toTypedArray(),
        ).toMap()

    /** The French labels of those of [qids], people or places, that have one, by Q-id. */
    fun frenchLabels(qids: Collection<String>): Map<String, String> =
        jdbc.query(
            """
            select qid, label_fr from dataset.person where qid = any (?) and label_fr is not null
            union all
            select qid, label_fr from dataset.place where qid = any (?) and label_fr is not null
            """.trimIndent(),
            { rs, _ -> rs.getString("qid") to rs.getString("label_fr") },
            qids.toTypedArray(), qids.toTypedArray(),
        ).toMap()

    fun articles(person: String): List<ArticleRow> =
        jdbc.query(
            "select person, language, title, url, extract, thumbnail_url, image from dataset.person_article where person = ? order by language",
            ARTICLE, person,
        )

    private fun ResultSet.instant(column: String) = getObject(column, OffsetDateTime::class.java).toInstant()

    // NAMED and ALIVE_IN are shared with the people to review, which add the claims' status to the people they find.
    internal companion object {
        /** A person's own columns; their occupations, places and flags are read apart. */
        private val PERSON = RowMapper { rs, _ ->
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
        private val ARTICLE = DataClassRowMapper(ArticleRow::class.java)

        /** A person as lists show them, from `dataset.person p`. */
        private const val SUMMARY = """p.qid as person, p.label, p.label_fr, p.born, p.died, p.sitelinks,
                (select o.label from dataset.person_occupation po join dataset.occupation o on o.qid = po.occupation
                 where po.person = p.qid order by po.position limit 1) as occupation"""

        /** The ISO code of the country a place lies in today: by its coordinates, else its first country with one. */
        private const val PLACE_COUNTRY = """coalesce(pl.coordinates_country,
            (select pc.iso from dataset.place_country pc where pc.place = pl.qid and pc.iso is not null order by pc.position limit 1))"""

        /**
         * Lowercase and without accents ("Émile" becomes "emile"), as the release's `fold_for_search` does; that
         * function lives in the release's schema, the extension in schema `extensions`, made by core's migrations.
         */
        private fun fold(sql: String) = "lower(extensions.unaccent('extensions.unaccent'::regdictionary, $sql))"

        /** The person the first parameter names by Q-id, or whose label contains the second or the third. */
        val NAMED = """p.qid = ? or strpos(${fold("p.label")}, ${fold("?")}) > 0 or strpos(${fold("p.label_fr")}, ${fold("?")}) > 0"""

        /** Alive at some time from the first parameter's year to the second's, and tied to the region the third names. */
        const val ALIVE_IN = """p.born <= ? and p.died >= ? and exists (
                select 1 from dataset.person_place pp join dataset.place pl on pl.qid = pp.place
                where pp.person = p.qid and $PLACE_COUNTRY = ?)"""
    }
}
