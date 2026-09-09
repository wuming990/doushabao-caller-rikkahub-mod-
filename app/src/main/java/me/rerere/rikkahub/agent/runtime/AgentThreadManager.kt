package me.rerere.rikkahub.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_RESUME_CHECKPOINT
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentErrorKind
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentPipelineOutcome
import me.rerere.rikkahub.agent.model.AgentPipelineStage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentStageSignal
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.model.AgentVerdict
import me.rerere.rikkahub.agent.model.inferVerdictFromConclusion
import me.rerere.rikkahub.agent.repo.AgentThreadRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 并发代理数达到上限时抛出 */
class AgentLimitReachedException(val maxConcurrent: Int) :
    IllegalStateException("并发代理已达上限 $maxConcurrent")

/**
 * v235：上游抖动时的**自动**续跑上限（每线程，进程内计数）。
 *
 * 子代理常用便宜模型跑，上游限流 / 5xx / 连接被重置会时不时发生。
 * 这类错误自动续两次基本能过去；两次还不行说明不是抖动，交给主模型接手。
 */
internal const val AGENT_AUTO_RETRY_MAX = 2

/**
 * v235：单线程续跑总次数上限（含手动点「继续输出」）。
 *
 * 纯粹是防呆：一个彻底坏掉的上游配上自动重试，可能把线程拖进无限循环。
 */
internal const val AGENT_RESUME_TOTAL_MAX = 8

/**
 * v241：中途换模型时，最多等这么久让当前那次生成停下来。
 *
 * 等不到就明确告诉用户「这次没换成」，不假装成功 —— 卡死的上游可能几十秒都醒不过来。
 */
internal const val AGENT_MODEL_SWITCH_WAIT_MILLIS = 30_000L
/** v235：自动续跑退避基数，第 n 次等 n × 该值（限流类错误必须喘口气再试） */
internal const val AGENT_AUTO_RETRY_BACKOFF_MILLIS = 4_000L

/**
 * v236：点了停止之后，最多等这么久让线程自己落终态；超时就兜底强制写 STOPPED。
 *
 * 为什么需要兜底：模型请求可能卡在一次长读上（真机见过单次思考 100 秒以上），
 * 取消信号要等读操作醒过来才生效。用户看到的却是「正在停止…」一直转圈。
 * 现在到点直接落「已停止」，界面立刻干净，后台那次请求即使晚几秒才真正断开，
 * 也不会再往这条线程写任何内容（收尾写入只认终态，见 finalize / markStopping）。
 */
internal const val AGENT_STOP_FINALIZE_TIMEOUT_MILLIS = 6_000L

/** v236：每一棒流水线的等待上限（超过就认定这一棒卡住，交回主模型） */
internal const val AGENT_PIPELINE_STAGE_TIMEOUT_MILLIS = 15 * 60 * 1000L

/**
 * v244：流水线单棒等待的**硬上限**（[pipelineStageWaitMillis] 的天花板）。
 *
 * 每棒的等待时限现在会自动放大到「够那一棒把自愈机制走完」，但不能无限放大：
 * 一棒真的挂死时，整条流水线不该跟着无限期挂着。90 分钟是够宽的余量 ——
 * 单次超时 20 分钟 + 续跑 2 次也只要约 62 分钟。
 */
internal const val AGENT_PIPELINE_STAGE_WAIT_MAX_MILLIS = 90 * 60 * 1000L

/** v236：上一棒报告转交下一棒时的长度上限（防止流水线越走上下文越胖） */
internal const val AGENT_PIPELINE_CARRY_MAX_CHARS = 4 * 1024

/**
 * v218：应用级代理线程管理器。
 *
 * 关键设计（与圆桌/主会话完全解耦）：
 * - 不依赖 ConversationSession：父会话空闲回收、停止回复都不影响代理继续运行；
 * - 每个代理一条独立协程，停止一个不影响其他；
 * - 并发上限由 [maxConcurrent] 控制，超限直接拒绝（不排队）；
 * - 用户停止 → STOPPED；意外取消（应用级 scope 取消等）→ INTERRUPTED，绝不自动重跑；
 * - 所有消息/事件经 repository 落库到独立 AgentDatabase，不进入主会话。
 *
 * v222 三项关键修复：
 * 1. **停止真的会有反馈**：所有收尾状态写入都包在 [NonCancellable] 里。
 *    v221 的收尾写入跑在已被取消的协程中，写库动作立刻又被取消，
 *    STOPPED / INTERRUPTED / FAILED 永远写不进数据库，界面一直显示「正在查证…」。
 * 2. **停止有过渡态**：点击停止立刻落 [AgentThreadStatus.STOPPING]，界面马上显示「正在停止…」。
 * 3. **支持继续输出**：[resume] 让被截断/停止/中断/失败的线程从中断处接着跑，
 *    不再从头重来，也不再白白浪费之前的花费。
 */
