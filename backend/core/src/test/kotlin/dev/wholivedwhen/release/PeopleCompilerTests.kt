package dev.wholivedwhen.release

import org.junit.jupiter.api.Test
import dev.wholivedwhen.dataset.ArticleRow
import dev.wholivedwhen.dataset.DatasetFileCounts
import dev.wholivedwhen.dataset.DatasetManifest
import dev.wholivedwhen.dataset.FetchedDataset
import dev.wholivedwhen.dataset.MappedImport
import dev.wholivedwhen.dataset.OccupationRow
import dev.wholivedwhen.dataset.PersonOccupation
import dev.wholivedwhen.dataset.PersonPlace
import dev.wholivedwhen.dataset.PersonRow
import dev.wholivedwhen.dataset.PlaceCountry
import dev.wholivedwhen.dataset.PlaceRow
import dev.wholivedwhen.domain.Domain
import dev.wholivedwhen.domain.PlaceKind
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeopleCompilerTests {

    private val paris = PlaceRow("Q90", "Paris", null, null, "FR", null, emptyList(), emptySet())
    private val rome = PlaceRow("Q220", "Rome", null, null, null, null, listOf(PlaceCountry("Q38", "IT")), emptySet())
    private val atlantis = PlaceRow("Q1", "Atlantis", null, null, null, null, listOf(PlaceCountry("Q2", null)), emptySet())

    private val occupations = listOf(
        OccupationRow("Q1028181", "painter", null, null, listOf("Q3391743")),
        OccupationRow("Q3391743", "visual artist", null, null, listOf("Q483501")),
        OccupationRow("Q483501", "artist", null, null, emptyList()),
        OccupationRow("Q82955", "politician", null, null, emptyList()),
        OccupationRow("Q2478141", "aristocrat", null, null, listOf("Q82955")),
        OccupationRow("Q36180", "writer", null, null, emptyList()),
        OccupationRow("Q81096", "engineer", null, null, emptyList()),
    )

    private fun row(qid: String, label: String, born: Int, sitelinks: Int, birthplace: PlaceRow?, vararg occupations: String) = PersonRow(
        qid, label, null, sitelinks, born, 9, born + 60, 9, false, false, null, null, null,
        occupations.map { PersonOccupation(it, true) },
        listOfNotNull(birthplace?.let { PersonPlace("P19", it.qid, null, null, true) }) + PersonPlace("P937", "Q90", 1880, 1890, false),
        emptySet(),
    )

    private val zola = row("Q504", "Émile Zola", 1840, 150, paris, "Q36180")
    private val leonardo = row("Q762", "Leonardo da Vinci", 1452, 300, rome, "Q1028181", "Q81096", "Q36180", "Q483501")

    private fun release(people: List<PersonRow>, articles: List<ArticleRow> = emptyList(), mapped: List<OccupationRecord> = emptyList()) = Release(
        regions = listOf(RegionRecord("FR", "France")),
        eras = emptyList(),
        people = listOf(PersonRecord("emile-zola", qid = "Q504", name = "Émile Zola", born = 1840, died = 1902, region = "FR", domain = Domain.ARTS, occupation = "novelist")),
        occupations = mapped,
        dataset = FetchedDataset(
            DatasetManifest(1, MappedImport(1, Instant.EPOCH, Instant.EPOCH), DatasetFileCounts(people.size, articles.size, 3, occupations.size)),
            people,
            articles.associateBy { it.person },
            listOf(paris, rome, atlantis).associateBy { it.qid },
            occupations.associateBy { it.qid },
        ),
        lives = emptyList(),
        events = emptyList(),
        connections = emptyList(),
        moments = emptyList(),
    )

    @Test
    fun `someone written by hand keeps what is written and takes places, sitelinks and the article from the dataset`() {
        val article = ArticleRow("Q504", "en", "Émile Zola", "https://en.wikipedia.org/wiki/%C3%89mile_Zola", "Émile Zola was a novelist. He wrote.", "https://upload.example/zola.jpg", "Zola.jpg")
        val compiled = ReleaseCompiler.compile(release(listOf(zola), listOf(article)))

        val person = compiled.people.single()
        assertEquals("emile-zola", person.id)
        assertEquals(listOf("novelist"), person.occupations)
        assertEquals(150, person.sitelinks)
        assertEquals("Émile Zola was a novelist. He wrote.", person.bioShort)
        assertEquals("https://upload.example/zola.jpg", person.portraitUrl)
        assertEquals(listOf(PlaceKind.BIRTH to "FR", PlaceKind.WORK to "FR"), person.places.map { it.kind to it.regionCode })
    }

    @Test
    fun `other people of the dataset come in with their birthplace's country, and those without one stay out`() {
        val lost = row("Q3", "Someone of Atlantis", 1500, 40, atlantis, "Q36180")
        val nowhere = row("Q4", "Someone of nowhere", 1500, 40, null, "Q36180")
        val compiled = ReleaseCompiler.compile(release(listOf(zola, leonardo, lost, nowhere)))

        assertEquals(listOf("emile-zola", "Q762"), compiled.people.map { it.id })
        assertEquals(listOf("FR" to "France", "IT" to "Italy"), compiled.regions.map { it.code to it.name })
        assertEquals("IT", compiled.people[1].region.code)
    }

    @Test
    fun `occupations show up to three, and the domain comes from the first one mapped, through its classes`() {
        val mapped = listOf(OccupationRecord("Q483501", Domain.ARTS), OccupationRecord("Q82955", Domain.POWER), OccupationRecord("Q2478141"))
        val aristocrat = row("Q5", "A count", 1600, 30, paris, "Q2478141", "Q36180")
        val engineer = row("Q6", "An engineer", 1600, 30, paris, "Q81096")
        val people = ReleaseCompiler.compile(release(listOf(zola, leonardo, aristocrat, engineer), mapped = mapped)).people.associateBy { it.id }

        assertEquals(listOf("painter", "engineer", "writer"), people.getValue("Q762").occupations)
        // painter is a visual artist, an artist: two levels up.
        assertEquals(Domain.ARTS, people.getValue("Q762").domain)
        // aristocrat is mapped to none: its class politician is not looked at, and writer is not mapped.
        assertEquals(Domain.EVERYDAY, people.getValue("Q5").domain)
        assertEquals(Domain.EVERYDAY, people.getValue("Q6").domain)
    }

    @Test
    fun `namesakes get their year of birth, the one most written about keeps the name, and people written by hand keep theirs`() {
        val namesake = row("Q7", "Émile Zola", 1900, 10, paris)
        val elder = row("Q8", "John Smith", 1580, 50, paris)
        val younger = row("Q9", "John Smith", 1580, 20, paris)
        val slugs = ReleaseCompiler.compile(release(listOf(zola, namesake, elder, younger))).people.associate { it.id to it.slug }

        assertEquals(mapOf("emile-zola" to "emile-zola", "Q7" to "emile-zola-1900", "Q8" to "john-smith", "Q9" to "john-smith-1580"), slugs)
    }

    @Test
    fun `fame is a percentile among the people of the same region and century of birth`() {
        val fewer = row("Q10", "A lesser painter", 1801, 15, paris)
        val elsewhere = row("Q11", "A Roman", 1850, 1, rome)
        val people = ReleaseCompiler.compile(release(listOf(zola, fewer, elsewhere))).people.associateBy { it.id }

        assertEquals(100, people.getValue("emile-zola").fame)
        assertEquals(50, people.getValue("Q10").fame)
        assertEquals(100, people.getValue("Q11").fame)
    }

    @Test
    fun `a leading hatnote is left out of the bio`() {
        val article = ArticleRow("Q762", "en", "Leonardo da Vinci", "https://en.wikipedia.org/wiki/Leonardo_da_Vinci", "For the film, see Leonardo (film).\nLeonardo was a polymath.", null, null)
        val person = ReleaseCompiler.compile(release(listOf(zola, leonardo), listOf(article))).people.single { it.id == "Q762" }

        assertEquals("Leonardo was a polymath.", person.bioShort)
    }

    @Test
    fun `two people written by hand cannot share a Q-id`() {
        val base = release(listOf(zola))
        val twin = base.people[0].copy(id = "zola-again", name = "Zola Again")
        assertEquals(
            "people.jsonl: two people have the Q-id Q504",
            assertFailsWith<InvalidReleaseException> { ReleaseCompiler.compile(base.copy(people = base.people + twin)) }.message,
        )
    }
}
