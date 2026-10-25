package art.liminis.release

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.ReportingPolicy
import art.liminis.domain.Anecdote
import art.liminis.domain.Connection
import art.liminis.domain.Door
import art.liminis.domain.Era
import art.liminis.domain.Event
import art.liminis.domain.EventParticipant
import art.liminis.domain.Life
import art.liminis.domain.Moment
import art.liminis.domain.Person
import art.liminis.domain.PersonPlace
import art.liminis.domain.Region
import art.liminis.domain.StoryCard

// One row per table of the site model (db/migration), its properties named after the columns: `regionCode` fills
// `region_code`. Enums are their names. Ids Hibernate would generate are given by [ReleaseRowMapper.rows], in the order
// Hibernate stored them.

data class RegionRow(val code: String, val name: String, val nameFr: String?)

data class EraRow(
    val id: String,
    val regionCode: String,
    val label: String,
    val governedBy: String,
    val startYear: Int,
    val endYear: Int,
    val labelFr: String?,
    val governedByFr: String?,
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
    val nameFr: String?,
    val occupationsFr: List<String>?,
    val wikipediaTitleFr: String?,
    val bioShortFr: String?,
    val aboutFr: String?,
    val wikipediaUrlFr: String?,
)

data class PersonPlaceRow(
    val personId: String,
    val position: Int,
    val placeQid: String,
    val name: String,
    val nameFr: String?,
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
    val labelFr: String?,
    val descriptionFr: String?,
)

data class EventRow(
    val id: String,
    val eraId: String,
    val title: String,
    val eventYear: Int,
    val description: String,
    val sourceUrl: String,
    val titleFr: String?,
    val descriptionFr: String?,
)

data class EventParticipantRow(val id: Long, val eventId: String, val personId: String, val role: String, val roleFr: String?)

data class ConnectionRow(
    val id: Long,
    val firstId: String,
    val secondId: String,
    val kind: String,
    val connectionYear: Int,
    val text: String,
    val sourceTitle: String?,
    val sourcePage: String?,
    val sourceEpisode: String?,
    val sourceMinute: String?,
    val sourceUrl: String?,
    val kindFr: String?,
    val textFr: String?,
)

data class AnecdoteRow(
    val id: String,
    val personId: String,
    val anecdoteYear: Int,
    val text: String,
    val title: String?,
    val page: String?,
    val episode: String?,
    val minute: String?,
    val url: String?,
    val textFr: String?,
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
    val placeFr: String?,
    val periodFr: String?,
    val hookFr: String?,
    val governsFr: String?,
    val everydayFr: String?,
    val artsFr: String?,
    val meanwhileFr: String?,
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
    val kickerFr: String?,
    val titleFr: String?,
    val textFr: String?,
)

data class DoorRow(
    val id: Long,
    val originId: String,
    val targetId: String,
    val position: Int,
    val kind: String,
    val text: String,
    val kindFr: String?,
    val textFr: String?,
)

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
    @Mapping(target = "labelFr", source = "fr.label")
    @Mapping(target = "governedByFr", source = "fr.governedBy")
    abstract fun era(entity: Era): EraRow

    @Mapping(target = "regionCode", source = "region.code")
    abstract fun person(entity: Person): PersonRow

    @Mapping(target = "personId", source = "person.id")
    @Mapping(target = "name", source = "place.name")
    @Mapping(target = "nameFr", source = "place.nameFr")
    abstract fun place(place: PersonPlace, person: Person, position: Int): PersonPlaceRow

    @Mapping(target = "eraId", source = "era.id")
    @Mapping(target = "url", source = "art.url")
    @Mapping(target = "credit", source = "art.credit")
    @Mapping(target = "sourceUrl", source = "art.sourceUrl")
    @Mapping(target = "position", source = "art.position")
    @Mapping(target = "fit", source = "art.fit")
    @Mapping(target = "labelFr", source = "fr.label")
    @Mapping(target = "descriptionFr", source = "fr.description")
    abstract fun life(entity: Life): LifeRow

    @Mapping(target = "eraId", source = "era.id")
    @Mapping(target = "eventYear", source = "year")
    @Mapping(target = "titleFr", source = "fr.title")
    @Mapping(target = "descriptionFr", source = "fr.description")
    abstract fun event(entity: Event): EventRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "eventId", source = "entity.event.id")
    @Mapping(target = "personId", source = "entity.person.id")
    abstract fun participant(entity: EventParticipant, rowId: Long): EventParticipantRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "firstId", source = "entity.first.id")
    @Mapping(target = "secondId", source = "entity.second.id")
    @Mapping(target = "connectionYear", source = "entity.year")
    @Mapping(target = "sourceTitle", source = "entity.source.title")
    @Mapping(target = "sourcePage", source = "entity.source.page")
    @Mapping(target = "sourceEpisode", source = "entity.source.episode")
    @Mapping(target = "sourceMinute", source = "entity.source.minute")
    @Mapping(target = "sourceUrl", source = "entity.source.url")
    @Mapping(target = "kindFr", source = "entity.fr.kind")
    @Mapping(target = "textFr", source = "entity.fr.text")
    abstract fun connection(entity: Connection, rowId: Long): ConnectionRow

    @Mapping(target = "personId", source = "person.id")
    @Mapping(target = "anecdoteYear", source = "year")
    @Mapping(target = "title", source = "source.title")
    @Mapping(target = "page", source = "source.page")
    @Mapping(target = "episode", source = "source.episode")
    @Mapping(target = "minute", source = "source.minute")
    @Mapping(target = "url", source = "source.url")
    abstract fun anecdote(entity: Anecdote): AnecdoteRow

    @Mapping(target = "regionCode", source = "region.code")
    @Mapping(target = "url", source = "art.url")
    @Mapping(target = "credit", source = "art.credit")
    @Mapping(target = "sourceUrl", source = "art.sourceUrl")
    @Mapping(target = "governs", source = "world.governs")
    @Mapping(target = "everyday", source = "world.everyday")
    @Mapping(target = "arts", source = "world.arts")
    @Mapping(target = "meanwhile", source = "world.meanwhile")
    @Mapping(target = "placeFr", source = "fr.place")
    @Mapping(target = "periodFr", source = "fr.period")
    @Mapping(target = "hookFr", source = "fr.hook")
    @Mapping(target = "governsFr", source = "fr.world.governs")
    @Mapping(target = "everydayFr", source = "fr.world.everyday")
    @Mapping(target = "artsFr", source = "fr.world.arts")
    @Mapping(target = "meanwhileFr", source = "fr.world.meanwhile")
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
    @Mapping(target = "kickerFr", source = "entity.fr.kicker")
    @Mapping(target = "titleFr", source = "entity.fr.title")
    @Mapping(target = "textFr", source = "entity.fr.text")
    abstract fun card(entity: StoryCard, rowId: Long): StoryCardRow

    @Mapping(target = "id", source = "rowId")
    @Mapping(target = "originId", source = "entity.origin.id")
    @Mapping(target = "targetId", source = "entity.target.id")
    @Mapping(target = "kindFr", source = "entity.fr.kind")
    @Mapping(target = "textFr", source = "entity.fr.text")
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
                "anecdote" to anecdotes.map(::anecdote),
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
