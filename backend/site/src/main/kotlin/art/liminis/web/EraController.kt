package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.ErasApi
import art.liminis.service.EraService

@RestController
class EraController(private val eraService: EraService) : ErasApi {

    override fun listEras() = eraService.list()

    override fun getEra(id: String) = eraService.detail(id)
}
