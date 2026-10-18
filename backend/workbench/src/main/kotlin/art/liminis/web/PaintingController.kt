package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.ReportingPolicy
import org.springframework.web.bind.annotation.RestController
import art.liminis.paintings.PaintingSearch
import art.liminis.paintings.Paintings
import art.liminis.workbench.api.PaintingsApi
import art.liminis.workbench.api.model.PaintingSearchDto

@RestController
class PaintingController(private val paintings: Paintings, private val mapper: PaintingApiMapper) : PaintingsApi {

    override fun findPaintings(q: String, limit: Int): PaintingSearchDto = mapper.toDto(paintings.find(q, limit))
}

/** Paintings found on Commons to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface PaintingApiMapper {

    fun toDto(search: PaintingSearch): PaintingSearchDto
}
