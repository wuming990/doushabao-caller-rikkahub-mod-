package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * v218：独立子代理活动面板（与圆桌完全无关）。
 * v220：新增关闭渠道 —— 面板不再只能展开/收起，已结束的线程可以一键清空，
 * 单个终态线程也能在详情框里单独关闭；展开列表限高可滚动，避免线程多时挤掉输入框。
 *
 * 仅在当前会话有**未关闭**的代理线程时显示在输入框上方；
 * 点击展开/收起；点单个代理可弹框查看可观察到的完整输出与结构化报告；
 * 运行中代理可单独点击「停止」，终态代理可单独点击「关闭」。
 */
@Composable
fun AgentActivityPanel(
    threads: List<AgentThread>,
    onStop: (String) -> Unit,
    onMerge: (String) -> Unit = {},
    onClose: (String) -> Unit = {},
    onCloseFinished: () -> Unit = {},
    onOpenThreadChat: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // v220：CLOSED 线程不再占用界面（记录仍保留在 AgentDatabase 中）
    val visible = visibleAgentThreads(threads)
    if (visible.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    val activeCount = visible.count { !it.status.isTerminal }
    val finishedCount = visible.size - activeCount
    val listScrollState = rememberScrollState()

    // v245：面板上也要能看出「跑了多久 / 多久没动静」。
    //
    // 此前这里只有一行状态文字，跑 10 秒和卡 20 分钟长得一模一样，只有点进详情页才看得到
    // 时长。而主模型在等子代理时用户看的就是这个面板，看不出进度只能干等。
    // 计时器只在**有活动线程**时才开，避免整个聊天页每秒重组。
    val manager: AgentThreadManager = koinInject()
    val activity by manager.activityFlow.collectAsStateWithLifecycle()
    // v246：面板还要说清「为什么没动静」—— 用户在这里看到红色的「没动静 7 分 49 秒」，
    // 其实是正卡在一次工具调用里，不说原因就像死机（真机反馈）。
    val stage by manager.stageFlow.collectAsStateWithLifecycle()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val ticking = activeCount > 0
    LaunchedEffect(ticking) {
        while (ticking) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            // 顶栏：代理活动状态 + 清空已结束 + 展开收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "代理活动",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (activeCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Text("$activeCount 运行中")
                    }
                }
                if (finishedCount > 0) {
                    Text(
                        text = "（$finishedCount 个已结束）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // v220：关闭渠道 —— 一键清空已结束的代理（运行中的不受影响）
                if (finishedCount > 0) {
                    IconButton(onClick = onCloseFinished) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "清空已结束的代理",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        // v220：线程多时限高可滚动，不再把输入框挤出屏幕
                        .heightIn(max = 220.dp)
                        .verticalScroll(listScrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visible.forEach { thread ->
                        val active = !thread.status.isTerminal
                        val signal = stage[thread.id]
                        AgentThreadRow(
                            thread = thread,
                            ranSeconds = thread.startedAt
                                ?.takeIf { active }
                                ?.let { ((nowMillis - it.toEpochMilli()) / 1000).coerceAtLeast(0) },
                            idleSeconds = activity[thread.id]
                                ?.takeIf { active }
                                ?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) },
                            phaseText = agentPhaseTextFrom(
                                status = thread.status,
                                lastEventType = signal?.lastEventType,
                                pendingTools = signal?.pendingTools ?: 0,
                                hasMessages = signal?.hasMessages == true,
                            ),
                            toolRunning = (signal?.pendingTools ?: 0) > 0,
                            onStop = { onStop(thread.id) },
                            onClick = { selectedThreadId = thread.id },
                        )
                    }
                }
            }
        }
    }

    // 线程详情弹框（可观察事件、结构化报告、错误、原文）
    // 按 id 取最新对象，避免线程状态更新后弹框还显示旧快照；
    // 线程被关闭后自然从 visible 里消失，弹框随之关闭（不在组合期写状态）。
    val selectedThread = selectedThreadId?.let { id -> visible.firstOrNull { it.id == id } }
    selectedThread?.let { thread ->
        AgentThreadDetailDialog(
            thread = thread,
            onDismiss = { selectedThreadId = null },
            onStop = {
                onStop(thread.id)
                selectedThreadId = null
            },
            onMerge = {
                onMerge(thread.id)
                selectedThreadId = null
            },
            onClose = {
                onClose(thread.id)
                selectedThreadId = null
            },
            onEnterChat = {
                selectedThreadId = null
                onOpenThreadChat(thread.id)
            },
        )
    }
}

/** v220：界面上只展示未关闭的线程（CLOSED 表示用户已收走，不再占用输入框上方空间） */
internal fun visibleAgentThreads(threads: List<AgentThread>): List<AgentThread> =
    threads.filter { it.status != AgentThreadStatus.CLOSED }

