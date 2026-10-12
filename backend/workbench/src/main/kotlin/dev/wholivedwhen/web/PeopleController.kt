package dev.wholivedwhen.web

import org.springframework.web.bind.annotation.RestController
import dev.wholivedwhen.people.People
import dev.wholivedwhen.workbench.api.PeopleApi
import dev.wholivedwhen.workbench.api.model.EvidenceDto
import dev.wholivedwhen.workbench.api.model.PersonSummaryDto

@RestController
class PeopleController(private val people: People, private val mapper: PeopleApiMapper) : PeopleApi {

    override fun findPeople(q: String, limit: Int): List<PersonSummaryDto> = people.find(q, limit).map(mapper::toDto)

    override fun getEvidence(qid: String): EvidenceDto = mapper.toDto(people.evidence(qid))
}
