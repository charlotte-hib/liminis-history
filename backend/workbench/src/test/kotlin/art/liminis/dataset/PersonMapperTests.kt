package art.liminis.dataset

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import tools.jackson.module.kotlin.treeToValue
import art.liminis.wikidata.SliceYears
import art.liminis.wikidata.WikidataProperties
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mapping of people on entities recorded from Wikidata on 2026-10-09: Julius Caesar, Victor Hugo (whose only
 * label in the languages asked for, besides Japanese, is the one shared by all languages, `mul`), Paul Aler (no English
 * Wikipedia article), and the 28 people born from 2000 to 1901 BCE, whose dates are as vague as they come.
 */
class PersonMapperTests {

    private val properties = WikidataProperties(
        bornFrom = -3499,
        sitelinks = 25,
        earlySitelinks = 10,
        earlyUntil = 1800,
        livingYears = 110,
        sliceYears = listOf(SliceYears(1500, 100)),
        languages = listOf("en", "fr", "ja", "mul"),
        sites = listOf("enwiki", "frwiki"),
        linkedProperties = listOf("P19"),
        classLevels = 3,
        restarts = 0,
        restartPause = Duration.ZERO,
    )
    private val mapper = PersonMapper(properties, 2026)

    private val jsonMapper = jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    // Victor Hugo from the second file, the one with the labels in mul.
    private val entities = listOf("wbgetentities-Q1048-Q535.json", "wbgetentities-Q535-Q552883.json", "wbgetentities-born-2000-to-1901-bce.json")
        .flatMap { file ->
            jsonMapper.readTree(ClassPathResource("wiremock/__files/wikidata/$file").inputStream).path("entities").properties()
                .map { (qid, json) -> qid to jsonMapper.treeToValue<WikidataEntity>(json) }
        }.toMap()

    private fun person(qid: String, sitelinks: Int = 100, mapper: PersonMapper = this.mapper): PersonRow =
        (mapper.map(entities.getValue(qid), sitelinks) as Mapped.Person).row

    @Test
    fun `Julius Caesar's dates are the preferred ones, with the year BCE as Wikidata writes it`() {
        val caesar = person("Q1048", sitelinks = 300)

        assertEquals("Julius Caesar", caesar.label)
        assertEquals("Jules César", caesar.labelFr)
        assertEquals(300, caesar.sitelinks)
        // Four dates of birth, two of them 102 BCE and "circa": the preferred one settles it.
        assertEquals(-100 to 10, caesar.born to caesar.bornPrecision)
        assertEquals(-44 to 11, caesar.died to caesar.diedPrecision)
        assertEquals(false, caesar.datesApproximate)
        assertEquals("Q6581097", caesar.gender)
        assertEquals("Julius Caesar" to "Jules César", caesar.enwiki to caesar.frwiki)
        assertEquals(10, caesar.occupations.size)
        assertEquals(PersonOccupation("Q12859263", referenced = false), caesar.occupations.first())
        assertEquals(
            listOf(
                PersonPlace("P19", "Q220", null, null, referenced = true),
                PersonPlace("P20", "Q944814", null, null, referenced = true),
                PersonPlace("P551", "Q220", null, null, referenced = false),
            ),
            caesar.places,
        )
        // His first occupation, the one the site would show, has no reference.
        assertEquals(setOf(PersonFlag.OCCUPATION_UNREFERENCED), caesar.flags)
    }

    @Test
    fun `without an English or French label, the English Wikipedia title and the label for all languages name a person`() {
        val hugo = person("Q535")

        assertEquals("Victor Hugo", hugo.label)
        assertEquals("Victor Hugo", hugo.labelFr)
        assertEquals(1802 to 1885, hugo.born to hugo.died)
        // A deprecated occupation is left out; places of residence keep their years. That one's only reference says
        // which Wikipedia it was imported from (P143), which names no source.
        assertEquals(15, hugo.occupations.size)
        assertEquals("Q214917", hugo.occupations.first().occupation)
        assertEquals(PersonPlace("P551", "Q92817862", 1852, 1855, referenced = false), hugo.places.single { it.place == "Q92817862" })
        assertEquals(emptySet(), hugo.flags)
    }

