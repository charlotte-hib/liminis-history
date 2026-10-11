package dev.wholivedwhen.web

import org.springframework.web.bind.annotation.RestController
import dev.wholivedwhen.curation.ClaimStatus
import dev.wholivedwhen.curation.ClaimType
import dev.wholivedwhen.curation.Claims
import dev.wholivedwhen.curation.Flag
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
class ClaimController(private val claims: Claims, private val mapper: ClaimApiMapper) : ClaimsApi {

    override fun listQueues(): List<QueueDto> = claims.queues().map(mapper::toDto)

    override fun listClaims(type: ClaimTypeDto?, status: ClaimStatusDto?, flag: FlagDto?, after: Long?, limit: Int): List<ClaimDto> =
        claims.list(type?.let { ClaimType.valueOf(it.name) }, status?.let { ClaimStatus.valueOf(it.name) }, flag?.let { Flag.valueOf(it.name) }, after ?: 0, limit)
            .map(mapper::toDto)

    override fun getClaim(id: String): ClaimDetailDto = mapper.toDto(claims.detail(id))

    override fun addClaim(newClaimDto: NewClaimDto): ClaimDetailDto = mapper.toDto(claims.add(mapper.toModel(newClaimDto), currentActor()))

    override fun decide(id: String, newDecisionDto: NewDecisionDto): ClaimDetailDto =
        mapper.toDto(claims.decide(id, mapper.toModel(newDecisionDto), currentActor()))

    override fun undo(id: String): ClaimDetailDto = mapper.toDto(claims.undo(id, currentActor()))
}
