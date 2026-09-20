package me.rerere.rikkahub.ui.components.ui

/**
 * v307：「思考/工具卡片」在完全收起模式下的可见性规则。
 *
 * 背景（用户口径）：v306 的「思考卡片单步可收起」在折叠时把**所有**步骤都藏了 ——
 * 连正在思考、正在跑工具的那一步也一起藏，生成过程中看不到模型此刻在干什么；
 * 而「工具等审批」「询问用户等回答」这两类按钮就长在卡片里，模型发出这类请求时
 * 生成任务已经结束（`loading` 为 false），于是按钮默认也被藏起来 ——
 * 虽然顶部控制条仍可点开找回，但用户根本不知道该点，等于「看不见按钮」。
 *
 * v307 规则（开关打开时）：
 * - 生成中：仍显示尾部 [visibleThinkingSteps] 步（与官方折叠态一致），能实时看到思考与工具调用；
 * - 生成结束：全部收起，只留顶部控制条（这才是这个开关本来的目的）；
 * - 卡片里有「正在等用户确认 / 等用户回答」的步骤时：这些步骤必须落在可见范围内
 *   （见 [minVisibleTailCount]），否则用户找不到按钮。
 *
 * 这里是纯函数（不依赖 Compose / Android），真单测见 ThinkingCardVisibilityTest。
 */

/**
 * 折叠态下是否要显示尾部步骤。
 *
 * @param messageLoading 这条消息是否正在生成（ChatList 只给最后一条消息传 true）
 * @param isLastBlock 这个思考块是不是消息里的最后一块（流式内容总是追加在最后一块）
 * @param hasPendingInteraction 这个思考块里是否有「等用户确认 / 等用户回答」的步骤
 */
internal fun shouldPreviewThinkingTail(
    messageLoading: Boolean,
    isLastBlock: Boolean,
    hasPendingInteraction: Boolean,
): Boolean = hasPendingInteraction || (messageLoading && isLastBlock)

/**
 * 算出真正要渲染的步骤列表。
 *
 * @param expanded 用户是否手动点开了「显示全部」（手动展开一律尊重，不自动收回）
 * @param canCollapse 是否显示顶部折叠控制条
 * @param fullyCollapsible 「思考卡片单步可收起」开关
 * @param previewTail 折叠态下是否显示尾部步骤（见 [shouldPreviewThinkingTail]）；仅开关打开时有意义
 * @param minVisibleTailCount 折叠态下**至少**要显示到从末尾数第几步（含）。
 *   用来保证「正在等用户操作的步骤」不被藏住：哪怕它排在倒数第三、第四步，
 *   也要求显示到它为止。0 = 无额外要求。仅开关打开时有意义。
 * @param collapsedVisibleCount 折叠时保留的尾部步骤数（官方默认 2）
 */
internal fun <T> visibleThinkingSteps(
    steps: List<T>,
    expanded: Boolean,
    canCollapse: Boolean,
    fullyCollapsible: Boolean,
    previewTail: Boolean,
    minVisibleTailCount: Int,
    collapsedVisibleCount: Int,
): List<T> = when {
    // 展开、或本来就不可折叠：全部显示
    expanded || !canCollapse -> steps
    // 完全可折叠模式：生成中/有待用户操作 → 显示尾部（步数取两者较大的那个）；否则一步不留
    fullyCollapsible -> {
        val previewCount = if (previewTail) collapsedVisibleCount.coerceAtLeast(1) else 0
        val wanted = maxOf(previewCount, minVisibleTailCount)
        if (wanted <= 0) emptyList() else steps.takeLast(wanted)
    }
    // 官方行为：折叠时保留尾部若干步（v307 一个字节都没动）
    else -> steps.takeLast(collapsedVisibleCount)
}
