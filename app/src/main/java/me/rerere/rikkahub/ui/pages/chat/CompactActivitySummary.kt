package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.service.GroupRunState
import me.rerere.rikkahub.service.GroupRunStatus

/** v1023：短入口与完整朗读说明共用同一份实时状态，只影响显示，不调度、不改任何记录。 */
internal data class CompactActivitySummary(
    val label: String,
    val contentDescription: String,
    val needsAttention: Boolean = false,
)

internal fun compactAgentActivitySummary(threads: List<AgentThread>): CompactActivitySummary? {
    val visible = threads.filter { it.status != AgentThreadStatus.CLOSED }
    if (visible.isEmpty()) return null
    val active = visible.count { !it.status.isTerminal }
    val finished = visible.size - active
    val waiting = visible.count { it.status == AgentThreadStatus.WAITING_APPROVAL }
    val failed = visible.count { it.status == AgentThreadStatus.FAILED }
    val interrupted = visible.count { it.status == AgentThreadStatus.INTERRUPTED }
    val counts = buildList {
        if (waiting > 0) add("${waiting}待批")
        if (active > waiting) add("${active - waiting}运行")
        if (finished > 0) add("${finished}结束")
    }.joinToString("·")
    val description = buildString {
        append("代理活动：$active 个运行中，$finished 个已结束")
        if (waiting > 0) append("；$waiting 个等待审批")
        if (failed > 0) append("；$failed 个失败")
        if (interrupted > 0) append("；$interrupted 个中断")
        append("。点按展开代理活动")
    }
    return CompactActivitySummary(
        label = "代理 $counts",
        contentDescription = description,
        needsAttention = waiting > 0 || failed > 0 || interrupted > 0,
    )
}

internal fun compactGroupActivitySummary(
    state: GroupRunState?,
    hasConfig: Boolean,
    paused: Boolean,
    membersEnough: Boolean,
): CompactActivitySummary? {
    if (state == null && !hasConfig) return null
    val status = state?.status ?: GroupRunStatus.IDLE
    val shortStatus = when {
        paused -> "已暂停"
        else -> when (status) {
            GroupRunStatus.IDLE -> "就绪"
            GroupRunStatus.SPEAKING -> state?.currentSpeakerName?.takeIf { it.isNotBlank() } ?: "发言中"
            GroupRunStatus.WAITING_FOR_USER -> "等你回答"
            GroupRunStatus.PAUSED -> "可继续"
            GroupRunStatus.STOPPED -> "已停止"
            GroupRunStatus.FINISHED -> "已结束"
        }
    }
    val fullStatus = when {
        paused -> "讨论群已暂停，成员和设置都留着"
        status == GroupRunStatus.SPEAKING -> "${state?.currentSpeakerName.orEmpty()} 正在发言"
        status == GroupRunStatus.WAITING_FOR_USER -> "有成员正在等待你回答"
        else -> "讨论群$shortStatus"
    }
    val progress = if (!paused && state != null && state.turnsBudget > 0) {
        "${state.turnsInRun}/${state.turnsBudget}"
    } else null
    val description = buildString {
        append(fullStatus)
        if (progress != null) append("，第 $progress 条")
        if (state?.error != null) append("；${state.error}")
        if (!membersEnough) append("；成员不足两个，请展开后去群设置补人")
        append("。点按展开讨论群")
    }
    return CompactActivitySummary(
        label = "讨论群·$shortStatus" + if (progress != null) " $progress" else "",
        contentDescription = description,
        needsAttention = (!paused && status == GroupRunStatus.WAITING_FOR_USER) ||
            state?.error != null || !membersEnough,
    )
}