/** v220：可被「清空已结束」关闭的线程 id（仅终态且尚未 CLOSED；运行中绝不动） */
internal fun closableAgentThreadIds(threads: List<AgentThread>): List<String> =
    threads.filter { it.status.isTerminal && it.status != AgentThreadStatus.CLOSED }
        .map { it.id }

@Composable
private fun AgentThreadRow(
    thread: AgentThread,
    onStop: () -> Unit,
    onClick: () -> Unit,
    /** v245：已经跑了多久（秒）；null = 不显示（终态） */
    ranSeconds: Long? = null,
    /** v245：多久没有新内容（秒）；null = 不显示 */
    idleSeconds: Long? = null,
    /** v246：当前阶段（等待模型返回 / 工具执行中 / …）；null = 不显示 */
    phaseText: String? = null,
    /** v246：是否有工具正在执行 —— 这时候「没动静」是正常的，不许标红 */
    toolRunning: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "「${thread.role.name.lowercase()}」${thread.task}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = agentStatusText(thread),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (thread.status) {
                        AgentThreadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        AgentThreadStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
                        AgentThreadStatus.WAITING_AUTO_RETRY -> MaterialTheme.colorScheme.tertiary
                        AgentThreadStatus.SUCCEEDED -> if (thread.truncated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                        AgentThreadStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v245：时长单独一行，「没动静」按时长变色（与详情页同一套判据）
                // v246：前面加当前阶段；工具在跑时改文案并且不标红 ——
                // 真机反馈：红色的「没动静 7 分 49 秒」其实是在跑工具，红字属于误导。
                val timing = buildList {
                    if (phaseText != null) add(phaseText)
                    if (ranSeconds != null) add("已跑 ${formatAgentDuration(ranSeconds)}")
                    if (idleSeconds != null) {
                        add(
                            if (toolRunning) "工具已跑 ${formatAgentDuration(idleSeconds)}"
                            else "没动静 ${formatAgentDuration(idleSeconds)}"
                        )
                    }
                }
                if (timing.isNotEmpty()) {
                    Text(
                        text = timing.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            // 工具在跑不是卡住，绝不标红
                            toolRunning -> MaterialTheme.colorScheme.primary
                            idleSeconds != null && idleSeconds >= 300L -> MaterialTheme.colorScheme.error
                            idleSeconds != null && idleSeconds >= 120L -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (thread.status == AgentThreadStatus.STOPPING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else if (!thread.status.isTerminal) {
                // v236：等待自动重试时也要能停 —— 这正是 v235 停不掉的那个窗口
                TextButton(onClick = onStop) {
                    Text("停止", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 状态中文说明（抽成纯函数，便于单元测试与复用） */
internal fun agentStatusText(thread: AgentThread?): String {
    if (thread == null) return "未知"
    return when (thread.status) {
        AgentThreadStatus.QUEUED -> "排队中"
        AgentThreadStatus.RUNNING -> "正在查证..."
        AgentThreadStatus.STOPPING -> "正在停止…"
        AgentThreadStatus.WAITING_APPROVAL -> "等待审批"
        // v236：退避等待必须说清「在等、几秒后会自己再试」，
        // 否则用户看到「失败」几秒后又变「正在查证」，完全不知道后台在干什么。
        AgentThreadStatus.WAITING_AUTO_RETRY -> "上游出错，等待自动重试…"
        AgentThreadStatus.SUCCEEDED -> if (thread.truncated) "输出未完（字数上限）" else "已完成"
        AgentThreadStatus.FAILED -> "失败：${thread.error ?: "未知错误"}"
        AgentThreadStatus.STOPPED -> "已停止"
        AgentThreadStatus.INTERRUPTED -> "已中断"
        AgentThreadStatus.CLOSED -> "已关闭"
    }
}

/**
 * v245：它现在到底在干什么（活动中的线程才有）。
 *
 * ## 为什么需要
 *
 * 状态文字只有一个笼统的「正在查证...」，加上「多久没动静」也只能看出「有没有在动」，
 * 看不出**为什么不动**。真机上三种完全不同的情况长得一模一样：模型还没开始吐字、
 * 工具正在执行（同步执行期间一个数据块都不会来）、刚判过卡死正在重来。
 *
 * ## 为什么不需要后端配合
 *
 * 后端本来就把这些都落成事件了（TOOL_CALL / TOOL_RESULT / MODEL_RETRY /
 * MODEL_FALLBACK / TIMEOUT），界面直接推导即可，零新增数据通道。
 * 工具配对靠事件 id —— [me.rerere.rikkahub.agent.runtime.agentEventId] 生成的形状是
 * `threadId:toolCallId:CALL` / `…:RESULT`，去掉后缀就能配对。
 *
 * 抽成纯函数是为了能真单测（本项目没有 Compose 测试环境，界面逻辑只能这样验）。
 */
internal fun agentPhaseText(
    thread: AgentThread?,
    events: List<AgentEvent>,
    hasMessages: Boolean,
): String? = agentPhaseTextFrom(
    status = thread?.status,
    lastEventType = events.lastOrNull()?.type,
    pendingTools = agentPendingToolCount(events),
    hasMessages = hasMessages,
)

/**
 * v246：还有几个工具「发出去了但还没拿到结果」。
 *
 * 工具事件的 id 形状是 `threadId:toolCallId:CALL` / `…:RESULT`
 * （见 [me.rerere.rikkahub.agent.runtime.agentEventId]），去掉后缀即可配对。
 *
 * 大于 0 表示正在跑工具 —— **这时候「没动静」在涨是正常的，不该标红**。
 */
internal fun agentPendingToolCount(events: List<AgentEvent>): Int {
    val called = events.filter { it.type == "TOOL_CALL" }
        .map { it.id.removeSuffix(":CALL") }
        .toSet()
    val returned = events.filter { it.type == "TOOL_RESULT" }
        .map { it.id.removeSuffix(":RESULT") }
        .toSet()
    return (called - returned).size
}

/**
 * v246：阶段判定的唯一实现。
 *
 * 详情页有完整事件轨迹、聊天页面板只有内存里的阶段信号（[me.rerere.rikkahub.agent.model.AgentStageSignal]），
 * 两边都走这一个函数，避免同一件事在两个界面上说法不一致。
 */
internal fun agentPhaseTextFrom(
    status: AgentThreadStatus?,
    lastEventType: String?,
    pendingTools: Int,
    hasMessages: Boolean,
): String? {
    if (status == null || status.isTerminal) return null
    return when (status) {
        AgentThreadStatus.QUEUED -> "排队等额度"
        AgentThreadStatus.STOPPING -> null
        AgentThreadStatus.WAITING_APPROVAL -> null
        AgentThreadStatus.WAITING_AUTO_RETRY -> "等待自动重试"
        else -> if (pendingTools > 0) {
            // 工具执行期间收不到任何数据，这时候「没动静」在涨是正常的
            "工具执行中"
        } else {
            when (lastEventType) {
                "MODEL_FALLBACK" -> "正在换备用模型"
                "MODEL_RETRY" -> "同一个模型再试一次"
                "TIMEOUT" -> "刚判过卡死，正在重来"
                else -> if (hasMessages) "正在输出" else "等待模型返回"
            }
        }
    }
}

@Composable
private fun AgentThreadDetailDialog(
    thread: AgentThread,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit,
    onEnterChat: (() -> Unit)? = null,
) {
    val report = AgentReport.decode(thread.reportJson)
    val detailScrollState = rememberScrollState()
    // v237：实际跑这次任务的模型。v236 起 activeModelId 就落库了，但界面从不显示，
    // 用户完全看不出备用模型有没有换过人。
    val settings by rememberUserSettingsState()
    val activeModelName = thread.activeModelId
        ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        ?.let { settings.findModelById(it)?.displayName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("子代理详情 · ${thread.role.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(detailScrollState),
            ) {
                Text("任务：${thread.task}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("状态：${agentStatusText(thread)}", style = MaterialTheme.typography.labelMedium)
                if (activeModelName != null) {
                    Text(
                        "实际跑的模型：$activeModelName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (thread.truncated) {
                    Text(
                        "⚠ 本次输出达到模型单次字数上限，进入完整对话可点「继续输出」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                HorizontalDivider()
                if (report != null && report.conclusion.isNotBlank()) {
                    Text("结论：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(report.conclusion, style = MaterialTheme.typography.bodySmall)
                } else if (thread.reportJson != null) {
                    Text("输出原文：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(thread.reportJson, style = MaterialTheme.typography.bodySmall)
                }
                if (thread.error != null) {
                    Text("错误信息：", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    Text(thread.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onEnterChat != null) {
                    TextButton(onClick = onEnterChat) {
                        Text("进入完整对话", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (report?.conclusion?.isNotBlank() == true && thread.status.isTerminal) {
                    TextButton(onClick = onMerge) {
                        Text("合并到聊天", style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭窗口")
                }
            }
        },
        dismissButton = {
            if (thread.status.isTerminal) {
                // v220：终态线程可以单独关闭（从面板移除，记录仍保留）
                TextButton(onClick = onClose) {
                    Text("移除此代理")
                }
            } else if (thread.status != AgentThreadStatus.STOPPING) {
                TextButton(onClick = onStop) {
                    Text("停止此代理", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
