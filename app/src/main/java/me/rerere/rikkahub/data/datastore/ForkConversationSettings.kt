package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid

/**
 * v318：创建对话分支时，把「挂在原对话 id 上的设置」带一份到新对话。
 *
 * ## 要解决的问题（用户真机报告）
 * 「打开讨论群组创建分支后，新分支出来的对话讨论群组直接消失/解散掉了」。
 *
 * 根因不是被删了，而是这类设置**按对话 id 存**：
 * - `groupChatConfigs`（v313 起）：成员、一次说几条、补说次数、暂停/启用状态
 * - `conversationModelIds`（v268 起）：这个对话最后用的是哪个模型
 *
 * 而分支会新建一个对话、领一个**全新的 id**，新对话上查不到任何条目，于是
 * 群面板按「没建过群就不渲染」的规则直接隐藏，模型也退回默认值。
 * 原对话的设置与讨论记录都还在，一点没丢。
 *
 * ## 为什么单独做成顶层纯函数
 * ChatService 需要 Android Context，单测里造不出来；只有把这段决策抽出来才能
 * 真跑断言（手册 §7.3：想真单测的逻辑就别放在需要 Android 依赖的类里）。
 *
 * ## 刻意不做的事
 * - **不清理源对话的任何设置**：分支是复制不是搬家，原对话照旧能用群。
 * - **不复制运行态**（谁正在说话、这批跑了几条）：那是内存里的 GroupChatRun，
 *   新对话从 IDLE 开始才是对的 —— 把「第 3/20 条」搬过去会让用户以为还在跑。
 * - **不复制圆桌（RoundTable）的会话**：圆桌每个座位本来就是一条独立对话，
 *   且它的过程记录归专属文件夹管，语义与「分支」不同，不在本轮范围内。
 *
 * @return 需要写入的设置；源对话没有可带的内容时**返回原对象本身**（同一引用），
 *   调用方据此跳过落盘 —— 没有变化就不要触发一次全量写盘。
 */
fun settingsAfterFork(
    settings: Settings,
    sourceConversationId: Uuid,
    newConversationId: Uuid,
): Settings {
    if (sourceConversationId == newConversationId) return settings
    val groupConfig = settings.groupChatConfigs[sourceConversationId]
    // 模型记忆只在「对话独立记忆模型」开着时才有意义：关着时 ChatVM 根本不会读它，
    // 悄悄写一条只会留下一份「看不见的记录」。与 ChatVM 记录侧的口径保持一致。
    val rememberedModelId = settings.conversationModelIds[sourceConversationId]
        ?.takeIf { settings.rememberModelPerConversation }
    if (groupConfig == null && rememberedModelId == null) return settings

    var updated = settings
    if (groupConfig != null) {
        updated = updated.copy(
            groupChatConfigs = updated.groupChatConfigs + (newConversationId to groupConfig)
        )
    }
    if (rememberedModelId != null) {
        updated = updated.copy(
            conversationModelIds = updated.conversationModelIds + (newConversationId to rememberedModelId)
        )
    }
    return updated
}
