package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
    // v323 合并：二改自建字段（官方类里没有的），全部带默认值
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val sharedStorageTreeUris: List<String> = emptyList(),
    val launchCount: Int = 0,
    val workspaceCommandTimeoutSeconds: Int = 0,
    val autoCompressModelOverrides: List<AutoCompressModelOverride> = emptyList(),
    val roundTableSetting: RoundTableSetting = RoundTableSetting(),
    val enableAgentTools: Boolean = true,
    val agentModelId: Uuid? = null,
    val agentMaxConcurrent: Int = 4,
    val agentThreadTimeoutMinutes: Int = 10,
    /**
     * v246：子代理单个工具最长允许跑多久（1~60 分钟，默认 10）。
     *
     * 工具执行期间收不到任何数据，所以不按「多久没动静」判（v245），
     * 但必须有这条上限兜着，否则挂死的工具会一直挂着。与「卡住判定」相互独立。
     */
    val agentToolTimeoutMinutes: Int = 10,
    /**
     * v248：**子代理**单次工具返回上限（KB），32~256。
     *
     * 一个数管三件事：子代理读文件一次给多少、子代理的工具返回超了截不截、以及分段时的安全线。
     * 调大 = 一次读到更多、少跑几步；调小 = 对上下文窗口小的模型更安全。
     *
     * ⚠️ **主对话与圆桌不读这个字段**（红线：主模型不许被限制或改动）。
     */
    val toolOutputLimitKb: Int = TOOL_OUTPUT_LIMIT_DEFAULT_KB,
    /**
     * v241：子代理自动续跑次数上限。0 = 完全不自动续跑（一切都等你手动点）。
     *
     * 这个数字同时管两种自动续跑：上游报错后的自动重试，和「话没说完」后的自动接着写。
     */
    val agentAutoResumeMax: Int = 2,
    /**
     * v263：主对话「异常截断自动续跑」次数上限。回复因达到单次输出长度上限被掐断时，
     * 自动从断点接着写完。0 = 完全不自动续跑；默认 3；v270 上限从 10 提到 99（用户要求）。
     * ⚠️ 99 意味着最坏情况一个回复最多 99 趟额外计费的完整请求，断线续跑共用此数字。
     * 只管「输出没写完就被掐断」这一种异常，不影响断网重试（那套是官方 2.4.13 的逻辑）。
     */
    val truncationAutoResumeMax: Int = 3,
    /**
     * v288：续跑方式（两种，见 [ResumeStrategy]）。默认「发送继续」= 原有行为。
     * 仅续跑（truncationAutoResumeMax > 0）时生效；撞输出上限那条路强制走「继续」。
     *
     * v288 替换了 v267~v272 的「续跑思考模式」：思考回灌做不到真正续接思维链
     * （原生思考模型需要原样回传带签名的 reasoning 块），
     * 实测只会造成多段思考堆叠与重复打转，整体废弃。
     */
    val resumeStrategy: ResumeStrategy = ResumeStrategy.CONTINUE,
    /**
     * v268：对话级模型记忆（照 Chatbox 设计）。开启时每个对话记住自己最后一次使用的模型，
     * 打开/切回该对话自动恢复；新对话沿用最近使用的模型。关闭 = 所有对话共用助手模型（官方行为）。
     */
    val rememberModelPerConversation: Boolean = true,
    /**
     * v268：对话 → 模型 映射。已删除对话的残留条目无害（对应模型不存在时自动忽略），
     * 不做主动清理。
     */
    val conversationModelIds: Map<Uuid, Uuid> = emptyMap(),
    /**
     * v313：对话 → 群组设置。只有被显式建过群的对话才有 entry，
     * 没有 entry 的对话与改前完全一致（群功能不会牵连普通聊天）。
     */
    val groupChatConfigs: Map<Uuid, GroupChatConfig> = emptyMap(),
    /**
     * v316：用户自己存的讨论群方案（全局共享，可在任意对话套用）。
     * 默认空表 —— 没有方案的对话与改前完全一致（群功能不牵连普通聊天）。
     */
    val groupChatPresets: List<GroupChatPreset> = emptyList(),
    /**
     * v321：「群讨论只保留发言正文」。默认 false = 与 v320 逐字节一致。
     *
     * 打开后，**群讨论期间产生的**思考过程、工具调用与结果、搜索结果
     * ① 不再进任何成员的上文（每位成员只看大家说过的正文）
     * ② 解散/暂停群之后，主对话拼上下文时也不再带上这些内容
     *
     * 三条不动的东西（用户 v321 明确过）：开群前原对话的上文照旧整份带；
     * 成员向用户提的问题与用户的回答照旧保留；图片/文件/音视频照旧保留。
     * 界面上的讨论记录**一个字都不删**，用户仍能回看当时查过什么。
     */
    val groupChatCleanContext: Boolean = false,
    /** v268：手动压缩上次选择的模式（true = 经典压缩，默认智能压缩）。 */
    val compressUseClassicMode: Boolean = false,
    /**
     * v280：允许子代理检索历史对话。默认 false，只有用户在子代理设置里主动打开才生效。
     * 主对话与圆桌完全不看这个字段。
     */
    val allowAgentConversationSearch: Boolean = false,
    /**
     * v241：子代理单趟步数上限（只读位）。编程位自动取它的 1.5 倍。
     *
     * 「一步」= 一次完整的模型调用（含它这一轮想调的工具）。上限存在的唯一理由是
     * 防止模型陷进死循环反复烧钱；撞到上限不算失败，会标成「这趟没跑完」并从断点接着跑。
     */
    val agentMaxSteps: Int = 32,
    val agentRoleModelOverrides: Map<String, Uuid?> = emptyMap(),
    val agentFallbackModelIds: List<Uuid> = emptyList(),
    val skillOrder: List<String> = emptyList(),
) {
    /** v248：子代理工具返回上限（KB→字节），主对话不读它。 */
    val toolOutputCharLimit: Int
        get() = toolOutputLimitKb
            .coerceIn(TOOL_OUTPUT_LIMIT_MIN_KB, TOOL_OUTPUT_LIMIT_MAX_KB) * 1024

    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    // v323 合并：二改自建字段（官方类里没有的），全部带默认值
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showResumeCount: Boolean = true,
    /**
     * v270：重点标色插件开关（默认 false）。
     *
     * 用户原话：「不要对主模型参生影响，只是提供给模型一个可额外使用的插件，而不是
     * 像提示词一样注入影响 ai 自身判断」。关闭 = 零注入，模型全程不知道该功能存在；
     * 打开 = 系统提示词末尾附加一段中性说明（红/黄/绿标注语法，可用可不用）。
     * 渲染层零改动（界面对 HTML span 颜色标签的支持是现成的）。
     * DisplaySetting 整体 JSON 序列化，带默认值新字段对旧数据向下兼容，无需迁移。
     */
    val highlightKeyPoints: Boolean = false,
    /**
     * v294：强制翻阅 skill 模式。开 = 注入强制指令，模型首次回答前必须对每个已开启
     * skill 调一次 use_skill 翻阅入口说明（SKILL.md）；关（默认）= 菜单制，模型自判。
     * 无已开启 skill 时两种模式都零注入。
     */
    val forceSkillReview: Boolean = false,
    /**
     * v306：思考卡片单步可收起。开 = 思考步骤卡片只要非空就显示折叠控制条
     * （单步也能收起来），且折叠时全部收起、连最后一条也不留，正文阅读不受影响；
     * 关（默认）= 官方行为（超过两步才可折叠、折叠时保留尾部两步）。
     * 只影响 ChainOfThought 渲染，不影响模型输出。
     */
    val collapseThinkingCardFully: Boolean = false,
    // 输入框上方常显「当前上下文占用」，默认开启,
    val showContextUsage: Boolean = true,
    /**
     * v297：对话页顶栏显示「本对话 token 累计」，默认开启、可关。
     *
     * 与 showTokenUsage（每条消息下方那行）是两件事：这里是对话级累计，
     * 口径含每次工具调用与续跑重复发送的上下文，不含后台调用与子代理。
     * DisplaySetting 整体 JSON 序列化，带默认值的新字段对旧数据向下兼容，无需迁移。
     */
    val showConversationTokenStats: Boolean = true,
    /**
     * v317：讨论群组面板是否收起（用户口径：「这个太占空间了，给我做一个可以折叠起来的」）。
     *
     * 存这里而不是 rememberSaveable：用户要的是「收一次之后就一直收着、新对话也默认收」，
     * 页面级状态做不到（换对话就重置）。DisplaySetting 整块 JSON 序列化，
     * **带默认值的新字段对旧数据向下兼容、不需要 DataStore key、更不动数据库版本**。
     *
     * 默认 false = 首次是展开的（用户拍板「默认展开，你手动收一次之后就一直记住是收着的」）。
     * ⚠️ 字段名就是「是否收起」，别把默认值写成 true —— 那等于一装上就收着，
     * 用户根本不知道有这块内容（主模型 v317 自查时抓到过这个自相矛盾）。
     */
    val groupPanelCollapsed: Boolean = false,
    val notifyGroupChatWaiting: Boolean = true,

)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
