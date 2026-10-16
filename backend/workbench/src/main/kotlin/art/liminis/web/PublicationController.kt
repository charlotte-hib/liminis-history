package art.liminis.web

import org.mapstruct.Mapper
import org.mapstruct.ReportingPolicy
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.RestController
import art.liminis.publishing.FileChanges
import art.liminis.publishing.Publication
import art.liminis.publishing.PublishProperties
import art.liminis.publishing.Publishing
import art.liminis.workbench.api.PublicationApi
import art.liminis.workbench.api.model.FileChangesDto
import art.liminis.workbench.api.model.NewPublicationDto
import art.liminis.workbench.api.model.PublicationDto
import java.time.Instant

/**
 * The publication API. Publishing writes to the data repository, on its way to production: it takes a passkey used in
 * the last few minutes, not only a valid sign-in. An older one gets the answer of RFC 9470, a step-up challenge, for
 * curate to ask for the passkey again with `max_age`.
 */
@RestController
class PublicationController(
    private val publishing: Publishing,
    private val properties: PublishProperties,
    private val mapper: PublicationApiMapper,
) : PublicationApi {

    override fun previewPublication(): PublicationDto = mapper.toDto(publishing.preview(), maxAge())

    override fun publish(newPublicationDto: NewPublicationDto): PublicationDto {
        requireRecentPasskey()
        return mapper.toDto(publishing.publish(newPublicationDto.base), maxAge())
    }

    /** The passkey's age limit, said in each answer, so that curate asks auth for the one the workbench checks. */
    private fun maxAge() = properties.maxAge.toSeconds().toInt()

    private fun requireRecentPasskey() {
        val token = (SecurityContextHolder.getContext().authentication as JwtAuthenticationToken).token
        val usedAt = token.getClaimAsInstant(AUTH_TIME)
        if (usedAt != null && usedAt >= Instant.now().minus(properties.maxAge)) return
        val maxAge = properties.maxAge.toSeconds()
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Publishing needs your passkey again: sign in once more")
        throw ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null).apply {
            headers.set(
                HttpHeaders.WWW_AUTHENTICATE,
                """Bearer error="insufficient_user_authentication", error_description="A passkey used in the last $maxAge seconds is needed", max_age=$maxAge""",
            )
        }
    }

    private companion object {
        /** When the curator last used their passkey, as auth writes it in access tokens. */
        const val AUTH_TIME = "auth_time"
    }
}

/** Publications to the API's models. Every target field is mapped or ignored on purpose. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface PublicationApiMapper {
    fun toDto(publication: Publication, maxAge: Int): PublicationDto

    fun toDto(changes: FileChanges): FileChangesDto
}
