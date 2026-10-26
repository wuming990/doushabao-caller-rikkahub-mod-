package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.model.GroupChatPreset
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.reflect.KProperty1
import kotlin.uuid.Uuid

/**
 * [Settings] 的一个字段和它在 DataStore 里的 key 的对应关系。
 *
 * 读、写、判断有没有变化都从这里走，新增字段只要在 [SETTINGS_FIELDS] 里加一行。
 */
internal class SettingsField<T, P : Any>(
    val key: Preferences.Key<P>,
    val property: KProperty1<Settings, T>,
    private val set: Settings.(T) -> Settings,
    private val encode: (T) -> P?,
    private val decode: (P) -> T,
) {
    fun changed(old: Settings, new: Settings): Boolean = property.get(old) != property.get(new)

    /** key 不存在时保留 [settings] 里原有的值。 */
    fun read(preferences: Preferences, settings: Settings): Settings {
        val stored = preferences[key] ?: return settings
        return settings.set(decode(stored))
    }

    /** 编码结果为 null 时删除 key。 */
    fun write(preferences: MutablePreferences, settings: Settings) {
        val encoded = encode(property.get(settings))
        if (encoded != null) preferences[key] = encoded else preferences.remove(key)
    }
}

/** 原样存取的基本类型。 */
private fun <T : Any> plain(
    key: Preferences.Key<T>,
    property: KProperty1<Settings, T>,
    set: Settings.(T) -> Settings,
) = SettingsField(key, property, set, encode = { it }, decode = { it })

/** 存成 JSON 字符串。 */
private inline fun <reified T> json(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, T>,
    noinline set: Settings.(T) -> Settings,
) = SettingsField(
    key,
    property,
    set,
    encode = { JsonInstant.encodeToString(it) },
    decode = { JsonInstant.decodeFromString<T>(it) },
)

private fun uuid(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, Uuid>,
    set: Settings.(Uuid) -> Settings,
) = SettingsField(key, property, set, encode = { it.toString() }, decode = { Uuid.parse(it) })

/** v1017 合并官方 3.1.1：存枚举名，认不出的值按 AUTO 处理。 */
private fun reasoningLevel(
    key: Preferences.Key<String>,
    property: KProperty1<Settings, ReasoningLevel>,
    set: Settings.(ReasoningLevel) -> Settings,
) = SettingsField(
    key,
    property,
    set,
    encode = { it.name },
    decode = { name -> ReasoningLevel.entries.find { it.name == name } ?: ReasoningLevel.AUTO },
)

