package me.rerere.rikkahub.utils

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

/** v1021: real icon selection, not model-ID renaming; no account data or network. */
class V1021ModelIconTest {
    @Test fun `opaque web ID uses Gemini icon`() {
        assertEquals("gemini-color.svg", computeAIIconByName("9d8ca3786ebdfbea", ProviderSetting.GeminiWeb()))
    }
    @Test fun `Argon display alias is not needed for web brand`() {
        assertEquals("gemini-color.svg", computeAIIconByName("opaque-account-model", ProviderSetting.GeminiWeb()))
    }
    @Test fun `renamed web provider still uses its actual type`() {
        assertEquals("gemini-color.svg", computeAIIconByName("9opaque", ProviderSetting.GeminiWeb(name = "私人账号")))
    }
    @Test fun `web brand has priority over accidental substrings in opaque ID`() {
        assertEquals("gemini-color.svg", computeAIIconByName("opaque-gpt-123", ProviderSetting.GeminiWeb()))
    }
    @Test fun `web lookup never poisons the same ID on other providers`() {
        val id = "9d8ca3786ebdfbea"
        computeAIIconByName(id, ProviderSetting.GeminiWeb())
        assertNull(computeAIIconByName(id, ProviderSetting.OpenAI()))
        assertNull(computeAIIconByName(id))
    }
    @Test fun `provider name alone must not force Gemini onto another type`() {
        assertNull(computeAIIconByName("9opaque", ProviderSetting.OpenAI(name = "Gemini 网页版")))
    }
    @Test fun `other provider model branding remains unchanged`() {
        assertEquals("claude-color.svg", computeAIIconByName("claude-sonnet", ProviderSetting.OpenAI()))
        assertEquals("zhipu-color.svg", computeAIIconByName("glm-5", ProviderSetting.OpenAI()))
    }
    @Test fun `legacy no provider callers keep old matching and fallback`() {
        assertEquals("gemini-color.svg", computeAIIconByName("gemini-3.1-pro"))
        assertEquals("openai.svg", computeAIIconByName("gpt-5"))
        assertNull(computeAIIconByName("9opaque"))
    }
}
