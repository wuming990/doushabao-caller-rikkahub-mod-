package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.accumulate
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.agent.model.AgentFinishReason
import me.rerere.rikkahub.data.datastore.ResumeStrategy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
internal const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

/** v289：续跑状态要同时显示实际错误，避免用户只能看到「回复中断」而无法判断原因。 */
private fun formatResumeError(error: Throwable): String {
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
    return message?.let { "${error.javaClass.simpleName}: $it" } ?: error.javaClass.simpleName
}

/**
 * v263：主对话「异常截断自动续跑」的接尾指令。
 * 与圆桌 v223 续跑、子代理 AGENT_RESUME_SUFFIX_INSTRUCTION 同一套思路：
 * 把半截回答原样回灌，明确要求只补缺失的后半段，不重新开头、不重复已写内容。
 * 只进请求、不进展示列表，因此不会落库。
 *
 * v265：开头加「系统自我声明」。真机实锤（本对话亲测）：模型会把这条指令误当成
 * 用户发言（回了一句「你把指令贴回来了」，用户根本没有发过任何东西）。声明用户
 * 未发送新消息、禁止提及本条，堵住误判；「不要重新开头」升级为严禁级——实测模型
 * 在续跑时重新输出了开头段落，造成重复文字。
 *
 * v268：指令换成「继续」。真机实测证明长指令的两条核心职责都失败了——
 * 「严禁重新开头」被无视（v265 开头整段重复两遍）；「系统自我声明」反而制造了
 * 新问题（模型没见过这种像正式文档的东西，把它误当成用户发言，v265 实锤）。
 * 「继续」是模型见得最多的对话模式，无从误读；模型万一还是重写一遍，
 * 由程序级去重（[stripResumeDuplication]）兜底剪掉逐字重复段。
 *
 * v288：v266 的「思考回灌」整体废弃 —— 思考被当普通文本塞进 user 消息，做不到真正
 * 续接思维链（原生思考模型需要原样回传带签名的 reasoning 块），实测只造成多段思考
 * 堆叠与重复打转。现在改成让用户在两种续跑方式里选（见 [ResumeStrategy]）。
 */
private const val TRUNCATION_RESUME_INSTRUCTION: String = "继续"

/**
 * v270：重点标色插件说明（设置→显示→「重点标色」打开时才附加到系统提示词末尾）。
 *
 * 用户拍板的插件式设计（原话）：「不要对主模型参生影响，只是提供给模型一个可额外使用的
 * 插件，而不是像提示词一样注入影响 ai 自身判断」—— 关闭时不注入任何字，模型全程
 * 不知道该功能存在。文案刻意中性：只说明界面有这个能力和语法，明确「可用可不用」，
 * 不指挥模型怎么回答。
 *
 * 红黄绿三色是探测位核实的界面现成渲染能力（richtext/MarkdownNew 的 parseColor
 * 支持命名色，HTML span 会自动走 hasHtml 路径渲染），渲染层零改动。
 */
private const val HIGHLIGHT_KEY_POINTS_INJECTION: String =
    "[界面渲染能力] 本界面支持红/黄/绿重点标注，语法为 HTML 内联标签：" +
        "<span style=\"color:red\">重点</span>（黄/绿替换颜色名即可；" +
        "黄在浅色底上偏淡，建议改用 background-color:yellow 荧光笔效果）。" +
        "仅在确实有助于阅读时使用，不用完全可以。"

/**
 * v268：程序级安全去重的最小匹配长度。低于它的重合不剪（可能是正常的巧合）。
 */
private const val RESUME_DUP_MIN_CHARS = 60

/**
 * v268：程序级安全去重。
 * 模型不遵守「继续」的语义、把旧内容重新写一遍时（v265 真机实锤），
 * 检测新文本是否以「旧文本的前缀」（从头重写）或「旧文本的后缀」（复述结尾）
 * 逐字开头（≥[RESUME_DUP_MIN_CHARS] 字），是则剪掉重复段。
 * 安全边界：只剪逐字匹配的部分，正常续写的新内容不会被误伤。
 */
