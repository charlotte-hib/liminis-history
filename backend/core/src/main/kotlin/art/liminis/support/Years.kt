package art.liminis.support

import java.time.Year

// Years are integers; negative years are BCE. There is no year 0: 1 BCE is followed by 1 CE.

/** Whole years from one year to a later one, skipping the year 0 that never existed. */
fun yearsBetween(from: Int, to: Int): Int = to - from - if (from < 0 && to > 0) 1 else 0

fun currentYear(): Int = Year.now().value
