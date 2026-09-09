package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * v213 圆桌运行时状态。
 *
 * 一轮圆桌一个实例，负责三件事：
 * 1. 保存每个座位的状态，供界面实时显示与操作；
 * 2. 接收界面下达的"停止 / 更换模型 / 重试 / 跳过"指令；
 * 3. 用**尝试编号**（[RoundTableSeat.attempt]）挡住迟到的旧请求，
 *    避免刚被换掉的模型稍后返回结果，把新模型的结果覆盖掉。
 *
 * 只放状态与协调逻辑，不负责调用模型，也不负责写页面，方便单元测试。
 */
internal class RoundTableRun(
    runId: Uuid,
    conversationId: Uuid,
    nodeId: Uuid,
    autoSummarize: Boolean,
    seats: List<RoundTableSeat>,
    private val nowMillis: () -> Long,
    /**
     * v239：跑模型的那些协程放在这个上下文里，**故意与调度协程分开**。
     *
     * 为什么必须分开（本轮真机事故的根因）：旧写法是在调度协程内用
     * `supervisorScope { async { generate() } }`，而 supervisorScope 的语义是
     * 「block 返回后必须等所有子协程真正结束」。用户点停止时 `cancel()` 只是
     * **请求**取消，中转站那种「保持连接但不推数据」的上游会让底层读操作一直阻塞，
     * 协程到不了可取消的挂起点 —— 于是 supervisorScope 永远不返回，调度协程里
     * 「取走用户指令并执行」的代码一行都跑不到，界面就永久停在「正在处理你的指令…」。
     *
     * 放进独立作用域后，调度协程可以随时抛下它往前走；那个卡住的连接变成孤儿，
     * 由底层读超时或 [cleanup] 收尾，不再拖住任何人。
     */
    attemptContext: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        RoundTableRunState(
            runId = runId,
            conversationId = conversationId,
            nodeId = nodeId,
            phase = RoundTableRunPhase.CONTRACT,
            autoSummarize = autoSummarize,
            seats = seats,
        )
    )
    val state: StateFlow<RoundTableRunState> = _state.asStateFlow()

    /** 界面下达但还没被座位执行协程取走的指令 */
    private val commands = ConcurrentHashMap<String, RoundTableSeatCommand>()

    /** 每个座位当前那一次尝试的任务，用于"只停这一个位置" */
    private val attemptJobs = ConcurrentHashMap<String, Job>()

    /**
     * v239：跑模型用的独立作用域（见构造参数 attemptContext 的说明）。
     *
     * 用 SupervisorJob 是为了让某个座位的连接崩掉不牵连别的座位。
     */
    private val attemptScope = CoroutineScope(SupervisorJob() + attemptContext)

    /** 已经有执行协程负责的座位，防止调度器重复启动同一个位置 */
    private val claimedSeats: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 缺席检查点：等界面回答"补齐 / 继续 / 结束" */
    private val gapDecision = AtomicReference<CompletableDeferred<RoundTableGapDecision>?>(null)

    val runId: Uuid get() = _state.value.runId
    val nodeId: Uuid get() = _state.value.nodeId

    fun seat(seatId: String): RoundTableSeat? = _state.value.seat(seatId)

    fun snapshot(): RoundTableRunState = _state.value

    // ---- 阶段 ----

    fun setPhase(phase: RoundTableRunPhase) {
        _state.update { it.copy(phase = phase) }
    }

    // ---- 座位状态变更 ----

    private inline fun updateSeat(seatId: String, crossinline block: (RoundTableSeat) -> RoundTableSeat) {
        _state.update { state ->
            val index = state.seats.indexOfFirst { it.seatId == seatId }
            if (index < 0) return@update state
            val updated = state.seats.toMutableList()
            updated[index] = block(updated[index])
            state.copy(seats = updated)
        }
    }

    /** 这一次尝试还是当前有效的那一次吗？不是就必须丢弃结果 */
    fun isCurrentAttempt(seatId: String, attempt: Int): Boolean =
        seat(seatId)?.attempt == attempt

    /**
     * v224：绑定这个位置对应的「独立对话」。
     *
     * 用户原话：「点击『查看过程』可以直接进入那个对话观看过程」。
     * 因此每个位置在开跑时会真的建一个 Conversation，界面只负责跳过去；
     * 这里只保存 ID，绑定后不再变（重试 / 换模型 / 续跑都追加到同一个对话里）。
     */
    fun bindSeatConversation(seatId: String, conversationId: String) {
        if (conversationId.isBlank()) return
        updateSeat(seatId) {
            if (!it.chatConversationId.isNullOrBlank()) it
            else it.copy(chatConversationId = conversationId)
        }
    }

    fun markRunning(seatId: String, modelId: Uuid?, modelName: String) {
        val now = nowMillis()
        updateSeat(seatId) {
            it.copy(
                modelId = modelId ?: it.modelId,
                modelName = modelName.ifBlank { it.modelName },
                status = RoundTableSeatStatus.RUNNING,
                chars = 0,
                startedAtMillis = now,
                lastActivityAtMillis = now,
                error = null,
                suspectReason = null,
                pendingCommand = false,
            )
        }
    }

    /**
     * 有动静就刷新一次。
     *
     * [chars] 只是给界面看的进度；判断"是否卡死"依据的是本方法被调用的时间，
     * 因此思考中、调用工具中也应该调用它，避免 MAX 长思考被误判成卡住。
     */
    fun touch(seatId: String, attempt: Int, chars: Int) {
        if (!isCurrentAttempt(seatId, attempt)) return
        val now = nowMillis()
        updateSeat(seatId) {
            if (it.status != RoundTableSeatStatus.RUNNING) it
            else it.copy(chars = chars, lastActivityAtMillis = now)
        }
    }

    fun markSucceeded(
        seatId: String,
        attempt: Int,
        messageId: Uuid,
        chars: Int,
        finishReason: String? = null,
        truncated: Boolean = false,
    ): Boolean {
        if (!isCurrentAttempt(seatId, attempt)) return false
        val now = nowMillis()
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.SUCCEEDED,
                messageId = messageId,
                chars = chars,
                lastActivityAtMillis = now,
                error = null,
                suspectReason = null,
                pendingCommand = false,
                finishReason = finishReason,
                truncated = truncated,
                // v223：被截断的产出并不完整，默认不进最终拍板；
                // 用户可以先点「继续输出」补齐，或手动勾回来
                includeInSummary = if (truncated) false else it.includeInSummary,
            )
        }
        return true
    }

    fun markSuspect(
        seatId: String,
        attempt: Int,
        messageId: Uuid,
        chars: Int,
        verdict: RoundTableContentVerdict,
        finishReason: String? = null,
        truncated: Boolean = false,
    ): Boolean {
        if (!isCurrentAttempt(seatId, attempt)) return false
        val now = nowMillis()
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.SUSPECT,
                messageId = messageId,
                chars = chars,
                lastActivityAtMillis = now,
                error = null,
                suspectReason = verdict,
                // 疑似无效默认不进最终拍板，用户可以手动勾回来
                includeInSummary = false,
                pendingCommand = false,
                finishReason = finishReason,
                truncated = truncated,
            )
        }
        return true
    }

    fun markFailed(seatId: String, attempt: Int, error: String?): Boolean {
        if (!isCurrentAttempt(seatId, attempt)) return false
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.FAILED,
                error = error,
                includeInSummary = false,
                pendingCommand = false,
            )
        }
        return true
    }

    fun markStopped(seatId: String) {
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.STOPPED,
                includeInSummary = false,
                pendingCommand = false,
            )
        }
    }

    /** 看门狗判定长时间无响应后自动停手；算失败，但写明是超时 */
    fun markTimedOut(seatId: String, reason: String) {
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.FAILED,
                error = reason,
                includeInSummary = false,
                pendingCommand = false,
            )
        }
    }

    /**
     * 程序内部的自动重来一次（不是用户点的重试）。
     *
     * 同样会让尝试编号 +1，这样上一次的迟到结果不会被写进来。
     */
    fun prepareAutoRetry(seatId: String): Boolean {
        val seat = seat(seatId) ?: return false
        if (seat.autoRetries >= RoundTableRetryPolicy.MAX_AUTO_RETRIES) return false
        updateSeat(seatId) {
            it.copy(
                attempt = it.attempt + 1,
                reruns = it.reruns + 1,
                autoRetries = it.autoRetries + 1,
                status = RoundTableSeatStatus.PENDING,
                chars = 0,
                error = null,
                suspectReason = null,
                pendingCommand = false,
            )
        }
        return true
    }

    /**
     * v229：程序内部的自动**断点续跑**（不是用户点的「继续输出」）。
     *
     * 与 [prepareAutoRetry] 的区别：
     * - 续跑保留已产出内容（前缀由调度器记在 continuePrefixes 里），不清空结果；
     * - 不推高 [RoundTableSeat.reruns]（那是「重跑」的口径），只计 continuations；
     * - 单独用 [RoundTableSeat.autoContinuations] 记预算，与用户手动续跑分开。
     */
    fun prepareAutoContinue(seatId: String): Boolean {
        val seat = seat(seatId) ?: return false
        if (seat.autoContinuations >= RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS) return false
        updateSeat(seatId) {
            it.copy(
                attempt = it.attempt + 1,
                continuations = it.continuations + 1,
                autoContinuations = it.autoContinuations + 1,
                status = RoundTableSeatStatus.PENDING,
                chars = 0,
                error = null,
                suspectReason = null,
                includeInSummary = true,
                pendingCommand = false,
                finishReason = null,
                truncated = false,
            )
        }
        return true
    }

    fun markSkipped(seatId: String) {
        updateSeat(seatId) {
            it.copy(
                status = RoundTableSeatStatus.SKIPPED,
                includeInSummary = false,
                pendingCommand = false,
            )
        }
    }

    fun markInterrupted(seatId: String) {
        updateSeat(seatId) {
            if (it.status.isTerminal) it
            else it.copy(
                status = RoundTableSeatStatus.INTERRUPTED,
                includeInSummary = false,
                pendingCommand = false,
            )
        }
    }

    /** 整场被取消时，把还在跑的位置标成意外中断，已完成的一律保留 */
    fun markAllActiveInterrupted() {
        _state.update { state ->
            state.copy(
                seats = state.seats.map {
                    if (it.status.isTerminal) it
                    else it.copy(
                        status = RoundTableSeatStatus.INTERRUPTED,
                        includeInSummary = false,
                        pendingCommand = false,
                    )
                }
            )
        }
    }

    fun setIncludeInSummary(seatId: String, include: Boolean) {
        updateSeat(seatId) { it.copy(includeInSummary = include) }
    }

    // ---- 界面指令 ----

    /**
     * 下达指令：先改状态，再取消这一次尝试的连接。
     *
     * 顺序很重要：先把 attempt +1，旧请求即使这时才返回，也已经不是当前尝试，结果会被丢弃。
     *
     * v215 说明：[RoundTableSeat.attempt] 是内部的"作废序号"，任何指令都会推高它；
     * 用户可见的"重跑次数"是 [RoundTableSeat.reruns]，只有真正重新调用模型
     * （重试 / 换模型 / 自动重试）才会 +1。停止和跳过不算重跑。
     */
    fun submitCommand(seatId: String, command: RoundTableSeatCommand): Boolean {
        val seat = seat(seatId) ?: return false
        when (command) {
            is RoundTableSeatCommand.Stop -> {
                if (seat.status.isTerminal) return false
                // v239：已有一条待处理指令时不再直接拒绝。
                // 旧行为 return false 会让用户第二次点停止**完全没有任何反应**，
                // 而第一条指令又可能因为执行协程被卡死的连接拖住而永远没人消费。
                // 现在覆盖登记（尝试编号不再重复推高），上层还会走强制通道兜底。
                val alreadyQueued = commands.containsKey(seatId)
                commands[seatId] = command
                if (!alreadyQueued) {
                    updateSeat(seatId) {
                        it.copy(attempt = it.attempt + 1, pendingCommand = true)
                    }
                }
            }

            is RoundTableSeatCommand.Timeout -> {
                if (seat.status.isTerminal) return false
                val alreadyQueued = commands.containsKey(seatId)
                commands[seatId] = command
                if (!alreadyQueued) {
                    updateSeat(seatId) {
                        it.copy(attempt = it.attempt + 1, pendingCommand = true)
                    }
                }
            }

            is RoundTableSeatCommand.Skip -> {
                // 已经有结论的位置直接标成跳过：不排指令，否则没有执行协程会来消费它
                if (seat.status.isTerminal) {
                    markSkipped(seatId)
                    return true
                }
                commands[seatId] = command
                updateSeat(seatId) {
                    it.copy(attempt = it.attempt + 1, pendingCommand = true)
                }
            }

            is RoundTableSeatCommand.Retry -> {
                commands[seatId] = command
                updateSeat(seatId) {
                    it.copy(
                        attempt = it.attempt + 1,
                        reruns = it.reruns + 1,
                        status = RoundTableSeatStatus.PENDING,
                        chars = 0,
                        error = null,
                        suspectReason = null,
                        includeInSummary = true,
                        pendingCommand = false,
                        finishReason = null,
                        truncated = false,
                    )
                }
            }

            is RoundTableSeatCommand.Continue -> {
                // v223：续跑。只对留下过半截内容的终态位置生效；
                // 正常完整写完的位置不允许续跑（再喊继续只是白花钱）。
                if (!seat.canContinue) return false
                commands[seatId] = command
                updateSeat(seatId) {
                    it.copy(
                        attempt = it.attempt + 1,
                        // 续跑不是重跑：reruns 不动，单独计 continuations，界面分开显示
                        continuations = it.continuations + 1,
                        status = RoundTableSeatStatus.PENDING,
                        chars = 0,
                        error = null,
                        suspectReason = null,
                        includeInSummary = true,
                        pendingCommand = false,
                        finishReason = null,
                        truncated = false,
                    )
                }
            }

            is RoundTableSeatCommand.Replace -> {
                commands[seatId] = command
                updateSeat(seatId) {
                    it.copy(
                        modelId = command.modelId,
                        modelName = command.modelName,
                        attempt = it.attempt + 1,
                        reruns = it.reruns + 1,
                        status = RoundTableSeatStatus.PENDING,
                        chars = 0,
                        error = null,
                        suspectReason = null,
                        includeInSummary = true,
                        pendingCommand = false,
                        finishReason = null,
                        truncated = false,
                    )
                }
            }
        }
        // 断开这个位置当前的连接；其它位置不受影响
        attemptJobs.remove(seatId)?.cancel(RoundTableSeatCancellation(seatId))
        return true
    }

    fun consumeCommand(seatId: String): RoundTableSeatCommand? = commands.remove(seatId)

    fun peekCommand(seatId: String): RoundTableSeatCommand? = commands[seatId]

    // ---- 单座位任务登记 ----

    fun registerAttemptJob(seatId: String, job: Job) {
        attemptJobs[seatId] = job
    }

    /**
     * v239：在独立作用域里跑这一次尝试，并登记成这个座位的当前任务。
     *
     * 调度协程只 await 返回的 [Deferred]，不承担它的生命周期 —— 这样「用户点停止」
     * 不再需要等那个可能永远不返回的上游连接（旧死锁的根因，见构造参数说明）。
     */
    fun <T> launchAttempt(seatId: String, block: suspend () -> T): Deferred<T> {
        val deferred = attemptScope.async { block() }
        attemptJobs[seatId] = deferred
        return deferred
    }

    /**
     * v239：立刻断开这个座位当前这次尝试，并把它的结果作废。
     *
     * 与 [submitCommand] 的区别：**不排队、不等任何执行协程**。
     * 专门给「强制停止 / 强制跳过」用 —— 用户已经点过一次、指令却没人接住时，
     * 界面必须马上有反应，不能再指望那条卡死的链路。
     *
     * - attempt +1：即使那个上游稍后才吐出结果，也已经不是当前尝试，会被丢弃；
     * - 清掉挂起指令：避免执行协程醒来后又执行一遍，重复写页面；
     * - pendingCommand 复位：界面不再显示「正在处理你的指令…」。
     */
    fun invalidateAttempt(seatId: String): Boolean {
        seat(seatId) ?: return false
        updateSeat(seatId) { it.copy(attempt = it.attempt + 1, pendingCommand = false) }
        commands.remove(seatId)
        attemptJobs.remove(seatId)?.cancel(RoundTableSeatCancellation(seatId))
        return true
    }

    fun clearAttemptJob(seatId: String, job: Job) {
        attemptJobs.remove(seatId, job)
    }

    /** 调度器抢占某个座位的执行权；返回 false 说明已经有协程在跑它 */
    fun tryClaimSeat(seatId: String): Boolean {
        val claimed = claimedSeats.add(seatId)
        // v229：把「有没有人负责」同步进快照，调度层与界面都要据此判断是否卡在无人调度状态
        if (claimed) updateSeat(seatId) { it.copy(scheduled = true) }
        return claimed
    }

    fun releaseSeat(seatId: String) {
        claimedSeats.remove(seatId)
        updateSeat(seatId) { it.copy(scheduled = false) }
    }

    /**
     * v229：这个座位当前有没有执行协程在负责它。
     *
     * 用于修复「复活后无人调度」的死锁：用户点重试 / 换模型 / 继续输出时，
     * 如果那一组的调度器已经退出，必须由调用方补一个执行协程，否则座位会永久
     * 停在「排队中」，界面上所有救援按钮都会消失。
     */
    fun isSeatClaimed(seatId: String): Boolean = claimedSeats.contains(seatId)

    // ---- 缺席检查点 ----

    fun openGap(prompt: RoundTableGapPrompt): CompletableDeferred<RoundTableGapDecision> {
        val deferred = CompletableDeferred<RoundTableGapDecision>()
        gapDecision.set(deferred)
        _state.update { it.copy(phase = RoundTableRunPhase.PROPOSAL_GAP, gapPrompt = prompt) }
        return deferred
    }

    fun resolveGap(decision: RoundTableGapDecision): Boolean {
        val deferred = gapDecision.getAndSet(null) ?: return false
        _state.update { it.copy(gapPrompt = null) }
        return deferred.complete(decision)
    }

    fun hasOpenGap(): Boolean = gapDecision.get() != null

    fun cleanup() {
        commands.clear()
        // v239：原来只是 clear，卡住的连接会继续在后台跑（还在烧钱、还在往页面写）。
        // 这里显式取消每个座位当前的尝试；但**刻意不关 attemptScope** ——
        // v223 起圆桌结束后仍然要支持对单个座位「继续输出 / 重试 / 换模型」，
        // 作用域一关，那些入口全部变成点了没反应。
        attemptJobs.forEach { (seatId, job) -> job.cancel(RoundTableSeatCancellation(seatId)) }
        attemptJobs.clear()
        claimedSeats.clear()
        gapDecision.getAndSet(null)?.cancel()
    }
}

/**
 * 只取消"某一个座位"时使用的取消原因。
 *
 * 用它可以区分：是用户点了这个位置的停止/更换，还是整场圆桌被取消。
 */
internal class RoundTableSeatCancellation(val seatId: String) :
    java.util.concurrent.CancellationException("round table seat cancelled: $seatId")
