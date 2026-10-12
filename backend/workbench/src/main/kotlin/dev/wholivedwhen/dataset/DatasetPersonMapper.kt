package dev.wholivedwhen.dataset

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.ReportingPolicy

/**
 * A person's row to the person as proposals read them: the same facts, with the labels of their occupations and places
 * and all their flags, worked out by [DatasetStore.people]. Every target field is mapped or ignored on purpose.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class DatasetPersonMapper {

    @Mapping(target = "occupations", source = "labelledOccupations")
    @Mapping(target = "places", source = "labelledPlaces")
    @Mapping(target = "flags", source = "allFlags")
    abstract fun person(
        row: PersonRow,
        labelledOccupations: List<DatasetOccupation>,
        labelledPlaces: List<DatasetPlace>,
        allFlags: List<Pair<String, String>>,
    ): DatasetPerson
}
