package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    /**
     * v305：内置清单里的 7 个广告位（带推广文案与返佣链接）已按用户要求移除，
     * 推荐清单也整表清空。官方上游 2.5.1 里这些条目**依然存在**，将来合并上游时
     * 一旦被整覆盖就会静默回来 —— 这条测试就是为了在那一刻变红，而不是悄悄退化。
     * 同时守住宿主机邀请码这类不该出现在我们发布物里的东西（v305 已连带清除）。
     */
    @Test
    fun `已移除的广告位供应商不会随上游合并回来`() {
        val names = DEFAULT_PROVIDERS.map { it.name }
        listOf("AiHubMix", "APIMart", "硅基流动", "小马算力", "302.AI", "随想AI网关", "MaruCode")
            .forEach { removed ->
                assertFalse("内置清单又出现了已移除的广告位：$removed", names.contains(removed))
            }
        // 核心供应商必须还在（防止有人用「清空整张表」来交差）
        listOf("OpenAI", "Gemini", "DeepSeek", "OpenRouter", "Gemini OAuth", "Codex", "Grok")
            .forEach { kept ->
                assertTrue("内置清单缺了必须保留的供应商：$kept", names.contains(kept))
            }
        // 推荐清单已整表清空（SettingProviderPage 靠「空就隐藏按钮」处理入口）
        assertTrue("推荐清单又带回广告位了", RECOMMENDED_PROVIDERS.isEmpty())
    }

    /**
     * v305：v274 内置的三套官方账号条目（默认关闭）必须保持默认关 ——
     * 上面那条断言只查"还在不在"，这里补住"不能被顺手改成开启"。
     */
    @Test
    fun `官方账号登录的内置条目仍然默认关闭`() {
        listOf("Codex", "Grok", "Gemini OAuth").forEach { name ->
            val provider = DEFAULT_PROVIDERS.single { it.name == name }
            assertFalse("$name 内置条目不应默认开启", provider.enabled)
        }
    }

    /**
     * v305：Gemini 账号登录界面原本 34 条文案只有 10 条做了中文，缺的 24 条在中文环境下
     * 回退成英文（用户报「把 gemini 账号登录的英文改为中文」）。这里钉住「中文不缺键」，
     * 否则将来上游往这个界面加英文文案时，我们又会静默出现中英混排。
     */
    @Test
    fun `Gemini 登录界面的中文文案没有缺键`() {
        val repoRoot = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").exists() }
        val root = repoRoot ?: java.io.File(System.getProperty("user.dir"))
        fun geminiKeys(path: String): Set<String> =
            Regex("<string name=\"(gemini_[^\"]+)\"")
                .findAll(java.io.File(root, path).readText())
                .map { it.groupValues[1] }
                .toSet()
        val en = geminiKeys("app/src/main/res/values/strings.xml")
        val zh = geminiKeys("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文侧应有 Gemini 文案键", en.isNotEmpty())
        assertEquals(
            "中文缺的 Gemini 文案：${(en - zh).sorted()}",
            emptySet<String>(),
            en - zh,
        )
        // 抽查两条最显眼的入口文案确实被翻了（不是照抄英文）
        val zhText = java.io.File(root, "app/src/main/res/values-zh/strings.xml").readText()
        listOf(
            "name=\"gemini_sign_in\">用谷歌账号登录",
            "name=\"gemini_ban_warning_title\">",
            "name=\"gemini_check_status\">检查状态",
        ).forEach {
            assertTrue("中文 Gemini 文案缺「$it」", zhText.contains(it))
        }
    }
}
