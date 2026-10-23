package art.liminis.web

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import art.liminis.curation.ClaimRepository
import art.liminis.curation.ClaimRules
import art.liminis.curation.Claims
import art.liminis.curation.TranslatedText
import art.liminis.curation.Translation
import art.liminis.runs.Runs
import art.liminis.support.InvalidRequestException
import art.liminis.support.NotFoundException
import art.liminis.translating.RecordTexts
import art.liminis.translating.TranslationSources
import art.liminis.web.WorkbenchSecurity.Companion.currentActor
import art.liminis.workbench.api.TranslationsApi
import art.liminis.workbench.api.model.CardTypeDto
import art.liminis.workbench.api.model.ClaimDetailDto
import art.liminis.workbench.api.model.NewFrenchDto
import art.liminis.workbench.api.model.PaintingDto
import art.liminis.workbench.api.model.RecordCardDto
import art.liminis.workbench.api.model.RecordFrenchDto
import art.liminis.workbench.api.model.RecordTextDto
import art.liminis.workbench.api.model.RunDto
import art.liminis.workbench.api.model.RunKindDto

/** The records of `data/` in French: one with its French, translated with Claude or written by hand, or all at once. */
@RestController
class TranslationController(
    private val runs: Runs,
    private val sources: TranslationSources,
    private val claims: Claims,
    private val reviewed: ClaimRepository,
    private val claimMapper: ClaimApiMapper,
) : TranslationsApi {

    override fun getTranslationRun(): RunDto = runs.lastTranslation() ?: throw NotFoundException("No translation run yet")

    override fun translate(): RunDto = runs.start(RunKindDto.TRANSLATIONS, null)

    override fun getRecordFrench(record: String): RecordFrenchDto {
        val found = find(record)
        val french = RecordTexts.french(found.kind, found.node).orEmpty()
        val claim = ClaimRules.translationId(found.key).takeIf(reviewed::existsById)
        return RecordFrenchDto(
            record = found.key,
            label = found.label,
            texts = found.texts.map { (path, en) -> RecordTextDto(path, en, french[path]) },
            claim = claim,
            run = runs.lastTranslation(found.key),
            art = found.node["art"]?.let(::painting),
            cards = found.node["cards"]?.values()?.map { card ->
                RecordCardDto(
                    type = CardTypeDto.valueOf(card["type"].asString()),
                    person = card["person"]?.asString(),
                    life = card["life"]?.asString(),
                    event = card["event"]?.asString(),
                    year = card["year"]?.asInt(),
                    art = card["art"]?.let(::painting),
                )
            },
        )
    }

    private fun painting(art: JsonNode) = PaintingDto(art["url"].asString(), art["credit"].asString(), art["source"].asString())

    override fun translateRecord(record: String): RunDto = runs.start(RunKindDto.TRANSLATIONS, null, find(record).key)

    @ResponseStatus(HttpStatus.CREATED)
    override fun writeRecordFrench(record: String, newFrenchDto: NewFrenchDto): ClaimDetailDto {
        val found = find(record)
        val french = newFrenchDto.texts.associate { it.path to it.fr }
        if (french.keys != found.texts.map { it.first }.toSet()) invalid("Write every text of ${found.key} in French, and only those")
        val proposal = Translation(found.key, found.label, found.texts.map { (path, en) -> TranslatedText(path, en, "") })
        val written = proposal.copy(texts = proposal.texts.map { it.copy(fr = french.getValue(it.path)) })
        return claimMapper.toDto(claims.addApproved(written, proposal, currentActor().name, newFrenchDto.note))
    }

    private fun find(record: String) = sources.record(sources.base(), record) ?: throw NotFoundException("No record $record")

    private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)
}
