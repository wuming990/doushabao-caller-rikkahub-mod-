package me.rerere.rikkahub.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.agent.model.AgentErrorKind
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.InMemoryAgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AGENT_THREAD_TIMEOUT_MAX_MINUTES
import me.rerere.rikkahub.agent.runtime.AgentAttemptTimeoutException
import me.rerere.rikkahub.agent.runtime.AgentBackend
import me.rerere.rikkahub.agent.runtime.AgentRunOutcome
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * v238 门禁：卡住要能被发现、能被换模型救回来。
 *
 * ## 用户真机反馈原话
 *
 * > 「子代理卡半天我很难判断是不是卡住了，我想直接切换另一个子代理续跑目前能不能行？」
 * > 「当前子代理模型两次失败就跳转下一个子代理。」
 *
 * v237 之前的三个真实缺口：
 * 1. **单独派发的子代理完全没有超时**（只有流水线的每一棒有 900 秒上限），
 *    上游挂住连接就无限期挂着，死线程一直占着并发额度；
 * 2. **界面不显示任何时长**，状态栏只有「正在查证...」，跑 10 秒和跑 10 分钟长得一样；
 * 3. **没有任何地方能改已存在线程的模型** —— 主模型 spawn 时点名过的模型永远排在
 *    候选链第一位，改全局设置也挤不掉，用户想「换个模型接着跑」做不到。
 */
class AgentTimeoutAndModelSwitchTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
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

    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
    private val managerPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt"
    private val storePath =
        "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"

    // -------------------------------------------------- 同一个模型试两次才换下一个

    @Test
    fun `同一个模型必须先重试若干次才换下一个`() {
        // v270：用户拍板合并——「续跑次数没了直接换下个模型就行了，不用额外设置一个按键」。
        // 同模型重试次数复用「自动续跑次数」（Settings.agentAutoResumeMax），
        // 该值为 0 时至少保留首次尝试（coerceAtLeast(1)），否则整条模型链一个都不会跑。
        val backend = source(backendPath)
        assertTrue(
            "必须有「同模型重试」的内层循环，否则还是一次就换人",
            backend.contains("for (attempt in 1..modelAttempts)"),
        )
        assertTrue(
            "循环上界必须复用「自动续跑次数」设置，不再有独立旋钮",
            backend.contains("val modelAttempts = settings.agentAutoResumeMax"),
        )
        assertTrue(
            "设置为 0（不自动续跑）时必须保底 1 次尝试，否则整条模型链都不会跑",
            backend.contains(".coerceAtLeast(1)"),
        )
        assertTrue(
            "不得残留已删除的独立设置项（v270 合并，用户拍板）",
            !backend.contains("agentModelAttempts"),
        )
        assertTrue(
            "重试之间必须等一会儿：限流类错误立刻重撞没有意义",
            backend.contains("kotlinx.coroutines.delay(AGENT_MODEL_RETRY_DELAY_MILLIS)"),
        )
        assertTrue(
            "重试必须发可观察事件，否则用户又是一片空白",
            backend.contains("\"MODEL_RETRY\""),
        )
    }

    @Test
    fun `换模型的事件文案必须说清是连试几次才换的`() {
        val backend = source(backendPath)
        assertTrue(
            backend.contains("上一个模型连试 \$modelAttempts 次都失败"),
        )
    }

    /**
     * v269：超时卡住必须立刻换下一个模型，不在同一个模型上耗重试次数。
     *
     * 用户原话：「换成只有到达超时时间没反应才会自动切换其他模型，其他情况下继续续跑，
     * 直到续跑次数没了再切换其他模型」。
     *
     * 这条断言同时是防覆盖保险丝：合并上游后若 break 丢失，超时会退回「同模型再等一轮」，
     * 用户会重新遇到「卡住了还在同一个模型上干等」。
     */
    @Test
    fun `超时卡住必须立刻换模型而不是耗完重试次数`() {
        val backend = source(backendPath)
        assertTrue(
            "超时分支必须以 break 跳出内层重试循环，直接换下一个候选模型",
            backend.contains("if (progress.producedOutput) throw timeout") &&
                backend.substringAfter("if (progress.producedOutput) throw timeout")
                    .substringBefore("} catch (e: AgentAttemptFailure)")
                    .contains("break"),
        )
        assertTrue(
            "已产出内容时仍必须交给续跑机制，不得被 break 抢走",
            backend.contains("if (progress.producedOutput) throw timeout"),
        )
    }

    @Test
    fun `已经吐出内容就绝不重试也绝不换模型`() {
        val backend = source(backendPath)
        // 两条路都要守：普通失败走 AgentAttemptFailure，超时走 progress
        assertTrue(
            "普通失败路径",
            backend.contains("if (e.producedOutput) throw e.failure"),
        )
        assertTrue(
            "超时路径 —— 少这一行会造成同一份内容被写两遍",
            backend.contains("if (progress.producedOutput) throw timeout"),
        )
    }

    // -------------------------------------------------------------- 超时

    @Test
    fun `超时必须按每次尝试算而不是按整条线程算`() {
        val backend = source(backendPath)
        assertTrue(
            "超时必须套在 runWithModel 这一层（每次尝试），套在整条线程外面会把重试与换模型的机会一起吃掉",
            backend.contains("kotlinx.coroutines.withTimeoutOrNull(attemptTimeoutMillis)"),
        )
        assertTrue(
            "超时上界必须与设置层一致",
            backend.contains("coerceIn(0, AGENT_THREAD_TIMEOUT_MAX_MINUTES)"),
        )
        assertEquals(120, AGENT_THREAD_TIMEOUT_MAX_MINUTES)
    }

    @Test
    fun `超时为零必须等于不限时保持老行为`() {
        val backend = source(backendPath)
        assertTrue(
            "必须显式分岔：0 时直接跑，不套超时",
            backend.contains("if (attemptTimeoutMillis > 0L)"),
        )
    }

    @Test
    fun `超时错误必须被判成可恢复否则自动续跑不会触发`() {
        val message = AgentAttemptTimeoutException(10).message
        assertNotNull(message)
        assertTrue("消息里必须带 timeout 字样", message!!.contains("timeout"))
        assertEquals(
            "判成 FATAL 或 UNKNOWN 的话，卡死之后不会自动续跑，等于白加超时",
            AgentErrorKind.RECOVERABLE,
            AgentErrorKind.classify(message),
        )
    }

    @Test
    fun `超时必须发出可观察事件`() {
        val backend = source(backendPath)
        assertTrue(backend.contains("\"TIMEOUT\""))
        assertTrue(backend.contains("按卡死处理"))
    }

    @Test
    fun `超时设置必须读也必须写否则用户配好的会自己消失`() {
        val store = source(storePath)
        assertTrue(
            "缺少 DataStore key",
            store.contains("val AGENT_THREAD_TIMEOUT = intPreferencesKey(\"agent_thread_timeout_minutes\")"),
        )
        assertTrue(
            "缺少读回",
            store.contains("agentThreadTimeoutMinutes = (preferences[AGENT_THREAD_TIMEOUT] ?: 10).coerceIn(0, 120)"),
        )
        assertTrue(
            "缺少落盘 —— v219 就是因为只读不写让用户配好的设置自己变回默认（v220 才修）",
            store.contains("preferences[AGENT_THREAD_TIMEOUT] = settings.agentThreadTimeoutMinutes.coerceIn(0, 120)"),
        )
        assertTrue(
            "Settings 字段必须有默认值，否则旧备份反序列化会炸",
            store.contains("val agentThreadTimeoutMinutes: Int = 10,"),
        )
    }

    // ------------------------------------------------------- 换模型继续（行为测试）

    @Test
    fun `换模型继续必须真的把模型钉进线程并且续跑`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = AgentThreadManager(repo, OkBackend(), scope, maxConcurrent = 4)

        // 造一条「失败可续跑」的线程，模型是主模型 spawn 时点名的那个
        val failed = AgentThread(
            id = "t-1",
            conversationId = "conv-1",
            task = "随便查一下",
            modelId = "原来点名的坏模型",
            status = AgentThreadStatus.FAILED,
            error = "429 too many requests",
            createdAt = Instant.now(),
        )
        repo.insertThread(failed)

        val revived = mgr.resumeWithModel("t-1", "用户换上的好模型")
        assertNotNull("应该真的续跑起来", revived)

        val settled = withTimeout(15_000) {
            var current = repo.thread("t-1")
            while (current == null || current.status != AgentThreadStatus.SUCCEEDED) {
                delay(20)
                current = repo.thread("t-1")
            }
            current
        }
        assertEquals(
            "换上的模型必须被钉进线程，否则候选链第一棒还是那个坏模型",
            "用户换上的好模型",
            settled.modelId,
        )
        assertEquals("续跑次数要 +1", 1, settled.resumeCount)
    }

    @Test
    fun `不能续跑的线程不允许换模型硬启`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = AgentThreadManager(repo, OkBackend(), scope, maxConcurrent = 4)
        // SUCCEEDED 且没被截断 → canResume 为 false
        repo.insertThread(
            AgentThread(
                id = "t-2",
                conversationId = "conv-1",
                task = "已经做完了",
                status = AgentThreadStatus.SUCCEEDED,
                truncated = false,
                createdAt = Instant.now(),
            )
        )

        assertNull(
            "已经完整成功的线程不该被换模型重跑，那是无意义的重复收费",
            mgr.resumeWithModel("t-2", "别的模型"),
        )
        assertNull("模型也不该被偷偷改掉", repo.thread("t-2")?.modelId)
    }

    @Test
    fun `空模型 id 必须被拒绝`() = runBlocking {
        val repo = InMemoryAgentThreadRepository()
        val mgr = AgentThreadManager(repo, OkBackend(), scope, maxConcurrent = 4)
        repo.insertThread(
            AgentThread(
                id = "t-3",
                conversationId = "conv-1",
                task = "x",
                modelId = "原模型",
                status = AgentThreadStatus.STOPPED,
                createdAt = Instant.now(),
            )
        )

        assertNull(mgr.resumeWithModel("t-3", "   "))
        assertEquals("原模型", repo.thread("t-3")?.modelId)
    }

    @Test
    fun `换模型必须发出可观察事件`() {
        val manager = source(managerPath)
        assertTrue(manager.contains("\"MODEL_SWITCHED\""))
        assertTrue(
            "事件要说清已产出内容会被保留，否则用户不敢点",
            manager.contains("已产出内容全部保留"),
        )
    }

    @Test
    fun `换模型不能自己重新拿锁否则死锁`() {
        val manager = source(managerPath)
        assertTrue(
            "必须显式注明 mutex 不可重入 —— 在锁内调 resume 会直接死锁，这个坑不留注释后人一定会踩",
            manager.contains("mutex 不可重入"),
        )
    }

    // ------------------------------------------------------------ 界面入口

    @Test
    fun `详情页必须显示跑了多久和多久没有新内容`() {
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue("缺少已跑时长", page.contains("已跑 "))
        assertTrue("缺少静默时长 —— 这才是判断卡没卡的关键", page.contains("没有新内容"))
        assertTrue(
            "静默时长必须按「消息或事件的最晚时间」算",
            page.contains("messages.maxOfOrNull { it.createdAt.toEpochMilli() }") &&
                page.contains("events.maxOfOrNull { it.createdAt.toEpochMilli() }"),
        )
        assertTrue(
            "只能在活动态开每秒刷新，终态还刷是白耗电",
            page.contains("LaunchedEffect(threadActive)"),
        )
    }

    @Test
    fun `详情页必须有换模型继续按钮`() {
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue(page.contains("换模型继续"))
        assertTrue(
            // v241：改走 switchModelAndResume —— 它对「还在跑」的线程也有效
            // （内部先停当前那次生成、等它真停住、再换人从断点接着写）。
            // 原来接的是 resumeWithModel，只能等线程自己停下来才用得上。
            "按钮必须真的接到 switchModelAndResume 上",
            page.contains("manager.switchModelAndResume(threadId, model.id.toString())"),
        )
        assertTrue(
            "终态时必须只在可续跑的情况下才亮出来",
            page.contains("if (currentThread.canResume) {"),
        )
        assertTrue(
            "v241：运行中也必须能换模型（不用先手动停）",
            page.contains("// v241：中途换模型接着跑。"),
        )
    }

    @Test
    fun `设置界面必须有超时调节器`() {
        val picker = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue(picker.contains("R.string.agent_tools_timeout_title"))
        assertTrue(
            "0 必须显示成「不限时」而不是「0 分钟」",
            picker.contains("R.string.agent_tools_timeout_unlimited"),
        )
        assertTrue(picker.contains("agentThreadTimeoutMinutes - 5).coerceAtLeast(0)"))
        assertTrue(picker.contains("agentThreadTimeoutMinutes + 5).coerceAtMost(120)"))
    }

    // ------------------------------------------- 审查关卡漏写 verdict 的补问（v238）

    @Test
    fun `审查位漏写 verdict 时必须补问一次而不是直接判不通过`() {
        val manager = source(managerPath)
        assertTrue(
            "缺少补问：审查位写了「全部满足、可以往下走」却漏 verdict，会被误判成不通过，流水线白跑",
            manager.contains("if (verdict == AgentVerdict.UNSET) {"),
        )
        assertTrue(
            "补问必须留可观察事件",
            manager.contains("\"VERDICT_MISSING\""),
        )
        assertTrue(
            "补问必须真的重新解析一次结论",
            manager.contains("AgentReport.decode(reAsked.reportJson)?.verdict"),
        )
        assertTrue(
            "追问后仍不表态必须按不通过处理，不能默认放过",
            manager.contains("追问后仍未明确表态"),
        )
    }

    @Test
    fun `明确写了 fail 绝不允许追问翻案`() {
        val manager = source(managerPath)
        val gate = manager.substringAfter("if (stage.gate) {", "")
        assertTrue("找不到 gate 段", gate.isNotBlank())
        val followUpAt = gate.indexOf("\"VERDICT_MISSING\"")
        val unsetGuardAt = gate.indexOf("if (verdict == AgentVerdict.UNSET) {")
        assertTrue("补问必须存在", followUpAt > 0)
        assertTrue(
            "补问必须包在 UNSET 判断里；不设这个条件的话，明确判 fail 也会被追问，等于给不合格结论第二次机会",
            unsetGuardAt in 0 until followUpAt,
        )
    }

    @Test
    fun `补问必须只归一化结论不许重新审查`() {
        val manager = source(managerPath)
        assertTrue(manager.contains("**不要重新审查，不要读任何文件，不要调用任何工具。**"))
        assertTrue(
            "补问必须把上一次的结论原文喂回去，否则它无从判断",
            manager.contains("你上一次写的结论原文："),
        )
        assertTrue(
            "补问的线程不能带写权限",
            manager.contains("writablePaths = emptyList(),"),
        )
    }

    // -------------------------------------- 提示词（断言渲染结果，不是源码文本）

    @Test
    fun `提示词必须点明项目约定文件，并禁止子代理自己全树搜索`() {
        val prompt = buildAgentSystemPrompt(
            AgentThread(
                conversationId = "c",
                task = "随便",
                role = AgentRole.EXPLORER,
                createdAt = Instant.now(),
            )
        )
        assertTrue("缺少任务板", prompt.contains("TASKBOARD.md"))
        assertTrue("缺少项目手册", prompt.contains("PROJECT-MANUAL.md"))
        assertTrue("缺少 AGENTS.md 约定", prompt.contains("AGENTS.md"))
        // v248：这条断言原来要求提示词让子代理「自己去搜一次」这几个文件名。
        // 真机事故把那个做法推翻了：工作区整棵树有 44 万个条目，而这类模式全树往往只有
        // 1 个匹配，匹配额度永远凑不满 —— 一条子代理开局并行发了 3 个这种查找，
        // 跑了 8 分 35 秒还没返回就被人工停止，什么活都没干成。
        // 现在的做法是：路径由主代理在任务说明里直接给出，提示词明确禁止它自己去搜。
        assertTrue(
            "必须明确禁止子代理自己去搜这些文件名（从根目录全树查找要好几分钟）",
            prompt.contains("不要自己用查找工具去搜"),
        )
        assertTrue(
            "必须说明主代理没给路径就直接开工，否则没有这些文件的项目会卡在找文件上",
            prompt.contains("没给就直接开工"),
        )
        assertFalse(
            "不许再出现鼓励它反复去搜的说法",
            prompt.contains("搜一次就够"),
        )
    }

    @Test
    fun `审查位提示词必须把 verdict 摆到最重要的位置`() {
        val prompt = buildAgentSystemPrompt(
            AgentThread(
                conversationId = "c",
                task = "审一下",
                role = AgentRole.REVIEWER,
                createdAt = Instant.now(),
            )
        )
        assertTrue(
            "必须明说 verdict 是第一个字段、漏写等于不通过",
            prompt.contains("verdict 必须是报告 JSON 的第一个字段，漏写它等于判不通过"),
        )
        assertTrue(
            "必须明说在 conclusion 里写「全部满足」不算表态 —— 这正是本轮实测踩到的",
            prompt.contains("不算表态"),
        )
        assertTrue(prompt.contains("\"verdict\": \"pass\""))
        assertTrue(prompt.contains("\"verdict\": \"fail\""))
    }

    @Test
    fun `老的提示词约束一条都不许丢`() {
        val readOnly = buildAgentSystemPrompt(
            AgentThread(
                conversationId = "c",
                task = "随便",
                role = AgentRole.DEFAULT,
                createdAt = Instant.now(),
            )
        )
        // 这两条自 v218 起就是子代理的底线，历史上被门禁守着
        assertTrue(
            "只读子代理必须仍被告知不能改文件",
            readOnly.contains("不能修改任何文件"),
        )
        assertTrue(
            "四段式输出契约不许丢",
            readOnly.contains("结论、证据、不确定项、建议"),
        )
        assertTrue(
            "输出契约标题不许丢（v238 编辑时曾误删过一次）",
            readOnly.contains("## 输出契约（必须遵守）"),
        )
    }

    @Test
    fun `有写权限的子代理必须被告知没有编译能力`() {
        val writable = buildAgentSystemPrompt(
            AgentThread(
                conversationId = "c",
                task = "改一下",
                role = AgentRole.PROGRAMMER,
                writablePaths = listOf("upstream/x/App.kt"),
                createdAt = Instant.now(),
            )
        )
        assertTrue(writable.contains("upstream/x/App.kt"))
        assertTrue(writable.contains("不能编译"))
        assertTrue(
            "必须说清编译由主代理统一跑，否则它会以为自己该验证",
            writable.contains("编译与测试由主代理统一执行"),
        )
    }

    /** 立刻成功的假后端 */
    private class OkBackend : AgentBackend {
        override suspend fun run(
            thread: AgentThread,
            resume: Boolean,
            onMessage: suspend (AgentMessage) -> Unit,
            onEvent: suspend (AgentEvent) -> Unit,
        ): AgentRunOutcome {
            delay(20)
            onMessage(AgentMessage(threadId = thread.id, role = "assistant", content = "ok"))
            return AgentRunOutcome(
                report = AgentReport(conclusion = "完成"),
                finishReason = "stop",
                truncated = false,
                modelId = thread.modelId,
            )
        }
    }
}
