package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/**
 * v1024：手动后台智能压缩专用提交。摘要算法不变；只核原内容、会话代次和源存在性。
 * 本地保存失败只撤销本次生成的节点，恢复原记录，同时保留保存期间新来的内容。
 * 经典/自动压缩不调用这里。
 */
internal suspend fun commitManualSmartCompression(
    session: ConversationSession,
    source: Conversation,
    newNodes: List<MessageNode>,
    expectedIncarnation: Int,
    sourceStillExists: suspend () -> Boolean,
) {
    currentCoroutineContext().ensureActive()
    check(sourceStillExists()) { "原对话已不存在，本次压缩未写入" }
    currentCoroutineContext().ensureActive()
    var oldSuggestions: List<String> = emptyList()
    session.update { latest ->
        check(session.incarnation == expectedIncarnation && !session.isGenerating &&
            latest.messageNodes == source.messageNodes) {
            "压缩期间原对话内容已改变，本次压缩未覆盖新内容"
        }
        oldSuggestions = latest.chatSuggestions
        latest.copy(messageNodes = newNodes, chatSuggestions = emptyList())
    }
    try {
        // 与经典模式一致：提交开始后把这一笔本地保存做完，避免用户取消造成半提交。
        withContext(NonCancellable) {
            check(sourceStillExists()) { "原对话已不存在，本次压缩未写入" }
            session.flush()
        }
    } catch (e: Exception) {
        val generated = newNodes.associateBy { it.id }
        val originalIds = source.messageNodes.map { it.id }.toSet()
        session.update { latest ->
            // v1024：本次摘要已被用户整体移除/清空时，不再把旧历史恢复回来。
            if (session.incarnation != expectedIncarnation || latest.messageNodes.none { it.id in generated }) latest else {
                val extras = latest.messageNodes.filter { node ->
                    node.id !in originalIds && (generated[node.id] == null || generated[node.id] != node)
                }
                val restored = source.messageNodes.map { original ->
                    latest.messageNodes.firstOrNull { it.id == original.id } ?: original
                }
                latest.copy(
                    messageNodes = restored + extras,
                    chatSuggestions = latest.chatSuggestions.ifEmpty { oldSuggestions },
                )
            }
        }
        throw e
    }
}
