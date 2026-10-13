package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.MomentsApi
import art.liminis.service.MomentService

@RestController
class MomentController(private val momentService: MomentService) : MomentsApi {

    override fun listMoments() = momentService.list()

    override fun getMoment(id: String) = momentService.detail(id)

    override fun getStory(id: String) = momentService.story(id)

    override fun listMomentPeople(id: String, year: Int, draw: Int) = momentService.people(id, year, draw)
}
