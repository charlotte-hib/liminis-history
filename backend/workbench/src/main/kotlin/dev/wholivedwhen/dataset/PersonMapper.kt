package dev.wholivedwhen.dataset

import dev.wholivedwhen.support.yearsBetween
import dev.wholivedwhen.wikidata.WikidataProperties

/** Why a person fetched from Wikidata is not in the dataset. */
enum class LeftOut {
    /** No date of birth: they cannot be placed in time. */
    NO_BIRTH,
    /** No date of death, and born within `app.wikidata.living-years`: alive, as far as Wikidata knows. */
    LIVING,
}

/** A person's row, or why there is none. */
sealed interface Mapped {
    data class Person(val row: PersonRow) : Mapped
    data class Out(val reason: LeftOut) : Mapped
}

/**
 * Maps a person's Wikidata entity to their row of the fetched dataset. Applies no decision: what `data/` corrects is
 * applied later, by the compiler.
 *
 * Dates: the preferred-rank value, else the only other value; several that disagree on the year give the earliest,
 * flagged. A death recorded as "unknown value", or none for someone born more than [WikidataProperties.livingYears]
 * before [currentYear], is estimated: the latest year they are known to have worked (P1317, P2032), else 60 years
 * after their birth.
 */
class PersonMapper(private val properties: WikidataProperties, private val currentYear: Int) {

    fun map(entity: WikidataEntity, sitelinks: Int): Mapped {
        val birth = date(entity, BIRTH)
        if (birth !is Dated) return Mapped.Out(LeftOut.NO_BIRTH)
        val born = birth.time.year
        val death = date(entity, DEATH)
        // The same rule as discovery's (DiscoveryPlan), which leaves most of them out already.
        if (death == null && yearsBetween(born, currentYear) <= properties.livingYears) return Mapped.Out(LeftOut.LIVING)

        val died = (death as? Dated)?.time?.year ?: estimateDeath(entity, born)
        val diedPrecision = (death as? Dated)?.time?.precision
        val occupations = entity.statements(OCCUPATION).mapNotNull { s -> s.mainsnak.id?.let { PersonOccupation(it, s.referenced) } }
        val enwiki = entity.sitelinks["enwiki"]?.title
        val frwiki = entity.sitelinks["frwiki"]?.title

        val flags = buildSet {
            if (!birth.referenced) add(PersonFlag.BIRTH_UNREFERENCED)
            if (birth.time.imprecise) add(PersonFlag.BIRTH_IMPRECISE)
            if (birth.disagrees) add(PersonFlag.BIRTH_DISAGREES)
            if (death is Dated) {
                if (!death.referenced) add(PersonFlag.DEATH_UNREFERENCED)
                if (death.time.imprecise) add(PersonFlag.DEATH_IMPRECISE)
                if (death.disagrees) add(PersonFlag.DEATH_DISAGREES)
            } else {
                add(PersonFlag.DEATH_ESTIMATED)
            }
            if (occupations.firstOrNull()?.referenced == false) add(PersonFlag.OCCUPATION_UNREFERENCED)
            if (sitelinks < properties.sitelinks && enwiki == null && frwiki == null) add(PersonFlag.FEW_SITELINKS_NO_ARTICLE)
        }

        return Mapped.Person(
            PersonRow(
                qid = entity.id,
                label = entity.label("en") ?: enwiki ?: entity.label("mul") ?: entity.labels.values.firstOrNull()?.value ?: entity.id,
                labelFr = entity.label("fr", "mul"),
                sitelinks = sitelinks,
                born = born,
                bornPrecision = birth.time.precision,
                died = died,
                diedPrecision = diedPrecision,
                diedEstimated = death !is Dated,
                datesApproximate = birth.approximate || death !is Dated || death.approximate,
                gender = entity.ids(GENDER).firstOrNull(),
                enwiki = enwiki,
                frwiki = frwiki,
                occupations = occupations,
                places = PlaceProperty.entries.flatMap { places(entity, it.code) },
                flags = flags,
            ),
        )
    }

    private sealed interface Date

    /** A year, from one statement or several that agree on it, or the earliest of several that disagree. */
    private data class Dated(val time: WikidataTime, val circa: Boolean, val referenced: Boolean, val disagrees: Boolean) : Date {
        val approximate get() = time.imprecise || circa
    }

    /** Recorded as "unknown value". */
    private data object Unknown : Date

    /** The date of [property], from the best rank stated; null when none is. */
    private fun date(entity: WikidataEntity, property: String): Date? {
        val statements = entity.statements(property)
        val best = statements.filter { it.rank == statements.firstOrNull()?.rank }
        val dated = best.mapNotNull { s -> s.mainsnak.time?.let { it to s } }
        if (dated.isEmpty()) return if (best.any { it.mainsnak.unknown }) Unknown else null

        val year = dated.minOf { it.first.year }
        val agreeing = dated.filter { it.first.year == year }
        val (time, statement) = agreeing.maxBy { it.first.precision }
        return Dated(time, statement.circa, agreeing.any { it.second.referenced }, dated.any { it.first.year != year })
    }

    private fun estimateDeath(entity: WikidataEntity, born: Int): Int =
        WORKED.flatMap { entity.statements(it) }.mapNotNull { it.mainsnak.time?.year }.filter { it >= born }.maxOrNull()
            ?: yearsAfter(born, ESTIMATED_AGE)

    private fun places(entity: WikidataEntity, property: String): List<PersonPlace> =
        entity.statements(property).mapNotNull { s ->
            s.mainsnak.id?.let { PersonPlace(property, it, s.qualifierYear(START), s.qualifierYear(END), s.referenced) }
        }

    /** The year [years] after [year], skipping the year 0 that never existed. */
    private fun yearsAfter(year: Int, years: Int): Int = (year + years).let { if (year < 0 && it >= 0) it + 1 else it }

    companion object {
        private const val BIRTH = "P569"
        private const val DEATH = "P570"
        private const val OCCUPATION = "P106"
        private const val GENDER = "P21"
        /** Floruit, and the end of a work period. */
        private val WORKED = listOf("P1317", "P2032")
        private const val START = "P580"
        private const val END = "P582"
        private const val ESTIMATED_AGE = 60

        /** The statements the mapping reads: the rest of an entity can stay in the database. */
        val PROPERTIES = listOf(BIRTH, DEATH, OCCUPATION, GENDER) + PlaceProperty.entries.map { it.code } + WORKED
    }
}
