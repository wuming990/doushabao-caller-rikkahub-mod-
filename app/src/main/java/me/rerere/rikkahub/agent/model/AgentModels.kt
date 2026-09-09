package me.rerere.rikkahub.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v218：独立 Codex 风格子代理（Agent Thread）数据模型。
 *
 * 与圆桌（RoundTable*）完全解耦：不使用圆桌座位、阶段、合同或面板；
 * 与主会话（ConversationSession）完全解耦：代理线程独立运行，不依赖父会话存活。
 */

/** 代理线程状态 */
enum class AgentThreadStatus {
    QUEUED,
    RUNNING,

    /**
     * v222 新增：用户已点击停止、取消信号已发出，但收尾状态尚未落库的过渡态。
     *
     * 为什么需要它：v221 点击停止后界面毫无反应（看起来像根本没停）。
     * 真实原因是收尾写入发生在「已被取消的协程」里，写库动作立刻又被取消，
     * STOPPED 永远写不进数据库，界面因此一直停在「正在查证…」。
     * v222 起：点击停止先落一个 STOPPING（界面立刻显示「正在停止…」），
     * 随后收尾写入在 NonCancellable 中把状态改成 STOPPED。
     */
    STOPPING,
    WAITING_APPROVAL,

    /**
     * v236 新增：可恢复错误后的**退避等待**窗口（还没重新发请求）。
     *
     * 为什么必须单独立一个状态：v235 的自动续跑在退避那几秒里线程状态仍是 FAILED，
     * 界面显示「失败」，几秒后又自己跳回「正在查证」。用户看到的是
     * 「我明明停了/它明明失败了，怎么又在跑」，完全无法判断后台还有没有活。
     * 现在退避期间显式落 WAITING_AUTO_RETRY：界面写明「等待自动重试」，
     * 主模型也能从 wait_agents 看到它不是死了而是在等。
     *
     * 仍算活动态（占并发额度），因为它确实会再次发请求。
     */
    WAITING_AUTO_RETRY,
    SUCCEEDED,
    FAILED,
    STOPPED,
    INTERRUPTED,
    CLOSED;

    /** 终态：不会再有运行行为 */
    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == STOPPED || this == INTERRUPTED || this == CLOSED

    /** 活动中（排队 / 运行 / 等待批准 / 停止过渡 / 等待自动重试）：占用并发额度 */
    val isActive: Boolean
        get() = !isTerminal
}

/**
 * 内置代理角色。
 *
 * v218：default / explorer / reviewer（Codex 风格，全部只读）。
 * v236 新增 [PROGRAMMER]：负责真正改文件的「编程位」。
 * 它与其他角色的差别只在提示词；**写权限不由角色决定**，而由
 * [AgentThread.writablePaths] 白名单决定（空 = 只读）。
 * 这样即使模型自称 programmer，没拿到白名单也一个字都改不了。
 */
enum class AgentRole {
    DEFAULT,
    EXPLORER,
    REVIEWER,
    PROGRAMMER,
}

/**
 * v222：结束原因判定。
 *
 * 各服务商对「达到输出上限被截断」的叫法不同：
 * - OpenAI Chat Completions：`length`
 * - OpenAI Response API：`incomplete:max_output_tokens`
 * - Claude：`max_tokens`
 * - Google：`MAX_TOKENS`
 * 统一转小写后按关键字匹配，避免逐家硬编码。
 */
object AgentFinishReason {
    private val TRUNCATION_KEYWORDS = listOf(
        "length",
        "max_token",
        "max_output_token",
        "maxtoken",
        "incomplete",
        "truncat",
    )

