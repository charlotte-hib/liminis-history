package art.liminis.auth

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Every token auth issues is a row in oauth2_authorization, wb's included (one per command, about one every 5 minutes
 * through a long run), and Spring Security never deletes them. Once all of a row's tokens have expired it is of no use.
 */
@Component
class ExpiredAuthorizations(private val jdbc: JdbcClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "1d", initialDelayString = "1m")
    fun delete() {
        val deleted = jdbc.sql(
            """
            delete from oauth2_authorization
            where greatest(authorization_code_expires_at, access_token_expires_at, refresh_token_expires_at) < now()
            """,
        ).update()
        if (deleted > 0) log.info("Deleted {} expired authorizations", deleted)
    }
}
