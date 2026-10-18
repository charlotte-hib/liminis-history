package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import art.liminis.api.model.AnecdoteSourceDto
import art.liminis.api.model.ArtworkDto
import art.liminis.api.model.ConnectionDto
import art.liminis.api.model.DoorDto
import art.liminis.api.model.EraDto
import art.liminis.api.model.EventDto
import art.liminis.api.model.LifeArtDto
import art.liminis.api.model.LifeDto
import art.liminis.api.model.MomentSummaryDto
import art.liminis.api.model.ParticipantDto
import art.liminis.api.model.PersonDto
import art.liminis.api.model.RegionDto
import art.liminis.api.model.StoryCardDto
import art.liminis.api.model.WorldAroundDto
import art.liminis.domain.AnecdoteSource
import art.liminis.domain.Artwork
import art.liminis.domain.Connection
import art.liminis.domain.Door
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.EventParticipant
import art.liminis.domain.Life
import art.liminis.domain.LifeArt
import art.liminis.domain.Moment
import art.liminis.domain.Person
import art.liminis.domain.Region
import art.liminis.domain.StoryCard
import art.liminis.domain.WorldAround

/** Entities to the API's models, which are generated from api/openapi.yaml (package art.liminis.api.model). */
@Mapper
interface ApiMapper {
    fun toDto(region: Region): RegionDto
    fun toDto(life: Life): LifeDto
    fun toDto(art: LifeArt): LifeArtDto
    fun toDto(event: Event): EventDto
    fun toDto(artwork: Artwork): ArtworkDto
    fun toDto(world: WorldAround): WorldAroundDto
    fun toDto(card: StoryCard): StoryCardDto
    fun toDto(source: AnecdoteSource): AnecdoteSourceDto

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", source = "region.name")
    fun toDto(era: Era): EraDto

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "region", source = "region.name")
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
