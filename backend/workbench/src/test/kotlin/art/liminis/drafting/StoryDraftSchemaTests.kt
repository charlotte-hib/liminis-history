package art.liminis.drafting

import com.anthropic.models.messages.StructuredOutputConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class StoryDraftSchemaTests {

    @Test
    fun `the draft classes give a schema the API accepts for structured output`() {
        // The SDK checks the derived JSON schema against what structured outputs support, and throws if it can't be used.
        val config = StructuredOutputConfig.builder<StoryDraft>().format(StoryDraft::class.java).build()
        val schema = config.rawOutputConfig.toString()
        listOf("hook", "world", "cards", "citations", "sourceId", "quote", "PERSON").forEach { assertContains(schema, it) }
    }
}
