package me.rerere.rikkahub.agent.runtime

import kotlinx.coroutines.delay
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentFinishReason
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import java.time.Instant

/**
 * v251：续跑检查点事件的稳定类型与稳定 id（按 threadId）。
 *
 * 检查点存现有 agent_events 表的 detail 字段，不新增表/列、不升级 AgentDatabase（version 3 不动）。
 * 事件流（eventsFlow）必须排除它，界面看不到；普通 messagesFlow/messages() 也读不到它。
 * 事件类型常量定义在 model 包（[me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_RESUME_CHECKPOINT]），
 * 避免 repo ↔ runtime 的包级循环依赖。
 */
internal fun resumeCheckpointEventId(threadId: String): String = "$threadId:resume_checkpoint"

/**
 * v251：续跑模式。
 *
 * - [SUFFIX]：只补缺失的后半段（真正被截断时用），禁止重抄、禁止只回「无需重复」；
 * - [REPAIR]：交一份完整、紧凑、可解析的替换报告（占位符 / 格式无效 / 本地裁剪 / 旧线程无检查点时用），
 *   不能只解释「上一轮已完成」。
 *
 * 模式由线程现有 reportJson 的结构 / 省略标记推导，不升级数据库。
 *
 * 可见性说明：它出现在公开数据类 [AgentRunOutcome] 的属性上，所以必须与
 * `AgentThreadStatus` / `AgentRole` / `AgentFinishReason` 这些同类枚举一样是公开的。
 * 标成模块内可见会直接编译失败（公开成员不许暴露模块内类型）——v251 真踩过一次。
 */
enum class AgentResumeMode {
    SUFFIX,
    REPAIR,
}

/**
 * v251：报告评估状态（纯逻辑、可测试、保守）。
 *
 * - [COMPLETE]：合并后是完整、可解析、非占位、未裁剪的结构化报告 → 成功收尾；
 * - [REPLACE_COMPLETE]：本轮新输出单独就是完整结构化报告 → 直接替换旧稿（绝不与旧稿拼接）→ 成功；
 * - [CONTINUE_SUFFIX]：Provider length/max_tokens、tool_calls、未闭合报告且没有完整可解析报告 → 接尾续写；
 * - [REPAIR_REPLACE]：整段模板占位 / 无效 JSON / 本地 clipAgentReport 省略标记 → 一次完整替换修复；
 * - [NO_PROGRESS]：续跑只回了「无需重复/上一轮已完成」等元话语 → 保留旧报告，停止自动循环。
 *
 * 可见性同 [AgentResumeMode]：被公开数据类 [AgentRunOutcome] 的属性引用，必须公开。
 */
enum class AgentReportAssessment {
    COMPLETE,
    REPLACE_COMPLETE,
    CONTINUE_SUFFIX,
    REPAIR_REPLACE,
    NO_PROGRESS,
}

/**
 * v222：一次代理运行的完整结果。
 *
 * v221 的 run() 只返回 [AgentReport]，把「结束原因」丢掉了，
 * 于是「写到一半被截断」和「正常写完」在上层完全无法区分，
 * 半截结果被当成成功，用户前面花的钱白费。
 */
data class AgentRunOutcome(
    val report: AgentReport,
    /** Provider 返回的结束原因（stop / end_turn / length / max_tokens / incomplete:...） */
    val finishReason: String? = null,
    /** 是否因达到输出上限被截断 */
    val truncated: Boolean = false,
    /**
     * v236：本次实际使用的模型 id。
     *
     * 备用模型链可能在运行中途换人（第一个模型崩了自动换下一个），
     * 所以「派发时指定的模型」和「实际干活的模型」可以不是同一个。
     * 落库后界面与主模型都能看到真正是谁跑出来的这份报告。
     */
    val modelId: String? = null,
    /**
     * v251：报告评估结果。
     *
     * null 表示后端没有评估（旧的假后端直接给 truncated），管理器按
     * `truncated → CONTINUE_SUFFIX，否则 COMPLETE` 兼容。
     */
    @PublishedApi internal val assessment: AgentReportAssessment? = null,
    /**
     * v251：本次运行实际采用的续跑模式（resume 时由后端从线程状态推导）。
     * 管理器用它区分「第一次修复机会」与「修复轮再次失败」，防止无限自动循环。
     */
    @PublishedApi internal val resumeMode: AgentResumeMode? = null,
)

/**
 * v218：代理线程执行后端。
 *
 * 第 1 阶段只有 [FakeAgentBackend]（不联网、不调用模型，用于并发/停止/生命周期验证）；
 * 第 2 阶段接入 GenerationLoop 时实现真实后端（独立消息列表、独立助手副本、只读工具）。
 */
interface AgentBackend {
    /**
     * 运行一个代理线程。
     *
     * @param resume    是否为「继续输出」：true 时后端应把该线程已产出的内容作为上下文，
     *                  让模型从中断处接着写，而不是从头重跑（避免重复收费与重复内容）
     * @param onMessage 产出可观察消息时回调（会落库到 AgentDatabase）
     * @param onEvent   发生工具/进度事件时回调（会落库到 AgentDatabase）
     * @return 本次运行结果（报告 + 结束原因 + 是否截断）
     */
    suspend fun run(
        thread: AgentThread,
        resume: Boolean = false,
        onMessage: suspend (AgentMessage) -> Unit,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentRunOutcome
}

/** 演示/测试后端：不调用任何模型，模拟流式产出消息与事件 */
class FakeAgentBackend(
    private val delayMillis: Long = 100,
    private val messageCount: Int = 2,
    private val failAfterMessages: Int = -1,
    private val failWith: Exception? = null,
    /** v222：模拟 Provider 的结束原因，用于验证截断识别（如 "length" / "max_tokens"） */
    private val finishReason: String? = null,
) : AgentBackend {

    /** v222：记录最近一次 run 是否为续跑，供测试断言 */
    @Volatile
    var lastResume: Boolean = false
        private set

    override suspend fun run(
        thread: AgentThread,
        resume: Boolean,
        onMessage: suspend (AgentMessage) -> Unit,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentRunOutcome {
        lastResume = resume
        repeat(messageCount) { index ->
            if (failWith != null && index >= failAfterMessages) throw failWith
            onEvent(
                AgentEvent(
                    threadId = thread.id,
                    type = "PROGRESS",
                    detail = "第 ${index + 1}/$messageCount 步",
                    createdAt = Instant.now(),
                )
            )
            onMessage(
                AgentMessage(
                    threadId = thread.id,
                    role = "assistant",
                    content = "观察 ${index + 1}：${thread.task}",
                    createdAt = Instant.now(),
                )
            )
            delay(delayMillis)
        }
        return AgentRunOutcome(
            report = AgentReport(
                conclusion = "完成对「${thread.task}」的检查",
                evidence = listOf("共 $messageCount 条观察"),
                uncertainties = listOf("无"),
                suggestions = emptyList(),
            ),
            finishReason = finishReason,
            truncated = AgentFinishReason.isTruncated(finishReason),
        )
    }
}
