package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_RESUME_CHECKPOINT
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_OMISSION_MARKER
import me.rerere.rikkahub.agent.runtime.AGENT_RESUME_INSTRUCTION
import me.rerere.rikkahub.agent.runtime.AGENT_RESUME_SUFFIX_INSTRUCTION
import me.rerere.rikkahub.agent.runtime.AGENT_RESUME_SUFFIX_INSTRUCTION_V251
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentReportAssessment
import me.rerere.rikkahub.agent.runtime.AgentResumeMode
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.assessAgentReport
import me.rerere.rikkahub.agent.runtime.buildAgentInputMessages
import me.rerere.rikkahub.agent.runtime.buildResumeInstruction
import me.rerere.rikkahub.agent.runtime.looksLikeAgentReportJson
import me.rerere.rikkahub.agent.runtime.mergeResumeText
import me.rerere.rikkahub.agent.runtime.resumeCheckpointEventId
import me.rerere.rikkahub.agent.runtime.resolveAgentResumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * v251 门禁：子代理报告「占位符被判成功」与「截断续跑后只回无需重复、导致原报告丢失」的修复。
 *
 * 覆盖：
 * - 报告评估器（纯逻辑）：占位符/无效 JSON/本地裁剪 → 修复；截断 → 接尾；元话语 → 无进展；
 * - 合并器：有效后缀合并、完整替换不拼接、重抄去重；
 * - 续跑模式推导（v254）：判定只看检查点 —— 手动停止时 reportJson 未落库也必须接着写；
 *   无检查点 → 兼容修复；
 * - 检查点：保存/读取/覆盖，且 eventsFlow 隐藏；
 * - 管理器行为：无进展落 FAILED 保留旧报告且不再自动循环；用户停止不自动续跑。
 */
class AgentResumeRecoveryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun thread(
        reportJson: String? = null,
        checkpoint: String? = null,
    ) = AgentThread(
        id = "t1",
        conversationId = "c1",
        task = "任务",
        reportJson = reportJson,
        createdAt = Instant.now(),
    )

    // ------------------------------------------------------------ 占位符不能成功

    @Test
    fun `整段模板占位不能判成功`() {
        // 非续跑轮，模型只回了占位文字（真机抓到的那条 12 字符交白卷）
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", "（结论待数据收集后填写）", "stop", false, false),
        )
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", "待补充", "stop", false, false),
        )
        // 占位写成了结构化 JSON 也一样不能成功
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", """{"conclusion": "待填写"}""", "stop", false, false),
        )
        // 续跑轮回占位同样不能成功（且不是无进展，因为它在尝试交稿）
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("""{"conclusion": "旧结论"}""", "（结论待数据收集后填写）", "stop", true, false),
        )
    }

    // ------------------------------------------------------------ 短但真实的完整报告可以成功

    @Test
    fun `短但真实的完整报告可以成功`() {
        // 非续跑轮：纯文本真实报告（含「无需重复修改」字样的正常结论不能被误杀）
        assertEquals(
            AgentReportAssessment.COMPLETE,
            assessAgentReport("", "两处改动均已存在，无需重复修改。", "stop", false, false),
        )
        // 结构化短报告（resume 轮也一样）
        assertEquals(
            AgentReportAssessment.REPLACE_COMPLETE,
            assessAgentReport(
                "旧的半截JSON",
                """{"conclusion": "已完成核对", "evidence": ["A.kt:1"]}""",
                "stop",
                true,
                false,
            ),
        )
        // 续跑轮：新输出能构成完整报告 → 成功（不会被「短」卡掉）
        assertEquals(
            AgentReportAssessment.COMPLETE,
            assessAgentReport(
                """{"conclusion": "前半", "evidence": ["E1"]""",
                ", \"uncertainties\": [], \"suggestions\": []}",
                "stop",
                true,
                false,
            ),
        )
    }

    // ------------------------------------------------------------ 完整替换不重复

    @Test
    fun `新一轮单独是完整报告时直接替换绝不与旧稿拼接`() {
        val carried = """{"conclusion": "旧报告", "evidence": ["旧证据"]}"""
        val fresh = """{"conclusion": "新报告", "evidence": ["新证据"]}"""
        assertEquals(
            AgentReportAssessment.REPLACE_COMPLETE,
            assessAgentReport(carried, fresh, "stop", true, false),
        )
        // 合并器本身在 REPLACE 场景下不会被用到（评估器先拦截），
        // 但即使被误调，也不该把两份完整 JSON 拼在一起。
        val merged = mergeResumeText(carried, fresh)
        assertFalse("两份完整报告绝不能拼在一起", looksLikeAgentReportJson(merged))
    }

    // ------------------------------------------------------------ 有效后缀合并

    @Test
    fun `有效后缀与检查点合并成完整报告`() {
        val carried = """{"conclusion": "开头", "evidence": ["e1"]"""
        val fresh = """, "uncertainties": [], "suggestions": []}"""
        assertEquals(
            AgentReportAssessment.COMPLETE,
            assessAgentReport(carried, fresh, "stop", true, false),
        )
        val merged = mergeResumeText(carried, fresh)
        assertTrue("合并结果必须是合法 JSON", looksLikeAgentReportJson(merged))
        // 无缝拼接：半截 JSON 续写中间不能插换行
        assertEquals(
            """{"conclusion": "开头", "evidence": ["e1"], "uncertainties": [], "suggestions": []}""",
            merged,
        )
    }

    @Test
    fun `合并时去掉新输出重抄的旧结尾`() {
        // 廉价模型续跑时把旧结尾重抄了一遍 → 只保留增量
        assertEquals(
            "第一段\n第二段\n补充",
            mergeResumeText("第一段\n第二段", "第二段 补充"),
        )
        assertEquals(
            "{\"conclusion\": \"x\"}",
            mergeResumeText("{\"conclusion\": \"x\"}", "{\"conclusion\": \"x\"}"),
        )
    }

    // ------------------------------------------------------------ 无需重复 → 无进展

    @Test
    fun `续跑只回无需重复时判无进展`() {
        val carried = """{"conclusion": "半截结论"}"""
        assertEquals(
            AgentReportAssessment.NO_PROGRESS,
            assessAgentReport(carried, "无需重复", "stop", true, false),
        )
        assertEquals(
            AgentReportAssessment.NO_PROGRESS,
            assessAgentReport(carried, "上一轮已完成，无需再写。", "stop", true, false),
        )
        assertEquals(
            AgentReportAssessment.NO_PROGRESS,
            assessAgentReport(carried, "", "stop", true, false),
        )
    }

    // ------------------------------------------------------------ 本地裁剪 → 修复

    @Test
    fun `本地裁剪省略标记必须走完整替换修复`() {
        val clipped = "开头" + AGENT_OMISSION_MARKER + "结尾"
        // localTruncated 置位
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", clipped, "stop", false, true),
        )
        // 省略标记出现在合并文本里（即使没显式置位）
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", clipped, "stop", false, false),
        )
    }

    // ------------------------------------------------------------ 截断 → 接尾

    @Test
    fun `provider 截断或工具调用收尾或未闭合时走接尾续写`() {
        val half = """{"conclusion": "半截"""
        assertEquals(
            AgentReportAssessment.CONTINUE_SUFFIX,
            assessAgentReport("", half, "max_tokens", false, false),
        )
        assertEquals(
            AgentReportAssessment.CONTINUE_SUFFIX,
            assessAgentReport("", half, "tool_calls", false, false),
        )
        assertEquals(
            AgentReportAssessment.CONTINUE_SUFFIX,
            assessAgentReport("", "<report>{\"conclusion\": \"没闭合", "length", false, false),
        )
        // 空报告 + 截断 → 接尾（不是修复）
        assertEquals(
            AgentReportAssessment.CONTINUE_SUFFIX,
            assessAgentReport("", "", "max_tokens", false, false),
        )
        // 空报告 + 自然结束 → 修复
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", "", "stop", false, false),
        )
    }

    // ------------------------------------------------------------ 无效 JSON → 修复

    @Test
    fun `坏掉的 JSON 自然结束时走完整替换修复`() {
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("", """{"conclusion": "少了右括号""", "stop", false, false),
        )
        // 续跑轮合并后仍不是合法 JSON → 修复（不能把纯文本当成功）
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport("""{"conclusion": "半截""", "随便补了一段话", "stop", true, false),
        )
    }

    // ------------------------------------------------------------ 续跑模式推导

    @Test
    fun `旧线程无检查点只能走兼容修复`() {
        // 没有检查点 → 无论 reportJson 什么样都按 REPAIR（历史可能被界面裁剪过）
        assertEquals(AgentResumeMode.REPAIR, resolveAgentResumeMode(thread(reportJson = null), null))
        assertEquals(
            AgentResumeMode.REPAIR,
            resolveAgentResumeMode(
                thread(reportJson = AgentReport.encode(AgentReport(conclusion = "看似完整"))),
                null,
            ),
        )
    }

    @Test
    fun `手动停止后reportJson为空但检查点有真实内容必须接着写`() {
        // v254 核心场景（真机缺陷）：用户手动停止时报告还没落库 —— reportJson 只在正常
        // 收尾分支写入，此时为 null/空/无法解码；但检查点里存着断点前写过的全部原文。
        // 判定看的是检查点 → 必须 SUFFIX 从断点接着写，绝不能再退回「重新生成」（REPAIR）。
        val checkpoint = """{"conclusion": "写到一半的结论", "evidence": ["A.kt:1 —— 已核对"""
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(thread(reportJson = null), checkpoint),
        )
        // reportJson 是空串（同样未落库）也一样
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(thread(reportJson = ""), checkpoint),
        )
        // reportJson 解析不出也不能改变判定（判定依据是检查点，不是 reportJson）
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(thread(reportJson = "不是JSON"), checkpoint),
        )
        // 检查点是被截断的半截纯文本输出（非 JSON）→ 同样接着写
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(thread(reportJson = null), "已读完前两个文件，正准备核对第三处改动"),
        )
    }

    @Test
    fun `检查点是占位符废稿仍走修复而正文引用省略标记不受影响`() {
        // 检查点整体是模板占位 → 废稿接尾没意义 → 修复
        assertEquals(
            AgentResumeMode.REPAIR,
            resolveAgentResumeMode(thread(reportJson = null), "（结论待数据收集后填写）"),
        )
        // v254：检查点是无损原文，永远不会自带省略标记；出现这串字只可能是模型逐字写了它
        // （子代理审查裁剪相关源码时真的会发生）。断点原文完好，必须接着写，
        // 不能退化成「重新交一份完整报告」。
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(
                thread(reportJson = null),
                "我核对了裁剪逻辑，它会写入 ${AGENT_OMISSION_MARKER} 这样的标记，接下来看第二处改动",
            ),
        )
        // 对照：同样的 reportJson（占位报告），检查点换成真实半截报告 → 接尾。
        // 说明判定确实只看检查点内容，reportJson 里是什么结构化报告完全不影响结果。
        assertEquals(
            AgentResumeMode.SUFFIX,
            resolveAgentResumeMode(
                thread(reportJson = AgentReport.encode(AgentReport(conclusion = "待补充"))),
                """{"conclusion": "半截", "evidence": ["e1"""",
            ),
        )
    }

    // ------------------------------------------------------------ v254：接尾模式收尾判定

    @Test
    fun `接尾模式下有实质内容但没按格式包不再触发完整重写`() {
        // 场景：用户手动停止 → 点继续输出 → 模型接着写了实质内容，但没用结构化格式包起来。
        // v251 会判 REPAIR_REPLACE，于是自动再跑一轮「重新交一份完整报告」——
        // 内容明明已经在手上，用户看到的还是「重新生成」，还多花一趟钱。
        assertEquals(
            AgentReportAssessment.COMPLETE,
            assessAgentReport(
                carriedText = "已经读完前两个文件，确认改动在位。",
                freshText = "第三处也核对完了，三处改动全部一致，没有发现遗漏。",
                finishReason = "stop",
                isResume = true,
                localTruncated = false,
                resumeMode = AgentResumeMode.SUFFIX,
            ),
        )
    }

    @Test
    fun `修复模式下没按格式包仍要求重交替换稿`() {
        // 对照：修复模式要的本来就是一份结构化替换稿，行为一个字都不能变。
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport(
                carriedText = "旧的半截内容",
                freshText = "又写了一段没有结构的散文，仍然不是可解析报告。",
                finishReason = "stop",
                isResume = true,
                localTruncated = false,
                resumeMode = AgentResumeMode.REPAIR,
            ),
        )
    }

    @Test
    fun `接尾模式的宽松收尾不会放过空白与元话语`() {
        // 只回元话语 → 仍然判无进展（保留旧报告），宽松收尾绝不能把它放过去
        assertEquals(
            AgentReportAssessment.NO_PROGRESS,
            assessAgentReport(
                carriedText = "已有内容",
                freshText = "上一轮已完整输出，无需重复。",
                finishReason = "stop",
                isResume = true,
                localTruncated = false,
                resumeMode = AgentResumeMode.SUFFIX,
            ),
        )
        // 本地裁剪留下省略标记 → 仍然要求完整替换修复（裁剪稿不能靠接尾修）
        assertEquals(
            AgentReportAssessment.REPAIR_REPLACE,
            assessAgentReport(
                carriedText = "已有内容",
                freshText = "接着写的后半段",
                finishReason = "stop",
                isResume = true,
                localTruncated = true,
                resumeMode = AgentResumeMode.SUFFIX,
            ),
        )
    }

    // ------------------------------------------------------------ 双模式提示词

    @Test
    fun `两种续跑提示词语义分离`() {
        val suffix = buildResumeInstruction(AgentResumeMode.SUFFIX)
        assertTrue("接尾模式必须要求只补缺失的后半段", suffix.contains("只补缺失的后半段"))
        assertTrue("接尾模式必须禁止只回无需重复", suffix.contains("无需重复"))
        // v254：接尾指令绝不能再给「重交一份完整报告」的出口 —— 那句话是用户真机
        // 「点继续输出却像重新生成」的主因（提示词邀请模型重做一遍，等于付两次钱）。
        assertFalse(
            "接尾模式不得授权重交完整报告",
            suffix.contains("就交一份完整、紧凑、可解析的报告"),
        )
        assertFalse(
            "接尾模式不得预告机器会当替换稿处理",
            suffix.contains("当替换稿处理"),
        )
        assertTrue("接尾模式必须明说机器会自动接到前半段后面", suffix.contains("接到前半段后面"))
        assertTrue("接尾模式必须禁止重抄前面内容", suffix.contains("不要重抄"))
        // v251 旧文案必须原样留着（只用于认历史），且必须与新文案不同
        assertTrue(
            "v251 旧文案必须保留原样，供历史消息识别",
            AGENT_RESUME_SUFFIX_INSTRUCTION_V251.contains("机器会把它当替换稿处理"),
        )
        assertFalse("新旧文案不能相同", suffix == AGENT_RESUME_SUFFIX_INSTRUCTION_V251)

        val repair = buildResumeInstruction(AgentResumeMode.REPAIR)
        assertTrue("修复模式必须要求完整替换", repair.contains("完整、紧凑、可解析"))
        assertTrue("修复模式必须禁止只解释上一轮已完成", repair.contains("上一轮已完成"))
        assertTrue("修复模式必须说明会替换旧报告", repair.contains("替换报告"))
    }

    // ------------------------------------------------------------ 续跑输入回灌（v251 回归）

    @Test
    fun `有检查点的续跑输入包含检查点所有用户补充指令且只追加一条续跑指令`() {
        val checkpoint = "完整原文检查点"
        val history = listOf(
            AgentMessage(threadId = "t1", role = "user", content = "补充指令一"),
            AgentMessage(threadId = "t1", role = "assistant", content = "旧输出（不是补充指令）"),
            AgentMessage(threadId = "t1", role = "user", content = "补充指令二"),
            // 内容恰好等于旧/新续跑指令模板的 user 消息：不是补充指令，必须被滤掉
            AgentMessage(threadId = "t1", role = "user", content = AGENT_RESUME_INSTRUCTION),
            AgentMessage(threadId = "t1", role = "user", content = AGENT_RESUME_SUFFIX_INSTRUCTION),
            AgentMessage(threadId = "t1", role = "user", content = "   "),
        )
        val resumeInstruction = buildResumeInstruction(AgentResumeMode.SUFFIX)
        val messages = buildAgentInputMessages(
            thread = thread(reportJson = AgentReport.encode(AgentReport(conclusion = "半截"))),
            history = history,
            resume = true,
            resumeMode = AgentResumeMode.SUFFIX,
            checkpoint = checkpoint,
        )

        // 顺序：system -> task(user) -> assistant(checkpoint) -> user 补充指令（按落库顺序）-> 唯一续跑指令
        assertEquals(
            listOf(
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.USER,
                MessageRole.USER,
            ),
            messages.map { it.role },
        )
        // 检查点内容完整回灌（不裁剪、不截断）
        assertEquals(checkpoint, messages[2].toText())
        // 两条 user 补充指令按落库顺序都在
        assertEquals("补充指令一", messages[3].toText())
        assertEquals("补充指令二", messages[4].toText())
        // 恰好一条当前续跑指令，且是最后一条；指令模板历史与空白 user 消息都没有混入
        assertEquals(resumeInstruction, messages[5].toText())
        assertEquals(
            "续跑指令必须恰好一条",
            1,
            messages.count { it.role == MessageRole.USER && it.toText() == resumeInstruction },
        )
        // 旧 assistant 输出不混进输入
        assertFalse(messages.any { it.toText().contains("旧输出") })
    }

    // ------------------------------------------------------------ 检查点存取与隐藏

    @Test
    fun `检查点保存读取覆盖且事件流隐藏`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        repo.insertEvent(
            AgentEvent(
                threadId = "t1",
                type = AGENT_EVENT_TYPE_RESUME_CHECKPOINT,
                detail = "完整原文A",
                createdAt = Instant.now(),
            )
        )
        repo.insertEvent(
            AgentEvent(threadId = "t1", type = "PROGRESS", detail = "可见事件", createdAt = Instant.now())
        )
        assertEquals("完整原文A", repo.resumeCheckpoint("t1"))
        // eventsFlow 必须隐藏检查点（UI 看不到）
        val visible = repo.eventsFlow("t1").first().map { it.type }
        assertEquals(listOf("PROGRESS"), visible)
        // 稳定 id 覆盖写（流式更新 = 反复 upsert 同一条）
        repo.insertEvent(
            AgentEvent(
                id = resumeCheckpointEventId("t1"),
                threadId = "t1",
                type = AGENT_EVENT_TYPE_RESUME_CHECKPOINT,
                detail = "完整原文B",
                createdAt = Instant.now().plusSeconds(1),
            )
        )
        assertEquals("完整原文B", repo.resumeCheckpoint("t1"))
        // 没有检查点的线程返回 null
        assertEquals(null, repo.resumeCheckpoint("t2"))
    }

    // ------------------------------------------------------------ 管理器行为

    /** 可编程后端：按顺序吐 outcome；队列耗尽后返回完整成功。 */
    private class ScriptedBackend(
        private val outcomes: List<AgentRunOutcome>,
    ) : AgentBackend {
        private val queue = ArrayDeque(outcomes)

        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            if (resume) {
                onEvent(AgentEvent(threadId = thread.id, type = "RESUMED", detail = "resumed"))
            }
            return queue.removeFirstOrNull()
                ?: AgentRunOutcome(
                    report = AgentReport(conclusion = "fallback"),
                    finishReason = "stop",
                    truncated = false,
                    assessment = AgentReportAssessment.COMPLETE,
                )
        }
    }

    @Test
    fun `续跑只回无需重复时保留旧报告落失败且不再自动循环`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(
            listOf(
                AgentRunOutcome(
                    report = AgentReport(conclusion = "（结论待数据收集后填写）"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                ),
                AgentRunOutcome(
                    report = AgentReport(conclusion = "无需重复"),
                    finishReason = "stop",
                    truncated = false,
                    assessment = AgentReportAssessment.NO_PROGRESS,
                    resumeMode = AgentResumeMode.REPAIR,
                ),
            )
        )
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4, autoResumeMax = { 2 })
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "t"))
        // 第一轮占位 → 自动修复一次 → 第二轮「无需重复」→ 落 FAILED
        withTimeout(40_000) {
            while (repo.thread(thread.id)?.status != AgentThreadStatus.FAILED) delay(20)
        }
        // 再等一个退避窗口，确认它不会第三次自动续跑
        delay(6_000)
        val final = repo.thread(thread.id)!!
        assertEquals("无进展必须落 FAILED，不能标成功", AgentThreadStatus.FAILED, final.status)
        assertEquals("最多只自动续了一次", 1, final.resumeCount)
        assertFalse("无进展后不得再置位 truncated", final.truncated)
        // 「无需重复」绝不能覆盖已有的报告（第一轮的占位报告被保留）
        val kept = AgentReport.decode(final.reportJson)
        assertNotNull("旧报告必须被保留", kept)
        assertEquals("（结论待数据收集后填写）", kept!!.conclusion)
        assertTrue("失败必须允许用户手动继续或换模型", final.canResume)
    }

    @Test
    fun `占位符只给一次完整替换修复机会且修复成功`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(
            listOf(
                AgentRunOutcome(
                    report = AgentReport(conclusion = "（结论待数据收集后填写）"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                ),
                AgentRunOutcome(
                    report = AgentReport(
                        conclusion = "完整修复后的结论",
                        evidence = listOf("A.kt:1 —— ok"),
                    ),
                    finishReason = "stop",
                    truncated = false,
                    assessment = AgentReportAssessment.COMPLETE,
                    resumeMode = AgentResumeMode.REPAIR,
                ),
            )
        )
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4, autoResumeMax = { 2 })
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "t"))
        withTimeout(40_000) {
            while (repo.thread(thread.id)?.status != AgentThreadStatus.SUCCEEDED ||
                repo.thread(thread.id)!!.resumeCount < 1
            ) {
                delay(20)
            }
        }
        val final = repo.thread(thread.id)!!
        assertEquals(AgentThreadStatus.SUCCEEDED, final.status)
        assertEquals(1, final.resumeCount)
        assertFalse(final.truncated)
        assertEquals("完整修复后的结论", AgentReport.decode(final.reportJson)?.conclusion)
    }

    @Test
    fun `截断后用户停止不自动续跑`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(
            listOf(
                AgentRunOutcome(
                    report = AgentReport(conclusion = "半截"),
                    finishReason = "length",
                    truncated = true,
                    assessment = AgentReportAssessment.CONTINUE_SUFFIX,
                ),
            )
        )
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4, autoResumeMax = { 2 })
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "t"))
        // 等第一轮落 SUCCEEDED（自动接尾已排程、在退避中）
        withTimeout(15_000) {
            while (repo.thread(thread.id)?.status != AgentThreadStatus.SUCCEEDED) delay(20)
        }
        // 用户立刻停止：退避中的自动续跑必须被掐掉
        manager.stop(thread.id)
        // 再等一整个退避窗口（4 秒）加余量，确认它没有自己复活接着跑
        delay(8_000)
        val final = repo.thread(thread.id)!!
        assertEquals("停止后绝不允许自动续跑", 0, final.resumeCount)
        assertEquals("线程保持第一轮的截断成功态（用户可手动继续）", AgentThreadStatus.SUCCEEDED, final.status)
    }

    @Test
    fun `修复轮再次失败时不再自动循环`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(
            listOf(
                AgentRunOutcome(
                    report = AgentReport(conclusion = "待补充"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                ),
                AgentRunOutcome(
                    report = AgentReport(conclusion = "还是待补充"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                    resumeMode = AgentResumeMode.REPAIR,
                ),
            )
        )
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4, autoResumeMax = { 2 })
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "t"))
        // 第一轮占位 → 自动修复一次 → 修复轮仍是占位 → 落 FAILED 停止循环
        withTimeout(40_000) {
            while (repo.thread(thread.id)?.status != AgentThreadStatus.FAILED) delay(20)
        }
        delay(6_000)
        val final = repo.thread(thread.id)!!
        assertEquals(AgentThreadStatus.FAILED, final.status)
        assertEquals("修复失败后不得再续跑", 1, final.resumeCount)
    }

    @Test
    fun `修复轮被派生成接尾模式时机会仍然只有一次`() = runBlocking {
        // v254 回归：续跑模式改由检查点推导后，修复轮也可能被派生成 SUFFIX。
        // 此时「本次是不是 REPAIR 模式」这个推断失效，必须靠独立计数兜住
        // 「最多自动修一次」，否则会多烧几趟调用。
        val repo = InMemoryAgentThreadRepository()
        val backend = ScriptedBackend(
            listOf(
                AgentRunOutcome(
                    report = AgentReport(conclusion = "待补充"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                ),
                AgentRunOutcome(
                    report = AgentReport(conclusion = "还是待补充"),
                    finishReason = "stop",
                    truncated = true,
                    assessment = AgentReportAssessment.REPAIR_REPLACE,
                    // 关键差别：这一轮报的是 SUFFIX（v254 由检查点派生出来的模式）
                    resumeMode = AgentResumeMode.SUFFIX,
                ),
            )
        )
        val manager = AgentThreadManager(repo, backend, scope, maxConcurrent = 4, autoResumeMax = { 2 })
        val thread = manager.spawn(AgentSpawnRequest(conversationId = "conv-1", task = "t"))
        withTimeout(40_000) {
            while (repo.thread(thread.id)?.status != AgentThreadStatus.FAILED) delay(20)
        }
        delay(6_000)
        val final = repo.thread(thread.id)!!
        assertEquals(AgentThreadStatus.FAILED, final.status)
        assertEquals("派生成接尾模式也只能修一次", 1, final.resumeCount)
    }
}
