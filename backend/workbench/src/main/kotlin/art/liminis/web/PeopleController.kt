package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.curation.Bio
import art.liminis.curation.ClaimRules
import art.liminis.curation.Claims
import art.liminis.curation.NewClaim
import art.liminis.people.EditedFacts
import art.liminis.people.People
import art.liminis.people.PeopleReview
import art.liminis.people.PersonConnections
import art.liminis.people.PersonPreviews
import art.liminis.people.ReviewScope
import art.liminis.runs.Runs
import art.liminis.web.WorkbenchSecurity.Companion.currentActor
import art.liminis.workbench.api.PeopleApi
import art.liminis.workbench.api.PlacesApi
import art.liminis.workbench.api.model.BioTextDto
import art.liminis.workbench.api.model.ClaimVersionDto
import art.liminis.workbench.api.model.EvidenceDto
import art.liminis.workbench.api.model.FoundPlaceDto
import art.liminis.workbench.api.model.PeopleToReviewDto
import art.liminis.workbench.api.model.PersonBioDto
import art.liminis.workbench.api.model.PersonConnectionDto
import art.liminis.workbench.api.model.PersonPreviewDto
import art.liminis.workbench.api.model.PersonSummaryDto
import art.liminis.workbench.api.model.ReviewFilterDto
import art.liminis.workbench.api.model.ReviewNeighboursDto
import art.liminis.workbench.api.model.RunDto
import art.liminis.workbench.api.model.RunKindDto

@RestController
class PeopleController(
    private val people: People,
    private val review: PeopleReview,
    private val previews: PersonPreviews,
    private val connections: PersonConnections,
    private val mapper: PeopleApiMapper,
    private val claimMapper: ClaimApiMapper,
    private val claims: Claims,
    private val runs: Runs,
) : PeopleApi {

    override fun findPeople(q: String, limit: Int): List<PersonSummaryDto> {
        val found = people.find(q, limit)
        val held = connections.held(found.map { it.person })
        return found.map { mapper.toDto(it, fetched = it.person !in held) }
    }

    override fun listPersonConnections(qid: String): List<PersonConnectionDto> = connections.of(qid).map {
        PersonConnectionDto(claimMapper.toDto(it.claim), it.claim.version!!, mapper.toDto(it.other))
    }

    override fun getPersonBio(qid: String): PersonBioDto = PersonBioDto(draft = runs.lastBio(qid))

    override fun writePersonBio(qid: String, bioTextDto: BioTextDto): ClaimVersionDto {
        // Waiting for review, as the curator wrote it: approved with the person's facts, in one decision.
        val added = claims.add(NewClaim(Bio(qid, bioTextDto.text.trim()), emptyList()), currentActor().name)
        return ClaimVersionDto(added.claim.id, added.claim.version!!)
    }

    override fun draftPersonBio(qid: String): RunDto {
        // A draft rewrites the bio: not under someone else's hands.
        claims.requireWritable(listOf(ClaimRules.bioId(qid)), currentActor())
        return runs.start(RunKindDto.BIO_DRAFT, null, person = qid)
    }

    override fun getEvidence(qid: String): EvidenceDto = mapper.toDto(people.evidence(qid))

    override fun listPeopleToReview(moment: String?, filter: ReviewFilterDto, q: String?, offset: Int, limit: Int): PeopleToReviewDto =
        mapper.toDto(review.list(ReviewScope(moment, mapper.toModel(filter), q), offset, limit))

    override fun getReviewNeighbours(qid: String, moment: String?, filter: ReviewFilterDto, q: String?): ReviewNeighboursDto =
        mapper.toDto(review.neighbours(qid, ReviewScope(moment, mapper.toModel(filter), q)))

    override fun getPersonPreview(
        qid: String,
        moment: String?,
        born: Int?,
        died: Int?,
        occupation: List<String>?,
        birthplace: String?,
        withoutArticle: Boolean?,
    ): PersonPreviewDto =
        mapper.toDto(previews.preview(qid, moment, EditedFacts(born, died, occupation?.takeIf { it.isNotEmpty() }, birthplace, withoutArticle == true)))
}

@RestController
class PlacesController(private val people: People, private val mapper: PeopleApiMapper) : PlacesApi {

    override fun findPlaces(q: String, limit: Int): List<FoundPlaceDto> = people.findPlaces(q, limit).map(mapper::toDto)
}
