package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
// v274：Gemini 官方账号登录读侧过滤
import me.rerere.rikkahub.data.gemini.DENIED_MODEL_IDS
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.CodexCompaction
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.isLegacyCompressPrompt
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

/**
 * v274：逐条容错解码供应商列表（照搬 ExTV rikkahub-agent）。
 *
 * 为什么需要：providers 存在 DataStore 里是一个多态 JSON 数组，整表 decodeFromString
 * 只要遇到一个未知类型标记就会整表抛异常 —— 用户会丢掉全部已保存的供应商
 * （API key、自定义模型全都没了）。逐条解码让能解析的落库、未知的记日志跳过。
 * 对未来任何多态结构变化（改名、删除类型）都是保险。
 */
private fun decodeProvidersTolerant(raw: String): List<ProviderSetting> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        try {
            JsonInstant.decodeFromJsonElement<ProviderSetting>(element)
        } catch (e: SerializationException) {
            Log.w(TAG, "Skipping unrecognised provider entry during decode: ${e.message}")
            null
        }
    }
}

/**
 * v316：群方案列表的**逐条**容错解码（复审位抓的 P2：原本整表 decodeFromString，
 * 一条坏数据会让 8 个方案全部读不出来 = 用户以为方案全丢了）。
 *
 * 写法照上面的 [decodeProvidersTolerant]，是本项目 v274 定下的同一套做法。
 */
internal fun decodeGroupPresetsTolerant(raw: String): List<GroupChatPreset> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        try {
            JsonInstant.decodeFromJsonElement<GroupChatPreset>(element)
        } catch (e: SerializationException) {
            Log.w(TAG, "Skipping unrecognised group preset entry during decode: ${e.message}")
            null
        }
    }.take(GroupChatPreset.MAX_PRESETS)
}

/**
 * v274：GeminiOAuth 归一化分支的 models 变换（照搬 ExTV）：按 id 去重，
 * 再剔除 modelId 命中 [DENIED_MODEL_IDS] 的条目。
 *
 * GeminiProvider 拉模型列表时的过滤只能挡住「重新拉取」；在那之前已经持久化的副本
 * 会原样活在合并逻辑里。这里在每次设置加载时做读侧驱逐，无需迁移、不改已存 JSON。
 */
private fun dropDeniedGeminiOAuthModels(models: List<Model>): List<Model> =
    models.distinctBy { model -> model.id }.filterNot { model -> model.modelId in DENIED_MODEL_IDS }

private const val SETTINGS_STORE_NAME = "settings"

// v311：跟随上游 2.5.5 —— 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        ),
        produceFile = { file },
    )
}

