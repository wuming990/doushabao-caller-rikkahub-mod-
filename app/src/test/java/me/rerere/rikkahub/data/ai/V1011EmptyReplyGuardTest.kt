package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1011 零产出定格：判据分类 + 接线守卫。
 *
 * 起因（用户真机原话）：「claude兼容格式好像有问题，输出会被截断导致空回」「输出上限是无限，
 * 开启思考了，没有显示思考框，直接回复被截断了」。修完解析器之后还剩一件事必须做：
 * 上游给的是 HTTP 200、没有任何错误时，软件不能再装作「回答顺利结束」—— 旧路径是
 * ChatService 因 cause=null 记 COMPLETED、不弹错，界面上要么空白、要么只挂一排按钮。
 *
 * 两半：
 * 1. [classifyEmptyReply] 是真逻辑，纯函数直接测。**重点是别误伤正常产出**：只回工具、
 *    只回图片、只回服务端工具、等审批这些必须返回 null（不报错）。
 *    两个独立审查位各抓过一条：只按 `isEmptyUIMessage()` 判的话，「有思考、零正文」这种
 *    形态永远判成非空、守卫永远不触发 —— 那正是用户这次撞上的东西，所以这里单独有用例。
 * 2. 接线（默认关、只有主对话显式打开、参数一路传到 generateInternal、异常不被续跑吃掉、
 *    流式与非流式两条路都有守卫、文案两份语言齐全）全是「删一行就静默复发、编译器抓不到」
 *    的形态，照同目录 Guard 测试惯例读源码比对。**只证明接线还在，不证明逻辑正确。**
 */
class V1011EmptyReplyGuardTest {

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

    private fun text(s: String) = UIMessagePart.Text(s)
    private fun reasoning(s: String) = UIMessagePart.Reasoning(s)
    private fun tool() = UIMessagePart.Tool(toolCallId = "call_1", toolName = "search_web", input = "{}")
    private fun serverTool() = UIMessagePart.ServerTool(
        toolCallId = "srv_1",
        toolName = "web_search",
        input = null,
        output = null,
        status = ServerToolStatus.COMPLETED,
    )
    private fun image(url: String) = UIMessagePart.Image(url)

    // ---------- 第 1 半：判据与分类（行为） ----------

    @Test
    fun `any real answer must never be reported as an empty reply`() {
        // 正文非空
        assertNull(classifyEmptyReply(listOf(reasoning("想了一下"), text("这是答复")), "end_turn", false))
        // 只回工具调用（一个字正文都没有）—— 也绝不能报错
        assertNull(classifyEmptyReply(listOf(reasoning("想"), tool()), "tool_use", false))
        // 只回服务端工具（搜索之类）
        assertNull(classifyEmptyReply(listOf(reasoning("想"), serverTool()), "end_turn", true))
        // 只回图片
        assertNull(classifyEmptyReply(listOf(image("data:image/png;base64,AAAA")), "end_turn", false))
    }

    @Test
    fun `blank text and blank image url do not count as an answer`() {
        assertEquals(
            EmptyReplyKind.UpstreamReturnedNothing,
            classifyEmptyReply(listOf(text("   "), image("")), "end_turn", false),
        )
    }

    @Test
    fun `thinking with no answer is its own kind because that is what the user saw`() {
        // 用户原话「没有显示思考框，直接回复被截断了」：有思考、零正文、上游自称说完
        assertEquals(
            EmptyReplyKind.ThinkingOnlyWithoutAnswer,
            classifyEmptyReply(listOf(reasoning("想了好久")), "end_turn", false),
        )
        // 但上游明确说撞到输出上限时，归到上限类（措辞要指向「调大上限/降档位」）
        assertEquals(
            EmptyReplyKind.OutputBudgetExhausted,
            classifyEmptyReply(listOf(reasoning("想了好久")), "max_tokens", false),
        )
    }

    @Test
    fun `output budget truncation is its own kind because the fix is a setting change`() {
        assertEquals(
            EmptyReplyKind.OutputBudgetExhausted,
            classifyEmptyReply(emptyList(), "max_tokens", false),
        )
        assertEquals(
            EmptyReplyKind.OutputBudgetExhausted,
            classifyEmptyReply(emptyList(), "length", false),
        )
        // Responses API 的写法；断流标记为真也不能抢走「上限截断」这个更具体的结论
        assertEquals(
            EmptyReplyKind.OutputBudgetExhausted,
            classifyEmptyReply(emptyList(), "incomplete:max_output_tokens", true),
        )
    }

    @Test
    fun `policy refusal is never reported as a technical truncation`() {
        assertEquals(EmptyReplyKind.RefusedByPolicy, classifyEmptyReply(emptyList(), "refusal", false))
        assertEquals(EmptyReplyKind.RefusedByPolicy, classifyEmptyReply(emptyList(), "content_filter", true))
        assertEquals(EmptyReplyKind.RefusedByPolicy, classifyEmptyReply(emptyList(), "SAFETY", false))
    }

    @Test
    fun `context window exceeded is not lumped in with protocol trouble`() {
        // 审查位 P2：以前它落到 UpstreamReturnedNothing，文案把用户指向「报文形态不对」
        assertEquals(
            EmptyReplyKind.ContextWindowExceeded,
            classifyEmptyReply(emptyList(), "model_context_window_exceeded", false),
        )
    }

