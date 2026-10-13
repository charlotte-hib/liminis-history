package dev.wholivedwhen.release

import org.mapstruct.factory.Mappers
import dev.wholivedwhen.domain.Connection
import dev.wholivedwhen.domain.Door
import dev.wholivedwhen.domain.Era
import dev.wholivedwhen.domain.Event
import dev.wholivedwhen.domain.Life
import dev.wholivedwhen.domain.Moment
import dev.wholivedwhen.domain.Person
import dev.wholivedwhen.domain.Region

/**
 * A release as the site's entities, new and not yet stored. Store them in this order. Doors come last, apart from
 * their moments, because they point at other moments.
 */
class CompiledRelease(
    val regions: List<Region>,
    val eras: List<Era>,
    val people: List<Person>,
    val lives: List<Life>,
    val events: List<Event>,
    val connections: List<Connection>,
    val moments: List<Moment>,
    val doors: List<Door>,
)

/**
 * Turns a release into the site's entities, in an order where every reference points at something already
 * mapped. [ReleaseMapper] does the mapping. Fails on the first problem, naming the file and the record.
 */
object ReleaseCompiler {

    private val mapper = Mappers.getMapper(ReleaseMapper::class.java)
    private val OBJECT_POSITION = Regex("""(100|[1-9]?[0-9])% (100|[1-9]?[0-9])%""")

    fun compile(release: Release): CompiledRelease {
        val references = References()

        val peopleCompiler = PeopleCompiler(release)
        val listed = release.regions.map { references.at("regions.jsonl ${it.id}") { mapper.region(it) } }
        val regions = listed + peopleCompiler.otherRegions(listed.map { it.code }.toSet()).map { (code, name) -> Region(code, name) }
        references.regions(regions)

        val eras = release.eras.map { references.at("eras.jsonl ${it.id}") { mapper.era(it, references) } }
        references.eras(eras)

        val people = peopleCompiler.compile(references)
        references.people(people)

        val lives = release.lives.map {
            references.at("lives.jsonl ${it.id}") {
                if (it.art != null && !OBJECT_POSITION.matches(it.art.position)) {
                    throw InvalidReleaseException("${references.where}: the art's position is two percentages, e.g. \"50% 70%\"")
                }
                mapper.life(it, references)
            }
        }
        references.lives(lives)

        val events = release.events.map { references.at("events.jsonl ${it.id}") { mapper.event(it, references) } }
        references.events(events)

        val connections = release.connections.map {
            references.at("connections.jsonl ${it.people.joinToString(" ")} ${it.kind}") {
                if (it.people.size != 2 || it.people[0] == it.people[1]) {
                    throw InvalidReleaseException("${references.where}: a connection links two different people")
                }
                mapper.connection(it, references)
            }
        }

        val moments = release.moments.map { references.at("moments/${it.id}.json") { mapper.moment(it, references) } }
        references.moments(moments)

        val doors = release.moments.zip(moments).flatMap { (record, moment) ->
            record.doors.mapIndexed { position, door ->
                references.at("moments/${record.id}.json door ${position + 1}") { mapper.door(door, moment, position, references) }
            }
        }

        return CompiledRelease(regions, eras, people, lives, events, connections, moments, doors)
    }
}

/**
 * The entities mapped so far, by id, for [ReleaseMapper] to resolve references; and where in the release the
 * mapping is, for error messages.
 */
class References {

    var where = ""
        private set

    private var regions = emptyMap<String, Region>()
    private var eras = emptyList<Era>()
    private var people = emptyMap<String, Person>()
    private var lives = emptyMap<String, Life>()
    private var events = emptyMap<String, Event>()
    private var moments = emptyMap<String, Moment>()

    fun <T> at(where: String, map: () -> T): T {
        val outer = this.where
        this.where = where
        try {
            return map()
        } finally {
            this.where = outer
        }
    }

    fun regions(all: List<Region>) { regions = all.associateBy { it.code } }
    fun eras(all: List<Era>) { eras = all }
    fun people(all: List<Person>) { people = all.associateBy { it.id } }
    fun lives(all: List<Life>) { lives = all.associateBy { it.id } }
    fun events(all: List<Event>) { events = all.associateBy { it.id } }
    fun moments(all: List<Moment>) { moments = all.associateBy { it.id } }

    fun region(id: String): Region = regions[id] ?: missing("region", id)
    fun person(id: String): Person = people[id] ?: missing("person", id)
    fun life(id: String): Life = lives[id] ?: missing("life", id)
    fun event(id: String): Event = events[id] ?: missing("event", id)
    fun moment(id: String): Moment = moments[id] ?: missing("moment", id)

    /** Consecutive eras share their boundary year; the one that starts later wins it. */
    fun eraAt(region: String, year: Int): Era =
        eras.filter { it.region.code == region && year in it.startYear..it.endYear }.maxByOrNull { it.startYear }
            ?: throw InvalidReleaseException("$where: no era in $region covers $year")

    private fun missing(kind: String, id: String): Nothing = throw InvalidReleaseException("$where: no $kind $id")
}
