package dev.wholivedwhen.release

import org.junit.jupiter.api.Test
import dev.wholivedwhen.domain.PlaceKind
import java.nio.file.Path
import kotlin.test.assertEquals

/** The repository's sample/ release, which the site's tests, local runs and the Docker image serve. */
class SampleReleaseTests {

    private val sample = Path.of(System.getProperty("sample.dir") ?: error("Run with Gradle, which sets sample.dir"))

    @Test
    fun `sample compiles`() {
        val release = ReleaseCompiler.compile(ReleaseReader.read(sample))

        assertEquals(release.people.size, release.people.map { it.slug }.toSet().size)
        assertEquals("fr-celtic-gaul", release.events.single { it.id == "alesia" }.era.id)
    }

    @Test
    fun `sample's people written by hand take their places and article from its dataset`() {
        val release = ReleaseCompiler.compile(ReleaseReader.read(sample))

        val zola = release.people.single { it.id == "emile-zola" }
        assertEquals("Q504", zola.qid)
        assertEquals("Building the twenty-novel Rougon-Macquart cycle through the 1870s and 80s.", zola.bioShort)
        assertEquals("https://en.wikipedia.org/wiki/%C3%89mile_Zola", zola.wikipediaUrl)
        assertEquals("FR", zola.places.first { it.kind == PlaceKind.BIRTH }.regionCode)
        assert(release.people.size > 96) { "the dataset's other people are in the release" }
    }
}
