package art.liminis.release

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Which schema a release goes in: one per deployed commit, or the local one. */
class ReleasePropertiesTests {

    private val dir = Path.of("sample")

    @Test
    fun `a deployed commit names its schema after its first 12 characters`() {
        assertEquals("release_00c16a24aa84", ReleaseProperties(dir, "00c16a24aa84ab1637ee4559aaef4103c047f33d").schema)
    }

    @Test
    fun `without a commit, the schema is the local one`() {
        assertEquals("release_local", ReleaseProperties(dir).schema)
        assertEquals("release_local", ReleaseProperties(dir, "").schema)
    }

    @Test
    fun `anything but a commit hash is refused, so it never reaches SQL`() {
        assertFailsWith<IllegalArgumentException> { ReleaseProperties(dir, "x; drop schema public").schema }
        assertFailsWith<IllegalArgumentException> { ReleaseProperties(dir, "00C16A24AA84").schema }
    }
}
