package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy
import org.mapstruct.ValueMapping
import art.liminis.dataset.ArticleRow
import art.liminis.dataset.PersonSummary
import art.liminis.dataset.Rank
import art.liminis.people.Evidence
import art.liminis.people.EvidenceStatement
import art.liminis.workbench.api.model.ArticleDto
import art.liminis.workbench.api.model.EvidenceDto
import art.liminis.workbench.api.model.PersonSummaryDto
import art.liminis.workbench.api.model.StatementDto

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
