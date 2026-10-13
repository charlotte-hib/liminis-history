package dev.wholivedwhen.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import dev.wholivedwhen.repository.RegionRepository
import dev.wholivedwhen.web.ApiMapper
import dev.wholivedwhen.api.model.RegionDto

@Service
@Transactional(readOnly = true)
class RegionService(private val regions: RegionRepository, private val mapper: ApiMapper) {
    fun list(): List<RegionDto> = regions.findWithEras().map(mapper::toDto)
}
