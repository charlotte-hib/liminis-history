package art.liminis.service

import art.liminis.domain.Language
import art.liminis.domain.Person
import art.liminis.domain.PlaceKind
import art.liminis.api.model.PersonPlaceDto
import art.liminis.api.model.PlaceKindDto
import art.liminis.api.model.PlaceRoleDto
import art.liminis.web.dto

/**
 * Where [person] was born, worked, lived and died: one line per place, with every role it had (Wikidata may list a
 * place twice, or two items of the same name in one country: they are one line), in the order they were there, by the
 * first year known; places with no year come last, in Wikidata's order. A birth is dated by their birth, a death by
 * their death. Names are in [language] where Wikidata has them in it; [country] names a region code.
 */
fun placesOf(person: Person, language: Language, country: (String) -> String?): List<PersonPlaceDto> =
    person.places
        .groupBy { it.name.lowercase() to it.regionCode }
        .values
        .map { same ->
            val first = same.first()
            val french = first.nameFr?.takeIf { language == Language.FR }
            val roles = same
                .map { place ->
                    when (place.kind) {
                        PlaceKind.BIRTH -> PlaceRoleDto(PlaceKindDto.BIRTH, person.birthYear, person.birthYear)
                        PlaceKind.DEATH -> PlaceRoleDto(PlaceKindDto.DEATH, person.deathYear, person.deathYear)
                        PlaceKind.WORK -> PlaceRoleDto(PlaceKindDto.WORK, place.startYear, place.endYear)
                        PlaceKind.RESIDENCE -> PlaceRoleDto(PlaceKindDto.RESIDENCE, place.startYear, place.endYear)
                    }
                }
                .distinct()
                .sortedBy { it.kind.ordinal }
            PersonPlaceDto(french ?: first.name, first.regionCode?.let(country), roles, (if (french != null) Language.FR else Language.EN).dto())
        }
        .sortedBy { line -> line.roles.mapNotNull { it.startYear ?: it.endYear }.minOrNull() ?: Int.MAX_VALUE }