class AgentThreadManager(
    private val repository: AgentThreadRepository,
    private val backend: AgentBackend,
    private val scope: CoroutineScope,
    maxConcurrent: Int = 4,
    /**
     * v241：自动续跑次数上限的读取口（0 = 完全不自动续跑）。
     *
     * 用户原话：「直接给我个选项，让我可以自主选择续跑次数。」
     * 做成 lambda 而不是直接依赖 SettingsStore：一是 agent 包不必为了读一个数字
     * 就把设置层拖进来，二是单元测试可以随手注入固定值。
     * 默认值保持 [AGENT_AUTO_RETRY_MAX]，与 v240 及以前完全一致。
     */
    private val autoResumeMax: suspend () -> Int = { AGENT_AUTO_RETRY_MAX },
    /**
     * v242：判断某个模型 id 在当前设置里是否**真的存在**。
     *
     * 真机实测（本轮子代理测试抓到）：给 `resume_agent` 传一个不存在的模型 id，
     * 回执是 `ok:true`、事件流里还写着「已换成 xxx」，但候选链解析时那个 id 找不到
     * 对应模型就被静默跳过，最后仍然用原来的模型跑完 —— 用户以为换了，其实没换。
     *
     * 与 [autoResumeMax] 同样做成 lambda：agent 包不必为了查一个 id 把设置层拖进来，
     * 单元测试也能随手注入。默认恒 true，保持旧行为，不影响既有调用点与测试。
     */
    private val modelExists: suspend (String) -> Boolean = { true },
    /**
     * v244：子代理「多久没有新内容算卡住」的当前阈值（毫秒）。
     *
     * 流水线要用它算出每一棒该等多久：真机上出现过「等待方先到点，被等的一方连自愈
     * 机制都没来得及用」（见 [pipelineStageWaitMillis]）。默认 0，表示按内置默认值估算。
     */
    private val idleTimeoutMillis: suspend () -> Long = { 0L },
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()

    /**
     * v235：自动续跑已用次数（进程内计数，故意不落库）。
     *
     * 不落库的理由：自动重试是针对「刚刚这一次上游抖动」的短时补救。
     * App 重启说明用户已经介入，这时候从零开始计数比沿用旧计数更符合直觉，
     * 而且省掉一次数据库迁移（AgentDatabase 保持 version 2）。
     * 线程成功收尾时清零，让下一段运行重新获得完整的重试预算。
     */
    private val autoRetryCount = ConcurrentHashMap<String, Int>()

    /**
     * v254：「完整替换修复」已用次数（进程内计数，与 [autoRetryCount] 同款，故意不落库）。
     *
     * v251 靠「本次是不是以 REPAIR 模式跑的」来判断「已经修过一次」。v254 把续跑模式
     * 改成由检查点推导之后，修复轮也可能被派生成 SUFFIX，那个推断就不成立了 ——
     * 「最多自动修一次」的保护会被悄悄削弱。改成独立计数，清零点与 autoRetryCount 严格一致
     * （成功收尾 / 无进展收尾 / 修复失败收尾），同样不需要动数据库。
     */
    private val repairAttemptCount = ConcurrentHashMap<String, Int>()

    /**
     * v236：退避中的自动续跑协程句柄。
     *
     * v235 的真实缺陷：退避那几秒里 `jobs` 是空的，用户点停止时
     * `jobs[threadId]?.cancel()` 打在空气上，退避结束后线程照样复活。
     * 用户的原话是「中途我不知道有没有额外继续在跑什么的」—— 就是这个。
     * 现在停止会连这个句柄一起掐掉。
     */
    private val autoRetryJobs = ConcurrentHashMap<String, Job>()

    /**
     * v236：用户「停止」意图的**长效**标记。
     *
     * 和 [stopRequested] 的区别在生命周期：`stopRequested` 会在 runThread 的 finally 里清掉
     * （它只用来区分这一次运行是 STOPPED 还是 INTERRUPTED）。但自动续跑是在运行**结束之后**
     * 才排的程，如果只看 stopRequested，就会出现这个竞态：
     *   线程失败落 FAILED → 用户点停止（此时退避还没登记，掐不到） →
     *   finally 清掉 stopRequested → 退避结束 → 检查 stopRequested 已经空了 → 线程复活。
     * 这个标记只在 [resume]（用户/主模型明确要求继续）时才清除，把上面的缝彻底焊死。
     */
    private val stopLatched = ConcurrentHashMap.newKeySet<String>()

    /**
     * v241：每次「停止」的收尾协程句柄。
     *
     * 为什么必须留着它：收尾协程最后会调 [forceStopIfStillActive]，那个方法只看
     * 「线程现在是不是还活着」，看到活着就强制标 STOPPED。中途换模型的流程是
     * 「停 → 等它落终态 → 换人接着跑」，如果不等收尾协程整个跑完就开始跑，
     * 它醒过来时看到的是刚被换人跑起来的线程，于是一巴掌把它拍回 STOPPED ——
     * 用户看到的就是「换了模型，结果它自己又停了」。
     */
    private val stopFinalizeJobs = ConcurrentHashMap<String, Job>()

    /**
     * v242：每条线程的「运行代次」。
     *
     * ## 修的是什么真机故障
     *
     * 本轮子代理实测：对一条**正在跑**的线程用「换模型接着跑」，新一轮跑完并写下
     * SUCCEEDED（报告完整），几十秒后状态却自己变成了 INTERRUPTED。
     *
     * 根因：被换掉的那一次运行协程收到 cancel 只是「请求」，它可能正卡在一次工具
     * 调用或一次网络读上，要等醒过来才抛 CancellationException。等它醒来时新一轮
     * 早就写完终态了，而它照样会：
     *   1. 走 catch 分支再写一次终态 → 把 SUCCEEDED 覆盖成 INTERRUPTED；
     *   2. 走 finally 把 [jobs] 里**新一轮**的句柄和 [stopRequested] 一起清掉
     *      → 之后用户点停止，`jobs[threadId]?.cancel()` 打在空气上，
     *        界面又回到「正在停止…」下不来。
     *
     * 这两条正是用户长期反馈的「跑完了自己变成中断」「点停止没反应」。
     * v241 的 [stopFinalizeJobs] 只挡住了收尾协程那一路，挡不住旧运行协程自己。
     *
     * ## 解法
     *
     * 每次真正启动运行时把代次 +1，并把当次代次作为参数传进 [runThread]。
     * 写状态、清句柄之前先确认「我还是当前这一代」，过期的那一代一律不许写。
     */
    private val runEpoch = ConcurrentHashMap<String, Long>()

    /**
     * v242：「马上会自己接着跑」的标记。
     *
     * ## 修的是什么真机故障
     *
     * 本轮实测：一条只读子代理被判卡住 → 落 FAILED，主模型的 `wait_agents` 立刻
     * 返回「失败」；可它随后**自动续跑了一次并完整跑完**，报告一点没少。
     *
     * 根因：[awaitTerminal] 只看「是不是终态」，而 FAILED 就是终态。落 FAILED 到
     * 落 WAITING_AUTO_RETRY 之间有一条几十毫秒的缝（中间还夹着一次写库的事件），
     * 500ms 轮询正好可能撞在缝里。于是主模型以为失败，可能白重派一次（重复花钱），
     * 用户也会以为「子代理老是失败」。历史交接文档里记的几次「子代理无产出」，
     * 很可能有一部分就是这个误判。
     *
     * 截断自动续写（[scheduleTruncationResume]）有同样的缝：那边是 SUCCEEDED +
     * truncated，主模型会拿到一份半截报告就走。
     *
     * ## 解法
     *
     * 决定要自己接着跑时**先立旗**，再落终态；[awaitTerminal] 见到旗就继续等。
     * 旗在续跑协程的 finally 里必定清掉，不会挂着不动。
     */
    private val autoResumePlanned = ConcurrentHashMap.newKeySet<String>()

    /**
     * v245：每条线程「最后一次真的有新内容」的时刻（毫秒）。
     *
     * ## 修的是什么真机故障
     *
     * 详情页此前算「多久没动静」用的是数据库里消息/事件的 `createdAt`。但流式输出是
     * **同一条消息记录反复覆盖**（稳定 id + REPLACE，v222 为了不刷屏刻意这么做的），
     * 而 `createdAt` 取的是这条消息**第一次出现**的时间、之后永不更新。
     * 于是只要模型在一条消息里持续写下去（长思考 + 长正文，中间不调工具），
     * 界面上的「没动静」就会一直往上涨 —— 真机已确认：正文在增长，「没动静」也在增长。
     * 用户正是靠这个数字判断要不要干预，它一失真，判断全错。
     *
     * ## 为什么放内存而不落库
     *
     * 落库要给 `agent_messages` 加列 → `AgentDatabase` 版本必须从 3 升到 4，
     * 而数据库版本是明确禁区。而且这份数据只在「线程正在跑」的那段时间有用：
     * App 重启后活动中的线程一律被标成 INTERRUPTED（[markInterruptedOnBoot]），
     * 没有任何需要跨进程恢复的场景。所以内存态足够，且零迁移风险。
     */
    private val lastActivityAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * v245：界面观察用 —— threadId → 最后一次有新内容的时刻（毫秒）。
     *
     * 刻意用 `val` 持有同一个只读视图实例：如果写成 `get() = lastActivityAt.asStateFlow()`，
     * 每次访问都会新建一个包装对象，而 Compose 的 `collectAsStateWithLifecycle` 以流对象
     * 作为 remember 的键 —— 那样每次重组都会重启一次收集。
     */
    val activityFlow: StateFlow<Map<String, Long>> = lastActivityAt.asStateFlow()

    /** v245：有新内容就打一次时间戳（runThread 包在 onMessage/onEvent 外面） */
    private fun touchActivity(threadId: String) {
        lastActivityAt.update { it + (threadId to System.currentTimeMillis()) }
    }

    /** v245：线程不再运行就把记录清掉，避免残留和内存堆积 */
    private fun clearActivity(threadId: String) {
        lastActivityAt.update { if (it.containsKey(threadId)) it - threadId else it }
    }

    /**
     * v245：这条线程「多久没有新内容」（秒）。拿不到（没在跑 / 刚起来）返回 null。
     *
     * 给工具层用：主模型此前只能看到 status=RUNNING，没有任何办法区分
     * 「还在稳定产出」和「已经卡住」，于是要么白等到超时、要么白重派一次。
     */
    fun idleSecondsOrNull(threadId: String): Long? =
        lastActivityAt.value[threadId]?.let {
            ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
        }

    /** v245：给工具层看「它是不是马上会自己接着跑」，避免把一条正要复活的线程报成失败 */
    fun isAutoResumePlanned(threadId: String): Boolean = willAutoResume(threadId)

    /**
     * v246：每条线程「现在在干什么」的最小信号（内存态，同 [lastActivityAt]）。
     *
     * 聊天页那个面板只拿到线程列表，不观察每条线程的事件，所以推不出阶段。
     * 真机反馈：面板上一个红色的「没动静 7 分 49 秒」，其实是正卡在工具调用里 ——
     * 不说原因，红字看着就像死机。这里由落库回调顺手维护，面板与详情页共用同一套判据。
     */
    private val liveStage = MutableStateFlow<Map<String, AgentStageSignal>>(emptyMap())

    /** v246：界面观察用 —— threadId → 当前阶段信号（`val` 持有单一实例，理由同 activityFlow） */
    val stageFlow: StateFlow<Map<String, AgentStageSignal>> = liveStage.asStateFlow()

    /** v246：新一段运行开始，阶段信号归零（上一段的待返回工具数不能带过来） */
    private fun resetStage(threadId: String) {
        liveStage.update { it + (threadId to AgentStageSignal()) }
    }

    private fun clearStage(threadId: String) {
        liveStage.update { if (it.containsKey(threadId)) it - threadId else it }
    }

    private fun noteStageMessage(threadId: String) {
        liveStage.update { map ->
            val cur = map[threadId] ?: AgentStageSignal()
            if (cur.hasMessages) map else map + (threadId to cur.copy(hasMessages = true))
        }
    }

    private fun noteStageEvent(threadId: String, type: String) {
        liveStage.update { map ->
            val cur = map[threadId] ?: AgentStageSignal()
            val pending = when (type) {
                "TOOL_CALL" -> cur.pendingTools + 1
                "TOOL_RESULT" -> (cur.pendingTools - 1).coerceAtLeast(0)
                else -> cur.pendingTools
            }
            map + (threadId to cur.copy(lastEventType = type, pendingTools = pending))
        }
    }

    /** v242：开启新一代运行，返回本次的代次号 */
    private fun nextRunEpoch(threadId: String): Long =
        runEpoch.compute(threadId) { _, old -> (old ?: 0L) + 1L } ?: 1L

    /** v242：这一次运行是否已被更新的一代顶替（顶替了就不许再写状态） */
    private fun isStaleRun(threadId: String, epoch: Long): Boolean {
        val current = runEpoch[threadId] ?: return false
        return current != epoch
    }

    /**
     * v242：这条线程是不是「已经落终态，但马上会自己接着跑」。
     *
     * 用 `isActive` 而不是 `containsKey`：句柄的清理时机不保证，万一留下一个
     * **已经跑完**的句柄，containsKey 仍然为真，会让 [awaitTerminal] 白等到超时。
     */
    private fun willAutoResume(threadId: String): Boolean =
        threadId in autoResumePlanned || autoRetryJobs[threadId]?.isActive == true

    /**
     * v222：状态写入串行锁。
     *
     * 停止过渡态（STOPPING）与收尾终态（STOPPED）可能同时发生，
     * 必须串行化并配合「只在活动态时才写过渡态」的条件判断，
     * 否则 STOPPING 可能覆盖已落库的 STOPPED，线程会永久卡在「正在停止」。
     */
    private val statusMutex = Mutex()

    /** 并发上限（可在设置中调整 1~8，运行时生效） */
    @Volatile
    var maxConcurrent: Int = maxConcurrent

    init {
        // App 重启后，把上次遗留的非终态线程标记为 INTERRUPTED（绝不自动重跑，避免重复收费）
        scope.launch {
            runCatching { repository.markInterruptedOnBoot() }
        }
    }

    /**
     * 创建一个代理线程并立即开始运行。
     * @throws AgentLimitReachedException 超过并发上限时
     */
    suspend fun spawn(request: AgentSpawnRequest): AgentThread {
        mutex.withLock {
            if (repository.activeCount() >= maxConcurrent) {
                throw AgentLimitReachedException(maxConcurrent)
            }
            val thread = AgentThread(
                id = Uuid.random().toString(),
                conversationId = request.conversationId,
                parentMessageNodeId = request.parentMessageNodeId,
                task = request.task,
                role = request.role,
                modelId = request.modelId,
                workspaceId = request.workspaceId,
                contextSummary = request.contextSummary,
                // v236：写白名单必须跟着线程走。漏了这一行，编程位会永远拿不到写工具
                // （白名单为空 → createAgentWritableTools 返回空列表 → 退回纯只读）。
                writablePaths = request.writablePaths,
                status = AgentThreadStatus.QUEUED,
                createdAt = Instant.now(),
            )
            repository.insertThread(thread)
            // v236：先登记 job 再启动（CoroutineStart.LAZY）。
            // v235 是 `scope.launch{...}` 之后才写进 jobs：如果用户正好在这条缝里点停止，
            // `jobs[threadId]` 还是空的，cancel 打空拳，线程会在后台一路跑到底，
            // 而界面已经显示「正在停止…」。这正是「停止太慢、不知道还在跑什么」的一半原因。
            // v242：为这一次运行分配代次，写状态前要凭它确认「我还是当前这一代」
            val epoch = nextRunEpoch(thread.id)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runThread(thread.id, epoch = epoch)
            }
            jobs[thread.id] = job
            job.start()
            return thread
        }
    }

    /**
     * 停止单个代理线程（只影响这一个，其他线程继续）。
     *
     * v222：两步走，先真停再给反馈（取消协程 → 落 STOPPING 过渡态）。
     *
     * v236 三处加固，对应用户实测的「点了停止一直卡在正在停止」：
     * 1. **掐掉退避中的自动续跑**：否则几秒后线程自己复活，停止等于没停；
     * 2. **等收尾 + 兜底落终态**：等不到线程自己写 STOPPED 就强制写，
     *    绝不让 STOPPING 永久留在库里（那会一直占并发额度，还让主模型的
     *    wait_agents 一直等到超时）；
     * 3. 配合 spawn/resume 的 LAZY 启动，cancel 不再可能打空拳。
     */
    fun stop(threadId: String) {
        stopRequested.add(threadId)
        stopLatched.add(threadId)
        // v242：喊停之后就不会再自动接着跑，把「马上会自己接着跑」的旗落下。
        // 必须显式落：退避协程如果还没 start 就被 cancel，它的 finally 不会执行，
        // 旗会永久挂着，让 awaitTerminal（wait_agents）白等到超时。
        autoResumePlanned.remove(threadId)
        autoRetryJobs.remove(threadId)?.cancel()
        // v245：喊停之后这条线程不该再显示「刚刚还在动」，清掉活动记录
        clearActivity(threadId)
        clearStage(threadId)
        val job = jobs[threadId]
        job?.cancel()
        // v241：收尾协程的句柄留下来，「中途换模型」要等它整个跑完才敢续跑
        stopFinalizeJobs[threadId] = scope.launch {
            withContext(NonCancellable) { runCatching { markStopping(threadId) } }
            // 等待本身放在 NonCancellable 之外：等不到也没关系，下一步有兜底。
            runCatching { withTimeoutOrNull(AGENT_STOP_FINALIZE_TIMEOUT_MILLIS) { job?.join() } }
            withContext(NonCancellable) { runCatching { forceStopIfStillActive(threadId) } }
        }
    }

    fun threadsFlow(conversationId: String): Flow<List<AgentThread>> =
        repository.threadsFlow(conversationId)

    suspend fun thread(threadId: String): AgentThread? =
        repository.thread(threadId)

    /** 按会话列出全部线程（含终态） */
    suspend fun list(conversationId: String): List<AgentThread> =
        repository.threadsFlow(conversationId).first()

    /**
     * 给线程补充指令（第一版语义）：落一条 user 消息到 AgentDatabase 作为记录，
     * 若线程已结束则返回错误（不自动重跑，避免意外重复调用）。
     */
    suspend fun sendMessage(threadId: String, content: String): AgentMessage? {
        val thread = repository.thread(threadId) ?: return null
        if (thread.status.isTerminal) return null
        val message = AgentMessage(
            threadId = threadId,
            role = "user",
            content = content,
        )
        repository.insertMessage(message)
        return message
    }

    /**
     * v222：继续输出（续跑）。
     *
     * 适用场景（用户原话：「明明可以直接喊模型继续的」）：
     * - 输出达到模型单次上限被截断（SUCCEEDED + truncated）；
     * - 用户手动停止后想接着跑（STOPPED）；
     * - App 被杀/意外中断（INTERRUPTED）；
     * - 报错失败后重试（FAILED）。
     *
     * 成功且完整的线程不允许续跑，避免毫无意义的重复收费。
     *
     * @param extraInstruction 可选的补充指令，会作为一条 user 消息进入续跑上下文
     * @return 续跑后的线程；不满足续跑条件时返回 null
     * @throws AgentLimitReachedException 并发额度已满时
     */
    suspend fun resume(threadId: String, extraInstruction: String? = null): AgentThread? {
        mutex.withLock {
            val thread = repository.thread(threadId) ?: return null
            if (!thread.canResume) return null
            // v235：防呆上限。坏掉的上游 + 自动重试可能把线程拖进无限循环。
            // v241：用户可以把自动续跑次数调到 10，总上限不能比它还小，
            // 否则设了也白设（会在第 8 次被这里挡下来）。
            val totalMax = maxOf(
                AGENT_RESUME_TOTAL_MAX,
                runCatching { autoResumeMax() }.getOrDefault(0) * 2,
            )
            if (thread.resumeCount >= totalMax) return null
            if (jobs.containsKey(threadId)) return null
            if (repository.activeCount() >= maxConcurrent) {
                throw AgentLimitReachedException(maxConcurrent)
            }
            extraInstruction?.takeIf { it.isNotBlank() }?.let {
                repository.insertMessage(
                    AgentMessage(threadId = threadId, role = "user", content = it)
                )
            }
            val revived = thread.copy(
                status = AgentThreadStatus.QUEUED,
                error = null,
                finishedAt = null,
                finishReason = null,
                truncated = false,
                resumeCount = thread.resumeCount + 1,
            )
            repository.updateThread(revived)
            stopRequested.remove(threadId)
            // v236：明确要求继续，解除长效停止标记
            stopLatched.remove(threadId)
            // v242：已经真的跑起来了，「马上会自己接着跑」的旗可以落下
            autoResumePlanned.remove(threadId)
            // v236：同 spawn，先登记再启动，消除「刚续跑就点停止」的竞态
            // v242：新一代运行 —— 旧那一代醒过来时不许再写状态（详见 runEpoch 注释）
            val epoch = nextRunEpoch(threadId)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runThread(threadId, resume = true, epoch = epoch)
            }
            jobs[threadId] = job
            job.start()
            return revived
        }
    }

    /**
     * v238：换一个模型再续跑。
     *
     * ## 为什么需要
     *
     * 用户真实反馈：「子代理卡半天我很难判断是不是卡住了，我想直接切换另一个子代理续跑」。
     * v237 之前唯一的办法是：点停止 → 去设置里改「子代理模型」→ 回来点「继续输出」。
     * 而且这条路有个死结 —— 如果主模型在 spawn 时点名过模型（`thread.modelId` 非空），
     * 那个模型永远排在候选链第一位，改全局设置也挤不掉它，而全项目没有任何地方
     * 能修改已存在线程的 modelId。这里补上这个能力。
     *
     * ## 语义
     *
     * 把 [modelId] 写进线程（于是它成为候选链的第一棒），然后照常续跑 ——
     * **已产出的内容全部保留**，模型从中断处接着写。备用模型链、每模型重试、
     * 自动续跑三套机制都照常生效，只是起点换了人。
     *
     * 与 [resume] 的唯一差别就是"先把模型钉死再跑"，所有前置校验都复用 [resume]。
     */
    /**
     * v243：给工具层查「这个模型编号在设置里到底存不存在」。
     *
     * 为什么要单独暴露：换模型失败时 [switchModelAndResume] 与 [resumeWithModel] 都只返回
     * null，工具层于是统一回一句「resume did not start (already running, or state changed
     * just now)」。真机实测时这句话把人骗了一下 —— 真实原因是模型不存在，回执却让人以为
     * 「它还在跑」。事件流里写得准，但主模型看不到事件流，只看得到回执。
     */
    suspend fun isModelAvailable(modelId: String): Boolean =
        runCatching { modelExists(modelId) }.getOrDefault(true)

    suspend fun resumeWithModel(
        threadId: String,
        modelId: String,
        extraInstruction: String? = null,
    ): AgentThread? {
        if (modelId.isBlank()) return null
        // v242：先确认这个模型真的存在，再动线程。
        // 旧行为是「照样钉上去」：候选链解析时找不到就静默跳过，回落到原模型，
        // 于是事件流写着「已换成 xxx」、回执还是成功，实际一点没换（真机实测）。
        if (!isModelAvailable(modelId)) {
            emitEvent(
                threadId,
                "MODEL_SWITCH_FAILED",
                "指定的模型不存在（可能已被删除或改过 id），没有换模型；" +
                    "线程保持原样，可以在设置里确认模型后重试",
            )
            return null
        }
        // 注意：mutex 不可重入，这里必须先释放再调 resume，否则死锁。
        val previous = mutex.withLock {
            val thread = repository.thread(threadId) ?: return null
            if (!thread.canResume) return null
            if (thread.modelId != modelId) {
                repository.updateThread(thread.copy(modelId = modelId))
            }
            thread.modelId
        }
        emitEvent(
            threadId,
            "MODEL_SWITCHED",
            "用户手动换模型续跑：${previous ?: "（原为跟随设置）"} → $modelId" +
                "（已产出内容全部保留，从中断处接着写）",
        )
        return resume(threadId, extraInstruction)
    }

    /**
     * v241：**中途**换模型接着跑（不用先等它失败或自己去点停止）。
     *
     * 用户原话：「新增中途切换模型续跑的功能。」
     *
     * [resumeWithModel] 只能对已经停下来的线程用（canResume 要求终态）。真机上更常见的
     * 情形是：它正卡在一个不吐字的模型上，用户想立刻换人，却只能先点停止、等它落终态、
     * 再点换模型 —— 三步操作，中间还要盯着状态变化。这个方法把三步合成一步：
     *
     * 1. 还在跑就先停（已产出内容全部保留，停止本身就会写成半截结果）；
     * 2. 等它真的落终态，最多等 30 秒（停不下来就明确告诉用户，不假装成功）；
     * 3. 清掉「停止闩锁」—— 换模型是用户的明确意图，不能被防止自动复活的那道闸挡住；
     * 4. 把模型钉到线程上，从断点接着写。
     */
    suspend fun switchModelAndResume(
        threadId: String,
        modelId: String,
        extraInstruction: String? = null,
    ): AgentThread? {
        if (modelId.isBlank()) return null
        val current = repository.thread(threadId) ?: return null
        // v242：模型不存在就**什么都不要动**。
        // 这道校验必须在 stop 之前：否则先把正在跑的那次生成停掉，才发现模型是假的，
        // 用户白丢一次生成进度（[resumeWithModel] 里那道校验此时已经太晚了）。
        if (!isModelAvailable(modelId)) {
            emitEvent(
                threadId,
                "MODEL_SWITCH_FAILED",
                "指定的模型不存在（可能已被删除或改过 id），没有换模型，也没有打断正在跑的这一次；" +
                    "可以在设置里确认模型后重试",
            )
            return null
        }
        if (!current.status.isTerminal) {
            emitEvent(
                threadId,
                "MODEL_SWITCH_PENDING",
                "正在停下当前这次生成，准备换模型接着跑（已产出内容全部保留）",
            )
            stop(threadId)
            // v241：必须等「停止收尾协程」整个跑完，而不只是等状态变成终态。
            // 那个协程最后会调 forceStopIfStillActive，它看到活着的线程就强制标 STOPPED；
            // 不等它就续跑的话，刚换人跑起来的线程会被它一巴掌拍回 STOPPED。
            runCatching {
                withTimeoutOrNull(AGENT_MODEL_SWITCH_WAIT_MILLIS) {
                    stopFinalizeJobs[threadId]?.join()
                }
            }
            val settled = withTimeoutOrNull(AGENT_MODEL_SWITCH_WAIT_MILLIS) {
                while (repository.thread(threadId)?.status?.isTerminal != true) {
                    delay(100)
                }
                true
            }
            if (settled != true) {
                emitEvent(
                    threadId,
                    "MODEL_SWITCH_FAILED",
                    "等了 ${AGENT_MODEL_SWITCH_WAIT_MILLIS / 1000} 秒还没停下来，这次没换成；" +
                        "可以再点一次，或者先手动停止再换",
                )
                return null
            }
            // 换模型是用户的明确意图，不能被「防止停止后自己复活」那道闸挡住
            stopLatched.remove(threadId)
            stopRequested.remove(threadId)
        }
        return resumeWithModel(threadId, modelId, extraInstruction)
    }

    /** 关闭已完成线程（仅终态可关闭；CLOSED 只表示关闭，不删除记录） */
    suspend fun close(threadId: String): Boolean {
        val thread = repository.thread(threadId) ?: return false
        if (!thread.status.isTerminal) return false
        // v242：关掉的线程不会再自己跑起来，落旗（否则 wait_agents 会一直等它复活）
        autoResumePlanned.remove(threadId)
        // v245：关闭后不再需要活动记录
        clearActivity(threadId)
        clearStage(threadId)
        repository.updateThread(thread.copy(status = AgentThreadStatus.CLOSED))
        return true
    }

    /** 等待若干线程到达终态；超时返回当前状态快照（不抛异常） */
    suspend fun awaitTerminal(
        threadIds: List<String>,
        timeoutMillis: Long,
    ): List<AgentThread> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val threads = threadIds.mapNotNull { repository.thread(it) }
            // v242：只看「是不是终态」不够 —— 如果它马上会自己接着跑（自动续跑，
            // 或截断后自动接着写），现在返回等于把一条正要复活的线程报成
            // 「最终失败」或「半截报告」。真机实测踩到过：主模型据此以为失败，
            // 而那条线程随后自己跑完并交出了完整报告。
            if (threads.isNotEmpty() &&
                threads.all { it.status.isTerminal && !willAutoResume(it.id) }
            ) {
                return threads
            }
            delay(500)
        }
        return threadIds.mapNotNull { repository.thread(it) }
    }

    /** v222：普通状态写入（串行化） */
    private suspend fun mutate(threadId: String, transform: (AgentThread) -> AgentThread) {
        statusMutex.withLock {
            val current = repository.thread(threadId) ?: return@withLock
            repository.updateThread(transform(current))
        }
    }

    /**
     * v222：收尾状态写入。
     *
     * 必须包在 [NonCancellable] 中 —— 这是 v221「点击停止毫无反馈」的直接原因：
     * catch(CancellationException) 里调用挂起的写库函数时，
     * 因为所在协程已被取消，写库会立刻再抛 CancellationException，状态永远写不进去。
     *
     * v242：**终态一旦落定，不许被另一个终态覆盖。**
     *
     * 本轮真机实测抓到：一条子代理已经写下 SUCCEEDED、报告完整，几十秒后状态却自己
     * 变成了 INTERRUPTED（用户长期反馈的「跑完了自己变成中断」）。能写终态的地方有
     * 好几条路（本次运行收尾、被取消的旧运行醒得晚、停止兜底、退避协程），逐条去堵
     * 既堵不干净也无法证明堵住了。这里改成在**唯一出口**上设一道闸：
     * 已经是终态就不再改成另一个终态，并写一条 `TERMINAL_WRITE_REJECTED` 事件留证，
     * 下次再出现类似现象可以直接在子代理详情页看到「谁想把 X 改成 Y」。
     *
     * CLOSED 例外：它表示「关闭这条记录」，是用户/主模型的显式动作，不是运行结果。
     */
    private suspend fun finalize(threadId: String, transform: (AgentThread) -> AgentThread) {
        withContext(NonCancellable) {
            runCatching {
                var rejected: Pair<AgentThreadStatus, AgentThreadStatus>? = null
                statusMutex.withLock {
                    val current = repository.thread(threadId) ?: return@withLock
                    val next = transform(current)
                    if (shouldRejectTerminalWrite(current.status, next.status)) {
                        rejected = current.status to next.status
                        return@withLock
                    }
                    repository.updateThread(next)
                }
                rejected?.let { (from, to) ->
                    emitEvent(
                        threadId,
                        "TERMINAL_WRITE_REJECTED",
                        "已经是「${from.name}」的线程被要求改成「${to.name}」，已拒绝：" +
                            "终态一旦落定就不再被覆盖（这条记录用于排查「跑完了自己变状态」）",
                    )
                }
            }
        }
    }

    /** v222：写入「正在停止」过渡态（条件写，绝不覆盖终态） */
    private suspend fun markStopping(threadId: String) {
        statusMutex.withLock {
            val current = repository.thread(threadId) ?: return@withLock
            if (!current.status.isActive) return@withLock
            if (current.status == AgentThreadStatus.STOPPING) return@withLock
            repository.updateThread(current.copy(status = AgentThreadStatus.STOPPING))
        }
    }

    /**
     * v236：退避等待态（条件写）。
     *
     * 只在线程确实处于 FAILED 时才写 —— 用户如果在这一瞬间停止/关闭了线程，
     * 不能被这里改回活动态。
     */
    private suspend fun markWaitingAutoRetry(threadId: String) {
        statusMutex.withLock {
            val current = repository.thread(threadId) ?: return@withLock
            if (current.status != AgentThreadStatus.FAILED) return@withLock
            repository.updateThread(current.copy(status = AgentThreadStatus.WAITING_AUTO_RETRY))
        }
    }

    /**
     * v236：把退避态还原成它真实的底层状态 FAILED，然后才允许 [resume]。
     *
     * 为什么不直接让 canResume 认 WAITING_AUTO_RETRY：那会让界面在退避期间
     * 亮出「继续输出」按钮（此时点它只会撞上并发检查），语义也变脏。
     * 先还原再续跑，canResume 的含义保持干净。
     *
     * @return 是否成功还原（false 表示用户已经停止/关闭，不该再续跑）
     */
    private suspend fun restoreFailedFromWaiting(threadId: String): Boolean = statusMutex.withLock {
        val current = repository.thread(threadId) ?: return@withLock false
        when (current.status) {
            AgentThreadStatus.WAITING_AUTO_RETRY -> {
                repository.updateThread(current.copy(status = AgentThreadStatus.FAILED))
                true
            }
            // 已经是 FAILED（没来得及写退避态）也允许续跑
            AgentThreadStatus.FAILED -> true
            else -> false
        }
    }

    /**
     * v236：停止兜底 —— 线程没能自己落终态时强制写 STOPPED。
     *
     * 这是「点了停止一直卡在正在停止」的最后一道防线。除了 v235 那个
     * 「取消发生在 try 之前 → catch/finally 全都不执行」的根因（已在 runThread 修掉），
     * 还有一类无法从代码上根除的情况：模型请求卡在一次长读上，取消信号要等
     * 读操作醒过来。与其让用户一直看着转圈，不如到点落终态。
     *
     * @return 是否真的强制写了（用于决定要不要发事件，避免正常路径下多一条噪音）
     */
    private suspend fun forceStopIfStillActive(threadId: String) {
        val forced = statusMutex.withLock {
            val current = repository.thread(threadId) ?: return@withLock false
            if (current.status.isTerminal) return@withLock false
            repository.updateThread(
                current.copy(status = AgentThreadStatus.STOPPED, finishedAt = Instant.now())
            )
            true
        }
        if (forced) {
            emitEvent(
                threadId,
                "STOPPED",
                "已停止（线程未能在 ${AGENT_STOP_FINALIZE_TIMEOUT_MILLIS / 1000} 秒内自行收尾，" +
                    "已按停止处理；可点「继续输出」接着跑）",
            )
            jobs.remove(threadId)
            stopRequested.remove(threadId)
        }
    }

    /** v222：落一条可观察事件（收尾场景同样需要 NonCancellable） */
    private suspend fun emitEvent(threadId: String, type: String, detail: String) {
        withContext(NonCancellable) {
            runCatching {
                repository.insertEvent(
                    AgentEvent(
                        threadId = threadId,
                        type = type,
                        detail = detail,
                        createdAt = Instant.now(),
                    )
                )
            }
        }
    }

    /**
     * v235：上游抖动后的自动续跑。
     *
     * v292（用户拍板「全拆黑名单」）：不再按错误分类拦截——任何失败（含 FATAL 的
     * API Key 无效 / 模型不存在 / 参数非法）都排自动续跑，烧到用户设置的次数用尽。
     * 内层模型链（GenerationAgentBackend）本来就不看分类：同模型试 N 次后换下一个。
     * 用户主动停止（stopLatched / stopRequested）仍然一律不排、不续。
     *
     * 几个刻意的设计：
     * - **必须异步**：此刻还在 `runThread` 的 catch 里，`finally` 尚未执行，
     *   `jobs` 中仍留着这个 threadId，直接调 [resume] 会被 `jobs.containsKey` 挡掉。
     *   放进 `scope.launch` 并先退避几秒，等 `finally` 清理完再续跑。
     * - **退避递增**：第 n 次等 n × [AGENT_AUTO_RETRY_BACKOFF_MILLIS]，限流类错误需要喘口气。
     * - **安全性由 [resume] 自己兜底**：这几秒里用户若手动停止、关闭或已在重跑，
     *   [resume] 会因 `canResume` / `jobs` / 上限检查而返回 null，不会重复启动。
     */
    private fun scheduleAutoResume(threadId: String, kind: AgentErrorKind) {
        // v239：从「只有 RECOVERABLE 才自动续」放宽成「只要不是确定没救就试一次」。
        // v292（用户拍板「全拆黑名单」）：FATAL 也不再拦截——任何错误都排自动续跑，
        // 烧到用户设置的次数用尽为止。kind 仍传入，用于 AUTO_RETRY 事件里如实标注分类。
        // v236：用户已经喊过停，连排程都不要排（这是「停止后又自己跑起来」的第一道闸）
        if (threadId in stopLatched) return
        // v242：兜底再立一次旗（catch 分支已立过），退出时统一在 finally 里落下
        autoResumePlanned.add(threadId)
        // v236：先登记句柄再启动，这样用户在退避窗口里点停止一定能掐到它
        val job = scope.launch(start = CoroutineStart.LAZY) {
          try {
            // v241：次数上限改由用户在设置里定。0 = 完全不自动续跑。
            val max = runCatching { autoResumeMax() }.getOrDefault(AGENT_AUTO_RETRY_MAX)
            val used = autoRetryCount[threadId] ?: 0
            if (max <= 0) {
                emitEvent(
                    threadId,
                    "AUTO_RETRY_SKIPPED",
                    "自动续跑次数被设成 0，这次不自动接，等你手点「继续输出」",
                )
                return@launch
            }
            if (used >= max) {
                emitEvent(
                    threadId,
                    "AUTO_RETRY_SKIPPED",
                    "自动续跑次数已用完（$used/$max），等你手点「继续输出」或换个模型继续",
                )
                return@launch
            }
            val attempt = used + 1
            autoRetryCount[threadId] = attempt
            val waitMillis = AGENT_AUTO_RETRY_BACKOFF_MILLIS * attempt
            emitEvent(
                threadId,
                "AUTO_RETRY",
                // v292：全拆黑名单后 FATAL 也会走到这，文案如实标注分类（不再写「可恢复」）
                "上游错误（${kind.name}），${waitMillis / 1000} 秒后自动续跑" +
                    "（第 $attempt/$max 次，保留中断前全部内容）",
            )
            // v236：退避期间显式落「等待自动重试」，界面和主模型都看得见它在等而不是死了
            markWaitingAutoRetry(threadId)
            delay(waitMillis)
            // v236：退避这几秒里用户完全可能已经点了停止。v235 会照样复活，
            // 用户看到的就是「明明停了怎么又在跑」。这里必须让路并落终态。
            if (threadId in stopRequested || threadId in stopLatched) {
                emitEvent(threadId, "AUTO_RETRY_SKIPPED", "退避期间已被用户停止，放弃自动续跑")
                forceStopIfStillActive(threadId)
                return@launch
            }
            if (!restoreFailedFromWaiting(threadId)) {
                emitEvent(threadId, "AUTO_RETRY_SKIPPED", "线程状态已改变（被停止/关闭/已在重跑），放弃自动续跑")
                return@launch
            }
            val revived = runCatching { resume(threadId) }
                .onFailure {
                    emitEvent(
                        threadId,
                        "AUTO_RETRY_FAILED",
                        (it.message ?: it.toString()).take(200),
                    )
                }
                .getOrNull()
            if (revived == null) {
                emitEvent(
                    threadId,
                    "AUTO_RETRY_SKIPPED",
                    "自动续跑未启动（可能已被停止/关闭、正在重跑，或已达续跑上限）",
                )
            }
          } finally {
            // v242：不论从哪条路径退出，都要把「马上会自己接着跑」的旗落下，
            // 否则 awaitTerminal 会一直以为它还要复活，白等到超时。
            autoResumePlanned.remove(threadId)
          }
        }
        autoRetryJobs[threadId] = job
        job.start()
    }

    /**
     * v239：输出被截断时，自动从断点接着写。
     *
     * 与 [scheduleAutoResume] 的区别：那条路针对「失败」（线程状态是 FAILED），
     * 这条路针对「成功但话没说完」（状态是 SUCCEEDED + truncated）。
     * 两者共用同一份 [AGENT_AUTO_RETRY_MAX] 预算，所以最多自动接两次就停下等人，
     * 不会无限续写把钱烧完。硬上限还有 [AGENT_RESUME_TOTAL_MAX] 兜着。
     */
    private fun scheduleTruncationResume(threadId: String) {
        // 用户已经喊过停就不要自己又跑起来（和自动重试同一道闸）
        if (threadId in stopLatched || threadId in stopRequested) {
            // v242：不接了就把「马上会自己接着写」的旗落下（runThread 里已经立过）
            autoResumePlanned.remove(threadId)
            return
        }
        // v242：兜底再立一次旗，退出时统一在 finally 里落下
        autoResumePlanned.add(threadId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
          try {
            // v241：与自动重试共用「自动续跑次数」这一个设置项，0 = 完全不自动接
            val max = runCatching { autoResumeMax() }.getOrDefault(AGENT_AUTO_RETRY_MAX)
            val used = autoRetryCount[threadId] ?: 0
            if (max <= 0) {
                emitEvent(
                    threadId,
                    "AUTO_CONTINUE_SKIPPED",
                    "话没说完，但自动续跑次数被设成 0，等你手点「继续输出」",
                )
                return@launch
            }
            if (used >= max) {
                emitEvent(
                    threadId,
                    "AUTO_CONTINUE_SKIPPED",
                    "话没说完，但自动续跑次数已用完（$used/$max），等你手点「继续输出」或换个模型继续",
                )
                return@launch
            }
            val attempt = used + 1
            autoRetryCount[threadId] = attempt
            emitEvent(
                threadId,
                "AUTO_CONTINUE",
                "话没说完（达到模型单次上限或被切断），" +
                    "${AGENT_AUTO_RETRY_BACKOFF_MILLIS / 1000} 秒后自动从断点接着写" +
                    "（第 $attempt/$max 次，已产出内容全部保留）",
            )
            delay(AGENT_AUTO_RETRY_BACKOFF_MILLIS)
            if (threadId in stopRequested || threadId in stopLatched) {
                emitEvent(threadId, "AUTO_CONTINUE_SKIPPED", "等待期间已被用户停止，放弃自动接着写")
                return@launch
            }
            val current = repository.thread(threadId)
            if (current == null ||
                !current.truncated ||
                current.status != AgentThreadStatus.SUCCEEDED
            ) {
                emitEvent(
                    threadId,
                    "AUTO_CONTINUE_SKIPPED",
                    "线程状态已改变（被停止/关闭/已在续跑），放弃自动接着写",
                )
                return@launch
            }
            val revived = runCatching { resume(threadId) }
                .onFailure {
                    emitEvent(
                        threadId,
                        "AUTO_CONTINUE_FAILED",
                        (it.message ?: it.toString()).take(200),
                    )
                }
                .getOrNull()
            if (revived == null) {
                emitEvent(
                    threadId,
                    "AUTO_CONTINUE_SKIPPED",
                    "自动接着写未启动（可能已被停止/关闭，或已达续跑上限）",
                )
            }
          } finally {
            // v242：同自动续跑，退出前必须落旗（否则 awaitTerminal 白等到超时）
            autoResumePlanned.remove(threadId)
          }
        }
        autoRetryJobs[threadId] = job
        job.start()
    }

    /**
     * v236：一次性跑完一整条流水线（主模型只调一次工具，中间过程不进主对话）。
     *
     * 这是用户要的形状：
     * > 「主模型负责发布任务和最后审查，一个或者多个负责探测，一个负责编程，
     * >  一个负责一审和改错，如果不通过，进入主代理接手后续工作。
     * >  省额度的同时还可以让主模型上下文干净。」
     *
     * 设计要点：
     * - **串行**：每一棒的报告摘要作为下一棒的 contextSummary 往下传，
     *   所以下一棒知道上一棒查到了什么，而主模型全程不用参与中转；
     * - **关卡**：标了 gate 的那一棒（通常是审查位）verdict 不是 PASS 就立刻停，
     *   把「停在第几棒 + 为什么」交回主模型，也就是用户说的「不通过进入主代理接手」；
     * - **保守**：审查位没明确表态（UNSET）也算不通过。宁可多问主模型一次，
     *   也不要把「其实没审」当成审过了；
     * - **不给编译权限**：编译要独占 Gradle、单次好几分钟，只能留在主模型手里。
     *   流水线的机械裁判是审查位，编译由主模型在流水线之后自己跑。
     */
    suspend fun runPipeline(
        conversationId: String,
        workspaceId: String?,
        stages: List<AgentPipelineStage>,
        parentMessageNodeId: String? = null,
        stageTimeoutMillis: Long = AGENT_PIPELINE_STAGE_TIMEOUT_MILLIS,
    ): AgentPipelineOutcome {
        val finishedThreads = mutableListOf<AgentThread>()
        var carry: String? = null
        // v244：把每一棒的等待上限放大到「够那一棒把自愈机制走完」。
        //
        // 真机故障：三棒流水线的审查那一棒，跑到调用方给的 600 秒被掐掉 —— 线程 STOPPED、
        // 零产出、resume_count=0，它自己的「同模型再试 → 换备用模型 → 自动续跑」一次都
        // 没来得及用。等待方先到点，被等的一方就永远没有第二次机会。
        val stageWaitMillis = pipelineStageWaitMillis(
            requestedMillis = stageTimeoutMillis,
            idleTimeoutMillis = runCatching { idleTimeoutMillis() }.getOrDefault(0L),
            autoResumeMax = runCatching { autoResumeMax() }.getOrDefault(AGENT_AUTO_RETRY_MAX),
        )

        stages.forEachIndexed { index, stage ->
            val spawned = try {
                spawn(
                    AgentSpawnRequest(
                        conversationId = conversationId,
                        parentMessageNodeId = parentMessageNodeId,
                        task = stage.task,
                        role = stage.role,
                        modelId = stage.modelId,
                        workspaceId = workspaceId,
                        contextSummary = mergeStageContext(stage.contextSummary, carry),
                        writablePaths = stage.writablePaths,
                    )
                )
            } catch (e: AgentLimitReachedException) {
                return AgentPipelineOutcome(
                    threads = finishedThreads,
                    stoppedAtIndex = index,
                    reason = "LIMIT",
                    detail = e.message ?: "并发代理已达上限",
                )
            }

            val settled = awaitTerminal(listOf(spawned.id), stageWaitMillis).firstOrNull()
                ?: return AgentPipelineOutcome(
                    threads = finishedThreads,
                    stoppedAtIndex = index,
                    reason = "STAGE_FAILED",
                    detail = "第 ${index + 1} 棒线程记录丢失",
                )
            finishedThreads += settled

            if (!settled.status.isTerminal) {
                // 等待到点仍没结束：主动停掉，不让它在背后继续烧钱
                stop(settled.id)
                return AgentPipelineOutcome(
                    threads = finishedThreads,
                    stoppedAtIndex = index,
                    reason = "STAGE_FAILED",
                    detail = "第 ${index + 1} 棒等了 ${stageWaitMillis / 1000} 秒还没结束，已停止" +
                        "（已产出的内容都留着，可以用 resume_agent 接着跑；" +
                        "要给它更多时间就调大设置里的「子代理单次超时」）",
                )
            }
            if (settled.status == AgentThreadStatus.STOPPED) {
                return AgentPipelineOutcome(
                    threads = finishedThreads,
                    stoppedAtIndex = index,
                    reason = "STOPPED",
                    detail = "第 ${index + 1} 棒被停止，流水线中止",
                )
            }
            if (settled.status != AgentThreadStatus.SUCCEEDED) {
                return AgentPipelineOutcome(
                    threads = finishedThreads,
                    stoppedAtIndex = index,
                    reason = "STAGE_FAILED",
                    detail = "第 ${index + 1} 棒 ${settled.status.name}：${settled.error ?: "无错误信息"}",
                )
            }

            val report = AgentReport.decode(settled.reportJson)
            if (stage.gate) {
                var verdict = AgentVerdict.parse(report?.verdict)
                // v238：只有「没表态」才追问一次。
                //
                // 为什么必须补这一手（本轮真机实测抓到的）：审查位在 conclusion 里
                // 写得明明白白「四条判定规则全部满足……可以往下走」，却漏了 verdict 字段，
                // 于是 parse 得到 UNSET、按不通过处理，流水线中断并把活退回主模型。
                // 方向是对的（宁可多问一眼），但**每次都误报的话「省额度」就落空了** ——
                // 最后还是主模型接手，中间几棒白跑。
                //
                // 追问只花一次廉价调用，比把整件事退回主模型便宜得多。
                // 刻意不追问 FAIL：明确写了 fail 就是审查结论，不许追问翻案。
                if (verdict == AgentVerdict.UNSET) {
                    emitEvent(
                        settled.id,
                        "VERDICT_MISSING",
                        "审查位没有输出 verdict 字段，补问一次「pass 还是 fail」再决定是否继续",
                    )
                    val followUp = runCatching {
                        spawn(
                            AgentSpawnRequest(
                                conversationId = conversationId,
                                parentMessageNodeId = parentMessageNodeId,
                                task = buildVerdictFollowUpTask(index, report, settled),
                                role = AgentRole.REVIEWER,
                                modelId = stage.modelId,
                                workspaceId = workspaceId,
                                contextSummary = mergeStageContext(stage.contextSummary, carry),
                                writablePaths = emptyList(),
                            )
                        )
                    }.getOrNull()
                    if (followUp != null) {
                        val reAsked =
                            awaitTerminal(listOf(followUp.id), stageWaitMillis).firstOrNull()
                        if (reAsked != null) {
                            finishedThreads += reAsked
                            if (!reAsked.status.isTerminal) stop(reAsked.id)
                            verdict = AgentVerdict.parse(
                                AgentReport.decode(reAsked.reportJson)?.verdict
                            )
                            // v243：补问也没写字段，就从它自己说的那段话里兜底识别一次。
                            //
                            // 真机第三次踩到：审查位内容判断完全正确（「两项核对均满足」），
                            // 补问后回「判为通过」，两次都没写 verdict 字段，于是整条流水线
                            // 判「未明确表态」、前面几棒白跑。识别不出来才交回主模型。
                            if (verdict == AgentVerdict.UNSET) {
                                val spoken = listOfNotNull(
                                    AgentReport.decode(reAsked.reportJson)?.conclusion,
                                    report?.conclusion,
                                ).firstOrNull { it.isNotBlank() }
                                val inferred = inferVerdictFromConclusion(spoken)
                                if (inferred != AgentVerdict.UNSET) {
                                    emitEvent(
                                        settled.id,
                                        "VERDICT_INFERRED",
                                        "审查位两次都没写 verdict 字段，从它的结论原文里识别成" +
                                            "「${inferred.name}」并采纳；原文：" +
                                            spoken.orEmpty().take(120),
                                    )
                                    verdict = inferred
                                }
                            }
                        }
                    }
                }
                if (verdict != AgentVerdict.PASS) {
                    return AgentPipelineOutcome(
                        threads = finishedThreads,
                        stoppedAtIndex = index,
                        reason = "GATE_FAILED",
                        detail = buildString {
                            append("第 ${index + 1} 棒是审查关卡，结论=")
                            append(if (verdict == AgentVerdict.UNSET) "追问后仍未明确表态" else "不通过")
                            append("，交回主模型接手")
                        },
                    )
                }
            }
            carry = buildStageCarry(index, report, settled)
        }

        return AgentPipelineOutcome(
            threads = finishedThreads,
            stoppedAtIndex = stages.size,
            reason = "COMPLETED",
            detail = "全部 ${stages.size} 棒跑完，审查关卡均通过",
        )
    }

    /**
     * v236：把上一棒的成果压成下一棒能吃的摘要。
     *
     * 只传结论与证据 —— 建议和不确定项是给主模型看的，塞给下一棒容易让它跑偏。
     * 总长受 [AGENT_PIPELINE_CARRY_MAX_CHARS] 限制，避免流水线越走上下文越胖。
     */
    /**
     * v238：补问审查结论的任务文本。
     *
     * 刻意只让它回答一个词，不让它重新审一遍 —— 重审等于再烧一遍配额，
     * 而且很可能得到和上一次不一致的结论。这里要的只是把上一次的结论归一化成 pass/fail。
     */
    private fun buildVerdictFollowUpTask(
        index: Int,
        report: AgentReport?,
        thread: AgentThread,
    ): String = buildString {
        appendLine("你刚刚审查完第 ${index + 1} 棒，但漏了 verdict 字段，所以现在必须补一个明确结论。")
        appendLine()
        appendLine("**不要重新审查，不要读任何文件，不要调用任何工具。**")
        appendLine("只根据下面你自己写过的结论，判断它到底是「通过」还是「不通过」。")
        appendLine()
        appendLine("你上一次写的结论原文：")
        appendLine("---")
        appendLine(
            (report?.conclusion?.ifBlank { null } ?: thread.reportJson.orEmpty())
                .take(AGENT_PIPELINE_CARRY_MAX_CHARS)
        )
        appendLine("---")
        appendLine()
        appendLine("输出要求：报告里只需要 verdict 与一句话 conclusion，例如")
        appendLine("{\"verdict\": \"pass\", \"conclusion\": \"上次结论说全部满足，判为通过\"}")
        appendLine("verdict 只能是 pass 或 fail。看不出来就写 fail。")
        appendLine("注意：verdict 必须是报告里的**字段**，而且放在第一个。")
        appendLine("只在正文里用自然语言说「通过」「均满足」这类话不算表态 —— 机器只认字段。")
    }

    /**
     * v239：把「主模型给整条流水线的背景资料」与「上一棒的报告」拼起来。
     *
     * 顺序刻意是背景在前、上一棒报告在后 —— 越近的事越重要，放在末尾更容易被读到。
     * 两个都空则返回 null，与 v238 行为一致（不制造空段落）。
     */
    private fun mergeStageContext(stageContext: String?, carry: String?): String? {
        val head = stageContext?.takeIf { it.isNotBlank() }
        val tail = carry?.takeIf { it.isNotBlank() }
        return when {
            head == null -> tail
            tail == null -> head
            else -> head + "\n\n" + tail
        }
    }

    private fun buildStageCarry(
        index: Int,
        report: AgentReport?,
        thread: AgentThread,
    ): String = buildString {
        appendLine("上一棒（第 ${index + 1} 棒 · ${thread.role.name.lowercase()}）的结果：")
        appendLine(report?.conclusion?.ifBlank { null } ?: thread.reportJson.orEmpty())
        val evidence = report?.evidence.orEmpty()
        if (evidence.isNotEmpty()) {
            appendLine()
            appendLine("它给出的可核对证据：")
            evidence.take(20).forEach { appendLine("- $it") }
        }
        if (thread.truncated) {
            appendLine()
            appendLine("注意：上一棒的输出被截断过，可能不完整。")
        }
    }.let { if (it.length > AGENT_PIPELINE_CARRY_MAX_CHARS) it.take(AGENT_PIPELINE_CARRY_MAX_CHARS) else it }

    private suspend fun runThread(threadId: String, resume: Boolean = false, epoch: Long) {
        try {
            // v236：整个函数体都包进 try。
            //
            // v235 把下面这几行放在 try **之外**，这是「点了停止一直显示正在停止」的根因：
            // 用户在「刚派发、还没进入生成」的窗口点停止时，取消异常从这几行直接抛出，
            // 既不进 catch（STOPPED / INTERRUPTED 永远写不进库，界面永久停在「正在停止…」），
            // 也不进 finally（jobs 与 stopRequested 永不清理，这条死线程还一直占着并发额度，
            // 且 activeCount 把 STOPPING 算作活动态，攒够几条之后新的子代理直接派不出去）。
            // 主模型那边则表现为 wait_agents 一直等到超时才返回 —— 就是用户说的「太慢了」。
            val initial = repository.thread(threadId) ?: return

            // v236：开跑之前先看一眼有没有人已经喊停。有的话连第一次请求都不发，省钱。
            if (threadId in stopRequested) {
                finalize(threadId) { current ->
                    current.copy(status = AgentThreadStatus.STOPPED, finishedAt = Instant.now())
                }
                emitEvent(threadId, "STOPPED", "在开始生成前已被停止，未产生任何模型请求")
                return
            }

            mutate(threadId) {
                it.copy(
                    status = AgentThreadStatus.RUNNING,
                    // 续跑保留首次开始时间，便于界面显示总耗时
                    startedAt = it.startedAt ?: Instant.now(),
                    finishedAt = null,
                    error = null,
                )
            }
            if (resume) {
                emitEvent(threadId, "RESUMED", "继续输出（第 ${initial.resumeCount} 次续跑）")
            }
            val running = repository.thread(threadId) ?: initial
            // v245：开跑就先打一个活动时间戳，否则界面在「已连上、还没吐第一个字」
            // 这段时间里没有基准，只能退回用消息的 createdAt（那正是失真的来源）。
            touchActivity(threadId)
            // v246：新一段运行，阶段信号归零
            resetStage(threadId)
            val outcome = backend.run(
                thread = running,
                resume = resume,
                onMessage = {
                    repository.insertMessage(it)
                    // v245：真实活动时间只认「这一刻又来了新内容」，与消息 createdAt 无关
                    touchActivity(threadId)
                    noteStageMessage(threadId)
                },
                onEvent = {
                    repository.insertEvent(it)
                    touchActivity(threadId)
                    // v251：续跑检查点不是可见的阶段事件（UI 看不到），但说明后端在持续产出，
                    // 仍算「有动静」；只有普通事件才更新阶段信号。
                    if (it.type != AGENT_EVENT_TYPE_RESUME_CHECKPOINT) {
                        noteStageEvent(threadId, it.type)
                    }
                },
            )
            // v242：跑到这里可能已经过了好几分钟，中途可能有人「换模型接着跑」，
            // 那时已经开了新一代运行。旧那一代绝不能再写状态，否则会把新一代
            // 刚写好的结果覆盖掉（真机实测：SUCCEEDED 被覆盖成 INTERRUPTED）。
            if (isStaleRun(threadId, epoch)) return
            // v251：报告评估驱动收尾。旧的假后端不设 assessment，按 truncated 兼容映射
            // （truncated=true → 当作「接尾续写」，否则当作「完整」）。
            val assessment = outcome.assessment
                ?: if (outcome.truncated) AgentReportAssessment.CONTINUE_SUFFIX
                else AgentReportAssessment.COMPLETE
            when (assessment) {
                // v251：无进展（续跑只回了「无需重复」之类）→ 保留旧报告，落 FAILED，
                // 不再自动续跑。绝不能标 SUCCEEDED，也绝不能继续无限自动续跑。
                AgentReportAssessment.NO_PROGRESS -> {
                    finalize(threadId) { current ->
                        current.copy(
                            status = AgentThreadStatus.FAILED,
                            error = "续跑没有产生有效新内容（模型只回了「无需重复」之类的元话语），" +
                                "已保留上一次的报告；可点「继续输出」或换模型继续",
                            finishReason = outcome.finishReason,
                            truncated = false,
                            activeModelId = outcome.modelId ?: current.activeModelId,
                            finishedAt = Instant.now(),
                        )
                    }
                    emitEvent(
                        threadId,
                        "REPORT_NO_PROGRESS",
                        "续跑没有产生有效新内容（模型只回了「无需重复」之类的元话语），" +
                            "已保留上一次的报告，不再自动续跑",
                    )
                    // v235：收尾就把自动重试预算清零，避免残留额度影响下一段
                    autoRetryCount.remove(threadId)
                    // v254：修复机会计数同步清零（清零点与 autoRetryCount 严格一致）
                    repairAttemptCount.remove(threadId)
                }

                // v251：格式/占位/本地裁剪 → 第一次给一次「完整替换修复」机会；
                // 修复轮（本次就是以 REPAIR 模式跑的）仍然失败 → 落 FAILED 停止自动循环。
                AgentReportAssessment.REPAIR_REPLACE -> {
                    // v254：不能只看「本次是不是 REPAIR 模式」—— 续跑模式改由检查点推导后，
                    // 修复轮也可能派生成 SUFFIX。补一个进程内独立计数，兜住「最多自动修一次」。
                    val repairUsed = repairAttemptCount[threadId] ?: 0
                    val alreadyRepairing =
                        outcome.resumeMode == AgentResumeMode.REPAIR || repairUsed >= 1
                    if (alreadyRepairing) {
                        // v242：不会再自己接着跑，把「马上会自己接着跑」的旗落下
                        autoResumePlanned.remove(threadId)
                        finalize(threadId) { current ->
                            current.copy(
                                status = AgentThreadStatus.FAILED,
                                error = "报告完整替换修复仍未能产出有效报告（占位符/格式无效/历史不完整），" +
                                    "已保留上一次的报告；可点「继续输出」或换模型继续",
                                finishReason = outcome.finishReason,
                                truncated = false,
                                activeModelId = outcome.modelId ?: current.activeModelId,
                                finishedAt = Instant.now(),
                            )
                        }
                        emitEvent(
                            threadId,
                            "REPORT_REPAIR_FAILED",
                            "完整替换修复仍未能产出有效报告，已停止自动循环，保留上一次的报告",
                        )
                        autoRetryCount.remove(threadId)
                        repairAttemptCount.remove(threadId)
                    } else {
                        // v254：记账「这条线程已经用掉那唯一一次自动修复机会」
                        repairAttemptCount[threadId] = repairUsed + 1
                        // v242：要自己接着跑就先立旗、再落状态（见 autoResumePlanned 注释）
                        if (threadId !in stopLatched && threadId !in stopRequested) {
                            autoResumePlanned.add(threadId)
                        }
                        finalize(threadId) { current ->
                            current.copy(
                                status = AgentThreadStatus.SUCCEEDED,
                                reportJson = AgentReport.encode(outcome.report),
                                finishReason = outcome.finishReason,
                                truncated = true,
                                activeModelId = outcome.modelId ?: current.activeModelId,
                                finishedAt = Instant.now(),
                            )
                        }
                        emitEvent(
                            threadId,
                            "REPORT_REPAIR",
                            "报告未通过校验（占位符/格式无效/被本地裁剪），将自动尝试一次完整替换修复",
                        )
                        // 与截断续写共用同一份 AGENT_AUTO_RETRY_MAX 预算，最多自动修一次
                        scheduleTruncationResume(threadId)
                    }
                }

                // v239：真正被截断（Provider length/max_tokens、tool_calls、未闭合）→ 接尾续写
                AgentReportAssessment.CONTINUE_SUFFIX -> {
                    // v242：先立旗再落状态（见 autoResumePlanned 注释）
                    if (threadId !in stopLatched && threadId !in stopRequested) {
                        autoResumePlanned.add(threadId)
                    }
                    finalize(threadId) { current ->
                        current.copy(
                            status = AgentThreadStatus.SUCCEEDED,
                            reportJson = AgentReport.encode(outcome.report),
                            finishReason = outcome.finishReason,
                            truncated = true,
                            activeModelId = outcome.modelId ?: current.activeModelId,
                            finishedAt = Instant.now(),
                        )
                    }
                    emitEvent(
                        threadId,
                        "TRUNCATED",
                        "输出达到模型单次上限被截断（finish_reason=${outcome.finishReason}）",
                    )
                    // 预算刻意**不清零** —— 用同一份 AGENT_AUTO_RETRY_MAX 额度，
                    // 自动接两次还写不完就停下等人，不会无限续下去烧钱。
                    scheduleTruncationResume(threadId)
                }

                // v251：完整（COMPLETE / REPLACE_COMPLETE）→ 成功收尾
                else -> {
                    finalize(threadId) { current ->
                        current.copy(
                            status = AgentThreadStatus.SUCCEEDED,
                            reportJson = AgentReport.encode(outcome.report),
                            finishReason = outcome.finishReason,
                            truncated = false,
                            activeModelId = outcome.modelId ?: current.activeModelId,
                            finishedAt = Instant.now(),
                        )
                    }
                    // v235：成功收尾就把自动重试预算清零，下一段运行重新获得完整额度
                    autoRetryCount.remove(threadId)
                    // v254：修复机会计数同步清零（清零点与 autoRetryCount 严格一致）
                    repairAttemptCount.remove(threadId)
                }
            }
        } catch (e: CancellationException) {
            // v242：这是「被换模型/被顶替的那一代」最容易造成事故的地方。
            //
            // 旧那一代收到 cancel 后可能还卡在一次长读上，等它醒来抛出取消异常时，
            // 新一代早就跑完并写好 SUCCEEDED 了。此时若照旧写终态，用户看到的就是
            // 「明明成功了，过一会儿自己变成中断」。所以过期的一代只传播取消，
            // 不写状态、不发事件。
            if (isStaleRun(threadId, epoch)) throw e
            // 用户停止 → STOPPED；其他原因取消 → INTERRUPTED（不自动重跑）
            val stoppedByUser = threadId in stopRequested
            val target =
                if (stoppedByUser) AgentThreadStatus.STOPPED else AgentThreadStatus.INTERRUPTED
            finalize(threadId) { current ->
                current.copy(status = target, finishedAt = Instant.now())
            }
            emitEvent(
                threadId,
                target.name,
                if (stoppedByUser) "已按用户要求停止，可点「继续输出」接着跑"
                else "运行被意外中断，可点「继续输出」接着跑",
            )
            // 协程取消语义必须向上传播，否则父 Job 无法正确进入 cancelled 状态
            throw e
        } catch (e: Exception) {
            // v242：过期的一代不许写状态（理由同上面的取消分支）
            if (isStaleRun(threadId, epoch)) return
            val message = (e.message ?: e.toString()).take(500)
            // v235：区分「上游抖动，值得再试」和「配置写错了，再试也没用」。
            val kind = AgentErrorKind.classify(message)
            // v242：先立「马上会自己接着跑」的旗，再落 FAILED。
            // 顺序反了的话，主模型的 wait 会在「已落 FAILED、还没排上自动续跑」
            // 的缝里返回，把一条随后自己跑完的线程报成最终失败（真机实测踩到）。
            // v292（用户拍板「全拆黑名单」）：任何错误分类都立旗——失败即排自动续跑。
            if (threadId !in stopLatched) {
                autoResumePlanned.add(threadId)
            }
            finalize(threadId) { current ->
                current.copy(
                    status = AgentThreadStatus.FAILED,
                    error = message,
                    finishedAt = Instant.now(),
                )
            }
            emitEvent(threadId, "FAILED", "[${kind.name}] $message")
            scheduleAutoResume(threadId, kind)
        } finally {
            // v242：过期的一代绝不能清「当前一代」的句柄。
            //
            // 旧那一代醒得晚，它的 finally 会把 jobs 里**新一代**的句柄连同
            // stopRequested 一起抹掉。之后用户点停止时 `jobs[threadId]?.cancel()`
            // 打在空气上，界面就永远停在「正在停止…」下不来 —— 这是历史上反复
            // 出现的「点停止没反应」的一个真实来源。
            if (!isStaleRun(threadId, epoch)) {
                jobs.remove(threadId)
                stopRequested.remove(threadId)
                // v236：本次运行的退避句柄不再有效（新的失败会重新登记一个）
                autoRetryJobs.remove(threadId)
                // v245：这一段运行已经结束，清掉活动记录（自动续跑会重新打点）
                clearActivity(threadId)
                clearStage(threadId)
            }
        }
    }
}

