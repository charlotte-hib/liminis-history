package art.liminis.web

import org.springframework.web.bind.annotation.RestController
import art.liminis.runs.Runs
import art.liminis.support.NotFoundException
import art.liminis.workbench.api.TranslationsApi
import art.liminis.workbench.api.model.RunDto
import art.liminis.workbench.api.model.RunKindDto

@RestController
class TranslationController(private val runs: Runs) : TranslationsApi {

    override fun getTranslationRun(): RunDto = runs.all().firstOrNull { it.kind == RunKindDto.TRANSLATIONS } ?: throw NotFoundException("No translation run yet")

    override fun translate(): RunDto = runs.start(RunKindDto.TRANSLATIONS, null)
}
