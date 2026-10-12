package dev.wholivedwhen.web

import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy
import org.mapstruct.ValueMapping
import dev.wholivedwhen.dataset.ArticleRow
import dev.wholivedwhen.dataset.PersonSummary
import dev.wholivedwhen.dataset.Rank
import dev.wholivedwhen.people.Evidence
import dev.wholivedwhen.people.EvidenceStatement
import dev.wholivedwhen.workbench.api.model.ArticleDto
import dev.wholivedwhen.workbench.api.model.EvidenceDto
import dev.wholivedwhen.workbench.api.model.PersonSummaryDto
import dev.wholivedwhen.workbench.api.model.StatementDto

/** The fetched people to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class PeopleApiMapper {

    abstract fun toDto(person: PersonSummary): PersonSummaryDto

    abstract fun toDto(evidence: Evidence): EvidenceDto

    abstract fun toDto(statement: EvidenceStatement): StatementDto

    abstract fun toDto(article: ArticleRow): ArticleDto

    /** Deprecated statements are left out of the evidence. */
    @ValueMapping(source = "DEPRECATED", target = MappingConstants.THROW_EXCEPTION)
    abstract fun toDto(rank: Rank): StatementDto.Rank
}
