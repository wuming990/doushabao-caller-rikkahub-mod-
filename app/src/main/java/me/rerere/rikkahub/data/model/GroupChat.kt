package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * v313：AI 讨论群组（用户新功能）。
 *
 * 与圆桌的区别（用户原话：「现在的圆桌还不够，我需要可以多 AI 实时讨论，
 * 条件不足时可以向我提问，补充完需求后继续讨论的群组」）：
 * - 圆桌是**一次性流水线**：合同 → 各自出方案 → 反驳 → 拍板，中途不许反问用户
 *   （角色提示词里明写「不要向用户反问后等待回答；本轮不会有人回复你」）；
 * - 群组是**一条共享时间线**：成员互相接话、能看到彼此已经说过的内容，
 *   条件不足时可以问用户，用户补充后从断点继续讨论。
 *
 * 实现取向（刻意为之，写在这是为了让后来人别改歪）：
 * 1. **不建新表、不动数据库版本。** 讨论记录就是普通对话的 messageNodes，
 *    群设置存 DataStore（照 v268「对话 → 模型」映射那套五环节）。
 *    AppDatabase 必须停在 25，加列＝26＋迁移，风险落在用户全部聊天记录上。
 * 2. **复用现成聊天页。** 每条助手消息自带 modelId，气泡署名读的是
 *    `node.currentMessage.modelId`（ChatList.kt:334），所以成员各自署名，
 *    不需要新写页面、不需要动共享渲染层。
 * 3. **绝不改动主对话行为。** 群驱动器自己指定 model 调 generateText
 *    （照圆桌 generateRoundTableProposal 的做法），不碰「助手当前模型」这类全局设置。
 *
 * 本文件只放数据与纯函数，不依赖 Android，方便单元测试。
 */

/** 谁下一个说话 */
@Serializable
enum class GroupSpeakerMode {
    /** 按成员名单轮流，不需要额外调用模型选人参 */
    ROUND_ROBIN,

    /** 主持人群体里指定下一个发言者；主持人失败时自动退回轮流 */
    MODERATED,
}

/**
 * 一个群对话的设置。按 conversationId 存在 DataStore 的一张映射表里。
 *
 * 所有字段都带默认值 —— 旧设置 JSON 里没有这些键时必须能解出来
 * （JsonInstant 配了 ignoreUnknownKeys + encodeDefaults，带默认值即双向兼容）。
 */
