package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.SearchApi
import art.liminis.api.model.LanguageDto
import art.liminis.service.SearchService

@RestController
class SearchController(private val searchService: SearchService) : SearchApi {

    override fun search(q: String, lang: LanguageDto) = searchService.search(q, lang.language())
}
