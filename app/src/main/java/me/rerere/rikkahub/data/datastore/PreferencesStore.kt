package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
import me.rerere.rikkahub.data.model.Avatar
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
 * v274：GeminiOAuth 归一化分支的 models 变换（照搬 ExTV）：按 id 去重，
 * 再剔除 modelId 命中 [DENIED_MODEL_IDS] 的条目。
 *
 * GeminiProvider 拉模型列表时的过滤只能挡住「重新拉取」；在那之前已经持久化的副本
 * 会原样活在合并逻辑里。这里在每次设置加载时做读侧驱逐，无需迁移、不改已存 JSON。
 */
private fun dropDeniedGeminiOAuthModels(models: List<Model>): List<Model> =
    models.distinctBy { model -> model.id }.filterNot { model -> model.modelId in DENIED_MODEL_IDS }

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
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
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
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

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
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
                preferences[RESUME_STRATEGY_V288] = settings.resumeStrategy.name
                preferences[REMEMBER_MODEL_PER_CONVERSATION] = settings.rememberModelPerConversation
                preferences[CONVERSATION_MODEL_IDS] = JsonInstant.encodeToString(
                    settings.conversationModelIds.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                )
                preferences[COMPRESS_USE_CLASSIC_MODE] = settings.compressUseClassicMode
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
            }
        }
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
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
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
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
                compressUseClassicMode = preferences[COMPRESS_USE_CLASSIC_MODE] == true,
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
                } ?: emptyList(),
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
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
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
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        persistSettings(dataStore, settings)
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
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
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
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
    val sharedStorageTreeUris: List<String> = emptyList(),
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    // 工作区 shell 命令默认超时(秒)。0 = 不限时(默认)。仅在 AI 未显式指定 timeout 时生效。
    val workspaceCommandTimeoutSeconds: Int = 0,
    // 特殊模型的自动压缩触发值。只有列在这里的模型走各自的值，其余模型仍用所在助手的自定义触发值。
    val autoCompressModelOverrides: List<AutoCompressModelOverride> = emptyList(),
    // 圆桌模式：多个模型用同一份上文各出一份方案，再由主模型综合。全局生效，换助手也通用。
    val roundTableSetting: RoundTableSetting = RoundTableSetting(),

    // v219：普通聊天的子代理（Codex 风格）总开关。默认开启；关闭后主模型拿不到 6 个控制工具。
    val enableAgentTools: Boolean = true,

    // v219：子代理默认模型。null = 跟随主助手（当前助手绑定的模型）。
    // 优先级：spawn_agent 显式 model_id > agentModelId > 主助手模型。
    val agentModelId: Uuid? = null,

    // v219：子代理最大并发数（1~8，默认 4）。
    val agentMaxConcurrent: Int = 4,

    // v238：单个子代理的超时分钟数（1~120，0 = 不限时，默认 10）。
    // 为什么需要：v237 之前单独派发的子代理完全没有超时，卡住就无限期挂着，
    // 界面只显示「正在查证...」，用户没法判断它是在干活还是死了（用户真实反馈）。
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

    // v219：角色级模型覆盖（role 名 -> 模型 id；null/缺失 = 跟随子代理默认模型）。
    val agentRoleModelOverrides: Map<String, Uuid?> = emptyMap(),

    // v237：子代理备用模型（最多 3 个，按顺序兜底）。
    // 插在候选链的「主对话模型」之前：先用你指定的便宜兜底模型，最后才落到主对话模型。
    val agentFallbackModelIds: List<Uuid> = emptyList(),
) {
    /**
     * v248：子代理单次工具返回的字符上限。界面按 KB 存，这里统一换算，
     * 让「子代理后端」与「子代理读文件工具」用同一套换算，避免两处各算一遍算出不同结果。
     *
     * ⚠️ 只有子代理那条路会读它 —— 生成层（`GenerationLoop`）绝不读，
     * 否则主对话就被牵连了（有门禁断言钉着这一条）。
     */
    val toolOutputCharLimit: Int
        get() = toolOutputLimitKb
            .coerceIn(TOOL_OUTPUT_LIMIT_MIN_KB, TOOL_OUTPUT_LIMIT_MAX_KB) * 1024

    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

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
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    /**
     * v269：消息下方显示「续跑 N 次」（仅真的续跑过、次数 > 0 的消息才渲染）。
     *
     * 用户原话：「需要可以关掉，而不是一直显示没法关闭显示」—— 默认开、可关。
     * DisplaySetting 整体 JSON 序列化，带默认值的新字段对旧数据向下兼容，无需迁移。
     */
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
    // 输入框上方常显「当前上下文占用」，默认开启
    val showContextUsage: Boolean = true,
    /**
     * v297：对话页顶栏显示「本对话 token 累计」，默认开启、可关。
     *
     * 与 showTokenUsage（每条消息下方那行）是两件事：这里是对话级累计，
     * 口径含每次工具调用与续跑重复发送的上下文，不含后台调用与子代理。
     * DisplaySetting 整体 JSON 序列化，带默认值的新字段对旧数据向下兼容，无需迁移。
     */
    val showConversationTokenStats: Boolean = true,
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
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

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

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
