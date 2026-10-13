package dev.wholivedwhen.service

import dev.wholivedwhen.repository.MomentCandidate
import java.util.Random
import kotlin.math.ln

/**
 * Which of a moment's people to show, [size] at a time. Draw 0 ranks them: people with documented relations first,
 * then those tied to the moment's place, then the most written about. Each later draw takes the next [size] of the
 * others in a random order weighted the same way, so that nobody comes back before everyone has been shown; the last
 * draw is filled up from the start of that order. The order is seeded by [seed]: the same draw always shows the same
 * people.
 */
class PeopleDraws(
    candidates: List<MomentCandidate>,
    private val related: Set<String>,
    seed: String,
    private val size: Int = DRAW_SIZE,
) {
    private val ranked = candidates.sortedWith(
        compareByDescending<MomentCandidate> { it.id in related }.thenByDescending { it.atPlace }.thenByDescending { it.sitelinks }.thenBy { it.id },
    )
    private val first = ranked.take(size)

    // Efraimidis and Spirakis's weighted shuffle: each person's key is ln(u) / weight, highest first.
    private val others = ranked.drop(size).sortedWith(
        compareByDescending<MomentCandidate> { ln(Random("$seed ${it.id}".hashCode().toLong()).nextDouble(MIN_U, 1.0)) / weight(it) }.thenBy { it.id },
    )

    /** How many different draws there are. */
    val count: Int = 1 + (others.size + size - 1) / size

    /** The ids of [draw]'s people; a draw past the last starts over. */
    fun ids(draw: Int): List<String> {
        val index = draw % count
        if (index == 0) return first.map { it.id }
        val page = others.drop((index - 1) * size).take(size)
        val filled = if (page.size < size && others.size >= size) page + others.take(size - page.size) else page
        return filled.map { it.id }
    }

    private fun weight(candidate: MomentCandidate): Double =
        (candidate.sitelinks + 1.0) * (if (candidate.id in related) RELATED_WEIGHT else 1.0) * (if (candidate.atPlace) PLACE_WEIGHT else 1.0)

    companion object {
        const val DRAW_SIZE = 24
        private const val RELATED_WEIGHT = 4.0
        private const val PLACE_WEIGHT = 2.0
        private const val MIN_U = 1e-12
    }
}
