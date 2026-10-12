package dev.wholivedwhen.web

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.RestController
import dev.wholivedwhen.curation.ClaimDetail
import dev.wholivedwhen.curation.Claims
import dev.wholivedwhen.support.StaleException
import dev.wholivedwhen.web.WorkbenchSecurity.Companion.currentActor
import dev.wholivedwhen.workbench.api.ClaimsApi
import dev.wholivedwhen.workbench.api.model.ClaimDetailDto
import dev.wholivedwhen.workbench.api.model.ClaimDto
import dev.wholivedwhen.workbench.api.model.ClaimStatusDto
import dev.wholivedwhen.workbench.api.model.ClaimTypeDto
import dev.wholivedwhen.workbench.api.model.FlagDto
import dev.wholivedwhen.workbench.api.model.NewClaimDto
import dev.wholivedwhen.workbench.api.model.NewDecisionDto
import dev.wholivedwhen.workbench.api.model.QueueDto

/**
 * The claims API. A claim's version travels in HTTP's own headers: its `ETag` on every answer about one claim, and the
 * `If-Match` a decision or an undo must carry, so that nobody decides on a claim that changed since they read it.
 */
@RestController
class ClaimController(
    private val claims: Claims,
    private val mapper: ClaimApiMapper,
    private val response: HttpServletResponse,
) : ClaimsApi {

    override fun listQueues(): List<QueueDto> = claims.queues().map(mapper::toDto)

    override fun listClaims(type: ClaimTypeDto?, status: ClaimStatusDto?, flag: FlagDto?, after: Long?, limit: Int): List<ClaimDto> =
        claims.list(type?.let(mapper::toModel), status?.let(mapper::toModel), flag?.let(mapper::toModel), after ?: 0, limit)
            .map(mapper::toDto)

    override fun getClaim(id: String): ClaimDetailDto = answer(claims.detail(id))

    override fun addClaim(newClaimDto: NewClaimDto): ClaimDetailDto = answer(claims.add(mapper.toModel(newClaimDto), currentActor()))

    override fun decide(id: String, newDecisionDto: NewDecisionDto, ifMatch: String?): ClaimDetailDto =
        answer(claims.decide(id, version(id, ifMatch), mapper.toModel(newDecisionDto), currentActor()))

    override fun undo(id: String, ifMatch: String?): ClaimDetailDto = answer(claims.undo(id, version(id, ifMatch), currentActor()))

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
