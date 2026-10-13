package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.ReportingPolicy
import org.springframework.web.bind.annotation.RestController
import art.liminis.occupations.OccupationEvidence
import art.liminis.occupations.Occupations
import art.liminis.workbench.api.OccupationsApi
import art.liminis.workbench.api.model.OccupationEvidenceDto

@RestController
class OccupationsController(private val occupations: Occupations, private val mapper: OccupationsApiMapper) : OccupationsApi {

    override fun getOccupation(qid: String): OccupationEvidenceDto = mapper.toDto(occupations.evidence(qid))
}

/** Occupations to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, uses = [PeopleApiMapper::class])
interface OccupationsApiMapper {
    fun toDto(evidence: OccupationEvidence): OccupationEvidenceDto
}
