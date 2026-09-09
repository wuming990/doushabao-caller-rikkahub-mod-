package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.RoundTableMaterial
import java.util.concurrent.ConcurrentHashMap

/**
 * v213 圆桌调度器：**每个座位各自独立跑，互不连带。**
 *
 * v212 的做法是 `tasks.awaitAll()`——必须等所有模型都结束才能往下走，
 * 于是一个模型不返回就把整场拖住，用户既停不掉它，也换不了人。
 *
 * 这里改成"对账式"调度：
 * - 谁还没跑完就给它起一个执行协程，谁跑完就不再管；
 * - 用户随时可以对某个座位下"停止 / 换模型 / 重试 / 跳过"，只影响那一个座位；
 * - 换人时旧连接被单独取消，且尝试编号 +1，旧请求即使晚到也写不进来；
 * - 看门狗只在"长时间完全没动静"时才自动停手，不会误杀 MAX 长思考。
 *
 * 真正调模型和写页面都通过 [RoundTableSeatCallbacks] 交给 ChatService，
 * 因此本类可以用假的回调做单元测试。
 */
internal class RoundTableCoordinator(
    val run: RoundTableRun,
    private val callbacks: RoundTableSeatCallbacks,
    private val nowMillis: () -> Long,
    private val maxEffort: Boolean = false,
    private val watchdogIntervalMillis: Long = RoundTableStallPolicy.CHECK_INTERVAL_MILLIS,
    private val autoRetryDelayMillis: Long = DEFAULT_AUTO_RETRY_DELAY_MILLIS,
    /**
     * v239：等模型出结果时，每隔这么久看一眼「用户有没有下指令」。
     *
     * 这个值就是「点了停止最久多长时间生效」的上限。不能靠上游断开来触发，
     * 因为出问题的恰恰是那种连接不断、一个字也不推的上游。
     */
    private val commandPollIntervalMillis: Long = DEFAULT_COMMAND_POLL_INTERVAL_MILLIS,
) {
    private val results = ConcurrentHashMap<String, UIMessage>()

    /**
     * v223：各座位待续跑的「已产出内容」前缀。
     *
     * 用户点「继续输出」时把当前半截内容记在这里，下一次调用模型时作为上下文回灌，
     * 让模型从中断处接着写；成功写入完整结果后清除。
     * 重试 / 换模型属于「从头重跑」，会显式清掉它。
     */
    private val continuePrefixes = ConcurrentHashMap<String, String>()

    fun resultOf(seatId: String): UIMessage? = results[seatId]

    /**
     * 这一组座位里可以送去拍板的材料（按座位顺序）。
     *
     * v215 修正：不再要求状态必须是 SUCCEEDED。只要这个位置留下了内容、且用户没有把它排除，
     * 就算进去——否则「疑似无效」和「已停止」的页即使用户手动勾了"算进结论"也不会生效。
     */
    fun usableResults(seatIds: List<String>): List<Pair<RoundTableSeat, UIMessage>> =
        run.snapshot().seats
            .filter { it.seatId in seatIds && it.includeInSummary }
            .mapNotNull { seat -> results[seat.seatId]?.let { seat to it } }

    /**
     * 跑一组座位，直到这一组全部有结论（成功 / 疑似无效 / 失败 / 停止 / 跳过）。
     *
     * 组内是并发的；单个座位失败或被停止都不会影响同组其它座位。
     */
    suspend fun runGroup(
        seatIds: List<String>,
        materials: () -> List<RoundTableMaterial>,
    ) {
        if (seatIds.isEmpty()) return
        supervisorScope {
            val watchdog = launch { watchdogLoop(seatIds) }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val before = run.snapshot()
                    val group = before.seats.filter { it.seatId in seatIds }
                    if (group.isEmpty()) break

                    // 1) 给所有还没跑完、且当前没人负责的座位起执行协程
                    group.forEach { seat ->
                        if (!seat.status.isTerminal && run.tryClaimSeat(seat.seatId)) {
                            launch {
                                try {
                                    runSeat(seat.seatId, materials)
                                } finally {
                                    // 兜底：执行协程退出时这个座位必须已经有结论，
                                    // 否则对账循环会等一个永远不会到来的状态变化。
                                    run.seat(seat.seatId)?.let { latest ->
                                        if (!latest.status.isTerminal) {
                                            run.markFailed(
                                                seat.seatId,
                                                latest.attempt,
                                                UNEXPECTED_EXIT_MARKER,
                                            )
                                        }
                                    }
                                    run.releaseSeat(seat.seatId)
                                }
                            }
                        }
                    }

                    // 2) 全组都有结论就收工；顺手清掉终态座位上残留的指令，避免对账循环空转
                    val settledSeats = run.snapshot().seats.filter { it.seatId in seatIds }
                    if (settledSeats.all { it.status.isTerminal }) {
                        settledSeats.forEach { run.consumeCommand(it.seatId) }
                        break
                    }

                    // 3) 等座位状态真的发生变化再对账（只看状态与尝试编号，不被流式进度惊醒）
                    val beforeSignature = signatureOf(before, seatIds)
                    run.state.first { signatureOf(it, seatIds) != beforeSignature }
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun signatureOf(state: RoundTableRunState, seatIds: List<String>): String =
        state.seats
            .filter { it.seatId in seatIds }
            .joinToString("|") { "${it.seatId}#${it.status}#${it.attempt}" }

    /**
     * 一个座位的执行循环。
     *
     * 换模型或重试会回到循环开头，重新用新的尝试编号跑一次；
     * 停止、跳过、超时、失败到底则退出循环。
     */
    private suspend fun runSeat(seatId: String, materials: () -> List<RoundTableMaterial>) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val seat = run.seat(seatId) ?: return
            if (seat.status.isTerminal) return

            // 还没开跑就被下了指令（例如刚排队就被点了跳过）
            run.consumeCommand(seatId)?.let { queued ->
                if (applyCommand(seatId, queued)) return
            }

            val current = run.seat(seatId) ?: return
            if (current.status.isTerminal) return

            val modelName = callbacks.resolveModelName(current)
            if (current.modelId == null || modelName == null) {
                run.markFailed(seatId, current.attempt, MODEL_MISSING_MARKER)
                callbacks.writeNotice(current, RoundTableSeatNotice.ModelMissing)
                callbacks.onStateChanged()
                return
            }

            val attempt = current.attempt
            run.markRunning(seatId, current.modelId, modelName)
            callbacks.onStateChanged()

            // v223：续跑时把已产出内容带进去，让模型接着写而不是从头再来
            val continueFrom = continuePrefixes[seatId]

            // v239：生成协程交给 RoundTableRun 的独立作用域跑，调度协程只负责等它。
            //
            // 旧写法是 `supervisorScope { async { generate() } }`，而 supervisorScope
            // 的语义是「block 返回后必须等所有子协程真正结束」。用户点停止时 cancel()
            // 只是**请求**取消，遇到「连接不断、一个字也不推」的上游（中转站常见），
            // 底层读一直阻塞、协程到不了可取消的挂起点，于是 supervisorScope 永远不返回，
            // 下面「取走指令并执行」的代码一行都跑不到 ——
            // 这就是用户看到的「点停止按钮变灰、点跳过没反应、整场焊死」。
            val deferred = run.launchAttempt(seatId) {
                callbacks.generate(
                    seat = run.seat(seatId) ?: current,
                    attempt = attempt,
                    materials = materials(),
                    continueFrom = continueFrom,
                    onActivity = { chars ->
                        run.touch(seatId, attempt, chars)
                        callbacks.onStateChanged()
                    },
                )
            }
            val result = try {
                awaitAttemptOrCommand(seatId, deferred)
            } finally {
                run.clearAttemptJob(seatId, deferred)
            }
            // 整场被取消时必须向上传播，不能当成单个座位的问题
            currentCoroutineContext().ensureActive()

            if (result == null) {
                // 指令抢先到达：立刻断开这次尝试，不等上游把话说完
                deferred.cancel(RoundTableSeatCancellation(seatId))
            }

            // 用户/看门狗在这期间下过指令：指令优先，模型返回什么都不算
            run.consumeCommand(seatId)?.let { command ->
                if (applyCommand(seatId, command)) return
                continue
            }

            // v239：这期间也可能被「强制停止 / 强制跳过」就地落了终态
            // （那条路径根本不走指令队列）。此时绝不能再写第二页说明或结果。
            run.seat(seatId)?.let { if (it.status.isTerminal) return }

            if (result == null) {
                // 指令已被强制通道取走：本轮不做判定，回到循环开头重新对账
                continue
            }

            val message = result.getOrNull()
            if (message != null) {
                // v229：finishWithResult 返回 false 表示已经安排了自动续跑，本座位还没完
                if (finishWithResult(seatId, attempt, message)) return
                delay(autoRetryDelayMillis)
                continue
            }

            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                // 被定向取消但没有留下指令：按"已停止"处理，不自动重试
                run.markStopped(seatId)
                callbacks.writeNotice(run.seat(seatId) ?: current, RoundTableSeatNotice.Stopped)
                callbacks.onStateChanged()
                return
            }

            val seatNow = run.seat(seatId) ?: return
            // v229：断线 / 报错时先尝试**自动断点续跑**，保住已经吐出来的半截成果；
            // 只有续跑不成立（没内容、预算用完、疑似无效、配置类错误）才回落到旧的从头重试。
            if (tryAutoContinue(seatId, error = error, timedOut = false)) {
                delay(autoRetryDelayMillis)
                continue
            }
            val retryVerdict = RoundTableRetryPolicy.evaluate(seatNow, error)
            if (retryVerdict.shouldRetry && run.prepareAutoRetry(seatId)) {
                callbacks.onStateChanged()
                delay(autoRetryDelayMillis)
                continue
            }

            val reason = error?.message?.takeIf { it.isNotBlank() }
                ?: error?.toString().orEmpty()
            run.markFailed(seatId, attempt, reason)
            callbacks.writeNotice(
                run.seat(seatId) ?: seatNow,
                RoundTableSeatNotice.Failed(
                    reason = reason,
                    autoRetries = seatNow.autoRetries,
                    retryVerdict = retryVerdict,
                ),
            )
            callbacks.onStateChanged()
            return
        }
    }

    /**
     * v239：等这一次尝试出结果，但**用户的指令随时可以抢先**。
     *
     * 返回 null = 「有指令待处理，别等了」，调用方应立刻断开这次尝试并去执行指令；
     * 返回非空 = 这次尝试的结果（成功或异常）。
     *
     * 为什么用轮询而不是等信号：指令放在一个普通并发表里，不是 Flow；与其为它再造
     * 一套通知机制，不如每 [commandPollIntervalMillis] 毫秒瞄一眼。代价是指令最坏
     * 晚生效这么一小会儿，换来的是**任何上游都拖不住停止键**。
     *
     * 实现要点：这里用 [Deferred.join] 而不是 `runCatching { await() }` 去等。
     * 如果写成 `withTimeoutOrNull { runCatching { deferred.await() } }`，
     * runCatching 会把超时本身当成一个「失败结果」抓回来，于是每 250 毫秒就假报一次
     * 生成失败，进而触发自动重试 —— 比原来的死锁更糟。join 不抛业务异常，
     * 超时干净地由 withTimeoutOrNull 自己收掉。
     */
    private suspend fun awaitAttemptOrCommand(
        seatId: String,
        deferred: Deferred<UIMessage>,
    ): Result<UIMessage>? {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (run.peekCommand(seatId) != null) return null
            val finished = withTimeoutOrNull(commandPollIntervalMillis) { deferred.join() } != null
            if (finished) {
                // 结果和指令同时到达时指令优先，与旧行为保持一致
                if (run.peekCommand(seatId) != null) return null
                return runCatching { deferred.await() }
            }
        }
    }

    /**
     * v239：把「强制停止 / 强制跳过」的页面补写出来。
     *
     * 座位状态由调用方（ChatService 的强制通道）先就地改掉，界面立刻有反应；
     * 这里只负责事后把半截内容或说明页落进对话，写页面慢一点不影响用户看到状态变化。
     */
    suspend fun writeForcedSettleNotice(seatId: String, skipped: Boolean) {
        val seat = run.seat(seatId) ?: return
        if (skipped) {
            // 跳过 = 彻底放弃这个位置，不保留半截内容
            results.remove(seatId)
            callbacks.writeNotice(seat, RoundTableSeatNotice.Skipped)
        } else {
            // 停止 = 把已经吐出来的半截内容留成一页，用户花的钱不白费
            val partial = callbacks.writePartialResult(seat, RoundTableSeatNotice.Stopped)
            if (partial != null) {
                results[seatId] = partial
            } else {
                callbacks.writeNotice(seat, RoundTableSeatNotice.Stopped)
            }
        }
        callbacks.onStateChanged()
    }

    /** 返回 true 表示这个座位已经有最终结论，执行循环应该退出 */
    private suspend fun applyCommand(seatId: String, command: RoundTableSeatCommand): Boolean {
        val seat = run.seat(seatId) ?: return true
        return when (command) {
            is RoundTableSeatCommand.Stop -> {
                // v215：停止 = 先别跑了，但把已经吐出来的半截内容留成一页给用户看。
                // 默认不算进结论（markStopped 会置 includeInSummary=false），用户可手动勾回。
                val partial = callbacks.writePartialResult(seat, RoundTableSeatNotice.Stopped)
                if (partial != null) {
                    results[seatId] = partial
                } else {
                    callbacks.writeNotice(seat, RoundTableSeatNotice.Stopped)
                }
                run.markStopped(seatId)
                callbacks.onStateChanged()
                true
            }

            is RoundTableSeatCommand.Skip -> {
                // v215：跳过 = 彻底放弃这个位置，不保留半截内容，缺席检查点也不再提醒它
                results.remove(seatId)
                run.markSkipped(seatId)
                callbacks.writeNotice(seat, RoundTableSeatNotice.Skipped)
                callbacks.onStateChanged()
                true
            }

            is RoundTableSeatCommand.Timeout -> {
                // v229：卡死（上游不稳定 / 网络中断）也先自动救两次：
                // 有半截内容就接着写，一个字都没有就等于重来一次；预算用完才落终态。
                if (tryAutoContinue(seatId, error = null, timedOut = true)) {
                    false
                } else {
                    run.markTimedOut(seatId, TIMEOUT_MARKER)
                    callbacks.writeNotice(seat, RoundTableSeatNotice.TimedOut(command.idleMillis))
                    callbacks.onStateChanged()
                    true
                }
            }

            is RoundTableSeatCommand.Continue -> {
                // v223：续跑 = 保留已产出内容，让模型从中断处接着写。
                // 与重试/换人的根本区别：绝不清空 results，也不丢弃半截内容。
                val prefix = callbacks.partialTextOf(seat).trim().takeIf { it.isNotBlank() }
                    ?: results[seatId]?.toText()?.trim()?.takeIf { it.isNotBlank() }
                if (prefix != null) {
                    continuePrefixes[seatId] = prefix
                }
                callbacks.onStateChanged()
                false
            }

            is RoundTableSeatCommand.Retry,
            is RoundTableSeatCommand.Replace,
                -> {
                // 换人/重试：清掉这个座位的旧结果、续跑前缀与旧输出（流式快照/旧页面），
                // 避免把上一个人的内容算进材料或冒充新模型产出。（v276：清旧输出走回调）
                results.remove(seatId)
                continuePrefixes.remove(seatId)
                callbacks.clearSeatOutput(seat)
                callbacks.onStateChanged()
                false
            }
        }
    }

    /**
     * v229：自动断点续跑。
     *
     * 返回 true 表示已经把座位复活成待跑状态、并且把半截内容记成续跑前缀，
     * 调用方应当继续执行循环（不要落终态）。
     *
     * 判定完全交给 [RoundTableAutoContinuePolicy]：
     * - 「疑似无效」不自动续跑，停下来等用户决策（用户明确要求，防止硬编产生噪音）；
     * - 用户主动停止不自动续跑；
     * - 鉴权 / 余额 / 参数类错误不续跑；
     * - 每个位置最多自动续 [RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS] 次。
     */
    private suspend fun tryAutoContinue(
        seatId: String,
        error: Throwable?,
        timedOut: Boolean,
    ): Boolean {
        val seat = run.seat(seatId) ?: return false
        // v276：优先取 Callbacks（ChatService 侧）的流式快照；没有实时快照时回退到
        // Coordinator 自己保存的 results，避免「没有流式快照」时丢掉已产出内容从头续跑。
        val partialRaw = callbacks.partialTextOf(seat).takeIf { it.isNotBlank() }
            ?: results[seatId]?.toText()?.takeIf { it.isNotBlank() }
        val partial = (partialRaw ?: "").trim()
        val verdict = RoundTableAutoContinuePolicy.evaluate(
            seat = seat,
            partialText = partial,
            error = error,
            timedOut = timedOut,
        )
        if (!verdict.shouldContinue) return false
        if (!run.prepareAutoContinue(seatId)) return false
        // 有半截内容才带前缀；卡死且一个字都没吐出来时等于重来一次
        if (partial.isNotBlank()) {
            continuePrefixes[seatId] = partial
        }
        val armed = run.seat(seatId) ?: seat
        callbacks.writeNotice(
            armed,
            RoundTableSeatNotice.AutoContinuing(
                attemptNo = armed.autoContinuations,
                maxAttempts = RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS,
                timedOut = timedOut,
            ),
        )
        callbacks.onStateChanged()
        return true
    }

    /**
     * 返回 true 表示这个座位已经有最终结论；false 表示已安排自动续跑，执行循环应继续。
     */
    private suspend fun finishWithResult(seatId: String, attempt: Int, message: UIMessage): Boolean {
        // 迟到的旧请求：这里挡住，绝不覆盖新模型的结果
        if (!run.isCurrentAttempt(seatId, attempt)) return true
        val seat = run.seat(seatId) ?: return true
        val text = message.toText().trim()
        val verdict = RoundTableValidity.evaluate(seat.role, text)
        // v276：这次尝试的续跑前缀。有前缀时，按「新增文字」判定与记字数，
        // 旧前缀是上一次尝试的产出，不能算成这一轮新模型的成果。
        val continuationPrefix = continuePrefixes[seatId]
        val chars = if (continuationPrefix.isNullOrBlank()) {
            text.length
        } else {
            roundTableContinuationDelta(continuationPrefix, text)
        }
        // v276：续跑白跑了（结果与旧前缀相同，或只多了空白）——不算成功，也不覆盖旧结果。
        // 保留旧 results/续跑前缀供用户再次继续，标失败并写 NoProgress 说明，停止自动循环。
        if (!continuationPrefix.isNullOrBlank() && chars == 0) {
            run.markFailed(seatId, attempt, NO_PROGRESS_MARKER)
            callbacks.writeNotice(seat, RoundTableSeatNotice.NoProgress)
            callbacks.onStateChanged()
            // 返回 true = 本座位已有最终结论，执行协程退出。不写页、不覆盖旧结果、不自动续跑。
            return true
        }
        // v223：读取 Provider 的结束原因，识别「达到单次输出上限被截断」。
        // 在此之前圆桌完全不看它，半截产出会被当成已完成或误判成「太短」。
        val finishReason = message.finishReason
        // v272：无声截断 —— 上游把流关了，却既没发协议终止信号、也没给任何结束原因
        // （v269 主对话同款判定）。圆桌此前看不到这种截断：半截产出被当成完整成功
        // （字数够）或误判成「疑似无效·太短」，座位既不标截断、也不给续跑入口，
        // 用户只能眼看内容残缺（真机实锤）。与主对话相同的双条件缺一不可：
        // Gemini 正常收尾也是「无终止信号」形态，但它有结束原因，不能误伤。
        val silentlyTruncated = message.truncatedWithoutSentinel && finishReason.isNullOrBlank()
        val truncated = RoundTableFinishReason.isTruncated(finishReason) || silentlyTruncated

        callbacks.writeResult(seat, attempt, message, verdict, truncated)
        results[seatId] = message
        // 已经拿到完整落库结果，续跑前缀不再需要
        continuePrefixes.remove(seatId)

        if (verdict.isUsable) {
            run.markSucceeded(seatId, attempt, message.id, chars, finishReason, truncated)
            // v229：被截断说明还没写完，自动接着写（预算内），不必等用户手点「继续输出」
            if (truncated && tryAutoContinue(seatId, error = null, timedOut = false)) {
                return false
            }
        } else {
            run.markSuspect(seatId, attempt, message.id, chars, verdict, finishReason, truncated)
            // 疑似无效：**不自动续跑**。主流程会在本阶段结束后开「审阅检查点」，
            // 整场停下来等用户决定算不算进方案（用户明确要求，避免强制续写产生噪音）。
        }
        callbacks.onStateChanged()
        return true
    }

    private suspend fun watchdogLoop(seatIds: List<String>) {
        while (true) {
            delay(watchdogIntervalMillis)
            val now = nowMillis()
            run.snapshot().seats
                .filter { it.seatId in seatIds }
                .forEach { seat ->
                    // v239：指令下达了、却没有任何执行协程负责这个座位 ——
                    // 这段兜底在 v239 定稿时被删掉了：只要 runGroup 还活着，对账循环
                    // 会立刻给「非终态且没人负责」的座位补起执行协程并消费掉指令，
                    // 所以这个条件在这里永远不成立（实测证明它是死代码）。
                    // runGroup 已经退出的情形由 ChatService 的强制通道兜底
                    // （见 controlRoundTableSeat 里的 commandStuck 分支）。
                    if (RoundTableStallPolicy.shouldForceStop(seat, now, maxEffort)) {
                        run.submitCommand(
                            seat.seatId,
                            RoundTableSeatCommand.Timeout(seat.idleMillis(now)),
                        )
                    }
                }
            // 让界面上的"等待较久"提示能刷新
            callbacks.onStateChanged()
        }
    }

    companion object {
        const val DEFAULT_AUTO_RETRY_DELAY_MILLIS = 1_500L

        /**
         * v239：指令轮询间隔 = 「点停止最久多久生效」。
         *
         * 250ms 是在「反应够快，用户感觉是即时的」和「别把 CPU 用在空转上」之间取的值。
         */
        const val DEFAULT_COMMAND_POLL_INTERVAL_MILLIS = 250L
        const val TIMEOUT_MARKER = "__round_table_timeout__"
        const val MODEL_MISSING_MARKER = "__round_table_model_missing__"
        const val UNEXPECTED_EXIT_MARKER = "__round_table_unexpected_exit__"

        /** v276：续跑没有任何新增内容时，失败原因用这个标记 */
        const val NO_PROGRESS_MARKER = "__round_table_no_progress__"
    }
}