@Serializable
data class GroupChatConfig(
    /** 成员模型 id，**顺序就是轮流发言的顺序** */
    val memberModelIds: List<Uuid> = emptyList(),
    val speakerMode: GroupSpeakerMode = GroupSpeakerMode.ROUND_ROBIN,
    /** MODERATED 模式下的主持人；为空或模型已删时退回轮流 */
    val moderatorModelId: Uuid? = null,
    /**
     * 一次「继续讨论」最多自动发几条言，到数就停下等用户。
     *
     * 用户口径是「可以提供可调的批次上限，但不得偷偷限制普通聊天」，
     * 所以这个上限只管群组这一条路径，且界面可调。
     */
    val turnsPerRun: Int = DEFAULT_TURNS_PER_RUN,
    /**
     * v314：成员发言被异常掐断时，自动接着说的次数上限（0 = 关）。
     *
     * 用户口径（v314）：「成员说到一半被掐断就真的停在半截」，要求给群一个可调的续写次数。
     * 这是**群自己的预算**，不是借主对话那个开关 —— 主对话 / 圆桌 / 子代理一律不读它
     * （与 v248 工具上限、v263 续跑同一套隔离做法，见 PROJECT-MANUAL §4 禁区 #13）。
     * 接着说的方法（发「继续」/ 重新生成）跟随用户全局选的那个，不在这里重复一遍。
     */
    val resumeMax: Int = DEFAULT_RESUME_MAX,
    /**
     * v315：这个对话的讨论群是不是**启用**状态。
     *
     * 用户口径（v315 原话）：「解散群聊后全部清空了 —— 我建群时选的成员没了，要重新选一遍，
     * 讨论次数和被打断次数也被恢复为默认了」。所以把原来那个"解散"拆成两个动作：
     * - **暂停**（`enabled = false`）：配置全留着，这个对话恢复成普通聊天，随时能再启用；
     * - **彻底删除**：从 `groupChatConfigs` 里删掉，那才是真的清空（界面要明确说清会删什么）。
     *
     * 默认 true：老设置 JSON 里没有这个键时，行为与 v314 完全一致（建了就启用）。
     */
    val enabled: Boolean = true,
    /** 允许成员向用户提问（关掉时提示词里要求他们把疑问写进发言、不要调用提问工具） */
    val allowAskUser: Boolean = true,
    /** 每个成员开口前，允许它先查工作区/搜索（工具与主对话同一套装配） */
    val allowTools: Boolean = true,
    /**
     * v319：群讨论上文的字符上限，**0 = 不限**（默认）。
     *
     * 用户口径（v319 原话）：「我要的是打开时候获取全部上文」—— 所以默认不设上限，
     * 十几万字的原对话（含之前的工具调用与子代理产出）整份带进群。
     *
     * 但这个可调项是**自救入口**，不是摆设：整份上文一旦超过某个模型的上下文窗口，
     * 服务商那边会直接报错、讨论一步都跑不动。用户自己在设置里填一个数就能降级，
     * 不必等开发者改代码发新版。
     */
    val transcriptCharLimit: Int = DEFAULT_TRANSCRIPT_CHAR_LIMIT,
) {
    // v313 曾加过 firstRoundBlind（首轮各自独立想）开关，**已删除**：
    // 引擎没有实现它，留着就是一个骗人的界面开关（审查位抓的同类问题）。
    // 要做就得连带「盲写阶段与实时讨论语义冲突」一起设计，不留半成品。
    /** 至少两个成员才谈得上「讨论」 */
    val hasEnoughMembers: Boolean get() = memberModelIds.size >= MIN_MEMBERS

    companion object {
        const val MIN_MEMBERS = 2

        /** 成员上限，与圆桌一致（圆桌 MAX_ROUND_TABLE_MEMBERS = 8） */
        const val MAX_MEMBERS = 8
        const val DEFAULT_TURNS_PER_RUN = 3
        const val MAX_TURNS_PER_RUN = 20

        /** v314：群成员续写默认次数（与主对话默认值一致，装上去就能用，不用先去找设置） */
        const val DEFAULT_RESUME_MAX = 3

        /** v314：群续写次数上限，与主对话那条滑条同口径 */
        const val MAX_RESUME_MAX = 99

        /** v315：一键预设最多填几个成员（3 个够讨论，也够省） */
        const val MAX_PRESET_MEMBERS = 3

        /**
         * v319：群讨论上文的默认字符上限 = **0（不限）**。
         *
         * 用户口径：「我要的是打开时候获取全部上文」，所以默认不砍。
         * >0 时才从最旧的发言开始丢（保住最新内容），那是留给「模型窗口装不下」时的自救档。
         */
        const val DEFAULT_TRANSCRIPT_CHAR_LIMIT = 0

        /** v319：上限档位能填到的最大值（挡住误填的天文数字，够用即可） */
        const val MAX_TRANSCRIPT_CHAR_LIMIT = 2_000_000
    }
}

/**
 * 下一个该谁说话。
 *
 * 返回 null 表示「这一轮没人可说」—— 成员全被删了，交给调用方提示用户，
 * 绝不能在这里编一个模型出来。
 */
fun nextRoundRobinSpeaker(
    memberModelIds: List<Uuid>,
    existingModelIds: Set<Uuid>,
    lastSpeakerModelId: Uuid?,
): Uuid? {
    val alive = memberModelIds.filter { it in existingModelIds }
    if (alive.isEmpty()) return null
    val lastIndex = lastSpeakerModelId?.let { alive.indexOf(it) } ?: -1
    // 上一个说话的人已被删掉时 lastIndex = -1，自然从第一个开始
    return alive[(lastIndex + 1) % alive.size]
}

/**
 * 清洗成员名单：去重、剔除已经不存在的模型、限制人数。
 *
 * 为什么放在读取链路上做而不是写入时做：用户会在别的地方删模型、合并上游也会改设置，
 * 名单里的 id 随时可能变成悬空引用。驱动器每次开口前都要拿到「真的还在」的成员。
 */
fun sanitizeGroupMembers(
    memberModelIds: List<Uuid>,
    existingModelIds: Set<Uuid>,
    maxMembers: Int = GroupChatConfig.MAX_MEMBERS,
): List<Uuid> = memberModelIds
    .filter { it in existingModelIds }
    .distinct()
    .take(maxMembers)

/**
 * 把批次上限夹进合法区间。
 *
 * 不给 0 或负数：那等于「一开口就停」，用户会以为功能坏了。
 */
fun clampGroupTurns(turnsPerRun: Int): Int =
    turnsPerRun.coerceIn(1, GroupChatConfig.MAX_TURNS_PER_RUN)