class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : KoinComponent {
    // v323：主构造只给单元测试注入可控磁盘用（SettingsStoreTest 要卡住写入，验证
    // 「页面关闭时取消写入后，内存里已改的值仍会落盘」）。生产路径一律走下面这个构造，
    // context.settingsStore 仍是官方 3.1 的进程内单例，行为与官方完全一致。
    constructor(context: Context, scope: AppScope) : this(context.settingsStore, scope)
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        // v250：跟随上游 2.4.11 的「网络设置」（自定义访问标识 + 代理）
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val MEMORY_MODEL = stringPreferencesKey("memory_model")
        val MEMORY_MODEL_REASONING_LEVEL = stringPreferencesKey("memory_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        // v323 合并 3.1.0 新增字段的 DataStore key
        val ENABLE_AUTO_TITLE = booleanPreferencesKey("enable_auto_title")
        val TRANSLATE_TARGET_LANGUAGE = stringPreferencesKey("translate_target_language")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")

        // 子代理（v219）
        val AGENT_MAX_CONCURRENT = intPreferencesKey("agent_max_concurrent")
        val AGENT_ROLE_MODELS = stringPreferencesKey("agent_role_models")

        // v237：子代理备用模型（最多 3 个，按顺序兜底）。
        val AGENT_FALLBACK_MODELS = stringPreferencesKey("agent_fallback_models")

        // v238：单个子代理的超时分钟数（0 = 不限时）。
        val AGENT_THREAD_TIMEOUT = intPreferencesKey("agent_thread_timeout_minutes")

        /**
         * v246：子代理**单个工具**最长允许跑多久（分钟）。
         *
         * 为什么和「卡住判定」分开：工具是同步执行的，执行期间一个数据块都不会回来，
         * 所以 v245 起工具时间不算进「多久没动静」。但工具自己必须有上限，
         * 否则一个挂死的搜索会一直挂着。此前这个上限写死在代码里（10 分钟），
         * 用户把卡住判定设成 5 分钟却发现 8 分钟都没反应，只会以为设置没生效。
         */
        val AGENT_TOOL_TIMEOUT = intPreferencesKey("agent_tool_timeout_minutes")

        /**
         * v248：**子代理**单次工具返回的上限（KB）。
         *
         * ⚠️ **只作用于子代理。主对话与圆桌一个字都没动**，仍走
         * `GenerationLoop.MAX_TOOL_OUTPUT_CHARS` 那个写死的 32KB。
         * 用户红线原话：「你子代理可以随便动，不好用大不了以后不用，
         * 但是主模型绝对不能乱动，不能做出任何限制」——
         * 所以这个设置只由子代理后端显式传给生成层，生成层自己绝不去读它。
         *
         * 为什么子代理需要放开：它没有终端，被截掉的部分拿不回来，只能分成好几段读；
         * 而它跑的是廉价模型、结果只回一份摘要，读多少都不污染主对话。
         *
         * 唯一的真实风险是**模型上下文窗口**：窗口只有 32K token 的模型，一次塞 256KB
         * 会直接请求失败（不是变慢，是报错）。所以给档位而不是彻底无上限。
         */
        val TOOL_OUTPUT_LIMIT_KB = intPreferencesKey("tool_output_limit_kb")

        /**
         * v241：子代理自动续跑次数上限（0 = 完全不自动续跑）。
         *
         * 用户原话：「直接给我个选项，让我可以自主选择续跑次数。」
         * 此前这个数字写死在代码里（2 次），既不能调高（长任务不够）也不能关掉
         * （不想让它自己花钱时没有开关）。
         */
        val AGENT_AUTO_RESUME_MAX = intPreferencesKey("agent_auto_resume_max")

        /**
         * v263：主对话「异常截断自动续跑」次数上限（回复因输出长度上限被掐断时自动接着写）。
         */
        val TRUNCATION_AUTO_RESUME_MAX = intPreferencesKey("truncation_auto_resume_max")

        /**
         * v304：技能（skills）列表的显示顺序，存的是技能**目录名**（按用户拖动后的排列）。
         * 目录名是唯一稳定标识（frontmatter 里的 name 可能重复、也可能被改）。
         * 只影响列表顺序与注入顺序，不影响技能内容与「哪些技能被启用」。
         * 默认空 = 按目录名排（确定性顺序，不再是文件系统随机顺序）。
         */
        val SKILL_ORDER = stringPreferencesKey("skill_order")

        /**
         * v241：子代理单趟步数上限（只读位；编程位自动取它的 1.5 倍）。
         *
         * 用户原话：「为什么要限制这个步数？不会导致关键时候差一点搜索完结果截断么」——
         * 会。所以这个数字必须能自己调，而不是写死在代码里。
         * 上限存在的唯一理由是防止模型陷入死循环反复烧钱，不是为了截断结果。
         */
        val AGENT_MAX_STEPS = intPreferencesKey("agent_max_steps_readonly")

        // v220 修复：这两项 v219 漏了持久化，导致设置过的子代理模型/开关会被 DataStore
        // 下一次发射用默认值覆盖（表现为“模型自己消失、恢复默认”）。
        val AGENT_ENABLED = booleanPreferencesKey("agent_tools_enabled")

        /**
         * v288：续跑方式。用户二选一：
         * · CONTINUE   = 发送「继续」，让模型接着已写内容往下写（原有行为，默认）；
         * · REGENERATE = 重新生成最后一次输出（丢掉这一趟写坏的半截，原样重发同一请求，
         *   照官方网络重试的做法——不是整个对话重新生成）。
         *
         * v288 换新键：v272 那个思考回灌开关已整体废弃，旧键弃置不读（残值无害）。
         * 撞输出上限那条路强制走 CONTINUE，不受本设置影响——
         * 重新生成必然再撞同一个上限，纯烧钱。
         */
        val RESUME_STRATEGY_V288 = stringPreferencesKey("resume_strategy_v288")

        /** v268：对话级模型记忆开关。 */
        val REMEMBER_MODEL_PER_CONVERSATION = booleanPreferencesKey("remember_model_per_conversation")

        /** v268：对话 → 最后使用模型 的映射（JSON: Map<conversationId, modelId>，均为 Uuid 字符串）。 */
        val CONVERSATION_MODEL_IDS = stringPreferencesKey("conversation_model_ids")

        /**
         * v313：对话 → 群组设置 的映射（JSON: Map<conversationId, GroupChatConfig>）。
         *
         * 刻意存 DataStore 而不是给 Conversation 加列：AppDatabase 必须停在 25，
         * 加列＝升版本＋迁移，风险落在用户全部聊天记录上（禁区 #1）。
         * 与 v268 那份映射同一套取舍：已删除对话的残留条目无害，不主动清理。
         */
        val GROUP_CHAT_CONFIGS = stringPreferencesKey("group_chat_configs")

        /**
         * v316：用户自己存的讨论群**方案**列表（全局共享，不按对话隔离）。
         *
         * 与 GROUP_CHAT_CONFIGS 的区别必须分清：那份是「某个对话当前的群配置」，
         * 这份是「用户攒好的、可以反复套用的整套配置」。存成 JSON 数组而不是 map，
         * 因为方案要保序（用户说的「选择方案几」就是按顺序数出来的）。
         * 同样刻意存 DataStore —— AppDatabase 必须停在 25（禁区 #1）。
         */
        val GROUP_CHAT_PRESETS = stringPreferencesKey("group_chat_presets")

        /**
         * v321：「群讨论只保留发言正文」——**全局**开关，默认关。
         *
         * 为什么存全局而不塞进 GroupChatConfig：这条设置要在「解散群之后主对话拼上下文」
         * 那条路上也读得到，而那份群配置可能被用户「彻底删除」（v315 就有这个动作）。
         * 存在群配置里一旦配置被删就静默失效，那是最难查的一类毛病。
         * 群组设置面板里那一栏只是把这个全局值露出来给用户拨，不另存一份。
         *
         * 读取一律用 `== true`（不是 `!= false`）：缺 key 时必须拿到 false = 行为与 v320 一致。
         */
        val GROUP_CHAT_CLEAN_CONTEXT = booleanPreferencesKey("group_chat_clean_context")

        /** v268：手动压缩上次选择的模式（true = 经典压缩）。 */
        val COMPRESS_USE_CLASSIC_MODE = booleanPreferencesKey("compress_use_classic_mode")

        /**
         * v280：允许**子代理**检索用户的历史对话（默认关）。
         *
         * 用户原话：「选a，可以自主选择给子代理记忆的权限。」
         *
         * 默认关是刻意的：conversation_search 搜的是整个库、不按助手过滤，返回的是对话内容
         * 片段，而子代理跑的往往是另一家的廉价模型 —— 打开就等于把历史对话内容发给那家
         * 服务商。主对话那条路完全不受这个开关影响（它自己看助手的
         * enableRecentChatsReference），子代理的隔离助手副本里那行 false 也一律不动。
         */
        val AGENT_CONVERSATION_SEARCH = booleanPreferencesKey("agent_conversation_search")
        val AGENT_MODEL = stringPreferencesKey("agent_model")

        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 文件管理器：用户通过系统授权的共享存储目录
        val SHARED_STORAGE_TREE_URIS = stringPreferencesKey("shared_storage_tree_uris")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒

        // 特殊模型的自动压缩触发值（只对被选中的模型生效）
        val AUTO_COMPRESS_MODEL_OVERRIDES = stringPreferencesKey("auto_compress_model_overrides")

        // 圆桌模式（多模型同上文各出方案 + 主模型综合）
        val ROUND_TABLE_SETTING = stringPreferencesKey("round_table_setting")

        // v323：二改字段「工作区命令时长上限」。v322 只有 Settings 字段却没有 key/读写，
        // 用户调完重启就回到默认（0 = 不限时）；本轮补上。
        val WORKSPACE_COMMAND_TIMEOUT = intPreferencesKey("workspace_command_timeout_seconds")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(
            context: Context,
            settings: Settings,
            launchCount: Int = 0,
        ) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
            context.settingsStore.edit { it[LAUNCH_COUNT] = launchCount }
        }