/** 座位没有拿到结果时写进页面的说明 */
internal sealed interface RoundTableSeatNotice {
    data class Failed(
        val reason: String,
        val autoRetries: Int,
        val retryVerdict: RoundTableRetryVerdict,
    ) : RoundTableSeatNotice

    data class TimedOut(val idleMillis: Long) : RoundTableSeatNotice

    /**
     * v229：已经自动安排了断点续跑。
     *
     * 写这一页是为了让用户看得见「为什么它自己又跑起来了」，以及自动救了第几次，
     * 避免莫名其妙多花钱。
     */
    data class AutoContinuing(
        val attemptNo: Int,
        val maxAttempts: Int,
        val timedOut: Boolean,
    ) : RoundTableSeatNotice

    data object Stopped : RoundTableSeatNotice

    data object Skipped : RoundTableSeatNotice

    data object ModelMissing : RoundTableSeatNotice

    /**
     * v276：续跑没有产生任何新增内容。
     *
     * 区别于 [Failed]：这不是模型报错，而是「这次续跑白跑了」——结果与旧前缀相同或只多了空白。
     * 旧结果与续跑前缀都保留，用户可以再次继续或换模型。
     */
    data object NoProgress : RoundTableSeatNotice
}

/** 调度器需要外部提供的能力；ChatService 实现它 */
internal interface RoundTableSeatCallbacks {
    /**
     * 真正调用模型跑这一个座位。
     *
     * @param continueFrom v223：续跑用的「已产出内容」。非空时必须把它作为上下文回灌，
     *        让模型从中断处接着写，并把最终产出与它拼成一份完整内容；
     *        为空表示这是一次从头开始的正常生成。
     * @param onActivity 只要有任何动静（正文、思考、工具调用）就调用一次，参数是当前正文字数。
     *        判断卡死靠的是这个回调的时间间隔，所以务必在思考阶段也调用。
     */
    suspend fun generate(
        seat: RoundTableSeat,
        attempt: Int,
        materials: List<RoundTableMaterial>,
        continueFrom: String? = null,
        onActivity: (Int) -> Unit,
    ): UIMessage

