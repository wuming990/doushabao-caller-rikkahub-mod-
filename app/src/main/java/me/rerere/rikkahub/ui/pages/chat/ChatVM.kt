package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.utils.UpdateInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.db.dao.ConversationTokenStats
import me.rerere.rikkahub.data.db.dao.ModelTokenStats
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getConversationTokenStats
import me.rerere.rikkahub.data.db.dao.getConversationTokenStatsByModel
import me.rerere.rikkahub.ui.components.ai.ConversationCost
import me.rerere.rikkahub.ui.components.ai.ModelPrice
import me.rerere.rikkahub.ui.components.ai.ModelUsageSlice
import me.rerere.rikkahub.ui.components.ai.computeConversationCost
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutoCompressModelSource
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.data.model.GroupPresetOpKind
import me.rerere.rikkahub.data.model.GroupPresetOpResult
import me.rerere.rikkahub.data.model.validateNewPresetName
import me.rerere.rikkahub.data.model.validatePresetRename
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.CompressionModelSource
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.GroupPickOutcome
import me.rerere.rikkahub.service.GroupRunState
import me.rerere.rikkahub.service.RoundTableGapDecision
import me.rerere.rikkahub.service.RoundTableRunState
import me.rerere.rikkahub.service.RoundTableSeatCommand
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.ApiException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val agentThreadManager: me.rerere.rikkahub.agent.runtime.AgentThreadManager,
    // v297：本对话 token 累计（对话级聚合查询，不动数据库版本）
    private val messageNodeDAO: MessageNodeDAO,
    private val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    /**
     * v268 / v299 / v300：打开（或重新进入）本对话时，恢复它最后一次使用的模型。
     *
     * v300（用户反馈「时灵时不灵」）的根因：这段逻辑原来写在 init 里，而 ViewModel 在
     * 导航返回时是**被复用**的 —— 从 A 对话切到 B、再退回 A 时，A 的导航条目自带的
     * ViewModelStore 还在，VM 不会重建，init 一辈子只跑一次，于是「切回来」这一步
     * 根本不会重新恢复，界面停在上一个对话的模型上。
     * 现在抽成幂等函数，由 ChatPage 每次进入该页面时调用（重复调用无副作用）。
     */
    fun restoreRememberedModel() {
        // v268：对话级模型记忆（照 Chatbox 设计）—— 打开/切回对话时恢复它最后一次使用的模型。
        // 写回助手默认值是刻意的：全局「当前模型」跟随正在查看的对话（= 最近使用），
        // 新对话因此沿用最近使用的模型；已存在的其它对话不受影响（它们各自有自己的记忆）。
        // v268 审查修复：直接查库取对话真实所属助手，不读内存态 conversation.value——
        // VM 创建瞬间那还是占位对话（assistantId = 当时的当前助手），跨助手打开时会把
        // 记忆模型写错到别的助手头上。生成用的就是对话所属助手的模型，恢复只写它。
        viewModelScope.launch {
            // v299 二轮审查：必须等「真实设置」到位再判断。settingsFlow 的初始值是 Settings.dummy()
            // （init=true、assistants 与 conversationModelIds 都是空的），冷启动后立刻打开对话时
            // first() 可能拿到 dummy → 记忆条目查不到 → 恢复被静默跳过。
            val settings = settingsStore.settingsFlow.first { !it.init }
            if (!settings.rememberModelPerConversation) return@launch
            val remembered = settings.conversationModelIds[_conversationId] ?: return@launch
            if (settings.findModelById(remembered) == null) return@launch
            val realAssistantId = conversationRepo.getConversationById(_conversationId)
                ?.assistantId ?: return@launch
            val needsUpdate = settings.assistants.any { assistant ->
                assistant.id == realAssistantId &&
                    (assistant.chatModelId ?: settings.chatModelId) != remembered
            }
            if (needsUpdate) {
                // v299 二轮审查（带全文那份）：只同步内存还不够 —— 恢复的 `update{}` 是**全量落盘**，
                // persistSettings 会把快照里的 assistantId 一起写回；若它晚于 ChatService
                // initializeConversation 的切助手提交，刚切好的当前助手就被回写成旧值，
                // 界面（读「当前助手的 chatModelId」）停在旧模型上，症状与修复前一字不差。
                // 所以先把当前助手切到本对话所属助手（与 ChatService.kt 那句等价、幂等），
                // 之后这份快照必然带着目标 assistantId，与对端协程的提交顺序彻底解耦。
                settingsStore.updateAssistant(realAssistantId)
                settingsStore.update { s ->
                    s.copy(
                        assistants = s.assistants.map { assistant ->
                            if (assistant.id == realAssistantId) {
                                assistant.copy(chatModelId = remembered)
                            } else {
                                assistant
                            }
                        }
                    )
                }
                // v299 诊断：只有「本对话确实有记忆条目」时才打一行（普通对话不打，避免刷屏）。
                // 真机若仍复现，去 设置→日志 搜 ChatVM 就能看到到底走到哪一步。
                Logging.log(TAG, "已恢复本对话记忆模型 $remembered（助手 $realAssistantId）")
            } else {
                // v300：即使不需要恢复也留一行 —— 用户报「时灵时不灵」时要能区分
                // 「检查跑了、本来就一致」和「检查压根没跑」这两种情况。
                Logging.log(TAG, "本对话记忆模型已是 $remembered（助手 $realAssistantId），无需恢复")
            }
        }
    }

    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // v218：独立子代理活动（与圆桌无关）
    val agentThreads: StateFlow<List<me.rerere.rikkahub.agent.model.AgentThread>> =
        agentThreadManager.threadsFlow(_conversationId.toString())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * v297：本对话 token 累计（顶栏那行小字的数据源）。
     *
     * 只在「打开对话」与「这一趟生成结束」两个时点查一次 SQL，不跟着流式分片刷新 ——
     * 聚合走 json_each 全对话扫描，每收到一个 token 就查一次会把主线程旁边的 IO 打满。
     * 口径与限制见 ConversationTokenStats 的注释。
     */
    private val _conversationTokenStats = MutableStateFlow(ConversationTokenStats())
    val conversationTokenStats = _conversationTokenStats.asStateFlow()

    /**
     * v301：按用户自填单价估算的花费（「本对话 token 明细」里那行「≈ 消耗」）。
     * 没有任何模型填过价时保持 null —— 界面不显示这一行，绝不用「≈ 0」冒充免费。
     */
    private val _conversationTokenCost = MutableStateFlow<ConversationCost?>(null)
    val conversationTokenCost = _conversationTokenCost.asStateFlow()

    private companion object {
        /**
         * v300：用量签名变化后等这么久再刷 —— 一次生成里每轮的用量会连着落库，
         * 合并成一次查询，既跟上进度又不至于把 IO 打满。
         */
        const val TOKEN_STATS_REFRESH_DEBOUNCE_MS = 800L
    }

    fun refreshConversationTokenStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _conversationTokenStats.value =
                    messageNodeDAO.getConversationTokenStats(_conversationId.toString())
                // v301：按「生成这条回答的模型」分组算花费（单价用户自己填，模型没填价就不计入）。
                // 设置还没加载好（dummy）时先不动 —— 那一瞬间所有模型都查不到价，
                // 会把「未计入」条数报得很难看，属于自己吓自己。
                val settings = settingsStore.settingsFlow.value
                if (!settings.init) {
                    val slices = messageNodeDAO.getConversationTokenStatsByModel(_conversationId.toString())
                    val modelsById = settings.providers.flatMap { provider -> provider.models }
                        .associateBy { model -> model.id }
                    _conversationTokenCost.value = computeConversationCost(
                        slices = slices.map { it.toCostSlice() },
                        priceOf = { rawModelId ->
                            rawModelId
                                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                                ?.let { modelsById[it] }
                                ?.let { model ->
                                    ModelPrice(
                                        inputPerMillion = model.inputPricePerMillion,
                                        outputPerMillion = model.outputPricePerMillion,
                                        cachedPerMillion = model.cachedPricePerMillion,
                                        perRequest = model.requestPrice,
                                    )
                                }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 查不到就保留上一次的值，不显示成 0 骗人；错误原文进日志便于定性
                Log.w(TAG, "读取本对话 token 累计失败: ${e.message}")
            }
        }
    }

    init {
        refreshConversationTokenStats()
        // 生成结束（job 变 null）后刷一次：这一趟的工具轮与续跑轮都已计入 cumulativeUsage
        viewModelScope.launch {
            conversationJob.collect { job -> if (job == null) refreshConversationTokenStats() }
        }
        // v300（用户反馈）：原来只在「整趟生成结束」刷一次，一轮长回答要看几分钟前的旧数字。
        // 现在改成「每轮一刷」：触发条件是**用量签名变化** —— 服务商报出用量的那一刻签
        // 名才会变（流式分片期间它不变），所以不会每个 token 都去扫一次聚合 SQL。
        // collectLatest + 延迟 = 合并连续变化，最多晚 TOKEN_STATS_REFRESH_DEBOUNCE_MS 刷新。
        viewModelScope.launch {
            conversation
                .map { conv ->
                    val msgs = conv.currentMessages
                    val lastAssistant = msgs.lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    "${msgs.size}|${lastAssistant?.usage}|${lastAssistant?.cumulativeUsage}"
                }
                .distinctUntilChanged()
                .collectLatest {
                    delay(TOKEN_STATS_REFRESH_DEBOUNCE_MS)
                    refreshConversationTokenStats()
                }
        }
    }

    fun stopAgent(threadId: String) = agentThreadManager.stop(threadId)

    /**
     * v220：关闭单个已结束的子代理线程（从「代理活动」面板移除，AgentDatabase 记录保留）。
     * 运行中的线程不会被关闭 —— manager.close 只接受终态。
     */
    fun closeAgent(threadId: String) {
        viewModelScope.launch {
            agentThreadManager.close(threadId)
        }
    }

    /**
     * v220：一键清空本会话所有**已结束**的子代理线程；运行中的绝不受影响。
     * 这是用户反馈「代理活动关不掉」的正式关闭渠道。
     */
    fun closeFinishedAgents() {
        viewModelScope.launch {
            closableAgentThreadIds(agentThreadManager.list(_conversationId.toString()))
                .forEach { agentThreadManager.close(it) }
        }
    }

    /**
     * 把子代理的结构化报告合并为一条助手消息追加到主聊天（用户主动触发的唯一入口；
     * 只合并摘要，不合并原始过程，避免上下文污染）。
     */
    fun mergeAgentReport(threadId: String) {
        viewModelScope.launch {
            val thread = agentThreadManager.thread(threadId) ?: return@launch
            val report = me.rerere.rikkahub.agent.model.AgentReport.decode(thread.reportJson)
            if (report == null || report.conclusion.isBlank()) return@launch
            val text = buildString {
                appendLine("【子代理报告 · ${thread.role.name.lowercase()}】")
                appendLine("任务：${thread.task}")
                appendLine()
                appendLine("结论：")
                appendLine(report.conclusion)
                if (report.evidence.isNotEmpty()) {
                    appendLine()
                    appendLine("证据：")
                    report.evidence.forEach { appendLine("- $it") }
                }
                if (report.uncertainties.isNotEmpty()) {
                    appendLine()
                    appendLine("不确定项：")
                    report.uncertainties.forEach { appendLine("- $it") }
                }
                if (report.suggestions.isNotEmpty()) {
                    appendLine()
                    appendLine("建议：")
                    report.suggestions.forEach { appendLine("- $it") }
                }
            }
            chatService.updateConversationState(_conversationId) { conversation ->
                conversation.copy(
                    messageNodes = conversation.messageNodes +
                        me.rerere.ai.ui.UIMessage(
                            role = me.rerere.ai.core.MessageRole.ASSISTANT,
                            parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text)),
                        ).toMessageNode()
                )
            }
        }
    }

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 流式输出时会话每个 chunk 都在变，助手和模型只取决于下面这几个字段
    private val configuredConversation = conversation.distinctUntilChanged { old, new ->
        old.assistantId == new.assistantId && old.config == new.config &&
            old.modeInjectionIds == new.modeInjectionIds && old.lorebookIds == new.lorebookIds
    }

    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getAssistantOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    // 当前模型
    val currentChatModel: StateFlow<Model?> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, settings.value.getChatModelOf(conversation.value))

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var oldSettings: Settings? = null
            var newSettings: Settings? = null
            settingsStore.update { old ->
                fn(old).also { new ->
                    oldSettings = old
                    newSettings = new
                }
            }
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings ?: return@launch, newSettings ?: return@launch)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 聊天页对助手的修改：新会话直接改助手，会话开始后只改当前会话
    fun updateAssistant(assistant: Assistant) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { assistant }
        }
    }

    // 新会话开始前归入抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { it.copy(chatModelId = model.id) }
            // v268/v323：对话级模型记忆 —— 切换的瞬间记到当前对话头上（照 Chatbox，不等发送）。
            // 关闭开关时不记录，行为与官方一致。官方 3.1 重写 setChatModel 时把这句覆盖掉了，
            // 结果「切回对话恢复上次模型」只剩恢复、没有记录。
            settingsStore.update { settings ->
                if (!settings.rememberModelPerConversation) {
                    settings
                } else {
                    settings.copy(
                        conversationModelIds = settings.conversationModelIds + (_conversationId to model.id)
                    )
                }
            }
        }
    }

    // 切换搜索方式
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) {
        // v323：3.1 把搜索设置固定到对话上（ConversationConfig），统一走 ChatService
        viewModelScope.launch {
            chatService.updateChatSearch(_conversationId, enableWebSearch, builtInSearch)
        }
    }

    // Update checker (二改版: 已移除在线检查更新)
    val updateState: StateFlow<UiState<UpdateInfo>> = MutableStateFlow(UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun shouldAutoCompressBeforeSend(): Boolean {
        val assistant = settings.value.assistants.firstOrNull { it.id == conversation.value.assistantId }
            ?: settings.value.getCurrentAssistant()
        return chatService.shouldAutoCompressConversation(assistant, conversation.value)
    }

    suspend fun autoCompressForkForSend(): Conversation {
        val assistant = settings.value.assistants.firstOrNull { it.id == conversation.value.assistantId }
            ?: settings.value.getCurrentAssistant()
        return chatService.autoCompressForkConversation(
            _conversationId,
            conversation.value,
            assistant.autoCompressModelSource,
        )
    }

    fun sendMessageToConversation(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)
        chatService.sendMessage(conversationId, content, answer)
    }

    /**
     * 圆桌模式（v207 二改新增）：多个模型用同一份上文并发各出一份方案，
     * [autoSummarize] 为 true 时全部出完后由主模型综合成最后一页。
     */
    fun runRoundTable(content: List<UIMessagePart>, autoSummarize: Boolean) {
        analytics.logEvent("ai_round_table", null)
        chatService.runRoundTable(_conversationId, content, autoSummarize)
    }

    /** 综合已有的多份方案（长按圆桌按钮 → 综合已有的方案） */
    fun summarizeRoundTableNode(nodeId: Uuid) {
        analytics.logEvent("ai_round_table_summarize", null)
        chatService.summarizeRoundTableNode(_conversationId, nodeId)
    }

    /**
     * v213 圆桌座位状态：界面用它显示控制面板。
     * null 表示当前这个对话没有在跑圆桌。
     */
    internal val roundTableRun: StateFlow<RoundTableRunState?> =
        chatService.getRoundTableRunFlow(_conversationId)

    /** 对某一个圆桌位置下指令（停止 / 换模型 / 重试 / 跳过），只影响这一个位置 */
    internal fun controlRoundTableSeat(seatId: String, command: RoundTableSeatCommand): Boolean =
        chatService.controlRoundTableSeat(_conversationId, seatId, command)

    /** 手动决定"疑似无效"的那一页要不要算进最终结论 */
    internal fun setRoundTableSeatIncluded(seatId: String, include: Boolean) {
        chatService.setRoundTableSeatIncluded(_conversationId, seatId, include)
    }

    /** 缺席检查点上的选择：补齐 / 用现有方案继续 / 结束本轮 */
    internal fun resolveRoundTableGap(decision: RoundTableGapDecision): Boolean =
        chatService.resolveRoundTableGap(_conversationId, decision)

    // ---- v313：AI 讨论群组 ----

    /**
     * 当前对话的群讨论状态。null = 这个对话没建群、或没在讨论。
     *
     * 刻意与圆桌的 roundTableRun 并列、互不引用：两者形态不同（流水线 vs 共享时间线），
     * 硬共用一个状态会把两边的判定都搞糊。
     */
    internal val groupRun: StateFlow<GroupRunState?> =
        chatService.getGroupRunFlow(_conversationId)

    /**
     * v316：群方案写操作的回执（存 / 覆盖 / 改名 / 删除）。
     *
     * 为什么不直接用函数返回值：写盘是协程里异步做的，返回值只能代表「校验过了、
     * 任务提交了」，用它报「已存好」会在写失败时说谎（审查位 v316 抓的 P2）。
     * 模式照 v302 的备份传输状态：VM 里跑、界面只显示，读完即清。
     */
    private val _groupPresetOp = MutableStateFlow<GroupPresetOpResult?>(null)
    internal val groupPresetOp: StateFlow<GroupPresetOpResult?> = _groupPresetOp.asStateFlow()

    /** v316：界面报完就清，避免同一个回执被反复弹出 */
    fun consumeGroupPresetOp() {
        _groupPresetOp.value = null
    }

    /** v315：点「开始/继续讨论」——带上用户在弹框里写的「这次想讨论什么」 */
    fun startGroupDiscussion(topic: String? = null) {
        analytics.logEvent("ai_group_discussion", null)
        chatService.startGroupDiscussion(_conversationId, topic)
    }

    /** 停止这场讨论，已经说过的内容全部保留 */
    fun stopGroupDiscussion() {
        viewModelScope.launch { chatService.stopGroupDiscussion(_conversationId) }
    }

    /** 不想回答这次提问：把「未作答」如实回传给提问的成员，让讨论接着走 */
    fun skipGroupQuestion() {
        chatService.skipGroupQuestion(_conversationId)
    }

    /** 保存这个对话的群设置（成员、批次、是否允许提问…） */
    fun saveGroupConfig(config: GroupChatConfig) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    groupChatConfigs = settings.groupChatConfigs + (_conversationId to config)
                )
            }
            chatService.refreshGroupState(_conversationId)
        }
    }

    /**
     * v314：解散这个讨论群。
     *
     * 为什么必须有：建过群之后，用户在这个对话里发的每一条消息都会按「插话」处理
     * （进讨论、不触发助手回复）。没有这个出口，用户就再也回不到普通聊天了 ——
     * 那是功能，不是惩罚。删掉配置后这个对话立刻恢复成普通对话，已经聊过的内容一条不动。
     */
    /**
     * v315：暂停 / 重新启用这个对话的讨论群。
     *
     * **配置一个字节都不动**（成员、批次、续写次数全留着），只是不再把用户输入当插话、
     * 也不让引擎继续跑。用户口径：「解散丢配置也可以，但……」，所以两个动作都给：
     * 想暂时不用就用这个，想彻底清就用 [deleteGroupConfig]。
     */
    fun setGroupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) chatService.stopGroupDiscussion(_conversationId)
            settingsStore.update { settings ->
                val current = settings.groupChatConfigs[_conversationId] ?: return@update settings
                settings.copy(
                    groupChatConfigs = settings.groupChatConfigs +
                        (_conversationId to current.copy(enabled = enabled))
                )
            }
            chatService.clearGroupRunState(_conversationId)
        }
    }

    /**
     * v315：彻底删除这个对话的群设置（成员与次数一起没掉）。
     *
     * 与「暂停」的区别必须在界面上说清楚：这个动作**不可撤销**，
     * 下次要用得重新建群（但可以用「一键套用成员组合」快速重建）。
     */
    fun deleteGroupConfig() {
        viewModelScope.launch {
            // 顺序要紧：先停下（如果正在跑）→ 删配置 → 再把面板状态清掉。
            // 反过来的话会留下一个「按钮还在、点了没反应」的空面板（审查位 v314 抓的 P1）。
            chatService.stopGroupDiscussion(_conversationId)
            settingsStore.update { settings ->
                settings.copy(groupChatConfigs = settings.groupChatConfigs - _conversationId)
            }
            chatService.clearGroupRunState(_conversationId)
        }
    }

    /** 这个对话的群设置；没建过群返回 null */
    /**
     * v319：点名 —— 让指定成员下一个说话（跳过中间排队的其他成员）。
     *
     * 同步调用即可：ChatService 那一层只改状态、必要时开一批，本身不是 suspend。
     */
    fun requestGroupSpeaker(modelId: Uuid): GroupPickOutcome =
        chatService.requestGroupSpeakerNow(_conversationId, modelId)

    fun groupConfig(): GroupChatConfig? =
        settingsStore.settingsFlow.value.groupChatConfigs[_conversationId]

    /**
     * v316：把界面上当前这套存成一个**新方案**（用户口径：「提前设置好几个方案，
     * 然后直接选择方案几来一键配置」）。
     *
     * 审查位 v316 抓的 P2：旧写法返回 Boolean，但 true 只代表「名字合法、任务已提交」，
     * DataStore 真写失败时界面照样报「方案已存好」= 说谎；而"存满 8 条挤掉最旧一条"
     * 是静默丢用户数据。改成两段：
     * - **同步校验**（空名 / 存满 / 重名）：当场给原因，这是用户能改的错误；
     * - **异步回执**（[groupPresetOp]）：写成功才说「已存好」，写失败原样摊出来。
     * 校验交给顶层纯函数，能真单测（`GroupChatTest`）。
     */
    fun requestSaveGroupPreset(name: String, config: GroupChatConfig) {
        val presets = settingsStore.settingsFlow.value.groupChatPresets
        val (cleanName, reason) = validateNewPresetName(name, presets)
        if (cleanName == null) {
            _groupPresetOp.value = GroupPresetOpResult.Rejected(GroupPresetOpKind.SAVE, reason)
            return
        }
        val preset = GroupChatPreset(
            id = Uuid.random(),
            name = cleanName,
            memberModelIds = config.memberModelIds,
            turnsPerRun = config.turnsPerRun,
            resumeMax = config.resumeMax,
        )
        persistPresetOp(GroupPresetOpKind.SAVE, cleanName) { settings ->
            settings.copy(groupChatPresets = settings.groupChatPresets + preset)
        }
    }

    /** v316：用当前这套配置**覆盖**指定方案（成员与次数一起换，名字留着） */
    fun requestOverwriteGroupPreset(presetId: Uuid, config: GroupChatConfig) {
        val target = settingsStore.settingsFlow.value.groupChatPresets.firstOrNull { it.id == presetId }
        if (target == null) {
            _groupPresetOp.value = GroupPresetOpResult.Rejected(
                GroupPresetOpKind.OVERWRITE, "那个方案已经不在了（可能被删了），请重新存一个",
            )
            return
        }
        persistPresetOp(GroupPresetOpKind.OVERWRITE, target.name) { settings ->
            settings.copy(
                groupChatPresets = settings.groupChatPresets.map { preset ->
                    if (preset.id != presetId) preset else preset.copy(
                        memberModelIds = config.memberModelIds,
                        turnsPerRun = config.turnsPerRun,
                        resumeMax = config.resumeMax,
                    )
                }
            )
        }
    }

    /** v316：改方案名字（重名 / 空名当场拒绝并说原因） */
    fun requestRenameGroupPreset(presetId: Uuid, name: String) {
        val presets = settingsStore.settingsFlow.value.groupChatPresets
        val (cleanName, reason) = validatePresetRename(name, presets, presetId)
        if (cleanName == null) {
            _groupPresetOp.value = GroupPresetOpResult.Rejected(GroupPresetOpKind.RENAME, reason)
            return
        }
        persistPresetOp(GroupPresetOpKind.RENAME, cleanName) { settings ->
            settings.copy(
                groupChatPresets = settings.groupChatPresets.map { preset ->
                    if (preset.id == presetId) preset.copy(name = cleanName) else preset
                }
            )
        }
    }

    /** v316：删除方案。只动方案表，不影响任何对话的现有群配置 */
    fun requestDeleteGroupPreset(presetId: Uuid) {
        val target = settingsStore.settingsFlow.value.groupChatPresets.firstOrNull { it.id == presetId }
        if (target == null) {
            _groupPresetOp.value = GroupPresetOpResult.Rejected(
                GroupPresetOpKind.DELETE, "那个方案已经不在了",
            )
            return
        }
        persistPresetOp(GroupPresetOpKind.DELETE, target.name) { settings ->
            // 按 id 逐条比对剔除（不能用 `- presetId`：那是删「元素」不是删「id」，
            // 类型也推不出来，编译器会直接拒绝）
            settings.copy(
                groupChatPresets = settings.groupChatPresets.filterNot { it.id == presetId }
            )
        }
    }

    /**
     * v316：方案写操作的统一落库 + 回执。
     *
     * ⚠️ 取消异常必须原样上抛（手册 §7：runCatching 会吞掉 CancellationException，
     * 用户点停止就停不下来），所以这里用显式 try / catch，且 CancellationException
     * 排在 Exception **之前** —— 顺序反了那个分支永远走不到。
     */
    private fun persistPresetOp(
        kind: GroupPresetOpKind,
        name: String,
        transform: (Settings) -> Settings,
    ) {
        viewModelScope.launch {
            try {
                val before = settingsStore.settingsFlow.value.groupChatPresets
                settingsStore.update(transform)
                val after = settingsStore.settingsFlow.value.groupChatPresets
                // 必须回读比对再报成功：SettingsStore.update(settings) 在设置还是占位的
                // dummy（init = true）时会**只写一行日志就静默 return**，
                // 不抛异常。照旧报「已存好」就是说谎 —— 与审查位 v316 抓的 P2 同一类问题。
                // ⚠️ 残留边界（本轮不扩大改动）：若 persistSettings 自身抛异常，
                // 内存态已经改过而磁盘没改，官方这条链路本来就没有回读磁盘的能力。
                if (before === after) {
                    Log.w(TAG, "群方案写入未生效（设置未初始化或被丢弃）: $kind $name")
                    _groupPresetOp.value = GroupPresetOpResult.Rejected(
                        kind, "设置还没准备好，这条改动没落下去，稍后再试一次",
                    )
                } else {
                    _groupPresetOp.value = GroupPresetOpResult.Done(kind, name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "群方案写入失败: ${e.message}")
                _groupPresetOp.value = GroupPresetOpResult.Rejected(
                    kind, "没存上：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * 最近一组圆桌方案所在的节点；没有则返回 null。
     * 由 ChatService 记录，优先精确匹配圆桌产物，避免误抓普通换模型重新生成的多页消息。
     */
    fun findLatestRoundTableNodeId(): Uuid? = chatService.findLatestRoundTableNodeId(_conversationId)

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        modelSource: CompressionModelSource,
        customPrompt: String,
    ): Job {
        return viewModelScope.launch {
            // 先保存压缩提示词（留空=恢复内置默认；填写=完全覆盖默认），再执行压缩。
            // v268：同时记住用户选择了智能模式，下次打开对话框默认停在智能。
            settingsStore.update { current ->
                current.copy(
                    compressPrompt = customPrompt.trim().ifBlank { DEFAULT_COMPRESS_PROMPT },
                    compressUseClassicMode = false,
                )
            }
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                modelSource,
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
            // v299：压缩会整批替换本对话的消息节点，统计随之重算（智能压缩几乎归零；
            // 经典压缩只保留最近若干条，会留下那几条的用量）。但顶栏那行只在
            // 「打开对话」与「这趟生成结束」两个时点刷新 —— 不主动刷一次，
            // 用户看到的还是压缩前那个数，像是「压缩后没重新计算、还在叠加」。
            refreshConversationTokenStats()
        }
    }

    /**
     * v268：「经典压缩」入口 —— 官方旧版压缩流程（自选目标 token 数与保留消息条数）。
     * 与智能压缩并存，不读设置里的压缩提示词（那是 Codex 版，占位符不同）。
     */
    fun handleCompressContextClassic(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Job {
        return viewModelScope.launch {
            // v268：记住用户选择了经典模式，下次打开对话框默认停在经典。
            settingsStore.update { current ->
                current.copy(compressUseClassicMode = true)
            }
            chatService.compressConversationClassic(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages,
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
            // v299：压缩会整批替换本对话的消息节点，统计随之重算（智能压缩几乎归零；
            // 经典压缩只保留最近若干条，会留下那几条的用量）。但顶栏那行只在
            // 「打开对话」与「这趟生成结束」两个时点刷新 —— 不主动刷一次，
            // 用户看到的还是压缩前那个数，像是「压缩后没重新计算、还在叠加」。
            refreshConversationTokenStats()
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(message: UIMessage) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        // v313：群里成员提问时，必须由**提问的那位成员**接着说。
        // 主对话那条路固定用「助手当前模型」续跑，照抄会变成 A 提问、B 回答。
        // answerGroupQuestion 只在「群引擎确实停在等回答」时返回 true，
        // 普通对话与圆桌永远走下面那句 —— 主对话行为一个字节都没变。
        if (chatService.answerGroupQuestion(_conversationId, toolCallId, answer)) return
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
            // v318：对话没了就要停掉它的讨论群与状态转发订阅。
            // v317 之前这里只是两张表里留个无用条目；v318 加了订阅之后不取消就是
            // 一个永远挂着的协程（审查位 v318 抓的 P2，因本轮改动升级为必修）。
            chatService.releaseGroupStateOnDelete(conversation.id)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            // v306 合并上游 2.5.3：置顶改走 ChatService（会话级元数据更新，只更新对应列）
            chatService.toggleConversationPinned(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            // v306 合并上游 2.5.3：移动改走 ChatService.moveConversationToAssistant
            // （内部用 session.updateMetadata 只更新 assistantId/folder_id 两列，
            //  避免整对象保存把正在流式输出的消息覆盖回旧快照；原「手动查库+整对象保存」写法已由官方取代）
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.selectAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            chatService.generateTitle(conversation.id, conversation, force)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        viewModelScope.launch {
            chatService.clearTranslationField(_conversationId, messageId)
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            try {
                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
            } catch (e: ApiException) {
                // 界面上的分支已经过时（比如刚被清理掉），切换不了就算了
                Log.w(TAG, "selectMessageNode: ${e.message}")
            }
        }
    }

    /** v323：页面上的整份会话更新（内部走 ChatService 的会话状态通道）。 */
    fun updateConversation(conversation: Conversation) {
        chatService.updateConversationState(_conversationId) { conversation }
    }

    /** v323：页面用的异步落盘。 */
    fun saveConversationAsync() {
        viewModelScope.launch { chatService.saveConversation(_conversationId, conversation.value) }
    }

    fun updateConversationSystemPrompt(systemPrompt: String?) {
        viewModelScope.launch {
            chatService.updateConversationSystemPrompt(_conversationId, systemPrompt)
        }
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            chatService.updateWorkspaceCwd(_conversationId, workspaceCwd)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.setNodeFavorite(_conversationId, node.id, favorite = !currentlyFavorited)
        }
    }

}

/** v301：数据库那一行的「按模型分组用量」→ 算钱用的纯数据结构。 */
private fun ModelTokenStats.toCostSlice(): ModelUsageSlice = ModelUsageSlice(
    modelId = modelId,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    cachedTokens = cachedTokens,
    messageCount = messageCount,
    requestCount = requestCount,
)
