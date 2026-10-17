package art.liminis.release

import org.junit.jupiter.api.Test
import art.liminis.domain.AnecdoteSource
import art.liminis.domain.ArtFit
import art.liminis.domain.CardType
import art.liminis.domain.Domain
import art.liminis.domain.LifeArt
import art.liminis.domain.LifeIcon
import art.liminis.domain.PublicationStatus
import art.liminis.support.currentYear
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseCompilerTests {

    private val release = Release(
        regions = listOf(RegionRecord("FR", "France")),
        eras = listOf(
            EraRecord("fr-second-empire", "FR", "Second Empire", governedBy = "Napoleon III", start = 1852, end = 1870),
            EraRecord("fr-third-republic", "FR", "Third Republic", start = 1870, end = 1940),
            EraRecord("fr-fifth-republic", "FR", "Fifth Republic", start = 1958),
        ),
        people = listOf(
            PersonRecord("emile-zola", qid = "Q504", name = "Émile Zola", born = 1840, died = 1902, region = "FR", domain = Domain.ARTS, occupation = "novelist"),
            PersonRecord("paul-cezanne", name = "Paul Cézanne", born = 1839, died = 1906, region = "FR", domain = Domain.ARTS, occupation = "painter", wikipedia = "Cézanne"),
        ),
        lives = listOf(LifeRecord("a-laundress", "FR", "A laundress", "Washes linen.", 1870, 1900)),
        events = listOf(
            EventRecord("jaccuse", "FR", 1898, "J'accuse", "An open letter.", "https://example.org/jaccuse", listOf(ParticipantRecord("emile-zola", "author"))),
        ),
        connections = listOf(ConnectionRecord(listOf("paul-cezanne", "emile-zola"), "school friends", 1852, "Friends.", "https://example.org/friends")),
        moments = listOf(
            MomentRecord(
                "paris-1870s", "FR", "Paris", period = "1870s", start = 1870, end = 1880, focus = 1875, status = PublicationStatus.PUBLISHED, hook = "A republic.",
                cards = listOf(CardRecord(CardType.PERSON, person = "emile-zola"), CardRecord(CardType.LIFE, life = "a-laundress")),
                doors = listOf(DoorRecord("paris-1890s", "follow", "Twenty years on", faces = listOf("emile-zola"))),
            ),
            MomentRecord("paris-1890s", "FR", "Paris", period = "1890s", start = 1890, end = 1900, focus = 1898, status = PublicationStatus.DRAFT, hook = "An affair."),
        ),
    )

    private fun problem(release: Release): String = assertFailsWith<InvalidReleaseException> { ReleaseCompiler.compile(release) }.message!!

    @Test
    fun `resolves references by id and fills in what records leave out`() {
        val compiled = ReleaseCompiler.compile(release)

        val zola = compiled.people.first()
        assertEquals("emile-zola", zola.slug)
        assertEquals("Émile_Zola", zola.wikipediaTitle)
        assertEquals("Cézanne", compiled.people[1].wikipediaTitle)
        assertEquals("Napoleon III", compiled.eras[0].governedBy)
        assertEquals("Third Republic", compiled.eras[1].governedBy)
        assertEquals(currentYear(), compiled.eras[2].endYear)
        assertEquals(zola, compiled.events.single().participants.single().person)
        assertEquals(listOf(compiled.people[1], zola), compiled.connections.single().let { listOf(it.first, it.second) })
        assertEquals(listOf(0, 1), compiled.moments[0].cards.map { it.position })
        assertEquals(compiled.lives.single(), compiled.moments[0].cards[1].life)
    }

    @Test
    fun `doors are kept apart from their moments, pointing at both ends`() {
        val compiled = ReleaseCompiler.compile(release)

        val door = compiled.doors.single()
        assertEquals(listOf("paris-1870s", "paris-1890s"), listOf(door.origin.id, door.target.id))
        assertEquals(listOf("emile-zola"), door.faces.map { it.id })
        assertEquals(emptyList(), compiled.moments[0].doors)
    }

    @Test
    fun `consecutive eras share their boundary year, and the later one wins it`() {
        val compiled = ReleaseCompiler.compile(release.copy(events = listOf(release.events[0].copy(year = 1870))))
        assertEquals("fr-third-republic", compiled.events.single().era.id)
        assertEquals("fr-third-republic", compiled.lives.single().era.id)
    }

    @Test
    fun `a life or event outside every era is an error`() {
        assertEquals("events.jsonl jaccuse: no era in FR covers 1945", problem(release.copy(events = listOf(release.events[0].copy(year = 1945)))))
    }

    @Test
    fun `an unknown reference is an error that names the record`() {
        assertEquals("events.jsonl jaccuse: no person zola", problem(release.copy(events = listOf(release.events[0].copy(participants = listOf(ParticipantRecord("zola", "author")))))))
        assertEquals("people.jsonl emile-zola: no region GA", problem(release.copy(people = listOf(release.people[0].copy(region = "GA")))))
        val moment = release.moments[0]
        assertEquals("moments/paris-1870s.json card 2: no life a-weaver", problem(release.copy(moments = listOf(moment.copy(cards = listOf(moment.cards[0], CardRecord(CardType.LIFE, life = "a-weaver")))))))
        assertEquals("moments/paris-1870s.json door 1: no moment paris-1900s", problem(release.copy(moments = listOf(moment.copy(doors = listOf(moment.doors[0].copy(to = "paris-1900s")))))))
    }

    @Test
    fun `a connection links two different people`() {
        val connection = ConnectionRecord(listOf("emile-zola", "emile-zola"), "friends", 1860, "Himself.", "https://example.org")
        assertEquals("connections.jsonl emile-zola emile-zola friends: a connection links two different people", problem(release.copy(connections = listOf(connection))))
    }

    @Test
    fun `an anecdote is tied to its person by Q-id, with a source that has a title or a URL`() {
        val anecdote = AnecdoteRecord("anecdote:Q504:1898:aaaa", "Q504", 1898, "He leaves for London.", AnecdoteSourceRecord(title = "A book", page = "112"))
        val compiled = ReleaseCompiler.compile(release.copy(anecdotes = listOf(anecdote)))

        assertEquals(compiled.people.first(), compiled.anecdotes.single().person)
        assertEquals(AnecdoteSource("A book", "112", null, null, null), compiled.anecdotes.single().source)
        assertEquals("anecdotes.jsonl anecdote:Q504:1898:aaaa: no person Q999", problem(release.copy(anecdotes = listOf(anecdote.copy(person = "Q999")))))
        assertEquals(
            "anecdotes.jsonl anecdote:Q504:1898:aaaa: the source has a title or a URL",
            problem(release.copy(anecdotes = listOf(anecdote.copy(source = AnecdoteSourceRecord(page = "112"))))),
        )
    }

    @Test
    fun `a life's art is centred and cropped unless its record says otherwise, and its icon is kept`() {
        val art = LifeArtRecord("https://example.org/laundress.jpg", "A painter, Laundresses, 1880", "https://example.org/laundresses")
        val lives = listOf(
            release.lives[0].copy(art = art),
            release.lives[0].copy(id = "a-weaver", art = art.copy(position = "15% 50%", fit = ArtFit.CONTAIN)),
            release.lives[0].copy(id = "a-mason", icon = LifeIcon.HAMMER),
        )

        val compiled = ReleaseCompiler.compile(release.copy(lives = lives))

        assertEquals(LifeArt(art.url, art.credit, art.source, "50% 50%", ArtFit.COVER), compiled.lives[0].art)
        assertEquals(LifeArt(art.url, art.credit, art.source, "15% 50%", ArtFit.CONTAIN), compiled.lives[1].art)
        assertEquals(null, compiled.lives[2].art)
        assertEquals(LifeIcon.HAMMER, compiled.lives[2].icon)
    }

    @Test
    fun `a life's art position is two percentages`() {
        val art = LifeArtRecord("https://example.org/laundress.jpg", "A painter, Laundresses, 1880", "https://example.org/laundresses", position = "center")
        assertEquals(
            "lives.jsonl a-laundress: the art's position is two percentages, e.g. \"50% 70%\"",
            problem(release.copy(lives = listOf(release.lives[0].copy(art = art)))),
        )
    }

    @Test
    fun `two people cannot share a slug`() {
        val namesake = release.people[1].copy(name = "Emile Zola")
        assertEquals("people.jsonl: emile-zola and paul-cezanne have the same slug, emile-zola", problem(release.copy(people = listOf(release.people[0], namesake))))
    }
}
