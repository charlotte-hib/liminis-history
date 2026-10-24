package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy
import org.mapstruct.ValueMapping
import art.liminis.curation.ClaimStatus
import art.liminis.curation.FlagReason
import art.liminis.curation.SourceRef
import art.liminis.dataset.ArticleRow
import art.liminis.dataset.FoundPlace
import art.liminis.dataset.PersonSummary
import art.liminis.dataset.Rank
import art.liminis.people.Evidence
import art.liminis.people.EvidenceStatement
import art.liminis.people.PeopleToReview
import art.liminis.people.PersonPreview
import art.liminis.people.PersonToReview
import art.liminis.people.PreviewLifeLine
import art.liminis.people.ReviewFilter
import art.liminis.people.ReviewNeighbours
import art.liminis.workbench.api.model.ArticleDto
import art.liminis.workbench.api.model.ClaimStatusDto
import art.liminis.workbench.api.model.EvidenceDto
import art.liminis.workbench.api.model.FlagReasonDto
import art.liminis.workbench.api.model.FoundPlaceDto
import art.liminis.workbench.api.model.PeopleToReviewDto
import art.liminis.workbench.api.model.PersonPreviewDto
import art.liminis.workbench.api.model.PersonSummaryDto
import art.liminis.workbench.api.model.PersonToReviewDto
import art.liminis.workbench.api.model.PreviewLifeLineDto
import art.liminis.workbench.api.model.ReviewCountsDto
import art.liminis.workbench.api.model.ReviewFilterDto
import art.liminis.workbench.api.model.ReviewNeighboursDto
import art.liminis.workbench.api.model.SourceDto
import art.liminis.workbench.api.model.StatementDto

/** The fetched people to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class PeopleApiMapper {

    abstract fun toDto(person: PersonSummary): PersonSummaryDto

    abstract fun toDto(place: FoundPlace): FoundPlaceDto

    abstract fun toDto(evidence: Evidence): EvidenceDto

    abstract fun toDto(statement: EvidenceStatement): StatementDto

    abstract fun toDto(article: ArticleRow): ArticleDto

    /** Deprecated statements are left out of the evidence. */
    @ValueMapping(source = "DEPRECATED", target = MappingConstants.THROW_EXCEPTION)
    abstract fun toDto(rank: Rank): StatementDto.Rank

    fun toDto(people: PeopleToReview): PeopleToReviewDto = PeopleToReviewDto(
        total = people.total,
        counts = people.counts.let {
            ReviewCountsDto(
                everyone = it.getValue(ReviewFilter.EVERYONE),
                waiting = it.getValue(ReviewFilter.WAITING),
                conflicts = it.getValue(ReviewFilter.CONFLICTS),
                poorlyDocumented = it.getValue(ReviewFilter.POORLY_DOCUMENTED),
                keptForLater = it.getValue(ReviewFilter.KEPT_FOR_LATER),
                decided = it.getValue(ReviewFilter.DECIDED),
                notProposed = it.getValue(ReviewFilter.NOT_PROPOSED),
            )
        },
        people = people.people.map(::toDto),
    )

    abstract fun toDto(person: PersonToReview): PersonToReviewDto

    abstract fun toDto(flag: FlagReason): FlagReasonDto

    abstract fun toDto(status: ClaimStatus): ClaimStatusDto

    abstract fun toDto(neighbours: ReviewNeighbours): ReviewNeighboursDto

    abstract fun toDto(preview: PersonPreview): PersonPreviewDto

    abstract fun toDto(line: PreviewLifeLine): PreviewLifeLineDto

    abstract fun toDto(source: SourceRef): SourceDto

    abstract fun toModel(filter: ReviewFilterDto): ReviewFilter
}
