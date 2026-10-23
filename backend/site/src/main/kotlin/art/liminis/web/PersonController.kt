package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.api.PeopleApi
import art.liminis.api.model.LanguageDto
import art.liminis.service.PersonService

@RestController
class PersonController(private val personService: PersonService) : PeopleApi {

    /** [year] picks the moment to look at them in, e.g. from a moment page; their prime when absent. */
    override fun getPerson(slug: String, lang: LanguageDto, year: Int?) = personService.detail(slug, year, lang.language())

    /** A few people alive in [year] in each region except [exclude]: the "meanwhile, elsewhere" of a moment. */
    override fun listAliveElsewhere(year: Int, exclude: String, lang: LanguageDto, perRegion: Int) =
        personService.aliveElsewhere(exclude, year, perRegion, lang.language())
}
