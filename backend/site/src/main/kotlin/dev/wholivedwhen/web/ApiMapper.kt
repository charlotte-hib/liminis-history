package dev.wholivedwhen.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import dev.wholivedwhen.api.model.ArtworkDto
import dev.wholivedwhen.api.model.ConnectionDto
import dev.wholivedwhen.api.model.DoorDto
import dev.wholivedwhen.api.model.EraDto
import dev.wholivedwhen.api.model.EventDto
import dev.wholivedwhen.api.model.LifeArtDto
import dev.wholivedwhen.api.model.LifeDto
import dev.wholivedwhen.api.model.MomentSummaryDto
import dev.wholivedwhen.api.model.ParticipantDto
import dev.wholivedwhen.api.model.PersonDto
import dev.wholivedwhen.api.model.RegionDto
import dev.wholivedwhen.api.model.StoryCardDto
import dev.wholivedwhen.api.model.WorldAroundDto
import dev.wholivedwhen.domain.Artwork
import dev.wholivedwhen.domain.Connection
import dev.wholivedwhen.domain.Door
import dev.wholivedwhen.domain.Era
import dev.wholivedwhen.domain.Event
import dev.wholivedwhen.domain.EventParticipant
import dev.wholivedwhen.domain.Life
import dev.wholivedwhen.domain.LifeArt
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.domain.Region
import dev.wholivedwhen.domain.StoryCard
import dev.wholivedwhen.domain.WorldAround

/** Entities to the API's models, which are generated from api/openapi.yaml (package dev.wholivedwhen.api.model). */
@Mapper
interface ApiMapper {
    fun toDto(region: Region): RegionDto
    fun toDto(life: Life): LifeDto
    fun toDto(art: LifeArt): LifeArtDto
    fun toDto(event: Event): EventDto
    fun toDto(artwork: Artwork): ArtworkDto
    fun toDto(world: WorldAround): WorldAroundDto
    fun toDto(card: StoryCard): StoryCardDto

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", source = "region.name")
    fun toDto(era: Era): EraDto

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", source = "region.name")
    @Mapping(target = "occupation", expression = "java(person.getOccupations().isEmpty() ? \"\" : person.getOccupations().get(0))")
    fun toDto(person: Person): PersonDto

    fun toDto(participant: EventParticipant): ParticipantDto

    /** A moment as listed, with what is worked out from other tables: its regime, its story's length, its cast. */
    @Mapping(target = "regionCode", source = "moment.region.code")
    @Mapping(target = "region", source = "moment.region.name")
    fun toSummary(moment: Moment, governedBy: String?, storyCards: Int, cast: List<PersonDto>): MomentSummaryDto

    /** A curated door, to the [target] moment as listed. */
    @Mapping(target = "target", source = "target")
    fun toDto(door: Door, target: MomentSummaryDto): DoorDto

    /** A curated connection, seen from the other [person]'s side. */
    @Mapping(target = "person", source = "person")
    fun toDto(connection: Connection, person: Person): ConnectionDto
}