/**
 * v242：这一次「收尾状态写入」该不该被拒绝。
 *
 * 规则只有一条：**终态一旦落定，就不许被另一个终态覆盖**。
 *
 * 为什么需要它：能写终态的路有好几条（本次运行正常收尾、被取消的旧运行醒得晚、
 * 停止兜底、退避协程），真机上出现过「SUCCEEDED 的线程几十秒后自己变成 INTERRUPTED」，
 * 报告明明是完整的。逐条去堵既堵不干净、也无法证明堵住了，所以改成在唯一出口设闸。
 *
 * CLOSED 例外：它表示「关闭这条记录」，是用户或主模型的显式动作，不是运行结果。
 *
 * 抽成顶层函数是为了能真单测（本项目 testImplementation 只有 junit，造不出
 * AgentThreadManager 需要的那一套依赖；放在类里就只能退化成 grep 源码断言）。
 */
internal fun shouldRejectTerminalWrite(
    current: AgentThreadStatus,
    next: AgentThreadStatus,
): Boolean =
    current.isTerminal && next != current && next != AgentThreadStatus.CLOSED

/**
 * v244：流水线里**每一棒**实际该等多久。
 *
 * ## 修的是什么真机故障
 *
 * 真机实测：一条三棒流水线，前两棒正常，第三棒（审查位）跑到我给的 600 秒时限被掐掉，
 * 线程 STOPPED、零产出、`resume_count = 0` —— 它自己的「同模型再试 → 换备用模型 →
 * 自动续跑」三套自愈机制**一次都没来得及用**。
 *
 * 根因是两个时限没有对齐：一棒真正可能需要多久，取决于「多久没动静算卡住」这个阈值
 * （v244 起那个设置项就是它）加上允许续跑几次；而流水线每棒的等待上限默认只有 15 分钟，
 * 调用方传参更可能只给几百秒。等待方先到点，被等的一方就永远没有第二次机会。
 *
 * ## 规则
 *
 * 下限 = 静默阈值 × 3 ×（续跑次数 + 1）+ 递增退避总和 + 1 分钟余量，与调用方传入的值取
 * 大者，最后夹在 [AGENT_PIPELINE_STAGE_WAIT_MAX_MILLIS] 以内（免得一棒挂死拖住整条
 * 流水线）。×3 是因为一趟正常任务的实际时长通常是静默阈值的若干倍 —— 它每产出一次
 * 就重新计时。
 *
 * 抽成顶层函数是为了能直接单测：这类「两个超时没对齐」的缺陷靠 grep 源码是抓不到的。
 */
