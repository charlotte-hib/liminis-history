package dev.wholivedwhen.web

import org.mapstruct.Mapper
import org.mapstruct.ReportingPolicy
import org.springframework.web.bind.annotation.RestController
import dev.wholivedwhen.occupations.OccupationEvidence
import dev.wholivedwhen.occupations.Occupations
import dev.wholivedwhen.workbench.api.OccupationsApi
import dev.wholivedwhen.workbench.api.model.OccupationEvidenceDto

@RestController
class OccupationsController(private val occupations: Occupations, private val mapper: OccupationsApiMapper) : OccupationsApi {

    override fun getOccupation(qid: String): OccupationEvidenceDto = mapper.toDto(occupations.evidence(qid))
}

/** Occupations to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, uses = [PeopleApiMapper::class])
interface OccupationsApiMapper {
    fun toDto(evidence: OccupationEvidence): OccupationEvidenceDto
}
