package art.liminis.service

import art.liminis.domain.Anecdote
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.Language
import art.liminis.domain.Person
import art.liminis.support.currentYear
import art.liminis.support.yearsBetween
import art.liminis.web.ApiMapper
import art.liminis.api.model.LanguageDto
import art.liminis.api.model.LifeLineDto
import art.liminis.api.model.LifeLineKindDto
import art.liminis.web.dto

/** How many events the person is not part of may appear, so their own moments stand out. */
private const val MAX_BACKGROUND_EVENTS = 4

/**
 * "Their life in their time": who governed when they were born, each change of regime during their life,
 * the documented events around them and their own, each with their age, and the anecdotes told about them. Built only
 * from dated records. The birth and regime lines carry their era, so the page can open it; anecdotes, their source.
 * Events and anecdotes are told in [language] where they are written in it; the lines worded here, birth, regimes and
 * death, are in English, with their era in [language] for the page to word them again.
 */
fun lifeInTime(
    person: Person,
    regionEras: List<Era>,
    regionEvents: List<Event>,
    anecdotes: List<Anecdote>,
    mapper: ApiMapper,
    language: Language,
): List<LifeLineDto> {
    val end = person.deathYear ?: currentYear()
    // An anecdote may tell of a year outside their life (a legend, a burial): it then has no age.
    val age = { year: Int -> yearsBetween(person.birthYear, year).takeIf { year in person.birthYear..end } }
    val lines = mutableListOf<LifeLineDto>()

    val bornUnder = regionEras.lastOrNull { person.birthYear in it.startYear..it.endYear }
    lines += LifeLineDto(
        person.birthYear, null, LifeLineKindDto.BIRTH, "Born" + (bornUnder?.let { " ${regimePhrase(it.label)}" } ?: ""), null,
        bornUnder?.let { mapper.toDto(it, language) }, null, LanguageDto.en,
    )

    regionEras
        .filter { it.startYear > person.birthYear && it.startYear <= end }
        .forEach {
            lines += LifeLineDto(it.startYear, age(it.startYear), LifeLineKindDto.ERA, "${withArticle(it.label)} begins", null, mapper.toDto(it, language), null, LanguageDto.en)
        }

    var background = 0
    regionEvents
        .filter { it.year in person.birthYear..end }
        .sortedBy { it.year }
        .forEach { event ->
            val told = mapper.toDto(event, language)
            val own = told.participants.firstOrNull { it.person.slug == person.slug }
            when {
                own != null -> lines += LifeLineDto(event.year, age(event.year), LifeLineKindDto.OWN_EVENT, told.title, own.role, null, null, told.lang)
                background++ < MAX_BACKGROUND_EVENTS -> lines += LifeLineDto(event.year, age(event.year), LifeLineKindDto.EVENT, told.title, null, null, null, told.lang)
            }
        }

    anecdotes.forEach {
        val french = it.textFr?.takeIf { language == Language.FR }
        lines += LifeLineDto(it.year, age(it.year), LifeLineKindDto.ANECDOTE, french ?: it.text, null, null, mapper.toDto(it.source), if (french != null) LanguageDto.fr else LanguageDto.en)
    }

    person.deathYear?.let { lines += LifeLineDto(it, age(it), LifeLineKindDto.DEATH, "Dies", null, null, null, LanguageDto.en) }
    return lines.sortedWith(compareBy({ it.year }, { it.kind.ordinal }))
}

// Place names read without an article ("in Roman Gaul"); regimes take one ("under the Third Republic").
private val PLACE_LIKE = "^(Celtic|Roman|Iron Age|Anglo-Saxon|Frankish|West Francia|Modern)".toRegex()
private val REGIME = "(republic|empire|monarchy|restoration|government|regime|shogunate)".toRegex(RegexOption.IGNORE_CASE)

private fun withArticle(label: String) =
    if (PLACE_LIKE.containsMatchIn(label) && !REGIME.containsMatchIn(label)) label else "The $label"

private fun regimePhrase(label: String) = when {
    REGIME.containsMatchIn(label) -> "under the $label"
    PLACE_LIKE.containsMatchIn(label) -> "in $label"
    else -> "in the $label"
}
