package dev.wholivedwhen.release

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.ReportingPolicy
import dev.wholivedwhen.domain.Connection
import dev.wholivedwhen.domain.Door
import dev.wholivedwhen.domain.Era
import dev.wholivedwhen.domain.Event
import dev.wholivedwhen.domain.EventParticipant
import dev.wholivedwhen.domain.Life
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.domain.PersonPlace
import dev.wholivedwhen.domain.Region
import dev.wholivedwhen.domain.StoryCard

// One row per table of the site model (db/migration), its properties named after the columns: `regionCode` fills
// `region_code`. Enums are their names. Ids Hibernate would generate are given by [ReleaseRowMapper.rows], in the order
// Hibernate stored them.

data class RegionRow(val code: String, val name: String)

data class EraRow(
    val id: String, val regionCode: String, val label: String, val governedBy: String, val startYear: Int, val endYear: Int,
)

data class PersonRow(
    val id: String,
    val qid: String?,
    val slug: String,
    val name: String,
    val birthYear: Int,
    val deathYear: Int?,
    val datesApproximate: Boolean,
    val regionCode: String,
    val domain: String,
    val occupations: List<String>,
    val sitelinks: Int,
    val fame: Int,
    val wikipediaTitle: String?,
    val bioShort: String?,
    val about: String?,
    val wikipediaUrl: String?,
    val portraitUrl: String?,
)

data class PersonPlaceRow(
    val personId: String,
    val position: Int,
    val placeQid: String,
    val kind: String,
    val regionCode: String?,
    val startYear: Int?,
    val endYear: Int?,
)

data class LifeRow(
    val id: String,
    val eraId: String,
    val label: String,
    val description: String,
    val startYear: Int,
    val endYear: Int,
    val url: String?,
    val credit: String?,
    val sourceUrl: String?,
    val position: String?,
    val fit: String?,
    val icon: String?,
)

data class EventRow(
    val id: String, val eraId: String, val title: String, val eventYear: Int, val description: String, val sourceUrl: String,
)

data class EventParticipantRow(val id: Long, val eventId: String, val personId: String, val role: String)

data class ConnectionRow(
    val id: Long,
    val firstId: String,
    val secondId: String,
    val kind: String,
    val connectionYear: Int,
    val text: String,
    val sourceUrl: String,
)

data class MomentRow(
    val id: String,
    val regionCode: String,
    val place: String,
    val placeQid: String?,
    val period: String,
    val startYear: Int,
    val endYear: Int,
    val focusYear: Int,
    val hook: String,
    val url: String?,
    val credit: String?,
    val sourceUrl: String?,
    val governs: String?,
    val everyday: String?,
    val arts: String?,
    val meanwhile: String?,
    val status: String,
    val featured: Boolean,
)

data class StoryCardRow(
    val id: Long,
    val momentId: String,
    val position: Int,
    val type: String,
    val kicker: String?,
    val title: String?,
    val text: String?,
    val cardYear: Int?,
    val personId: String?,
    val lifeId: String?,
    val eventId: String?,
    val url: String?,
    val credit: String?,
    val sourceUrl: String?,
)

data class DoorRow(val id: Long, val originId: String, val targetId: String, val position: Int, val kind: String, val text: String)

data class DoorFaceRow(val doorId: Long, val position: Int, val facesId: String)

/** A compiled release as rows, table by table, in an order where every reference points at a row before it. */
class ReleaseRows(
    val tables: List<Pair<String, List<Any>>>,
) {
    fun count(table: String) = tables.first { it.first == table }.second.size
}

/** Turns the compiled entities into rows. MapStruct copies the fields; references become the ids they point at. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class ReleaseRowMapper {

    abstract fun region(entity: Region): RegionRow

    @Mapping(target = "regionCode", source = "region.code")
    abstract fun era(entity: Era): EraRow

    @Mapping(target = "regionCode", source = "region.code")
    abstract fun person(entity: Person): PersonRow

    @Mapping(target = "personId", source = "person.id")
    abstract fun place(place: PersonPlace, person: Person, position: Int): PersonPlaceRow

    @Mapping(target = "eraId", source = "era.id")
    @Mapping(target = "url", source = "art.url")
    @Mapping(target = "credit", source = "art.credit")
    @Mapping(target = "sourceUrl", source = "art.sourceUrl")
    @Mapping(target = "position", source = "art.position")
    @Mapping(target = "fit", source = "art.fit")
    abstract fun life(entity: Life): LifeRow

    @Mapping(target = "eraId", source = "era.id")
    @Mapping(target = "eventYear", source = "year")
    abstract fun event(entity: Event): EventRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "eventId", source = "entity.event.id")
    @Mapping(target = "personId", source = "entity.person.id")
    abstract fun participant(entity: EventParticipant, rowId: Long): EventParticipantRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "firstId", source = "entity.first.id")
    @Mapping(target = "secondId", source = "entity.second.id")
    @Mapping(target = "connectionYear", source = "entity.year")
    abstract fun connection(entity: Connection, rowId: Long): ConnectionRow

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "url", source = "art.url")
    @Mapping(target = "credit", source = "art.credit")
    @Mapping(target = "sourceUrl", source = "art.sourceUrl")
    @Mapping(target = "governs", source = "world.governs")
    @Mapping(target = "everyday", source = "world.everyday")
    @Mapping(target = "arts", source = "world.arts")
    @Mapping(target = "meanwhile", source = "world.meanwhile")
    abstract fun moment(entity: Moment): MomentRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "momentId", source = "entity.moment.id")
    @Mapping(target = "cardYear", source = "entity.year")
    @Mapping(target = "personId", source = "entity.person.id")
    @Mapping(target = "lifeId", source = "entity.life.id")
    @Mapping(target = "eventId", source = "entity.event.id")
    @Mapping(target = "url", source = "entity.art.url")
    @Mapping(target = "credit", source = "entity.art.credit")
    @Mapping(target = "sourceUrl", source = "entity.art.sourceUrl")
    abstract fun card(entity: StoryCard, rowId: Long): StoryCardRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "originId", source = "entity.origin.id")
    @Mapping(target = "targetId", source = "entity.target.id")
    abstract fun door(entity: Door, rowId: Long): DoorRow

    /** Every table's rows. Generated ids count from 1 in the order the entities were stored before: by their parent. */
    fun rows(release: CompiledRelease): ReleaseRows = with(release) {
        val doorRows = doors.mapIndexed { i, door -> door(door, i + 1L) }
        ReleaseRows(
            listOf(
                "region" to regions.map(::region),
                "era" to eras.map(::era),
                "person" to people.map(::person),
                "person_place" to people.flatMap { person -> person.places.mapIndexed { position, place -> place(place, person, position) } },
                "life" to lives.map(::life),
                "event" to events.map(::event),
                "event_participant" to events.flatMap { it.participants }.mapIndexed { i, it -> participant(it, i + 1L) },
                "connection" to connections.mapIndexed { i, it -> connection(it, i + 1L) },
                "moment" to moments.map(::moment),
                "story_card" to moments.flatMap { it.cards }.mapIndexed { i, it -> card(it, i + 1L) },
                "door" to doorRows,
                "door_face" to doors.zip(doorRows).flatMap { (door, row) ->
                    door.faces.mapIndexed { position, face -> DoorFaceRow(row.id, position, face.id) }
                },
            )
        )
    }
}