    /**
     * v240：这些结束原因表示「模型这一轮是以工具调用收尾的」——
     * 也就是它还想接着干，只是被外面的步数上限切断了。
     *
     * 真机实测（本轮抓到的）：一个只读子代理跑满 20 步上限后停下，
     * finish_reason=tool_calls、报告一个字都没有，却被判成 SUCCEEDED；
     * 因为不属于「截断」，canResume 也是 false —— 用户花了钱，什么都没拿到，
     * 还没有任何入口能把它接回来。这个词表就是为了堵住那个洞。
     */
    private val UNFINISHED_KEYWORDS = listOf(
        "tool_call",
        "tool_use",
        "toolcall",
        "function_call",
    )

    /** 该结束原因是否代表「输出没写完就被强行截断」 */
    fun isTruncated(finishReason: String?): Boolean {
        val normalized = finishReason?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return TRUNCATION_KEYWORDS.any { normalized.contains(it) }
    }

    /** v240：该结束原因是否代表「模型还想继续，只是这一轮被切断了」 */
    fun isUnfinished(finishReason: String?): Boolean {
        val normalized = finishReason?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return UNFINISHED_KEYWORDS.any { normalized.contains(it) }
    }

    /**
     * v269：这些结束原因表示「内容被策略拦下了」——续写只会被再次拦下，
     * 白花一次请求还是拿不到东西，必须原样呈现给用户而不是自动重试。
     *
     * 覆盖 OpenAI 的 content_filter、Claude 的 refusal、
     * Google 的 SAFETY / RECITATION / PROHIBITED_CONTENT / BLOCKLIST。
     */
    private val POLICY_BLOCKED_KEYWORDS = listOf(
        "content_filter",
        "content-filter",
        "contentfilter",
        "safety",
        "recitation",
        "refusal",
        "prohibited",
        "blocklist",
    )

    /** v269：该结束原因是否属于「续写也没用」的策略拦截类原因 */
    fun isBlockedByPolicy(finishReason: String?): Boolean {
        val normalized = finishReason?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return POLICY_BLOCKED_KEYWORDS.any { normalized.contains(it) }
    }
}

/**
 * v235：上游错误的可恢复性判定。
 *
 * ## 为什么需要
 *
 * 子代理常用便宜模型跑，上游服务未必稳：限流、5xx、连接被重置、读超时都会时不时出现。
 * 这类中断和「参数写错了」「API Key 无效」性质完全不同——前者重试一下大概率就过去了，
 * 后者再试一百次也一样失败。此前二者都只落成 `FAILED` + 一段错误文本，
 * 主线无法分辨，只能一律人工介入。
 *
 * 判定顺序刻意是「先 FATAL 再 RECOVERABLE」：FATAL 关键词特异性更强
 * （401 / invalid api key / model not found），而 `timeout` 这类词可能出现在任何消息里。
 * 宁可少自动救一次，也不要在明确无救的错误上白烧配额。
 *
 * 这是启发式匹配，不可能覆盖所有服务商的措辞，判不出来时返回 [UNKNOWN] 并交给主线决定。
 */
enum class AgentErrorKind {
    /** 网络抖动 / 限流 / 服务端 5xx / 读超时：值得自动续跑 */
    RECOVERABLE,

    /** 认证失败 / 模型不存在 / 参数非法 / 余额不足：再试也没用 */
    FATAL,

    /** 判不出来：不自动重试，把决定权交给主模型 */
    UNKNOWN;

