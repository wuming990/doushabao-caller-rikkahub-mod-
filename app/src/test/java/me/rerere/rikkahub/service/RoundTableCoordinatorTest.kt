package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v213 圆桌调度器测试。
 *
 * 直接复现用户这次遇到的场景：
 * "有一个模型卡死，我想换成其他可用模型补上都没办法，而且想去掉或者更换也不行。"
 */
class RoundTableCoordinatorTest {

    private var now = 0L

    private val hangingModelName = "卡住模型"
    private val replacementModelName = "接手模型"

    private fun validProposal(tag: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("结论（$tag）：这个位置给出了可用方案。")
                    appendLine("已确认事实：调度器按座位对账，不再整场等待。")
                    appendLine("验证：单元测试覆盖换人与旧结果丢弃。")
                    append("具体步骤与风险说明，逐条写清依据和验证方式。".repeat(20))
                }
            )
        ),
    )

    private fun newRun(explorerCount: Int = 2, firstModelName: String? = null): RoundTableRun {
        val seats = (0 until explorerCount).map { index ->
            RoundTableSeat(
                seatId = RoundTableSeatIds.explorer(index),
                role = RoundTableRole.EXPLORATION,
                slot = 100 + index,
                ordinal = index + 1,
                modelId = Uuid.random(),
                modelName = if (index == 0 && firstModelName != null) {
                    firstModelName
                } else {
                    "探索模型${index + 1}"
                },
            )
        }
        return RoundTableRun(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            nodeId = Uuid.random(),
            autoSummarize = true,
            seats = seats,
            nowMillis = { now },
        )
    }

    private class FakeCallbacks(
        val behavior: suspend (RoundTableSeat, Int, (Int) -> Unit) -> UIMessage,
    ) : RoundTableSeatCallbacks {
        val generateCalls = mutableListOf<Pair<String, Int>>()
        val writtenResults = mutableListOf<Triple<String, Int, RoundTableContentVerdict>>()
        val notices = mutableListOf<Pair<String, RoundTableSeatNotice>>()

        /** v223：记录每次生成收到的续跑前缀，用于断言「续跑带上了半截内容」 */
        val continueFroms = mutableListOf<Pair<String, String?>>()

        /** v223：记录写页时是否被标记为截断 */
        val truncatedWrites = mutableListOf<Pair<String, Boolean>>()

        /** v215：测试里预置"这个座位已经吐了半截内容"，用于验证停止时保留 */
        val partials = mutableMapOf<String, UIMessage>()
        val partialWrites = mutableListOf<String>()

        /** v276：记录换模型/重试时被清掉旧输出的座位 */
        val clearSeatOutputCalls = mutableListOf<String>()

        override suspend fun generate(
            seat: RoundTableSeat,
            attempt: Int,
            materials: List<RoundTableMaterial>,
            continueFrom: String?,
            onActivity: (Int) -> Unit,
        ): UIMessage {
            generateCalls += seat.seatId to attempt
            continueFroms += seat.seatId to continueFrom
            return behavior(seat, attempt, onActivity)
        }

        override suspend fun writeResult(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
            verdict: RoundTableContentVerdict,
            truncated: Boolean,
        ) {
            writtenResults += Triple(seat.seatId, attempt, verdict)
            truncatedWrites += seat.seatId to truncated
        }

        override suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice) {
            notices += seat.seatId to notice
        }

        override suspend fun writePartialResult(
            seat: RoundTableSeat,
            notice: RoundTableSeatNotice,
        ): UIMessage? {
            val partial = partials.remove(seat.seatId) ?: return null
            partialWrites += seat.seatId
            return partial
        }

        override fun resolveModelName(seat: RoundTableSeat): String? =
            seat.modelName.ifBlank { "模型" }

        override fun partialTextOf(seat: RoundTableSeat): String =
            partials[seat.seatId]?.toText().orEmpty()

        override suspend fun clearSeatOutput(seat: RoundTableSeat) {
            clearSeatOutputCalls += seat.seatId
            partials.remove(seat.seatId)
        }

        override fun onStateChanged() = Unit
    }

    private fun coordinatorOf(
        run: RoundTableRun,
        callbacks: FakeCallbacks,
        watchdogIntervalMillis: Long = 20L,
    ) = RoundTableCoordinator(
        run = run,
        callbacks = callbacks,
        nowMillis = { now },
        maxEffort = false,
        watchdogIntervalMillis = watchdogIntervalMillis,
        autoRetryDelayMillis = 1L,
    )

    private suspend fun RoundTableRun.await(
        timeoutMillis: Long = 5_000L,
        predicate: (RoundTableRunState) -> Boolean,
    ) = withTimeout(timeoutMillis) { state.first(predicate) }

    private fun seatIdsOf(run: RoundTableRun) = run.snapshot().seats.map { it.seatId }

    @Test
    fun `a hung seat can be replaced while the others keep their results`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)
        val healthy = RoundTableSeatIds.explorer(1)

        val callbacks = FakeCallbacks { seat, _, onActivity ->
            if (seat.modelName == hangingModelName) {
                onActivity(0)
                awaitCancellation()
            } else {
                validProposal(seat.seatId)
            }
        }
        val coordinator = coordinatorOf(run, callbacks)

        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        // 健康的位置先跑完，卡住的那个还在跑
        run.await {
            it.seat(healthy)?.status == RoundTableSeatStatus.SUCCEEDED &&
                it.seat(hung)?.status == RoundTableSeatStatus.RUNNING
        }

        // 用户换人：只换这一个位置
        assertTrue(
            run.submitCommand(
                hung,
                RoundTableSeatCommand.Replace(Uuid.random(), replacementModelName),
            )
        )

        withTimeout(5_000L) { groupJob.join() }

        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(hung)?.status)
        assertEquals(replacementModelName, run.seat(hung)?.modelName)
        // 已完成的位置没有被重跑
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(healthy)?.status)
        assertEquals(1, callbacks.generateCalls.count { it.first == healthy })
        assertEquals(2, callbacks.generateCalls.count { it.first == hung })
        // 换人后的结果确实落了页
        assertTrue(callbacks.writtenResults.any { it.first == hung })
    }

    @Test
    fun `a late result from the replaced model is discarded`() = runBlocking {
        val run = newRun(explorerCount = 1, firstModelName = hangingModelName)
        val seatId = RoundTableSeatIds.explorer(0)
        val staleStarted = CompletableDeferred<Unit>()

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == hangingModelName) {
                staleStarted.complete(Unit)
                // 故意无视取消，稍后才返回：模拟"旧模型迟到"
                withContext(NonCancellable) {
                    delay(80)
                    validProposal("stale")
                }
            } else {
                validProposal("fresh")
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        withTimeout(5_000L) { staleStarted.await() }
        run.submitCommand(seatId, RoundTableSeatCommand.Replace(Uuid.random(), replacementModelName))
        withTimeout(5_000L) { groupJob.join() }
        // 给迟到的旧请求留出返回时间
        delay(150)

        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
        assertEquals(replacementModelName, run.seat(seatId)?.modelName)
        // 只有新模型那一次写了页，旧的一次没有
        assertEquals(1, callbacks.writtenResults.size)
        assertEquals(run.seat(seatId)?.attempt, callbacks.writtenResults.first().second)
    }

    @Test
    fun `skipping a hung seat lets the round table move on`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == hangingModelName) awaitCancellation() else validProposal(seat.seatId)
        }
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        run.await { it.seat(hung)?.status == RoundTableSeatStatus.RUNNING }
        run.submitCommand(hung, RoundTableSeatCommand.Skip)

        withTimeout(5_000L) { groupJob.join() }

        assertEquals(RoundTableSeatStatus.SKIPPED, run.seat(hung)?.status)
        assertFalse(run.seat(hung)!!.includeInSummary)
        assertEquals(
            RoundTableSeatStatus.SUCCEEDED,
            run.seat(RoundTableSeatIds.explorer(1))?.status,
        )
        assertTrue(callbacks.notices.any { it.first == hung && it.second is RoundTableSeatNotice.Skipped })
    }

    @Test
    fun `stopping a hung seat only stops that seat`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == hangingModelName) awaitCancellation() else validProposal(seat.seatId)
        }
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        run.await { it.seat(hung)?.status == RoundTableSeatStatus.RUNNING }
        run.submitCommand(hung, RoundTableSeatCommand.Stop)
        withTimeout(5_000L) { groupJob.join() }

        assertEquals(RoundTableSeatStatus.STOPPED, run.seat(hung)?.status)
        assertEquals(
            RoundTableSeatStatus.SUCCEEDED,
            run.seat(RoundTableSeatIds.explorer(1))?.status,
        )
    }

    // ==================== v215：停止保留半截内容，跳过彻底放弃 ====================

    @Test
    fun `stopping a seat keeps its partial output and lets the user opt it back in`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)
        val partial = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("这是它停下之前已经说出来的半截内容。")),
        )

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == hangingModelName) awaitCancellation() else validProposal(seat.seatId)
        }
        callbacks.partials[hung] = partial
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        run.await { it.seat(hung)?.status == RoundTableSeatStatus.RUNNING }
        run.submitCommand(hung, RoundTableSeatCommand.Stop)
        withTimeout(5_000L) { groupJob.join() }

        assertEquals(RoundTableSeatStatus.STOPPED, run.seat(hung)?.status)
        // 半截内容被写成一页，而不是只剩一句"没有结果"
        assertEquals(listOf(hung), callbacks.partialWrites)
        assertTrue(callbacks.notices.none { it.first == hung })
        assertEquals(partial, coordinator.resultOf(hung))

        // 默认不算进最终结论
        assertFalse(run.seat(hung)!!.includeInSummary)
        assertTrue(coordinator.usableResults(listOf(hung)).isEmpty())

        // 用户手动勾回来必须生效（v213 时状态不是 SUCCEEDED，勾了也不算）
        run.setIncludeInSummary(hung, true)
        assertEquals(1, coordinator.usableResults(listOf(hung)).size)
    }

    @Test
    fun `skipping a seat drops its partial output`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == hangingModelName) awaitCancellation() else validProposal(seat.seatId)
        }
        callbacks.partials[hung] = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("半截内容，跳过时应当丢弃。")),
        )
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        run.await { it.seat(hung)?.status == RoundTableSeatStatus.RUNNING }
        run.submitCommand(hung, RoundTableSeatCommand.Skip)
        withTimeout(5_000L) { groupJob.join() }

        assertEquals(RoundTableSeatStatus.SKIPPED, run.seat(hung)?.status)
        assertTrue("跳过不该保留半截内容", callbacks.partialWrites.isEmpty())
        assertTrue(coordinator.resultOf(hung) == null)
        assertTrue(
            callbacks.notices.any {
                it.first == hung && it.second is RoundTableSeatNotice.Skipped
            }
        )
        // 即使勾了"算进结论"，也没有内容可算
        run.setIncludeInSummary(hung, true)
        assertTrue(coordinator.usableResults(listOf(hung)).isEmpty())
    }

    @Test
    fun `one seat failing does not stop the others`() = runBlocking {
        val run = newRun(firstModelName = "坏掉的模型")
        val broken = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == "坏掉的模型") error("unauthorized: invalid api key")
            validProposal(seat.seatId)
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.FAILED, run.seat(broken)?.status)
        assertEquals(
            RoundTableSeatStatus.SUCCEEDED,
            run.seat(RoundTableSeatIds.explorer(1))?.status,
        )
        // 鉴权类错误不自动重试
        assertEquals(1, callbacks.generateCalls.count { it.first == broken })
        val notice = callbacks.notices.first { it.first == broken }.second
        assertTrue(notice is RoundTableSeatNotice.Failed)
        assertEquals(
            RoundTableRetryVerdict.NOT_RETRYABLE,
            (notice as RoundTableSeatNotice.Failed).retryVerdict,
        )
    }

    @Test
    fun `transient failure before any output is retried once`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) throw java.io.IOException("connection reset by peer")
            validProposal("retry-ok")
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
        assertEquals(2, callbacks.generateCalls.size)
        assertEquals(1, run.seat(seatId)?.autoRetries)
    }

    @Test
    fun `failure after streaming is never retried automatically`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { _, _, onActivity ->
            onActivity(1_200)
            throw java.io.IOException("stream closed")
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)?.status)
        // 已经吐过字，重试会产生重复的半截回答，所以只调用一次
        assertEquals(1, callbacks.generateCalls.size)
        val notice = callbacks.notices.first().second as RoundTableSeatNotice.Failed
        assertEquals(RoundTableRetryVerdict.ALREADY_STREAMED, notice.retryVerdict)
    }

    @Test
    fun `watchdog stops a seat that has no activity for too long`() = runBlocking {
        now = 1_000L
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { _, _, _ -> awaitCancellation() }
        val coordinator = coordinatorOf(run, callbacks, watchdogIntervalMillis = 10L)
        val groupJob = launch { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        run.await { it.seat(seatId)?.status == RoundTableSeatStatus.RUNNING }
        // v229：卡死不再一次就放弃——用户要求「上游不稳定 / 网络中断也自动救两次」，
        // 因此这里持续把时间往前推，直到自动续跑预算用完、座位真正落终态。
        val ticker = launch {
            while (isActive) {
                now += 21 * 60 * 1000L
                delay(20L)
            }
        }
        try {
            withTimeout(10_000L) { groupJob.join() }
        } finally {
            ticker.cancel()
        }

        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)?.status)
        assertEquals(RoundTableCoordinator.TIMEOUT_MARKER, run.seat(seatId)?.error)
        assertEquals(
            "卡死必须自动救满预算才放弃",
            RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS,
            run.seat(seatId)?.autoContinuations,
        )
        assertTrue(callbacks.notices.any { it.second is RoundTableSeatNotice.AutoContinuing })
        assertTrue(callbacks.notices.any { it.second is RoundTableSeatNotice.TimedOut })
    }

    @Test
    fun `long thinking with periodic activity is not stopped`() = runBlocking {
        now = 1_000L
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        // 模拟 MAX 档长思考：不吐正文，但每隔一会儿有一次协议活动
        val callbacks = FakeCallbacks { _, _, onActivity ->
            repeat(6) {
                now += 5 * 60 * 1000L
                onActivity(0)
                delay(5)
            }
            validProposal("max-thinking")
        }
        val coordinator = coordinatorOf(run, callbacks, watchdogIntervalMillis = 5L)
        withTimeout(10_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        // 总共过了 30 分钟，但每 5 分钟就有动静，不该被判成卡死
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
        assertEquals(1, callbacks.generateCalls.size)
    }

    @Test
    fun `suspect output is kept out of the summary material`() = runBlocking {
        val run = newRun(explorerCount = 2, firstModelName = "复述模型")
        val echo = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { seat, _, _ ->
            if (seat.modelName == "复述模型") {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("你现在是【独立探索员】。我明白我的职责是发现别人漏掉的东西。")
                    ),
                )
            } else {
                validProposal(seat.seatId)
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.SUSPECT, run.seat(echo)?.status)
        assertEquals(RoundTableContentVerdict.ROLE_ECHO, run.seat(echo)?.suspectReason)
        // 页面照样写下来了，只是不参与拍板
        assertTrue(callbacks.writtenResults.any { it.first == echo })
        val usable = coordinator.usableResults(seatIdsOf(run)).map { it.first.seatId }
        assertEquals(listOf(RoundTableSeatIds.explorer(1)), usable)
    }

    // ==================== v223：截断识别 + 继续输出 ====================

    @Test
    fun `truncated output is detected and the seat can continue`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        // v276：截断后会自动续跑（没有流式快照时回退到 results 里的旧内容做前缀），
        // 因此给每次尝试不同的内容，让自动续跑每次都有新增、最终停在预算用尽。
        val callbacks = FakeCallbacks { _, attempt, _ ->
            // 达到单次上限被砍断：内容够长，但 finishReason 明确是 length
            validProposal("truncated-$attempt").copy(finishReason = "length")
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUCCEEDED, seat.status)
        assertTrue("length 必须被识别成截断", seat.truncated)
        assertEquals("length", seat.finishReason)
        // 截断的产出不完整，默认不进拍板材料
        assertFalse(seat.includeInSummary)
        // 允许接着写
        assertTrue(seat.canContinue)
        assertEquals(
            "自动续跑每次截断都写一次结果页",
            RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS + 1,
            callbacks.truncatedWrites.size,
        )
        assertTrue(callbacks.truncatedWrites.all { it.first == seatId && it.second })
    }

    @Test
    fun `a normal finish reason is not treated as truncation`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        val callbacks = FakeCallbacks { _, _, _ ->
            validProposal("complete").copy(finishReason = "stop")
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUCCEEDED, seat.status)
        assertFalse(seat.truncated)
        assertTrue(seat.includeInSummary)
        // 正常写完的位置不该出现「继续输出」
        assertFalse(seat.canContinue)
    }

    @Test
    fun `silent truncation without finish reason is treated as truncated (v272)`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        // v276：无声截断同样会触发自动续跑（results 回退做前缀），每次给不同内容
        val callbacks = FakeCallbacks { _, attempt, _ ->
            // 无声截断：上游把流关了，没有终止信号也没有结束原因（v269 主对话同款形态，
            // Claude 中转站真机实锤）。此前圆桌把这种半截产出当成完整成功。
            validProposal("silent-$attempt").copy(truncatedWithoutSentinel = true)
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUCCEEDED, seat.status)
        assertTrue("无声截断必须被识别成截断", seat.truncated)
        // 截断的产出不完整，默认不进拍板材料
        assertFalse(seat.includeInSummary)
        // 必须给出续跑入口
        assertTrue(seat.canContinue)
        assertEquals(
            "自动续跑每次截断都写一次结果页",
            RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS + 1,
            callbacks.truncatedWrites.size,
        )
        assertTrue(callbacks.truncatedWrites.all { it.first == seatId && it.second })
    }

    @Test
    fun `continue feeds the partial text back and keeps the earlier content`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val firstHalf = "第一段已经写好的内容。".repeat(40)

        // v276：改用「疑似无效」来触发手动继续 —— 截断现在会自动续跑，不会停在原地等用户点继续。
        // 疑似无效按策略不自动续跑，正好留出「用户手动继续」这一步来验证回灌。
        val suspectText = "你现在是【独立探索员】。我明白我的职责是发现别人漏掉的东西。"
        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(suspectText)),
                    finishReason = "stop",
                )
            } else {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf + "接着写完的后半段结论、验证与风险。")),
                    finishReason = "stop",
                )
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertTrue("第一次应被判定为疑似无效", run.seat(seatId)!!.suspectReason != null)
        // 第一次生成没有续跑前缀
        assertEquals(listOf(seatId to null), callbacks.continueFroms)

        // 用户点「继续输出」
        assertTrue(run.submitCommand(seatId, RoundTableSeatCommand.Continue))
        assertEquals(RoundTableSeatStatus.PENDING, run.seat(seatId)!!.status)
        assertEquals(1, run.seat(seatId)!!.continuations)
        // 续跑不算重跑
        assertEquals(0, run.seat(seatId)!!.reruns)

        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        // 第二次生成必须带上第一次的已产出内容
        val secondCall = callbacks.continueFroms.last()
        assertEquals(seatId, secondCall.first)
        assertTrue("续跑必须把已产出内容回灌", secondCall.second?.contains(suspectText) == true)

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUCCEEDED, seat.status)
        assertFalse("补完后不该再标记截断", seat.truncated)
        assertTrue(seat.includeInSummary)
        // 最终结果包含前半段，说明没有丢内容
        assertTrue(coordinator.resultOf(seatId)!!.toText().contains("第一段已经写好的内容"))
        assertTrue(coordinator.resultOf(seatId)!!.toText().contains("接着写完的后半段"))
    }

    @Test
    fun `continue after a stop resumes from the kept partial output`() = runBlocking {
        val run = newRun(firstModelName = hangingModelName)
        val hung = RoundTableSeatIds.explorer(0)
        val partialText = "停止之前已经说出来的半截内容。"

        val callbacks = FakeCallbacks { seat, attempt, _ ->
            if (seat.modelName == hangingModelName && attempt == 1) {
                awaitCancellation()
            } else {
                validProposal("resumed")
            }
        }
        callbacks.partials[hung] = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(partialText)),
        )
        val coordinator = coordinatorOf(run, callbacks)
        val groupJob = launch { coordinator.runGroup(seatIdsOf(run)) { emptyList() } }

        run.await { it.seat(hung)?.status == RoundTableSeatStatus.RUNNING }
        run.submitCommand(hung, RoundTableSeatCommand.Stop)
        withTimeout(5_000L) { groupJob.join() }
        assertEquals(RoundTableSeatStatus.STOPPED, run.seat(hung)?.status)

        // 停止后允许接着写
        assertTrue(run.seat(hung)!!.canContinue)
        assertTrue(run.submitCommand(hung, RoundTableSeatCommand.Continue))
        withTimeout(5_000L) { coordinator.runGroup(listOf(hung)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(hung)?.status)
        assertTrue(
            "停止后的续跑必须带上保留的半截内容",
            callbacks.continueFroms.last().second?.contains("停止之前已经说出来") == true,
        )
    }

    @Test
    fun `retry clears the continuation prefix so it starts over`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        // v276：改用「疑似无效」留出「先点继续、再点重试」的窗口 —— 截断现在会自动续跑
        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("你现在是【独立探索员】。我明白我的职责是发现别人漏掉的东西。")
                    ),
                    finishReason = "stop",
                )
            } else {
                validProposal("retried").copy(finishReason = "stop")
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }
        assertTrue("第一次应被判定为疑似无效", run.seat(seatId)!!.suspectReason != null)

        // 先点继续，再改主意点重试：重试必须是从头跑，不带续跑前缀
        run.submitCommand(seatId, RoundTableSeatCommand.Continue)
        run.submitCommand(seatId, RoundTableSeatCommand.Retry)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertEquals(null, callbacks.continueFroms.last().second)
        assertFalse(run.seat(seatId)!!.truncated)
    }

    @Test
    fun `a deleted model is reported instead of hanging the group`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)

        val callbacks = object : RoundTableSeatCallbacks {
            val notices = mutableListOf<RoundTableSeatNotice>()
            override suspend fun generate(
                seat: RoundTableSeat,
                attempt: Int,
                materials: List<RoundTableMaterial>,
                continueFrom: String?,
                onActivity: (Int) -> Unit,
            ): UIMessage = error("should not be called")

            override suspend fun writeResult(
                seat: RoundTableSeat,
                attempt: Int,
                message: UIMessage,
                verdict: RoundTableContentVerdict,
                truncated: Boolean,
            ) = Unit

            override suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice) {
                notices += notice
            }

            override suspend fun writePartialResult(
                seat: RoundTableSeat,
                notice: RoundTableSeatNotice,
            ): UIMessage? = null

            override fun partialTextOf(seat: RoundTableSeat): String = ""

            // 模型已被用户删掉
            override fun resolveModelName(seat: RoundTableSeat): String? = null
            override fun onStateChanged() = Unit
        }

        val coordinator = RoundTableCoordinator(
            run = run,
            callbacks = callbacks,
            nowMillis = { now },
            watchdogIntervalMillis = 20L,
            autoRetryDelayMillis = 1L,
        )
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)?.status)
        assertTrue(callbacks.notices.any { it is RoundTableSeatNotice.ModelMissing })
    }

    // ==================== v276：检查点判定 ====================

    private fun reviewerSeat(
        id: String,
        status: RoundTableSeatStatus,
        includeInSummary: Boolean = true,
    ) = RoundTableSeat(
        seatId = id,
        role = RoundTableRole.EXPLORATION,
        slot = 100,
        ordinal = 1,
        modelId = Uuid.random(),
        modelName = "模型",
        status = status,
        includeInSummary = includeInSummary,
    )

    @Test
    fun `REVIEW needs a checkpoint for failed stopped interrupted and truncated-without-summary seats`() {
        // FAILED / STOPPED / INTERRUPTED 都不在可用结果集合里时，需要停下
        assertTrue(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.FAILED)),
                emptySet(),
            )
        )
        assertTrue(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.STOPPED)),
                emptySet(),
            )
        )
        assertTrue(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.INTERRUPTED)),
                emptySet(),
            )
        )
        // 截断成功但未纳入摘要（includeInSummary=false，真实可用集合为空）也需要停下
        assertTrue(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.SUCCEEDED, includeInSummary = false)),
                emptySet(),
            )
        )
        // SKIPPED 是用户明确放弃，可豁免
        assertFalse(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.SKIPPED)),
                emptySet(),
            )
        )
        // 可用集合非空且覆盖全部非跳过座位：不需要停下
        assertFalse(
            roundTableStageNeedsReview(
                RoundTableGapStage.REVIEW,
                listOf(reviewerSeat("a", RoundTableSeatStatus.SUCCEEDED)),
                setOf("a"),
            )
        )
    }

    @Test
    fun `SUMMARY needs a checkpoint only when the real usable set is empty`() {
        // 有一个可用结果（即使 checked 座位状态不是 SUCCEEDED，只要在可用集合里）就不停
        assertFalse(
            roundTableStageNeedsReview(
                RoundTableGapStage.SUMMARY,
                listOf(reviewerSeat("a", RoundTableSeatStatus.SKIPPED)),
                setOf("a"),
            )
        )
        // 可用集合为空：需要停下
        assertTrue(
            roundTableStageNeedsReview(
                RoundTableGapStage.SUMMARY,
                listOf(reviewerSeat("a", RoundTableSeatStatus.SKIPPED)),
                emptySet(),
            )
        )
    }

    @Test
    fun `missing seat ids follow the same rule as the review decision`() {
        val seats = listOf(
            reviewerSeat("a", RoundTableSeatStatus.FAILED),
            reviewerSeat("b", RoundTableSeatStatus.SKIPPED),
            reviewerSeat("c", RoundTableSeatStatus.SUCCEEDED),
        )
        // REVIEW：缺失 = 非跳过且不在可用集合 -> a 缺席，b(跳过)/c(可用) 不缺席
        assertEquals(
            setOf("a"),
            roundTableMissingSeatIds(RoundTableGapStage.REVIEW, seats, setOf("c")),
        )
        // SUMMARY：缺失 = 不在可用集合 -> a、b、c 中只有 c 可用，a、b 缺席
        assertEquals(
            setOf("a", "b"),
            roundTableMissingSeatIds(RoundTableGapStage.SUMMARY, seats, setOf("c")),
        )
    }

    // ==================== v276：换模型/重试清旧输出 ====================

    @Test
    fun `retry clears the old seat output via callback and does not reuse old partial as continue prefix`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val partialText = "旧模型吐出的半截内容".repeat(30)

        // v276：用「疑似无效」触发首跑（截断现在会自动续跑，不便于测手动重试）
        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("你现在是【独立探索员】。我明白我的职责是发现别人漏掉的东西。")
                    ),
                    finishReason = "stop",
                )
            } else {
                validProposal("retried-fresh")
            }
        }
        // 预置旧快照，验证重试会把它清掉
        callbacks.partials[seatId] = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(partialText)),
        )
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }
        assertTrue("第一次应被判定为疑似无效", run.seat(seatId)!!.suspectReason != null)
        assertTrue("重试前旧快照应该还在", callbacks.partials.containsKey(seatId))

        // 重试应清掉旧输出（调用 clearSeatOutput），并从零开始
        run.submitCommand(seatId, RoundTableSeatCommand.Retry)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertTrue("重试必须调用 clearSeatOutput", callbacks.clearSeatOutputCalls.contains(seatId))
        assertFalse("重试后旧快照必须被清掉", callbacks.partials.containsKey(seatId))
        // 重试后从头跑，不带任何前缀
        assertEquals("重试后不该带续跑前缀", null, callbacks.continueFroms.last().second)
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
        assertFalse("重试后不该标记为截断", run.seat(seatId)!!.truncated)
    }

    @Test
    fun `replace clears the old seat output via callback`() = runBlocking {
        val run = newRun(explorerCount = 1, firstModelName = replacementModelName)
        val seatId = RoundTableSeatIds.explorer(0)
        val callbacks = FakeCallbacks { _, _, _ -> validProposal("ok") }
        callbacks.partials[seatId] = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("旧内容".repeat(40))),
        )
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }
        assertTrue(callbacks.partials.containsKey(seatId))

        run.submitCommand(seatId, RoundTableSeatCommand.Replace(Uuid.random(), "新模型"))
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertTrue("换模型必须调用 clearSeatOutput", callbacks.clearSeatOutputCalls.contains(seatId))
        assertFalse("换模型后旧快照必须被清掉", callbacks.partials.containsKey(seatId))
    }

    // ==================== v276：续跑无新增不算成功 ====================

    @Test
    fun `continue that produces no new content is failed without overwriting the old result`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val firstHalf = "第一段已经写好的内容，多写些让它超过疑似无效阈值。".repeat(20)

        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf)),
                    finishReason = "length",
                )
            } else {
                // 第二次续跑返回与前缀完全相同的内容 -> 无新增
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf)),
                    finishReason = "stop",
                )
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        // v276：第一次截断后自动续跑（results 回退做前缀），第二次返回完全相同内容 -> 无新增。
        // 最终状态 FAILED、NoProgress 通知、旧结果仍在、没有第二次写结果。
        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)?.status)
        assertEquals(RoundTableCoordinator.NO_PROGRESS_MARKER, run.seat(seatId)?.error)
        assertTrue(
            callbacks.notices.any { it.first == seatId && it.second is RoundTableSeatNotice.NoProgress }
        )
        // 旧结果必须保留（第一次截断页，不被覆盖）
        assertTrue(coordinator.resultOf(seatId) != null)
        assertTrue(coordinator.resultOf(seatId)!!.toText().contains(firstHalf))
        // 续跑无新增不该写第二次结果
        assertEquals(1, callbacks.writtenResults.size)
    }

    @Test
    fun `continue that adds only whitespace is treated as no progress`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val suspectText = "你现在是【独立探索员】。我明白我的职责是发现别人漏掉的东西。"

        // v276：用「疑似无效」首跑（不自动续跑），再手动继续；续跑只吐空白 -> 无新增
        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(suspectText)),
                    finishReason = "stop",
                )
            } else {
                // 续跑只额外吐了空白
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(suspectText + "   \n  ")),
                    finishReason = "stop",
                )
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }
        assertTrue("第一次应被判定为疑似无效", run.seat(seatId)!!.suspectReason != null)

        run.submitCommand(seatId, RoundTableSeatCommand.Continue)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)?.status)
        assertTrue(
            callbacks.notices.any { it.second is RoundTableSeatNotice.NoProgress }
        )
        assertEquals(1, callbacks.writtenResults.size)
    }

    @Test
    fun `continue with real new content still succeeds and keeps the merged result`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val firstHalf = "前面已经写好的内容，足够长避免被判成疑似无效。".repeat(20)

        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf)),
                    finishReason = "length",
                )
            } else {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf + "最后补上的新段落与具体结论与风险说明。")),
                    finishReason = "stop",
                )
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        // v276：第一次截断后自动续跑（results 回退做前缀）直接完成第二次尝试
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
        assertFalse(run.seat(seatId)!!.truncated)
        // 有新增才会写第二次结果
        assertEquals(2, callbacks.writtenResults.size)
    }

    // ==================== v276：auto continue 用 partialTextOf，空则回退 results ====================

    @Test
    fun `auto continue falls back to coordinator results when there is no streaming snapshot`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val partialText = "已产出的半截内容，超过疑似无效长度阈值避免误判。".repeat(20)

        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                // 第一次正常产出但被截断；partials 里刻意不预置快照（模拟没有流式快照）
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(partialText)),
                    finishReason = "max_tokens",
                )
            } else {
                validProposal("fixed")
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) {
            coordinator.runGroup(listOf(seatId)) {
                emptyList()
            }
        }

        // v276：results[seatId] 已在 finishWithResult 里写入，partialTextOf 为空时用它兜底续跑，
        // 所以第一次截断后自动续跑（第二次生成）带上了 results 里的旧内容作为前缀
        assertEquals(2, callbacks.generateCalls.count { it.first == seatId })
        val secondFrom = callbacks.continueFroms.last().second
        assertTrue(
            "auto continue 带前缀",
            secondFrom?.contains("已产出的半截内容") == true,
        )
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
    }

    // ==================== v276：continuation delta 与字数 ====================

    @Test
    fun `continuation delta excludes the old prefix`() {
        val prefix = "第一段已经写好的内容"
        // 合并结果 = 前缀 + 换行 + 新增
        val merged = "$prefix\n最后补上的新段落"
        val delta = roundTableContinuationDelta(prefix, merged)
        assertTrue("新增字数应只算新段落（约${"最后补上的新段落".length}）", delta == "最后补上的新段落".length)
        // 与前缀相同 -> 0
        assertEquals(0, roundTableContinuationDelta(prefix, prefix))
        // 只多空白 -> 0
        assertEquals(0, roundTableContinuationDelta(prefix, "$prefix   \n"))
        // 无前缀 -> 全文
        assertEquals(10, roundTableContinuationDelta(null, "0123456789"))
    }

    @Test
    fun `finish chars for a continuation do not include the old prefix`() = runBlocking {
        val run = newRun(explorerCount = 1)
        val seatId = RoundTableSeatIds.explorer(0)
        val firstHalf = "前面已经写好的内容，足够长避免被判成疑似无效。".repeat(20)

        val callbacks = FakeCallbacks { _, attempt, _ ->
            if (attempt == 1) {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf)),
                    finishReason = "length",
                )
            } else {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(firstHalf + "新增的结论段")),
                    finishReason = "stop",
                )
            }
        }
        val coordinator = coordinatorOf(run, callbacks)
        withTimeout(5_000L) { coordinator.runGroup(listOf(seatId)) { emptyList() } }

        // v276：第一次截断后自动续跑完成第二次尝试；第二次成功后的 chars 只算新增文字长度
        assertEquals(2, callbacks.generateCalls.count { it.first == seatId })
        val expandedChars = "新增的结论段".length
        assertEquals("续跑成功后的字数只算新增", expandedChars, run.seat(seatId)?.chars)
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)?.status)
    }
}
