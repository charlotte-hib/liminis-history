package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.SearchApi
import art.liminis.service.SearchService

@RestController
class SearchController(private val searchService: SearchService) : SearchApi {

    override fun search(q: String) = searchService.search(q)
}
