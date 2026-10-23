package art.liminis.service

import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import art.liminis.domain.Domain
import art.liminis.domain.Language
import org.springframework.transaction.annotation.Transactional
import art.liminis.support.NotFoundException
import art.liminis.repository.EraRepository
import art.liminis.repository.EventRepository
import art.liminis.repository.PersonRepository
import art.liminis.web.ApiMapper
import art.liminis.api.model.EraDetailDto
import art.liminis.api.model.EraDto

/** How many of the most written about people of each domain an era shows. */
private const val PER_DOMAIN = 12

@Service
@Transactional(readOnly = true)
class EraService(
    private val eras: EraRepository,
    private val people: PersonRepository,
    private val events: EventRepository,
    private val moments: MomentService,
    private val mapper: ApiMapper,
) {
    fun list(language: Language): List<EraDto> = eras.findAllByOrderByStartYearAscIdAsc().map { mapper.toDto(it, language) }

    fun detail(id: String, language: Language): EraDetailDto {
        val era = eras.findById(id).orElseThrow {
            NotFoundException("Unknown era: $id")
        }
        return EraDetailDto(
            era = mapper.toDto(era, language),
            people = Domain.entries
                .associate { domain -> domain.name to people.findTiedAliveBetween(era.region.code, era.startYear, era.endYear, domain, Limit.of(PER_DOMAIN)) }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, group) -> group.map { mapper.toDto(it, language) } },
            events = events.findByEraIdOrderByYearAscIdAsc(id).map { mapper.toDto(it, language) },
            moments = moments.overlapping(era.region.code, era.startYear, era.endYear, language),
        )
    }
}
