package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CodexCompaction
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEGACY_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.RoundTableMaterial
import me.rerere.rikkahub.data.ai.prompts.RoundTableProposal
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import me.rerere.rikkahub.data.ai.prompts.ROUND_TABLE_CONTINUE_INSTRUCTION
import me.rerere.rikkahub.data.ai.prompts.buildRoundTableStageMessage
import me.rerere.rikkahub.data.ai.prompts.buildCompactionPrompt
import me.rerere.rikkahub.data.ai.prompts.buildRoundTableSummaryMessage
import me.rerere.rikkahub.data.ai.scopedCustomBodies
import me.rerere.rikkahub.data.ai.stripResumeDuplication
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createRoundTableReadOnlyWorkspaceTools
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.tools.createAgentControlTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEvent.GenerationEndReason
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.AutoCompressModelOverride
import me.rerere.rikkahub.data.datastore.MAX_ROUND_TABLE_MEMBERS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutoCompressModelSource
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val GENERATION_CHECKPOINT_INTERVAL_MS = 1_000L

/** 圆桌各阶段允许使用的唯一"读文件"工具；写/改/执行/发布不会被构造出来 */
private const val ROUND_TABLE_READ_FILE_TOOL = "workspace_read_file"

/** v209: 单个阶段遇到网络/服务端故障时自动重来的次数（不含首次） */
private const val ROUND_TABLE_STAGE_RETRIES = 1

/** 重试前的等待，给临时性网络故障一点恢复时间 */
private const val ROUND_TABLE_RETRY_DELAY_MS = 1_500L

/**
 * v209: 少于这个份数就不进入最后的拍板阶段。
 * 只有 1 份材料时，拍板只是把同一段内容重说一遍，而那是整轮最贵的一次调用。
 */
private const val MIN_ROUND_TABLE_SUMMARY_PROPOSALS = 2


internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/** Codex compaction 继承当前助手思考档位；摘要输出不设置应用层 maxTokens。 */
internal fun compressionTextGenerationParams(
    model: Model,
    assistant: Assistant,
): TextGenerationParams = backgroundTextGenerationParams(
    model = model,
    reasoningLevel = assistant.reasoningLevel,
).copy(
    maxTokens = null,
)

/** 自动压缩阈值是直接触发值，不再进行 90% 换算。 */
internal fun shouldAutoCompressAtTokenCount(
    assistant: Assistant,
    estimatedTokens: Long,
    triggerTokens: Int = assistant.autoCompressTriggerTokens,
): Boolean = assistant.enableAutoCompress &&
    triggerTokens > 0 &&
    estimatedTokens >= triggerTokens.toLong()

/**
 * 解析当前该用哪个触发值。
 *
 * 规则：当前聊天模型如果被列进「特殊模型」表且值有效，就用它自己的值；
 * 否则回退到所在助手的自定义触发值。这样只需要给上下文特别大或特别小的那几个模型单独设一次。
 *
 * @param chatModelId 当前对话实际使用的聊天模型（不是压缩用的模型）
 */
internal fun resolveAutoCompressTriggerTokens(
    assistant: Assistant,
    chatModelId: Uuid?,
    overrides: List<AutoCompressModelOverride>,
): Int {
    val override = chatModelId?.let { id ->
        overrides.firstOrNull { it.modelId == id && it.triggerTokens > 0 }
    }
    return override?.triggerTokens ?: assistant.autoCompressTriggerTokens
}

/**
 * 服务商返回的真实 token 用量: 取最近一条带用量的消息的 prompt + completion。
 * 没有任何真实用量时返回 null。
 */
internal fun reportedConversationTokens(conversation: Conversation): Long? =
    conversation.currentMessages.asReversed()
        .firstNotNullOfOrNull { message ->
            message.usage?.let { usage ->
                val total = usage.promptTokens + usage.completionTokens
                if (total > 0) total.toLong() else null
            }
        }
        ?.takeIf { it > 0L }

/** 本地估算 token: 按 Codex 的 approx_token_count，UTF-8 字节数 / 4 (向上取整)。 */
internal fun approxConversationTokens(conversation: Conversation): Long =
    conversation.currentMessages.sumOf {
        CodexCompaction.approxTokenCount(it.summaryAsText(maxLength = Int.MAX_VALUE)).toLong()
    }

/**
 * 估算当前对话占用的 token 数。优先真实用量，否则本地估算。
 * 自动压缩判定与界面常显的「上下文占用」共用同一口径，保证界面数字到达触发值时确实会压缩。
 */
internal fun estimateConversationTokensOf(conversation: Conversation): Long =
    reportedConversationTokens(conversation) ?: approxConversationTokens(conversation)

/**
 * 输入框上方常显的「上下文占用」信息。
 *
 * @param usedTokens 当前对话已占用的 token 数
 * @param limitTokens 分母。取自助手的自动压缩触发值; 自动压缩关闭时为 0，表示没有参照上限
 * @param isEstimated true 表示这个数字是本地估算（尚无服务商真实用量），界面需要标注「约」
 */