internal val SETTINGS_FIELDS: List<SettingsField<*, *>> = with(SettingsStore) {
    listOf(
        // UI设置
        plain(DYNAMIC_COLOR, Settings::dynamicColor) { copy(dynamicColor = it) },
        plain(THEME_ID, Settings::themeId) { copy(themeId = it) },
        json(CUSTOM_THEMES, Settings::customThemes) { copy(customThemes = it) },
        json(DISPLAY_SETTING, Settings::displaySetting) { copy(displaySetting = it) },
        json(NETWORK_SETTING, Settings::networkSetting) { copy(networkSetting = it) },
        plain(DEVELOPER_MODE, Settings::developerMode) { copy(developerMode = it) },

        // 模型选择
        json(FAVORITE_MODELS, Settings::favoriteModels) { copy(favoriteModels = it) },
        uuid(SELECT_MODEL, Settings::chatModelId) { copy(chatModelId = it) },
        uuid(FAST_MODEL, Settings::fastModelId) { copy(fastModelId = it) },
        reasoningLevel(FAST_MODEL_REASONING_LEVEL, Settings::fastModelReasoningLevel) {
            copy(fastModelReasoningLevel = it)
        },
        SettingsField(
            MEMORY_MODEL,
            Settings::memoryModelId,
            { copy(memoryModelId = it) },
            // 没设置时不存这个 key
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),
        reasoningLevel(MEMORY_MODEL_REASONING_LEVEL, Settings::memoryModelReasoningLevel) {
            copy(memoryModelReasoningLevel = it)
        },
        uuid(TRANSLATE_MODEL, Settings::translateModeId) { copy(translateModeId = it) },
        plain(ENABLE_SUGGESTION, Settings::enableSuggestion) { copy(enableSuggestion = it) },
        uuid(IMAGE_GENERATION_MODEL, Settings::imageGenerationModelId) { copy(imageGenerationModelId = it) },
        plain(ENABLE_AUTO_TITLE, Settings::enableAutoTitle) { copy(enableAutoTitle = it) },
        plain(TITLE_PROMPT, Settings::titlePrompt) { copy(titlePrompt = it) },
        plain(TRANSLATION_PROMPT, Settings::translatePrompt) { copy(translatePrompt = it) },
        plain(TRANSLATE_THINKING_BUDGET, Settings::translateThinkingBudget) { copy(translateThinkingBudget = it) },
        plain(TRANSLATE_TARGET_LANGUAGE, Settings::translateTargetLanguage) { copy(translateTargetLanguage = it) },
        plain(SUGGESTION_PROMPT, Settings::suggestionPrompt) { copy(suggestionPrompt = it) },
        uuid(OCR_MODEL, Settings::ocrModelId) { copy(ocrModelId = it) },
        plain(OCR_PROMPT, Settings::ocrPrompt) { copy(ocrPrompt = it) },

        // 提供商
        json(PROVIDERS, Settings::providers) { copy(providers = it) },

        // 助手
        uuid(SELECT_ASSISTANT, Settings::assistantId) { copy(assistantId = it) },
        json(ASSISTANTS, Settings::assistants) { copy(assistants = it) },
        json(ASSISTANT_TAGS, Settings::assistantTags) { copy(assistantTags = it) },

        // 搜索
        json(SEARCH_SERVICES, Settings::searchServices) { copy(searchServices = it) },
        json(SEARCH_COMMON, Settings::searchCommonOptions) { copy(searchCommonOptions = it) },
        plain(SEARCH_SELECTED, Settings::searchServiceSelected) { copy(searchServiceSelected = it) },

        // MCP
        json(MCP_SERVERS, Settings::mcpServers) { copy(mcpServers = it) },

        // WebDAV
        json(WEBDAV_CONFIG, Settings::webDavConfig) { copy(webDavConfig = it) },

        // S3
        json(S3_CONFIG, Settings::s3Config) { copy(s3Config = it) },
        json(UPLOAD_S3_CONFIG, Settings::uploadS3Config) { copy(uploadS3Config = it) },

        // 媒体生成
        json(MEDIA_GENERATION_PROVIDERS, Settings::mediaGenerationProviders) { copy(mediaGenerationProviders = it) },

        // TTS
        json(TTS_PROVIDERS, Settings::ttsProviders) { copy(ttsProviders = it) },
        uuid(SELECTED_TTS_PROVIDER, Settings::selectedTTSProviderId) { copy(selectedTTSProviderId = it) },
        plain(DEFAULT_TTS_PLAYBACK_SPEED, Settings::defaultTTSPlaybackSpeed) { copy(defaultTTSPlaybackSpeed = it) },

        // ASR
        json(ASR_PROVIDERS, Settings::asrProviders) { copy(asrProviders = it) },
        SettingsField(
            SELECTED_ASR_PROVIDER,
            Settings::selectedASRProviderId,
            { copy(selectedASRProviderId = it) },
            // 没选时不存这个 key
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),

        // Web Server
        plain(WEB_SERVER_ENABLED, Settings::webServerEnabled) { copy(webServerEnabled = it) },
        plain(WEB_SERVER_PORT, Settings::webServerPort) { copy(webServerPort = it) },
        plain(WEB_SERVER_JWT_ENABLED, Settings::webServerJwtEnabled) { copy(webServerJwtEnabled = it) },
        plain(WEB_SERVER_ACCESS_PASSWORD, Settings::webServerAccessPassword) { copy(webServerAccessPassword = it) },
        plain(WEB_SERVER_LOCALHOST_ONLY, Settings::webServerLocalhostOnly) { copy(webServerLocalhostOnly = it) },

        // 提示词注入
        json(MODE_INJECTIONS, Settings::modeInjections) { copy(modeInjections = it) },
        json(LOREBOOKS, Settings::lorebooks) { copy(lorebooks = it) },
        json(QUICK_MESSAGES, Settings::quickMessages) { copy(quickMessages = it) },

        // 备份提醒
        json(BACKUP_REMINDER_CONFIG, Settings::backupReminderConfig) { copy(backupReminderConfig = it) },

        // 赞助提醒
        plain(SPONSOR_ALERT_DISMISSED_AT, Settings::sponsorAlertDismissedAt) { copy(sponsorAlertDismissedAt = it) },

        // v323：二改自建字段。官方 3.1 新增这份 SETTINGS_FIELDS 清单时只有官方字段，
        // 合并时如果不补，一旦将来读写切到这套机制，用户的续跑次数、群聊、技能顺序
        // 会全部静默回到默认值（SettingsFieldsTest 的「每个字段恰好一处」就是钉这个的）。
        plain(AGENT_MAX_CONCURRENT, Settings::agentMaxConcurrent) { copy(agentMaxConcurrent = it) },
        plain(AGENT_THREAD_TIMEOUT, Settings::agentThreadTimeoutMinutes) { copy(agentThreadTimeoutMinutes = it) },
        plain(AGENT_TOOL_TIMEOUT, Settings::agentToolTimeoutMinutes) { copy(agentToolTimeoutMinutes = it) },
        plain(TOOL_OUTPUT_LIMIT_KB, Settings::toolOutputLimitKb) { copy(toolOutputLimitKb = it) },
        plain(AGENT_AUTO_RESUME_MAX, Settings::agentAutoResumeMax) { copy(agentAutoResumeMax = it) },
        plain(TRUNCATION_AUTO_RESUME_MAX, Settings::truncationAutoResumeMax) { copy(truncationAutoResumeMax = it) },
        SettingsField(
            SKILL_ORDER, Settings::skillOrder, { copy(skillOrder = it) },
            encode = { encodeSkillOrder(it) },
            decode = { decodeSkillOrder(it) },
        ),
        SettingsField(
            RESUME_STRATEGY_V288, Settings::resumeStrategy, { copy(resumeStrategy = it) },
            encode = { it.name },
            decode = { name -> ResumeStrategy.entries.find { it.name == name } ?: ResumeStrategy.CONTINUE },
        ),
        plain(REMEMBER_MODEL_PER_CONVERSATION, Settings::rememberModelPerConversation) {
            copy(rememberModelPerConversation = it)
        },
        SettingsField(
            CONVERSATION_MODEL_IDS, Settings::conversationModelIds, { copy(conversationModelIds = it) },
            encode = { ids ->
                JsonInstant.encodeToString(ids.mapKeys { it.key.toString() }.mapValues { it.value.toString() })
            },
            decode = { raw ->
                runCatching {
                    JsonInstant.decodeFromString<Map<String, String>>(raw)
                        .mapKeys { Uuid.parse(it.key) }
                        .mapValues { Uuid.parse(it.value) }
                }.getOrDefault(emptyMap())
            },
        ),
        SettingsField(
            GROUP_CHAT_CONFIGS, Settings::groupChatConfigs, { copy(groupChatConfigs = it) },
            encode = { configs -> JsonInstant.encodeToString(configs.mapKeys { it.key.toString() }) },
            decode = { raw ->
                runCatching {
                    JsonInstant.decodeFromString<Map<String, GroupChatConfig>>(raw)
                        .mapNotNull { (key, value) ->
                            runCatching { Uuid.parse(key) }.getOrNull()?.let { it to value }
                        }
                        .toMap()
                }.getOrDefault(emptyMap())
            },
        ),
        plain(COMPRESS_USE_CLASSIC_MODE, Settings::compressUseClassicMode) { copy(compressUseClassicMode = it) },
        SettingsField(
            GROUP_CHAT_PRESETS, Settings::groupChatPresets, { copy(groupChatPresets = it) },
            encode = { JsonInstant.encodeToString(it) },
            decode = { raw -> decodeGroupPresetsTolerant(raw) },
        ),
        plain(GROUP_CHAT_CLEAN_CONTEXT, Settings::groupChatCleanContext) { copy(groupChatCleanContext = it) },
        plain(AGENT_CONVERSATION_SEARCH, Settings::allowAgentConversationSearch) {
            copy(allowAgentConversationSearch = it)
        },
        plain(AGENT_MAX_STEPS, Settings::agentMaxSteps) { copy(agentMaxSteps = it) },
        plain(AGENT_ENABLED, Settings::enableAgentTools) { copy(enableAgentTools = it) },
        SettingsField(
            AGENT_MODEL, Settings::agentModelId, { copy(agentModelId = it) },
            encode = { it?.toString() },
            decode = { Uuid.parse(it) },
        ),
        SettingsField(
            AGENT_ROLE_MODELS, Settings::agentRoleModelOverrides, { copy(agentRoleModelOverrides = it) },
            encode = { encodeAgentRoleModelOverrides(it) },
            decode = { decodeAgentRoleModelOverrides(it) },
        ),
        SettingsField(
            AGENT_FALLBACK_MODELS, Settings::agentFallbackModelIds, { copy(agentFallbackModelIds = it) },
            encode = { encodeAgentFallbackModelIds(it) },
            decode = { decodeAgentFallbackModelIds(it) },
        ),
        uuid(COMPRESS_MODEL, Settings::compressModelId) { copy(compressModelId = it) },
        plain(COMPRESS_PROMPT, Settings::compressPrompt) { copy(compressPrompt = it) },
        json(SHARED_STORAGE_TREE_URIS, Settings::sharedStorageTreeUris) { copy(sharedStorageTreeUris = it) },
        plain(LAUNCH_COUNT, Settings::launchCount) { copy(launchCount = it) },
        json(AUTO_COMPRESS_MODEL_OVERRIDES, Settings::autoCompressModelOverrides) {
            copy(autoCompressModelOverrides = it)
        },
        json(ROUND_TABLE_SETTING, Settings::roundTableSetting) { copy(roundTableSetting = it) },
        plain(WORKSPACE_COMMAND_TIMEOUT, Settings::workspaceCommandTimeoutSeconds) {
            copy(workspaceCommandTimeoutSeconds = it)
        },
    )
}

/**
 * key 不存在时各字段的取值。
 *
 * 和 [Settings] 构造函数的默认值只有一处不同，沿用的是一直以来的读取行为：没选过的模型指向内置的自动模型。
 */
private val MISSING_KEY_DEFAULTS = Settings(
    chatModelId = DEFAULT_AUTO_MODEL_ID,
    fastModelId = DEFAULT_AUTO_MODEL_ID,
    translateModeId = DEFAULT_AUTO_MODEL_ID,
)

/** 解码出的是盘上的原始值，还没有经过 [normalized]。 */
internal fun Preferences.toSettings(): Settings =
    SETTINGS_FIELDS.fold(MISSING_KEY_DEFAULTS) { settings, field -> field.read(this, settings) }

/**
 * 把 [settings] 写进 Preferences。
 *
 * 给了 [persisted]（盘上现有的那一份）时只写和它不同的字段：整份设置的 JSON 编码很重，没变的字段不用重新编码。
 */
internal fun MutablePreferences.putSettings(settings: Settings, persisted: Settings? = null) {
    SETTINGS_FIELDS.forEach { field ->
        if (persisted == null || field.changed(persisted, settings)) {
            field.write(this, settings)
        }
    }
}