    @Test
    fun `silent cut requires both no sentinel and no stop reason`() {
        assertEquals(EmptyReplyKind.StreamCutWithoutSignal, classifyEmptyReply(emptyList(), null, true))
        assertEquals(EmptyReplyKind.StreamCutWithoutSignal, classifyEmptyReply(emptyList(), "   ", true))
        // 有结束原因却没终止帧 —— 按官方时序正文其实已经发完，不算静默掐断
        assertEquals(EmptyReplyKind.UpstreamReturnedNothing, classifyEmptyReply(emptyList(), "end_turn", true))
        // 有终止帧、没结束原因 —— 也不是静默掐断
        assertEquals(EmptyReplyKind.UpstreamReturnedNothing, classifyEmptyReply(emptyList(), null, false))
    }

    @Test
    fun `refusal wins over truncation keywords so the wording stays honest`() {
        // 上游偶尔会给组合措辞，拦截类必须优先，否则会把拒答说成技术问题
        assertEquals(EmptyReplyKind.RefusedByPolicy, classifyEmptyReply(emptyList(), "max_tokens_refusal", true))
    }

    // ---------- 第 2 半：接线（读源码） ----------

    @Test
    fun `empty reply reporting is off by default on both entry points`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        val declarations = loop.lines().count { it.trim() == "reportEmptyReplyAsError: Boolean = false," }
        assertEquals("generateText 与 generateInternal 两处声明都必须默认 false", 2, declarations)
        assertTrue(
            "generateText 必须把参数一路传给 generateInternal（传丢就等于没接线）",
            loop.contains("reportEmptyReplyAsError = reportEmptyReplyAsError,"),
        )
    }

    @Test
    fun `only the main chat turns this on`() {
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        val opens = chat.lines().count { it.trim() == "reportEmptyReplyAsError = true," }
        assertEquals(
            "只有主对话那一个调用点能显式打开；圆桌/子代理/群组/压缩/翻译必须不传（隔离规矩）",
            1, opens,
        )
    }

    @Test
    fun `both the streaming and the non-streaming path carry the guard`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        val guards = loop.lines().count { it.trim() == "if (reportEmptyReplyAsError) {" }
        assertEquals(
            "流式与非流式两条路都要有守卫（审查位抓到：关掉流式输出时旧症状会原样存在）",
            2, guards,
        )
    }

    @Test
    fun `the notice exception is gated by the resend policy before the resume path`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        // v1011 那版是「一律原样上抛」；v1012 改成按「重发要不要再收一次钱」分档，
        // 但**位置不能变**：必须在「异常中断才续跑」那段之前，否则会被续跑通道吃掉。
        val catchLine = loop.lines().indexOfFirst {
            it.trim() == "if (error is EmptyReplyException && !error.kind.isWorthResending) throw error"
        }
        val resumeCount = loop.lines().indexOfFirst { it.contains("interruptedPartial.isNotBlank()") }
        assertTrue("找不到零产出说明的分档判定，接线可能被动过", catchLine >= 0)
        assertTrue("找不到「异常中断才续跑」的判据，接线可能被动过", resumeCount >= 0)
        assertTrue(
            "分档判定必须排在异常中断续跑之前",
            catchLine < resumeCount,
        )
    }

    @Test
    fun `the guard refuses to fire when the user cancelled`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        // 两处守卫前都必须先 ensureActive，否则用户点停止的那一瞬会被变成假报错 + 队列被暂停
        val lines = loop.lines()
        val guardIndexes = lines.indices.filter { lines[it].trim() == "if (reportEmptyReplyAsError) {" }
        assertEquals(2, guardIndexes.size)
        guardIndexes.forEach { i ->
            val nextTwo = lines.drop(i + 1).take(3).joinToString("\n")
            assertTrue(
                "守卫 ${i + 1} 行后面必须紧跟 ensureActive（取消不能变成空回报错）",
                nextTwo.contains("currentCoroutineContext().ensureActive()"),
            )
        }
    }

    @Test
    fun `the yardstick is hasVisibleAnswer and no dead message-dropping code comes back`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(
            "判据必须是 hasVisibleAnswer（思考不算答复）；只按 isEmptyUIMessage 判会让「有思考零正文」永远漏网",
            loop.contains("if (parts.hasVisibleAnswer()) return null"),
        )
        // 二审抓到 removeAt 那套是无效代码（Conversation.updateCurrentMessages 从不裁剪尾部节点），
        // 已经删掉。这条断言防止以后有人再把它「修回来」。
        assertFalse(
            "不许再用 removeAt 摘空助手消息（摘不掉，只会骗到读代码的人）",
            loop.contains("removeAt(assistantIndex)") || loop.contains("removeAt(idx)"),
        )
    }

    @Test
    fun `all six notices exist in both locales with the stop reason placeholder`() {
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        listOf(
            "chat_generation_empty_reply_budget",
            "chat_generation_empty_reply_cut",
            "chat_generation_empty_reply_refused",
            "chat_generation_empty_reply_thinking_only",
            "chat_generation_empty_reply_context_window",
            "chat_generation_empty_reply_none",
        ).forEach { key ->
            listOf(en, zh).forEach { text ->
                val line = text.lines().firstOrNull { it.contains("\"$key\"") }
                assertNotNull("找不到 $key 这一行", line)
                assertTrue("$key 必须带 %1\$s 结束原因占位符", line!!.contains("%1\$s"))
            }
        }
    }

    @Test
    fun `every kind maps to a notice resource`() {
        // 每个 kind 都必须有文案。以前这里写的是 `emptyReplyMessageRes(kind) != 0`，
        // 而 R 常量永远不为 0 —— 那是条不可能失败的假绿断言（审查位抓到）。
        // 换成能失败的写法：六个 kind 必须映射到六个互不相同的资源。
        val resIds = EmptyReplyKind.entries.map { emptyReplyMessageRes(it) }
        assertEquals("六个 kind 必须各自对应一条独立文案", resIds.size, resIds.distinct().size)
    }
}