internal fun stripResumeDuplication(oldText: String, newText: String): String {
    if (oldText.length < RESUME_DUP_MIN_CHARS || newText.length < RESUME_DUP_MIN_CHARS) return newText
    val maxCheck = minOf(oldText.length, newText.length, 4_000)
    var best = 0
    // 情形1：模型从头重写（新文本开头 == 旧文本开头）
    for (len in maxCheck downTo RESUME_DUP_MIN_CHARS) {
        if (newText.regionMatches(0, oldText, 0, len)) {
            best = len
            break
        }
    }
    // 情形2：续写前先把旧结尾复述了一遍（旧文本后缀 == 新文本前缀）
    if (best < RESUME_DUP_MIN_CHARS) {
        for (len in maxCheck downTo RESUME_DUP_MIN_CHARS) {
            if (oldText.regionMatches(oldText.length - len, newText, 0, len)) {
                best = len
                break
            }
        }
    }
    if (best >= RESUME_DUP_MIN_CHARS) {
        Log.w(TAG, "Resume dedup: trimmed $best chars of verbatim duplication")
        return newText.drop(best)
    }
    return newText
}

/** v289：只取本次模型请求新增的文字，避免跨工具步骤把旧文字再次塞进「继续」请求。 */
internal fun UIMessage.textSincePartCount(basePartCount: Int): String =
    parts.drop(basePartCount.coerceIn(0, parts.size))
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

/** v289：续跑时只移除本次请求新增、尚未执行的工具调用；已执行工具及结果全部保留。 */
internal fun UIMessage.removeNewUnexecutedToolsAfter(basePartCount: Int): UIMessage {
    if (role != MessageRole.ASSISTANT || basePartCount >= parts.size) return this
    val start = basePartCount.coerceAtLeast(0)
    val newParts = parts.drop(start).filterNot {
        it is UIMessagePart.Tool && !it.isExecuted
    }
    if (newParts.size == parts.size - start) return this
    return copy(parts = parts.take(start) + newParts)
}

/** v268：消息里全部 Text part 的直接拼接（无分隔符），去重基线用。 */
private fun UIMessage.concatTextParts(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

/**
 * v268：对续跑轮新增的 parts 做去重。
 * [preText]/[prePartCount] 是续跑轮开始前的快照；本轮新增 = parts.drop(prePartCount)。
 * 命中重复时，把本轮新增的多个 Text part 折叠成一个（内容为去重后文本），
 * 非 Text part（思考等）原位保留。无重复时原样返回（保持对象同一性）。
 */
private fun UIMessage.stripResumeRoundDuplication(preText: String?, prePartCount: Int?): UIMessage {
    if (preText == null || prePartCount == null || prePartCount >= parts.size) return this
    val newParts = parts.drop(prePartCount)
    val newText = newParts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    if (newText.length < RESUME_DUP_MIN_CHARS) return this
    val deduped = stripResumeDuplication(preText, newText)
    if (deduped == newText) return this
    val rebuilt = mutableListOf<UIMessagePart>()
    var textPlaced = false
    newParts.forEach { part ->
        if (part is UIMessagePart.Text) {
            if (!textPlaced) {
                rebuilt.add(UIMessagePart.Text(deduped))
                textPlaced = true
            }
        } else {
            rebuilt.add(part)
        }
    }
    return copy(parts = parts.take(prePartCount) + rebuilt)
}

/**
 * v263：把消息里**相邻**的 Text part 合并成一个。
 * 续跑轮新增的文字由 StreamChunkHandler 作为新的 Text part 追加（它按流的 id 建 part），
 * 不合并的话接缝处会显示成两个段落。只合并 Text-Text 相邻项，不动 Reasoning/工具/图片。
 */
private fun UIMessage.mergeAdjacentTextParts(): UIMessage {
    val merged = mutableListOf<UIMessagePart>()
    parts.forEach { part ->
        val last = merged.lastOrNull()
        if (part is UIMessagePart.Text && last is UIMessagePart.Text) {
            merged[merged.size - 1] = last.copy(text = last.text + part.text)
        } else {
            merged.add(part)
        }
    }
    return copy(parts = merged)
}

/**
 * v266：两条续跑路径（截断 / 中断）共用的回灌输入：原始输入 + 半截正文 + 「继续」。
 * v288：不再附带上一段思考（思考回灌已整体废弃，见 [TRUNCATION_RESUME_INSTRUCTION]）。
 */
internal fun buildResumeProviderInput(
    internalMessages: List<UIMessage>,
    partialText: String,
    model: Model,
): List<UIMessage> = internalMessages +
    // v295：空正文催答（商汤 deepseek「思考完不给正文」实锤）时不拼空 assistant 消息 ——
    // 部分网关（含商汤）对空 content 的 assistant 消息行为不明示、可能直接拒收；
    // 此时只追加一条「继续」催答即可。
    (
        if (partialText.isNotBlank()) {
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(partialText)),
                    modelId = model.id,
                ),
            )
        } else {
            emptyList()
        }
    ) + listOf(
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(TRUNCATION_RESUME_INSTRUCTION)),
        ),
    )