data class ContextUsageInfo(
    val usedTokens: Long,
    val limitTokens: Int,
    val isEstimated: Boolean,
) {
    /** 是否有可用的参照上限（即自动压缩已开启且触发值有效）。 */
    val hasLimit: Boolean get() = limitTokens > 0

    /** 进度条比例，钳制在 0..1。 */
    val fraction: Float
        get() = if (hasLimit) {
            (usedTokens.toDouble() / limitTokens.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    /** 百分比，可以超过 100（已越过触发值但还没来得及压缩时）。 */
    val percent: Int
        get() = if (hasLimit) {
            (usedTokens * 100 / limitTokens).coerceAtMost(999L).toInt()
        } else {
            0
        }

    /** 接近上限（>= 80%），界面用提醒色。 */
    val nearLimit: Boolean get() = hasLimit && percent >= 80

    /** 已达到或超过触发值，界面用警示色。 */
    val overLimit: Boolean get() = hasLimit && usedTokens >= limitTokens.toLong()
}

/**
 * 组装当前上下文占用信息。纯函数，便于单元测试。
 * 分母只在自动压缩开启时才给出，避免在压缩关闭时显示一个不会发生任何事情的「上限」。
 *
 * @param triggerTokens 已经按「特殊模型」规则解析过的触发值，见 [resolveAutoCompressTriggerTokens]
 * @param reportedTokens 服务商返回的真实用量，没有则传 null
 * @param approxTokens 本地估算值，只在没有真实用量时使用
 */
internal fun buildContextUsageInfo(
    assistant: Assistant,
    triggerTokens: Int,
    reportedTokens: Long?,
    approxTokens: Long,
): ContextUsageInfo = ContextUsageInfo(
    usedTokens = reportedTokens ?: approxTokens,
    limitTokens = if (assistant.enableAutoCompress) {
        triggerTokens.coerceAtLeast(0)
    } else {
        0
    },
    isEstimated = reportedTokens == null,
)

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

enum class CompressionModelSource {
    CURRENT_CHAT,
    FIXED,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    // v2.5.0 合并保留：官方把它们收进 ChatToolFactory 了，但圆桌与子代理的
    // 工具组装（localTools.getTools / skillManager.listSkills）仍在使用这两个依赖。
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val agentThreadManager: me.rerere.rikkahub.agent.runtime.AgentThreadManager,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { id, cause ->
                    val session = sessions[id]
                    if (cause != null) session?.messageQueue?.pause()
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
                    }
                    appScope.launch { dispatchNextQueuedMessage(id) }
                },
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    // 2.4.13：后台生成保活。生成仍归 ChatService 管，这里只借前台服务的生命周期，
    // 让切到后台后流式生成不被系统杀进程打断。
    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.initializeOnce {
            // 生成已经开始时，内存态比数据库新，绝不能用旧快照覆盖。
            if (session.isGenerating) {
                Log.i(TAG, "initializeConversation: keep active in-memory state for $conversationId")
                return@initializeOnce
            }
            // v229 硬保护：内存里只要已经有消息，就绝不允许用「空的新对话」覆盖。
            // 旧版只看 isGenerating 这个瞬时状态，判断落空就会把刚写进去的用户消息冲掉。
            if (session.state.value.messageNodes.isNotEmpty()) {
                Log.i(TAG, "initializeConversation: memory already has messages, skip for $conversationId")
                return@initializeOnce
            }
            val conversation = conversationRepo.getConversationById(conversationId)
            if (session.isGenerating) {
                Log.i(TAG, "initializeConversation: generation started while loading $conversationId; keep memory")
                return@initializeOnce
            }
            if (session.state.value.messageNodes.isNotEmpty()) {
                Log.i(TAG, "initializeConversation: messages appeared while loading $conversationId; keep memory")
                return@initializeOnce
            }
            if (conversation != null) {
                updateConversation(conversationId, conversation)
                settingsStore.updateAssistant(conversation.assistantId)
            } else {
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                val newConversation = Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(assistant.presetMessages)
                updateConversation(conversationId, newConversation)
            }
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 圆桌模式 (v208: 主导式流程，照抄「单 AI 主导模式」的角色分工) ----

    /**
     * 页序槽位：数字小的排在前面。
     * 并发阶段谁先跑完不影响翻页顺序，避免同一组模型每次跑出来页序都不一样。
     */
    private object RoundTableSlot {
        const val CONTRACT = 0
        const val MEMBER_BASE = 100
        const val MAIN_DRAFT = 500
        const val REBUTTAL = 600

        /** v213：缺席检查点说明页，排在最终拍板之前 */
        const val GAP = 650
        const val SUMMARY = 700
    }

    /**
     * 本进程内记录哪些 node 是圆桌产物。
     * 「综合已有方案」据此精确定位，不会误抓用户普通换模型重新生成产生的多页消息。
     * 进程重启后记录会丢，此时退化为「最后一个多页助手节点」。
     */
    private val roundTableNodes = ConcurrentHashMap<Uuid, MutableSet<Uuid>>()

    private fun markRoundTableNode(conversationId: Uuid, nodeId: Uuid) {
        roundTableNodes
            .getOrPut(conversationId) { ConcurrentHashMap.newKeySet<Uuid>() }
            .add(nodeId)
    }

    fun findLatestRoundTableNodeId(conversationId: Uuid): Uuid? {
        val conversation = getConversationFlow(conversationId).value
        val known: Set<Uuid> = roundTableNodes[conversationId] ?: emptySet()
        conversation.messageNodes.lastOrNull { it.id in known }?.let { return it.id }
        return conversation.messageNodes.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.messages.size > 1
        }?.id
    }

    /** 一次圆桌运行期间共享的上下文；原始 Conversation 只读，不被改写 */
    private class RoundTableContext(
        val conversationId: Uuid,
        val nodeId: Uuid,
        val settings: Settings,
        val assistant: Assistant,
        val conversation: Conversation,
        val baseMessages: List<UIMessage>,
        val tools: List<Tool>,
        val mainModel: Model,
        val writeMutex: Mutex,
        val slots: MutableMap<Uuid, Int>,
    )

    /**
     * 各阶段共用的助手副本。
     *
     * 只读操作（联网搜索、列目录、找文件、搜内容、读文件、查历史对话、时间）在并发下互不影响，
     * 每个角色都完整保留，不因为"它只是反驳员"就削弱检索能力。
     * 会改变东西的操作一律关掉：记忆写入、MCP（外部服务不可控）、技能（会引导模型动手）、
     * 剪贴板 / 朗读 / 向用户追问（多个模型同时用会互相打架）。
     */
    private fun asProposalOnlyAssistant(
        assistant: Assistant,
        allowReadOnlyTools: Boolean,
    ): Assistant = assistant.copy(
        enableMemory = false,
        mcpServers = emptySet(),
        enabledSkills = emptySet(),
        localTools = if (allowReadOnlyTools) {
            assistant.localTools.filter { it == LocalToolOption.TimeInfo }
        } else {
            emptyList()
        },
        enableWebSearch = allowReadOnlyTools && assistant.enableWebSearch,
        enableRecentChatsReference = allowReadOnlyTools && assistant.enableRecentChatsReference,
    )

    /**
     * 圆桌各阶段可用的工具集：只读检索，全角色相同。
     * 写入 / 修改 / 删除 / 执行 / 发布在这里根本不构造，不依赖提示词自律。
     */
    private suspend fun buildRoundTableTools(
        settings: Settings,
        assistant: Assistant,
        conversation: Conversation,
        allowReadOnlyTools: Boolean,
    ): List<Tool> {
        if (!allowReadOnlyTools) return emptyList()
        return buildList {
            if (assistant.enableWebSearch) addAll(createSearchTools(settings))
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepo, assistant.id))
            }
            val workspaceId = assistant.workspaceId?.toString()
            // 列目录 / 按文件名找 / 搜内容（不需要 rootfs 安装）
            addAll(createRoundTableReadOnlyWorkspaceTools(workspaceId, workspaceRepository))
            // 读文件复用官方实现（支持 bind mount 与 rootfs 内部路径），只取只读那一个
            addAll(
                createWorkspaceToolsIfReady(workspaceId, conversation.workspaceCwd)
                    .filter { it.name == ROUND_TABLE_READ_FILE_TOOL }
            )
        }
    }

    private fun roundTableInputTransformers(tools: List<Tool>) = buildList {
        addAll(inputTransformers)
        add(templateTransformer)
        // 有任意工作区工具时才注入工作区提示，否则模型会去调用不存在的工具
        if (tools.any { it.name.startsWith("workspace_") }) add(workspaceReminderTransformer)
    }

    private fun roundTableRoleLabel(role: RoundTableRole): String = context.getString(
        when (role) {
            RoundTableRole.CONTRACT -> R.string.round_table_role_contract
            RoundTableRole.MAIN_DRAFT -> R.string.round_table_role_main_draft
            RoundTableRole.EXPLORATION -> R.string.round_table_role_exploration
            RoundTableRole.REBUTTAL -> R.string.round_table_role_rebuttal
            RoundTableRole.FINAL -> R.string.round_table_role_final
        }
    )

    /**
     * 跑一个阶段。整个生成过程只在本协程的内存里进行，中途不碰共享的 Conversation 状态，
     * 因此并发阶段的多个模型可以真正同时跑而不互相覆盖（等价于各开一个分支的隔离效果，
     * 但不建临时对话、不改数据库）。
     *
     * @param materials 本阶段临时附加材料；不会写回对话，原始聊天记录保持不变
     */
    private suspend fun generateRoundTableProposal(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        conversation: Conversation,
        baseMessages: List<UIMessage>,
        tools: List<Tool>,
        role: RoundTableRole,
        materials: List<RoundTableMaterial>,
        continueFrom: String? = null,
        stableMessageId: Uuid? = null,
        onProgress: (Int) -> Unit,
        onStream: suspend (UIMessage) -> Unit = {},
    ): UIMessage {
        var lastMessage: UIMessage? = null
        val stageMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(buildRoundTableStageMessage(role, materials))),
        )
        // v223 续跑：把这个位置已经产出的内容作为上文回灌，并明确要求接着写。
        // 不这样做的话「继续」只会让模型从头重写一遍，钱花两次、内容还重复。
        val continuationMessages = if (continueFrom.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(continueFrom)),
                ),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(ROUND_TABLE_CONTINUE_INSTRUCTION)),
                ),
            )
        }
        generationLoop.generateText(
            settings = settings,
            model = model,
            processingStatus = MutableStateFlow(null),
            // 原始上文 + 本阶段临时说明（+ 续跑上文）；临时部分不入库
            messages = baseMessages + stageMessage + continuationMessages,
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            conversationModeInjectionIds = conversation.modeInjectionIds,
            conversationLorebookIds = conversation.lorebookIds,
            workspaceCwd = conversation.workspaceCwd,
            memories = emptyList(),
            inputTransformers = roundTableInputTransformers(tools),
            outputTransformers = outputTransformers,
            tools = tools,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    chunk.messages.lastOrNull()?.let { last ->
                        // 续跑时把已产出内容与新增内容拼成一份完整产出，
                        // 这样字数判定、拍板材料、页面显示都是完整的
                        val merged = mergeRoundTableContinuation(
                            prefix = continueFrom,
                            message = last.copy(modelId = model.id),
                        ).let { message ->
                            stableMessageId?.let { message.copy(id = it) } ?: message
                        }
                        lastMessage = merged
                        // v276：续跑字数只报本次新增，不要把旧前缀算成新模型产出
                        onProgress(
                            roundTableContinuationDelta(continueFrom, merged.toText())
                        )
                        onStream(merged)
                    }
                }
            }
        }
        return lastMessage
            ?: error(context.getString(R.string.round_table_error_empty))
    }

    /**
     * v223：把续跑产出与之前已经写好的内容拼成一份完整产出。
     *
     * 只拼接，不加任何过渡语；用户在面板上通过「已续跑 N 次」知道这一页是接着写的。
     * 注意用 copy 保留原消息的 id 与 finishReason —— 前者保证覆盖同一页而不是新增一页，
     * 后者是截断识别的依据。
     *
     * v272：拼接前先做程序级去重 —— 模型无视「从中断处接着写」、把旧内容从头复述
     * 一遍时（真机实锤），剪掉新增内容开头逐字复述旧前缀的部分，否则用户看到的
     * 就是「续跑=推倒重来」。主对话 v268 起就有这道保护，圆桌此前没有。
     */
    private fun mergeRoundTableContinuation(prefix: String?, message: UIMessage): UIMessage {
        if (prefix.isNullOrBlank()) return message
        val deduped = stripRoundTableContinuationDuplication(message, prefix.trim())
        return deduped.copy(
            parts = listOf(UIMessagePart.Text(prefix.trimEnd() + "\n")) + deduped.parts,
        )
    }

    /**
     * v272：圆桌续跑的去重入口（复用主对话的 [stripResumeDuplication]，含 60 字
     * 逐字阈值与「从头重写 / 复述结尾」双向匹配，短内容与巧合重合不会被误剪）。
     * 命中时把新增内容里的全部 Text part 折叠成去重后的一份；思考等非 Text part 原位保留。
     */
    private fun stripRoundTableContinuationDuplication(
        message: UIMessage,
        prefix: String,
    ): UIMessage {
        if (prefix.isBlank()) return message
        val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
        if (textParts.isEmpty()) return message
        val newText = textParts.joinToString("") { it.text }
        val dedupedText = stripResumeDuplication(prefix, newText)
        if (dedupedText == newText) return message
        val rebuilt = mutableListOf<UIMessagePart>()
        var textPlaced = false
        message.parts.forEach { part ->
            if (part is UIMessagePart.Text) {
                if (!textPlaced) {
                    rebuilt.add(UIMessagePart.Text(dedupedText))
                    textPlaced = true
                }
            } else {
                rebuilt.add(part)
            }
        }
        return message.copy(parts = rebuilt)
    }

    /**
     * 把一页写进指定 node（不存在则新建）。读-改-写整体在 mutex 内串行，
     * 保证多个模型同时落笔时不会互相覆盖；页内顺序按 slot 稳定排序。
     */
    private suspend fun writeRoundTablePage(
        ctx: RoundTableContext,
        message: UIMessage,
        slot: Int,
        select: Boolean,
        persist: Boolean,
    ) = ctx.writeMutex.withLock {
        ctx.slots[message.id] = slot
        val conversation = getConversationFlow(ctx.conversationId).value
        val nodes = conversation.messageNodes.toMutableList()
        val nodeIndex = nodes.indexOfFirst { it.id == ctx.nodeId }
        if (nodeIndex < 0) {
            nodes.add(MessageNode(id = ctx.nodeId, messages = listOf(message), selectIndex = 0))
        } else {
            val node = nodes[nodeIndex]
            val keepSelectedId = node.messages.getOrNull(node.selectIndex)?.id
            val existingIndex = node.messages.indexOfFirst { it.id == message.id }
            val merged = if (existingIndex >= 0) {
                node.messages.toMutableList().also { it[existingIndex] = message }
            } else {
                node.messages + message
            }
            val sorted = merged.sortedBy { ctx.slots[it.id] ?: Int.MAX_VALUE }
            // select=false 时保持用户当前正在看的那一页，不把焦点抢走
            val targetId = if (select) message.id else keepSelectedId
            val newSelectIndex = sorted.indexOfFirst { it.id == targetId }.let { if (it >= 0) it else 0 }
            nodes[nodeIndex] = node.copy(messages = sorted, selectIndex = newSelectIndex)
        }
        val updated = conversation.copy(messageNodes = nodes, updateAt = Instant.now())
        if (persist) {
            saveConversation(ctx.conversationId, updated)
        } else {
            updateConversation(ctx.conversationId, updated)
        }
    }

    /** v209：删掉某个槽位已经写下的页（用于拍板重试前清掉上一次没写完的残页） */
    private suspend fun removeRoundTablePages(ctx: RoundTableContext, slot: Int) =
        ctx.writeMutex.withLock {
            val ids = ctx.slots.filterValues { it == slot }.keys.toSet()
            if (ids.isEmpty()) return@withLock
            val conversation = getConversationFlow(ctx.conversationId).value
            val nodes = conversation.messageNodes.toMutableList()
            val nodeIndex = nodes.indexOfFirst { it.id == ctx.nodeId }
            if (nodeIndex < 0) return@withLock
            val node = nodes[nodeIndex]
            val kept = node.messages.filterNot { it.id in ids }
            // 有其它消息保留：只删掉属于这个槽位的页（保持原行为）
            if (kept.isNotEmpty() && kept.size != node.messages.size) {
                val keepSelectedId = node.messages.getOrNull(node.selectIndex)?.id
                val newSelectIndex = kept.indexOfFirst { it.id == keepSelectedId }
                    .let { if (it >= 0) it else kept.lastIndex }
                nodes[nodeIndex] = node.copy(messages = kept, selectIndex = newSelectIndex)
            } else if (kept.isEmpty()) {
                // v276：整个圆桌节点里的页都被删空时，删除整节点而不是留下空节点。
                // 只有这个节点全是待删页时才会走到这（contains其它消息时 kept 非空），
                // 因此不会误删包含其它消息的节点。
                nodes.removeAt(nodeIndex)
            } else {
                // kept.size == node.messages.size：没有匹配的页，什么都不用改
                return@withLock
            }
            ids.forEach { ctx.slots.remove(it) }
            saveConversation(
                ctx.conversationId,
                conversation.copy(messageNodes = nodes, updateAt = Instant.now()),
            )
        }

    // ---- v224：圆桌位置的「独立对话」----

    /**
     * v224：每个圆桌位置对应一条**真实对话**。
     *
     * 用户原话：「我想要的是点击『查看过程』可以直接进入那个对话观看过程，不要这种」。
     *
     * v223 的做法是自绘一个只读页面去读主对话里的那一页，看着就不像对话；
     * 现在改成位置开跑时真的建一条 Conversation，模型的思考 / 工具调用 / 正文
     * 实时写进去，界面只负责跳转到普通聊天页（[Screen.Chat]），
     * 于是气泡、思考折叠、工具卡片、Markdown、自动滚动、长按复制全部是原生的。
     *
     * 这些过程对话统一归到助手下的「圆桌过程」文件夹：抽屉里的对话列表只列
     * 未归类会话（`getUnfiledConversationsOfAssistantPaging`），所以不会挤占
     * 用户平时的对话列表，但随时能点进去，圆桌结束后也能回头翻。
     */
    private val roundTableFolderMutex = Mutex()

    private suspend fun ensureRoundTableProcessFolder(assistantId: Uuid): Uuid? =
        roundTableFolderMutex.withLock {
            runCatching {
                val name = context.getString(R.string.round_table_seat_folder_name)
                folderRepository.getFoldersOfAssistant(assistantId).first()
                    .firstOrNull { it.name == name }
                    ?.id
                    ?: folderRepository.createFolder(assistantId, name).id
            }.onFailure {
                Log.w(TAG, "ensureRoundTableProcessFolder failed", it)
            }.getOrNull()
        }

    /** 为某个位置新建过程对话；建不出来也不能影响圆桌本身，失败返回 null */
    private suspend fun createRoundTableSeatConversation(
        ctx: RoundTableContext,
        seat: RoundTableSeat,
    ): Uuid? = runCatching {
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = ctx.assistant.id,
            title = context.getString(
                R.string.round_table_seat_chat_title,
                roundTableSeatLabel(seat),
                seat.modelName.ifBlank { "-" },
            ),
            messageNodes = emptyList(),
            folderId = ensureRoundTableProcessFolder(ctx.assistant.id),
            workspaceCwd = ctx.conversation.workspaceCwd,
        )
        conversationRepo.insertConversation(conversation)
        conversation.id
    }.onFailure {
        Log.w(TAG, "createRoundTableSeatConversation failed", it)
    }.getOrNull()

    /**
     * 一轮「提问 + 回答」在过程对话里占的两个节点。
     *
     * 每次尝试（首跑 / 重试 / 换模型 / 续跑，都会让 attempt +1）都是新的一轮，
     * 于是过程对话看起来就是一问一答往下接，用户能看清「我喊过继续」这件事。
     */
    private class RoundTableSeatTurn(
        val userNodeId: Uuid = Uuid.random(),
        val userMessageId: Uuid = Uuid.random(),
        val assistantNodeId: Uuid = Uuid.random(),
    )

    /**
     * 把一轮内容写进位置自己的过程对话（幂等：同一轮反复调用是原地更新）。
     *
     * 走 [saveConversation] 而不是只更新内存，是因为用户可能正开着那个对话页看，
     * 只有真落库 + 刷内存流，页面才会边生成边刷新。
     */
    private suspend fun mirrorRoundTableSeatTurn(
        conversationId: Uuid,
        turn: RoundTableSeatTurn,
        userText: String?,
        assistantMessage: UIMessage?,
    ) {
        runCatching {
            val current = conversationRepo.getConversationById(conversationId)
                ?: getConversationFlow(conversationId).value
            val nodes = current.messageNodes.toMutableList()
            if (userText != null) {
                val userNode = MessageNode(
                    id = turn.userNodeId,
                    messages = listOf(
                        UIMessage(
                            id = turn.userMessageId,
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(userText)),
                        )
                    ),
                    selectIndex = 0,
                )
                val index = nodes.indexOfFirst { it.id == turn.userNodeId }
                if (index >= 0) nodes[index] = userNode else nodes.add(userNode)
            }
            if (assistantMessage != null) {
                val assistantNode = MessageNode(
                    id = turn.assistantNodeId,
                    messages = listOf(assistantMessage),
                    selectIndex = 0,
                )
                val index = nodes.indexOfFirst { it.id == turn.assistantNodeId }
                if (index >= 0) nodes[index] = assistantNode else nodes.add(assistantNode)
            }
            saveConversation(
                conversationId,
                current.copy(messageNodes = nodes, updateAt = Instant.now()),
            )
        }.onFailure {
            Log.w(TAG, "mirrorRoundTableSeatTurn failed", it)
        }
    }

    /** 往过程对话追加一条独立的助手说明（超时、被停止、模型缺失等），单独占一个节点 */
    private suspend fun appendRoundTableSeatNote(
        conversationId: Uuid,
        text: String,
        modelId: Uuid?,
    ) {
        if (text.isBlank()) return
        runCatching {
            val current = conversationRepo.getConversationById(conversationId)
                ?: getConversationFlow(conversationId).value
            val note = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(text)),
                modelId = modelId,
            )
            saveConversation(
                conversationId,
                current.copy(
                    messageNodes = current.messageNodes + note.toMessageNode(),
                    updateAt = Instant.now(),
                ),
            )
        }.onFailure {
            Log.w(TAG, "appendRoundTableSeatNote failed", it)
        }
    }

    /**
     * 主模型最终拍板。结果作为最后一页；只在第一次出现时选中它，
     * 之后的流式更新不再抢焦点，用户可以随时翻回去看前面的方案。
     *
     * v213：拍板模型改为传入（座位上的模型可以被换掉），并把进度回报出去供看门狗判活。
     */
    private suspend fun summarizeRoundTable(
        ctx: RoundTableContext,
        proposals: List<RoundTableProposal>,
        rebuttal: String,
        sharedMaterials: List<RoundTableMaterial>,
        summaryPrompt: String,
        model: Model = ctx.mainModel,
        continueFrom: String? = null,
        stableMessageId: Uuid? = null,
        onActivity: (Int) -> Unit = {},
        onSnapshot: suspend (UIMessage) -> Unit = {},
    ): UIMessage? {
        val summaryMessages = ctx.baseMessages + UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    buildRoundTableSummaryMessage(
                        basePrompt = summaryPrompt,
                        proposals = proposals,
                        rebuttal = rebuttal,
                        sharedMaterials = sharedMaterials,
                    )
                )
            ),
        )
        // v223 续跑拼接（与 generateRoundTableProposal 一致）
        val continuationMessages = if (continueFrom.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(continueFrom)),
                ),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(ROUND_TABLE_CONTINUE_INSTRUCTION)),
                ),
            )
        }
        var lastCheckpointAt = 0L
        var firstWrite = true
        var summaryMessage: UIMessage? = null
        generationLoop.generateText(
            settings = ctx.settings,
            model = model,
            processingStatus = MutableStateFlow(null),
            messages = summaryMessages + continuationMessages,
            assistant = ctx.assistant,
            conversationSystemPrompt = ctx.conversation.customSystemPrompt,
            conversationModeInjectionIds = ctx.conversation.modeInjectionIds,
            conversationLorebookIds = ctx.conversation.lorebookIds,
            workspaceCwd = ctx.conversation.workspaceCwd,
            memories = emptyList(),
            inputTransformers = roundTableInputTransformers(ctx.tools),
            outputTransformers = outputTransformers,
            tools = ctx.tools,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    chunk.messages.lastOrNull()?.let { last ->
                        val merged = mergeRoundTableContinuation(
                            prefix = continueFrom,
                            message = last.copy(modelId = model.id),
                        ).let { message ->
                            stableMessageId?.let { message.copy(id = it) } ?: message
                        }
                        summaryMessage = merged
                        // v276：续跑字数只报本次新增，不要把旧前缀算成新模型产出
                        onActivity(
                            roundTableContinuationDelta(continueFrom, merged.toText())
                        )
                        onSnapshot(merged)
                        val now = SystemClock.elapsedRealtime()
                        val persist = now - lastCheckpointAt >= GENERATION_CHECKPOINT_INTERVAL_MS
                        if (persist) lastCheckpointAt = now
                        writeRoundTablePage(
                            ctx = ctx,
                            message = merged,
                            slot = RoundTableSlot.SUMMARY,
                            select = firstWrite,
                            persist = persist,
                        )
                        firstWrite = false
                    }
                }
            }
        }
        val finished = summaryMessage?.finishReasoning()
        if (finished != null) {
            withContext(NonCancellable) {
                writeRoundTablePage(
                    ctx = ctx,
                    message = finished,
                    slot = RoundTableSlot.SUMMARY,
                    select = false,
                    persist = true,
                )
            }
        }
        return finished
    }

    // ---- v213：圆桌座位运行时（每个位置都能单独停止、换模型、重试、跳过）----

    /** 一页圆桌产物的座位标记，写进 Text part 的 metadata；不会发给模型，只用于本地识别 */
    private val ROUND_TABLE_META_ROLE = "rt_role"
    private val ROUND_TABLE_META_SEAT = "rt_seat"
    private val ROUND_TABLE_META_ATTEMPT = "rt_attempt"
    private val ROUND_TABLE_META_USABLE = "rt_usable"
    private val ROUND_TABLE_META_NOTICE = "rt_notice"

    private val roundTableRuns = ConcurrentHashMap<Uuid, RoundTableRun>()
    private val roundTableRunStates = ConcurrentHashMap<Uuid, MutableStateFlow<RoundTableRunState?>>()

    /**
     * v223：最近一轮圆桌的续跑控制器。
     *
     * 原来的生命周期在 runRoundTable finally 中会销毁协调器；这样「只出方案」模式一结束，
     * 用户即使看到某个座位被截断，也没有任何执行者可以接住 Continue 指令。
     * 保留这个轻量句柄（不保留网络连接，只保留纯内存协调对象和材料闭包），
     * 直到下一轮圆桌覆盖它，保证结束后仍能对单个座位续跑。
     */
    private data class RoundTableContinuationContext(
        val coordinator: RoundTableCoordinator,
        val materialsFor: (RoundTableSeat) -> List<RoundTableMaterial>,
    )

    private val roundTableContinuationContexts = ConcurrentHashMap<Uuid, RoundTableContinuationContext>()

    private fun roundTableNow(): Long = SystemClock.elapsedRealtime()

    private fun roundTableRunStateFlow(conversationId: Uuid): MutableStateFlow<RoundTableRunState?> =
        roundTableRunStates.getOrPut(conversationId) { MutableStateFlow(null) }

    /** 界面订阅它来显示圆桌控制面板（每个座位一行，带停止/换人/跳过按钮） */
    internal fun getRoundTableRunFlow(conversationId: Uuid): StateFlow<RoundTableRunState?> =
        roundTableRunStateFlow(conversationId).asStateFlow()

    private fun publishRoundTableState(run: RoundTableRun) {
        val snapshot = run.snapshot()
        roundTableRunStateFlow(snapshot.conversationId).value = snapshot
    }

    /**
     * 对某一个座位下指令：停止 / 跳过 / 重试 / 换模型。
     *
     * 只影响这一个位置：别的模型该跑的继续跑，已经出结果的一律保留。
     */
    internal fun controlRoundTableSeat(
        conversationId: Uuid,
        seatId: String,
        command: RoundTableSeatCommand,
    ): Boolean {
        val run = roundTableRuns[conversationId] ?: return false
        val before = run.snapshot()
        val beforeSeat = before.seat(seatId) ?: return false

        // v229：这些指令会让座位重新开跑
        val revives = command is RoundTableSeatCommand.Retry ||
            command is RoundTableSeatCommand.Replace ||
            command is RoundTableSeatCommand.Continue
        // 当前有没有执行协程在负责这个座位
        val scheduled = run.isSeatClaimed(seatId)
        // v239：上一条指令还挂在那儿没人接住 —— 说明负责这个座位的执行协程
        // 已经被卡死的上游拖住了。用户这次点击必须走「不等任何人」的强制通道。
        val commandStuck = run.peekCommand(seatId) != null

        // v229 修复死锁：没人负责这个座位时，停止 / 跳过必须**就地落终态**。
        // 旧行为是把指令排进队列等执行协程消费，可是那一组的调度器可能早就退出了
        // （例如最终综合阶段跑完），于是指令永远没人处理：界面停在「排队中」，
        // 「停止」按了没反应、「重试」「继续输出」按钮又都不显示，整个位置焊死。
        //
        // v239 把判据从「没人负责」扩大到「没人负责 **或** 上一条指令没人接」。
        // 真机事故：座位明明有执行协程（scheduled=true），但那个协程卡在一个
        // 「连接不断、一个字不推」的上游上，于是停止指令排进队列后永远不被消费 ——
        // 停止按钮变灰、跳过点了没反应、整场焊死，前面几个位置跑出来的方案一起作废。
        if ((!scheduled || commandStuck) && !revives) {
            when (command) {
                is RoundTableSeatCommand.Stop,
                is RoundTableSeatCommand.Timeout,
                    -> {
                    if (beforeSeat.status.isTerminal) return false
                    // 先断开连接、作废这次尝试，再落终态。
                    // 顺序反了的话，那个上游稍后吐出来的结果会把「已停止」覆盖回去。
                    run.invalidateAttempt(seatId)
                    run.markStopped(seatId)
                    publishRoundTableState(run)
                    forceSettleRoundTablePage(conversationId, seatId, skipped = false)
                    return true
                }

                is RoundTableSeatCommand.Skip -> {
                    run.invalidateAttempt(seatId)
                    run.markSkipped(seatId)
                    publishRoundTableState(run)
                    forceSettleRoundTablePage(conversationId, seatId, skipped = true)
                    return true
                }

                else -> Unit
            }
        }

        // v229：复活类指令在没人负责时，必须自己补起一个执行协程。
        // 这条路径同时覆盖两种情况：圆桌已经收工，以及某一阶段的调度器刚退出的窗口期。
        val rescueContext = if (revives && !scheduled) {
            roundTableContinuationContexts[conversationId] ?: return false
        } else {
            null
        }
        if (!revives && before.isFinished) return false

        val accepted = run.submitCommand(seatId, command)
        if (!accepted) return false

        if (rescueContext != null) {
            // 补跑：把阶段恢复成这个座位所属的阶段，并单独跑它一个
            run.setPhase(
                when (beforeSeat.role) {
                    RoundTableRole.FINAL -> RoundTableRunPhase.SUMMARY
                    RoundTableRole.REBUTTAL -> RoundTableRunPhase.REBUTTAL
                    else -> RoundTableRunPhase.PROPOSALS
                }
            )
            publishRoundTableState(run)
            appScope.launch {
                try {
                    rescueContext.coordinator.runGroup(listOf(seatId)) {
                        rescueContext.materialsFor(run.seat(seatId) ?: beforeSeat)
                    }
                } catch (e: CancellationException) {
                    // 新一轮圆桌或 App 生命周期取消时不吞掉取消信号；座位状态由 finally 统一收尾
                    throw e
                } finally {
                    if (run.snapshot().activeSeats.isEmpty()) {
                        run.setPhase(RoundTableRunPhase.FINISHED)
                        publishRoundTableState(run)
                    }
                }
            }
        } else {
            publishRoundTableState(run)
        }
        return true
    }

    /**
     * v239：强制处置一个座位之后，把「半截内容 / 说明页」补写进对话。
     *
     * 座位状态已经在调用处就地改好（界面立刻有反应），这里只负责事后补页面：
     * 写页面要碰数据库、可能慢，但绝不能让它挡住状态更新 —— 那正是旧死锁的教训。
     */
    private fun forceSettleRoundTablePage(
        conversationId: Uuid,
        seatId: String,
        skipped: Boolean,
    ) {
        val coordinator = roundTableContinuationContexts[conversationId]?.coordinator ?: return
        appScope.launch {
            runCatching { coordinator.writeForcedSettleNotice(seatId, skipped) }
        }
    }

    /**
     * v239：把主对话原文 + 本会话已派出子代理的最新结论，组装成一段纯文本给子代理看。
     *
     * 用户原话：「可以自己选择给不给全部上文，但不能做不到给不了全部上文。」
     * 在此之前主模型没有任何办法导出自己的对话原文，`context_summary` 只能是手打转述。
     *
     * 刻意每次调用时**重新读一遍最新对话**（而不是复用装配工具那一刻的快照）：
     * 主模型往往是在一轮对话进行到一半时才派子代理，用旧快照就会漏掉最近几条，
     * 也漏掉刚刚跑完的那几个子代理的结论 —— 而那恰恰是最该带上的部分。
     *
     * 组装放在 service 侧、agent 包只收字符串，所以三向隔离红线不动。
     */
    private suspend fun buildAgentFullContext(
        conversationId: Uuid,
        includeReasoning: Boolean,
        maxChars: Int,
    ): String {
        val latest = getConversationFlow(conversationId).value
        val digests = runCatching {
            agentThreadManager.list(conversationId.toString()).map { thread ->
                AgentContextFeed.ThreadDigest(
                    id = thread.id,
                    role = thread.role.name,
                    status = thread.status.name,
                    activeModelId = thread.activeModelId,
                    taskPreview = thread.task,
                    conclusion = AgentReport.decode(thread.reportJson)?.conclusion
                        ?: thread.reportJson.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
        return AgentContextFeed.render(
            messages = latest.currentMessages,
            threads = digests,
            maxChars = maxChars,
            includeReasoning = includeReasoning,
        )
    }

    /** 手动把"疑似无效"的那一页重新算进（或排除出）最终拍板材料 */
    fun setRoundTableSeatIncluded(conversationId: Uuid, seatId: String, include: Boolean) {
        val run = roundTableRuns[conversationId] ?: return
        run.setIncludeInSummary(seatId, include)
        publishRoundTableState(run)
    }

    /** 缺席检查点上的选择：补齐 / 用现有材料继续 / 结束本轮 */
    internal fun resolveRoundTableGap(conversationId: Uuid, decision: RoundTableGapDecision): Boolean {
        val run = roundTableRuns[conversationId] ?: return false
        val accepted = run.resolveGap(decision)
        if (accepted) publishRoundTableState(run)
        return accepted
    }

    /** 座位的展示名：独立探索要带序号，其它按角色 */
    private fun roundTableSeatLabel(seat: RoundTableSeat): String = when (seat.role) {
        RoundTableRole.EXPLORATION ->
            context.getString(R.string.round_table_seat_exploration, seat.ordinal)

        else -> roundTableRoleLabel(seat.role)
    }

    /** 顶部那一行简要状态；详细状态在控制面板里 */
    private fun buildRoundTableStatusText(state: RoundTableRunState): String {
        if (state.phase == RoundTableRunPhase.PROPOSAL_GAP) {
            return context.getString(R.string.round_table_status_gap_waiting)
        }
        val now = roundTableNow()
        val running = state.seats.filter { it.status == RoundTableSeatStatus.RUNNING }
        if (running.isEmpty()) return context.getString(R.string.round_table_status_summarizing)
        return running.joinToString(" ｜ ") { seat ->
            val label = roundTableSeatLabel(seat)
            if (RoundTableStallPolicy.isSoftStalled(seat, now)) {
                context.getString(
                    R.string.round_table_status_stalled,
                    label,
                    seat.modelName,
                    (seat.idleMillis(now) / 60_000L).coerceAtLeast(1L).toInt(),
                )
            } else {
                context.getString(
                    R.string.round_table_status_stage,
                    label,
                    seat.modelName,
                    seat.chars,
                )
            }
        }
    }

    /**
     * 建座位表。
     *
     * 座位一旦建好，整轮圆桌都不变；模型只是"当前坐在这个位置上的人"，可以随时换。
     */
    private fun buildRoundTableSeats(
        enableContract: Boolean,
        enableMainDraft: Boolean,
        enableRebuttal: Boolean,
        autoSummarize: Boolean,
        members: List<Model>,
        mainModel: Model,
        contractModel: Model,
        rebuttalModel: Model,
    ): List<RoundTableSeat> = buildList {
        if (enableContract) {
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.CONTRACT,
                    role = RoundTableRole.CONTRACT,
                    slot = RoundTableSlot.CONTRACT,
                    modelId = contractModel.id,
                    modelName = contractModel.displayName,
                )
            )
        }
        members.forEachIndexed { index, model ->
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.explorer(index),
                    role = RoundTableRole.EXPLORATION,
                    slot = RoundTableSlot.MEMBER_BASE + index,
                    ordinal = index + 1,
                    modelId = model.id,
                    modelName = model.displayName,
                )
            )
        }
        if (enableMainDraft) {
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.MAIN_DRAFT,
                    role = RoundTableRole.MAIN_DRAFT,
                    slot = RoundTableSlot.MAIN_DRAFT,
                    modelId = mainModel.id,
                    modelName = mainModel.displayName,
                )
            )
        }
        if (autoSummarize && enableRebuttal) {
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.REBUTTAL,
                    role = RoundTableRole.REBUTTAL,
                    slot = RoundTableSlot.REBUTTAL,
                    modelId = rebuttalModel.id,
                    modelName = rebuttalModel.displayName,
                )
            )
        }
        if (autoSummarize) {
            add(
                RoundTableSeat(
                    seatId = RoundTableSeatIds.SUMMARY,
                    role = RoundTableRole.FINAL,
                    slot = RoundTableSlot.SUMMARY,
                    modelId = mainModel.id,
                    modelName = mainModel.displayName,
                )
            )
        }
    }

    /** 给圆桌产出的页打上座位标记，重启后仍能认出这一页是谁、什么角色、算不算有效材料 */
    private fun tagRoundTablePage(
        message: UIMessage,
        seat: RoundTableSeat,
        attempt: Int,
        usable: Boolean?,
        notice: String? = null,
    ): UIMessage {
        val tag = buildMap {
            put(ROUND_TABLE_META_ROLE, JsonPrimitive(seat.role.name))
            put(ROUND_TABLE_META_SEAT, JsonPrimitive(seat.seatId))
            put(ROUND_TABLE_META_ATTEMPT, JsonPrimitive(attempt))
            if (usable != null) put(ROUND_TABLE_META_USABLE, JsonPrimitive(usable))
            if (notice != null) put(ROUND_TABLE_META_NOTICE, JsonPrimitive(notice))
        }
        var tagged = false
        val parts = message.parts.map { part ->
            if (!tagged && part is UIMessagePart.Text) {
                tagged = true
                part.copy(metadata = JsonObject((part.metadata ?: JsonObject(emptyMap())) + tag))
            } else {
                part
            }
        }
        // v223：模型可能先长时间只返回 Reasoning / Tool、还没有 Text。
        // 此时也必须加一个空文本标记，否则「查看过程」页无法认出这条实时消息属于哪个座位。
        // 空文本不会改变 toText()、不会显示空气泡，只承担本地 metadata 载体。
        return if (tagged) {
            message.copy(parts = parts)
        } else {
            message.copy(
                parts = listOf(
                    UIMessagePart.Text(
                        text = "",
                        metadata = JsonObject(tag),
                    )
                ) + message.parts
            )
        }
    }

    /** 读回这一页的角色标记；v212 及更早的老数据没有标记，返回 null */
    private fun roundTablePageRole(message: UIMessage): String? =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .firstNotNullOfOrNull { part ->
                runCatching {
                    part.metadata?.get(ROUND_TABLE_META_ROLE)?.jsonPrimitive?.content
                }.getOrNull()
            }

    /** 这一页是否被判定为可用材料；没有标记时返回 null */
    private fun roundTablePageUsable(message: UIMessage): Boolean? =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .firstNotNullOfOrNull { part ->
                runCatching {
                    part.metadata?.get(ROUND_TABLE_META_USABLE)?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                }.getOrNull()
            }

    private fun roundTableNoticeText(seat: RoundTableSeat, notice: RoundTableSeatNotice): String {
        val label = roundTableSeatLabel(seat)
        val model = seat.modelName
        return when (notice) {
            is RoundTableSeatNotice.Stopped ->
                context.getString(R.string.round_table_notice_stopped, label, model)

            is RoundTableSeatNotice.Skipped ->
                context.getString(R.string.round_table_notice_skipped, label)

            is RoundTableSeatNotice.ModelMissing ->
                context.getString(R.string.round_table_notice_model_missing, label)

            is RoundTableSeatNotice.TimedOut ->
                context.getString(
                    R.string.round_table_notice_timeout,
                    label,
                    model,
                    (notice.idleMillis / 60_000L).coerceAtLeast(1L).toInt(),
                )

            is RoundTableSeatNotice.AutoContinuing ->
                context.getString(
                    if (notice.timedOut) {
                        R.string.round_table_notice_auto_continue_timeout
                    } else {
                        R.string.round_table_notice_auto_continue
                    },
                    label,
                    model,
                    notice.attemptNo,
                    notice.maxAttempts,
                )

            // v276：续跑没有新增内容，明确告诉用户旧内容已保留、可以再次继续或换模型
            RoundTableSeatNotice.NoProgress ->
                context.getString(R.string.round_table_notice_no_progress, label, model)

            is RoundTableSeatNotice.Failed -> buildString {
                append(
                    context.getString(
                        R.string.round_table_stage_failed_body,
                        label,
                        model,
                        notice.reason.ifBlank { context.getString(R.string.round_table_error_empty) },
                    )
                )
                if (notice.autoRetries > 0) {
                    append(
                        context.getString(
                            R.string.round_table_stage_retry_note,
                            notice.autoRetries,
                        )
                    )
                }
                append(context.getString(R.string.round_table_notice_failed_hint))
            }
        }
    }

    /** 缺席检查点说明页：写清楚少了哪些位置、继续还要花几次调用 */
    private suspend fun writeRoundTableGapPage(
        ctx: RoundTableContext,
        run: RoundTableRun,
        usableCount: Int,
        missingCount: Int,
        remainingCalls: Int,
        /**
         * v276：要展示为「缺席」的座位 id 集合。为空/未传时保持旧行为——按 countsAsGap 推导
         * （PROPOSALS 缺口沿用）。REVIEW / SUMMARY 检查点由调用方按真实可用结果集合传入，
         * 避免「停在检查点」与「显示哪些位置缺席」口径不一致（截断/失败/停止要显示出来）。
         */
        missingSeatIds: Set<String>? = null,
    ) {
        val missingLabels = if (missingSeatIds != null) {
            run.snapshot().seats
                .filter { it.seatId in missingSeatIds }
                .joinToString("、") { roundTableSeatLabel(it) }
        } else {
            run.snapshot().seats
                .filter { it.status.countsAsGap }
                .joinToString("、") { roundTableSeatLabel(it) }
        }
        writeRoundTablePage(
            ctx = ctx,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(
                        text = context.getString(
                            R.string.round_table_gap_body,
                            usableCount,
                            missingCount,
                            missingLabels,
                            remainingCalls,
                        ),
                        metadata = JsonObject(
                            mapOf(ROUND_TABLE_META_NOTICE to JsonPrimitive("gap"))
                        ),
                    )
                ),
                modelId = ctx.mainModel.id,
            ),
            slot = RoundTableSlot.GAP,
            select = true,
            persist = true,
        )
    }

    /**
     * v229：阶段结束后的「审阅检查点」——整场停下来等用户，后面的阶段绝不先开跑。
     *
     * 两个触发场景（都是用户明确要求的）：
     * 1. [RoundTableGapStage.REVIEW]：这一组里出现「疑似无效」（太短 / 复述角色说明 /
     *    只说要去做什么）。用户原话是不要自动续跑这种情况，先让他看过再决定算不算进方案，
     *    否则模型会在一点点内容上硬编，产生噪音；**并且后续流程必须一起暂停**。
     * 2. [RoundTableGapStage.SUMMARY]：最终综合没拿到可用结果。用户原话是不能跳过最后一步，
     *    否则前功尽弃，所以这里不允许直接收工，必须停下来允许重试 / 换模型 / 续跑。
     *
     * 用户在检查点选「先补齐」之后，只要对某个座位下重试 / 换模型 / 继续输出，
     * [controlRoundTableSeat] 会自动补一个执行协程把它跑起来，这里只负责等它跑完再复查。
     */
    private suspend fun awaitRoundTableReview(
        ctx: RoundTableContext,
        run: RoundTableRun,
        coordinator: RoundTableCoordinator,
        session: ConversationSession,
        seatIds: List<String>,
        stage: RoundTableGapStage,
        remainingCalls: Int,
    ): RoundTableGapDecision {
        var decision = RoundTableGapDecision.CONTINUE_WITH_CURRENT
        while (true) {
            currentCoroutineContext().ensureActive()
            val seats = run.snapshot().seats.filter { it.seatId in seatIds }
            if (seats.isEmpty()) return decision
            // v276：以 coordinator.usableResults 的真实可用结果集合为准，而不是只看 status。
            // REVIEW 只要「非跳过的座位里有一个没有可用材料」（失败/停止/中断/截断未纳入
            // 摘要）就必须停下；SUMMARY 是真实可用集合为空才停下。
            val usableIdSet = coordinator.usableResults(seatIds).map { it.first.seatId }.toSet()
            val needsReview = roundTableStageNeedsReview(stage, seats, usableIdSet)
            if (!needsReview) return decision

            val usableCount = usableIdSet.size
            val missingCount = roundTableMissingSeatIds(stage, seats, usableIdSet).size
            writeRoundTableGapPage(
                ctx,
                run,
                usableCount,
                missingCount,
                remainingCalls,
                missingSeatIds = roundTableMissingSeatIds(stage, seats, usableIdSet),
            )
            val answer = run.openGap(
                RoundTableGapPrompt(
                    usableCount = usableCount,
                    missingCount = missingCount,
                    remainingCalls = remainingCalls,
                    stage = stage,
                )
            )
            publishRoundTableState(run)
            session.processingStatus.value =
                context.getString(R.string.round_table_status_gap_waiting)
            decision = answer.await()
            publishRoundTableState(run)

            // 结束本轮：保留检查点说明页，作为"为什么停在这里"的记录
            if (decision == RoundTableGapDecision.END_RUN) return decision
            removeRoundTablePages(ctx, RoundTableSlot.GAP)
            if (decision == RoundTableGapDecision.CONTINUE_WITH_CURRENT) return decision

            // 先补齐：等用户复活某个座位，再等它跑完，然后回到循环顶部复查
            run.setPhase(
                when (stage) {
                    RoundTableGapStage.SUMMARY -> RoundTableRunPhase.SUMMARY
                    RoundTableGapStage.REVIEW -> run.snapshot().phase
                    RoundTableGapStage.PROPOSALS -> RoundTableRunPhase.PROPOSALS
                }
            )
            publishRoundTableState(run)
            run.state.first { state ->
                state.seats.any { it.seatId in seatIds && !it.status.isTerminal }
            }
            run.state.first { state ->
                state.seats.filter { it.seatId in seatIds }.all { it.status.isTerminal }
            }
        }
    }

    /**
     * v213：把"真正调模型、真正写页面"接给调度器。
     *
     * 调度器（[RoundTableCoordinator]）只负责座位状态、并发与指令；
     * 具体怎么生成、结果怎么落页、失败怎么写说明，都在这里。
     */
    private inner class RoundTableSeatExecutor(
        private val ctx: RoundTableContext,
        private val run: RoundTableRun,
        private val session: ConversationSession,
        private val summaryPrompt: String,
        private val sharedMaterials: () -> List<RoundTableMaterial>,
    ) : RoundTableSeatCallbacks {
        lateinit var coordinator: RoundTableCoordinator
        var proposalSeatIds: List<String> = emptyList()
        var rebuttalText: String = ""

        /**
         * v215：每个座位最近一次流式快照。
         *
         * 用户点「停止」时模型往往已经说了一部分，直接丢掉太浪费；
         * 这里把最后一次收到的消息留着，停止时写成一页（默认不算进结论）。
         */
        private val partialSnapshots = ConcurrentHashMap<String, UIMessage>()

        /** v223：当前座位/本次尝试对应的稳定页面 ID，流式刷新时原地覆盖而不是新增页面 */
        private val livePageIds = ConcurrentHashMap<String, Uuid>()

        /** v223：非最终座位的实时页每秒最多写一次，避免每个 token 都触发数据库写入 */
        private val lastLivePageAt = ConcurrentHashMap<String, Long>()

        /** v224：座位 → 它自己的过程对话（真实 Conversation） */
        private val seatChats = ConcurrentHashMap<String, Uuid>()

        /** v224：`座位#尝试次数` → 该轮在过程对话里占的节点 */
        private val seatTurns = ConcurrentHashMap<String, RoundTableSeatTurn>()

        /** v224：同一个位置的过程对话串行写，避免流式刷新与终态落盘互相覆盖 */
        private val seatChatMutexes = ConcurrentHashMap<String, Mutex>()

        private fun turnKey(seat: RoundTableSeat, attempt: Int): String = "${seat.seatId}#$attempt"

        private fun seatChatMutex(seatId: String): Mutex =
            seatChatMutexes.getOrPut(seatId) { Mutex() }

        /**
         * v224：确保这个位置有过程对话，并把 ID 回填到座位上（界面据此显示「查看过程」）。
         *
         * 建不出来只是看不到过程，不影响圆桌本身，因此失败返回 null 后照常继续跑。
         */
        private suspend fun ensureSeatChat(seat: RoundTableSeat): Uuid? {
            seatChats[seat.seatId]?.let { return it }
            val existing = run.seat(seat.seatId)?.chatConversationId
                ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
            if (existing != null) {
                seatChats[seat.seatId] = existing
                return existing
            }
            val created = createRoundTableSeatConversation(ctx, seat) ?: return null
            seatChats[seat.seatId] = created
            run.bindSeatConversation(seat.seatId, created.toString())
            onStateChanged()
            return created
        }

        /** v224：把这一轮的模型输出同步到过程对话（幂等，可反复调用） */
        private suspend fun mirrorSeatChat(
            seat: RoundTableSeat,
            attempt: Int,
            userText: String?,
            assistantMessage: UIMessage?,
        ) {
            val conversationId = ensureSeatChat(seat) ?: return
            val turn = seatTurns.getOrPut(turnKey(seat, attempt)) { RoundTableSeatTurn() }
            seatChatMutex(seat.seatId).withLock {
                mirrorRoundTableSeatTurn(
                    conversationId = conversationId,
                    turn = turn,
                    userText = userText,
                    assistantMessage = assistantMessage,
                )
            }
        }

        /** v224：往过程对话里补一条说明（超时 / 被停止 / 模型缺失…） */
        private suspend fun noteSeatChat(seat: RoundTableSeat, text: String) {
            val conversationId = seatChats[seat.seatId] ?: return
            seatChatMutex(seat.seatId).withLock {
                appendRoundTableSeatNote(conversationId, text, seat.modelId)
            }
        }

        private fun livePageKey(seat: RoundTableSeat): String = seat.seatId

        private fun stablePageId(
            seat: RoundTableSeat,
            continueFrom: String?,
            previousSnapshot: UIMessage?,
        ): Uuid {
            val key = livePageKey(seat)
            if (!continueFrom.isNullOrBlank()) {
                // 续跑尽量复用上一页 ID，让旧页原地长大，不产生第二份重复结果
                val previousId = coordinator.resultOf(seat.seatId)?.id ?: previousSnapshot?.id
                if (previousId != null) {
                    livePageIds[key] = previousId
                    return previousId
                }
            }
            // 同一座位跨重试/换模型也固定使用一页；attempt metadata 会更新，
            // 这样主对话不会堆出多份同座位残页。
            return livePageIds.getOrPut(key) { previousSnapshot?.id ?: Uuid.random() }
        }

        /** v223：把探索/合同/反驳的流式快照按固定页面实时写回当前圆桌节点 */
        private suspend fun streamSeatSnapshot(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
        ) {
            // 被换掉/重试掉的旧请求即使无视取消继续吐流，也绝不能覆盖新模型页面。
            if (!run.isCurrentAttempt(seat.seatId, attempt)) return
            val normalized = message.copy(
                modelId = seat.modelId,
                id = livePageIds[livePageKey(seat)] ?: message.id,
            )
            partialSnapshots[seat.seatId] = normalized
            val now = SystemClock.elapsedRealtime()
            val last = lastLivePageAt[seat.seatId] ?: 0L
            if (now - last < GENERATION_CHECKPOINT_INTERVAL_MS) return
            lastLivePageAt[seat.seatId] = now
            writeRoundTablePage(
                ctx = ctx,
                message = tagRoundTablePage(
                    message = normalized,
                    seat = seat,
                    attempt = attempt,
                    usable = null,
                ),
                slot = seat.slot,
                select = false,
                persist = false,
            )
            // v224：同一个节流点把过程同步到位置自己的对话，
            // 用户点「查看过程」进去看到的就是这份，边生成边刷新。
            mirrorSeatChat(
                seat = seat,
                attempt = attempt,
                userText = null,
                assistantMessage = normalized,
            )
        }

        /**
         * v224：最终拍板位置的流式镜像。
         *
         * 拍板本来就会实时写进主对话，这里只额外同步到它自己的过程对话，
         * 让「查看过程」对所有位置一致可用。
         */
        private suspend fun streamFinalSnapshot(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
        ) {
            // v239：守卫必须在写快照之前。
            // 顺序反了的话，被换掉 / 被强制停止的旧请求即使无视取消继续吐流，
            // 也会把 partialSnapshots 覆盖成它的内容，于是「保留半截内容」保下来的
            // 是那个已经作废的版本。
            if (!run.isCurrentAttempt(seat.seatId, attempt)) return
            partialSnapshots[seat.seatId] = message
            val now = SystemClock.elapsedRealtime()
            val last = lastLivePageAt[seat.seatId] ?: 0L
            if (now - last < GENERATION_CHECKPOINT_INTERVAL_MS) return
            lastLivePageAt[seat.seatId] = now
            mirrorSeatChat(
                seat = seat,
                attempt = attempt,
                userText = null,
                assistantMessage = message,
            )
        }

        /** 可用的方案（按座位顺序）；疑似无效和被跳过的不算 */
        fun proposals(): List<RoundTableProposal> =
            coordinator.usableResults(proposalSeatIds).map { (seat, message) ->
                RoundTableProposal(
                    modelName = seat.modelName,
                    text = message.toText().trim(),
                    isMainModelDraft = seat.role == RoundTableRole.MAIN_DRAFT,
                )
            }

        /** 反驳阶段要看的材料：一份方案一条，标明出自谁 */
        fun proposalMaterials(): List<RoundTableMaterial> =
            proposals().mapIndexed { index, proposal ->
                RoundTableMaterial(
                    label = context.getString(
                        if (proposal.isMainModelDraft) {
                            R.string.round_table_material_main_draft
                        } else {
                            R.string.round_table_material_proposal
                        },
                        index + 1,
                        proposal.modelName,
                    ),
                    text = proposal.text,
                )
            }

        override suspend fun generate(
            seat: RoundTableSeat,
            attempt: Int,
            materials: List<RoundTableMaterial>,
            continueFrom: String?,
            onActivity: (Int) -> Unit,
        ): UIMessage {
            // 续跑可能需要复用停止/失败前的快照 ID；先保存，再清空本次尝试的快照
            val previousSnapshot = partialSnapshots[seat.seatId]
            val pageId = stablePageId(seat, continueFrom, previousSnapshot)
            partialSnapshots.remove(seat.seatId)
            lastLivePageAt.remove(seat.seatId)
            val model = seat.modelId?.let { ctx.settings.findModelById(it) }
                ?: error(
                    context.getString(
                        R.string.round_table_notice_model_missing,
                        roundTableSeatLabel(seat),
                    )
                )
            // v224：先把这一轮真正发给模型的「提问」写进位置自己的过程对话，
            // 用户点进去看到的就是完整的一问一答（续跑那一轮显示的是「继续」指令），
            // 而不是只有半截回答、看不出模型在答什么。
            mirrorSeatChat(
                seat = seat,
                attempt = attempt,
                userText = when {
                    !continueFrom.isNullOrBlank() -> ROUND_TABLE_CONTINUE_INSTRUCTION
                    seat.role == RoundTableRole.FINAL -> buildRoundTableSummaryMessage(
                        basePrompt = summaryPrompt,
                        proposals = proposals(),
                        rebuttal = rebuttalText,
                        sharedMaterials = sharedMaterials(),
                    )

                    else -> buildRoundTableStageMessage(seat.role, materials)
                },
                assistantMessage = null,
            )
            if (seat.role == RoundTableRole.FINAL) {
                // 换人或重试之前先清掉上一次没写完的残页，避免出现两段半截结论
                removeRoundTablePages(ctx, RoundTableSlot.SUMMARY)
                return summarizeRoundTable(
                    ctx = ctx,
                    model = model,
                    proposals = proposals(),
                    rebuttal = rebuttalText,
                    sharedMaterials = sharedMaterials(),
                    summaryPrompt = summaryPrompt,
                    continueFrom = continueFrom,
                    stableMessageId = pageId,
                    onActivity = onActivity,
                    onSnapshot = { streamFinalSnapshot(seat, attempt, it) },
                ) ?: error(context.getString(R.string.round_table_error_empty))
            }
            return generateRoundTableProposal(
                settings = ctx.settings,
                model = model,
                assistant = ctx.assistant,
                conversation = ctx.conversation,
                baseMessages = ctx.baseMessages,
                tools = ctx.tools,
                role = seat.role,
                materials = materials,
                continueFrom = continueFrom,
                stableMessageId = pageId,
                onProgress = onActivity,
                onStream = { streamSeatSnapshot(seat, attempt, it) },
            )
        }

        override suspend fun writeResult(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
            verdict: RoundTableContentVerdict,
            truncated: Boolean,
        ) {
            // v223：被截断的产出页尾补一句醒目说明，避免用户误以为模型就只写了这么短
            val notice = if (truncated) {
                "\n\n⚠ " + context.getString(R.string.round_table_notice_truncated)
            } else {
                ""
            }
            val tagged = tagRoundTablePage(
                message = message.copy(
                    parts = message.parts + if (notice.isNotBlank()) {
                        listOf(UIMessagePart.Text(notice))
                    } else {
                        emptyList()
                    }
                ),
                seat = seat,
                attempt = attempt,
                usable = verdict.isUsable && !truncated,
            )
            writeRoundTablePage(
                ctx = ctx,
                message = tagged,
                slot = seat.slot,
                // 最终结论那一页需要自动翻到，其它页不抢用户正在看的位置
                select = seat.role == RoundTableRole.FINAL,
                persist = true,
            )
            // v224：终态内容同步到位置自己的过程对话，确保用户在那边看到的是最终完整版
            mirrorSeatChat(
                seat = seat,
                attempt = attempt,
                userText = null,
                assistantMessage = tagged,
            )
        }

        override fun partialTextOf(seat: RoundTableSeat): String =
            partialSnapshots[seat.seatId]?.toText()?.trim().orEmpty()

        /**
         * v276：换模型 / 重试时清掉这个座位的旧输出。
         *
         * 清掉流式快照、实时页节流计数、稳定页 ID，并删除该座位 slot 的旧圆桌页，
         * 避免旧模型的内容冒充新模型产出。**保留**该座位自己的过程对话历史
         * （seatChats / seatTurns 不清）——那是用户随时能点「查看过程」翻看的历史。
         */
        override suspend fun clearSeatOutput(seat: RoundTableSeat) {
            partialSnapshots.remove(seat.seatId)
            lastLivePageAt.remove(seat.seatId)
            livePageIds.remove(seat.seatId)
            removeRoundTablePages(ctx, seat.slot)
        }

        override suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice) {
            writeRoundTablePage(
                ctx = ctx,
                message = tagRoundTablePage(
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(roundTableNoticeText(seat, notice))),
                        modelId = seat.modelId,
                    ),
                    seat = seat,
                    attempt = seat.attempt,
                    usable = false,
                    notice = notice::class.simpleName,
                ),
                slot = seat.slot,
                select = false,
                persist = true,
            )
            // v224：过程对话里也补一条说明，避免用户在那边看不出这个位置为什么停了
            noteSeatChat(seat, roundTableNoticeText(seat, notice))
        }

        override suspend fun writePartialResult(
            seat: RoundTableSeat,
            notice: RoundTableSeatNotice,
        ): UIMessage? {
            val snapshot = partialSnapshots[seat.seatId] ?: return null
            if (snapshot.toText().isBlank()) return null
            // 在半截内容末尾补一句说明，避免用户误以为这是完整产出
            val message = snapshot.copy(
                parts = snapshot.parts + UIMessagePart.Text(
                    context.getString(
                        R.string.round_table_notice_stopped_partial,
                        roundTableSeatLabel(seat),
                        seat.modelName,
                    )
                ),
                modelId = seat.modelId,
            )
            val tagged = tagRoundTablePage(
                message = message,
                seat = seat,
                attempt = seat.attempt,
                usable = false,
                notice = notice::class.simpleName,
            )
            writeRoundTablePage(
                ctx = ctx,
                message = tagged,
                slot = seat.slot,
                select = false,
                persist = true,
            )
            // v224：半截内容同样落到过程对话，用户点进去能看到「停在哪一句」
            mirrorSeatChat(
                seat = seat,
                attempt = seat.attempt,
                userText = null,
                assistantMessage = tagged,
            )
            return message
        }

        override fun resolveModelName(seat: RoundTableSeat): String? =
            seat.modelId?.let { ctx.settings.findModelById(it)?.displayName }

        override fun onStateChanged() {
            val snapshot = run.snapshot()
            session.processingStatus.value = buildRoundTableStatusText(snapshot)
            roundTableRunStateFlow(snapshot.conversationId).value = snapshot
        }
    }
    /**
     * 主导式圆桌。
     *
     * 流程（照抄用户「单 AI 主导模式」）：
     * 1) 任务合同与共享事实（可关）：把目标、范围、完成标准、缺失信息结构化，并把查到的事实固定下来；
     * 2) 并发：各成员模型独立探索 + 主模型独立初案（可关）——彼此看不到对方，避免互相带偏；
     * 3) 针对性反驳（可关）：逐条检查具体主张是否成立；
     * 4) 主模型最终拍板：看到全部材料后自己判断，给出最终方案、验证、止损与回滚。
     *
     * 原始聊天上文全程只读；各阶段说明与材料只作为临时输入，不写回对话。
     *
     * @param content 用户这一轮的输入；为空表示直接对已有上文跑圆桌
     * @param autoSummarize true = 一路跑到最终拍板；false = 只跑到出方案就停
     */
    fun runRoundTable(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        autoSummarize: Boolean,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            val senderName = context.getString(R.string.round_table_sender_name)
            var endReason = GenerationEndReason.COMPLETED
            // v213：本轮的座位运行时；即使中途被取消也要在 finally 里收尾
            var activeRun: RoundTableRun? = null
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val settings = settingsStore.settingsFlow.first()
                val roundTable = settings.roundTableSetting
                val members = roundTable.memberModelIds
                    .distinct()
                    .mapNotNull { settings.findModelById(it) }
                    .take(MAX_ROUND_TABLE_MEMBERS)
                if (members.size < 2) {
                    addError(
                        IllegalStateException(context.getString(R.string.round_table_error_need_two_members)),
                        conversationId,
                        title = context.getString(R.string.round_table_error_title),
                    )
                    return@launch
                }

                val assistant = settings.getAssistantById(session.state.value.assistantId)
                    ?: settings.getCurrentAssistant()
                val mainModel = roundTable.mainModelId?.let { settings.findModelById(it) }
                    ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                if (mainModel == null) {
                    addError(
                        IllegalStateException(context.getString(R.string.round_table_error_no_main_model)),
                        conversationId,
                        title = context.getString(R.string.round_table_error_title),
                    )
                    return@launch
                }

                // v209 兜底：既没写任务、上文也是空的，圆桌不知道该干什么，直接停下不花钱
                // v229 收紧：**预设内容不算「用户说过的话」**。
                // 真机事故：助手配了预设内容时，新建对话一打开就被当成「已经有上文」，
                // 于是空任务也能开跑，圆桌整轮跑完每个模型都没拿到用户的要求（只能凭空编），
                // 而界面随后把输入框清空了，用户打的字彻底消失。
                val presetTexts = assistant.presetMessages.map { it.toText() }
                val existingUserTexts = getConversationFlow(conversationId).value.currentMessages
                    .filter { it.role == MessageRole.USER }
                    .map { it.toText() }
                if (
                    content.isEmptyInputMessage() &&
                    !RoundTableTaskGuard.hasRealUserTask(existingUserTexts, presetTexts)
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.round_table_error_no_task)),
                        conversationId,
                        title = context.getString(R.string.round_table_error_title),
                    )
                    return@launch
                }

                // 用户消息先落地（与普通发送一致）
                // v229：改成**原子**读改写，并且写完立刻回读校验：
                // 发现没写进去就当场补一次，补不上直接报错停下，
                // 绝不允许「圆桌跑了一整轮，却没人知道用户要什么」。
                var taskText = ""
                if (!content.isEmptyInputMessage()) {
                    val processedContent = preprocessUserInputParts(content, assistant)
                    taskText = processedContent.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                        .trim()
                    val taskNode = UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode()
                    mutateAndSaveConversation(conversationId) { current ->
                        current.copy(
                            messageNodes = current.messageNodes + taskNode,
                            chatSuggestions = emptyList(),
                        )
                    }
                    if (getConversationFlow(conversationId).value.messageNodes.none { it.id == taskNode.id }) {
                        Log.w(TAG, "runRoundTable: task message missing after save, repairing once")
                        mutateAndSaveConversation(conversationId) { current ->
                            current.copy(messageNodes = current.messageNodes + taskNode)
                        }
                    }
                    if (getConversationFlow(conversationId).value.messageNodes.none { it.id == taskNode.id }) {
                        addError(
                            IllegalStateException(context.getString(R.string.round_table_error_task_lost)),
                            conversationId,
                            title = context.getString(R.string.round_table_error_title),
                        )
                        return@launch
                    }
                }

                checkInvalidMessages(conversationId)
                val conversation = getConversationFlow(conversationId).value
                val allowReadOnly = roundTable.allowReadOnlyTools
                val roleAssistant = asProposalOnlyAssistant(assistant, allowReadOnly)
                val nodeId = Uuid.random()
                markRoundTableNode(conversationId, nodeId)
                val ctx = RoundTableContext(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    settings = settings,
                    assistant = roleAssistant,
                    conversation = conversation,
                    baseMessages = conversation.currentMessages,
                    tools = buildRoundTableTools(settings, roleAssistant, conversation, allowReadOnly),
                    mainModel = mainModel,
                    writeMutex = Mutex(),
                    slots = mutableMapOf(),
                )

                // ---- v213：每一步都是一个"座位"，模型只是坐在上面的人 ----
                // 好处：某个位置卡住时可以只停它、只换它，其它位置的结果一律保留。
                val contractModel = roundTable.contractModelId
                    ?.let { settings.findModelById(it) } ?: mainModel
                val rebuttalModel = roundTable.rebuttalModelId
                    ?.let { settings.findModelById(it) } ?: mainModel
                val seats = buildRoundTableSeats(
                    enableContract = roundTable.enableContractStage,
                    enableMainDraft = roundTable.enableMainDraft,
                    enableRebuttal = roundTable.enableRebuttalStage,
                    autoSummarize = autoSummarize,
                    members = members,
                    mainModel = mainModel,
                    contractModel = contractModel,
                    rebuttalModel = rebuttalModel,
                )
                val run = RoundTableRun(
                    runId = Uuid.random(),
                    conversationId = conversationId,
                    nodeId = nodeId,
                    autoSummarize = autoSummarize,
                    seats = seats,
                    nowMillis = ::roundTableNow,
                )
                activeRun = run
                roundTableRuns[conversationId] = run

                val sharedMaterials = mutableListOf<RoundTableMaterial>()
                // v229 兜底：把「本轮任务原文」固定成一份材料。
                // 这样即使消息层面再出任何意外（被覆盖、被清空、上文被截断），
                // 每一个座位也一定拿得到用户的要求，不会再出现「模型只能凭空编任务」。
                val taskMaterialText = taskText.ifBlank {
                    conversation.currentMessages
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.toText()
                        ?.trim()
                        .orEmpty()
                }
                if (taskMaterialText.isNotBlank()) {
                    sharedMaterials += RoundTableMaterial(
                        label = context.getString(R.string.round_table_material_task),
                        text = taskMaterialText,
                    )
                }
                val executor = RoundTableSeatExecutor(
                    ctx = ctx,
                    run = run,
                    session = session,
                    summaryPrompt = roundTable.summaryPrompt,
                    sharedMaterials = { sharedMaterials.toList() },
                )
                val coordinator = RoundTableCoordinator(
                    run = run,
                    callbacks = executor,
                    nowMillis = ::roundTableNow,
                    maxEffort = assistant.reasoningLevel == ReasoningLevel.MAX,
                )
                executor.coordinator = coordinator
                roundTableContinuationContexts[conversationId] = RoundTableContinuationContext(
                    coordinator = coordinator,
                    materialsFor = { seat ->
                        when (seat.role) {
                            RoundTableRole.REBUTTAL -> sharedMaterials.toList() + executor.proposalMaterials()
                            RoundTableRole.FINAL -> emptyList()
                            else -> sharedMaterials.toList()
                        }
                    },
                )
                publishRoundTableState(run)

                // ---- 阶段 1：任务合同与共享事实 ----
                if (roundTable.enableContractStage) {
                    run.setPhase(RoundTableRunPhase.CONTRACT)
                    publishRoundTableState(run)
                    coordinator.runGroup(listOf(RoundTableSeatIds.CONTRACT)) { sharedMaterials.toList() }
                    // v229：出现「疑似无效（太短 / 复述角色说明 / 只说要去做什么）」时整场停下来等用户。
                    // 用户明确要求：这种情况不要自动续写硬编，先让他看过再决定算不算进方案，
                    // 而且后面的阶段必须一起停住，不许当前阶段停着、后面先开跑。
                    val contractReview = awaitRoundTableReview(
                        ctx = ctx,
                        run = run,
                        coordinator = coordinator,
                        session = session,
                        seatIds = listOf(RoundTableSeatIds.CONTRACT),
                        stage = RoundTableGapStage.REVIEW,
                        remainingCalls = members.size + (if (autoSummarize) 2 else 0),
                    )
                    if (contractReview == RoundTableGapDecision.END_RUN) return@launch
                    coordinator.usableResults(listOf(RoundTableSeatIds.CONTRACT))
                        .firstOrNull()?.second?.toText()?.trim()?.takeIf { it.isNotBlank() }?.let {
                            sharedMaterials += RoundTableMaterial(
                                label = context.getString(R.string.round_table_material_contract),
                                text = it,
                            )
                        }
                }

                // ---- 阶段 2：并发出方案（成员独立探索 + 主模型独立初案）----
                val proposalSeatIds = buildList {
                    members.indices.forEach { add(RoundTableSeatIds.explorer(it)) }
                    if (roundTable.enableMainDraft) add(RoundTableSeatIds.MAIN_DRAFT)
                }
                executor.proposalSeatIds = proposalSeatIds
                run.setPhase(RoundTableRunPhase.PROPOSALS)
                publishRoundTableState(run)

                var gapDecision = RoundTableGapDecision.CONTINUE_WITH_CURRENT
                while (true) {
                    coordinator.runGroup(proposalSeatIds) { sharedMaterials.toList() }
                    // 只出方案模式：到这里就停，由用户自己看过再决定要不要综合
                    if (!autoSummarize) break

                    val groupSeats = run.snapshot().seats.filter { it.seatId in proposalSeatIds }
                    // v215：能不能继续，看的是"实际拿到多少可用材料"，与调度器口径保持一致；
                    // 被用户主动跳过的位置不再算作缺席（那是明确放弃，不该反复追问）。
                    val usableCount = coordinator.usableResults(proposalSeatIds).size
                    val missingCount = groupSeats.count { it.status.countsAsGap }
                    if (missingCount == 0 && usableCount >= MIN_ROUND_TABLE_SUMMARY_PROPOSALS) break

                    // v213 缺席检查点：有位置没拿到结果就先停下问用户，不自动往下花钱。
                    val remainingCalls = (if (roundTable.enableRebuttalStage) 1 else 0) + 1
                    writeRoundTableGapPage(ctx, run, usableCount, missingCount, remainingCalls)
                    val gapAnswer = run.openGap(
                        RoundTableGapPrompt(
                            usableCount = usableCount,
                            missingCount = missingCount,
                            remainingCalls = remainingCalls,
                        )
                    )
                    publishRoundTableState(run)
                    session.processingStatus.value =
                        context.getString(R.string.round_table_status_gap_waiting)
                    gapDecision = gapAnswer.await()
                    publishRoundTableState(run)

                    // 结束本轮：保留检查点说明页，作为"为什么停在这里"的记录
                    if (gapDecision == RoundTableGapDecision.END_RUN) break

                    removeRoundTablePages(ctx, RoundTableSlot.GAP)
                    if (gapDecision == RoundTableGapDecision.CONTINUE_WITH_CURRENT) {
                        // 一份可用材料都没有时，拍板只会重复空话，直接结束
                        if (usableCount == 0) gapDecision = RoundTableGapDecision.END_RUN
                        break
                    }

                    // 补齐缺席位置：等用户把某个位置换人或重试，之后自动接着跑
                    run.setPhase(RoundTableRunPhase.PROPOSALS)
                    publishRoundTableState(run)
                    run.state.first { state ->
                        state.seats.any { it.seatId in proposalSeatIds && !it.status.isTerminal }
                    }
                }

                if (autoSummarize && gapDecision != RoundTableGapDecision.END_RUN) {
                    // ---- 阶段 3：针对性反驳 ----
                    if (roundTable.enableRebuttalStage) {
                        run.setPhase(RoundTableRunPhase.REBUTTAL)
                        publishRoundTableState(run)
                        coordinator.runGroup(listOf(RoundTableSeatIds.REBUTTAL)) {
                            sharedMaterials + executor.proposalMaterials()
                        }
                        // v229：反驳阶段也一样，疑似无效就整场停下等用户
                        val rebuttalReview = awaitRoundTableReview(
                            ctx = ctx,
                            run = run,
                            coordinator = coordinator,
                            session = session,
                            seatIds = listOf(RoundTableSeatIds.REBUTTAL),
                            stage = RoundTableGapStage.REVIEW,
                            remainingCalls = 1,
                        )
                        if (rebuttalReview == RoundTableGapDecision.END_RUN) return@launch
                        executor.rebuttalText = coordinator
                            .usableResults(listOf(RoundTableSeatIds.REBUTTAL))
                            .firstOrNull()?.second?.toText()?.trim().orEmpty()
                    }

                    // ---- 阶段 4：主模型最终拍板 ----
                    run.setPhase(RoundTableRunPhase.SUMMARY)
                    publishRoundTableState(run)
                    coordinator.runGroup(listOf(RoundTableSeatIds.SUMMARY)) { emptyList() }
                    // v229：最终综合没拿到可用结果时**绝不许直接收工**。
                    // 用户原话：不能跳过最后一步，不然前功尽弃。
                    // 这里停下来等他重试 / 换模型 / 续跑，或明确选择结束。
                    awaitRoundTableReview(
                        ctx = ctx,
                        run = run,
                        coordinator = coordinator,
                        session = session,
                        seatIds = listOf(RoundTableSeatIds.SUMMARY),
                        stage = RoundTableGapStage.SUMMARY,
                        remainingCalls = 1,
                    )
                }

                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, getConversationFlow(conversationId).value)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                endReason = GenerationEndReason.CANCELLED
                throw e
            } catch (e: Exception) {
                endReason = GenerationEndReason.FAILED
                Log.e(TAG, "roundTable failed", e)
                addError(
                    e,
                    conversationId,
                    title = context.getString(R.string.round_table_error_title),
                )
            } finally {
                // v213 收尾：已完成的页一律保留；还在跑的位置标成"意外中断"，不自动重新调用模型
                activeRun?.let { finishedRun ->
                    finishedRun.markAllActiveInterrupted()
                    finishedRun.setPhase(RoundTableRunPhase.FINISHED)
                    publishRoundTableState(finishedRun)
                    // v223：不再移除最近一轮的 run/协调器；可续跑座位仍需要它。
                    // 下一次 runRoundTable 创建新 run 时会覆盖同一 conversationId 的句柄。
                    finishedRun.cleanup()
                }
                session.processingStatus.value = null
                appEventBus.tryEmit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        reason = endReason,
                        contentPreview = getConversationFlow(conversationId).value
                            .currentMessages.lastOrNull()?.toText()?.take(80)?.trim()
                            ?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        session.setJob(job)
    }

    /**
     * 手动综合：把指定 node 里已有的各页当作材料，用主模型综合出新的一页。
     * 用于「只出方案，不综合」之后，用户看过再决定综合的场景（此时不再单独跑反驳阶段）。
     */
    fun summarizeRoundTableNode(conversationId: Uuid, nodeId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            val senderName = context.getString(R.string.round_table_sender_name)
            var endReason = GenerationEndReason.COMPLETED
            try {
                runCatching { previousJob?.join() }

                val settings = settingsStore.settingsFlow.first()
                val roundTable = settings.roundTableSetting
                val conversation = getConversationFlow(conversationId).value
                val nodeIndex = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (nodeIndex < 0) return@launch
                val node = conversation.messageNodes[nodeIndex]

                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val mainModel = roundTable.mainModelId?.let { settings.findModelById(it) }
                    ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                if (mainModel == null) {
                    addError(
                        IllegalStateException(context.getString(R.string.round_table_error_no_main_model)),
                        conversationId,
                        title = context.getString(R.string.round_table_error_title),
                    )
                    return@launch
                }

                // v213：只把真正的"方案页"当材料。
                // 带 v213 标记的页按角色与有效性过滤（合同页、失败页、跳过页、反驳页、旧结论页一律排除）；
                // v212 及更早的老数据没有标记，保持原有行为，避免升级后功能突然失灵。
                val proposals = node.messages.mapNotNull { message ->
                    val role = roundTablePageRole(message)
                    if (role != null) {
                        val isProposalPage = role == RoundTableRole.EXPLORATION.name ||
                            role == RoundTableRole.MAIN_DRAFT.name
                        if (!isProposalPage) return@mapNotNull null
                        if (roundTablePageUsable(message) == false) return@mapNotNull null
                    }
                    message.toText().trim().takeIf { it.isNotBlank() }?.let { text ->
                        RoundTableProposal(
                            modelName = message.modelId
                                ?.let { settings.findModelById(it)?.displayName }
                                ?: context.getString(R.string.round_table_unknown_model),
                            text = text,
                            isMainModelDraft = if (role != null) {
                                role == RoundTableRole.MAIN_DRAFT.name
                            } else {
                                message.modelId == mainModel.id
                            },
                        )
                    }
                }
                if (proposals.isEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.round_table_error_no_proposals)),
                        conversationId,
                        title = context.getString(R.string.round_table_error_title),
                    )
                    return@launch
                }

                val allowReadOnly = roundTable.allowReadOnlyTools
                val roleAssistant = asProposalOnlyAssistant(assistant, allowReadOnly)
                markRoundTableNode(conversationId, nodeId)
                // 已有页保持原顺序：按当前下标预填槽位，综合结果排在最后
                val slots = mutableMapOf<Uuid, Int>()
                node.messages.forEachIndexed { index, message -> slots[message.id] = index }
                val ctx = RoundTableContext(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    settings = settings,
                    assistant = roleAssistant,
                    conversation = conversation,
                    baseMessages = conversation.messageNodes.take(nodeIndex).map { it.currentMessage },
                    tools = buildRoundTableTools(settings, roleAssistant, conversation, allowReadOnly),
                    mainModel = mainModel,
                    writeMutex = Mutex(),
                    slots = slots,
                )

                session.processingStatus.value =
                    context.getString(R.string.round_table_status_summarizing)
                summarizeRoundTable(
                    ctx = ctx,
                    proposals = proposals,
                    rebuttal = "",
                    sharedMaterials = emptyList(),
                    summaryPrompt = roundTable.summaryPrompt,
                )
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                endReason = GenerationEndReason.CANCELLED
                throw e
            } catch (e: Exception) {
                endReason = GenerationEndReason.FAILED
                Log.e(TAG, "roundTable summarize failed", e)
                addError(
                    e,
                    conversationId,
                    title = context.getString(R.string.round_table_error_title),
                )
            } finally {
                session.processingStatus.value = null
                appEventBus.tryEmit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        reason = endReason,
                        contentPreview = null,
                    )
                )
            }
        }
        session.setJob(job)
    }


    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        var generationEndReported = false
        var lastCheckpointAt = 0L
        runCatching {
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                )
            } catch (error: InvalidMcpServerNamesException) {
                sessions[conversationId]?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            generationLoop.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                // v263：主对话显式打开「异常截断自动续跑」；圆桌/子代理/压缩/翻译不传，
                // 默认 0 = 行为与改前一致（隔离规矩与 v248 那个工具输出上限参数同套）
                truncationAutoResumeMax = settings.truncationAutoResumeMax,
                // v267：续跑思考模式也只在主对话生效
                resumeStrategy = settings.resumeStrategy,
                // v270：重点标色插件也只在主对话生效（默认关；打开才在系统提示词末尾
                // 注入一段中性说明，关闭时零注入、模型不知道该功能存在）
                highlightKeyPoints = settings.displaySetting.highlightKeyPoints,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (settings.enableAgentTools && assistant.enableAgentTools) {
                        addAll(
                            createAgentControlTools(
                                conversationId = conversationId.toString(),
                                manager = agentThreadManager,
                                // v220 修复：透传助手绑定的工作区，子代理才拿得到只读文件工具
                                workspaceId = assistant.workspaceId?.toString(),
                                // v239：主对话原文只有主会话这一侧拿得到，在这里组装好交给工具层。
                                // 主模型可以选择带不带（spawn_agent 的 include_full_context），
                                // 但「想给却给不了」这种情况从此不存在。
                                fullContextProvider = { includeReasoning, maxChars ->
                                    buildAgentFullContext(
                                        conversationId = conversationId,
                                        includeReasoning = includeReasoning,
                                        maxChars = maxChars,
                                    )
                                },
                            )
                        )
                    }
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                forceReview = settings.displaySetting.forceSkillReview,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion { cause ->
                // 可能被取消或异常结束，仍然收束推理状态并只发送一次结束事件。
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 无论正常、异常或取消，都在不可取消上下文中保存最后已收到的内容。
                withContext(NonCancellable) {
                    runCatching {
                        saveConversation(conversationId, updatedConversation)
                    }.onFailure {
                        Log.e(TAG, "Failed to save final generation snapshot: $conversationId", it)
                    }
                }

                val reason = when (cause) {
                    null -> GenerationEndReason.COMPLETED
                    is CancellationException -> GenerationEndReason.CANCELLED
                    else -> GenerationEndReason.FAILED
                }
                generationEndReported = true
                appEventBus.tryEmit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        reason = reason,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(80)?.trim()?.takeIf { it.isNotBlank() },
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 低频检查点：避免切换页面、进程被杀或上游中断时丢掉整段输出。
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheckpointAt >= GENERATION_CHECKPOINT_INTERVAL_MS) {
                            lastCheckpointAt = now
                            runCatching {
                                saveConversation(conversationId, updatedConversation)
                            }.onFailure {
                                Log.e(TAG, "Failed to save generation checkpoint: $conversationId", it)
                            }
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 生成流建立前失败时 onCompletion 不会执行，这里补发一次结束事件。
            if (!generationEndReported) {
                appEventBus.tryEmit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        reason = if (it is CancellationException) {
                            GenerationEndReason.CANCELLED
                        } else {
                            GenerationEndReason.FAILED
                        },
                        contentPreview = null,
                    )
                )
            }
            if (it is CancellationException) throw it
            sessions[conversationId]?.messageQueue?.pause()

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        val defaultTimeoutSeconds = settingsStore.settingsFlow.value.workspaceCommandTimeoutSeconds
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, defaultTimeoutSeconds)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 (照抄 Codex codex-rs/core/src/compact.rs 的本地 compaction) ----

    /**
     * Codex 本地 compaction 流程:
     * 1. 把完整历史 + compaction 提示词作为最后一条 user message 发给模型;
     * 2. 继承当前助手思考档位，并且不限制摘要长度(不设 maxTokens, 提示词里也没有 target token);
     * 3. 若命中上下文超限, 从最旧的一条历史开始丢弃后重试;
     * 4. 摘要取本轮最后一条 assistant 消息, 加上 Codex 的 summary 前缀;
     * 5. 新历史 = 保留的真实用户消息(20k token 预算, 从最新往前取) + 摘要(user message)。
     */
    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String = "",
        modelSource: CompressionModelSource = CompressionModelSource.FIXED,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = when (modelSource) {
            CompressionModelSource.CURRENT_CHAT -> settings.findModelById(
                assistant.chatModelId ?: settings.chatModelId
            )
            CompressionModelSource.FIXED -> settings.findModelById(settings.compressModelId)
        } ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_model))
        val owningProvider = model.findProvider(settings.providers, checkOverwrite = false)
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_provider))
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_provider))
        val providerHandler = providerManager.getProviderByType(provider)

        val history = conversation.currentMessages.filter { it.isValidToUpload() }
        if (history.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        val compactionPrompt = buildCompactionPrompt(
            basePrompt = settings.compressPrompt.ifBlank { DEFAULT_COMPRESS_PROMPT },
            additionalPrompt = additionalPrompt,
        )

        val params = compressionTextGenerationParams(
            model = model,
            assistant = assistant,
        ).copy(
            customBody = scopedCustomBodies(
                assistantBodies = emptyList(),
                modelBodies = model.customBodies,
                providerIds = setOf(owningProvider.id, provider.id),
            ),
        )

        var attemptHistory = history
        var summaryText: String? = null
        // 压缩必须使用流式请求：长自定义提示词可能让模型生成较久，持续返回增量内容可避免网关把连接视为无响应。
        while (summaryText == null) {
            val requestMessages = attemptHistory + UIMessage.user(compactionPrompt)
            val textBuilder = StringBuilder()
            try {
                providerHandler.streamText(
                    providerSetting = provider,
                    messages = requestMessages,
                    params = params,
                ).collect { chunk ->
                    // 2.4.8 起流式事件改为 StreamChunk 密封类；摘要只关心正文增量。
                    if (chunk is StreamChunk.TextDelta) {
                        textBuilder.append(chunk.text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (attemptHistory.isNotEmpty() && CodexCompaction.isContextWindowExceeded(e)) {
                    Log.w(TAG, "compressConversation: context window exceeded, dropping oldest message")
                    attemptHistory = attemptHistory.drop(1)
                    continue
                }
                throw e
            }

            val text = textBuilder.toString().trim()
            if (text.isBlank()) {
                throw IllegalStateException(
                    context.getString(R.string.chat_page_compress_empty_summary)
                )
            }
            summaryText = text
        }

        val newMessageNodes = buildList {
            addAll(
                CodexCompaction.retainedUserMessages(history)
                    .map { UIMessage.user(it).toMessageNode() }
            )
            add(UIMessage.user(CodexCompaction.withSummaryPrefix(summaryText)).toMessageNode())
        }
        val latest = conversationRepo.getConversationById(conversationId) ?: conversation
        saveConversation(
            conversationId,
            latest.copy(
                messageNodes = newMessageNodes,
                chatSuggestions = emptyList(),
            ),
        )
    }

    /**
     * 自动压缩触发判定：autoCompressTriggerTokens 是直接触发值，不再额外乘 90%。
     * 当前聊天模型如果在「特殊模型」表里，则用该模型自己的触发值。
     */
    fun shouldAutoCompressConversation(assistant: Assistant, conversation: Conversation): Boolean {
        val settings = settingsStore.settingsFlow.value
        return shouldAutoCompressAtTokenCount(
            assistant = assistant,
            estimatedTokens = estimateConversationTokens(conversation),
            triggerTokens = resolveAutoCompressTriggerTokens(
                assistant = assistant,
                chatModelId = assistant.chatModelId ?: settings.chatModelId,
                overrides = settings.autoCompressModelOverrides,
            ),
        )
    }

    /**
     * 估算当前对话占用的 token 数。
     * 优先使用服务端返回的真实用量; 否则按 Codex 的 approx_token_count: UTF-8 字节数 / 4 (向上取整)。
     * 实际计算见顶层纯函数 [estimateConversationTokensOf]，界面常显的上下文占用与此共用同一口径。
     */
    fun estimateConversationTokens(conversation: Conversation): Long =
        estimateConversationTokensOf(conversation)

    /**
     * v268：「经典压缩」—— 官方旧版压缩流程（用户要求加回，与智能压缩并存，不是替换）。
     * 用户自选目标 token 数与保留最近消息条数；超长对话分块并发压缩，
     * 摘要作为 user 消息保存、保留的消息跟在后面。
     * 提示词用 [LEGACY_COMPRESS_PROMPT]（官方原文带占位符），**故意不读**
     * settings.compressPrompt —— 设置里存的是 Codex 提示词（v219 起迁移），混用会产生乱码。
     */
    suspend fun compressConversationClassic(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = LEGACY_COMPRESS_PROMPT.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    suspend fun autoCompressForkConversation(
        conversationId: Uuid,
        conversation: Conversation,
        modelSource: AutoCompressModelSource,
    ): Conversation {
        val sourceConversation = conversationRepo.getConversationById(conversationId) ?: conversation
        val forkConversation = sourceConversation.copy(
            id = Uuid.random(),
            // v207: 标题只在原标题后加一个"新"字, 旧写法 "· Auto compact" 在列表里太长
            title = sourceConversation.title.trim().let { if (it.isBlank()) "新对话" else "${it}新" },
            messageNodes = sourceConversation.messageNodes.map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part -> part.copyWithForkedFileUrl() }
                        )
                    }
                )
            },
            isPinned = false,
            chatSuggestions = emptyList(),
            createAt = Instant.now(),
            updateAt = Instant.now(),
        )
        saveConversation(forkConversation.id, forkConversation)

        compressConversation(
            conversationId = forkConversation.id,
            conversation = forkConversation,
            modelSource = when (modelSource) {
                AutoCompressModelSource.CURRENT_CHAT -> CompressionModelSource.CURRENT_CHAT
                AutoCompressModelSource.FIXED -> CompressionModelSource.FIXED
            },
        ).getOrThrow()

        return conversationRepo.getConversationById(forkConversation.id) ?: getConversationFlow(forkConversation.id).value
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessions[newConversation.id]
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }

        // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
        // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
        dispatchNextQueuedMessage(conversationId)
    }

    /**
     * v229：**原子**读改写会话内存态并落库。
     *
     * 旧写法是「先取一份快照 → 在快照上改 → 整份写回」，同一瞬间只要有别的地方也在写
     * 这份对话（初始化、生成标题、生成建议、圆桌写页），后写的那次就会把先写的整份盖掉，
     * 用户刚发出的消息就这么丢了（真机事故：圆桌页在、用户那句话没了）。
     *
     * 这里改成在 [MutableStateFlow.update] 里完成读改写，中间不给别人插手的机会。
     */
    private suspend fun mutateAndSaveConversation(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation {
        val session = getOrCreateSession(conversationId)
        var previous: Conversation? = null
        var next: Conversation? = null
        session.state.update { current ->
            val updated = update(current)
            if (updated.id != conversationId) {
                current
            } else {
                previous = current
                next = updated
                updated
            }
        }
        val before = previous
        val after = next ?: session.state.value
        if (before != null) checkFilesDelete(after, before)

        val exists = conversationRepo.existsConversationById(after.id)
        if (!exists && after.title.isBlank() && after.messageNodes.isEmpty()) return after
        if (!exists) {
            conversationRepo.insertConversation(after)
        } else {
            conversationRepo.updateConversation(after)
        }
        return after
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    /**
     * 停止生成。
     *
     * v215 修正：原来的做法是 `job.cancel()` 之后用 `job.join()` 一直等，
     * 等到 job 真正结束才会通过 invokeOnCompletion 把界面的"正在生成"清掉。
     * 但圆桌里只要有一个模型的连接卡住不响应取消，job 就会长时间停在 Cancelling，
     * 于是用户点右下角的停止按钮"完全没反应"。
     *
     * 现在改成：立刻让界面恢复（结束圆桌面板、清空 job 引用与状态文字），
     * 剩下的收尾放到后台等，僵尸连接自己超时死掉，不再堵住界面。
     */
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val job = session.getJob() ?: return

        // 圆桌：立刻把还在跑的位置标成中断并推给界面，不等卡住的连接
        roundTableRuns[conversationId]?.let { run ->
            run.markAllActiveInterrupted()
            run.setPhase(RoundTableRunPhase.FINISHED)
            publishRoundTableState(run)
        }

        // setJob(null) 会先 cancel 当前 job，再立刻清空引用，界面随即恢复可输入
        session.setJob(null)

        // v2.5.0 合并：官方语义 —— 停止时暂停消息队列，排队的消息不再派发
        synchronized(session) { session.messageQueue.pause() }
        session.processingStatus.value = null

        appScope.launch {
            // 30 秒够正常连接收尾；超过就不再等，避免这里也被僵尸连接拖住
            withTimeoutOrNull(30_000L) { runCatching { job.join() } }
            finishInterruptedPendingTools(conversationId)
        }
    }
}
