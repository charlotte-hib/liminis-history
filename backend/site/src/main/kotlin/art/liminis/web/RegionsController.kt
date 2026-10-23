package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.RegionsApi
import art.liminis.api.model.LanguageDto
import art.liminis.service.RegionService

@RestController
class RegionsController(private val regionService: RegionService) : RegionsApi {

    override fun listRegions(lang: LanguageDto) = regionService.list(lang.language())
}