/**
 * Assistant/model custom Body entries are legacy-compatible by default, but can be
 * explicitly excluded per provider. Most providers support a given key, so the UI
 * asks for the (few) providers that must NOT receive it.
 */
internal fun scopedCustomBodies(
    assistantBodies: List<CustomBody>,
    modelBodies: List<CustomBody>,
    providerIds: Set<Uuid>,
): List<CustomBody> = buildList {
    addAll(assistantBodies.filter { body -> body.shouldSendToProvider(providerIds) })
    addAll(modelBodies.filter { body -> body.shouldSendToProvider(providerIds) })
}

private fun CustomBody.shouldSendToProvider(requestProviderIds: Set<Uuid>): Boolean {
    // Legacy whitelist from the first mod2 build (already migrated on settings load,
    // kept as a safety net for configs that were never re-saved).
    providerIds?.let { selected ->
        return selected.any(requestProviderIds::contains)
    }
    excludedProviderIds?.let { excluded ->
        return excluded.none(requestProviderIds::contains)
    }
    return true
}

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        /**
         * v248：单个工具返回的字符上限。
         *
         * ⚠️ **默认值必须保持上游那个 32KB，主对话绝对不许被改动。**
         * 用户明确要求：「主模型绝对不能乱动，不能做出任何限制」。主对话不传这个参数，
         * 因此行为与上游逐字节一致 —— 被截断时完整内容会存成文件，主对话有终端可以 grep 取回。
         *
         * 只有**子代理**会显式传值（它没有终端、拿不回被截掉的部分，而且跑的是廉价模型、
         * 结果只回一份摘要，多读一些不污染主对话）。子代理传的是设置里那个可调档位。
         */
        toolOutputCharLimit: Int = MAX_TOOL_OUTPUT_CHARS,
        /**
         * v263：主对话「异常截断自动续跑」开关。
         *
         * ⚠️ 默认 0 = 关闭，所有调用方（圆桌 / 子代理 / 压缩 / 翻译等）行为与改前完全一致，
         * 与上面 toolOutputCharLimit 同一套「默认关、谁要谁显式打开」的隔离规矩。
         * **只有 ChatService 的主对话路径**显式传 settings.truncationAutoResumeMax。
         * 子代理不传：它自己已有一套截断续跑（AgentThreadManager），两套叠加会重复花钱。
         */
        truncationAutoResumeMax: Int = 0,
        /**
         * v288：续跑方式（仅 truncationAutoResumeMax > 0 时生效）。
         * CONTINUE = 回灌半截 + 发「继续」；REGENERATE = 原样重发同一请求、重来最后那一段。
         * 撞输出上限那条路强制走 CONTINUE（重新生成必然再撞同一上限）。
         * 默认 CONTINUE（原有行为）；只有主对话路径传 settings.resumeStrategy。
         */
        resumeStrategy: ResumeStrategy = ResumeStrategy.CONTINUE,
        /**
         * v270：重点标色插件（默认 false = 零注入）。
         *
         * 打开时在系统提示词**末尾**附加一段中性说明（界面支持红/黄/绿标注，可用可不用），
         * 关闭时模型全程不知道该功能存在 —— 用户原话：「只是提供给模型一个可额外使用的
         * 插件，而不是像提示词一样注入影响 ai 自身判断」。
         * 与 truncationAutoResumeMax 同一套隔离规矩：只有 ChatService 主对话路径显式传值，
         * 圆桌/子代理/压缩/翻译等其余路径不传，行为与改前逐字节一致。
         */
        highlightKeyPoints: Boolean = false,
    ): Flow<GenerationChunk> = flow {
        val owningProvider = model.findProvider(settings.providers, checkOverwrite = false)
            ?: error("Provider not found")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        val requestProviderIds = setOf(owningProvider.id, provider.id)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = tools,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    requestProviderIds = requestProviderIds,
                    truncationAutoResumeMax = truncationAutoResumeMax,
                    resumeStrategy = resumeStrategy,
                    highlightKeyPoints = highlightKeyPoints,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = toolCalls.map { tool ->
                    val toolDef = tools.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = tools.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(
                                    tool.toolCallId,
                                    result,
                                    hasShellAccess,
                                    toolOutputCharLimit,
                                )
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        requestProviderIds: Set<Uuid>,
        truncationAutoResumeMax: Int = 0,
        // v288：续跑方式（CONTINUE=发「继续」/ REGENERATE=重发同一请求重来最后一段）。
        // 默认 CONTINUE = 原有行为，主对话显式传参，其余路径不受影响。
        resumeStrategy: ResumeStrategy = ResumeStrategy.CONTINUE,
        /** v270：重点标色插件（见 generateText 处 KDoc；默认 false = 零注入） */
        highlightKeyPoints: Boolean = false,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                // v270：重点标色插件 —— 只在主对话显式打开时附加这一段中性说明（默认 false，
                // 其余路径不传，行为与改前逐字节一致）。刻意放在系统提示词最末尾、
                // 刻意一句话说完：这是「告知有此工具」的插件说明，不是行为指令。
                if (highlightKeyPoints) {
                    appendLine()
                    append(HIGHLIGHT_KEY_POINTS_INJECTION)
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = scopedCustomBodies(
                assistantBodies = assistant.customBodies,
                modelBodies = model.customBodies,
                providerIds = requestProviderIds,
            ),
            sessionId = conversationId?.toString(),
        )
        // v297：「真实消耗」累计。工具循环每调用一次工具就重进一次本函数，所以基线取
        // 助手消息上已有的 cumulativeUsage（已含之前几步）；本次调用内的每一轮请求都往
        // roundsUsage 上加 —— 成功收尾的轮走 foldRoundUsage，异常中断的轮走 catch 里的
        // 只加计数器那条路（Claude/Gemini 中断前就已报过用量）。
        // 写入一律用「基线 + 本次累计」的绝对值覆盖，不写增量：任何一轮重算都得到同一总数，
        // 既不会重复计数，也不会因为「重新生成」丢掉半截而漏计。
        val entryCumulativeUsage = messages.lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }?.cumulativeUsage
        var roundsUsage: TokenUsage? = null

        /**
         * 把本轮服务商报出的用量计入这条回答的 cumulativeUsage。
         *
         * 服务商这一轮压根没报用量（round 为 null）时**什么都不做** —— 宁可不加，
         * 也不把上一轮的数重复计入。写法照 v269 续跑次数记账那套：改完立刻 onUpdateMessages，
         * 否则 break 出循环后这次写入不会落库。
         */
        suspend fun foldRoundUsage(round: TokenUsage?) {
            if (round != null) {
                roundsUsage = roundsUsage.accumulate(round)
            }
            val total = roundsUsage ?: return
            val idx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (idx < 0) return
            messages = messages.toMutableList().also { list ->
                list[idx] = list[idx].copy(
                    cumulativeUsage = entryCumulativeUsage.accumulate(total)
                )
            }
            onUpdateMessages(messages)
        }

        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                var responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                // v270：续跑次数记累计账。truncatedResumeCount 每次生成从 0 起算，
                // 而同一条消息可能被「重新生成 / 掉线后重跑」再次送进生成 —— 上一轮已
                // 记进消息的次数若被本轮数字直接覆盖，界面就会出现「续跑 2 次」掉一次线
                // 后倒退成「续跑 1 次」的怪事（用户真机实锤）。开始时把消息已有次数抄作
                // 基线，之后每次写入一律用 基线 + 本轮次数，只增不减。
                val baselineResumeCount = responseBaseMessages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.ASSISTANT }?.resumeCount ?: 0
                var retryCount = 0
                // v263：主对话「异常截断自动续跑」。流正常收尾但结束原因是「输出长度上限被掐断」
                // （finishReason 含 length/max_tokens 等）时，把半截回答回灌给模型让它接着写。
                // 次数上限来自设置 truncationAutoResumeMax（0 = 关闭）。只认 isTruncated：
                // 以工具调用收尾在主对话是合法中间态（外层工具循环会接着跑），与子代理语义不同，
                // 不用 isUnfinished。非流式路径不续跑（主对话默认流式输出）。
                var truncatedResumeCount = 0
                // v268：去重保险——续跑轮开始前的正文与 parts 数量快照
                var preResumeText: String? = null
                var preResumePartCount: Int? = null
                // 本轮发给 Provider 的消息。续跑时在末尾追加「半截回答 + 接尾指令」；
                // 断网重试则复用同一份输入整请求重放（与 responseBaseMessages 的快照语义对应）。
                var providerInput = internalMessages
                // v289：记录本次请求开始时助手消息已有的 parts；续跑只回灌之后新生成的文字，
                // 跨工具步骤时不再把旧回答重复塞给模型。
                val requestStartPartCount = responseBaseMessages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.ASSISTANT }?.parts?.size ?: 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = providerInput,
                            params = params
                        ).collect { chunk ->
                            try {
                                // v263：网络重试或截断续跑的提示，收到新内容后即消失
                                if (retryCount > 0 || truncatedResumeCount > 0) {
                                    processingStatus.value = null
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages
                        // v297：本轮服务商报的用量计入「真实消耗」（本轮没报用量则不动这本账）
                        foldRoundUsage(streamChunkHandler.reportedUsage)
                        // v263：续跑轮新增的文字是新的 Text part，先把相邻 Text 合并，
                        // 接缝处才不会在界面上显示成两个段落。
                        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                        if (assistantIndex >= 0 && truncatedResumeCount > 0) {
                            // v268：先做程序级去重——模型不遵守「继续」语义重新开头时，
                            // 剪掉本轮新增内容里逐字复述旧内容的部分
                            val dedupedMsg = messages[assistantIndex]
                                .stripResumeRoundDuplication(preResumeText, preResumePartCount)
                            if (dedupedMsg !== messages[assistantIndex]) {
                                messages = messages.toMutableList().also { it[assistantIndex] = dedupedMsg }
                            }
                            messages = messages.toMutableList().apply {
                                this[assistantIndex] = this[assistantIndex].mergeAdjacentTextParts()
                                    // v269：把累计续跑次数写进助手消息 —— 随 onUpdateMessages 落库，
                                    // 重开 App 后消息下方仍显示「续跑 N 次」。
                                    // v270：改为 基线 + 本轮 —— 同一条消息被重跑时上一轮已记的
                                    // 次数不被清零，数字只增不减（修复「2 次掉线后变回 1 次」）。
                                    .copy(resumeCount = baselineResumeCount + truncatedResumeCount)
                            }
                            onUpdateMessages(messages)
                        }
                        // v263：本轮流正常收尾，检查是否「没写完就被掐断」。是且还有额度 → 续跑。
                        val lastAssistant = messages.getOrNull(assistantIndex)
                        val partialText = lastAssistant
                            ?.textSincePartCount(requestStartPartCount)
                            .orEmpty()
                        val maxTruncationResumes = truncationAutoResumeMax.coerceIn(0, 99)
                        // v269：无声截断 —— 上游把流关了，却既没发协议终止信号
                        // （[DONE] / message_stop / response.completed），也没给任何结束原因。
                        // Claude 的终止信号 message_stop 单独排在正文之后，正文中途断线必然漏掉它，
                        // 而此时 stop_reason 也还没到，两个条件同时成立，命中精准。
                        //
                        // 两个条件必须「同时」满足，缺一不可：
                        // · 只看 truncatedWithoutSentinel —— Gemini 的 SSE 不发终止信号、正常收尾
                        //   也是这个形态，会把它每次回答都误判成截断，白烧一次请求；
                        // · 中转站省掉 [DONE] 但给了 finish_reason=stop 时内容通常是完整的，
                        //   有结束原因就不算无声截断。
                        val silentlyTruncated = lastAssistant?.truncatedWithoutSentinel == true &&
                            lastAssistant.finishReason.isNullOrBlank()
                        // v269：策略拦截（内容审核 / 安全 / 拒答）续写也会被再次拦下，不续。
                        val blockedByPolicy =
                            AgentFinishReason.isBlockedByPolicy(lastAssistant?.finishReason)
                        // v295：本轮零输出检测 —— 正文与工具调用都没有新增（思考不算产出）。
                        // 商汤 deepseek-v4-pro 真机实锤：模型思考 250 秒后正文为空、也没再调
                        // 工具，外层「无待执行工具=说完」的判定直接收工定格，用户看到
                        // 「输出完了但一个字没有」。这里把空正文接进续跑通道：发「继续」
                        // 催模型把话说完（partialText 为空时构造器不拼空 assistant 消息）。
                        val noToolThisRound = lastAssistant?.parts
                            ?.drop(requestStartPartCount)
                            ?.none { it is UIMessagePart.Tool } ?: true
                        val emptyThisRound = partialText.isBlank() && noToolThisRound
                        if (
                            // v272：预算按「基线+本轮」累计判定 —— 同一消息跨次生成的
                            // 续跑总次数封顶在设置值，与弹窗、底部统计同口径
                            baselineResumeCount + truncatedResumeCount < maxTruncationResumes &&
                            !blockedByPolicy &&
                            (
                                AgentFinishReason.isTruncated(lastAssistant?.finishReason) ||
                                    silentlyTruncated ||
                                    emptyThisRound
                                ) &&
                            (partialText.isNotBlank() || emptyThisRound)
                        ) {
                            // v289：只有确定要续跑时才移除本次新增且未执行的工具调用；
                            // 正常完成的工具调用必须原样保留并交给外层执行。
                            if (assistantIndex >= 0) {
                                val currentAssistant = messages[assistantIndex]
                                val cleanedAssistant = currentAssistant
                                    .removeNewUnexecutedToolsAfter(requestStartPartCount)
                                if (cleanedAssistant !== currentAssistant) {
                                    messages = messages.toMutableList().also {
                                        it[assistantIndex] = cleanedAssistant
                                    }
                                    onUpdateMessages(messages)
                                }
                            }
                            val resumeAssistant = messages.getOrNull(assistantIndex)
                            val resumePartialText = resumeAssistant
                                ?.textSincePartCount(requestStartPartCount)
                                .orEmpty()
                            truncatedResumeCount++
                            // v272：续跑发生瞬间就写账。只靠「本轮最终正常收尾」那次写入的话，
                            // 最后一次续跑若以异常/停止收场，这笔账就永远丢了（真机实锤：
                            // 底部停在「续跑 2 次」不再涨）。弹窗同时改报累计值（基线+本轮），
                            // 不再出现「已续跑 2 次又弹 1/99」的观感错位。
                            if (assistantIndex >= 0) {
                                messages = messages.toMutableList().also { list ->
                                    list[assistantIndex] = list[assistantIndex].copy(
                                        resumeCount = baselineResumeCount + truncatedResumeCount
                                    )
                                }
                                onUpdateMessages(messages)
                            }
                            processingStatus.value = context.getString(
                                R.string.chat_generation_truncation_resuming,
                                baselineResumeCount + truncatedResumeCount,
                                maxTruncationResumes,
                            )
                            Log.w(
                                TAG,
                                "Response truncated (finishReason=${lastAssistant?.finishReason}, " +
                                    "silentlyTruncated=$silentlyTruncated), " +
                                    "auto continuing ($truncatedResumeCount/$maxTruncationResumes)",
                            )
                            // v263：续跑是一趟全新的模型请求，网络重试预算重新计满 3 次，
                            // 不与上一轮共享（否则连续续跑时后面几轮可能一次断网就放弃）。
                            retryCount = 0
                            // v288：撞输出上限这条路强制走「继续」——「重新生成」必然再撞
                            // 同一个上限，是必然失败而不是取舍，所以不看用户选的 resumeStrategy。
                            // 下一轮请求输入 = 原始输入 + 截至目前的完整半截回答 + 「继续」。
                            // 每轮都从 internalMessages 重建，不累加 —— 连续多轮被掐断时，
                            // 半截内容才不会在前几轮的输入里重复出现。这两条只进请求、
                            // 不进展示列表，因此不会落库。
                            providerInput = buildResumeProviderInput(
                                internalMessages = internalMessages,
                                partialText = resumePartialText,
                                model = model,
                            )
                            // 展示基线推进到当前半截回答：新一轮增量会追加进同一条助手消息
                            //（StreamChunkHandler 对末尾助手消息做追加），界面不会多出一页；
                            // 续跑途中若再遇断网，网络重试也从这份基线整请求重放。
                            responseBaseMessages = messages
                            // v268：去重快照（本轮已写的正文与 parts 数量）
                            preResumeText = resumeAssistant?.concatTextParts()
                            preResumePartCount = resumeAssistant?.parts?.size
                            continue
                        }
                        break
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        // v264：用户主动停止必须原样上抛，绝不能被当成「异常中断」自动续跑。
                        // 取消信号本身也原样上抛（协程未取消却收到取消信号的罕见竞态不续跑）。
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        // v297：这一轮虽然断了，但服务商可能**已经报过用量** —— Claude 在
                        // message_start 就报 input_tokens、Gemini 每个分片都带 usageMetadata。
                        // 不在中断路径上记账，注释与界面文案承诺的「中断/重跑的轮也已计入」就是假的
                        // （审查位抓到）。这里只加进本轮计数器 roundsUsage，**不在此处写消息落库**：
                        // 中断时 messages 还是上一轮的旧快照，此刻 onUpdateMessages 会把界面上
                        // 已显示的半截内容回退掉（v289 铁律区）。下一次成功收尾的轮会用
                        // 「基线 + roundsUsage」的绝对值把它一并写进去。用户主动取消在上面已上抛。
                        streamChunkHandler.reportedUsage?.let { roundsUsage = roundsUsage.accumulate(it) }
                        // v264：异常中断自动续跑 —— 流中途断掉（连接断 / 服务商临时故障）且已经
                        // 写出一部分内容时，不丢弃已写内容，从断点接着写。与长度上限截断
                        // （v263）同一条续跑通道、共用同一个次数设置。
                        // v292（用户拍板「全拆黑名单」）：不再按错误分类过滤——除上面的取消
                        // 保护外，任何服务商报错（含密钥错/余额类）都按中断续跑处理，
                        // 烧到次数用尽为止；原始错误文案始终展示给用户。
                        val interruptedMaxResumes = truncationAutoResumeMax.coerceIn(0, 99)
                        val interruptedMessage = attemptMessages.lastOrNull {
                            it.role == MessageRole.ASSISTANT
                        }
                        val interruptedPartial = interruptedMessage
                            ?.textSincePartCount(requestStartPartCount)
                            .orEmpty()
                        if (
                            // v272：预算按「基线+本轮」累计判定（与截断路径同口径）
                            baselineResumeCount + truncatedResumeCount < interruptedMaxResumes &&
                            // v292：不再排除任何错误分类（全拆黑名单，见上方注释）
                            interruptedPartial.isNotBlank()
                        ) {
                            truncatedResumeCount++
                            // v288：本轮按哪种方式接回来，下面状态提示、日志、分支三处共用这一个判断
                            val regenerating = resumeStrategy == ResumeStrategy.REGENERATE
                            processingStatus.value = context.getString(
                                if (regenerating) {
                                    R.string.chat_generation_interrupted_regenerating
                                } else {
                                    R.string.chat_generation_interrupted_resuming
                                },
                                baselineResumeCount + truncatedResumeCount,
                                interruptedMaxResumes,
                                formatResumeError(error),
                            )
                            Log.w(
                                TAG,
                                "Stream interrupted (${error.javaClass.simpleName}: ${error.message}), " +
                                    (
                                        if (regenerating) "regenerating last output"
                                        else "resuming from partial output"
                                        ) +
                                    " ($truncatedResumeCount/$interruptedMaxResumes)",
                                error,
                            )
                            if (regenerating) {
                                // v288 方式 2「重新生成最后一次输出」。照官方 awaitNetworkRetryOrThrow
                                // 的整请求重放语义：providerInput 一个字不改（还是产生这半截的那个
                                // 请求），展示基线也不推进 —— 循环开头 attemptMessages =
                                // responseBaseMessages 就会把写坏的半截丢掉，只重来最后那一段，
                                // 前面已完成的内容（含之前几轮「继续」的成果）不受影响。
                                //
                                // v272 的「续跑发生瞬间就记账」在这条路上必须记到**基线**那份消息上：
                                // 记到即将被丢弃的半截上等于没记（真机表现就是「续跑 N 次」不涨）。
                                // 也因此这条路只推一次界面更新 —— 直接推退回后的基线，
                                // 不先推一遍马上要丢掉的半截（那会白写一次库、界面还闪一下）。
                                val baseIdx =
                                    responseBaseMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
                                if (baseIdx >= 0) {
                                    responseBaseMessages = responseBaseMessages.toMutableList().also { list ->
                                        list[baseIdx] = list[baseIdx].copy(
                                            resumeCount = baselineResumeCount + truncatedResumeCount
                                        )
                                    }
                                }
                                onUpdateMessages(responseBaseMessages)
                                // 半截已丢弃 = 没有可去重的旧文本
                                preResumeText = null
                                preResumePartCount = null
                            } else {
                                // v289：只有真正选择 CONTINUE 时，才把本轮未执行的工具调用清掉并
                                // 保留文字续写；REGENERATE 直接回到 responseBaseMessages，避免多推一次半截。
                                val interruptedIdx =
                                    attemptMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
                                if (interruptedIdx >= 0) {
                                    val currentAssistant = attemptMessages[interruptedIdx]
                                    val cleanedAssistant = currentAssistant
                                        .removeNewUnexecutedToolsAfter(requestStartPartCount)
                                    attemptMessages = if (cleanedAssistant !== currentAssistant) {
                                        attemptMessages.toMutableList().also {
                                            it[interruptedIdx] = cleanedAssistant
                                        }
                                    } else {
                                        attemptMessages
                                    }
                                    attemptMessages = attemptMessages.toMutableList().also { list ->
                                        list[interruptedIdx] = list[interruptedIdx].copy(
                                            resumeCount = baselineResumeCount + truncatedResumeCount
                                        )
                                    }
                                    onUpdateMessages(attemptMessages)
                                }
                                // 已写的半截内容保留为展示基线：续写增量会追加进同一条助手消息；
                                // 下一轮请求输入从 internalMessages 重建（本次新增文字 + 「继续」）。
                                responseBaseMessages = attemptMessages
                                // v268：去重快照（含被中断那轮已写出的部分）
                                val continuedMessage = attemptMessages.lastOrNull {
                                    it.role == MessageRole.ASSISTANT
                                }
                                preResumeText = continuedMessage?.concatTextParts()
                                preResumePartCount = continuedMessage?.parts?.size
                                providerInput = buildResumeProviderInput(
                                    internalMessages = internalMessages,
                                    partialText = interruptedPartial,
                                    model = model,
                                )
                            }
                            retryCount = 0
                            continue
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            allowUpstreamErrors = truncationAutoResumeMax > 0,
                            // v273 合并上游 2.4.16：官方新增「自动重试」总开关；与二改的
                            // allowUpstreamErrors（续跑开启时把 429/5xx 等服务商临时故障也纳入重试）
                            // 互补共存——enabled=false 时任何网络重试都不做（官方语义），
                            // enabled=true 时 IOException 照旧，服务商临时故障按二改规则扩展。
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    allowUpstreamErrors = truncationAutoResumeMax > 0,
                    enabled = settings.networkSetting.enableAutoRetry,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                }
                messages = messages.handleTextGenerationResult(result = result, model = model)
                // v297：非流式路径同样计入真实消耗（一次请求就是一轮）
                foldRoundUsage(result.usage)
                onUpdateMessages(messages)
            }
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        allowUpstreamErrors: Boolean = false,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    allowUpstreamErrors = allowUpstreamErrors,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        allowUpstreamErrors: Boolean = false,
        // v273 合并上游 2.4.16：官方新增「自动重试」总开关；与二改的
        // allowUpstreamErrors（续跑开启时把服务商临时故障也纳入重试）互补共存——
        // enabled=false 时任何网络重试都不做（官方语义）。
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        // v264（二改）：主对话路径（allowUpstreamErrors=true，即续跑设置开启时）把服务商故障
        // 纳入整趟重试；圆桌/子代理/压缩走默认 false，行为与官方完全一致。
        // v292（用户拍板「全拆黑名单」）：不再看错误分类——除用户主动取消（本函数开头的
        // ensureActive 与调用方的 CancellationException 保护）外，任何服务商报错（含
        // 401/402/403/404 等「重试必无用」的错误）一律按临时故障整趟重试（3 次上限 + 指数
        // 退避），重试用尽后交给外层按用户设置的续跑次数继续，直到次数耗尽才最终报错停下。
        val recoverable = error is IOException || allowUpstreamErrors
        if (!enabled || !recoverable || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        // v264：服务商临时故障（非 IOException）没有对应的分类文案，直接展示原始错误文本，
        // 用户能看见「429 Too Many Requests」这类具体原因。
        val retryReason = if (error is IOException) {
            getNetworkErrorMessage(error)
        } else {
            error.message ?: error.javaClass.simpleName
        }
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            retryReason,
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    // v292（用户拍板「全拆黑名单」）：原先的 classifyUpstreamError（遍历 cause 链判定
    // FATAL/RECOVERABLE/UNKNOWN、任一层 FATAL 即拦截）随黑名单一起移除——续跑不再看
    // 错误分类。错误分类本身（AgentModels.kt 的 AgentErrorKind）仍被子代理等其他路径使用。

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        charLimit: Int = MAX_TOOL_OUTPUT_CHARS,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= charLimit.coerceAtLeast(4 * 1024)) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    if (hasShellAccess) {
                        appendLine("Full output saved to: /tool_outputs/$fileName")
                        appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                        appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    } else {
                        // v209: 圆桌等只读会话没有 shell，拿不回完整内容，
                        // 与其把整块塞进上下文（费用暴涨），不如让模型把问题问小一点。
                        appendLine("Tool output was too large to include. Ask a narrower question or request a smaller specific part.")
                    }
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
