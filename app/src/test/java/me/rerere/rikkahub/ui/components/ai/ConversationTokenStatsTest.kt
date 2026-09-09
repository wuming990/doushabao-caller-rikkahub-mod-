package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v297：本对话 token 累计显示与接线。
 *
 * 数字缩写是真跑函数；其余是源码门禁 —— 聚合查询、生成期累加、设置开关这几处都埋在
 * Room / Compose / 生成循环里，单测造不出对象，只能钉住「零件真的接上了」。
 */
class ConversationTokenStatsTest {

    private val root: java.io.File? by lazy {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun source(vararg path: String): String {
        val dir = checkNotNull(root) { "找不到工程根目录" }
        return java.io.File(dir, path.joinToString("/")).readText()
    }

    @Test
    fun `数字缩写规则 不足一千原样显示 整数不带小数点`() {
        assertEquals("0", formatTokenTotal(0))
        assertEquals("999", formatTokenTotal(999))
        assertEquals("1K", formatTokenTotal(1_000))
        assertEquals("1.2K", formatTokenTotal(1_234))
        assertEquals("12K", formatTokenTotal(12_000))
        assertEquals("75K", formatTokenTotal(75_000))
        assertEquals("1.5M", formatTokenTotal(1_500_000))
        assertEquals("2M", formatTokenTotal(2_000_000))
    }

    @Test
    fun `消息实体带 cumulativeUsage 字段 走 JSON 不落库版本`() {
        val msg = source("ai", "src", "main", "java", "me", "rerere", "ai", "ui", "Message.kt")
        assertTrue("UIMessage 缺 cumulativeUsage 字段", msg.contains("val cumulativeUsage: TokenUsage? = null"))
        // 数据库版本必须保持 24：这次只是往消息 JSON 里加字段，不该动 schema
        val db = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "data", "db", "AppDatabase.kt"
        )
        assertTrue("AppDatabase 版本被改动", db.contains("version = 24"))
    }

