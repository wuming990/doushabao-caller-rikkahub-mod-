package me.rerere.rikkahub.service

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.insertContextCheckpoint

internal enum class ClassicCompressionIssue {
    GENERATING,
    NOTHING_TO_COMPRESS,
    NOT_ENOUGH_MESSAGES,
    PENDING_TOOLS,
    EMPTY_SUMMARY,
    CONVERSATION_CHANGED,
}

internal class ClassicCompressionException(val issue: ClassicCompressionIssue) : IllegalStateException(issue.name)

/**
 * v1019: Official 3.1.1 checkpoint workflow, isolated from our smart/automatic compaction.
 * Only model I/O is supplied by ChatService; the real session owns the latest state and persistence.
 */
internal suspend fun ConversationSession.compressClassicHistory(
    keepRecentMessages: Int = 0,
    generateSummary: suspend (Conversation, List<UIMessage>) -> String,
) {
    if (isGenerating) throw ClassicCompressionException(ClassicCompressionIssue.GENERATING)
    val conversation = state.value
    val nodes = conversation.messageNodes
    val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val endIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
    if (endIndex <= checkpointIndex + 1) {
        throw ClassicCompressionException(
            if (keepRecentMessages > 0) ClassicCompressionIssue.NOT_ENOUGH_MESSAGES
            else ClassicCompressionIssue.NOTHING_TO_COMPRESS,
        )
    }
    // Official rule: only tools inside the compression range must have finished.
    if (nodes.subList(checkpointIndex + 1, endIndex).any { node ->
            node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
        }) {
        throw ClassicCompressionException(ClassicCompressionIssue.PENDING_TOOLS)
    }
    val summary = generateSummary(conversation, conversation.currentMessages.take(endIndex))
    if (summary.isBlank()) throw ClassicCompressionException(ClassicCompressionIssue.EMPTY_SUMMARY)
    currentCoroutineContext().ensureActive()
    // Insert against the latest nodes, never replace history with the pre-request snapshot.
    var insertedCheckpointId: Uuid? = null
    var previousSuggestions: List<String> = emptyList()
    update { latest ->
        if (isGenerating) throw ClassicCompressionException(ClassicCompressionIssue.GENERATING)
        // v1019：摘要只代表请求时那份选中内容；编辑/切分支后不能用旧摘要遮住新内容。
        val latestCheckpoint = latest.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val latestEnd = latest.messageNodes.indexOfFirst { it.id == nodes[endIndex - 1].id } + 1
        val expectedRange = nodes.subList(checkpointIndex.coerceAtLeast(0), endIndex)
            .map { it.id to it.currentMessage }
        val actualRange = if (latestEnd > latestCheckpoint.coerceAtLeast(0)) {
            latest.messageNodes.subList(latestCheckpoint.coerceAtLeast(0), latestEnd)
                .map { it.id to it.currentMessage }
        } else emptyList()
        if (actualRange != expectedRange) throw ClassicCompressionException(ClassicCompressionIssue.CONVERSATION_CHANGED)
        previousSuggestions = latest.chatSuggestions
        val updated = latest.insertContextCheckpoint(nodes[endIndex - 1].id, summary)
            ?: throw ClassicCompressionException(ClassicCompressionIssue.CONVERSATION_CHANGED)
        // v1019：只在新摘要里记下保留消息的用量水位，不改原消息/旧用量。
        // 不能用手机本地时间判断旧账：导入、改时区和重新生成旧消息都会让先后顺序失真。
        val checkpointIndex = updated.messageNodes.indexOfFirst { it.id == nodes[endIndex - 1].id } + 1
        val baselines = buildJsonObject {
            // v1019：保留区里每个节点的所有分支都记一份，切到未选中的旧分支时同样不误算。
            latest.messageNodes.drop(checkpointIndex).flatMap { node -> node.messages }
                .forEach { message -> if (message.usage != null) put(message.id.toString(), classicUsageStamp(message)) }
        }
        val checkpointNode = updated.messageNodes[checkpointIndex]
        insertedCheckpointId = checkpointNode.id
        val checkpoint = checkpointNode.currentMessage.copy(
            parts = listOf(UIMessagePart.Text(summary, metadata = buildJsonObject {
                put(CLASSIC_USAGE_BASELINES_KEY, baselines)
            })),
        )
        updated.copy(
            messageNodes = updated.messageNodes.toMutableList().also {
                it[checkpointIndex] = checkpointNode.copy(messages = listOf(checkpoint))
            },
            chatSuggestions = emptyList(),
        )
    }
    try {
        // v1019：模型请求仍可随时取消；提交一旦开始，先完成这一笔本地保存，避免半提交。
        withContext(NonCancellable) { flush() }
    } catch (e: Exception) {
        // 只撤掉本次尚未落库的摘要，不拿旧会话整份回滚，不丢保存期间新来的消息。
        update { latest ->
            latest.copy(
                messageNodes = latest.messageNodes.filterNot { it.id == insertedCheckpointId },
                chatSuggestions = latest.chatSuggestions.ifEmpty { previousSuggestions },
            )
        }
        throw e
    }
}