    @Test
    fun `a death recorded as unknown is estimated 60 years after the birth`() {
        val chnumet = person("Q1075501")

        assertEquals(-1900 to 7, chnumet.born to chnumet.bornPrecision)
        assertEquals(-1840, chnumet.died)
        assertNull(chnumet.diedPrecision)
        assertEquals(true, chnumet.diedEstimated)
        assertEquals(true, chnumet.datesApproximate)
        // The birth's only reference is an import from a Wikipedia (P143).
        assertEquals(setOf(PersonFlag.BIRTH_IMPRECISE, PersonFlag.BIRTH_UNREFERENCED, PersonFlag.DEATH_ESTIMATED), chnumet.flags)
    }

    @Test
    fun `no date of death for someone born centuries ago is estimated too`() {
        val sarenput = person("Q3473600")

        assertEquals(-1950 to -1890, sarenput.born to sarenput.died)
        assertEquals(true, sarenput.diedEstimated)
    }

    @Test
    fun `the end of a work period, when stated, is the estimated death`() {
        assertEquals(-1794, person("Q736198").died)
    }

    @Test
    fun `no date of death, born less than 110 years ago, is someone alive, left out`() {
        val seenFrom1900Bce = PersonMapper(properties, -1900)

        assertEquals(Mapped.Out(LeftOut.LIVING), seenFrom1900Bce.map(entities.getValue("Q3473600"), 10))
        // A death recorded as unknown is a death all the same.
        assertEquals(-1840, person("Q1075501", mapper = seenFrom1900Bce).died)
    }

    @Test
    fun `an estimate across the year 0 skips it`() {
        val born30Bce = WikidataEntity(
            id = "Q1",
            labels = mapOf("en" to Term("Someone")),
            claims = mapOf("P569" to listOf(Statement(Snak("value", time("-0030-00-00T00:00:00Z", 9)), Rank.NORMAL))),
        )

        assertEquals(31, (mapper.map(born30Bce, 10) as Mapped.Person).row.died)
    }

    @Test
    fun `dates that disagree give the earliest, flagged`() {
        val daDing = person("Q888231")

        assertEquals(-1900, daDing.born)
        assertEquals(true, PersonFlag.BIRTH_DISAGREES in daDing.flags)
    }

    @Test
    fun `a circa or a date coarser than a year makes the dates approximate`() {
        // Born circa 1950 BCE, with no reference.
        val shalimAhum = person("Q2618883")
        assertEquals(-1950 to 9, shalimAhum.born to shalimAhum.bornPrecision)
        assertEquals(true, shalimAhum.datesApproximate)
        assertEquals(true, PersonFlag.BIRTH_UNREFERENCED in shalimAhum.flags)
        assertEquals(false, PersonFlag.BIRTH_IMPRECISE in shalimAhum.flags)

        // Born and died in a decade, as Wikidata has it, with no reference; and no occupation.
        val loulanBeauty = person("Q18654056")
        assertEquals(-1900 to 8, loulanBeauty.born to loulanBeauty.bornPrecision)
        assertEquals(-1800 to 8, loulanBeauty.died to loulanBeauty.diedPrecision)
        assertEquals(emptyList(), loulanBeauty.occupations)
        assertEquals(
            setOf(PersonFlag.BIRTH_IMPRECISE, PersonFlag.BIRTH_UNREFERENCED, PersonFlag.DEATH_IMPRECISE, PersonFlag.DEATH_UNREFERENCED),
            loulanBeauty.flags,
        )
    }

    @Test
    fun `a person with no English Wikipedia article keeps the French one`() {
        val aler = person("Q552883", sitelinks = 10)

        assertEquals("Paul Aler", aler.label)
        assertEquals(null to "Paul Aler", aler.enwiki to aler.frwiki)
        assertEquals(1656 to 1727, aler.born to aler.died)
        assertEquals(emptySet(), aler.flags)
    }

    @Test
    fun `few sitelinks and no article in English or French is flagged`() {
        // Paul Aler, as if French Wikipedia had no article either.
        val noArticle = entities.getValue("Q552883").copy(sitelinks = emptyMap())

        assertEquals(true, PersonFlag.FEW_SITELINKS_NO_ARTICLE in (mapper.map(noArticle, 24) as Mapped.Person).row.flags)
        assertEquals(false, PersonFlag.FEW_SITELINKS_NO_ARTICLE in (mapper.map(noArticle, 25) as Mapped.Person).row.flags)
    }

    private fun time(time: String, precision: Int) =
        DataValue(jsonMapper.readValue("""{"time": "$time", "precision": $precision}"""))
}
