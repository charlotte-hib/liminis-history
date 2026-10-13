package art.liminis

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import art.liminis.repository.MomentRepository
import art.liminis.runs.Runs
import art.liminis.testing.PostgresTestConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Starts as `./gradlew :workbench:bootRun` does: it loads the repository's `sample/`, and runs nothing until asked. */
@SpringBootTest(properties = ["app.wikipedia.enrich=false"])
@Import(PostgresTestConfiguration::class)
class WorkbenchApplicationTests(
    @Autowired private val moments: MomentRepository,
    @Autowired private val runs: Runs,
) {

    @Test
    fun `loads the release, and runs nothing until asked`() {
        assertEquals(10, moments.count())
        assertTrue(runs.all().isEmpty())
    }
}
