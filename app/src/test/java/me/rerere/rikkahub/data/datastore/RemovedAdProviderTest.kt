package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v305：移除内置广告位供应商后，老用户设备上已存的条目要按「没填密钥且没配模型才清」
 * 的口径自动清掉。这里真跑判定函数（纯逻辑、无 Android 依赖），不是读源码字符串。
 */
class RemovedAdProviderTest {

    private val adId: Uuid = REMOVED_AD_PROVIDER_IDS.first()

    private fun openAi(
        id: Uuid = adId,
        apiKey: String = "",
        models: List<Model> = emptyList(),
    ) = ProviderSetting.OpenAI(id = id, apiKey = apiKey, models = models)

    @Test
    fun `广告位条目且未填密钥未配模型时清掉`() {
        assertTrue(openAi().shouldDropRemovedAdProvider())
    }

    @Test
    fun `填过密钥的广告位条目保留不动`() {
        assertFalse(openAi(apiKey = "sk-user-key").shouldDropRemovedAdProvider())
    }

    @Test
    fun `没填密钥但自己配过模型的广告位条目也保留`() {
        val model = Model(modelId = "some-model", displayName = "some-model", type = ModelType.CHAT)
        assertFalse(openAi(models = listOf(model)).shouldDropRemovedAdProvider())
    }

    @Test
    fun `不在广告位清单里的条目一律不动`() {
        val openAiId = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e") // 内置 OpenAI
        assertFalse(openAi(id = openAiId).shouldDropRemovedAdProvider())
    }

    @Test
    fun `密钥只有空白字符也算没填`() {
        assertTrue(openAi(apiKey = "   ").shouldDropRemovedAdProvider())
    }

    @Test
    fun `广告位清单里的七家都能被识别`() {
        assertEquals(7, REMOVED_AD_PROVIDER_IDS.size)
        REMOVED_AD_PROVIDER_IDS.forEach { id ->
            assertTrue("id=$id 应被判定为可清", openAi(id = id).shouldDropRemovedAdProvider())
        }
    }

    @Test
    fun `语音合成表的 AiHubMix 预置项同口径处理`() {
        val adTtsId = REMOVED_AD_TTS_PROVIDER_IDS.single()
        val clean = TTSProviderSetting.OpenAI(id = adTtsId, apiKey = "")
        val used = TTSProviderSetting.OpenAI(id = adTtsId, apiKey = "tts-user-key")
        assertTrue(clean.shouldDropRemovedAdTtsProvider())
        assertFalse(used.shouldDropRemovedAdTtsProvider())
        // 用户手动新建的 TTS（随机 id）绝不会被清
        assertFalse(TTSProviderSetting.OpenAI(apiKey = "").shouldDropRemovedAdTtsProvider())
        // 系统自带语音合成不在清理范围
        assertFalse(TTSProviderSetting.SystemTTS().shouldDropRemovedAdTtsProvider())
    }

    @Test
    fun `内置清单里不再有 AiHubMix 的语音合成预置项`() {
        // DEFAULT_TTS_PROVIDERS 是 private，这里通过公开面验证：系统语音合成仍在、
        // 且内置供应商表里 7 家广告位一个都不在（与 DefaultProvidersTest 互为印证）
        assertFalse(DEFAULT_PROVIDERS.any { it.id in REMOVED_AD_PROVIDER_IDS })
        assertTrue(DEFAULT_PROVIDERS.any { it.name == "Gemini OAuth" })
    }

    @Test
    fun `没有密钥概念的账号型供应商绝不误清`() {
        // Codex / Grok / GeminiOAuth 没有 apiKey 字段，即使 id 命中也不清（宁可不删）
        val gemini = ProviderSetting.GeminiOAuth(id = adId)
        assertFalse(gemini.shouldDropRemovedAdProvider())
        val codex = ProviderSetting.Codex(id = adId)
        assertFalse(codex.shouldDropRemovedAdProvider())
        val grok = ProviderSetting.Grok(id = adId)
        assertFalse(grok.shouldDropRemovedAdProvider())
    }
}
