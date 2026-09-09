package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.RoundTableMaterial
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * v239 门禁：圆桌的「停止 / 跳过」不许被卡死的上游拖住。
 *
 * 真机事故（用户截图 + 原话）：
 * > 「点击停止和跳过都没反应，一直卡在这里，不应该是跟截断一样直接断掉么？
 * >  直接导致前面跑出来的方案废掉了。」
 *
 * 现场是：座位显示「正在处理你的指令…」「已重跑 4 次」「已 30 分钟没动静」，
 * 停止按钮变灰、跳过点了没反应，整场焊死。
 *
 * 根因有两层：
 * 1. 调度协程用 `supervisorScope { async { generate() } }` 等生成结果，而
 *    supervisorScope 必须等子协程**真正结束**才返回。遇到「连接不断、一个字不推」
 *    的上游，cancel 请求到不了挂起点，于是「取走指令并执行」的代码永远跑不到；
 * 2. 界面把停止按钮做成 `enabled = !pendingCommand`，第一次点完就变灰，
 *    用户连再点一次的机会都没有。
 *
 * 这个测试用 `withContext(NonCancellable)` 精确复现「取消不掉的上游」，
 * 确认停止指令仍然在秒级生效。没有 v239 的修复，第一个用例必然超时失败。
 */
class RoundTableV239ForceStopTest {

    private var now = 0L

    private fun newRun(): RoundTableRun {
        val seats = listOf(
            RoundTableSeat(
                seatId = RoundTableSeatIds.explorer(0),
                role = RoundTableRole.EXPLORATION,
                slot = 100,
                ordinal = 1,
                modelId = Uuid.random(),
                modelName = "卡住的模型",
            )
        )
        return RoundTableRun(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            nodeId = Uuid.random(),
            autoSummarize = true,
            seats = seats,
            nowMillis = { now },
            attemptContext = Dispatchers.Default,
        )
    }

    private class FakeCallbacks(
        val behavior: suspend (RoundTableSeat, Int, (Int) -> Unit) -> UIMessage,
    ) : RoundTableSeatCallbacks {
        val notices = mutableListOf<Pair<String, RoundTableSeatNotice>>()
        val partialWrites = mutableListOf<String>()
        val partials = mutableMapOf<String, UIMessage>()

        override suspend fun generate(
            seat: RoundTableSeat,
            attempt: Int,
            materials: List<RoundTableMaterial>,
            continueFrom: String?,
            onActivity: (Int) -> Unit,
        ): UIMessage = behavior(seat, attempt, onActivity)

        override suspend fun writeResult(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
            verdict: RoundTableContentVerdict,
            truncated: Boolean,
        ) = Unit

        override suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice) {
            notices += seat.seatId to notice
        }

        override suspend fun writePartialResult(
            seat: RoundTableSeat,
            notice: RoundTableSeatNotice,
        ): UIMessage? {
            val snapshot = partials[seat.seatId] ?: return null
            partialWrites += seat.seatId
            return snapshot
        }

        override fun partialTextOf(seat: RoundTableSeat): String =
            partials[seat.seatId]?.toText().orEmpty()

        override fun resolveModelName(seat: RoundTableSeat): String? = seat.modelName

