package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

// v1035：只投影小窗所看的当前回复 ID，不改变生成请求、工具执行或历史消息。
internal fun ConversationSession.trackFloatingReply(messages: List<UIMessage>) {
    // 只认这一趟输入/输出的末条消息，不能倒找旧助手：新用户消息或压缩检查点之后尚无回复。
    replyMessageId.value = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id
}