/**
 * v314：把群续写次数夹进合法区间。
 *
 * 下限取 0（= 关）而不是 1：这是「可以不要」的功能，用户有权彻底关掉。
 * 不给负数：负数会被生成层当成「关」，用户以为开了其实没开；上限与主对话那条滑条一致。
 */
fun clampResumeMax(resumeMax: Int): Int =
    resumeMax.coerceIn(0, GroupChatConfig.MAX_RESUME_MAX)

/**
 * v319：把「群讨论上文上限」夹进合法区间。
 *
 * 允许 0 = 不限（默认，用户要的「打开时候获取全部上文」）。负数按 0 处理：
 * 负数传进截断逻辑会算成「一条都不留」，等于把讨论直接废掉，那不是用户要的。
 */
fun clampGroupTranscriptLimit(limit: Int): Int =
    limit.coerceIn(0, GroupChatConfig.MAX_TRANSCRIPT_CHAR_LIMIT)

/**
 * v319：解析点名 —— 用户点名的那位还在成员里就采用他，否则返回 null（退回正常顺序）。
 *
 * 为什么抽成顶层纯函数：引擎里的 pickSpeaker 要造 host / conversation / settings 才跑得动，
 * 真单测代价过高；而「点了一个已经被移出群的人」恰恰是最容易写歪、又最容易咬人的分支，
 * 必须有人守。所以把判定本身摘出来测，引擎只负责「取名字 + 报告退回原因」。
 */
fun resolveRequestedSpeaker(
    requested: Uuid?,
    members: Set<Uuid>,
): Uuid? = requested?.takeIf { it in members }

/**
 * v315：成员组合预设（用户口径：「没有预设方案选择 —— 现成的成员组合，比如三个不同长处的模型，
 * 选一个一键套用，不用自己一个个挑」）。
 *
 * 为什么按「不同厂商各取一个」：App 无法知道每个模型"擅长什么"（那是模型自己的属性），
 * 但**厂商**是我们确切知道的，而不同厂商的模型通常确实长处不同 —— 这是能在不猜的前提下
 * 给出「不同长处的组合」的唯一诚实做法。取不到足够厂商时自然退化，由调用方决定要不要补人。
 */
enum class GroupPresetKind {
    /** 不同厂商各挑一个（最多 3 个）—— 最省心的「不同长处」组合 */
    ONE_PER_PROVIDER,

    /** 你模型列表里的前三个（顺序就是你平时的使用习惯） */
    FIRST_THREE,
}

/** 预设按钮上显示的名字 */
fun GroupPresetKind.label(): String = when (this) {
    GroupPresetKind.ONE_PER_PROVIDER -> "不同厂商各一个（最多 3 个）"
    GroupPresetKind.FIRST_THREE -> "我列表里的前三个"
}

/**
 * 按预设算出成员名单。
 *
 * @param available 用户当前**真实存在**的模型（顺序即模型列表顺序；调用方负责已按供应商展开）
 * @return 建议的成员 id（可能不足 [GroupChatConfig.MIN_MEMBERS]，由界面提示用户再补）
 */
fun applyGroupPreset(
    kind: GroupPresetKind,
    available: List<Pair<Uuid, String>>,
): List<Uuid> = when (kind) {
    // 按供应商 id 分组，每组只取第一个 —— 结果天然是「不同厂商」
    GroupPresetKind.ONE_PER_PROVIDER -> available
        .groupBy { it.second }
        .values
        .mapNotNull { group -> group.firstOrNull()?.first }
        .take(GroupChatConfig.MAX_PRESET_MEMBERS)

    // 直接取列表前三个（v315：用 MAX_PRESET_MEMBERS 而不是「一批最多说几条」那个常量 ——
    // 后者语义不同，将来它被改成 5，这里会莫名其妙变成「前五个」）
    GroupPresetKind.FIRST_THREE -> available
        .take(GroupChatConfig.MAX_PRESET_MEMBERS)
        .map { it.first }
}

/**
 * v316：用户自己存下来的讨论群方案（用户口径：「没有做出我要的预设方案，
 * 我要可以提前设置好几个方案，然后直接选择方案几来一键配置」）。
 *
 * v315 给的两个内置组合（各厂商取一个 / 取前三）不是用户要的 —— 那两个是**系统挑人规则**，
 * 用户要的是**自己攒好的整套配置**。所以这一版把它们留着当兜底，另加这套用户方案。
 *
 * 一个方案装三样（用户拍板「成员 + 两条次数」）：成员名单、一次说几条、
 * 成员被打断后自动接着说的次数。刻意**不含**发言方式与主持人：那两个跟"这套人怎么协作"
 * 绑得不紧，套方案时把用户当前选的协作方式一起覆盖掉反而更容易出问题（用户没要求）。
 *
 * 存在 DataStore（见 PreferencesStore 的 GROUP_CHAT_PRESETS），**不动数据库版本**。
 * 全局共享，不按对话隔离 —— 用户要的是"提前设好几个方案"，在哪个对话都能拿来用。
 */
