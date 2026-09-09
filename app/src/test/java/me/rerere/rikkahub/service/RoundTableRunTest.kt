package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v213 圆桌座位状态机测试。
 *
 * 重点验证用户这次反馈的问题：
 * - 一个模型卡住时，能不能只停它一个、只换它一个；
 * - 换掉之后，旧模型迟到的结果会不会把新结果覆盖掉；
 * - 别的模型会不会被连带影响。
 */
class RoundTableRunTest {

    private var now = 1_000L

    private fun newRun(
        autoSummarize: Boolean = true,
        explorerCount: Int = 2,
    ): RoundTableRun {
        val seats = buildList {
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.CONTRACT,
                    role = RoundTableRole.CONTRACT,
                    slot = 0,
                    modelId = Uuid.random(),
                    modelName = "合同模型",
                )
            )
            repeat(explorerCount) { index ->
                add(
                    RoundTableSeat(
                        seatId = RoundTableSeatIds.explorer(index),
                        role = RoundTableRole.EXPLORATION,
                        slot = 100 + index,
                        ordinal = index + 1,
                        modelId = Uuid.random(),
                        modelName = "探索模型${index + 1}",
                    )
                )
            }
        }
        return RoundTableRun(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            nodeId = Uuid.random(),
            autoSummarize = autoSummarize,
            seats = seats,
            nowMillis = { now },
        )
    }

    @Test
    fun `replace bumps attempt so the stale request cannot overwrite`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, run.seat(seatId)?.modelId, "探索模型1")
        val staleAttempt = run.seat(seatId)!!.attempt

        val newModel = Uuid.random()
        assertTrue(run.submitCommand(seatId, RoundTableSeatCommand.Replace(newModel, "接手模型")))

        // 换人后：模型变了，尝试编号 +1，状态回到排队
        val seat = run.seat(seatId)!!
        assertEquals(newModel, seat.modelId)
        assertEquals("接手模型", seat.modelName)
        assertEquals(staleAttempt + 1, seat.attempt)
        assertEquals(RoundTableSeatStatus.PENDING, seat.status)

        // 旧模型这时才返回结果：必须被丢弃
        assertFalse(run.isCurrentAttempt(seatId, staleAttempt))
        assertFalse(run.markSucceeded(seatId, staleAttempt, Uuid.random(), chars = 5_000))
        assertEquals(RoundTableSeatStatus.PENDING, run.seat(seatId)!!.status)

        // 新模型用当前尝试编号写入：可以成功
        val freshAttempt = run.seat(seatId)!!.attempt
        val messageId = Uuid.random()
        assertTrue(run.markSucceeded(seatId, freshAttempt, messageId, chars = 1_200))
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)!!.status)
        assertEquals(messageId, run.seat(seatId)!!.messageId)
    }

    @Test
    fun `stopping one seat does not touch the others`() {
        val run = newRun()
        val stopped = RoundTableSeatIds.explorer(0)
        val healthy = RoundTableSeatIds.explorer(1)
        run.markRunning(stopped, run.seat(stopped)?.modelId, "探索模型1")
        run.markRunning(healthy, run.seat(healthy)?.modelId, "探索模型2")

        assertTrue(run.submitCommand(stopped, RoundTableSeatCommand.Stop))
        run.markStopped(stopped)

        assertEquals(RoundTableSeatStatus.STOPPED, run.seat(stopped)!!.status)
        assertEquals(RoundTableSeatStatus.RUNNING, run.seat(healthy)!!.status)

        // 另一个位置仍然能正常写入结果
        val attempt = run.seat(healthy)!!.attempt
        assertTrue(run.markSucceeded(healthy, attempt, Uuid.random(), chars = 900))
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(healthy)!!.status)
    }

    @Test
    fun `only one seat job is cancelled when a seat is replaced`() {
        val run = newRun()
        val target = RoundTableSeatIds.explorer(0)
        val other = RoundTableSeatIds.explorer(1)
        val targetJob = Job()
        val otherJob = Job()
        run.markRunning(target, null, "")
        run.markRunning(other, null, "")
        run.registerAttemptJob(target, targetJob)
        run.registerAttemptJob(other, otherJob)

        run.submitCommand(target, RoundTableSeatCommand.Replace(Uuid.random(), "接手模型"))

        assertTrue(targetJob.isCancelled)
        assertFalse(otherJob.isCancelled)
    }

    @Test
    fun `skip marks the seat done and excludes it from the summary`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(1)
        run.markRunning(seatId, null, "")

        run.submitCommand(seatId, RoundTableSeatCommand.Skip)
        run.markSkipped(seatId)

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SKIPPED, seat.status)
        assertTrue(seat.status.isTerminal)
        assertFalse(seat.includeInSummary)
        assertFalse(seat.hasUsableResult)
    }

    @Test
    fun `retry revives a failed seat with the same model`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        val originalModel = run.seat(seatId)!!.modelId
        run.markRunning(seatId, originalModel, "探索模型1")
        run.markFailed(seatId, run.seat(seatId)!!.attempt, "连接超时")
        assertEquals(RoundTableSeatStatus.FAILED, run.seat(seatId)!!.status)

        run.submitCommand(seatId, RoundTableSeatCommand.Retry)

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.PENDING, seat.status)
        assertEquals(originalModel, seat.modelId)
        assertNull(seat.error)
        assertTrue(seat.includeInSummary)
    }

    @Test
    fun `touch only refreshes the current attempt`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        val attempt = run.seat(seatId)!!.attempt

        now = 5_000L
        run.touch(seatId, attempt, chars = 120)
        assertEquals(120, run.seat(seatId)!!.chars)
        assertEquals(5_000L, run.seat(seatId)!!.lastActivityAtMillis)

        // 旧尝试的进度上报必须被忽略，否则会把"已经换人"的位置刷成还在动
        now = 9_000L
        run.touch(seatId, attempt - 1, chars = 999)
        assertEquals(120, run.seat(seatId)!!.chars)
        assertEquals(5_000L, run.seat(seatId)!!.lastActivityAtMillis)
    }

    @Test
    fun `idle time is measured from the last activity`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        now = 1_000L
        run.markRunning(seatId, null, "")
        val attempt = run.seat(seatId)!!.attempt

        now = 3_000L
        run.touch(seatId, attempt, chars = 10)

        assertEquals(2_000L, run.seat(seatId)!!.idleMillis(nowMillis = 5_000L))
        assertEquals(4_000L, run.seat(seatId)!!.elapsedMillis(nowMillis = 5_000L))

        // 已完成的位置不该被算成"卡住"
        run.markSucceeded(seatId, attempt, Uuid.random(), chars = 10)
        assertEquals(0L, run.seat(seatId)!!.idleMillis(nowMillis = 999_999L))
    }

    @Test
    fun `suspect result is kept but excluded from the summary by default`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        val attempt = run.seat(seatId)!!.attempt

        assertTrue(
            run.markSuspect(
                seatId = seatId,
                attempt = attempt,
                messageId = Uuid.random(),
                chars = 80,
                verdict = RoundTableContentVerdict.INTENT_ONLY,
            )
        )

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUSPECT, seat.status)
        assertFalse(seat.hasUsableResult)
        assertFalse(seat.includeInSummary)
        assertEquals(RoundTableContentVerdict.INTENT_ONLY, seat.suspectReason)

        // 用户可以手动把它勾回来
        run.setIncludeInSummary(seatId, true)
        assertTrue(run.seat(seatId)!!.includeInSummary)
    }

    @Test
    fun `cancelling the whole run keeps finished pages and marks the rest interrupted`() {
        val run = newRun()
        val done = RoundTableSeatIds.explorer(0)
        val running = RoundTableSeatIds.explorer(1)
        run.markRunning(done, null, "")
        run.markSucceeded(done, run.seat(done)!!.attempt, Uuid.random(), chars = 1_000)
        run.markRunning(running, null, "")

        run.markAllActiveInterrupted()

        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(done)!!.status)
        assertTrue(run.seat(done)!!.includeInSummary)
        assertEquals(RoundTableSeatStatus.INTERRUPTED, run.seat(running)!!.status)
        assertEquals(RoundTableSeatStatus.INTERRUPTED, run.seat(RoundTableSeatIds.CONTRACT)!!.status)
    }

    @Test
    fun `seat claim prevents launching the same seat twice`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        assertTrue(run.tryClaimSeat(seatId))
        assertFalse(run.tryClaimSeat(seatId))
        run.releaseSeat(seatId)
        assertTrue(run.tryClaimSeat(seatId))
    }

    @Test
    fun `gap checkpoint waits for the user decision`() {
        val run = newRun()
        assertFalse(run.hasOpenGap())

        val deferred = run.openGap(
            RoundTableGapPrompt(usableCount = 2, missingCount = 1, remainingCalls = 2)
        )
        assertTrue(run.hasOpenGap())
        assertEquals(RoundTableRunPhase.PROPOSAL_GAP, run.snapshot().phase)
        assertEquals(1, run.snapshot().gapPrompt?.missingCount)
        assertFalse(deferred.isCompleted)

        assertTrue(run.resolveGap(RoundTableGapDecision.CONTINUE_WITH_CURRENT))
        assertTrue(deferred.isCompleted)
        assertNull(run.snapshot().gapPrompt)
        assertFalse(run.hasOpenGap())
        // 已经回答过就不能再回答第二次
        assertFalse(run.resolveGap(RoundTableGapDecision.END_RUN))
    }

    @Test
    fun `commands are consumed exactly once`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        run.submitCommand(seatId, RoundTableSeatCommand.Stop)

        assertTrue(run.peekCommand(seatId) is RoundTableSeatCommand.Stop)
        assertTrue(run.consumeCommand(seatId) is RoundTableSeatCommand.Stop)
        assertNull(run.consumeCommand(seatId))
    }

    @Test
    fun `stop is rejected for a seat that already finished`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        run.markSucceeded(seatId, run.seat(seatId)!!.attempt, Uuid.random(), chars = 500)

        assertFalse(run.submitCommand(seatId, RoundTableSeatCommand.Stop))
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)!!.status)
    }

    // ==================== v223：截断标记与续跑计数 ====================

    @Test
    fun `truncated success is flagged and excluded from the summary by default`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        val attempt = run.seat(seatId)!!.attempt

        assertTrue(
            run.markSucceeded(
                seatId = seatId,
                attempt = attempt,
                messageId = Uuid.random(),
                chars = 4_000,
                finishReason = "max_tokens",
                truncated = true,
            )
        )

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.SUCCEEDED, seat.status)
        assertTrue(seat.truncated)
        assertEquals("max_tokens", seat.finishReason)
        // 内容不完整，默认不进拍板；用户可手动勾回
        assertFalse(seat.includeInSummary)
        assertTrue(seat.canContinue)

        run.setIncludeInSummary(seatId, true)
        assertTrue(run.seat(seatId)!!.includeInSummary)
    }

    @Test
    fun `a clean success cannot be continued`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        run.markSucceeded(
            seatId = seatId,
            attempt = run.seat(seatId)!!.attempt,
            messageId = Uuid.random(),
            chars = 4_000,
            finishReason = "stop",
            truncated = false,
        )

        assertFalse(run.seat(seatId)!!.canContinue)
        // 正常写完的位置点继续必须被拒绝，避免白花钱
        assertFalse(run.submitCommand(seatId, RoundTableSeatCommand.Continue))
        assertEquals(RoundTableSeatStatus.SUCCEEDED, run.seat(seatId)!!.status)
    }

    @Test
    fun `continue counts as continuation not rerun and clears truncation flags`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        val attempt = run.seat(seatId)!!.attempt
        run.markSucceeded(
            seatId = seatId,
            attempt = attempt,
            messageId = Uuid.random(),
            chars = 4_000,
            finishReason = "length",
            truncated = true,
        )

        assertTrue(run.submitCommand(seatId, RoundTableSeatCommand.Continue))

        val seat = run.seat(seatId)!!
        assertEquals(RoundTableSeatStatus.PENDING, seat.status)
        // 作废序号推高，旧结果写不回来
        assertEquals(attempt + 1, seat.attempt)
        // 续跑单独计数，不污染「重跑次数」
        assertEquals(1, seat.continuations)
        assertEquals(0, seat.reruns)
        assertFalse(seat.truncated)
        assertNull(seat.finishReason)
        assertTrue(seat.includeInSummary)
    }

    @Test
    fun `stopped interrupted failed and suspect seats can all continue`() {
        val run = newRun(explorerCount = 2)
        val stopped = RoundTableSeatIds.explorer(0)
        val failed = RoundTableSeatIds.explorer(1)

        run.markRunning(stopped, null, "")
        run.markStopped(stopped)
        assertTrue(run.seat(stopped)!!.canContinue)

        run.markRunning(failed, null, "")
        run.markFailed(failed, run.seat(failed)!!.attempt, "连接中断")
        assertTrue(run.seat(failed)!!.canContinue)

        run.markRunning(stopped, null, "")
        run.markSuspect(
            seatId = stopped,
            attempt = run.seat(stopped)!!.attempt,
            messageId = Uuid.random(),
            chars = 80,
            verdict = RoundTableContentVerdict.TOO_SHORT,
        )
        assertTrue(run.seat(stopped)!!.canContinue)

        run.markSkipped(failed)
        // 明确跳过的位置不提供续跑
        assertFalse(run.seat(failed)!!.canContinue)
    }

    @Test
    fun `finish reason keywords cover the major providers`() {
        listOf("length", "max_tokens", "MAX_TOKENS", "incomplete:max_output_tokens", "truncated")
            .forEach { reason ->
                assertTrue("$reason 应被识别为截断", RoundTableFinishReason.isTruncated(reason))
            }
        listOf(null, "", "stop", "end_turn", "tool_use", "STOP")
            .forEach { reason ->
                assertFalse("$reason 不该被识别为截断", RoundTableFinishReason.isTruncated(reason))
            }
    }

    @Test
    fun `seat id helpers round trip`() {
        assertEquals("explorer:3", RoundTableSeatIds.explorer(3))
        assertEquals(3, RoundTableSeatIds.explorerIndexOf("explorer:3"))
        assertNull(RoundTableSeatIds.explorerIndexOf(RoundTableSeatIds.MAIN_DRAFT))
    }

    // ==================== v215：停止 / 跳过 / 重跑计数 ====================

    @Test
    fun `stop and skip bump attempt but are not counted as reruns`() {
        val run = newRun()
        val stopped = RoundTableSeatIds.explorer(0)
        val skipped = RoundTableSeatIds.explorer(1)
        run.markRunning(stopped, null, "")
        run.markRunning(skipped, null, "")
        val stoppedAttempt = run.seat(stopped)!!.attempt
        val skippedAttempt = run.seat(skipped)!!.attempt

        run.submitCommand(stopped, RoundTableSeatCommand.Stop)
        run.submitCommand(skipped, RoundTableSeatCommand.Skip)

        // 作废序号必须推高，否则迟到的旧结果会写回来
        assertEquals(stoppedAttempt + 1, run.seat(stopped)!!.attempt)
        assertEquals(skippedAttempt + 1, run.seat(skipped)!!.attempt)
        // 但用户看到的"重跑次数"不能变——停止和跳过没有重新调用模型
        assertEquals(0, run.seat(stopped)!!.reruns)
        assertEquals(0, run.seat(skipped)!!.reruns)
    }

    @Test
    fun `retry replace and auto retry are counted as reruns`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")

        run.submitCommand(seatId, RoundTableSeatCommand.Retry)
        assertEquals(1, run.seat(seatId)!!.reruns)

        run.submitCommand(
            seatId,
            RoundTableSeatCommand.Replace(modelId = Uuid.random(), modelName = "新模型"),
        )
        assertEquals(2, run.seat(seatId)!!.reruns)

        run.markRunning(seatId, null, "")
        assertTrue(run.prepareAutoRetry(seatId))
        assertEquals(3, run.seat(seatId)!!.reruns)
    }

    @Test
    fun `pending command is flagged until the seat settles`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        assertFalse(run.seat(seatId)!!.pendingCommand)

        run.submitCommand(seatId, RoundTableSeatCommand.Stop)
        assertTrue("下了指令但还没处理完，界面要显示正在停止", run.seat(seatId)!!.pendingCommand)

        run.consumeCommand(seatId)
        run.markStopped(seatId)
        assertFalse(run.seat(seatId)!!.pendingCommand)
    }

    @Test
    fun `retry clears the pending flag because the seat starts over`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.explorer(0)
        run.markRunning(seatId, null, "")
        run.submitCommand(seatId, RoundTableSeatCommand.Stop)
        assertTrue(run.seat(seatId)!!.pendingCommand)

        run.submitCommand(seatId, RoundTableSeatCommand.Retry)
        assertFalse(run.seat(seatId)!!.pendingCommand)
        assertEquals(RoundTableSeatStatus.PENDING, run.seat(seatId)!!.status)
    }

    @Test
    fun `skipped seat is not asked about at the gap checkpoint but stopped seat is`() {
        assertTrue(RoundTableSeatStatus.STOPPED.countsAsGap)
        assertTrue(RoundTableSeatStatus.FAILED.countsAsGap)
        assertTrue(RoundTableSeatStatus.SUSPECT.countsAsGap)
        assertTrue(RoundTableSeatStatus.INTERRUPTED.countsAsGap)
        // 跳过是用户明确放弃，不该反复追问
        assertFalse(RoundTableSeatStatus.SKIPPED.countsAsGap)
        // 但"没拿到可用结果"这个判断照旧成立（界面据此显示重试按钮）
        assertTrue(RoundTableSeatStatus.SKIPPED.isMissingResult)
        assertFalse(RoundTableSeatStatus.SUCCEEDED.countsAsGap)
        assertFalse(RoundTableSeatStatus.RUNNING.countsAsGap)
    }
}