        override fun onStateChanged() = Unit
    }

    private fun coordinatorOf(run: RoundTableRun, callbacks: FakeCallbacks) =
        RoundTableCoordinator(
            run = run,
            callbacks = callbacks,
            nowMillis = { now },
            // 看门狗设很久，确保测的是「用户点停止」这条路，不是超时兜底
            watchdogIntervalMillis = 600_000,
            autoRetryDelayMillis = 10,
            commandPollIntervalMillis = 20,
        )

    @Test
    fun `上游取消不掉时停止指令仍然在秒级生效`() = runBlocking {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        // 「连接不断、一个字不推」的上游：cancel 请求到不了挂起点
        val callbacks = FakeCallbacks { _, _, _ ->
            withContext(NonCancellable) { delay(3_000) }
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("迟到的结果")))
        }
        val coordinator = coordinatorOf(run, callbacks)
        val job = launch { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        withTimeout(5_000) {
            run.state.first { it.seat(seatId)?.status == RoundTableSeatStatus.RUNNING }
        }
        assertTrue("停止指令应被接受", run.submitCommand(seatId, RoundTableSeatCommand.Stop))
        withTimeout(2_000) {
            run.state.first { it.seat(seatId)?.status == RoundTableSeatStatus.STOPPED }
        }
        assertTrue(
            "停止后必须留下说明页",
            callbacks.notices.any { it.second is RoundTableSeatNotice.Stopped },
        )
        job.cancel()
    }

    @Test
    fun `上游取消不掉时跳过指令同样在秒级生效`() = runBlocking {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        val callbacks = FakeCallbacks { _, _, _ ->
            withContext(NonCancellable) { delay(3_000) }
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("迟到的结果")))
        }
        val coordinator = coordinatorOf(run, callbacks)
        val job = launch { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        withTimeout(5_000) {
            run.state.first { it.seat(seatId)?.status == RoundTableSeatStatus.RUNNING }
        }
        assertTrue(run.submitCommand(seatId, RoundTableSeatCommand.Skip))
        withTimeout(2_000) {
            run.state.first { it.seat(seatId)?.status == RoundTableSeatStatus.SKIPPED }
        }
        job.cancel()
    }

    @Test
    fun `连续点两次停止都要被接受`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, run.seat(seatId)?.modelId, "卡住的模型")
        assertTrue("第一次", run.submitCommand(seatId, RoundTableSeatCommand.Stop))
        assertTrue(
            "第二次也必须被接受 —— 旧版在这里返回 false，用户第二次点击完全没反应",
            run.submitCommand(seatId, RoundTableSeatCommand.Stop),
        )
        assertTrue("界面上仍显示有待处理指令", run.seat(seatId)?.pendingCommand == true)
    }

    @Test
    fun `第二次停止不会把尝试编号反复推高`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, run.seat(seatId)?.modelId, "卡住的模型")
        val before = run.seat(seatId)?.attempt ?: 0
        run.submitCommand(seatId, RoundTableSeatCommand.Stop)
        val afterFirst = run.seat(seatId)?.attempt ?: 0
        run.submitCommand(seatId, RoundTableSeatCommand.Stop)
        val afterSecond = run.seat(seatId)?.attempt ?: 0
        assertEquals("第一次要作废旧结果", before + 1, afterFirst)
        assertEquals("第二次不该再推高", afterFirst, afterSecond)
    }

    @Test
    fun `强制作废会断开连接丢弃挂起指令并作废旧结果`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, run.seat(seatId)?.modelId, "卡住的模型")
        run.submitCommand(seatId, RoundTableSeatCommand.Stop)
        val attemptBefore = run.seat(seatId)?.attempt ?: 0

        assertTrue(run.invalidateAttempt(seatId))
        assertNull("挂起指令必须被清掉，否则执行协程醒来会重复执行一遍", run.peekCommand(seatId))
        assertEquals("必须作废旧结果", attemptBefore + 1, run.seat(seatId)?.attempt)
        assertFalse(
            "界面不该再显示「正在处理你的指令…」",
            run.seat(seatId)?.pendingCommand == true,
        )
    }

    @Test
    fun `强制落终态后调度协程不会再写第二页说明`() = runBlocking {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        val callbacks = FakeCallbacks { _, _, _ -> awaitCancellation() }
        callbacks.partials[seatId] = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("已经吐出来的半截内容")),
        )
        val coordinator = coordinatorOf(run, callbacks)
        val job = launch { coordinator.runGroup(listOf(seatId)) { emptyList() } }
        withTimeout(5_000) {
            run.state.first { it.seat(seatId)?.status == RoundTableSeatStatus.RUNNING }
        }

        // 模拟 ChatService 的强制通道：状态就地落定，页面事后补
        run.invalidateAttempt(seatId)
        run.markStopped(seatId)
        coordinator.writeForcedSettleNotice(seatId, skipped = false)

        withTimeout(5_000) { job.join() }
        assertEquals(
            "半截内容只该写一次",
            1,
            callbacks.partialWrites.count { it == seatId },
        )
        assertTrue(
            "不该再补一页「已停止」说明（那会变成两页）",
            callbacks.notices.none { it.second is RoundTableSeatNotice.Stopped },
        )
        assertEquals(RoundTableSeatStatus.STOPPED, run.seat(seatId)?.status)
    }

    @Test
    fun `没人负责的座位由 ChatService 强制通道落终态`() {
        // v239：这条路不在调度器里，而在 ChatService.controlRoundTableSeat ——
        // 只要 runGroup 还活着，对账循环会立刻给「非终态且没人负责」的座位补起
        // 执行协程，所以调度器内部那种兜底是死代码（实测已确认并删除）。
        // 这里只守住「强制落终态 + 补写页面」这两个能力还在。
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, run.seat(seatId)?.modelId, "卡住的模型")
        run.submitCommand(seatId, RoundTableSeatCommand.Skip)
        assertFalse("前提：确实没人负责", run.isSeatClaimed(seatId))

        // ChatService 强制通道做的三件事
        run.invalidateAttempt(seatId)
        run.markSkipped(seatId)

        assertEquals(RoundTableSeatStatus.SKIPPED, run.seat(seatId)?.status)
        assertNull("挂起指令必须被丢掉", run.peekCommand(seatId))
    }

    @Test
    fun `清场时会真的取消还在跑的连接而不是只清表`() = runBlocking {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        val deferred = run.launchAttempt(seatId) { awaitCancellation() }
        run.cleanup()
        withTimeout(2_000) { runCatching { deferred.join() } }
        assertTrue("旧版只 clear 表，连接会继续在后台烧钱", deferred.isCancelled)
    }

    // ---- 接线（源码断言）----

    private fun source(relative: String): String {
        val moduleDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
    }

    private fun resource(relative: String): String {
        val moduleDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(moduleDir, "src/main/res/$relative"),
            File(moduleDir, "app/src/main/res/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("找不到资源 $relative（user.dir=${moduleDir.absolutePath}）")
    }

    @Test
    fun `调度器不许再用 supervisorScope 等生成结果`() {
        val text = source("service/RoundTableCoordinator.kt")
        assertTrue(
            "生成必须交给独立作用域跑",
            text.contains("run.launchAttempt(seatId)"),
        )
        assertTrue(
            "必须让指令能抢先",
            text.contains("awaitAttemptOrCommand(seatId, deferred)"),
        )
        assertFalse(
            "旧写法会等被取消的子协程真正结束，这就是死锁根因",
            text.contains("val result = supervisorScope {"),
        )
    }

    @Test
    fun `ChatService 必须有不等任何人的强制通道`() {
        val text = source("service/ChatService.kt")
        assertTrue(
            "必须判断「上一条指令还没人接住」",
            text.contains("val commandStuck = run.peekCommand(seatId) != null"),
        )
        assertTrue(
            "强制通道的判据必须包含 commandStuck",
            text.contains("if ((!scheduled || commandStuck) && !revives) {"),
        )
        assertTrue(
            "强制处置前必须先断开连接并作废这次尝试",
            text.contains("run.invalidateAttempt(seatId)"),
        )
        assertTrue(
            "必须把半截内容补写出来",
            text.contains("forceSettleRoundTablePage(conversationId, seatId, skipped = false)"),
        )
    }

    @Test
    fun `停止按钮不许再被禁用`() {
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")
        val stopBlock = panel.substringAfter("RoundTableSeatCommand.Stop)").substringBefore("}")
        assertFalse(
            "停止按钮一旦被 pendingCommand 禁用，用户就再也点不了第二次",
            stopBlock.contains("enabled = !seat.pendingCommand"),
        )
        assertTrue(
            "已下过指令时要升级成「强制停止」",
            panel.contains("R.string.round_table_panel_action_force_stop"),
        )
        assertTrue(
            "跳过也要能强制",
            panel.contains("R.string.round_table_panel_action_force_skip"),
        )
        assertTrue(
            "「继续输出」保留原有禁用逻辑（它不是死锁点）",
            panel.contains("enabled = !seat.pendingCommand"),
        )
    }

    @Test
    fun `强制停止与强制跳过的文案两种语言都要有`() {
        listOf("values/strings.xml", "values-zh/strings.xml").forEach { path ->
            val xml = resource(path)
            assertTrue(
                "$path 缺 force_stop",
                xml.contains("name=\"round_table_panel_action_force_stop\""),
            )
            assertTrue(
                "$path 缺 force_skip",
                xml.contains("name=\"round_table_panel_action_force_skip\""),
            )
        }
    }

    @Test
    fun `迟到的旧流式内容不许覆盖半截快照`() {
        val text = source("service/ChatService.kt")
        val block = text.substringAfter("private suspend fun streamFinalSnapshot(")
            .substringBefore("private fun ")
        val guardIndex = block.indexOf("if (!run.isCurrentAttempt(seat.seatId, attempt)) return")
        val writeIndex = block.indexOf("partialSnapshots[seat.seatId] = message")
        assertTrue("两处都应存在", guardIndex >= 0 && writeIndex >= 0)
        assertTrue(
            "守卫必须在写快照之前，否则强制停止保下来的是已作废的版本",
            guardIndex < writeIndex,
        )
    }
}
