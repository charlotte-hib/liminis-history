package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import org.mapstruct.ReportingPolicy
import org.mapstruct.SubclassExhaustiveStrategy
import org.mapstruct.SubclassMapping
import art.liminis.curation.Anecdote
import art.liminis.curation.Claim
import art.liminis.curation.ClaimDetail
import art.liminis.curation.ClaimStatus
import art.liminis.curation.ClaimType
import art.liminis.curation.Connection
import art.liminis.curation.Decision
import art.liminis.curation.Event
import art.liminis.curation.Flag
import art.liminis.curation.NewClaim
import art.liminis.curation.NewDecision
import art.liminis.curation.OccupationDomain
import art.liminis.curation.Payload
import art.liminis.curation.PersonFacts
import art.liminis.curation.Queue
import art.liminis.curation.Story
import art.liminis.workbench.api.model.AnecdoteDto
import art.liminis.workbench.api.model.ClaimDetailDto
import art.liminis.workbench.api.model.ClaimDto
import art.liminis.workbench.api.model.ClaimStatusDto
import art.liminis.workbench.api.model.ClaimTypeDto
import art.liminis.workbench.api.model.ConnectionDto
import art.liminis.workbench.api.model.DecisionDto
import art.liminis.workbench.api.model.EventDto
import art.liminis.workbench.api.model.FlagDto
import art.liminis.workbench.api.model.NewClaimDto
import art.liminis.workbench.api.model.NewDecisionDto
import art.liminis.workbench.api.model.OccupationDomainDto
import art.liminis.workbench.api.model.PayloadDto
import art.liminis.workbench.api.model.PersonFactsDto
import art.liminis.workbench.api.model.QueueDto
import art.liminis.workbench.api.model.StoryDto
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

    abstract fun toDto(claim: Claim): ClaimDto

    @Mapping(target = "id", source = "claim.id")
    @Mapping(target = "seq", source = "claim.seq")
    @Mapping(target = "type", source = "claim.type")
    @Mapping(target = "status", source = "claim.status")
    @Mapping(target = "origin", source = "claim.origin")
    @Mapping(target = "flags", source = "claim.flags")
    @Mapping(target = "payload", source = "claim.payload")
    @Mapping(target = "proposal", source = "claim.proposal")
    abstract fun toDto(detail: ClaimDetail): ClaimDetailDto

    @Mapping(target = "decidedAt", source = "decidedAt", qualifiedByName = ["utc"])
    @Mapping(target = "edited", source = ".", qualifiedByName = ["edited"])
    abstract fun toDto(decision: Decision): DecisionDto

    @SubclassMapping(source = PersonFacts::class, target = PersonFactsDto::class)
    @SubclassMapping(source = Connection::class, target = ConnectionDto::class)
    @SubclassMapping(source = Event::class, target = EventDto::class)
    @SubclassMapping(source = Anecdote::class, target = AnecdoteDto::class)
    @SubclassMapping(source = OccupationDomain::class, target = OccupationDomainDto::class)
    @SubclassMapping(source = Story::class, target = StoryDto::class)
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

    abstract fun toModel(claim: NewClaimDto): NewClaim

    @Mapping(target = "verdict", source = "action")
    abstract fun toModel(decision: NewDecisionDto): NewDecision

    @SubclassMapping(source = PersonFactsDto::class, target = PersonFacts::class)
    @SubclassMapping(source = ConnectionDto::class, target = Connection::class)
    @SubclassMapping(source = EventDto::class, target = Event::class)
    @SubclassMapping(source = AnecdoteDto::class, target = Anecdote::class)
    @SubclassMapping(source = OccupationDomainDto::class, target = OccupationDomain::class)
    @SubclassMapping(source = StoryDto::class, target = Story::class)
    abstract fun toModel(payload: PayloadDto): Payload

    abstract fun toModel(type: ClaimTypeDto): ClaimType

    abstract fun toModel(status: ClaimStatusDto): ClaimStatus

    abstract fun toModel(flag: FlagDto): Flag

    @Named("utc")
    fun utc(decidedAt: OffsetDateTime?): OffsetDateTime? = decidedAt?.withOffsetSameInstant(ZoneOffset.UTC)

    /** Whether the decision changed the payload. */
    @Named("edited")
    fun edited(decision: Decision): Boolean = decision.payloadBefore != null && decision.payloadBefore != decision.payloadAfter
}
