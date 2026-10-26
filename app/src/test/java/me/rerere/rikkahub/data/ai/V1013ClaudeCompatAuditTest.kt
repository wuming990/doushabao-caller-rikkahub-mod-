package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.providers.claude.ClaudeCompatMemory
import me.rerere.ai.provider.providers.claude.ClaudeRequestFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1013 二审后的**接线守卫**：算法本身在 ai 模块的
 * `ClaudeLadderAuditV1013Test` / `ClaudeThinkingCompatTest` 里测，
 * 这里钉的是「编译器抓不到、删一行就静默复发」的接法与出口。
 *
 * 每一条都对应两位独立审查位真实抓到过的一个 fail 点，不是假想的坏味道。
 */
class V1013ClaudeCompatAuditTest {

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

    private val providerSource =
        "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt"
    private val compatSource =
        "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeThinkingCompat.kt"
    private val memorySource =
        "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeCompatMemory.kt"
    private val uiSource =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt"

    @Test
    fun `非流式路径的读数与脱敏必须与流式同一口径`() {
        val provider = source(providerSource)
        assertTrue(
            "非流式卡片要报出「本次带了哪些可选字段」，不能再硬填空表",
            provider.contains("sentOptionalFields = claudeOptionalFieldsIn(requestBody)"),
        )
        assertFalse(
            "emptyList() 会让卡片少一项关键读数，还会失去 max_tokens 那一项的无关词否决",
            provider.contains("sentOptionalFields = emptyList()"),
        )
        assertTrue(
            "进卡片的上游原文必须是脱敏后的那一份",
            provider.contains("ClaudeDegradeExhaustedException(diag, diag.upstreamExcerpt)"),
        )
        assertFalse(
            "把 raw 原样当第二个参数传下去 = 自己绕过隐私闸（卡片带复制按钮）",
            provider.contains("ClaudeDegradeExhaustedException(diagnostics(code, raw), raw?.take(200))"),
        )
    }

    @Test
    fun `脱敏闸要覆盖 system 与 tools，调用点一个都不许少`() {
        val provider = source(providerSource)
        val calls = provider.lines().count { it.contains("sanitizeClaudeUpstreamExcerpt(") }
        assertTrue("流式 diagnostics、流式 refusal 的 explanation、非流式那一处都要过闸，实测 $calls 处", calls >= 3)
        val compat = source(compatSource)
        assertTrue(
            "检索范围必须含 system 与 tools —— 助手提示词与工具说明书正好在这两处",
            compat.contains("""listOf("system", "messages", "tools")"""),
        )
        assertFalse(
            "只查 messages 的旧写法不许复活",
            provider.contains("""requestBody["messages"].toString().contains(excerpt)"""),
        )
    }

    @Test
    fun `卡片里的插值必须是真值形式`() {
        val provider = source(providerSource)
        // 期望源码里是「真插值」；曾经写成先转义美元符再跟字面 {}，结果卡片上印的是模板本身
        val expected = "add(\"max_tokens=\${it.jsonPrimitive.content}\")"
        assertTrue("找不到 $expected", provider.contains(expected))
    }

    @Test
    fun `已试档数要从真实起步档算起`() {
        val provider = source(providerSource)
        assertTrue(provider.contains("var variantsTried = index + 1"))
        assertFalse("写死 1 会把没试过的档位冒充成试过的起点", provider.contains("var variantsTried = 1"))
    }

    @Test
    fun `我们自己解码失败不许被当成上游嫌参数`() {
        val provider = source(providerSource)
        assertTrue(
            "必须按异常类型排除（kotlinx 的文案天然带 unexpected，还把原始 data 拼进 message）",
            provider.contains("if (!isOurOwnDecodeFailure(e) && tryDegradeThinkingTier(null, e.message, this)) return"),
        )
        assertTrue(
            "真错误帧走 HttpException，不能连它一起挡掉",
            provider.contains("e is kotlinx.serialization.SerializationException ||"),
        )
    }

    @Test
    fun `读数不许自相矛盾，最后一档不发思考时不能再报改发老式`() {
        val compat = source(compatSource)
        assertTrue(compat.contains("""if (useLegacyBudget && !dropThinking) add("思考改发老式预算")"""))
        assertFalse(compat.contains("""        if (useLegacyBudget) add("思考改发老式预算")"""))
    }

    @Test
    fun `没有调用点的死函数不许留着误导下一个人`() {
        val compat = source(compatSource)
        assertFalse("claudeNewlyStripped 已被阶梯归一化取代，只该留下删除说明",
            compat.contains("private fun claudeNewlyStripped("))
    }

    @Test
    fun `撞过底之后要能被下一次成功解除`() {
        val memory = ClaudeCompatMemory(null)
        memory.rememberExhausted("p|claude-opus-4-6", 5)
        assertEquals(5, memory.exhaustedFloor("p|claude-opus-4-6"))
        // 真的吐出内容 → 那个「底」就是过时结论，必须当场作废
        memory.remember("p|claude-opus-4-6", ClaudeRequestFlags(dropCacheControl = true))
        assertEquals("一次 400 不该把这台设备钉到重启为止", 0, memory.exhaustedFloor("p|claude-opus-4-6"))
    }

    @Test
    fun `记住默认档也要解除撞底下限（v1014 去掉多余的那道门）`() {
        val memory = ClaudeCompatMemory(null)
        memory.rememberExhausted("p|m", 4)
        assertEquals(4, memory.exhaustedFloor("p|m"))
        // 以前这里附加了「学到的档必须是非默认」的条件：万一末档就是默认档
        // （只有 1 档的形态），下限就永远解不开。成功本身已经足够说明那个底过时了。
        memory.remember("p|m", ClaudeRequestFlags())
        assertEquals(0, memory.exhaustedFloor("p|m"))
    }

    @Test
    fun `只撞过底没成功过时设置页也要看得见出口`() {
        val memory = ClaudeCompatMemory(null)
        memory.rememberExhausted("p|m", 3)
        assertTrue(memory.hasExhaustedForProvider("p"))
        assertFalse("撞过底不等于「学到过」，列表本来就应该是空的",
            memory.entriesForProvider("p").isNotEmpty())
        assertFalse(memory.hasExhaustedForProvider("other"))
        memory.forgetProvider("p")
        assertFalse(memory.hasExhaustedForProvider("p"))
        assertEquals(0, memory.exhaustedFloor("p|m"))
    }

    @Test
    fun `设置页的重置出口要同时覆盖撞底场景`() {
        val ui = source(uiSource)
        assertTrue(
            "只撞底没成功过时 learned 是空的，旧条件会把唯一的清除入口藏起来（用户只能重启 App）",
            ui.contains("if (learned.isNotEmpty() || exhaustedHere)"),
        )
        assertTrue(ui.contains("hasExhaustedForProvider(provider.id.toString())"))
    }

    @Test
    fun `记忆与兼容层都不许调 android 日志`() {
        // ai 模块单测没开 returnDefaultValues，android.util.Log 一调就抛「not mocked」把测试打死。
        listOf(memorySource, compatSource).forEach { path ->
            val text = source(path)
            assertFalse("$path 又引了 android.util.Log", text.contains("import android.util.Log"))
        }
    }
}
