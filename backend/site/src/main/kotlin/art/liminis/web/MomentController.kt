package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.MomentsApi
import art.liminis.api.model.LanguageDto
import art.liminis.service.MomentService

@RestController
class MomentController(private val momentService: MomentService) : MomentsApi {

    override fun listMoments(lang: LanguageDto) = momentService.list(lang.language())

    override fun getMoment(id: String, lang: LanguageDto) = momentService.detail(id, lang.language())

    override fun getStory(id: String, lang: LanguageDto) = momentService.story(id, lang.language())

    override fun listMomentPeople(id: String, year: Int, lang: LanguageDto, draw: Int) = momentService.people(id, year, draw, lang.language())
}
