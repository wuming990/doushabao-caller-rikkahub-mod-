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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.db.dao.ConversationTokenStats
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getConversationTokenStats
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
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.CompressionModelSource
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.RoundTableGapDecision
import me.rerere.rikkahub.service.RoundTableRunState
import me.rerere.rikkahub.service.RoundTableSeatCommand
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val agentThreadManager: me.rerere.rikkahub.agent.runtime.AgentThreadManager,
    // v297：本对话 token 累计（对话级聚合查询，不动数据库版本）
    private val messageNodeDAO: MessageNodeDAO,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    init {
        // v268：对话级模型记忆（照 Chatbox 设计）—— 打开/切回对话时恢复它最后一次使用的模型。
        // 写回助手默认值是刻意的：全局「当前模型」跟随正在查看的对话（= 最近使用），
        // 新对话因此沿用最近使用的模型；已存在的其它对话不受影响（它们各自有自己的记忆）。
        // v268 审查修复：直接查库取对话真实所属助手，不读内存态 conversation.value——
        // VM 创建瞬间那还是占位对话（assistantId = 当时的当前助手），跨助手打开时会把
        // 记忆模型写错到别的助手头上。生成用的就是对话所属助手的模型，恢复只写它。
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
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

    fun refreshConversationTokenStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _conversationTokenStats.value =
                    messageNodeDAO.getConversationTokenStats(_conversationId.toString())
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

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

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
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
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

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val updated = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
                // v268：对话级模型记忆 —— 切换的瞬间记到当前对话头上（照 Chatbox，不等发送）。
                // 关闭开关时不记录，行为与官方一致。
                if (updated.rememberModelPerConversation) {
                    updated.copy(
                        conversationModelIds = updated.conversationModelIds + (_conversationId to model.id)
                    )
                } else {
                    updated
                }
            }
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

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
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
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
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

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