    /**
     * 写正常结果页（包含疑似无效的结果，页面照样保留）。
     *
     * @param truncated v223：本次产出是否因达到模型单次上限被截断（页尾会补一句醒目说明）
     */
    suspend fun writeResult(
        seat: RoundTableSeat,
        attempt: Int,
        message: UIMessage,
        verdict: RoundTableContentVerdict,
        truncated: Boolean = false,
    )

    /** 写"这个位置没有结果"的说明页 */
    suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice)

    /**
     * v215：把这个座位当前已经流式吐出来的半截内容写成一页，并返回那条消息。
     *
     * 用于「停止」：用户点停止时模型往往已经说了一部分，直接丢掉很浪费。
     * 完全没有内容（刚开始就被停掉、或该角色不支持保留）时返回 null，
     * 调用方会改写普通说明页。
     */
    suspend fun writePartialResult(seat: RoundTableSeat, notice: RoundTableSeatNotice): UIMessage?

    /**
     * v223：这个座位当前留下的半截文本（用于「继续输出」）。
     *
     * 失败 / 意外中断时结果没进 results，但流式快照里往往还留着内容，
     * 续跑要能捞回来，否则用户前面花的钱依然白费。没有内容时返回空串。
     */
    fun partialTextOf(seat: RoundTableSeat): String

    /** 模型是否还在（用户可能已经把它删了）；返回显示名 */
    fun resolveModelName(seat: RoundTableSeat): String?

    /**
     * v276：换模型 / 重试时清掉这个座位的旧输出（流式快照、旧页面等），
     * 避免旧模型的内容冒充新模型产出。实现方必须保留该座位自己的过程对话历史。
     *
     * 默认空实现：无关的测试实现不需要为了这个新回调被迫改变。
     */
    suspend fun clearSeatOutput(seat: RoundTableSeat) = Unit

    /** 状态有变化，用来刷新界面上的状态文字 */
    fun onStateChanged()
}
