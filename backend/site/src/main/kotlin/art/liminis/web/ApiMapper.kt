package art.liminis.web

import org.mapstruct.Context
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import org.mapstruct.NullValueCheckStrategy
import art.liminis.api.model.ArtworkDto
import art.liminis.api.model.CitedSourceDto
import art.liminis.api.model.ConnectionDto
import art.liminis.api.model.DoorDto
import art.liminis.api.model.EraDto
import art.liminis.api.model.EventDto
import art.liminis.api.model.LanguageDto
import art.liminis.api.model.LifeArtDto
import art.liminis.api.model.LifeDto
import art.liminis.api.model.MomentSummaryDto
import art.liminis.api.model.ParticipantDto
import art.liminis.api.model.PersonDto
import art.liminis.api.model.RegionDto
import art.liminis.api.model.StoryCardDto
import art.liminis.api.model.WorldAroundDto
import art.liminis.domain.Artwork
import art.liminis.domain.CitedSource
import art.liminis.domain.Connection
import art.liminis.domain.Door
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.EventParticipant
import art.liminis.domain.Language
import art.liminis.domain.Life
import art.liminis.domain.LifeArt
import art.liminis.domain.Moment
import art.liminis.domain.Person
import art.liminis.domain.Region
import art.liminis.domain.StoryCard
import art.liminis.domain.WorldAround

/**
 * Entities to the API's models, which are generated from api/openapi.yaml (package art.liminis.api.model), in the
 * [Language] asked for. MapStruct copies a record in English (the methods named `english`); in French, the record's
 * French replaces its texts, when it has one, and its `lang` says so. A record without French stays in English.
 * Properties are checked for null before they are mapped, as the hand-written methods take none.
 */
@Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
abstract class ApiMapper {

    abstract fun toDto(art: LifeArt): LifeArtDto
    abstract fun toDto(artwork: Artwork): ArtworkDto
    abstract fun toDto(source: CitedSource): CitedSourceDto

    @Named("english")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(world: WorldAround): WorldAroundDto

    @Named("english")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(life: Life): LifeDto

    @Named("english")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(event: Event, @Context language: Language): EventDto

    @Named("english")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(card: StoryCard, @Context language: Language): StoryCardDto

    @Named("english")
    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", expression = "java(regionName(era.getRegion(), language))")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(era: Era, @Context language: Language): EraDto

    @Named("english")
    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", expression = "java(regionName(person.getRegion(), language))")
    @Mapping(target = "lang", constant = "en")
    @Mapping(target = "bioLang", constant = "en")
    protected abstract fun english(person: Person, @Context language: Language): PersonDto

    @Named("english")
    @Mapping(target = "regionCode", source = "moment.region.code")
    @Mapping(target = "region", expression = "java(regionName(moment.getRegion(), language))")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(moment: Moment, governedBy: String?, storyCards: Int, cast: List<PersonDto>, @Context language: Language): MomentSummaryDto

    @Named("english")
    @Mapping(target = "target", source = "target")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(door: Door, target: MomentSummaryDto, @Context language: Language): DoorDto

    @Named("english")
    @Mapping(target = "person", source = "person")
    @Mapping(target = "lang", constant = "en")
    protected abstract fun english(connection: Connection, person: Person, @Context language: Language): ConnectionDto

    fun toDto(region: Region, language: Language) = RegionDto(region.code, regionName(region, language))

    fun toDto(life: Life, @Context language: Language): LifeDto =
        english(life).let { dto -> life.fr?.inFrench(language)?.let { dto.copy(label = it.label, description = it.description, lang = LanguageDto.fr) } ?: dto }

    fun toDto(event: Event, @Context language: Language): EventDto =
        english(event, language).let { dto ->
            event.fr?.inFrench(language)?.let { dto.copy(title = it.title, description = it.description, lang = LanguageDto.fr) } ?: dto
        }

    /** A participant with their role in the event's language. */
    fun toDto(participant: EventParticipant, @Context language: Language) =
        ParticipantDto(toDto(participant.person, language), participant.roleFr?.inFrench(language) ?: participant.role)

    fun toDto(era: Era, @Context language: Language): EraDto =
        english(era, language).let { dto -> era.fr?.inFrench(language)?.let { dto.copy(label = it.label, governedBy = it.governedBy, lang = LanguageDto.fr) } ?: dto }

    /**
     * A person in French when their name and occupations are written in French (someone with none known, by their name);
     * their bio in French when it is, apart.
     */
    fun toDto(person: Person, @Context language: Language): PersonDto {
        val dto = english(person, language)
        if (language != Language.FR) return dto
        val occupations = person.occupationsFr ?: emptyList<String>().takeIf { person.occupations.isEmpty() }
        val named = if (person.nameFr != null && occupations != null) dto.copy(name = person.nameFr!!, occupations = occupations, lang = LanguageDto.fr) else dto
        return person.bioShortFr?.let { named.copy(bioShort = it, bioLang = LanguageDto.fr) } ?: named
    }

    /** The world around [moment], in French when its French has it. */
    fun world(moment: Moment, language: Language): WorldAroundDto? =
        moment.fr?.inFrench(language)?.world?.let { english(it).copy(lang = LanguageDto.fr) } ?: moment.world?.let(::english)

    /** A card in French when its texts are; a card with none, showing only its record, is in the language asked for. */
    fun toDto(card: StoryCard, @Context language: Language): StoryCardDto {
        val dto = english(card, language)
        val french = card.fr?.inFrench(language) ?: return if (card.wordless() && language == Language.FR) dto.copy(lang = LanguageDto.fr) else dto
        return dto.copy(kicker = french.kicker, title = french.title, text = french.text, lang = LanguageDto.fr)
    }

    /** A moment as listed, with what is worked out from other tables: its regime, its story's length, its cast. */
    fun toSummary(moment: Moment, governedBy: String?, storyCards: Int, cast: List<PersonDto>, language: Language): MomentSummaryDto =
        english(moment, governedBy, storyCards, cast, language).let { dto ->
            moment.fr?.inFrench(language)?.let { dto.copy(place = it.place, period = it.period, hook = it.hook, lang = LanguageDto.fr) } ?: dto
        }

    /** A curated door, to the [target] moment as listed. */
    fun toDto(door: Door, target: MomentSummaryDto, language: Language): DoorDto =
        english(door, target, language).let { dto -> door.fr?.inFrench(language)?.let { dto.copy(kind = it.kind, text = it.text, lang = LanguageDto.fr) } ?: dto }

    /** A curated connection, seen from the other [person]'s side. */
    fun toDto(connection: Connection, person: Person, language: Language): ConnectionDto =
        english(connection, person, language).let { dto ->
            connection.fr?.inFrench(language)?.let { dto.copy(kind = it.kind, text = it.text, lang = LanguageDto.fr) } ?: dto
        }

    /** A region's name in [language], when the release has it in French. */
    fun regionName(region: Region, language: Language): String = region.nameFr?.inFrench(language) ?: region.name

    private fun <T> T.inFrench(language: Language): T? = takeIf { language == Language.FR }

    private fun StoryCard.wordless() = kicker == null && title == null && text == null
}

fun LanguageDto.language(): Language = Language.valueOf(name.uppercase())

fun Language.dto(): LanguageDto = LanguageDto.valueOf(name.lowercase())
