package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    enum class GenerationEndReason {
        COMPLETED,
        FAILED,
        CANCELLED,
        STOPPED,
    }

    /** 每轮生成只发一次，通知只在这里产生，不在生成过程中常驻。 */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val reason: GenerationEndReason,
        val contentPreview: String?,
    ) : AppEvent()
}