    companion object {
        private val FATAL_MARKERS = listOf(
            "unauthorized", "authentication", "invalid api key", "incorrect api key",
            "api key not valid", "permission denied", "forbidden",
            "insufficient_quota", "insufficient balance", "quota exceeded",
            // v284：xAI 免费额度用尽（按模型的 24 小时滚动窗口）。返回的是 429，但 24 小时内
            // 不可能恢复 —— 交给自动续跑只会白烧次数，所以按「再试也没用」处理。
            "free-usage-exhausted", "included free usage",
            // v285：同一件事的另外两种说法，走的是 402/403。真机故障：账号余额用尽后
            // 既不报错也不换号，就是因为判定只认 429 那一种文案。
            "run out of credits", "spending-limit", "personal-team-blocked",
            "invalid_request_error", "invalid request", "unsupported",
            "model not found", "does not exist", "no such model",
            "context length", "maximum context", "context_length_exceeded",
            "模型不可用", "未配置", "已删除",
        )

        private val RECOVERABLE_MARKERS = listOf(
            "too many requests", "rate limit", "ratelimit",
            // v290：Kimi 的按模型限速写法（真机实锤：两份词表都接不住，既不换 key 也不续跑）
            "requests per minute",
            // v291：TPM/天配额/节流的其他常见写法（真机实锤："inference tpm exhausted" 全漏）
            "tpm exhausted", "rpm exhausted", "qpm exhausted",
            "tokens per minute", "requests per day", "tokens per day", "throttled",
            // v292：中转站的 key 状态文案（真机实锤：三道关卡全漏，零输出直接报错）
            "权重不可用",
            "overloaded", "capacity", "server_error", "internal server error",
            "service unavailable", "bad gateway", "gateway timeout",
            "timeout", "timed out", "sockettimeout",
            "connection reset", "connection refused", "connection closed",
            "connection aborted", "broken pipe", "stream was reset",
            "unexpected end of stream", "failed to connect", "software caused connection abort",
            "unknownhost", "no route to host", "network is unreachable",
            "ssl", "handshake",
        )

        /**
         * v284：状态码必须按「独立的数字」匹配，不能用 contains。
         *
         * 旧写法把 `"500"` / `"429"` / `"400"` 这些裸数字直接放进关键词表做 contains，于是
         * xAI 那条报错
         * 「You've used all the included free usage … tokens (actual/limit): 672022/500000」
         * 里的 **500000** 命中了 `"500"`，被当成 HTTP 500 服务器故障 → 判成可恢复 →
         * 自动续跑白烧好几轮（真机实锤，而它 24 小时内根本不会恢复）。
         *
         * 报错里出现含状态码片段的数字（token 数、金额、订单号）是常态，一律要用词边界。
         */
        private val FATAL_CODE_REGEX = Regex("""\b(400|401|402|403|404)\b""")
        private val RECOVERABLE_CODE_REGEX = Regex("""\b(429|500|502|503|504)\b""")

        /** 按错误文本判定可恢复性。空消息一律 [UNKNOWN]（不猜） */
        fun classify(message: String?): AgentErrorKind {
            val normalized = message?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return UNKNOWN
            // 文字标记优先于状态码：xAI 的「免费额度用尽」带的是 429，但它属于「再试也没用」。
            if (FATAL_MARKERS.any { normalized.contains(it) }) return FATAL
            if (FATAL_CODE_REGEX.containsMatchIn(normalized)) return FATAL
            if (RECOVERABLE_MARKERS.any { normalized.contains(it) }) return RECOVERABLE
            if (RECOVERABLE_CODE_REGEX.containsMatchIn(normalized)) return RECOVERABLE
            return UNKNOWN
        }
    }
}

/** 创建代理线程的请求：由主模型通过 spawn_agent 工具或用户手动发起 */
data class AgentSpawnRequest(
    val conversationId: String,
    val parentMessageNodeId: String? = null,
    val task: String,
    val role: AgentRole = AgentRole.DEFAULT,
    val modelId: String? = null,
    /** 只读工作区 ID（String 形式的 Uuid）；null 表示不提供工作区工具 */
    val workspaceId: String? = null,
    /** 主模型显式附带的最小上下文快照；默认不携带完整主对话 */
    val contextSummary: String? = null,
    /**
     * v236：可写文件白名单（工作区相对路径或前缀）。
     *
     * **空列表 = 只读**，与 v235 行为完全一致。只有主模型显式点名哪些文件/目录可改，
     * 子代理才拿到写工具，且只能碰白名单内的路径。
     */
    val writablePaths: List<String> = emptyList(),
)

