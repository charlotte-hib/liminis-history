package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.ReportingPolicy
import org.springframework.web.bind.annotation.RestController
import art.liminis.curation.ClaimStatus
import art.liminis.curation.StoryCard
import art.liminis.domain.WorldAround
import art.liminis.moments.MomentOverview
import art.liminis.moments.MomentPeople
import art.liminis.moments.MomentPerson
import art.liminis.moments.MomentStory
import art.liminis.moments.Moments
import art.liminis.runs.Runs
import art.liminis.workbench.api.MomentsApi
import art.liminis.workbench.api.model.ClaimStatusDto
import art.liminis.workbench.api.model.MomentDetailDto
import art.liminis.workbench.api.model.MomentPeopleDto
import art.liminis.workbench.api.model.MomentPersonDto
import art.liminis.workbench.api.model.MomentSummaryDto
import art.liminis.workbench.api.model.RunDto
import art.liminis.workbench.api.model.RunKindDto
import art.liminis.workbench.api.model.StoryCardDto
import art.liminis.workbench.api.model.WorldDto

@RestController
class MomentController(private val moments: Moments, private val runs: Runs, private val mapper: MomentApiMapper) : MomentsApi {

    override fun listMoments(): List<MomentSummaryDto> = moments.all().map(mapper::toDto)

    override fun getMoment(id: String): MomentDetailDto = mapper.toDto(moments.story(id)).copy(draft = runs.lastDraft(id))

    override fun draftStory(id: String): RunDto {
        moments.requireExists(id)
        return runs.start(RunKindDto.STORY_DRAFT, id)
    }

    override fun listMomentPeople(id: String, offset: Int, limit: Int): MomentPeopleDto = mapper.toDto(moments.people(id, offset, limit))
}

/** The workspace's moments to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface MomentApiMapper {

    @Mapping(target = "id", source = "moment.id")
    @Mapping(target = "region", source = "moment.region.code")
    @Mapping(target = "place", source = "moment.place")
    @Mapping(target = "period", source = "moment.period")
    @Mapping(target = "start", source = "moment.startYear")
    @Mapping(target = "end", source = "moment.endYear")
    @Mapping(target = "focus", source = "moment.focusYear")
    fun toDto(overview: MomentOverview): MomentSummaryDto

    @Mapping(target = "id", source = "overview.moment.id")
    @Mapping(target = "region", source = "overview.moment.region.code")
    @Mapping(target = "place", source = "overview.moment.place")
    @Mapping(target = "period", source = "overview.moment.period")
    @Mapping(target = "start", source = "overview.moment.startYear")
    @Mapping(target = "end", source = "overview.moment.endYear")
    @Mapping(target = "focus", source = "overview.moment.focusYear")
    @Mapping(target = "people", source = "overview.people")
    @Mapping(target = "waiting", source = "overview.waiting")
    @Mapping(target = "cards", source = "overview.cards")
    @Mapping(target = "story", source = "overview.story")
    @Mapping(target = "storyCards", source = "cards")
    @Mapping(target = "draft", ignore = true)
    fun toDto(story: MomentStory): MomentDetailDto

    fun toDto(card: StoryCard): StoryCardDto

    fun toDto(world: WorldAround): WorldDto

    fun toDto(status: ClaimStatus): ClaimStatusDto

    fun toDto(people: MomentPeople): MomentPeopleDto

    @Mapping(target = "person", source = "person.person")
    @Mapping(target = "label", source = "person.label")
    @Mapping(target = "labelFr", source = "person.labelFr")
    @Mapping(target = "born", source = "person.born")
    @Mapping(target = "died", source = "person.died")
    @Mapping(target = "occupation", source = "person.occupation")
    @Mapping(target = "sitelinks", source = "person.sitelinks")
    fun toDto(person: MomentPerson): MomentPersonDto
}
