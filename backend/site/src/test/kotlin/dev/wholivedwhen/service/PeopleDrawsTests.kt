package dev.wholivedwhen.service

import org.junit.jupiter.api.Test
import dev.wholivedwhen.repository.MomentCandidate
import kotlin.test.assertEquals

class PeopleDrawsTests {

    private val candidates = (1..10).map { MomentCandidate("p$it", sitelinks = it * 10, atPlace = it % 3 == 0) }

    @Test
    fun `the first draw ranks the related, then those tied to the place, then the most written about`() {
        val draws = PeopleDraws(candidates, related = setOf("p1"), seed = "paris-1870s 1875", size = 4)

        assertEquals(listOf("p1", "p9", "p6", "p3"), draws.ids(0))
    }

    @Test
    fun `later draws show everyone once, the last filled up, then start over`() {
        val draws = PeopleDraws(candidates, related = emptySet(), seed = "paris-1870s 1875", size = 4)

        assertEquals(3, draws.count)
        val others = draws.ids(1) + draws.ids(2)
        assertEquals(candidates.map { it.id }.toSet() - draws.ids(0).toSet(), others.toSet())
        assertEquals(4, draws.ids(2).size)
        assertEquals(draws.ids(0), draws.ids(3))
    }

    @Test
    fun `the same seed makes the same draws, another seed others`() {
        val many = (1..60).map { MomentCandidate("p$it", sitelinks = 50, atPlace = false) }
        val draw = PeopleDraws(many, emptySet(), seed = "a").ids(1)

        assertEquals(draw, PeopleDraws(many, emptySet(), seed = "a").ids(1))
        assertEquals(false, draw == PeopleDraws(many, emptySet(), seed = "b").ids(1))
    }
}