/** 代理线程（独立于主会话的完整记录） */
data class AgentThread(
    val id: String = Uuid.random().toString(),
    val conversationId: String,
    val parentMessageNodeId: String? = null,
    val task: String,
    val role: AgentRole = AgentRole.DEFAULT,
    val modelId: String? = null,
    val workspaceId: String? = null,
    /** 主代理显式附带的上下文快照（可选，默认不携带主对话） */
    val contextSummary: String? = null,
    val status: AgentThreadStatus = AgentThreadStatus.QUEUED,
    /** 结构化报告 JSON（AgentReport），解析失败时保存原文 */
    val reportJson: String? = null,
    val error: String? = null,
    /**
     * v222 新增：Provider 返回的结束原因（stop / end_turn / length / max_tokens / incomplete:...）。
     *
     * v221 只把该值加到了 ai 模块的 UIMessage，app 侧零消费，
     * 导致「写到一半被截断」的半截结果被当成完整成功，用户前面花的钱白费。
     */
    val finishReason: String? = null,
    /** v222 新增：是否因达到输出上限被截断（可续跑） */
    val truncated: Boolean = false,
    /** v222 新增：已续跑次数（用于界面提示与防止无限续跑） */
    val resumeCount: Int = 0,
    /**
     * v236：可写文件白名单（工作区相对路径或前缀），空 = 只读。
     *
     * 落库是必要的：续跑（resume）时必须原样恢复白名单，否则编程位一被中断就
     * 失去写权限、接着跑等于白跑。落库同时也让「这个线程当时被允许改哪些文件」
     * 可事后追查。
     */
    val writablePaths: List<String> = emptyList(),
    /** v236：本次实际使用的模型（备用模型链切换后会变），仅用于展示与排查 */
    val activeModelId: String? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
) {
    /** v236：是否已被授予写权限（白名单非空） */
    val canWrite: Boolean get() = writablePaths.any { it.isNotBlank() }

    /**
     * 是否允许「继续输出」：
     * - 用户停止 / 意外中断 / 失败：允许接着跑；
     * - 成功但被截断：允许接着跑；
     * - 成功且完整、已关闭、仍在活动中（含等待自动重试）：不允许（避免无意义的重复收费）。
     */
    val canResume: Boolean
        get() = when (status) {
            AgentThreadStatus.STOPPED,
            AgentThreadStatus.INTERRUPTED,
            AgentThreadStatus.FAILED,
                -> true

            AgentThreadStatus.SUCCEEDED -> truncated
            else -> false
        }
}

/**
 * v246：一条线程「现在在干什么」的最小信号。
 *
 * ## 为什么需要
 *
 * 详情页能看事件轨迹，所以能自己推出阶段；但**聊天页那个面板只拿到线程列表**，
 * 不观察每条线程的消息与事件。真机反馈：用户在面板上看到红色的「没动静 7 分 49 秒」，
 * 而它其实正卡在一次工具调用里 —— 面板不说原因，红字看着就像死机。
 *
 * 这里只放三个最小信号（最后一条事件类型、还没返回的工具数、有没有产出过消息），
 * 由 [me.rerere.rikkahub.agent.runtime.AgentThreadManager] 在落库回调里顺手维护，
 * 面板与详情页共用同一套判据（`agentPhaseTextFrom`），不会两处说法不一致。
 */
data class AgentStageSignal(
    val lastEventType: String? = null,
    /** 已发出但还没拿到结果的工具数量（>0 表示正在跑工具，这时候「没动静」是正常的） */
    val pendingTools: Int = 0,
    val hasMessages: Boolean = false,
)

/** 代理线程内的一条消息（可观察输出，只写 AgentDatabase，绝不进入主会话） */
data class AgentMessage(
    val id: String = Uuid.random().toString(),
    val threadId: String,
    val role: String,
    val content: String,
    val createdAt: Instant = Instant.now(),
)