@Serializable
data class GroupChatPreset(
    val id: Uuid,
    /** 方案名，界面直接显示；过长会被 [clampPresetName] 截断 */
    val name: String,
    /** 顺序就是轮流发言的顺序 */
    val memberModelIds: List<Uuid> = emptyList(),
    val turnsPerRun: Int = GroupChatConfig.DEFAULT_TURNS_PER_RUN,
    val resumeMax: Int = GroupChatConfig.DEFAULT_RESUME_MAX,
) {
    companion object {
        /** 方案条数上限：再多界面就成一长串按钮，反而更难选 */
        const val MAX_PRESETS = 8

        /** 方案名长度上限（含） */
        const val MAX_NAME_LENGTH = 20
    }
}

/**
 * 方案名清洗：去首尾空白 + 截断长度。
 *
 * 刻意不在这里判定"空名字"：那是调用方的事（界面要给用户一句人话说明为什么存不了）。
 */
fun clampPresetName(raw: String): String =
    raw.trim().take(GroupChatPreset.MAX_NAME_LENGTH)

/**
 * 给新方案建议一个名字：方案1、方案2…跳过已被占用的编号。
 *
 * 为什么不是"成员数 + 日期"之类：用户的说法就是"选择方案几"，编号命名才和用法对上。
 */
fun suggestPresetName(existing: List<GroupChatPreset>): String {
    val used = existing.map { it.name.trim() }.toSet()
    // 从 1 开始找**最小空号**，而不是「已有条数 + 1」：
    // 用户删掉「方案1」后再新建，应该还能叫回方案1。名字随条数漂移会让人以为
    // 自己在攒第 N 个方案，实际编号却在跳（审查位 v316 抓的口径不一致）。
    var index = 1
    while ("方案$index" in used) index += 1
    return "方案$index"
}

/**
 * 套用用户方案的产物（v316）。
 *
 * @param memberModelIds 已按"模型是否真的还在"清洗过的成员名单（与面板/插话同一口径）
 * @param droppedCount 方案里有几个成员已经被删掉了 —— 界面必须如实说出来，
 *   否则用户套完方案只看到"人变少了"，不知道是没存住还是被删了
 */
data class GroupPresetApplyResult(
    val memberModelIds: List<Uuid>,
    val turnsPerRun: Int,
    val resumeMax: Int,
    val droppedCount: Int,
) {
    /** 清洗后还够不够开一场讨论 */
    val membersEnough: Boolean
        get() = memberModelIds.size >= GroupChatConfig.MIN_MEMBERS
}

/**
 * 把用户存的方案落到"当前可用的模型"上。
 *
 * 必须清洗：模型会在别处被删掉、合并上游也会改设置，方案里的 id 随时可能悬空
 * （与 [sanitizeGroupMembers] 同一个理由）。清洗函数本身就负责去重与限人数，
 * **口径必须与手动添加成员完全一致**（复审位 v315 抓的那类硬伤，别再犯）。
 */
fun applySavedPreset(
    preset: GroupChatPreset,
    existingModelIds: Set<Uuid>,
): GroupPresetApplyResult {
    val alive = sanitizeGroupMembers(preset.memberModelIds, existingModelIds)
    val distinctRequested = preset.memberModelIds.distinct().size
    return GroupPresetApplyResult(
        memberModelIds = alive,
        turnsPerRun = clampGroupTurns(preset.turnsPerRun),
        resumeMax = clampResumeMax(preset.resumeMax),
        droppedCount = (distinctRequested - alive.size).coerceAtLeast(0),
    )
}

/**
 * v316：套用方案后给用户看的那句人话（**顶层纯函数**，可以直接真单测）。
 *
 * 为什么单独抽出来：这类提示一旦写在 Compose 里，`contains` 型断言证明不了它说得对不对，
 * 而「套完方案人变少了却一个字不说」正是 v315 被抓的那类体验硬伤。
 */
