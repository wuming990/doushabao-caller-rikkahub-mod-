package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * v300（二改）：商汤网关 + kimi 模型**不提供**「询问用户」工具。
 *
 * 真机实锤（v295/v299 两轮取证）：这个组合下网关会把 ask_user 参数里的 options
 * 数组整段吃掉 —— 题干正常显示、选项一个都看不到，卡片只能降级成自由文本框。
 * 对用户来说等于「问了等于没问，还被卡住」。用户拍板：这个组合干脆不提供该工具，
 * 让模型自己决定；商汤的 deepseek / glm 以及其它所有渠道行为一字不变。
 */
internal fun isAskUserUnsupported(host: String?, modelId: String?): Boolean {
    if (host == null || modelId == null) return false
    if (host != "token.sensenova.cn") return false
    return "kimi" in modelId.lowercase()
}

/**
 * 取供应商的自定义主机名。
 *
 * ⚠️ 必须按子类取：`ProviderSetting` 是密封类，**基类没有 baseUrl** ——
 * 只有 OpenAI / Google / Claude 三个子类各带自己的 `baseUrl`；
 * Codex / Grok / GeminiOAuth 是「账号登录」类通道，本来就没有自定义主机名。
 * 直接写 `provider.baseUrl` 会编译不过（unresolved reference），
 * 而且账号登录类也不该被套用任何一个默认域名。
 */
internal fun providerBaseUrlOf(provider: ProviderSetting?): String? = when (provider) {
    is ProviderSetting.OpenAI -> provider.baseUrl
    is ProviderSetting.Google -> provider.baseUrl
    is ProviderSetting.Claude -> provider.baseUrl
    else -> null
}

/** 取某次生成实际使用的供应商主机名（拿不到就返回 null = 不改任何行为）。 */
internal fun providerHostOf(model: Model, settings: Settings): String? =
    runCatching { providerBaseUrlOf(model.findProvider(settings.providers))?.toHttpUrl()?.host }.getOrNull()

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    // v300：host/modelId 默认 null —— 不传参数的调用点行为与改前一字不差。
    fun getTools(
        options: List<LocalToolOption>,
        host: String? = null,
        modelId: String? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        // v300：商汤 + kimi 不提供询问用户（见 isAskUserUnsupported）
        if (options.contains(LocalToolOption.AskUser) && !isAskUserUnsupported(host, modelId)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        return tools
    }
}