/** 代理线程内的一次事件（工具调用、进度、错误等，只写 AgentDatabase） */
data class AgentEvent(
    val id: String = Uuid.random().toString(),
    val threadId: String,
    val type: String,
    val detail: String,
    val createdAt: Instant = Instant.now(),
)

/**
 * v251：续跑检查点事件的稳定类型（存现有 agent_events 表的 detail，不改数据库结构）。
 *
 * 检查点 = 该线程「已累计的完整原文」（未经过界面裁剪），是续跑的唯一可靠数据源。
 * 约定：
 * - 事件 id 按 threadId 稳定（见 [me.rerere.rikkahub.agent.runtime.resumeCheckpointEventId]）；
 * - eventsFlow 必须排除它（UI 看不到），messagesFlow/messages() 也读不到；
 * - AgentThreadManager 的 onEvent 对检查点不算可见阶段事件，但可以更新 activity。
 */
internal const val AGENT_EVENT_TYPE_RESUME_CHECKPOINT = "RESUME_CHECKPOINT"

/**
 * 子代理结构化报告：结论 + 证据 + 不确定项 + 建议（+ v236 审查结论）。
 */
@Serializable
data class AgentReport(
    val conclusion: String,
    val evidence: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /**
     * v236：审查位的通过与否（"pass" / "fail"，其他值一律按未表态处理）。
     *
     * 流水线用它决定「继续走下一棒」还是「停下交回主模型」。
     * 刻意用字符串而不是布尔：廉价模型经常写成 PASS / 通过 / true，
     * 归一化交给 [AgentVerdict]，解析不出来就当没表态（保守停下，不冒进）。
     */
    val verdict: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(report: AgentReport): String = json.encodeToString(report)

        fun decode(text: String?): AgentReport? =
            text?.let { runCatching { json.decodeFromString<AgentReport>(it) }.getOrNull() }
    }
}

/**
 * v236：审查结论归一化。
 *
 * 只认「明确说通过」和「明确说不通过」，其余（空、含糊、只写了一堆问题）都算 [UNSET]。
 * 流水线遇到 UNSET 与遇到 FAIL 一样停下交给主模型 —— 宁可多问一次主模型，
 * 也不要在「审查位其实没表态」的情况下当成通过继续往下走。
 */
enum class AgentVerdict {
    PASS,
    FAIL,
    UNSET;

    companion object {
        /** 完全等于这些词时直接算通过（提示词要求审查位就写 pass / fail） */
        private val EXACT_PASS = setOf("pass", "passed", "通过", "pass。", "pass.")

        private val PASS_MARKERS = listOf("pass", "passed", "合格", "无问题", "没问题")
        private val FAIL_MARKERS = listOf(
            "fail", "failed", "reject", "not pass", "no-go", "not ok", "nok",
            "不通过", "未通过", "不合格", "有问题", "需修改", "打回",
        )

        fun parse(raw: String?): AgentVerdict {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isEmpty()) return UNSET
            // 1. 先看有没有干干净净地写了 pass —— 这是提示词要求的写法，最可信
            if (text in EXACT_PASS) return PASS
            // 2. 再判 FAIL。必须先于 PASS：「not pass」「未通过」里都含 pass / 通过，
            //    反过来判会把明确的不通过读成通过 —— 那是最危险的方向。
            //    副作用：「没有问题」含「有问题」会被判成 FAIL。这是刻意接受的保守偏差，
            //    宁可多喊主模型来看一眼，也不要把没审明白的东西放过去。
            if (FAIL_MARKERS.any { text.contains(it) }) return FAIL
            if (PASS_MARKERS.any { text.contains(it) }) return PASS
            return UNSET
        }
    }
}

