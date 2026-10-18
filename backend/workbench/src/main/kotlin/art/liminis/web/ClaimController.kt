package art.liminis.web

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.RestController
import art.liminis.curation.ClaimDetail
import art.liminis.curation.Claims
import art.liminis.curation.MomentPage
import art.liminis.curation.PersonFactsProposals
import art.liminis.curation.StoryLine
import art.liminis.curation.StoryWorld
import art.liminis.moments.Moments
import art.liminis.support.StaleException
import art.liminis.web.WorkbenchSecurity.Companion.currentActor
import art.liminis.workbench.api.ClaimsApi
import art.liminis.workbench.api.model.ClaimDetailDto
import art.liminis.workbench.api.model.ClaimDto
import art.liminis.workbench.api.model.ClaimStatusDto
import art.liminis.workbench.api.model.ClaimTypeDto
import art.liminis.workbench.api.model.FlagDto
import art.liminis.workbench.api.model.HoldsDto
import art.liminis.workbench.api.model.NewHolderDto
import art.liminis.workbench.api.model.MomentPageTextDto
import art.liminis.workbench.api.model.NewClaimDto
import art.liminis.workbench.api.model.NewDecisionDto
import art.liminis.workbench.api.model.QueueDto

/**
 * The claims API. A claim's version travels in HTTP's own headers: its `ETag` on every answer about one claim, and the
 * `If-Match` a decision or an undo must carry, so that nobody decides on a claim that changed since they read it.
 */
@RestController
class ClaimController(
    private val claims: Claims,
    private val moments: Moments,
    private val personFacts: PersonFactsProposals,
    private val mapper: ClaimApiMapper,
    private val response: HttpServletResponse,
) : ClaimsApi {

    override fun listQueues(): List<QueueDto> = claims.queues().map(mapper::toDto)

    override fun listClaims(type: ClaimTypeDto?, status: ClaimStatusDto?, flag: FlagDto?, moment: String?, after: Long?, limit: Int): List<ClaimDto> {
        val ids = moment?.let(moments::claimIds)
        return claims.list(type?.let(mapper::toModel), status?.let(mapper::toModel), flag?.let(mapper::toModel), ids, after ?: 0, limit)
            .map(mapper::toDto)
    }

    override fun getClaim(id: String): ClaimDetailDto = answer(claims.detail(id))

    override fun proposeFacts(qid: String): ClaimDetailDto = answer(claims.detail(personFacts.propose(qid)))

    override fun addClaim(newClaimDto: NewClaimDto): ClaimDetailDto = answer(claims.add(mapper.toModel(newClaimDto), currentActor().name))

    override fun writeMomentPage(id: String, momentPageTextDto: MomentPageTextDto): ClaimDetailDto {
        // As curate sends a decision's lines: trimmed, so a stray space or line break never reaches the published file.
        fun line(text: String) = StoryLine(text.trim())
        val page = with(momentPageTextDto) {
            MomentPage(
                id, line(hook), StoryWorld(line(world.governs), line(world.everyday), line(world.arts), line(world.meanwhile)),
                art?.let(mapper::toModel)?.let { it.copy(credit = it.credit.trim()) },
            )
        }
        return answer(moments.writePage(page, momentPageTextDto.note, currentActor().name))
    }

    override fun decide(id: String, newDecisionDto: NewDecisionDto, ifMatch: String?): ClaimDetailDto =
        answer(claims.decide(id, version(id, ifMatch), mapper.toModel(newDecisionDto), currentActor()))

    override fun undo(id: String, ifMatch: String?): ClaimDetailDto = answer(claims.undo(id, version(id, ifMatch), currentActor()))

    override fun takeClaim(id: String, newHolderDto: NewHolderDto?): ClaimDetailDto {
        val actor = currentActor()
        val holder = newHolderDto?.username ?: actor.name
        // Handing a claim to someone else is the admin's alone.
        if (holder != actor.name && !actor.admin) throw AccessDeniedException("Only the admin hands a claim to someone else")
        return answer(claims.take(id, actor, holder))
    }

    override fun releaseClaim(id: String): ClaimDetailDto = answer(claims.release(id, currentActor()))

    override fun releaseHolder(username: String): HoldsDto = mapper.toDto(claims.releaseAllHeldBy(username))

    /** The claim as the API shows it, with its version as the ETag. */
    private fun answer(detail: ClaimDetail): ClaimDetailDto {
        response.setHeader(HttpHeaders.ETAG, "\"${detail.claim.version}\"")
        return mapper.toDto(detail)
    }

    /** The version [ifMatch] names: none is a 428, and anything but a version this API gave can match no claim. */
    private fun version(id: String, ifMatch: String?): Long {
        if (ifMatch == null) {
            throw ErrorResponseException(
                HttpStatus.PRECONDITION_REQUIRED,
                ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, "Send the claim's ETag in If-Match"),
                null,
            )
        }
        return ifMatch.trim().removeSurrounding("\"").toLongOrNull()
            ?: throw StaleException("$id has no version $ifMatch: read it again")
    }
}
