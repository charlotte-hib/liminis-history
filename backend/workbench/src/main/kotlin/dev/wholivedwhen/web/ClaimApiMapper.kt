package dev.wholivedwhen.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import org.mapstruct.ReportingPolicy
import org.mapstruct.SubclassExhaustiveStrategy
import org.mapstruct.SubclassMapping
import dev.wholivedwhen.curation.Anecdote
import dev.wholivedwhen.curation.Claim
import dev.wholivedwhen.curation.ClaimDetail
import dev.wholivedwhen.curation.ClaimStatus
import dev.wholivedwhen.curation.ClaimType
import dev.wholivedwhen.curation.Connection
import dev.wholivedwhen.curation.Decision
import dev.wholivedwhen.curation.Event
import dev.wholivedwhen.curation.Flag
import dev.wholivedwhen.curation.NewClaim
import dev.wholivedwhen.curation.NewDecision
import dev.wholivedwhen.curation.OccupationDomain
import dev.wholivedwhen.curation.Payload
import dev.wholivedwhen.curation.PersonFacts
import dev.wholivedwhen.curation.Queue
import dev.wholivedwhen.workbench.api.model.AnecdoteDto
import dev.wholivedwhen.workbench.api.model.ClaimDetailDto
import dev.wholivedwhen.workbench.api.model.ClaimDto
import dev.wholivedwhen.workbench.api.model.ClaimStatusDto
import dev.wholivedwhen.workbench.api.model.ClaimTypeDto
import dev.wholivedwhen.workbench.api.model.ConnectionDto
import dev.wholivedwhen.workbench.api.model.DecisionDto
import dev.wholivedwhen.workbench.api.model.EventDto
import dev.wholivedwhen.workbench.api.model.FlagDto
import dev.wholivedwhen.workbench.api.model.NewClaimDto
import dev.wholivedwhen.workbench.api.model.NewDecisionDto
import dev.wholivedwhen.workbench.api.model.OccupationDomainDto
import dev.wholivedwhen.workbench.api.model.PayloadDto
import dev.wholivedwhen.workbench.api.model.PersonFactsDto
import dev.wholivedwhen.workbench.api.model.QueueDto
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The curation model to the API's models, generated from api/workbench.yaml (package
 * dev.wholivedwhen.workbench.api.model), and back. Fields and enum constants map by name; every target field is
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

    abstract fun toModel(claim: NewClaimDto): NewClaim

    @Mapping(target = "verdict", source = "action")
    abstract fun toModel(decision: NewDecisionDto): NewDecision

    @SubclassMapping(source = PersonFactsDto::class, target = PersonFacts::class)
    @SubclassMapping(source = ConnectionDto::class, target = Connection::class)
    @SubclassMapping(source = EventDto::class, target = Event::class)
    @SubclassMapping(source = AnecdoteDto::class, target = Anecdote::class)
    @SubclassMapping(source = OccupationDomainDto::class, target = OccupationDomain::class)
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
