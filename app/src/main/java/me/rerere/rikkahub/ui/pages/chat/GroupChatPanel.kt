package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupPresetKind
import me.rerere.rikkahub.data.model.GroupSpeakerMode
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.data.model.applyGroupPreset
import me.rerere.rikkahub.data.model.applySavedPreset
import me.rerere.rikkahub.data.model.clampPresetName
import me.rerere.rikkahub.data.model.label
import me.rerere.rikkahub.data.model.presetApplyMessage
import me.rerere.rikkahub.data.model.suggestPresetName
import me.rerere.rikkahub.service.GroupPrimaryAction
import me.rerere.rikkahub.service.GroupRunState
import me.rerere.rikkahub.service.GroupRunStatus
import me.rerere.rikkahub.service.resolveGroupPrimaryAction
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import kotlin.uuid.Uuid

/**
 * AI 讨论群组的进行中面板。
 *
 * v313 建立，v314 按「傻瓜式」整体重做，v315 加三件事（写题目 / 暂停与删除分开 / 一键挑人），
 * v316 再改两处口径：
 * 1. **开始讨论给两个入口**：
 *    - 「✍️ 写题目开始（可加图片/文件）」：把光标送进输入框，用户在那儿打字 + 加附件，
 *      一发出去就成为本次讨论的题目并立刻开跑。v315 那个「弹框写题目」被撤掉，
 *      因为弹框里加不了附件（用户 v316 不满：「没法选择图片/文件来配合文字在最开始发送」），
 *      而输入框那条路的底层在 v314 就已经通了（见 onStartWithTopic 的注释）；
 *    - 「▶ 直接开始 / 继续讨论」：保留 v315「不写题目也能开跑」的能力，不许悄悄砍掉。
 * 2. **群设置里加「我的方案」**：用户自己攒的整套配置（成员 + 一次说几条 + 补说次数），
 *    点名字整套填入、点旁边 ⋯ 覆盖/改名/删除。v315 那两个内置组合只是**系统挑人规则**，
 *    不是用户要的「方案」，所以降级为兜底、改名「快速开始（系统帮你挑人）」。
 *
 * 暂停 / 彻底删除分开（v315）保持不变。
 *
 * 面板仍然只在「这个对话确实建过群」时显示，普通对话看不到任何痕迹 —— 隔离规矩。
 */