        // v288 合并上游 2.4.17：官方把落盘逻辑抽成这个函数（备份恢复会在 Koin 之前调用它）。
        // 这里必须保留我们全部二改字段的写入环节 —— 少一条，用户设置就会在下一次 DataStore 发射时变回默认。
        private suspend fun persistSettings(dataStore: DataStore<Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                // v1017：官方记忆整理模型。留空时删除旧 key，避免把“跟随快速模型”的默认值伪装成用户显式选择。
                settings.memoryModelId?.let { memoryModelId -> preferences[MEMORY_MODEL] = memoryModelId.toString() } ?: preferences.remove(MEMORY_MODEL)
                preferences[MEMORY_MODEL_REASONING_LEVEL] = settings.memoryModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                preferences[TITLE_PROMPT] = settings.titlePrompt
                preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                preferences[OCR_MODEL] = settings.ocrModelId.toString()
                preferences[OCR_PROMPT] = settings.ocrPrompt
                preferences[AGENT_MAX_CONCURRENT] = settings.agentMaxConcurrent.coerceIn(1, 8)
                // v238：超时分钟数必须跟着落盘，否则下一次 DataStore 发射会恢复默认
                preferences[AGENT_THREAD_TIMEOUT] = settings.agentThreadTimeoutMinutes.coerceIn(0, 120)
                // v246：工具上限同样必须落盘，否则下一次 DataStore 发射会恢复默认
                preferences[AGENT_TOOL_TIMEOUT] = settings.agentToolTimeoutMinutes.coerceIn(1, 60)
                // v248：工具返回上限同样必须落盘（少了这一环，用户调完下一次发射就恢复默认）
                preferences[TOOL_OUTPUT_LIMIT_KB] = settings.toolOutputLimitKb
                    .coerceIn(TOOL_OUTPUT_LIMIT_MIN_KB, TOOL_OUTPUT_LIMIT_MAX_KB)
                preferences[AGENT_AUTO_RESUME_MAX] = settings.agentAutoResumeMax.coerceIn(0, 10)
                preferences[TRUNCATION_AUTO_RESUME_MAX] = settings.truncationAutoResumeMax.coerceIn(0, 99)
                preferences[SKILL_ORDER] = encodeSkillOrder(settings.skillOrder)
                preferences[RESUME_STRATEGY_V288] = settings.resumeStrategy.name
                preferences[REMEMBER_MODEL_PER_CONVERSATION] = settings.rememberModelPerConversation
                preferences[CONVERSATION_MODEL_IDS] = JsonInstant.encodeToString(
                    settings.conversationModelIds.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                )
                // v313：群组设置。键手工转成字符串 —— 与上面 conversationModelIds 同一套写法，
                // 不依赖 Uuid 当 JSON map 键的序列化能力（值本身是 @Serializable，直接整份序列化）。
                preferences[GROUP_CHAT_CONFIGS] = JsonInstant.encodeToString(
                    settings.groupChatConfigs.mapKeys { it.key.toString() }
                )
                preferences[COMPRESS_USE_CLASSIC_MODE] = settings.compressUseClassicMode
                // v316：用户方案列表（数组保序，无需转键）。空列表也照写 ——
                // 只在「一条都没有」时删掉这个键会让 DataStore 里残留旧数据，反而更糟。
                preferences[GROUP_CHAT_PRESETS] = JsonInstant.encodeToString(settings.groupChatPresets)
                // v321：「群讨论只保留发言正文」全局开关
                preferences[GROUP_CHAT_CLEAN_CONTEXT] = settings.groupChatCleanContext
                preferences[AGENT_CONVERSATION_SEARCH] = settings.allowAgentConversationSearch
                preferences[AGENT_MAX_STEPS] = settings.agentMaxSteps.coerceIn(16, 96)
                // v220 修复：子代理开关与默认模型必须落盘，否则下一次 DataStore 发射会恢复默认
                preferences[AGENT_ENABLED] = settings.enableAgentTools
                settings.agentModelId?.let {
                    preferences[AGENT_MODEL] = it.toString()
                } ?: preferences.remove(AGENT_MODEL)
                preferences[AGENT_ROLE_MODELS] =
                    encodeAgentRoleModelOverrides(settings.agentRoleModelOverrides)
                // v237：备用模型必须跟着落盘。历史上 v219 就是因为「只读不写」，
                // 用户配好的子代理模型会被 DataStore 下一次发射用默认值覆盖（v220 才修）。
                preferences[AGENT_FALLBACK_MODELS] =
                    encodeAgentFallbackModelIds(settings.agentFallbackModelIds)
                preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
                preferences[COMPRESS_PROMPT] = settings.compressPrompt

                preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

                preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

                preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                // v288 合并上游 2.4.17：官方补的下界保护，搜索服务为空时 coerceIn(0, -1) 会抛异常
                preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))

                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                settings.selectedTTSProviderId?.let {
                    preferences[SELECTED_TTS_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                settings.selectedASRProviderId?.let {
                    preferences[SELECTED_ASR_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                preferences[WEB_SERVER_PORT] = settings.webServerPort
                preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                preferences[SHARED_STORAGE_TREE_URIS] = JsonInstant.encodeToString(settings.sharedStorageTreeUris)
                preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                preferences[LAUNCH_COUNT] = settings.launchCount
                preferences[AUTO_COMPRESS_MODEL_OVERRIDES] =
                    JsonInstant.encodeToString(settings.autoCompressModelOverrides)
                preferences[ROUND_TABLE_SETTING] = JsonInstant.encodeToString(settings.roundTableSetting)
                // v323：工作区命令时长上限也要落盘，否则用户调完下一次发射就恢复默认
                preferences[WORKSPACE_COMMAND_TIMEOUT] =
                    settings.workspaceCommandTimeoutSeconds.coerceIn(0, 7200)
                // v1007：备份恢复走的是这条**手写**写路径（不经过 SETTINGS_FIELDS）。
                // 少了官方 3.1 这 5 个字段 → 恢复备份后「自动标题开关 / 翻译目标语言 /
                // 上传 S3 桶 / 画图供应商 / 赞助提示时间」会退回默认或本机旧值。
                // 读路径在 573-586 行，两边必须成对维护（审查位 C 抓到）。
                preferences[ENABLE_AUTO_TITLE] = settings.enableAutoTitle
                preferences[TRANSLATE_TARGET_LANGUAGE] = settings.translateTargetLanguage
                preferences[UPLOAD_S3_CONFIG] = JsonInstant.encodeToString(settings.uploadS3Config)
                preferences[MEDIA_GENERATION_PROVIDERS] =
                    JsonInstant.encodeToString(settings.mediaGenerationProviders)
                preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            }
        }
    }

    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                // v1017：官方记忆整理模型的读取路径；缺 key 必须保持 null（跟随快速模型）。
                memoryModelId = preferences[MEMORY_MODEL]?.let { Uuid.parse(it) },
                memoryModelReasoningLevel = preferences[MEMORY_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                // v323：官方 3.1 新增的 5 个字段。写路径（SETTINGS_FIELDS）有、读路径漏了 ——
                // 每个进程第一次改设置会整份落盘，把用户已存的画图供应商 / 上传 S3 桶 /
                // 自动标题开关覆盖成默认值（审查位 A/B 双派都抓到）。
                enableAutoTitle = preferences[ENABLE_AUTO_TITLE] != false,
                translateTargetLanguage = preferences[TRANSLATE_TARGET_LANGUAGE] ?: "zh-CN",
                uploadS3Config = preferences[UPLOAD_S3_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<S3Config>(it) }.getOrDefault(S3Config())
                } ?: S3Config(),
                mediaGenerationProviders = preferences[MEDIA_GENERATION_PROVIDERS]?.let {
                    runCatching {
                        JsonInstant.decodeFromString<List<MediaGenerationProviderSetting>>(it)
                    }.getOrDefault(emptyList())
                } ?: emptyList(),
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                agentMaxConcurrent = (preferences[AGENT_MAX_CONCURRENT] ?: 4).coerceIn(1, 8),
                agentThreadTimeoutMinutes = (preferences[AGENT_THREAD_TIMEOUT] ?: 10).coerceIn(0, 120),
                agentToolTimeoutMinutes = (preferences[AGENT_TOOL_TIMEOUT] ?: 10).coerceIn(1, 60),
                toolOutputLimitKb = (preferences[TOOL_OUTPUT_LIMIT_KB] ?: TOOL_OUTPUT_LIMIT_DEFAULT_KB)
                    .coerceIn(TOOL_OUTPUT_LIMIT_MIN_KB, TOOL_OUTPUT_LIMIT_MAX_KB),
                agentAutoResumeMax = (preferences[AGENT_AUTO_RESUME_MAX] ?: 2).coerceIn(0, 10),
                truncationAutoResumeMax = (preferences[TRUNCATION_AUTO_RESUME_MAX] ?: 3).coerceIn(0, 99),
                skillOrder = decodeSkillOrder(preferences[SKILL_ORDER]),
                resumeStrategy = preferences[RESUME_STRATEGY_V288]
                    ?.let { value -> ResumeStrategy.entries.find { it.name == value } }
                    ?: ResumeStrategy.CONTINUE,
                rememberModelPerConversation = preferences[REMEMBER_MODEL_PER_CONVERSATION] != false,
                conversationModelIds = preferences[CONVERSATION_MODEL_IDS]?.let { raw ->
                    runCatching {
                        JsonInstant.decodeFromString<Map<String, String>>(raw)
                            .mapKeys { Uuid.parse(it.key) }
                            .mapValues { Uuid.parse(it.value) }
                    }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                // v313：群组设置。解不出来的坏 JSON 一律退回空表 ——
                // 这份设置坏了不能连带把整个 Settings 读崩（手册里「设置读取失败被重置」
                // 就是官方真踩过的同类事故），所以这里必须 runCatching。
                groupChatConfigs = preferences[GROUP_CHAT_CONFIGS]?.let { raw ->
                    runCatching {
                        JsonInstant.decodeFromString<Map<String, GroupChatConfig>>(raw)
                            .mapNotNull { (key, value) ->
                                runCatching { Uuid.parse(key) }.getOrNull()?.let { it to value }
                            }
                            .toMap()
                    }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                compressUseClassicMode = preferences[COMPRESS_USE_CLASSIC_MODE] == true,
                // v316：方案列表。坏 JSON / 坏结构一律退回空表 —— 与 groupChatConfigs 同一理由：
                // 这份设置坏了绝不能连带把整个 Settings 读崩。条数在读取时夹住上限，
                // 免得手改或旧版本残留把界面撑爆。
                groupChatPresets = preferences[GROUP_CHAT_PRESETS]?.let { raw ->
                    decodeGroupPresetsTolerant(raw)
                } ?: emptyList(),
                // v321：必须是 false 当缺 key（老存档没有这个键）—— 用 `== true`，不用 `!= false`
                groupChatCleanContext = preferences[GROUP_CHAT_CLEAN_CONTEXT] == true,
                // v280：默认必须是关 —— 缺 key 时要得到 false，所以用 `== true` 而不是 `!= false`
                allowAgentConversationSearch = preferences[AGENT_CONVERSATION_SEARCH] == true,
                agentMaxSteps = (preferences[AGENT_MAX_STEPS] ?: 32).coerceIn(16, 96),
                // v220 修复：读回子代理开关与默认模型（v219 只写不读/只读不写，会被默认值覆盖）
                enableAgentTools = preferences[AGENT_ENABLED] != false,
                agentModelId = preferences[AGENT_MODEL]
                    ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() },
                agentRoleModelOverrides = decodeAgentRoleModelOverrides(preferences[AGENT_ROLE_MODELS]),
                agentFallbackModelIds = decodeAgentFallbackModelIds(preferences[AGENT_FALLBACK_MODELS]),
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = decodeProvidersTolerant(preferences[PROVIDERS] ?: "[]"), // v274：整表解码改逐条容错
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: DEFAULT_MODE_INJECTIONS,
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                sharedStorageTreeUris = preferences[SHARED_STORAGE_TREE_URIS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                autoCompressModelOverrides = preferences[AUTO_COMPRESS_MODEL_OVERRIDES]?.let {
                    runCatching { JsonInstant.decodeFromString<List<AutoCompressModelOverride>>(it) }
                        .getOrDefault(emptyList())
                } ?: emptyList(),
                roundTableSetting = preferences[ROUND_TABLE_SETTING]?.let {
                    runCatching { JsonInstant.decodeFromString<RoundTableSetting>(it) }
                        .getOrDefault(RoundTableSetting())
                } ?: RoundTableSetting(),
                // v323：0 = 不限时；夹在 0~7200 秒，避免手改存档把界面撑爆
                workspaceCommandTimeoutSeconds =
                    (preferences[WORKSPACE_COMMAND_TIMEOUT] ?: 0).coerceIn(0, 7200),
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            // v305（用户拍板「没填过密钥的才删，填过的保留不动」）：内置清单已移除这几家
            // 广告位（见 DefaultProviders 与 v304 FINAL 快照留档），但老用户设备上是把
            // 它们存进过设置数据的，不会自己消失，所以在这里按用户口径清一次。
            providers = providers.filterNot { it.shouldDropRemovedAdProvider() }.toMutableList()
            // v323：官方 3.1 的 copyProvider 签名收紧了（不再接受这些命名参数）。
            // 内置条目的角标/说明由界面按 id 现查，这里不再回写，行为不受影响。
            providers = providers.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            var ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider)
                }
            }
            // v305（用户拍板「一并清掉」）：清掉语音合成表里预置的 AiHubMix 广告位，
            // 同口径 —— 填过密钥的保留不动。
            ttsProviders = ttsProviders.filterNot { it.shouldDropRemovedAdTtsProvider() }.toMutableList()
            // v311：消除跨供应商的重复模型编号（用户真机反馈「同名模型换了供应商的
            // 那个，实际还是在跑旧的；候选链里的同名替补被直接跳过」）。
            // 口径是「保留第一个持有者的编号、只给后面撞号的重发」，因此所有既有引用
            // 的实际指向不变（编号重复时 findModelById 本来就只会返回第一个持有者）。
            // 刻意不回写 DataStore：这段在读设置的 map 里，往里写会自触发重算；
            // 重发是确定性的（同一份脏数据每次算出同一批新号），而用户任何一次改设置
            // 都会把修好的列表落盘，自然收敛。
            val deduped = dedupeModelIds(providers)
            if (deduped.reissued > 0) {
                Log.w(TAG, "v311: reissued ${deduped.reissued} duplicated model ids")
            }
            it.copy(
                providers = deduped.providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val allProviderIds = settings.providers.map { it.id }.toSet()
            // 上一版二改曾把 providerIds 当作“白名单”，现改为“排除名单”语义；
            // 读取设置时把旧白名单换算成等价排除名单，保证已保存配置的行为不变。
            fun migrateCustomBody(body: CustomBody): CustomBody {
                val legacyWhitelist = body.providerIds ?: return body
                return body.copy(
                    providerIds = null,
                    excludedProviderIds = allProviderIds - legacyWhitelist,
                )
            }
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            // 旧版压缩提示词(带占位符)自动重置为 Codex 提示词
            val migratedCompressPrompt = if (isLegacyCompressPrompt(settings.compressPrompt)) {
                DEFAULT_COMPRESS_PROMPT
            } else {
                settings.compressPrompt
            }
            settings.copy(
                compressPrompt = migratedCompressPrompt,
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }.map { model ->
                                model.copy(customBodies = model.customBodies.map(::migrateCustomBody))
                            }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }.map { model ->
                                model.copy(customBodies = model.customBodies.map(::migrateCustomBody))
                            }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }.map { model ->
                                model.copy(customBodies = model.customBodies.map(::migrateCustomBody))
                            }
                        )

                        // v274：三套官方账号 OAuth 登录（照搬 ExTV）
                        is ProviderSetting.Codex -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Grok -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.GeminiOAuth -> provider.copy(
                            models = dropDeniedGeminiOAuthModels(provider.models)
                        )

                        // v323：Gemini 网页版（cookie 直连）
                        is ProviderSetting.GeminiWeb -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        // v323 合并 3.1.0：官方新增的 ChatGPT 账号型提供商
                        is ProviderSetting.ChatGPT -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 旧版保存的是上下文窗口大小，按旧版 90% 规则换算成新的直接触发值。
                        autoCompressTriggerTokens = assistant.legacyAutoCompressContextWindow
                            ?.let { legacyWindow ->
                                val migratedWindow = when (legacyWindow) {
                                    200_000, 250_000 -> 272_000
                                    else -> legacyWindow
                                }
                                CodexCompaction.autoCompactTokenLimit(migratedWindow)
                                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                                    .toInt()
                            }
                            ?: assistant.autoCompressTriggerTokens.coerceAtLeast(1),
                        // 迁移完成后清空旧字段，避免用户后续设置自定义阈值又被旧值覆盖。
                        legacyAutoCompressContextWindow = null,
                        // 旧版 Body 白名单迁移为排除名单
                        customBodies = assistant.customBodies.map(::migrateCustomBody),
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id }.map { injection ->
                    // 旧版模式注入只有一个 targetModelId，迁移为新的多选集合。
                    if (injection is PromptInjection.ModeInjection &&
                        injection.targetModelIds.isEmpty()
                    ) {
                        injection.targetModelId?.let { legacyTargetId ->
                            injection.copy(
                                targetModelId = null,
                                targetModelIds = setOf(legacyTargetId),
                            )
                        } ?: injection
                    } else {
                        injection
                    }
                },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            // v323：官方 3.1 新增「设置变化时清模板缓存」。本地单元测试没有启动 Koin，
            // 直接 get<>() 会抛 "KoinApplication has not been started"，把整个测试进程带崩
            // （ProviderAccountManagerTest 那次崩溃就是它）。这里取不到就跳过：
            // 生产环境容器一定在，行为与官方完全一致。
            runCatching { get<PebbleEngine>().templateCache.invalidateAll() }
        }

    private val state = MutableStateFlow(Settings.dummy())

    /** 当前设置。加载完成前是 [Settings.dummy]，需要真实设置时用 [awaitLoaded]。 */
    val settingsFlow: StateFlow<Settings> = state.asStateFlow()

    init {
        // v323：官方 3.1 的结构 —— 启动时只读一次盘，之后 state 只由 update 驱动。
        // 旧实现用 toMutableStateFlow 持续收集 DataStore：每次写盘回流都会把内存里
        // 「刚改完、还没写完」的值覆盖回旧值（并发更新丢键、打字丢字就是这个原因）。
        // 整份设置的解码很重，不能放在主线程。
        scope.launch(Dispatchers.Default) {
            state.value = try {
                settingsFlowRaw.first().normalized()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 读不出设置就不能带着占位值继续运行：界面上是一份默认设置，而任何修改都落不了盘
                Log.e(TAG, "Failed to load settings", e)
                Runtime.getRuntime().halt(1)
                throw e
            }
        }
    }

    /** v323：官方 3.1 的启动次数流（备份里要用）。 */
    val launchCountFlow = settingsFlow.map { it.launchCount }

    suspend fun awaitLoaded(): Settings = state.first { !it.init }

    suspend fun setLaunchCount(count: Int) {
        dataStore.edit { it[LAUNCH_COUNT] = count }
        update { it.copy(launchCount = count) }
    }

    suspend fun selectAssistant(assistantId: Uuid) {
        update { it.copy(assistantId = assistantId) }
    }

    // 已经落盘的那一份，只在持有 persistMutex 时读写。
    // 启动后第一次写入前是 null，那一次整份写入：读盘时规整过、补出来的值要固定到盘上。
    private var persisted: Settings? = null
    private val persistMutex = Mutex()
    private val stateLock = Any()

    suspend fun update(settings: Settings) {
        if (settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        synchronized(stateLock) { state.value = settings }
        persist()
    }

    suspend fun update(fn: (Settings) -> Settings) {
        // v323：官方 3.1 修好的关键逻辑，合并时被 v322 的旧实现盖掉了 ——
        // 加载完成前的修改必须等真实设置出来再应用，否则会拿默认值把用户全部设置覆盖掉。
        // 已加载时这里不能挂起：输入框、slider 依赖修改同步生效。
        if (settingsFlow.value.init) awaitLoaded()
        synchronized(stateLock) {
            val updated = fn(settingsFlow.value)
            if (updated.init) {
                Log.w(TAG, "Cannot update dummy settings")
                return
            }
            // 规整会重建提供商、助手等对象，而界面按引用判断它们有没有变：
            // 规整没改动任何东西时保留传入的实例。
            val normalized = updated.normalized()
            state.value = if (normalized == updated) updated else normalized
        }
        persist()
    }

    // v323：连续修改只写最新的一份（官方 3.1 的行为）。旧实现每次都整份重写，
    // 慢盘时会把「用户输入到一半」的中间值反复写下去，也会把没变的字段重写一遍。
    // 不跟着调用方取消：页面关闭（viewModelScope 取消）时修改已经在内存里生效，不能只差落盘这一步。
    private suspend fun persist() {
        withContext(NonCancellable + Dispatchers.Default) {
            persistMutex.withLock {
                val latest = settingsFlow.value
                val base = persisted
                if (latest !== base) {
                    dataStore.edit { preferences -> preferences.putSettings(latest, base) }
                    persisted = latest
                }
            }
        }
    }

    // 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        // v323：同步内存态。launchCount 仍在 SETTINGS_FIELDS 里，下次整份 persist 会拿
        // state 里的旧值把它覆盖回去；同步后 persisted 才能检测到变化。
        state.value = state.value.copy(launchCount = count)
        return count
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        // v299：这条路径原来只直写 DataStore 的单个键，**不动内存态 settingsFlow**。
        // 于是「打开对话 → ChatService 切当前助手」与「ChatVM 恢复本对话上次用的模型 →
        // 全量落盘（persistSettings 会连 SELECT_ASSISTANT 一起写）」成为两条互相打架的写入：
        // 恢复那次带的是切助手之前的旧 assistantId，只要它后提交，刚切好的当前助手就被回写，
        // 界面（读「当前助手的 chatModelId」）于是停在旧模型上。真机现象就是
        // 「第一次从文件夹外切到文件夹内，模型还是外面对话的；切走再回来才对」——
        // 因为第二次进同一对话时记忆模型已经写进目标助手，恢复分支不再落盘，冲突消失。
        // 这里顺手把内存态也同步掉，两条通道从此一致。
        val current = settingsFlow.value
        if (!current.init && current.assistantId != assistantId) {
            state.value = current.copy(assistantId = assistantId)
        }
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(
        assistantId: Uuid,
        reasoningLevel: ReasoningLevel,
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            reasoningLevel = reasoningLevel,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

/**
 * v220：子代理角色级模型的持久化编解码（抽成纯函数，便于单元测试）。
 *
 * v219 的写法把 `Map<String, Uuid?>` 直接编码成含 null 值的 JSON，
 * 读回时用 `Map<String, String>` 反序列化会抛异常并被 runCatching 吞掉，
 * 结果**整张表一起丢失**（用户表现：角色模型自己消失、恢复默认）。
 *
 * 现在：写入时丢掉 null 值（等价于“跟随默认模型”），读取时对 null 值与坏 UUID 逐项容错，
 * 单条坏数据不会连带清空其他角色的设置。
 */
internal fun encodeAgentRoleModelOverrides(overrides: Map<String, Uuid?>): String =
    JsonInstant.encodeToString(
        overrides.mapNotNull { (role, id) ->
            val key = role.trim().lowercase()
            if (key.isBlank() || id == null) null else key to id.toString()
        }.toMap()
    )

internal fun decodeAgentRoleModelOverrides(raw: String?): Map<String, Uuid?> {
    if (raw.isNullOrBlank()) return emptyMap()
    val decoded = runCatching {
        JsonInstant.decodeFromString<Map<String, String?>>(raw)
    }.getOrNull() ?: return emptyMap()
    val result = mutableMapOf<String, Uuid?>()
    decoded.forEach { (role, value) ->
        val key = role.trim().lowercase()
        val id = value?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (key.isNotBlank() && id != null) {
            result[key] = id
        }
    }
    return result
}

/**
 * v237：子代理备用模型列表的编解码。
 *
 * 为什么需要它：v236 的候选链是「线程点名 → 角色覆盖 → 子代理默认 → 主对话模型」，
 * 用户什么都不配时这四项会去重塌成 1 个模型，`if (index > 0)` 恒假，
 * 换人代码一次都进不去 —— 「备用模型保底」等于没做。
 * 这里让用户能显式指定兜底模型，链子至少 2 节，换人才真的会发生。
 *
 * 上限 3 个：够跨两三家服务商兜底，又不至于失败时把配额烧一大圈。
 */
internal const val AGENT_FALLBACK_MODEL_LIMIT = 3

internal fun encodeAgentFallbackModelIds(ids: List<Uuid>): String =
    JsonInstant.encodeToString(
        ids.distinct().take(AGENT_FALLBACK_MODEL_LIMIT).map { it.toString() }
    )

internal fun decodeAgentFallbackModelIds(raw: String?): List<Uuid> {
    if (raw.isNullOrBlank()) return emptyList()
    val decoded = runCatching {
        JsonInstant.decodeFromString<List<String>>(raw)
    }.getOrNull() ?: return emptyList()
    return decoded
        .mapNotNull { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
        .distinct()
        .take(AGENT_FALLBACK_MODEL_LIMIT)
}

/**
 * v304：技能顺序的编解码。
 *
 * 为什么存目录名：SkillPaths 解析技能用的就是目录名，也是唯一稳定标识；
 * 显示名（frontmatter 的 name）可能重复或随时被用户改。
 * 去重、去空白，上限 200 条 —— 防止异常数据把设置撑大。
 */
internal const val SKILL_ORDER_LIMIT = 200

internal fun encodeSkillOrder(names: List<String>): String =
    JsonInstant.encodeToString(
        names.mapNotNull { name -> name.trim().takeIf { it.isNotEmpty() } }
            .distinct()
            .take(SKILL_ORDER_LIMIT)
    )

internal fun decodeSkillOrder(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { JsonInstant.decodeFromString<List<String>>(raw) }
        .getOrDefault(emptyList())
        .mapNotNull { name -> name.trim().takeIf { it.isNotEmpty() } }
        .distinct()
        .take(SKILL_ORDER_LIMIT)
}

@Serializable

/**
 * v248：**子代理**工具返回上限的档位（KB）。
 *
 * 上游对任何工具返回写死 32KB，超了只给模型 4KB 预览。子代理没有终端、拿不回被截掉的部分，
 * 所以给它一个可调档位（详见 [Settings.toolOutputLimitKb]）。**主对话仍是上游那个 32KB，不动。**
 *
 * 上界 256KB 不是拍脑袋 —— 再往上，窗口小的模型会直接请求失败（不是变慢，是报错），
 * 而失败信息在各家中转站的措辞都不一样，很难看懂。
 */
const val TOOL_OUTPUT_LIMIT_MIN_KB = 32
const val TOOL_OUTPUT_LIMIT_DEFAULT_KB = 64
const val TOOL_OUTPUT_LIMIT_MAX_KB = 256

/**
 * v288：续跑方式。回复没写完就断掉时，程序用哪种办法接回来。
 *
 * 用户口径（原话）：「1 是发送继续，2 是直接重新生成最后一次输出（不是整个对话重新生成）」。
 *
 * [CONTINUE] 把已写的半截原样回灌 + 发一句「继续」，让模型往下写；
 * [REGENERATE] 丢掉这一趟写坏的半截，把**同一个请求**原样重发一次
 * （照官方 awaitNetworkRetryOrThrow 的整请求重放语义，只重来最后那一段，不动前面已完成的内容）。
 *
 * 注意：撞输出上限（finishReason=length 等）时强制走 [CONTINUE] —— 重新生成必然再撞
 * 同一个上限，是必然失败而不是取舍。
 */
@Serializable
enum class ResumeStrategy {
    /** 发送「继续」，接着已写内容往下写。 */
    CONTINUE,

    /** 重新生成最后一次输出（原样重发同一请求，丢弃写坏的半截）。 */
    REGENERATE,
}

/**
 * v250：跟随上游 2.4.11 —— 网络设置（自定义访问标识与代理）。
 * 字段与上游逐字一致，方便以后继续合并上游。
 */

/**
 * 特殊模型的自动压缩触发值。
 *
 * 只有被选进这张表的模型才用自己的 [triggerTokens]（直接触发值，不打折）；
 * 没被选中的模型继续用所在助手的 autoCompressTriggerTokens。
 * 存在全局设置里而不是助手里，因为「这个模型能吃多少上下文」是模型本身的属性，
 * 换助手不该重新填一遍。
 */
@Serializable
data class AutoCompressModelOverride(
    val modelId: Uuid,
    val triggerTokens: Int,
)

/** 圆桌模式一次最多几个成员模型 */
const val MAX_ROUND_TABLE_MEMBERS = 5

/**
 * 圆桌模式配置（全局，换助手也通用）。
 *
 * [memberModelIds] 里的模型会用同一份上文各自出一份方案，彼此隔离、不共享中间状态。
 * 出方案期间只允许"查资料"类工具（联网搜索、读工作区文件、查历史对话、时间），
 * 一切会改变东西的操作（跑命令、写/改/发布文件、写记忆、MCP、技能、剪贴板、朗读）永久禁用，
 * 因为多个模型并发写同一份东西没有安全做法。
 * [allowReadOnlyTools] 关掉后连查资料也不给，模型只凭自身知识出方案（省搜索费用与 token）。
 * 全部出完后由 [mainModelId] 综合成最终方案；[mainModelId] 为 null 时用当前对话模型。
 * [summaryPrompt] 为空时使用内置默认综合提示词。
 */
@Serializable
data class RoundTableSetting(
    val memberModelIds: List<Uuid> = emptyList(),
    val mainModelId: Uuid? = null,
    val summaryPrompt: String = "",
    val allowReadOnlyTools: Boolean = true,
    /** 先由一个模型把目标/范围/完成标准/缺失信息结构化，产出共享事实与任务合同 */
    val enableContractStage: Boolean = true,
    /** 主模型在看不到别人方案之前，先独立写一份初案 */
    val enableMainDraft: Boolean = true,
    /** 由一个模型逐条针对性反驳，检查具体主张是否成立 */
    val enableRebuttalStage: Boolean = true,
    /** 任务合同检查用的模型；null = 用主模型 */
    val contractModelId: Uuid? = null,
    /** 针对性反驳用的模型；null = 用主模型 */
    val rebuttalModelId: Uuid? = null,
)


/**
 * v305：从内置清单移除的推广位供应商 id（AiHubMix / APIMart / 硅基流动 / 小马算力 /
 * 302.AI / 随想AI网关 / MaruCode）。id 是这些内置条目固定不变的标识，改名不影响判定，
 * 所以这里认 id 不认名字。声明为 internal 是为了让单测能拿到真实 id 构造样本。
 */
internal val REMOVED_AD_PROVIDER_IDS = setOf(
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"), // APIMart
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // 硅基流动
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"), // 小马算力
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), // 302.AI
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"), // 随想AI网关
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"), // MaruCode
)

/**
 * v305：判定一个已保存的供应商是否该被自动清掉 —— 必须是「我们移除的那几家广告位」
 * **且**「没填密钥」且「没添加过模型」，三条同时成立才动手（用户口径：填过密钥的保留不动）。
 * 加 models 判据是因为用户可能没填 key 但自己配过模型，那也算动过的配置。
 * 认不出的类型（Codex / Grok / GeminiOAuth 没有 apiKey 概念）一律不清 —— 宁可不删。
 * 纯函数、无 Android 依赖，可直接单测（见 RemovedAdProviderTest）。
 */
internal fun ProviderSetting.shouldDropRemovedAdProvider(): Boolean {
    if (id !in REMOVED_AD_PROVIDER_IDS) return false
    return when (this) {
        is ProviderSetting.OpenAI -> apiKey.isBlank() && models.isEmpty()
        is ProviderSetting.Google -> apiKey.isBlank() && models.isEmpty()
        is ProviderSetting.Claude -> apiKey.isBlank() && models.isEmpty()
        else -> false
    }
}


/**
 * v305：语音合成表里被移除的广告位预置项 id（AiHubMix）。同样只清纯没填过密钥的。
 */
internal val REMOVED_AD_TTS_PROVIDER_IDS = setOf(
    Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"), // AiHubMix TTS
)

/**
 * v305：与 [shouldDropRemovedAdProvider] 同口径的语音合成版 —— id 命中且没填过密钥才清。
 * 清掉后不会让朗读失效：getSelectedTTSProvider 有 `?: ttsProviders.firstOrNull()` 兜底，
 * 而 SystemTTS 永远留在清单里（它也在 DEFAULT_TTS_PROVIDERS 中，会被回填）。
 */
internal fun TTSProviderSetting.shouldDropRemovedAdTtsProvider(): Boolean {
    if (id !in REMOVED_AD_TTS_PROVIDER_IDS) return false
    return when (this) {
        is TTSProviderSetting.OpenAI -> apiKey.isBlank()
        else -> false
    }
}