/** v1019：智能算法之外的历史读取适配；测试先证明旧读取会重带归档历史。 */
internal fun smartCompressionHistory(conversation: Conversation): List<UIMessage> =
    conversation.currentMessages.limitContext(0).filter { it.isValidToUpload() }

/**
 * v1019：把 ChatService.editMessage 里原有的节点写法原样搬出来（纯搬移，非摘要消息的行为一字不变），
 * 便于用真实数据测「编辑摘要」这条新判据。
 */
internal fun editMessageNodes(
    nodes: List<MessageNode>,
    messageId: Uuid,
    parts: List<UIMessagePart>,
): List<MessageNode>? {
    if (nodes.none { node -> node.messages.any { it.id == messageId } }) return null
    return nodes.map { node ->
        val original = node.messages.firstOrNull { it.id == messageId } ?: return@map node
        if (original.isContextCheckpoint) {
            // v1019：与官方 Conversation.editMessage 同一判据 —— 摘要原地改写。
            // 新建分支会让「当前消息」不再是检查点，limitContext 找不回它，
            // 已归档的整段历史会被重新发给模型（用户以为压好了，实际恢复全量计费）。
            node.copy(
                messages = node.messages.map {
                    if (it.id == messageId) it.copy(parts = parts.carryingCheckpointBaselinesFrom(original)) else it
                }
            )
        } else node.copy(
            messages = node.messages + UIMessage(
                role = node.role,
                parts = parts,
            ),
            selectIndex = node.messages.size
        )
    }
}

private const val CLASSIC_USAGE_BASELINES_KEY = "doushabao_classic_usage_baselines"

private fun classicUsageStamp(message: UIMessage): JsonObject = buildJsonObject {
    put("usage", Json.encodeToJsonElement(message.usage))
    put("last_usage", Json.encodeToJsonElement(message.lastUsage))
    put("finished_at", JsonPrimitive(message.finishedAt?.toString()))
    put("request_count", JsonPrimitive(message.cumulativeRequestCount))
    put("response_ms", JsonPrimitive(message.cumulativeResponseMs))
    put("generation_ms", JsonPrimitive(message.cumulativeGenerationMs))
}

/** New checkpoints use explicit old-usage baselines; imported official checkpoints keep official time fallback. */
/** 摘要正文上的旧用量水位（存在 Text part 的 metadata 里；原消息与数据库字段都不动）。 */
internal fun UIMessage.checkpointBaselines(): JsonObject? =
    parts.filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { it.metadata?.get(CLASSIC_USAGE_BASELINES_KEY) as? JsonObject }

/** v1019：原地改写摘要时把水位带到新正文上，编辑后不许退回本地时间判据。 */
private fun List<UIMessagePart>.carryingCheckpointBaselinesFrom(original: UIMessage): List<UIMessagePart> {
    val baselines = original.checkpointBaselines() ?: return this
    var attached = false
    return map { part ->
        if (part is UIMessagePart.Text && !attached) {
            attached = true
            part.copy(metadata = buildJsonObject {
                part.metadata?.forEach { (key, value) -> put(key, value) }
                put(CLASSIC_USAGE_BASELINES_KEY, baselines)
            })
        } else part
    }
}

internal fun isPreCheckpointUsage(message: UIMessage, checkpoint: UIMessage): Boolean {
    val baselines = checkpoint.checkpointBaselines()
    return if (baselines != null) {
        baselines[message.id.toString()] == classicUsageStamp(message)
    } else {
        message.createdAt < checkpoint.createdAt
    }
}