@Composable
internal fun GroupChatPanel(
    state: GroupRunState?,
    hasConfig: Boolean,
    /** v315：群处于暂停态（配置留着但不用） */
    paused: Boolean,
    /** v314：成员是否够两个（不够时面板必须说清楚，否则用户只会看到「点了没反应」） */
    membersEnough: Boolean,
    /** v316：直接开跑（不写题目，就按上面已经聊过的内容讨论） */
    onContinue: () -> Unit,
    /**
     * v316：「写题目开始」—— 把光标送进输入框，用户在那儿打字 + 加图片/文件，
     * 一发出去就成为本次讨论的题目并立刻开跑。
     *
     * 用户口径（v316 拍板 B）：「点开始讨论时先不弹框，改成自动把光标放到平时聊天输入框」。
     * 为什么不复用 v315 那个弹框：弹框里做附件选择器要另起一套选择器与大小校验，
     * 而输入框那条路**底层已经通了** —— 插话通道（ChatService.tryGroupInterjection）
     * 会把消息连图片/文件一起原样入库，buildGroupTranscript 对 USER 消息又是原样保留，
     * 成员真的看得到附件；讨论停着时发一条消息还会自动开一批
     * （shouldAutoContinueAfterInterjection）。
     */
    onStartWithTopic: () -> Unit,
    onStop: () -> Unit,
    /** 等回答时放弃这次提问（把状态落回可继续），避免整场焊死 */
    onSkipQuestion: () -> Unit,
    /** v314：把光标放进输入框，让用户直接打字插话 */
    onInterject: () -> Unit,
    /** v315：暂停态下重新启用 */
    onResumeGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * v317：整块是否收起（用户口径：「这个太占空间了，给我做一个可以折叠起来的」）。
     * 偏好存在 DisplaySetting.groupPanelCollapsed —— 全局记住，换对话/重启都保持，
     * 且不走数据库（AppDatabase 必须停在 25）。
     */
    collapsed: Boolean,
    /** v317：切换收起 / 展开 */
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 没建过群、也没在讨论 → 完全不显示，不占输入框上方位置
    if (state == null && !hasConfig) return

    val status = state?.status ?: GroupRunStatus.IDLE
    val busy = status == GroupRunStatus.SPEAKING
    val waiting = status == GroupRunStatus.WAITING_FOR_USER
    // 「开始」还是「继续」：说过话就写继续，一次都没说过才写开始
    val hasSpokenBefore = state != null &&
        (state.turnsInRun > 0 || state.lastSpeakerModelId != null)
    val primary = resolveGroupPrimaryAction(status)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        paused -> "⏸ 讨论群已暂停（成员和设置都留着）"
                        waiting -> "🙋 有成员在等你回答"
                        busy -> "🗣 ${state?.currentSpeakerName.orEmpty()} 正在发言"
                        status == GroupRunStatus.PAUSED -> "⏸ 已暂停，随时可以接着聊"
                        status == GroupRunStatus.STOPPED -> "⏹ 已停止（说过的话都留着）"
                        status == GroupRunStatus.FINISHED -> "✅ 这轮讨论已结束"
                        else -> "💬 讨论群已就绪"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    // 收起时这一行是唯一可见内容，宁可截断成「🗣 xxx 正在发…」也不能换行；
                    // 展开态保持原样（默认 Clip），免得"收起再展开"看着跟以前不一样
                    overflow = if (collapsed) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
                if (!paused && state != null && state.turnsBudget > 0) {
                    // v317：进度在收起态也保留（用户要的样式：状态 · 第 0/3 条）
                    Text(
                        text = "第 ${state.turnsInRun}/${state.turnsBudget} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v317：折叠开关。用户拍板原话是「展开箭头」，所以给箭头；
                // 但箭头不单独表意（▼/▲ 在小字号下容易被看成装饰），一律配文字。
                // 用文字符号而不是图标组件：项目已有 ▶/⏹/🙋 先例，零新增依赖。
                TextButton(onClick = onToggleCollapsed) {
                    Text(
                        text = if (collapsed) "▼ 展开" else "▲ 收起",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // v317：收起时整块只剩上面那一行（用户嫌它太占空间）。
            // 必须排在暂停分支**之前** —— 否则收起状态下那个「▶ 重新启用讨论群」大按钮
            // 还在，等于没省到空间。
            // 唯一例外：报错照样摊出来。把故障藏进折叠里比占空间更危险
            // （手册 §7 续跑铁律 ③：上游报错必须显示给用户）。
            if (collapsed) {
                // 收起只折「可操作项」，不折「坏状态」——否则用户看着那行「💬 讨论群已就绪」
                // 以为一切正常，实际根本开不起来（审查位 v317 抓的 P2-1）。
                // 判空写法与下面 v314 那块保持一致（state != null && state.error != null），
                // 免得智能转换在属性上失效。
                if (state != null && state.error != null) {
                    Text(
                        text = state.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                    )
                }
                if (!membersEnough) {
                    Text(
                        text = "⚠️ 成员不足两个（可能模型被删了）：点「展开」再去「群设置」补人。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }

            if (paused) {
                // v315：暂停态只给「重新启用」和「群设置」，说明配置都还在
                Text(
                    text = "只是先不用它。成员、批次、续写次数都留着，点下面就能接着用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onResumeGroup, modifier = Modifier.weight(1f)) {
                        Text("▶ 重新启用讨论群", style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("⚙ 群设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
                return@Column
            }

            if (state != null && state.error != null) {
                // 失败原因必须摊开给用户看，不能只写日志（手册 §7 续跑铁律 ③）
                Text(
                    text = state.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                )
            }
            if (waiting) {
                Text(
                    text = "回答上面那条提问后，讨论会自动接着往下走。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!membersEnough) {
                Text(
                    text = "⚠️ 成员不足两个（可能模型被删了）：先去「⚙ 群设置」里补上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // v314：把「插话」这件事说清楚 —— v313 里用户完全不知道可以插话、
            // 也不知道插进去之后会发生什么。
            Text(
                text = "想插话？点下面「我要插话」或直接在大输入框打字。" +
                    "你这句话会进入讨论，大家都能看到并回应你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // 主按钮：**完全由纯函数决定**（`resolveGroupPrimaryAction`，有真单测；`when` 穷尽
            // 全部取值，编译器兜着）。
            when (primary) {
                GroupPrimaryAction.DISCUSSION -> {
                    // v316：两个入口都给。只给输入框那条，用户想「就按上面已经聊过的内容讨论」
                    // 就做不到（v315 的「不写题目也能直接开跑」是明确能力，不能悄悄砍掉）；
                    // 只给直接开跑那条，图片/文件就加不进来（用户 v316 的不满）。
                    Button(
                        onClick = onStartWithTopic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (hasSpokenBefore) {
                                "✍️ 写题目继续（可加图片/文件）"
                            } else {
                                "✍️ 写题目开始（可加图片/文件）"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    OutlinedButton(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (hasSpokenBefore) "▶ 直接继续讨论" else "▶ 直接开始讨论",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                GroupPrimaryAction.SKIP_QUESTION -> {
                    Button(
                        onClick = onSkipQuestion,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "⏭ 跳过这次提问，先聊别的",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }

                // 有成员正在说话：那个场合只该给「停止」，不给主按钮
                GroupPrimaryAction.NONE -> Unit
            }
            // ⚠️ 独立兜底：就算纯函数哪天被改坏，等回答时也绝不能没有出口
            // （v313 审查位抓的死锁 P0：等回答时没有任何出口 = 整场永久焊死）。
            // 正常情况这条永不命中，因此不会出现两个「跳过提问」按钮。
            if (waiting && primary != GroupPrimaryAction.SKIP_QUESTION) {
                Button(
                    onClick = onSkipQuestion,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "⏭ 跳过这次提问，先聊别的",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v314：停止只在「真的有东西在跑」时出现 —— 停下之后按钮立刻消失，
                // 用户才看得出「我这一下点生效了」。
                if (busy || waiting) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("⏹ 停止", style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    onClick = onInterject,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("✏️ 我要插话", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onOpenSettings) {
                    Text(
                        text = "⚙ 群设置",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}


/**
 * 建群 / 改群设置弹窗。
 *
 * 成员选择刻意用「点选加入 + 已选列表可移除」而不是拖拽排序：
 * 顺序就是轮流发言的顺序，必须看得见、改得动，但不值得为它上一套拖拽手势。
 *
 * v315 新增：顶部「快速开始」一键套用成员组合；底部「暂停使用 / 彻底删除」两个动作分开。
 */
@Composable
internal fun GroupChatSettingsDialog(
    initial: GroupChatConfig,
    providers: List<ProviderSetting>,
    /** v315：暂停 / 重新启用（配置一个字节都不动） */
    onPauseToggle: (Boolean) -> Unit,
    /** v315：彻底删除群设置（不可撤销，界面已二次确认） */
    onDelete: () -> Unit,
    /** v316：当前所有用户方案（全局共享，不按对话隔离） */
    presets: List<GroupChatPreset>,
    // v316 复审位抓的 P2：这四个回调原本用返回值 Boolean 表示成败，可写盘是协程里
    // 异步做的 —— 返回 true 只代表「校验过了、任务提交了」，真写失败时界面照样报成功。
    // 现在一律改成「只管提交」，成败由 ChatVM 的 groupPresetOp 回执通道回报（ChatPage 里监听）。
    /** v316：把界面上当前这套提交为存成新方案 */
    onSaveAsPreset: (name: String, config: GroupChatConfig) -> Unit,
    /** v316：用当前这套覆盖已有方案（名字留着） */
    onOverwritePreset: (presetId: Uuid, config: GroupChatConfig) -> Unit,
    /** v316：提交改名 */
    onRenamePreset: (presetId: Uuid, name: String) -> Unit,
    /** v316：提交删方案（只动方案表，不影响任何对话现有的群配置） */
    onDeletePreset: (presetId: Uuid) -> Unit,
    /** v316：即时反馈（只用于「填入草稿」这类当场生效的事；落库成败走回执） */
    onNotify: (message: String, isError: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (GroupChatConfig) -> Unit,
) {
    var members by remember { mutableStateOf(initial.memberModelIds) }
    var mode by remember { mutableStateOf(initial.speakerMode) }
    var turns by remember { mutableStateOf(initial.turnsPerRun) }
    var resumeMax by remember { mutableStateOf(initial.resumeMax) }
    var allowAskUser by remember { mutableStateOf(initial.allowAskUser) }
    var allowTools by remember { mutableStateOf(initial.allowTools) }
    var moderator by remember { mutableStateOf(initial.moderatorModelId) }
    var confirmDelete by remember { mutableStateOf(false) }
    // v316：「把当前配置存成方案」的命名框
    var saveAsNewPreset by remember { mutableStateOf(false) }
    // v316：点方案行右侧 ⋯ 打开的管理框（套用 / 覆盖 / 改名 / 删除）
    var managingPreset by remember { mutableStateOf<GroupChatPreset?>(null) }
    // v316：管理框里的改名草稿
    var presetNameDraft by remember { mutableStateOf("") }

    val allModels = remember(providers) {
        providers.flatMap { provider -> provider.models }
    }
    // v315：一键套用要按「厂商」分组才知道哪些模型来自不同家。
    // 用 provider.id 当分组键（同一个供应商下可能有多个模型）。
    val availableForPreset = remember(providers) {
        // ⚠️ 必须与「手动添加成员」同一口径（ModelList 那边是
        // `provider.enabled && model.type == type`）：不然一键套用会把画图 / 向量模型
        // 塞进群，成员一开口就报错，而手动挑人是永远挑不到它们的（复审位 v315 抓的硬伤）。
        providers
            .filter { provider -> provider.enabled }
            .flatMap { provider ->
                provider.models
                    .filter { model -> model.type == ModelType.CHAT }
                    .map { model -> model.id to provider.id.toString() }
            }
    }
    // v316：套用用户方案时要清洗名单。口径**必须与「添加成员」选择器一致**
    // （provider.enabled + ModelType.CHAT），复审位抓的 P2：原本用「全部供应商全部模型」
    // 会把已停用供应商的模型、甚至画图/向量模型重新塞回成员，一开口就报错。
    // 这里不再只管悬空引用 —— 存进方案的名单本来就来自这个口径，套回来也必须用它。
    val applyableModelIds = remember(providers) {
        providers
            .filter { provider -> provider.enabled }
            .flatMap { provider ->
                provider.models
                    .filter { model -> model.type == ModelType.CHAT }
                    .map { model -> model.id }
            }
            .toSet()
    }
    val nameOf = rememberUpdatedState(allModels.associate { it.id to it.displayName })
    val picker = rememberModelListState(modelId = null, providers = providers, type = ModelType.CHAT)
    // 主持人单独一个选择器：它的 onSelect 写的是 moderator，不能和「加成员」共用一个
    val moderatorPicker = rememberModelListState(modelId = null, providers = providers, type = ModelType.CHAT)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("讨论群设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ===== v316：用户自己攒的方案 =====
                // 用户口径：「没有做出我要的预设方案，我要可以提前设置好几个方案，
                // 然后直接选择方案几来一键配置」。v315 那两个内置组合是**系统挑人规则**，
                // 不是用户攒的配置，所以两个都留着（一个都没存方案时还能一键起步），
                // 另加这一整块。
                Text(
                    text = "我的方案（一键整套套用）",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (presets.isEmpty()) {
                    Text(
                        text = "还没有方案。先把成员和次数选好，再点下面的" +
                            "「把当前配置存成方案」，以后一键就能回来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "点方案名 = 整套填入（成员、一次说几条、被打断接着说的次数一起换），" +
                            "填完要点右下角「保存」才生效；点旁边的 ⋯ = 套用 / 覆盖 / 改名 / 删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    presets.forEach { preset ->
                        // v316：套用前先按「与手动添加成员同一口径」清洗一遍。
                        // 注意用的是 applyableModelIds（enabled 供应商 + CHAT 类型），
                        // 不是全部模型 —— 复审位抓的 P2：口径放宽会把停用的供应商、
                        // 画图/向量模型重新塞回成员，那位成员一开口就报错。
                        val applied = applySavedPreset(preset, applyableModelIds)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    members = applied.memberModelIds
                                    turns = applied.turnsPerRun
                                    resumeMax = applied.resumeMax
                                    onNotify(
                                        presetApplyMessage(preset.name, applied),
                                        !applied.membersEnough,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                // 人数显示「现在套得上几个」，不是「当初存了几个」——
                                // 存 4 人一点只剩 1 人，用户只会看到一闪而过的 toast
                                Text(
                                    text = buildString {
                                        append(preset.name)
                                        append("（${applied.memberModelIds.size} 人")
                                        if (applied.droppedCount > 0) {
                                            append("·有 ${applied.droppedCount} 个现在用不了")
                                        }
                                        append("·一次 ${preset.turnsPerRun} 条）")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            // v316 复审位抓的 P0：管理入口原本挂在「长按」这个手势上，
                            // 而手势是加在 Material3 按钮自己的 modifier 外面的。
                            // 按钮自带的点击是内层节点、会先消费掉按下事件，
                            // 外层那个「按住不放」的手势根本收不到 —— 改名 / 覆盖 / 删除
                            // 全部打不开，而当时长按是唯一入口。
                            // 本仓库 ChatDrawer.kt:837 早就记过「两套手势同挂会互相抢事件」。
                            // 改成一眼看得见的「⋯」按钮：不依赖手势嵌套，也更容易被发现。
                            TextButton(
                                onClick = {
                                    managingPreset = preset
                                    presetNameDraft = preset.name
                                },
                            ) {
                                Text("⋯", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { saveAsNewPreset = true },
                    enabled = members.size >= GroupChatConfig.MIN_MEMBERS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("把当前配置存成方案", style = MaterialTheme.typography.labelMedium)
                }
                if (members.size < GroupChatConfig.MIN_MEMBERS) {
                    // 「点了没反应」是本轮要消灭的东西 —— 按钮变灰必须同时说清原因
                    Text(
                        text = "成员至少两个才能存方案（现在 ${members.size} 个）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()
                // v315：系统给的挑人规则，留作兜底
                Text(
                    text = "快速开始（系统帮你挑人）",
                    style = MaterialTheme.typography.labelLarge,
                )
                listOf(GroupPresetKind.ONE_PER_PROVIDER, GroupPresetKind.FIRST_THREE).forEach { kind ->
                    OutlinedButton(
                        onClick = { members = applyGroupPreset(kind, availableForPreset) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(kind.label(), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    text = "套用后还能自己增减，顺序就是发言顺序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                Text(
                    text = "成员（顺序就是发言顺序）",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (members.size < GroupChatConfig.MIN_MEMBERS) {
                    // v315：只提示「空」是不够的 —— 预设只选出 1 个成员时（例如只有一家供应商），
                    // 界面上一个字都不说，「保存」却静默变灰，正是本轮要消灭的「点了没反应」。
                    Text(
                        text = if (members.isEmpty()) {
                            "还没选成员，至少选两个。"
                        } else {
                            "现在只有 ${members.size} 个成员，至少要两个才能保存。" +
                                "点上面的「快速开始」或「添加成员」再补一个。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                members.forEachIndexed { index, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}. ${nameOf.value[id] ?: "（模型已删除）"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { members = members - id }) {
                            Text("移除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { picker.open() },
                    enabled = members.size < GroupChatConfig.MAX_MEMBERS,
                ) {
                    Text("添加成员")
                }

                HorizontalDivider()
                Text("谁下一个说话", style = MaterialTheme.typography.labelLarge)
                ModeRow(
                    label = "轮流发言",
                    desc = "按上面的顺序一个接一个，不额外花钱选人参。",
                    selected = mode == GroupSpeakerMode.ROUND_ROBIN,
                    onClick = { mode = GroupSpeakerMode.ROUND_ROBIN },
                )
                ModeRow(
                    label = "主持人安排",
                    desc = "每轮多花一次调用问主持人，挑错了会自动退回轮流。",
                    selected = mode == GroupSpeakerMode.MODERATED,
                    onClick = { mode = GroupSpeakerMode.MODERATED },
                )

                // v313：主持人必须能选出来。审查位抓到「选了主持人安排却没有任何地方
                // 指定是谁」—— 引擎里 moderatorModelId 为空会直接退回轮流，
                // 等于这个模式在界面上根本用不了。
                if (mode == GroupSpeakerMode.MODERATED) {
                    ModeRow(
                        label = "主持人：" + (moderator?.let { id ->
                            allModels.firstOrNull { it.id == id }?.displayName
                        } ?: "还没选（会退回轮流）"),
                        desc = "主持人不发言，只挑下一个说话的人；点这里更换。",
                        selected = moderator != null,
                        onClick = { moderatorPicker.open() },
                    )
                }

                HorizontalDivider()
                Text(
                    text = "一次「继续讨论」最多说几条：$turns",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = turns.toFloat(),
                    onValueChange = { turns = it.toInt() },
                    valueRange = 1f..GroupChatConfig.MAX_TURNS_PER_RUN.toFloat(),
                    steps = GroupChatConfig.MAX_TURNS_PER_RUN - 2,
                )
                Text(
                    text = "到数就停下等你，不会一路烧到底。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                // v314：成员说一半被掐断时自动接着说。
                Text(
                    text = "成员说到一半被打断时，自动接着说：$resumeMax 次",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = resumeMax.toFloat(),
                    onValueChange = { resumeMax = it.toInt() },
                    valueRange = 0f..GroupChatConfig.MAX_RESUME_MAX.toFloat(),
                    steps = GroupChatConfig.MAX_RESUME_MAX - 1,
                )
                Text(
                    text = "0 = 关掉（被掐断就停在半截）。怎么接下去跟随你在设置里选的" +
                        "「发继续 / 重新生成最后一次输出」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // v314：把这层代价写出来（审查位指出以前没交代）——
                // 与主对话同口径：次数同时也决定这位成员这一次请求的重试上限，
                // 并且顺手把服务商的临时故障（限流、5xx 一类）也纳入可重试范围。
                Text(
                    text = "次数同时也决定这位成员这一次请求的重试上限，并把服务商的临时故障" +
                        "（限流、繁忙等）也纳入重试（与主对话同口径）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                SwitchRow(
                    label = "允许成员向你提问",
                    desc = "缺关键条件时会停下来等你回答，答完接着讨论。",
                    checked = allowAskUser,
                    onChange = { allowAskUser = it },
                )
                SwitchRow(
                    label = "成员开口前可查资料",
                    desc = "给成员挂上与主对话同一套工具（搜索、工作区等）。",
                    checked = allowTools,
                    onChange = { allowTools = it },
                )

                HorizontalDivider()
                // v315：两个动作分开 —— 「暂停」留着配置，「彻底删除」才是真清空。
                // 用户口径：「解散丢配置也可以，但要有方案预选来弥补这个缺陷」，
                // 所以两个都给，并在按钮上把差别写明白。
                Text(
                    text = "不再使用讨论群",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "「暂停」只是先不用它：成员、批次、续写次数都留着，随时能回来。" +
                        "「彻底删除」是真的清空，下次要用得重新建群（可以用上面的" +
                        "「快速开始」一键重建）。聊天记录两种情况都不会丢。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onPauseToggle(!initial.enabled) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (initial.enabled) {
                            "暂停使用（配置留着）"
                        } else {
                            "重新启用"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("彻底删除群设置", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        GroupChatConfig(
                            memberModelIds = members,
                            speakerMode = mode,
                            moderatorModelId = moderator,
                            turnsPerRun = turns,
                            resumeMax = resumeMax,
                            enabled = initial.enabled,
                            allowAskUser = allowAskUser,
                            allowTools = allowTools,
                        )
                    )
                },
                enabled = members.size >= GroupChatConfig.MIN_MEMBERS,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("彻底删除这个讨论群？") },
            text = {
                Text(
                    "成员和设置都会没掉，下次要用得重新建群（可以用「快速开始」一键重建）。" +
                        "聊天记录一条都不会丢。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    // v316：把当前这套存成一个新方案
    if (saveAsNewPreset) {
        PresetNameDialog(
            title = "存成新方案",
            initialName = suggestPresetName(presets),
            hint = "以后在任意对话的群设置里都能一键套用。",
            confirmLabel = "保存方案",
            onDismiss = { saveAsNewPreset = false },
            onConfirm = { rawName ->
                // 只提交，不在这里报成败 —— 空名 / 存满 / 重名 / 写盘失败全部由
                // ChatVM 的 groupPresetOp 回执统一回报（ChatPage 监听并弹一句人话）
                onSaveAsPreset(
                    rawName,
                    currentDraftConfig(
                        members = members,
                        mode = mode,
                        moderator = moderator,
                        turns = turns,
                        resumeMax = resumeMax,
                        enabledKeep = initial.enabled,
                        allowAskUser = allowAskUser,
                        allowTools = allowTools,
                    ),
                )
                saveAsNewPreset = false
            },
        )
    }

    // v316：点方案行右侧 ⋯ 打开的管理框
    managingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { managingPreset = null },
            title = { Text("方案「${preset.name}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "存了 ${preset.memberModelIds.size} 个成员，现在能套上 " +
                            "${applySavedPreset(preset, applyableModelIds).memberModelIds.size} 个；" +
                            "一次说 ${preset.turnsPerRun} 条、被打断补说 ${preset.resumeMax} 次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val applied = applySavedPreset(preset, applyableModelIds)
                            members = applied.memberModelIds
                            turns = applied.turnsPerRun
                            resumeMax = applied.resumeMax
                            managingPreset = null
                            onNotify(presetApplyMessage(preset.name, applied), !applied.membersEnough)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("套用这个方案")
                    }
                    OutlinedButton(
                        onClick = {
                            // 成败走回执
                            onOverwritePreset(
                                preset.id,
                                currentDraftConfig(
                                    members = members,
                                    mode = mode,
                                    moderator = moderator,
                                    turns = turns,
                                    resumeMax = resumeMax,
                                    enabledKeep = initial.enabled,
                                    allowAskUser = allowAskUser,
                                    allowTools = allowTools,
                                ),
                            )
                            managingPreset = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("用当前配置覆盖它", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedTextField(
                        value = presetNameDraft,
                        onValueChange = { presetNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("改名字") },
                    )
                    OutlinedButton(
                        onClick = {
                            // 空名 / 重名 / 写盘失败都由回执来说，界面不许自己宣布成功
                            onRenamePreset(preset.id, presetNameDraft)
                            managingPreset = null
                        },
                        enabled = clampPresetName(presetNameDraft).isNotEmpty() &&
                            clampPresetName(presetNameDraft) != preset.name,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("确认改名", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = {
                            // 回执会说明删没删成；不影响任何对话现有群配置这点也写进回执文案
                            onDeletePreset(preset.id)
                            managingPreset = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("删除这个方案", style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { managingPreset = null }) { Text("关闭") }
            },
        )
    }

    ModelListSheet(
        state = picker,
        onSelect = { model: Model ->
            if (!members.contains(model.id) && members.size < GroupChatConfig.MAX_MEMBERS) {
                members = members + model.id
            }
        },
    )

    // 主持人选择器：选谁当主持人（主持人自己也在成员里，只负责挑人、不发言）
    ModelListSheet(
        state = moderatorPicker,
        onSelect = { model: Model -> moderator = model.id },
    )
}

@Composable
private fun ModeRow(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * v316：把弹窗里当前的草稿拼成一份配置 —— 存方案与覆盖方案两处都要用，
 * 抽出来防止两处口径写歪（比如一处忘了带 resumeMax）。
 */
private fun currentDraftConfig(
    members: List<Uuid>,
    mode: GroupSpeakerMode,
    moderator: Uuid?,
    turns: Int,
    resumeMax: Int,
    enabledKeep: Boolean,
    allowAskUser: Boolean,
    allowTools: Boolean,
): GroupChatConfig = GroupChatConfig(
    memberModelIds = members,
    speakerMode = mode,
    moderatorModelId = moderator,
    turnsPerRun = turns,
    resumeMax = resumeMax,
    enabled = enabledKeep,
    allowAskUser = allowAskUser,
    allowTools = allowTools,
)

/**
 * v316：给方案起名字的弹框。
 *
 * 刻意预填建议名（方案1、方案2…）而不是空着：用户要的是「选择方案几」，
 * 让他现想名字反而增加上手成本。空名不给保存 —— 没名字的方案在按钮上无法辨认。
 */
@Composable
private fun PresetNameDialog(
    title: String,
    initialName: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("方案名") },
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = clampPresetName(name).isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
