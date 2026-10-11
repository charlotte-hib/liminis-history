package dev.wholivedwhen.web

import org.springframework.web.bind.annotation.RestController
import dev.wholivedwhen.curation.Claims
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

@RestController
class ClaimController(private val claims: Claims) : ClaimsApi {

    override fun listQueues(): List<QueueDto> = claims.queues()

    override fun listClaims(type: ClaimTypeDto?, status: ClaimStatusDto?, flag: FlagDto?, after: Long?, limit: Int): List<ClaimDto> =
        claims.list(type, status, flag, after ?: 0, limit)

    override fun getClaim(id: String): ClaimDetailDto = claims.detail(id)

    override fun addClaim(newClaimDto: NewClaimDto): ClaimDetailDto = claims.add(newClaimDto, currentActor())

    override fun decide(id: String, newDecisionDto: NewDecisionDto): ClaimDetailDto = claims.decide(id, newDecisionDto, currentActor())

    override fun undo(id: String): ClaimDetailDto = claims.undo(id, currentActor())
}