internal fun pipelineStageWaitMillis(
    requestedMillis: Long,
    idleTimeoutMillis: Long,
    autoResumeMax: Int,
    backoffMillis: Long = AGENT_AUTO_RETRY_BACKOFF_MILLIS,
): Long {
    val resumes = autoResumeMax.coerceAtLeast(0)
    val attempts = resumes + 1
    // v244：一趟的时长上限已经不是「总超时」了（那条闸只剩 2 小时的防死循环兜底）。
    // 真正决定一趟什么时候被打断的是静默阈值，而一趟正常任务的实际时长通常是它的若干倍
    // —— 每产出一次就重新计时。这里按 3 倍估：既不至于把还在干活的一棒掐掉，
    // 也不会在一棒真挂死时等到天荒地老（上面还有硬上限兜着）。
    val perAttempt =
        (if (idleTimeoutMillis > 0L) idleTimeoutMillis else AGENT_IDLE_TIMEOUT_DEFAULT_MILLIS) * 3
    // 退避是递增的（第 n 次等 backoff×n），总和 = backoff × n(n+1)/2
    val backoffTotal = backoffMillis * resumes * (resumes + 1) / 2
    val floor = perAttempt * attempts + backoffTotal + 60_000L
    // v244 自查补强：上限也要保证「至少够跑完一趟」。
    // 静默阈值最大可设到 120 分钟，那时一趟的预算就超过 90 分钟的硬上限 ——
    // 若死夹在 90 分钟，又会退回「等待方先到点、被等的一方连一趟都跑不完」。
    val cap = maxOf(AGENT_PIPELINE_STAGE_WAIT_MAX_MILLIS, perAttempt + backoffTotal + 60_000L)
    return maxOf(requestedMillis, floor).coerceAtMost(cap)
}
