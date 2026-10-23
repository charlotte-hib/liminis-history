package art.liminis.release

import org.mapstruct.AfterMapping
import org.mapstruct.Context
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.mapstruct.Named
import org.mapstruct.ReportingPolicy
import art.liminis.domain.Anecdote
import art.liminis.domain.AnecdoteSource
import art.liminis.domain.Artwork
import art.liminis.domain.Connection
import art.liminis.domain.Door
import art.liminis.domain.Era
import art.liminis.domain.EraFrench
import art.liminis.domain.Event
import art.liminis.domain.Life
import art.liminis.domain.LifeArt
import art.liminis.domain.Moment
import art.liminis.domain.MomentFrench
import art.liminis.domain.Person
import art.liminis.domain.Region
import art.liminis.domain.StoryCard
import art.liminis.domain.WorldAround

/**
 * Turns records into entities. MapStruct copies the fields; references are ids, which [References] turns into the
 * entities mapped before them. People are compiled by [PeopleCompiler], from records and the fetched dataset. Every target field is mapped or ignored on purpose (`unmappedTargetPolicy`).
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class ReleaseMapper {

    @Mapping(target = "code", source = "id")
    @Mapping(target = "nameFr", source = "fr.name")
    abstract fun region(record: RegionRecord): Region

    @Mapping(target = "governedBy", source = "governedBy", defaultExpression = "java(record.getLabel())")
    @Mapping(target = "startYear", source = "start")
    @Mapping(target = "endYear", source = "end", defaultExpression = "java(art.liminis.support.YearsKt.currentYear())")
    abstract fun era(record: EraRecord, @Context references: References): Era

    @Mapping(target = "governedBy", source = "governedBy", defaultExpression = "java(record.getLabel())")
    abstract fun eraFrench(record: EraFrenchRecord): EraFrench

    @Mapping(target = "era", source = "record", qualifiedByName = ["eraOfLife"])
    @Mapping(target = "startYear", source = "start")
    @Mapping(target = "endYear", source = "end")
    abstract fun life(record: LifeRecord, @Context references: References): Life

    @Mapping(target = "sourceUrl", source = "source")
    abstract fun lifeArt(record: LifeArtRecord): LifeArt

    @Mapping(target = "era", source = "record", qualifiedByName = ["eraOfEvent"])
    @Mapping(target = "sourceUrl", source = "source")
    @Mapping(target = "participants", ignore = true)
    abstract fun event(record: EventRecord, @Context references: References): Event

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "first", source = "people", qualifiedByName = ["first"])
    @Mapping(target = "second", source = "people", qualifiedByName = ["second"])
    @Mapping(target = "sourceUrl", source = "source")
    abstract fun connection(record: ConnectionRecord, @Context references: References): Connection

    @Mapping(target = "person", source = "person", qualifiedByName = ["personByQid"])
    @Mapping(target = "textFr", source = "fr.text")
    abstract fun anecdote(record: AnecdoteRecord, @Context references: References): Anecdote

    abstract fun anecdoteSource(record: AnecdoteSourceRecord): AnecdoteSource

    @Mapping(target = "startYear", source = "start")
    @Mapping(target = "endYear", source = "end")
    @Mapping(target = "focusYear", source = "focus")
    @Mapping(target = "cards", ignore = true)
    // Doors point at other moments, so they are mapped once every moment is: see door().
    @Mapping(target = "doors", ignore = true)
    abstract fun moment(record: MomentRecord, @Context references: References): Moment

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "art", source = "card.art")
    @Mapping(target = "fr", source = "card.fr")
    abstract fun card(card: CardRecord, moment: Moment, position: Int, @Context references: References): StoryCard

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "target", source = "door.to")
    @Mapping(target = "fr", source = "door.fr")
    abstract fun door(door: DoorRecord, origin: Moment, position: Int, @Context references: References): Door

    @Mapping(target = "sourceUrl", source = "source")
    abstract fun artwork(record: ArtRecord): Artwork

    abstract fun world(record: WorldRecord): WorldAround

    abstract fun momentFrench(record: MomentFrenchRecord): MomentFrench

    @AfterMapping
    protected fun addParticipants(record: EventRecord, @MappingTarget event: Event, @Context references: References) {
        record.participants.forEach { event.participant(references.person(it.person), it.role, it.fr?.role) }
    }

    @AfterMapping
    protected fun addCards(record: MomentRecord, @MappingTarget moment: Moment, @Context references: References) {
        record.cards.forEachIndexed { position, card ->
            references.at("moments/${record.id}.json card ${position + 1}") { moment.cards += card(card, moment, position, references) }
        }
    }

    // References by id, chosen by MapStruct from the target's type. A card's person, life and event are optional.
    protected fun region(id: String?, @Context references: References): Region? = id?.let(references::region)
    protected fun person(id: String?, @Context references: References): Person? = id?.let(references::person)
    protected fun life(id: String?, @Context references: References): Life? = id?.let(references::life)
    protected fun event(id: String?, @Context references: References): Event? = id?.let(references::event)
    protected fun moment(id: String?, @Context references: References): Moment? = id?.let(references::moment)

    @Named("eraOfLife")
    protected fun eraOfLife(record: LifeRecord, @Context references: References): Era = references.eraAt(record.region, record.start)

    @Named("eraOfEvent")
    protected fun eraOfEvent(record: EventRecord, @Context references: References): Era = references.eraAt(record.region, record.year)

    @Named("personByQid")
    protected fun personByQid(qid: String, @Context references: References): Person = references.personByQid(qid)

    @Named("first")
    protected fun first(people: List<String>, @Context references: References): Person = references.person(people[0])

    @Named("second")
    protected fun second(people: List<String>, @Context references: References): Person = references.person(people[1])
}
