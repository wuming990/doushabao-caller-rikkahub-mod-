package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.supportsReasoning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1022: wire values and UI capability for Gemini Web thinking tiers. No account/network. */
class V1022GeminiWebThinkingTest {
    private fun model(capacityField: Int = 12) = GeminiWebModel(
        modelId = "opaque-account-model",
        displayName = "Gemini 4 Argon",
        category = "MODEL",
        number = 8,
        capacity = if (capacityField == 13) 1 else 2,
        capacityField = capacityField,
    )

    @Test fun `Argon is detected from display name or the account mode number`() {
        assertTrue(model().isArgonModel())
        assertTrue(model().copy(displayName = "Localized name", category = "MODEL").isArgonModel())
        assertFalse(model().copy(displayName = "Gemini 3.8 Flash", number = 38).isArgonModel())
    }

    @Test fun `AUTO stays low at transport for background calls`() {
        assertEquals(1, geminiWebThinkingWire(ReasoningLevel.AUTO, isArgon = true))
        assertEquals(1, geminiWebThinkingWire(ReasoningLevel.AUTO, isArgon = false))
    }

    @Test fun `explicit tiers map to documented wire values`() {
        assertEquals(1, geminiWebThinkingWire(ReasoningLevel.OFF, isArgon = true))
        assertEquals(1, geminiWebThinkingWire(ReasoningLevel.LOW, isArgon = true))
        assertEquals(4, geminiWebThinkingWire(ReasoningLevel.MEDIUM, isArgon = true))
        assertEquals(1, geminiWebThinkingWire(ReasoningLevel.MEDIUM, isArgon = false))
        assertEquals(2, geminiWebThinkingWire(ReasoningLevel.HIGH, isArgon = true))
        assertEquals(2, geminiWebThinkingWire(ReasoningLevel.XHIGH, isArgon = true))
        assertEquals(2, geminiWebThinkingWire(ReasoningLevel.MAX, isArgon = true))
    }

    @Test fun `header thinking slot is second from last and session stays last`() {
        val header = Json.parseToJsonElement(model().modelHeader("session-id", 2)).jsonArray
        assertEquals("2", header[header.size - 2].jsonPrimitive.content)
        assertEquals("session-id", header.last().jsonPrimitive.content)
    }

    @Test fun `capacity field thirteen keeps the documented null-capacity layout`() {
        val header = Json.parseToJsonElement(model(capacityField = 13).modelHeader("sid", 2)).jsonArray
        assertEquals(18, header.size)
        assertEquals(JsonNull, header[11])
        assertEquals("1", header[12].jsonPrimitive.content)
        assertEquals("8", header[header.size - 3].jsonPrimitive.content)
        assertEquals("2", header[header.size - 2].jsonPrimitive.content)
        assertEquals("sid", header.last().jsonPrimitive.content)
    }

    @Test fun `legacy one argument header stays low tier`() {
        val header = Json.parseToJsonElement(model().modelHeader("sid")).jsonArray
        assertEquals("1", header[header.size - 2].jsonPrimitive.content)
    }

    @Test fun `saved Gemini Web model gets reasoning picker even with old empty abilities`() {
        val saved = Model(modelId = "old-opaque", displayName = "Gemini 4 Argon")
        val provider = ProviderSetting.GeminiWeb(models = listOf(saved))
        assertTrue(saved.supportsReasoning(listOf(provider)))
    }

    @Test fun `other provider does not gain reasoning ability from a web lookalike`() {
        val saved = Model(modelId = "plain", displayName = "Plain")
        val provider = ProviderSetting.OpenAI(models = listOf(saved))
        assertFalse(saved.supportsReasoning(listOf(provider)))
    }

    @Test fun `explicit reasoning ability remains supported for other providers`() {
        val saved = Model(modelId = "reasoner", displayName = "Reasoner", abilities = listOf(ModelAbility.REASONING))
        val provider = ProviderSetting.OpenAI(models = listOf(saved))
        assertTrue(saved.supportsReasoning(listOf(provider)))
    }

    @Test fun `no provider and no ability means no thinking picker`() {
        assertFalse(Model(modelId = "plain", displayName = "Plain").supportsReasoning(emptyList()))
    }
}