/**
 * v243：从审查位的**结论正文**里兜底识别表态。
 *
 * ## 为什么需要它
 *
 * 真机实测第三次踩到同一件事：审查位把内容审得完全正确（原话「两项核对均满足」），
 * 但没有把 `verdict` 写成报告的字段；v238 加的补问机制问了第二遍，它回
 * 「上次结论表明两项核对均满足，判为通过」—— **还是没写字段**。
 * 于是流水线判「追问后仍未明确表态」，前面几棒白跑，活退回主模型。
 *
 * 这不是模型能力问题（判断是对的），是「机器只认字段」与「模型爱说人话」之间的落差。
 * 所以在补问也失败之后，再从它自己写的那段话里识别一次。
 *
 * ## 为什么和 [AgentVerdict.parse] 分开
 *
 * `parse` 处理的是 verdict **字段**的内容（通常就一个词），可以宽松。
 * 这里处理的是一整段自然语言，误判代价更大，所以：
 *
 * 1. **只在补问之后仍然没有字段时才调用**，不作为常规路径；
 * 2. **先查否定**（「不通过」里含「通过」，反过来判会把明确的不通过读成通过）；
 * 3. 肯定侧只认**明确的表态短语**，不认单独一个「通过」——「通过读取文件」这种
 *    描述性用法会误判；
 * 4. 认不出来就返回 [AgentVerdict.UNSET]，老老实实交回主模型。
 *
 * 抽成顶层函数是为了能直接单测（本项目只有 junit，造不出 AgentThreadManager）。
 */
internal fun inferVerdictFromConclusion(raw: String?): AgentVerdict {
    val text = raw?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return AgentVerdict.UNSET
    val failPhrases = listOf(
        "verdict=fail", "verdict: fail", "verdict\": \"fail",
        "判为不通过", "判定为不通过", "结论：不通过", "结论:不通过",
        "不通过", "未通过", "不满足", "未满足", "不一致", "不合格",
        "有问题", "需修改", "打回", "缺失", "not pass", "no-go",
    )
    if (failPhrases.any { text.contains(it) }) return AgentVerdict.FAIL
    val passPhrases = listOf(
        "verdict=pass", "verdict: pass", "verdict\": \"pass",
        "判为通过", "判定为通过", "结论：通过", "结论:通过",
        "均满足", "全部满足", "都满足", "两项都", "两项均",
        "审查通过", "核对通过", "验证通过", "符合要求", "可以往下走",
        "没有问题", "无问题",
    )
    if (passPhrases.any { text.contains(it) }) return AgentVerdict.PASS
    return AgentVerdict.UNSET
}

/**
 * v236：流水线中的一棒。
 *
 * 主模型一次性把整条流水线交给 [me.rerere.rikkahub.agent.runtime.AgentThreadManager]，
 * 每一棒跑完把报告摘要交给下一棒，不需要主模型在中间反复轮询 —— 这是
 * 「省主模型上下文」的关键：中间过程一个字都不进主对话。
 */
data class AgentPipelineStage(
    val task: String,
    val role: AgentRole = AgentRole.DEFAULT,
    val modelId: String? = null,
    val writablePaths: List<String> = emptyList(),
    /** 该棒是否为审查关卡：verdict 不是 PASS 就停下交回主模型 */
    val gate: Boolean = false,
    /**
     * v239：这一棒额外带的上下文（通常是主对话原文）。
     *
     * 与「上一棒的报告」是两回事：报告由流水线自动往下传，这个字段是主模型
     * 一次性指定、每一棒都能看到的背景资料。两者都有时报告排在后面（更近的事更重要）。
     */
    val contextSummary: String? = null,
)

/** v236：流水线整体结果 */
data class AgentPipelineOutcome(
    val threads: List<AgentThread>,
    /** 停在第几棒（0 起）；全部跑完则等于棒数 */
    val stoppedAtIndex: Int,
    /** 为什么停：COMPLETED / GATE_FAILED / STAGE_FAILED / STOPPED / LIMIT */
    val reason: String,
    val detail: String,
) {
    val completed: Boolean get() = reason == "COMPLETED"
}
