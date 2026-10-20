package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.people.EditedFacts
import art.liminis.people.People
import art.liminis.people.PeopleReview
import art.liminis.people.PersonPreviews
import art.liminis.people.ReviewScope
import art.liminis.workbench.api.PeopleApi
import art.liminis.workbench.api.model.EvidenceDto
import art.liminis.workbench.api.model.PeopleToReviewDto
import art.liminis.workbench.api.model.PersonPreviewDto
import art.liminis.workbench.api.model.PersonSummaryDto
import art.liminis.workbench.api.model.ReviewFilterDto
import art.liminis.workbench.api.model.ReviewNeighboursDto

@RestController
class PeopleController(
    private val people: People,
    private val review: PeopleReview,
    private val previews: PersonPreviews,
    private val mapper: PeopleApiMapper,
) : PeopleApi {

    override fun findPeople(q: String, limit: Int): List<PersonSummaryDto> = people.find(q, limit).map(mapper::toDto)

    override fun getEvidence(qid: String): EvidenceDto = mapper.toDto(people.evidence(qid))

    override fun listPeopleToReview(moment: String?, filter: ReviewFilterDto, q: String?, offset: Int, limit: Int): PeopleToReviewDto =
        mapper.toDto(review.list(ReviewScope(moment, mapper.toModel(filter), q), offset, limit))

    override fun getReviewNeighbours(qid: String, moment: String?, filter: ReviewFilterDto, q: String?): ReviewNeighboursDto =
        mapper.toDto(review.neighbours(qid, ReviewScope(moment, mapper.toModel(filter), q)))

    override fun getPersonPreview(qid: String, moment: String?, born: Int?, died: Int?, occupation: List<String>?): PersonPreviewDto =
        mapper.toDto(previews.preview(qid, moment, EditedFacts(born, died, occupation?.takeIf { it.isNotEmpty() })))
}
