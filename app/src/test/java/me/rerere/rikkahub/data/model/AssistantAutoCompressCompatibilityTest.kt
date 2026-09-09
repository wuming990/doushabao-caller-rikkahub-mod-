package me.rerere.rikkahub.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantAutoCompressCompatibilityTest {
    @Test
    fun `old assistant settings keep legacy context window for migration`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"autoCompressContextWindow":128000}"""
        )

        assertEquals(128_000, assistant.legacyAutoCompressContextWindow)
        assertEquals(AutoCompressModelSource.FIXED, assistant.autoCompressModelSource)
    }

    @Test
    fun `old single model injection is exposed as one automatic target`() {
        val targetId = Uuid.random()
        val injection = JsonInstant.decodeFromString<PromptInjection.ModeInjection>(
            """{"targetModelId":"$targetId"}"""
        )

        assertEquals(setOf(targetId), injection.automaticTargetModelIds())
    }

    @Test
    fun `new mode injection preserves multiple automatic targets`() {
        val targetIds = setOf(Uuid.random(), Uuid.random())
        val encoded = JsonInstant.encodeToString(
            PromptInjection.ModeInjection(
                targetModelId = null,
                targetModelIds = targetIds,
            )
        )
        val decoded = JsonInstant.decodeFromString<PromptInjection.ModeInjection>(encoded)

        assertNull(decoded.targetModelId)
        assertEquals(targetIds, decoded.automaticTargetModelIds())
    }
}
