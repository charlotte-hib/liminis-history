package dev.wholivedwhen.curation

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.Named
import org.mapstruct.ReportingPolicy
import dev.wholivedwhen.dataset.DatasetOccupation
import dev.wholivedwhen.dataset.DatasetPerson
import dev.wholivedwhen.dataset.DatasetPlace
import dev.wholivedwhen.dataset.PlaceProperty

/**
 * A person of the dataset to the facts proposed for review. MapStruct copies the fields of the same name; every target
 * field is mapped or ignored on purpose (`unmappedTargetPolicy`). The person's flags are not facts: the proposal
 * carries them beside the payload.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class PersonFactsMapper {

    @Mapping(target = "person", source = "qid")
    abstract fun facts(person: DatasetPerson): PersonFacts

    /** A deleted occupation keeps its Q-id as its label. */
    @Mapping(target = "label", source = "label", defaultExpression = "java(occupation.getOccupation())")
    abstract fun occupation(occupation: DatasetOccupation): Occupation

    /** A deleted place keeps its Q-id as its label. */
    @Mapping(target = "role", source = "property", qualifiedByName = ["role"])
    @Mapping(target = "label", source = "label", defaultExpression = "java(place.getPlace())")
    abstract fun place(place: DatasetPlace): Place

    @Named("role")
    fun role(property: String): PlaceRole = when (PlaceProperty.of(property)) {
        PlaceProperty.BIRTH -> PlaceRole.BIRTH
        PlaceProperty.DEATH -> PlaceRole.DEATH
        PlaceProperty.WORK -> PlaceRole.WORK
        PlaceProperty.RESIDENCE -> PlaceRole.RESIDENCE
    }
}
