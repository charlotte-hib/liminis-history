package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import org.mapstruct.ReportingPolicy
import org.mapstruct.SubclassExhaustiveStrategy
import org.mapstruct.SubclassMapping
import art.liminis.curation.Anecdote
import art.liminis.curation.Claim
import art.liminis.curation.ClaimDecision
import art.liminis.curation.ClaimDetail
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.ClaimVersion
import art.liminis.curation.Connection
import art.liminis.curation.Decision
import art.liminis.curation.Event
import art.liminis.curation.Flag
import art.liminis.curation.Holds
import art.liminis.curation.NewClaim
import art.liminis.curation.NewDecision
import art.liminis.curation.OccupationDomain
import art.liminis.curation.Payload
import art.liminis.curation.PersonFacts
import art.liminis.curation.Queue
import art.liminis.curation.MomentPage
import art.liminis.curation.Painting
import art.liminis.curation.Story
import art.liminis.curation.Translation
import art.liminis.workbench.api.model.AnecdoteDto
import art.liminis.workbench.api.model.ClaimDecisionDto
import art.liminis.workbench.api.model.ClaimDetailDto
import art.liminis.workbench.api.model.ClaimDto
import art.liminis.workbench.api.model.ClaimStatusDto
import art.liminis.workbench.api.model.ClaimTypeDto
import art.liminis.workbench.api.model.ClaimVersionDto
import art.liminis.workbench.api.model.ConnectionDto
import art.liminis.workbench.api.model.DecisionDto
import art.liminis.workbench.api.model.EventDto
import art.liminis.workbench.api.model.FlagDto
import art.liminis.workbench.api.model.MomentPageDto
import art.liminis.workbench.api.model.PaintingDto
import art.liminis.workbench.api.model.NewClaimDto
import art.liminis.workbench.api.model.NewDecisionDto
import art.liminis.workbench.api.model.OccupationDomainDto
import art.liminis.workbench.api.model.PayloadDto
import art.liminis.workbench.api.model.PersonFactsDto
import art.liminis.workbench.api.model.HolderCountDto
import art.liminis.workbench.api.model.HoldsDto
import art.liminis.workbench.api.model.QueueDto
import art.liminis.workbench.api.model.StoryDto
import art.liminis.workbench.api.model.TranslationDto
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The curation model to the API's models, generated from api/workbench.yaml (package
 * art.liminis.workbench.api.model), and back. Fields and enum constants map by name; every target field is
 * mapped or ignored on purpose (`unmappedTargetPolicy`).
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, subclassExhaustiveStrategy = SubclassExhaustiveStrategy.RUNTIME_EXCEPTION)
abstract class ClaimApiMapper {

    abstract fun toDto(queue: Queue): QueueDto

    @Mapping(target = "heldSince", source = "heldSince", qualifiedByName = ["utc"])
    abstract fun toDto(claim: Claim): ClaimDto

    @Mapping(target = "id", source = "claim.id")
    @Mapping(target = "seq", source = "claim.seq")
    @Mapping(target = "type", source = "claim.type")
    @Mapping(target = "status", source = "claim.status")
    @Mapping(target = "origin", source = "claim.origin")
    @Mapping(target = "flags", source = "claim.flags")
    @Mapping(target = "payload", source = "claim.payload")
    @Mapping(target = "proposal", source = "claim.proposal")
    @Mapping(target = "heldBy", source = "claim.heldBy")
    @Mapping(target = "heldSince", source = "claim.heldSince", qualifiedByName = ["utc"])
    abstract fun toDto(detail: ClaimDetail): ClaimDetailDto

    /** Who holds the claims left as they were, by username. */
    fun toDto(holds: Holds): HoldsDto =
        HoldsDto(holds.changed, holds.heldByOthers.toSortedMap().map { (username, claims) -> HolderCountDto(username, claims) })

    @Mapping(target = "decidedAt", source = "decidedAt", qualifiedByName = ["utc"])
    @Mapping(target = "edited", source = ".", qualifiedByName = ["edited"])
    abstract fun toDto(decision: Decision): DecisionDto

    @SubclassMapping(source = PersonFacts::class, target = PersonFactsDto::class)
    @SubclassMapping(source = Connection::class, target = ConnectionDto::class)
    @SubclassMapping(source = Event::class, target = EventDto::class)
    @SubclassMapping(source = Anecdote::class, target = AnecdoteDto::class)
    @SubclassMapping(source = OccupationDomain::class, target = OccupationDomainDto::class)
    @SubclassMapping(source = Story::class, target = StoryDto::class)
    @SubclassMapping(source = MomentPage::class, target = MomentPageDto::class)
    @SubclassMapping(source = Translation::class, target = TranslationDto::class)
    abstract fun toDto(payload: Payload): PayloadDto

    @Mapping(target = "type", constant = "PERSON_FACTS")
    abstract fun toDto(facts: PersonFacts): PersonFactsDto

    @Mapping(target = "type", constant = "CONNECTION")
    abstract fun toDto(connection: Connection): ConnectionDto

    @Mapping(target = "type", constant = "EVENT")
    abstract fun toDto(event: Event): EventDto

    @Mapping(target = "type", constant = "ANECDOTE")
    abstract fun toDto(anecdote: Anecdote): AnecdoteDto

    @Mapping(target = "type", constant = "OCCUPATION_DOMAIN")
    abstract fun toDto(occupation: OccupationDomain): OccupationDomainDto

    @Mapping(target = "type", constant = "STORY")
    abstract fun toDto(story: Story): StoryDto

    @Mapping(target = "type", constant = "MOMENT_PAGE")
    abstract fun toDto(page: MomentPage): MomentPageDto

    @Mapping(target = "type", constant = "TRANSLATION")
    abstract fun toDto(translation: Translation): TranslationDto

    abstract fun toModel(claim: NewClaimDto): NewClaim

    @Mapping(target = "verdict", source = "action")
    abstract fun toModel(decision: NewDecisionDto): NewDecision

    abstract fun toModel(decision: ClaimDecisionDto): ClaimDecision

    abstract fun toModel(version: ClaimVersionDto): ClaimVersion

    @SubclassMapping(source = PersonFactsDto::class, target = PersonFacts::class)
    @SubclassMapping(source = ConnectionDto::class, target = Connection::class)
    @SubclassMapping(source = EventDto::class, target = Event::class)
    @SubclassMapping(source = AnecdoteDto::class, target = Anecdote::class)
    @SubclassMapping(source = OccupationDomainDto::class, target = OccupationDomain::class)
    @SubclassMapping(source = StoryDto::class, target = Story::class)
    @SubclassMapping(source = MomentPageDto::class, target = MomentPage::class)
    @SubclassMapping(source = TranslationDto::class, target = Translation::class)
    abstract fun toModel(payload: PayloadDto): Payload

    abstract fun toModel(painting: PaintingDto): Painting

    abstract fun toModel(type: ClaimTypeDto): ClaimType

    abstract fun toModel(status: ClaimStatusDto): ClaimStatus

    abstract fun toModel(flag: FlagDto): Flag

    @Named("utc")
    fun utc(time: OffsetDateTime?): OffsetDateTime? = time?.withOffsetSameInstant(ZoneOffset.UTC)

    /** Whether the decision changed the payload. */
    @Named("edited")
    fun edited(decision: Decision): Boolean = decision.payloadBefore != null && decision.payloadBefore != decision.payloadAfter
}
