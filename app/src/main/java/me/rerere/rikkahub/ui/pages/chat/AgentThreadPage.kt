package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import me.rerere.rikkahub.agent.repo.AgentThreadRepository
import me.rerere.rikkahub.agent.runtime.AgentThreadManager
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * v221：子代理独立全屏对话页。
 * v222：
 * 1. 停止过渡态（STOPPING）：点下立刻显示「正在停止…」和转圈，按钮禁用防重复点；
 * 2. 截断提醒：输出被截断时显示醒目黄底横幅，明确说明「达到模型单次输出上限」；
 * 3. 继续输出（续跑）：对截断、停止、中断、失败的线程亮出「继续输出」按钮，
 *    点击即可让模型从半截处接着写，不再白白浪费之前的花费。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentThreadPage(
    threadId: String,
    conversationId: String,
) {
    val navController = LocalNavController.current
    val manager: AgentThreadManager = koinInject()
    val repository: AgentThreadRepository = koinInject()
    val scope = rememberCoroutineScope()

    val threads by manager.threadsFlow(conversationId).collectAsStateWithLifecycle(initialValue = emptyList())
    val thread = threads.firstOrNull { it.id == threadId }
    val messages by repository.messagesFlow(threadId).collectAsStateWithLifecycle(initialValue = emptyList())
    val events by repository.eventsFlow(threadId).collectAsStateWithLifecycle(initialValue = emptyList())

    // v237：把「这次实际是哪个模型跑的」显示出来。
    // activeModelId 从 v236 起就落库、也回传给主模型了，但界面一个字都不显示，
    // 用户因此完全看不出备用模型到底有没有换过人（真实反馈）。
    val settings by rememberUserSettingsState()
    val activeModelName = thread?.activeModelId
        ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        ?.let { settings.findModelById(it)?.displayName }
    val modelSwitched = events.any { it.type == "MODEL_FALLBACK" }

    // v238：跑了多久 / 多久没有新内容。
    // 用户真实反馈：「子代理卡半天我很难判断是不是卡住了」。此前状态栏只有一个
    //「正在查证...」，跑 10 秒和跑 10 分钟长得一模一样，没有任何可判断的信息。
    // 只在活动态开这个每秒刷新的循环，终态不刷（省电、避免无意义重组）。
    val threadActive = thread != null && !thread.status.isTerminal
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(threadActive) {
        while (threadActive) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }
    val startedAtMillis = thread?.startedAt?.toEpochMilli()
    // v245：真实活动时间。
    //
    // 数据库里的 createdAt 是「这条消息第一次出现」的时刻，而流式输出是同一条记录反复
    // 覆盖（v222 为了不刷屏刻意如此），所以模型在一条消息里持续写下去时 createdAt 永不
    // 更新 —— 真机已确认：正文在增长，「没动静」也在增长。用户正是靠这个数字判断要不要
    // 干预，它一失真判断全错。运行中一律以管理器的实时打点为准，拿不到才退回数据库时间。
    val liveActivity by manager.activityFlow.collectAsStateWithLifecycle()
    // 「新内容」= 新消息或新事件，两者取最晚的一个
    val lastActivityMillis = maxOf(
        messages.maxOfOrNull { it.createdAt.toEpochMilli() } ?: 0L,
        events.maxOfOrNull { it.createdAt.toEpochMilli() } ?: 0L,
        liveActivity[threadId] ?: 0L,
    ).takeIf { it > 0L }
    val liveRanSeconds = if (threadActive && startedAtMillis != null) {
        ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)
    } else {
        null
    }
    // v244：「多久没有新内容」必须单独占位、排在前面，不能再和别的信息挤一行。
    //
    // 用户实测反馈（附了截图）：v239 把「已跑多久」和「多久没有新内容」拼在同一行，
    // 右边还并排两个跳转按钮，那一行根本放不下，末尾被省略号吃掉 —— 被吃掉的恰恰是
    // 最关键的半句。截图里显示的是「已跑 25 分 25 秒 · 已 23 …」，而那条子代理当时
    // 确实已经 23 分钟没有任何新内容（真的卡住了，却看不出来）。
    val liveIdleSeconds = if (threadActive && lastActivityMillis != null) {
        ((nowMillis - lastActivityMillis) / 1000).coerceAtLeast(0)
    } else {
        null
    }
    // v246：有工具正在执行时，「没动静」在涨是正常的，不许标红（真机反馈过这个误导）
    //
    // v247：光不标红还不够。真机反馈原话是「有显示工具执行中，但是工具执行中没动静的计时
    // 还在涨，我不知道是特意设置还是漏洞」—— 面板（AgentActivityPanel）v246 已经把文案
    // 换成「工具已跑」，详情页当时只改了颜色、漏了文案，于是同一件事两个界面两种说法，
    // 正是 v246 想消掉的「界面说法漂移」。
    //
    // 数字继续涨是**刻意的**：它如实反映「模型确实这么久没吐新内容」，不能为了好看去清零。
    // 要改的是标签 —— 工具在跑时叫「工具已跑」，这样一眼就知道秒数是花在工具上、不是卡死。
    val livePendingTools = agentPendingToolCount(events)
    val liveProgressText = liveRanSeconds?.let { "已跑 ${formatAgentDuration(it)}" }
    val liveIdleText = liveIdleSeconds?.let {
        if (livePendingTools > 0) "工具已跑 ${formatAgentDuration(it)}"
        else "没动静 ${formatAgentDuration(it)}"
    }
    // v245：光有「多久没动静」还不够 —— 还要说清它为什么不动。
    // 阶段完全由既有事件推导（工具调用/工具结果/换模型/重试），后端零改动。
    val livePhaseText = agentPhaseText(
        thread = thread,
        events = events,
        hasMessages = messages.isNotEmpty(),
    )

    // v238：「换模型继续」用的模型选择面板。
    // 为什么需要：主模型 spawn 时点名过模型的话，那个模型永远排在候选链第一位，
    // 改全局设置也挤不掉它，而此前没有任何地方能改已存在线程的模型。
    val switchModelPicker = rememberModelListState(
        modelId = thread?.modelId?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() },
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    val listState = rememberLazyListState()
    // v239：只有用户本来就停在底部时才自动跟到最新。
    // 否则用户往上翻着看的时候会被新内容一次次拽回去，越长越没法读。
    val followLatest by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size, events.size, thread?.reportJson) {
        if (!followLatest) return@LaunchedEffect
        // v239 修 bug：旧代码滚到 messages.size + events.size。
        // 那个数字既不是最后一项也不是第一项 —— 列表里还有任务卡片、两个小标题、
        // 截断横幅和最终报告，于是它正好落在中间，每次进页面都停在半中央，
        // 想看「多久没更新」要往上翻、想看「跑到哪一步」要往下翻（用户实测反馈）。
        // 这里改成按真实项数滚到最后一项；项数要等首帧测量出来才有，所以等它 > 0。
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.animateScrollToItem(total - 1)
    }

    // v245：同一条消息内容变长时也要跟到最新。
    //
    // 上面那个只看「条数」变化，而流式输出是同一条记录不断变长（稳定 id + 覆盖写），
    // 于是正文在增长、页面却停在原处，看起来就像「没有新内容」。
    //
    // 必须节流：每来一小段就滚一次会和手指抢滚动、也更卡。这里 700 毫秒最多滚一次，
    // 而且用瞬时滚动（animateScrollToItem 在高频更新下会互相打断）。
    LaunchedEffect(threadId) {
        var lastScrollAtMillis = 0L
        snapshotFlow { messages.sumOf { it.content.length } }
            .collect {
                if (!followLatest) return@collect
                val now = System.currentTimeMillis()
                if (now - lastScrollAtMillis < 700L) return@collect
                lastScrollAtMillis = now
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) listState.scrollToItem(total - 1)
            }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "子代理 · ${thread?.role?.name?.lowercase() ?: "agent"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = thread?.task ?: "正在加载任务...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = HugeIcons.ArrowLeft01,
                                contentDescription = "返回主对话",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                // v239：常驻状态条。
                //
                // 用户原话：「我想拉到最上面看多久没更新内容和最下面到哪一步的时候
                // 文本一长就很费力，不能直接像圆桌模式那样么」。
                // 圆桌的做法就是一条不随内容滚动的紧凑面板 —— 这里照搬：
                // 「跑了多久 / 多久没新内容 / 换过哪个模型 / 续跑几次」全部钉在顶上，
                // 右边给两个直达按钮，不用再靠手滑找位置。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = agentStatusText(thread),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when (thread?.status) {
                                    AgentThreadStatus.FAILED -> MaterialTheme.colorScheme.error
                                    AgentThreadStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
                                    AgentThreadStatus.WAITING_AUTO_RETRY -> MaterialTheme.colorScheme.tertiary
                                    AgentThreadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (liveProgressText != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = liveProgressText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            TextButton(
                                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("回顶部", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val total = listState.layoutInfo.totalItemsCount
                                        if (total > 0) listState.animateScrollToItem(total - 1)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("到最新", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val statusDetail = buildList {
                            if (activeModelName != null) {
                                add(
                                    if (modelSwitched) "模型 $activeModelName（换过备用）"
                                    else "模型 $activeModelName"
                                )
                            }
                            if (thread != null && thread.resumeCount > 0) {
                                add("已续跑 ${thread.resumeCount} 次")
                            }
                            if (thread?.truncated == true) add("上次输出被截断")
                        }
                        if (statusDetail.isNotEmpty() || liveIdleText != null || livePhaseText != null) {
                            // v244：静默时长排第二行最前面，并按时长变色 ——
                            // 2 分钟内灰（正常）、超过 2 分钟橙（该留意了）、
                            // 超过 5 分钟红（基本可以判定卡住，此时看门狗也快介入了）。
                            // 这样一眼就能分清「它在干活」和「它卡住了」，不用再去数秒。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // v245：先说「在干什么」，再说「多久没动静」——
                                // 工具执行期间「没动静」在涨是正常的，没有阶段就会看成卡住。
                                if (livePhaseText != null) {
                                    Text(
                                        text = livePhaseText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = " · ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (liveIdleText != null) {
                                    val idle = liveIdleSeconds ?: 0L
                                    Text(
                                        text = liveIdleText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            // v246：工具在跑不是卡住
                                            livePendingTools > 0 -> MaterialTheme.colorScheme.primary
                                            idle >= 300L -> MaterialTheme.colorScheme.error
                                            idle >= 120L -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                    if (statusDetail.isNotEmpty()) {
                                        Text(
                                            text = " · ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (statusDetail.isNotEmpty()) {
                                    Text(
                                        text = statusDetail.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (modelSwitched || thread?.truncated == true) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val currentThread = thread
                    when {
                        currentThread == null -> {
                            OutlinedButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("返回主对话")
                            }
                        }

                        // 正在停止过渡态
                        currentThread.status == AgentThreadStatus.STOPPING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.weight(1f),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在停止…")
                            }
                        }

                        // 运行中 / 排队中
                        !currentThread.status.isTerminal -> {
                            Button(
                                onClick = { manager.stop(threadId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(HugeIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止生成")
                            }
                            // v241：中途换模型接着跑。
                            // 用户原话：「新增中途切换模型续跑的功能。」
                            // 以前必须先点停止、等它落终态、再点换模型（三步）；
                            // 现在一步：内部会自动停当前那次生成、保留已产出内容、换人接着写。
                            OutlinedButton(
                                onClick = { switchModelPicker.open() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("换模型继续", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        // 终态
                        else -> {
                            OutlinedButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("返回主对话")
                            }

                            // v222：截断 / 停止 / 中断 / 失败时亮出「继续输出」
                            if (currentThread.canResume) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            manager.resume(threadId)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (currentThread.resumeCount > 0) "继续（${currentThread.resumeCount}）"
                                        else "继续输出"
                                    )
                                }
                            }

                            // v238：换个模型接着跑。
                            // 用户原话：「子代理卡半天我很难判断是不是卡住了，
                            // 我想直接切换另一个子代理续跑」。已产出的内容全部保留。
                            if (currentThread.canResume) {
                                OutlinedButton(
                                    onClick = { switchModelPicker.open() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("换模型继续", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            val report = AgentReport.decode(currentThread.reportJson)
                            if (report != null && report.conclusion.isNotBlank() && !currentThread.truncated) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            manager.close(threadId)
                                            navController.popBackStack()
                                        }
                                    },
                                    modifier = Modifier.weight(1.2f),
                                ) {
                                    Icon(HugeIcons.CheckmarkCircle02, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("完成并返回")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 任务信息卡片
            item(key = "task_header") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "任务目标",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = agentStatusText(thread),
                                style = MaterialTheme.typography.labelMedium,
                                color = when (thread?.status) {
                                    AgentThreadStatus.FAILED -> MaterialTheme.colorScheme.error
                                    AgentThreadStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
                                    AgentThreadStatus.WAITING_AUTO_RETRY -> MaterialTheme.colorScheme.tertiary
                                    AgentThreadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // v239：「已跑多久 · 已多久没有新内容」已挪到顶部常驻状态条，
                        // 这里不再重复一份 —— 重复显示只会挤占正文空间。
                        Text(
                            text = thread?.task ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val contextText = thread?.contextSummary
                        if (!contextText.isNullOrBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            // v239：contextSummary 现在可能是几万字的主对话原文，
                            // 整段渲染会把详情页卡死，所以默认只给前 300 字 + 总字数。
                            var contextExpanded by remember(thread?.id) { mutableStateOf(false) }
                            val preview = if (contextText.length <= CONTEXT_PREVIEW_CHARS) {
                                contextText
                            } else {
                                contextText.take(CONTEXT_PREVIEW_CHARS) + "…"
                            }
                            Text(
                                text = "上下文（${contextText.length} 字）：" +
                                    if (contextExpanded) contextText else preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (contextText.length > CONTEXT_PREVIEW_CHARS) {
                                TextButton(onClick = { contextExpanded = !contextExpanded }) {
                                    Text(
                                        text = if (contextExpanded) "收起上下文" else "展开全部上下文",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                        if (thread != null && thread.resumeCount > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "已续跑 ${thread.resumeCount} 次（保留中断前全部内容）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // v237：实际跑这次任务的模型（换过备用模型时特别标出来）
                        if (activeModelName != null) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = if (modelSwitched) {
                                    "实际跑的模型：$activeModelName（中途换过备用模型）"
                                } else {
                                    "实际跑的模型：$activeModelName"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (modelSwitched) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            // v222：截断横幅（达到输出上限）
            if (thread?.truncated == true) {
                item(key = "truncation_banner") {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠ 输出未完（达到模型单次字数上限）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "模型在输出第 ${messages.size} 条消息时被截断（原因：${thread.finishReason ?: "length"}）。" +
                                    "前面的内容已完整保留，点击底部「继续输出」即可让模型接着写。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            // 工具事件
            if (events.isNotEmpty()) {
                item(key = "events_header") {
                    Text(
                        text = "工具调用轨迹（${events.size} 项动作）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                items(events, key = { "evt_${it.id}" }) { event ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${event.type}：${event.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 模型输出消息
            if (messages.isNotEmpty()) {
                item(key = "messages_header") {
                    Text(
                        text = "模型输出内容",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                items(messages, key = { "msg_${it.id}" }) { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (message.role == "user") "补充指令" else "子代理回复",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // 最终报告
            val report = AgentReport.decode(thread?.reportJson)
            if (report != null && report.conclusion.isNotBlank()) {
                item(key = "final_report") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "结构化总结报告",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = report.conclusion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            } else if (!thread?.error.isNullOrBlank()) {
                item(key = "error_box") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "执行错误",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = thread?.error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // v249：底部锚点。
            //
            // 「到最新」原来滚到 total - 1，而最后一项往往就是那条几千字的长回复卡片。
            // LazyColumn 的语义是「把该项的**顶部**对齐视口顶部」，于是跳到回复开头，
            // 用户还得自己往下滑很长一段（真机反馈原话：「这个到最新直接跳转到回复
            // 刚开始，而不是跳转到最底下的输出」）。
            //
            // 主对话早就用这个办法解决了（ChatList.kt 的 ScrollBottomKey），这里照搬：
            // 末尾放一个几 dp 的空条，滚到它就等于滚到整页最底。
            // 自动跟随（v239/v245 那两处 scrollToItem(total - 1)）也一起受益，不用改。
            item(key = "scroll_bottom_anchor") {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }
    }

    // v238：换模型继续 —— 选完直接把模型钉进这条线程再续跑（已产出内容保留）
    // v241：改用 switchModelAndResume，运行中的线程也能直接换 ——
    // 它会先停下当前那次生成、等它真的停住，再换人从断点接着写。
    ModelListSheet(
        state = switchModelPicker,
        onSelect = { model ->
            scope.launch {
                manager.switchModelAndResume(threadId, model.id.toString())
            }
        },
    )
}

/**
 * v238：把秒数格式化成人话。
 *
 * 不用「00:03:12」这种表盘格式 —— 用户看的是「卡了多久」，
 * 「3 分 12 秒」比冒号分隔更容易一眼判断。
 *
 * v245：改成 internal —— 聊天页的子代理面板（AgentActivityPanel）也要显示时长，
 * 两处必须用同一套格式，不然同一件事在两个地方长得不一样。
 */
internal fun formatAgentDuration(totalSeconds: Long): String {
    if (totalSeconds < 60) return "$totalSeconds 秒"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
}

/** v239：详情页默认只渲染这么多字的上下文，避免几万字原文把页面卡死 */
private const val CONTEXT_PREVIEW_CHARS = 300
