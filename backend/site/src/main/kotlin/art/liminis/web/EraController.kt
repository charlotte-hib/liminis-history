package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.ErasApi
import art.liminis.api.model.LanguageDto
import art.liminis.service.EraService

@RestController
class EraController(private val eraService: EraService) : ErasApi {

    override fun listEras(lang: LanguageDto) = eraService.list(lang.language())

    override fun getEra(id: String, lang: LanguageDto) = eraService.detail(id, lang.language())
}
