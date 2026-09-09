package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.runtime.AGENT_REPORT_LIST_ITEM_MAX_CHARS
import me.rerere.rikkahub.agent.runtime.AGENT_REPORT_LIST_MAX_ITEMS
import me.rerere.rikkahub.agent.runtime.AGENT_REPORT_MAX_CHARS
import me.rerere.rikkahub.agent.runtime.clipAgentReport
import me.rerere.rikkahub.agent.runtime.clipAgentReportText
import me.rerere.rikkahub.agent.runtime.extractAgentReportPayload
import me.rerere.rikkahub.agent.runtime.normalizeAgentReportText
import me.rerere.rikkahub.agent.runtime.parseAgentReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * v234 门禁：子代理报告「残缺而不自知」的闭环坏链必须保持修好。
 *
 * ## 病根（真机实测出来的）
 *
 * 子代理写完报告后，报告文本被本地 `take(4 * 1024)` 砍断，
 * 但**只有模型侧 finish_reason=length/max_tokens 才会置位 truncated**。
 * 于是本地砍断这条路整链都断：
 *
 * ```
 * 本地 take(...) 砍断
 *   → outcome.truncated 仍是 false
 *   → AgentThreadManager 落库 truncated=false
 *   → AgentThread.canResume 为 false（SUCCEEDED -> truncated）
 *   → AgentThreadManager.resume 直接 return null，界面不出现「继续输出」
 *   → wait_agents 只回 status=SUCCEEDED + 一份残缺 conclusion
 *   → 主线以为拿到了完整报告
 * ```
 *
 * 实测表现：一个四步任务，报告只回来前两步，第四步（最有价值的合并建议）
 * 一个字都没有，而线程状态显示「成功」。
 *
 * ## 修法
 *
 * 本地裁剪同样置位 truncated。因为 `truncated` 自 v222 起就是 `agent_threads`
 * 的独立数据库列，且 `canResume`、TRUNCATED 事件、界面按钮都已在读它，
 * 所以这一处赋值改对了，整条链自动全通，不需要新增列或迁移。
 */
class AgentReportTruncationTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative（repoRoot=${repoRoot.absolutePath}）")
        return file.readText()
    }

    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
    private val controlPath =
        "app/src/main/java/me/rerere/rikkahub/agent/tools/AgentControlTools.kt"

    // ------------------------------------------------------------ 上限本身

    @Test
    fun `报告上限必须明显大于旧的 4096`() {
        assertTrue(
            "AGENT_REPORT_MAX_CHARS 必须大于旧值 4096，实际 $AGENT_REPORT_MAX_CHARS",
            AGENT_REPORT_MAX_CHARS > 4 * 1024,
        )
        assertEquals("本版约定为 12K", 12 * 1024, AGENT_REPORT_MAX_CHARS)
    }

    // ------------------------------------------------------------ 头尾保留

    @Test
    fun `未超限的报告必须原样返回`() {
        val text = "结论：没问题。\n证据：文件 A 第 10 行。"
        assertEquals(text, clipAgentReportText(text, AGENT_REPORT_MAX_CHARS))
    }

    @Test
    fun `刚好等于上限的报告必须原样返回`() {
        val text = "x".repeat(100)
        assertEquals(text, clipAgentReportText(text, 100))
    }

    @Test
    fun `超限时必须同时保留开头与结尾并标明省略量`() {
        val head = "【开头】这里是第一步分析。"
        val tail = "【结尾】这里是第四步的合并建议，最关键。"
        val text = head + "填".repeat(5000) + tail

        val clipped = clipAgentReportText(text, 500)

        assertTrue("必须保留开头", clipped.startsWith("【开头】"))
        assertTrue("必须保留结尾（结论都在末尾）", clipped.endsWith(tail))
        assertTrue("必须标明中间省略", clipped.contains("中间省略"))
        assertTrue("结果长度不得超过上限，实际 ${clipped.length}", clipped.length <= 500)
    }

    @Test
    fun `上限为零或负数时返回空串`() {
        assertEquals("", clipAgentReportText("随便什么内容", 0))
        assertEquals("", clipAgentReportText("随便什么内容", -1))
    }

    @Test
    fun `上限小到装不下省略标记时退化为直接截取且不越界`() {
        val text = "a".repeat(1000)
        val clipped = clipAgentReportText(text, 10)
        assertEquals(10, clipped.length)
    }

    // ------------------------------------------------------------ 续跑链路

    private fun thread(status: AgentThreadStatus, truncated: Boolean) = AgentThread(
        conversationId = "conv-1",
        task = "任务",
        status = status,
        truncated = truncated,
        createdAt = Instant.now(),
    )

    @Test
    fun `成功但被截断必须允许续跑`() {
        assertTrue(thread(AgentThreadStatus.SUCCEEDED, truncated = true).canResume)
    }

    @Test
    fun `成功且完整不得允许续跑`() {
        assertFalse(thread(AgentThreadStatus.SUCCEEDED, truncated = false).canResume)
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `本地裁剪必须参与 truncated 判定`() {
        val backend = source(backendPath)
        assertTrue(
            "truncated 必须由整份报告的裁剪结果与模型侧共同决定",
            backend.contains("val (report, localTruncated) = clipAgentReport(parsed)"),
        )
        assertTrue(
            "v251：本地裁剪必须进入完整替换修复（不能当简单接尾）",
            backend.contains("AgentReportAssessment.REPAIR_REPLACE"),
        )
        assertTrue(
            "v251：truncated 由评估结果驱动（只有接尾/修复才置位）",
            backend.contains("val truncated = when (assessment) {"),
        )
        assertFalse(
            "不得再出现一刀切的 finalText.take(4 * 1024)",
            backend.contains("finalText.take(4 * 1024)"),
        )
    }

    @Test
    fun `报告出口必须走三级清洗`() {
        val backend = source(backendPath)
        assertTrue(
            "必须先剥标记再压空行",
            backend.contains("val payload = normalizeAgentReportText(extractAgentReportPayload(rawFinalText))"),
        )
        assertTrue(
            "必须尝试结构化解析",
            backend.contains("val parsed = parseAgentReport(payload)"),
        )
        assertFalse(
            "三个列表字段不得再被硬编码成空表",
            backend.contains("evidence = emptyList(),"),
        )
    }

    @Test
    fun `进度查询不得回传任务书全文`() {
        val control = source(controlPath)
        assertFalse(
            "wait_agents / list_agents 都不得再回传 task 全文",
            control.contains("put(\"task\", t.task)"),
        )
        assertEquals(
            "两个工具都必须改用预览",
            2,
            Regex("put\\(\"task_preview\", agentTaskPreview\\(t\\.task\\)\\)").findAll(control).count(),
        )
    }

    @Test
    fun `进度查询必须暴露截断与可续跑信号`() {
        val control = source(controlPath)
        assertTrue("必须暴露 truncated", control.contains("put(\"truncated\", t.truncated)"))
        assertTrue("必须暴露 can_resume", control.contains("put(\"can_resume\", t.canResume)"))
        assertTrue("wait_agents 必须暴露 report_chars", control.contains("put(\"report_chars\", conclusion.length)"))
        assertTrue("wait_agents 必须暴露 finish_reason", control.contains("put(\"finish_reason\", t.finishReason ?: \"\")"))
    }

    @Test
    fun `子代理默认仍是只读，写能力必须靠白名单显式授予`() {
        // v235 时这里的语义是「子代理永远不得写」。
        // v236 按用户要求放开了「编程位」，但边界改成了**白名单制**：
        // 只读工具文件里一个写工具都不许出现，写能力只在 AgentWritableTools 里，
        // 且白名单为空时一把都不装配（详见 AgentWritableToolsTest）。
        val readOnly = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentReadOnlyTools.kt")
        listOf(
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_shell",
            "workspace_publish_file",
        ).forEach { forbidden ->
            assertFalse("只读工具集不得出现 $forbidden", readOnly.contains("name = \"$forbidden\""))
        }
        // 无论有没有白名单，终端与编译能力一律不给
        val writable = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentWritableTools.kt")
        listOf("workspace_shell", "workspace_publish_file").forEach { forbidden ->
            assertFalse("写工具集也不得出现 $forbidden", writable.contains(forbidden))
        }
    }

    // ------------------------------------------------------- v235：报告边界标记

    @Test
    fun `有标记时只取标记内的正文`() {
        val raw = """
            Let me verify the line anchors first.
            <report>
            这才是正式报告。
            </report>
        """.trimIndent()
        val payload = extractAgentReportPayload(raw)
        assertEquals("这才是正式报告。", payload)
        assertFalse("思考过程必须被剥掉", payload.contains("Let me verify"))
    }

    @Test
    fun `出现多个标记块时取最后一个`() {
        val raw = "<report>示范格式</report>\n中间又想了想\n<report>最终结论</report>"
        assertEquals("最终结论", extractAgentReportPayload(raw))
    }

    @Test
    fun `没有标记时必须原样回退`() {
        val raw = "廉价模型没听话，直接写了正文。"
        assertEquals(raw, extractAgentReportPayload(raw))
    }

    @Test
    fun `标记内为空时必须回退到全文`() {
        val raw = "有内容但标记是空的<report>   </report>"
        assertEquals(raw, extractAgentReportPayload(raw))
    }

    @Test
    fun `连续空行必须被压掉`() {
        val raw = "第一段   \n\n\n\n\n第二段\n   \n\n第三段  "
        assertEquals("第一段\n\n第二段\n\n第三段", normalizeAgentReportText(raw))
    }

    // ------------------------------------------------------- v235：结构化解析

    @Test
    fun `合法 JSON 必须解析成四段`() {
        val json = """
            {
              "conclusion": "结论正文",
              "evidence": ["A.kt:10 —— val x = 1"],
              "uncertainties": ["没读到 B.kt"],
              "suggestions": ["建议补测试"]
            }
        """.trimIndent()
        val r = parseAgentReport(json)
        assertEquals("结论正文", r.conclusion)
        assertEquals(listOf("A.kt:10 —— val x = 1"), r.evidence)
        assertEquals(listOf("没读到 B.kt"), r.uncertainties)
        assertEquals(listOf("建议补测试"), r.suggestions)
    }

    @Test
    fun `带代码块围栏的 JSON 也要能解析`() {
        val fenced = "```json\n{\"conclusion\":\"围栏里的结论\"}\n```"
        assertEquals("围栏里的结论", parseAgentReport(fenced).conclusion)
    }

    @Test
    fun `非 JSON 文本整段进 conclusion`() {
        val text = "这是一段普通报告，没有 JSON。"
        val r = parseAgentReport(text)
        assertEquals(text, r.conclusion)
        assertTrue(r.evidence.isEmpty())
    }

    @Test
    fun `坏掉的 JSON 必须回退而不是抛异常`() {
        val broken = "{\"conclusion\": \"少了右括号"
        val r = parseAgentReport(broken)
        assertEquals(broken, r.conclusion)
    }

    // ------------------------------------------------------- v235：整份报告收束

    @Test
    fun `列表条数超限必须裁剪并置位截断`() {
        val many = (1..AGENT_REPORT_LIST_MAX_ITEMS + 5).map { "证据 $it" }
        val (clipped, truncated) = clipAgentReport(AgentReport(conclusion = "短", evidence = many))
        assertEquals(AGENT_REPORT_LIST_MAX_ITEMS, clipped.evidence.size)
        assertTrue("超条数必须算截断", truncated)
    }

    @Test
    fun `单条过长必须裁剪并置位截断`() {
        val long = "x".repeat(AGENT_REPORT_LIST_ITEM_MAX_CHARS + 500)
        val (clipped, truncated) = clipAgentReport(AgentReport(conclusion = "短", suggestions = listOf(long)))
        assertTrue(clipped.suggestions.single().length <= AGENT_REPORT_LIST_ITEM_MAX_CHARS)
        assertTrue("单条超长必须算截断", truncated)
    }

    @Test
    fun `都不超限时不得误报截断`() {
        val (clipped, truncated) = clipAgentReport(
            AgentReport(conclusion = "短结论", evidence = listOf("A.kt:1 —— ok"))
        )
        assertEquals("短结论", clipped.conclusion)
        assertEquals(listOf("A.kt:1 —— ok"), clipped.evidence)
        assertFalse("没超限就不该置位", truncated)
    }

    // ------------------------------------------------------- v235：提示词与回传契约

    @Test
    fun `系统提示词必须写明报告边界与证据要求`() {
        val backend = source(backendPath)
        assertTrue("必须要求用 report 标记包裹", backend.contains("<report> 与 </report>"))
        assertTrue(
            "必须说明标记外内容不会交给主代理",
            backend.contains("标记之外的一切内容"),
        )
        assertTrue("必须给出 JSON 结构范例", backend.contains("\\\"evidence\\\": [\\\"文件路径:行号"))
        assertTrue(
            "必须要求 evidence 带可核对位置",
            backend.contains("evidence 每一条都必须给出可核对的位置"),
        )
    }

    @Test
    fun `进度查询必须回传结构化四段`() {
        val control = source(controlPath)
        assertTrue("必须回传 evidence", control.contains("put(\"evidence\", JsonArray(report.evidence"))
        assertTrue("必须回传 uncertainties", control.contains("put(\"uncertainties\", JsonArray(report.uncertainties"))
        assertTrue("必须回传 suggestions", control.contains("put(\"suggestions\", JsonArray(report.suggestions"))
        assertTrue(
            "空列表不得输出，避免噪音",
            control.contains("if (report.evidence.isNotEmpty())"),
        )
    }
}