fun presetApplyMessage(
    presetName: String,
    result: GroupPresetApplyResult,
): String = buildString {
    append("已填好「").append(presetName).append("」：")
    append("${result.memberModelIds.size} 个成员、")
    append("一次 ${result.turnsPerRun} 条、")
    append("补说 ${result.resumeMax} 次")
    if (result.droppedCount > 0) {
        // 别说成「已经被删掉」：也可能是供应商被关掉、或那个模型不是聊天模型
        append("（已跳过 ${result.droppedCount} 个现在用不了的成员" +
            "：模型被删、供应商被关或不是聊天模型）")
    }
    if (!result.membersEnough) {
        append("；成员不足两个，得先补人")
    }
    // 审查位 v316 抓的 P1：弹窗里改的是草稿，不点「保存」就不会生效。
    // 提示必须把这件事说明白，否则用户以为已经配好了。
    append("。记得点右下角「保存」才生效")
}

/**
 * v316：方案写操作的种类与结果（审查位 v316 抓的 P2：`saveGroupPreset` 返回 Boolean
 * 只代表「名字合法、任务已提交」，DataStore 真写失败时界面照样报「方案已存好」= 说谎）。
 *
 * 所以改成「提交 + 回执」：调用方立刻拿到 Unit，结果由 ChatVM 的 StateFlow 一次性回报，
 * 成功与失败都说人话。模式与 v302 的备份传输状态一致（AppScope/VM 里跑，界面只显示）。
 */
enum class GroupPresetOpKind { SAVE, OVERWRITE, RENAME, DELETE }

/** 方案写操作的回执 */
sealed class GroupPresetOpResult {
    /** 已落库成功。name = 界面上要提到的方案名 */
    data class Done(val kind: GroupPresetOpKind, val name: String) : GroupPresetOpResult()

    /** 没做成（校验没过或写盘异常）。reason 必须原样摊给用户，不许只写日志 */
    data class Rejected(val kind: GroupPresetOpKind, val reason: String) : GroupPresetOpResult()
}

/**
 * 存新方案前的校验（顶层纯函数，可真单测）。
 *
 * 通过返回清洗后的名字；不通过返回 null，并给出**为什么**——
 * 「点了没反应」和「点了告诉我为啥不行」的差别就在这里。
 */
fun validateNewPresetName(
    raw: String,
    existing: List<GroupChatPreset>,
): Pair<String?, String> {
    val name = clampPresetName(raw)
    if (name.isEmpty()) return null to "方案名不能是空的，先给它起个名字"
    if (existing.size >= GroupChatPreset.MAX_PRESETS) {
        return null to "最多存 ${GroupChatPreset.MAX_PRESETS} 个方案，请先删掉一个不用的再存"
    }
    if (existing.any { it.name.trim() == name }) {
        return null to "已经有叫「$name」的方案了，换一个名字才不会搞混"
    }
    return name to ""
}

/** 改名前的校验：允许改回自己原来的名字（等于没改，不该报错） */
fun validatePresetRename(
    raw: String,
    existing: List<GroupChatPreset>,
    selfId: Uuid,
): Pair<String?, String> {
    val name = clampPresetName(raw)
    if (name.isEmpty()) return null to "方案名不能是空的"
    if (existing.any { it.id != selfId && it.name.trim() == name }) {
        return null to "已经有叫「$name」的方案了，换一个名字才不会搞混"
    }
    return name to ""
}

/**
 * v316：方案写操作回执 → 一句人话（顶层纯函数，可真单测）。
 *
 * 为什么要抽出来：界面自己拼文案最容易和真实行为脱节 —— 审查位 v316 抓的 P2 就是
 * 「返回值只代表任务已提交，界面却据此报成功」。成败一律由回执说话。
 */
fun presetOpMessage(result: GroupPresetOpResult): Pair<String, Boolean> = when (result) {
    is GroupPresetOpResult.Done -> (when (result.kind) {
        GroupPresetOpKind.SAVE -> "方案「${result.name}」已存好，点它的名字就能整套填入"
        GroupPresetOpKind.OVERWRITE -> "已用当前配置覆盖「${result.name}」"
        GroupPresetOpKind.RENAME -> "方案名已改成「${result.name}」"
        GroupPresetOpKind.DELETE -> "已删除方案「${result.name}」（不影响任何对话现有的群设置）"
    } to false)

    is GroupPresetOpResult.Rejected -> (when (result.kind) {
        GroupPresetOpKind.SAVE -> "没存上：${result.reason}"
        GroupPresetOpKind.OVERWRITE -> "没覆盖上：${result.reason}"
        GroupPresetOpKind.RENAME -> "没改成：${result.reason}"
        GroupPresetOpKind.DELETE -> "没删掉：${result.reason}"
    } to true)
}
