package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.shouldUseGeminiNativeSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v310：搜索工具的路由 —— 只有「谷歌账号登录 + 模型开了内置搜索」才改用独立搜索工具
 * （GeminiSearchClient）；其它通道（含官方 API key 版 Google）行为一个字节不变。
 *
 * 最后一条是**接线守卫**：主对话的真实工具表在 ChatService 的内联 buildList 里
 * （工厂的返回值只用于 MCP 名校验），若哪天有人把接线改断（工具"装了但没人用"），
 * 这条会直接红 —— 这正是本功能第一版差点静默失效的地方。
 */
class GeminiNativeSearchRoutingTest {
    @Test
    fun `gemini oauth with built-in search uses the native search tool`() {
        assertTrue(
            shouldUseGeminiNativeSearch(
                ProviderSetting.GeminiOAuth(),
                Model(tools = setOf(BuiltInTools.Search)),
            )
        )
    }

    @Test
    fun `other providers keep the request-level built-in search`() {
        assertFalse(
            shouldUseGeminiNativeSearch(
                ProviderSetting.OpenAI(),
                Model(tools = setOf(BuiltInTools.Search)),
            )
        )
    }

    @Test
    fun `no provider means no native tool`() {
        assertFalse(shouldUseGeminiNativeSearch(null, Model(tools = setOf(BuiltInTools.Search))))
    }

    @Test
    fun `without the search switch no native tool is offered`() {
        assertFalse(shouldUseGeminiNativeSearch(ProviderSetting.GeminiOAuth(), Model()))
    }

    @Test
    fun `the real chat path assembles the native search tool`() {
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue(
            "ChatService 的真实工具表未接线（搜索工具会装了个寂寞）",
            chat.contains("createSearchToolsForGeneration(")
        )
        val factory = source("app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt")
        assertTrue(
            "工厂未定义共享装配函数",
            factory.contains("internal fun createSearchToolsForGeneration(")
        )
        assertTrue(
            "工厂未引用原生搜索工具",
            factory.contains("createGeminiNativeSearchTool(")
        )
    }

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }
}
