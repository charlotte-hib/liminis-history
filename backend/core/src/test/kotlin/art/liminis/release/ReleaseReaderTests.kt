package art.liminis.release

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseReaderTests {

    @TempDir
    lateinit var dir: Path

    private val files = mutableMapOf(
        "regions.jsonl" to """{"id":"FR","name":"France"}""",
        "eras.jsonl" to """{"id":"fr-third-republic","region":"FR","label":"Third Republic","start":1870,"end":1940}""",
        "people.jsonl" to """
            {"id":"emile-zola","name":"Émile Zola","born":1840,"died":1902,"region":"FR","domain":"ARTS","occupation":"novelist"}
            {"id":"paul-cezanne","name":"Paul Cézanne","born":1839,"died":1906,"region":"FR","domain":"ARTS","occupation":"painter"}
        """,
        "lives.jsonl" to "",
        "events.jsonl" to "",
        "connections.jsonl" to """
            {"people":["paul-cezanne","emile-zola"],"kind":"school friends","year":1852,"text":"Friends.","source":"https://example.org/a"}
            {"people":["paul-cezanne","emile-zola"],"kind":"wrote about","year":1886,"text":"A novel.","source":"https://example.org/b"}
        """,
        "moments/paris-1870s.json" to """{"id":"paris-1870s","region":"FR","place":"Paris","period":"1870s","start":1870,"end":1880,"focus":1875,"status":"PUBLISHED","hook":"A republic."}""",
    )

    private fun read(): Release {
        dir.resolve("moments").createDirectories()
        files.forEach { (name, text) -> dir.resolve(name).writeText(text.trimIndent().let { if (it.isEmpty()) it else "$it\n" }) }
        return ReleaseReader.read(dir)
    }

    private fun problem(): String = assertFailsWith<InvalidReleaseException> { read() }.message!!

    @Test
    fun `reads every file, with defaults for what a record leaves out`() {
        val release = read()

        assertEquals(listOf("emile-zola", "paul-cezanne"), release.people.map { it.id })
        assertEquals(false, release.people[0].approximate)
        assertEquals(2, release.connections.size)
        assertEquals(emptyList(), release.moments.single().cards)
    }

    @Test
    fun `corrections are optional, and read by Q-id when there are some`() {
        assertEquals(emptyList(), read().corrections)

        files["corrections.jsonl"] = """
            {"id":"Q504","born":1841,"occupations":["Q36180"]}
            {"id":"Q762","name":"Leonardo","approximate":false}
        """
        val corrections = read().corrections

        assertEquals(listOf(CorrectionRecord("Q504", born = 1841, occupations = listOf("Q36180")), CorrectionRecord("Q762", name = "Leonardo", approximate = false)), corrections)
    }

    @Test
    fun `anecdotes are optional, and read with their source when there are some`() {
        assertEquals(emptyList(), read().anecdotes)

        files["anecdotes.jsonl"] = """
            {"id":"anecdote:Q504:1898:aaaa","person":"Q504","year":1898,"text":"He leaves for London.","source":{"title":"A podcast","episode":"12","minute":"14:20"}}
            {"id":"anecdote:Q504:1902:bbbb","person":"Q504","year":1902,"text":"He dies at home.","source":{"url":"https://example.org/zola"}}
        """
        val anecdotes = read().anecdotes

        assertEquals(
            listOf(
                AnecdoteRecord("anecdote:Q504:1898:aaaa", "Q504", 1898, "He leaves for London.", AnecdoteSourceRecord(title = "A podcast", episode = "12", minute = "14:20")),
                AnecdoteRecord("anecdote:Q504:1902:bbbb", "Q504", 1902, "He dies at home.", AnecdoteSourceRecord(url = "https://example.org/zola")),
            ),
            anecdotes,
        )
    }

    @Test
    fun `records must be sorted by id`() {
        files["people.jsonl"] = files.getValue("people.jsonl").lines().reversed().joinToString("\n")
        assertEquals("people.jsonl:2: emile-zola is out of order", problem())
    }

    @Test
    fun `an id appears once`() {
        files["regions.jsonl"] = """
            {"id":"FR","name":"France"}
            {"id":"FR","name":"Gaul"}
        """
        assertEquals("regions.jsonl:2: FR appears twice", problem())
    }

    @Test
    fun `connections are sorted by their people, then by kind`() {
        files["connections.jsonl"] = files.getValue("connections.jsonl").lines().reversed().joinToString("\n")
        assertEquals("connections.jsonl:2: paul-cezanne emile-zola school friends is out of order", problem())
    }

    @Test
    fun `an unknown field is an error, not ignored`() {
        files["regions.jsonl"] = """{"id":"FR","name":"France","nmae":"Gaul"}"""
        assertContains(problem(), "regions.jsonl:1: Unrecognized property \"nmae\"")
    }

    @Test
    fun `a missing field is an error`() {
        files["regions.jsonl"] = """{"id":"FR"}"""
        assertContains(problem(), "regions.jsonl:1:")
    }

    @Test
    fun `a moment's file is named after its id`() {
        files["moments/paris-1880s.json"] = files.remove("moments/paris-1870s.json")!!
        assertEquals("moments/paris-1880s.json: its id is paris-1870s", problem())
    }

    @Test
    fun `every file must be there`() {
        files.remove("lives.jsonl")
        assertEquals("lives.jsonl is missing", problem())
    }
}
