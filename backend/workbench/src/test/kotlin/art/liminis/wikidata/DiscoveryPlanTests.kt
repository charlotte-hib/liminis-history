package art.liminis.wikidata

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryPlanTests {

    private val properties = WikidataProperties(
        bornFrom = -3499,
        sitelinks = 25,
        earlySitelinks = 10,
        earlyUntil = 1800,
        livingYears = 110,
        sliceYears = listOf(SliceYears(1900, 10), SliceYears(1500, 100)),
        languages = listOf("en"),
        sites = listOf("enwiki"),
        linkedProperties = listOf("P19"),
        classLevels = 3,
        restarts = 0,
        restartPause = Duration.ZERO,
    )
    private val plan = DiscoveryPlan(properties, LocalDate.of(2026, 10, 9))
    private val slices = plan.slices()

    private fun slice(from: Int, until: Int) = BirthSlice(LocalDate.of(from, 1, 1), LocalDate.of(until, 1, 1))

    @Test
    fun `slices go from 3500 BCE to next year, a century, then a decade, then a year at a time`() {
        assertEquals(slice(-3499, -3399), slices.first())
        assertEquals(slice(1401, 1500), slices.single { it.from.year == 1401 })
        assertEquals(slice(1500, 1510), slices.single { it.from.year == 1500 })
        assertEquals(slice(1890, 1900), slices.single { it.from.year == 1890 })
        assertEquals(slice(1900, 1901), slices.single { it.from.year == 1900 })
        assertEquals(slice(2026, 2027), slices.last())
        assertEquals(50 + 40 + 127, slices.size)
        // Without gaps or overlaps.
        slices.zipWithNext { a, b -> assertEquals(a.until, b.from) }
    }

    @Test
    fun `the cut-off is lower for people born before 1800, and people who may be alive are left out`() {
        val caesar = plan.query(slice(-199, -99))
        assertTrue("""FILTER("-0199-01-01T00:00:00Z"^^xsd:dateTime <= ?born && ?born < "-0099-01-01T00:00:00Z"^^xsd:dateTime)""" in caesar, caesar)
        assertTrue("?sitelinks >= 10" in caesar, caesar)
        assertFalse("P570" in caesar, caesar)

        assertTrue("?sitelinks >= 10" in plan.query(slice(1790, 1800)))
        assertTrue("?sitelinks >= 25" in plan.query(slice(1800, 1810)))
        assertFalse("P570" in plan.query(slice(1915, 1916)))

        // Born 1916 or later with no date of death: alive, as far as Wikidata knows.
        val born1950 = plan.query(slice(1950, 1951))
        assertTrue("""FILTER(?born < "1916-01-01T00:00:00Z"^^xsd:dateTime || EXISTS { ?person wdt:P570 [] })""" in born1950, born1950)
    }

    @Test
    fun `a slice always splits into the same two halves`() {
        val (first, second) = slice(1950, 1951).halves()
        assertEquals(BirthSlice(LocalDate.of(1950, 1, 1), LocalDate.of(1950, 7, 2)), first)
        assertEquals(BirthSlice(LocalDate.of(1950, 7, 2), LocalDate.of(1951, 1, 1)), second)
        assertTrue(first in slice(1950, 1951))
        assertFalse(slice(1950, 1951) in first)
    }
}
