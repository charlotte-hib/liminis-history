package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.people.People
import art.liminis.workbench.api.PeopleApi
import art.liminis.workbench.api.model.EvidenceDto
import art.liminis.workbench.api.model.PersonSummaryDto

@RestController
class PeopleController(private val people: People, private val mapper: PeopleApiMapper) : PeopleApi {

    override fun findPeople(q: String, limit: Int): List<PersonSummaryDto> = people.find(q, limit).map(mapper::toDto)

    override fun getEvidence(qid: String): EvidenceDto = mapper.toDto(people.evidence(qid))
}
