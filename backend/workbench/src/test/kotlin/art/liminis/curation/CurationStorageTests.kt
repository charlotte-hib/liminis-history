package art.liminis.curation

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals

/**
 * Claims stored by earlier versions of the workbench, written here as SQL, still read the same. The schema curation
 * is never dropped: a change to the stored types that breaks these rows needs a migration of them, not a new test.
 */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@Import(PostgresTestConfiguration::class)
class CurationStorageTests(@Autowired private val claims: Claims, @Autowired private val jdbc: JdbcTemplate) {

    @BeforeEach
    fun setUp() {
        CurationFixtures.clearClaims(jdbc)
    }

    @Test
    fun `reads a person's facts, their flags, sources and decisions as stored on 2026-10-11`() {
        val facts = """
            {"type": "PERSON_FACTS", "person": "Q535", "label": "Victor Hugo", "labelFr": "Victor Hugo", "born": 1802,
             "bornPrecision": 11, "died": 1885, "diedPrecision": 11, "diedEstimated": false, "datesApproximate": false,
             "enwiki": "Victor Hugo", "frwiki": "Victor Hugo",
             "occupations": [{"occupation": "Q36180", "label": "writer", "labelFr": "écrivain"}],
             "places": [{"role": "BIRTH", "place": "Q37776", "label": "Besançon", "country": "FR"},
                        {"role": "RESIDENCE", "place": "Q1", "label": "Guernsey", "startYear": 1855, "endYear": 1870}]}
        """.trimIndent()
        insertClaim("person-facts:Q535", "PERSON_FACTS", "CANDIDATE", "WIKIDATA", facts, """[{"flag": "CONFLICT", "reason": "CHANGED_SINCE_APPROVED"}]""")
        val source = jdbc.queryForObject(
            """insert into curation.source (kind, locator) values ('WIKIDATA', '{"entity": "Q535", "revision": 2401234567}') returning id""",
            Long::class.java,
        )!!
        jdbc.update("insert into curation.claim_source (claim, source, position, quote) values ('person-facts:Q535', ?, 0, null)", source)
        jdbc.update(
            """
            insert into curation.decision (claim, decided_by, from_status, to_status, payload_before, payload_after, note)
            values ('person-facts:Q535', 'rule', null, 'CANDIDATE', null, ?::jsonb, 'Proposed')
            """.trimIndent(),
            facts,
        )

        val detail = claims.detail("person-facts:Q535")

        val expected = PersonFacts(
            person = "Q535", label = "Victor Hugo", labelFr = "Victor Hugo", born = 1802, bornPrecision = 11, died = 1885,
            diedPrecision = 11, diedEstimated = false, datesApproximate = false, enwiki = "Victor Hugo", frwiki = "Victor Hugo",
            occupations = listOf(Occupation("Q36180", "writer", "écrivain")),
            places = listOf(
                Place(PlaceRole.BIRTH, "Q37776", "Besançon", "FR"),
                Place(PlaceRole.RESIDENCE, "Q1", "Guernsey", startYear = 1855, endYear = 1870),
            ),
        )
        assertEquals(ClaimType.PERSON_FACTS, detail.claim.type)
        assertEquals(ClaimStatus.CANDIDATE, detail.claim.status)
        assertEquals(Origin.WIKIDATA, detail.claim.origin)
        assertEquals(expected, detail.claim.payload)
        assertEquals(expected, detail.claim.proposal)
        assertEquals(listOf(FlagReason(Flag.CONFLICT, "CHANGED_SINCE_APPROVED")), detail.claim.flags)
        assertEquals(listOf(SourceRef(SourceKind.WIKIDATA, entity = "Q535", revision = 2401234567)), detail.sources)
        assertEquals(listOf(expected), detail.decisions.map { it.payloadAfter })
    }

    @Test
    fun `reads a connection, an event and an anecdote as stored on 2026-10-11`() {
        insertClaim(
            "connection:Q504|Q535:friends", "CONNECTION", "APPROVED", "MANUAL",
            """{"type": "CONNECTION", "people": ["Q535", "Q504"], "kind": "friends", "year": 1878, "text": "Zola wrote to Hugo."}""",
        )
        insertClaim(
            "event:FR:1885:hugo-s-funeral", "EVENT", "NEEDS_WORK", "BOOK",
            """{"type": "EVENT", "region": "FR", "year": 1885, "title": "Hugo's funeral", "description": "Two million people.",
                "participants": [{"person": "Q535", "role": "the deceased"}]}""",
        )
        insertClaim(
            "anecdote:Q535:1851:ee94071e7248", "ANECDOTE", "REJECTED", "CLAUDE",
            """{"type": "ANECDOTE", "person": "Q535", "year": 1851, "text": "Hugo fled to Brussels."}""",
            """[{"flag": "SINGLE_SOURCE", "reason": "ONE_SOURCE"}]""",
        )

        assertEquals(
            Connection(listOf("Q535", "Q504"), "friends", 1878, "Zola wrote to Hugo."),
            claims.detail("connection:Q504|Q535:friends").claim.payload,
        )
        assertEquals(
            Event("FR", 1885, "Hugo's funeral", "Two million people.", listOf(Participant("Q535", "the deceased"))),
            claims.detail("event:FR:1885:hugo-s-funeral").claim.payload,
        )
        val anecdote = claims.detail("anecdote:Q535:1851:ee94071e7248").claim
        assertEquals(Anecdote("Q535", 1851, "Hugo fled to Brussels."), anecdote.payload)
        assertEquals(Origin.CLAUDE, anecdote.origin)
        assertEquals(listOf(FlagReason(Flag.SINGLE_SOURCE, "ONE_SOURCE")), anecdote.flags)
    }

    private fun insertClaim(id: String, type: String, status: String, origin: String, payload: String, flags: String = "[]") {
        jdbc.update(
            "insert into curation.claim (id, type, status, origin, payload, proposal, flags) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)",
            id, type, status, origin, payload, payload, flags,
        )
    }
}