    @Test
    fun `生成循环每轮都累加真实消耗 流式与非流式两条路都要接上`() {
        val loop = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "data", "ai", "GenerationLoop.kt"
        )
        val calls = Regex("foldRoundUsage\\(").findAll(loop).count()
        // 1 处定义 + 2 处调用（流式每轮结束、非流式一次请求）
        assertEquals("foldRoundUsage 定义/调用数量不符", 3, calls)
        assertTrue("没读 handler 的本轮用量", loop.contains("foldRoundUsage(streamChunkHandler.reportedUsage)"))
        assertTrue("累计写入用的是基线+本轮的绝对值", loop.contains("entryCumulativeUsage.accumulate(total)"))
        // 路径级门禁（v297 审查位抓到）：异常中断那一轮也必须计入真实消耗，否则注释与
        // 六语言文案里「中断/重跑的轮也已计入」就是假话。定位到中断续跑那段再查，
        // 不能用整文件 contains —— 成功路径本来就有这一句，会假绿。
        val catchRegion = loop
            .substringAfter("if (error is CancellationException) throw error")
            .substringBefore("val interruptedMaxResumes")
        assertTrue(
            "异常中断轮没计入真实消耗（Claude 开头就报输入量、Gemini 每片都带用量，会漏计）",
            catchRegion.contains("streamChunkHandler.reportedUsage")
        )
        // 中断处只加计数器、绝不在那里写库回退界面（v289 铁律：中断时 messages 是旧快照）
        assertFalse("中断路径直接落库会回退已显示的半截内容", catchRegion.contains("foldRoundUsage("))
    }

    @Test
    fun `handler 保留本轮用量 且消息上的 usage 仍是水位口径`() {
        val handler = source(
            "ai", "src", "main", "java", "me", "rerere", "ai", "ui", "StreamChunkHandler.kt"
        )
        assertTrue("缺本轮用量字段", handler.contains("private var roundUsage: TokenUsage? = null"))
        assertTrue("缺对外读取口", handler.contains("val reportedUsage: TokenUsage? get() = roundUsage"))
        assertTrue("本轮用量没被记录", handler.contains("roundUsage = roundUsage.merge(chunk.usage)"))
        // 消息上的 usage 必须仍是水位口径（改了会把上下文占用与自动压缩判定一起带坏）
        assertTrue("消息 usage 被改成累加口径", handler.contains("copy(usage = usage.merge(chunk.usage))"))
        assertFalse("handler 里出现了累加口径", handler.contains("usage.accumulate("))
    }

    @Test
    fun `对话级聚合查询按 conversation_id 隔离 且优先用累计口径`() {
        val dao = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "data", "db", "dao", "MessageNodeDAO.kt"
        )
        assertTrue("缺 conversation_id 过滤", dao.contains("WHERE mn.conversation_id = ?"))
        assertTrue("没优先取 cumulativeUsage", dao.contains("\$.cumulativeUsage."))
        assertTrue("思考 token 没进聚合", dao.contains("reasoningTokens"))
        assertTrue("全 App 统计页仍是各算一套口径", dao.contains("usageFieldSql(\"promptTokens\")"))
    }

    @Test
    fun `各家服务商解析思考 token 且不重复计入输出`() {
        val openai = source(
            "ai", "src", "main", "java", "me", "rerere", "ai", "provider", "providers", "openai",
            "ChatCompletionsAPI.kt"
        )
        assertTrue("OpenAI 兼容通道没解析 reasoning_tokens", openai.contains("\"reasoning_tokens\""))
        val google = source(
            "ai", "src", "main", "java", "me", "rerere", "ai", "provider", "providers", "google",
            "GoogleProvider.kt"
        )
        assertTrue("Gemini 思考量没单列", google.contains("reasoningTokens = thoughtTokens"))
        // 关键：Gemini 的 completion 仍要含思考（改了会动到上下文与压缩判定），思考只作分解
        assertTrue("Gemini completion 口径被改", google.contains("completionTokens = candidatesTokens + thoughtTokens"))
    }

    @Test
    fun `顶栏显示受设置开关控制 明细窗带口径说明`() {
        val page = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "ui", "pages", "chat", "ChatPage.kt"
        )
        assertTrue("顶栏没接 token 行", page.contains("ConversationTokenStatsLine("))
        assertTrue("开关没生效", page.contains("showTokenStats = setting.displaySetting.showConversationTokenStats"))
        assertTrue("明细窗没接上", page.contains("ConversationTokenStatsDialog("))
        val prefs = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "data", "datastore", "PreferencesStore.kt"
        )
        assertTrue("设置项默认值不是开", prefs.contains("val showConversationTokenStats: Boolean = true"))
        val settingPage = source(
            "app", "src", "main", "java", "me", "rerere", "rikkahub", "ui", "pages", "setting",
            "SettingPreferencesUIPage.kt"
        )
        assertTrue("设置页没有这个开关", settingPage.contains("showConversationTokenStats = it"))
    }

    @Test
    fun `六个语言的文案都补齐`() {
        for (loc in listOf(
            "values", "values-zh", "values-zh-rTW", "values-ja", "values-ko-rKR", "values-ru"
        )) {
            val dir = checkNotNull(root)
            val xml = java.io.File(
                dir, "app/src/main/res/$loc/strings.xml"
            ).readText()
            for (key in listOf(
                "chat_conversation_token_summary",
                "chat_conversation_token_detail_title",
                "chat_conversation_token_reasoning",
                "chat_conversation_token_total",
                "chat_conversation_token_note",
                "chat_conversation_token_close",
                "setting_display_page_show_conversation_tokens_title",
                "setting_display_page_show_conversation_tokens_desc",
            )) {
                assertTrue("$loc 缺文案 $key", xml.contains("name=\"$key\""))
            }
        }
    }
}
