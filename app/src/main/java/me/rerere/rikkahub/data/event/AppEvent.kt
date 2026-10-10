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

    /**
     * v320：AI 讨论群组**停下来等用户**了。
     *
     * 为什么要单独一个事件而不是复用 [ChatGenerationEnded]：群里一批讨论结束的原因有第三种
     * ——「有成员在用提问卡片等你回话」。那一件事既不是完成、也不是失败、更不是取消，
     * 硬塞进原来的枚举会让通知文案说谎（用户看到的会是「回复已完成」，其实他在等你）。
     *
     * 症状（用户原话）：「弹出提问工具时，已经没有在继续生成了，但我处于后台不会弹出通知」。
     */
    enum class GroupWaitReason {
        /** 有成员在用提问卡片等用户回话 */
        QUESTION,

        /** 这一批按额度说完了，等用户点「继续讨论」 */
        TURN_DONE,

        /**
         * v320：整场结束了 —— 成员被删到不足两个、模型找不到、或群被停用/解散。
         *
         * 为什么不并进 TURN_DONE（审查位 v320 抓的 P2-1，核实为真）：那种场合点「继续讨论」
         * 并不会真的跑起来（引擎第一行就把状态打回 FINISHED），通知却写「等你点继续讨论」
         * = 叫用户去点一个没反应的按钮，文案说谎。状态不同就必须给不同文案，不许糊在一起。
         */
        ENDED,

        /** 某个成员这一轮失败，讨论停在可继续状态 */
        FAILED,
    }

    /** 由群讨论的批次收尾处发出（每批最多一次），交给 ChatNotificationManager 决定要不要弹。 */
    data class GroupChatWaitingForUser(
        val conversationId: Uuid,
        val senderName: String,
        val reason: GroupWaitReason,
        val errorText: String?,
    ) : AppEvent()
}
