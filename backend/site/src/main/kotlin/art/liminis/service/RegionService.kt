package art.liminis.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import art.liminis.domain.Language
import art.liminis.repository.RegionRepository
import art.liminis.web.ApiMapper
import art.liminis.api.model.RegionDto

@Service
@Transactional(readOnly = true)
class RegionService(private val regions: RegionRepository, private val mapper: ApiMapper) {
    fun list(language: Language): List<RegionDto> = regions.findWithEras().map { mapper.toDto(it, language) }
}
