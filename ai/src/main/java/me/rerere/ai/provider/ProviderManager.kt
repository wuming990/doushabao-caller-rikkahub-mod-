package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    accountCredentials: AccountCredentialProvider,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
        // v323：官方账号型 provider 照常注册；我们的思考档位兜底继续包在三家里。
        registerProvider("openai", ReasoningFallbackProvider(OpenAIProvider(client, context)))
        registerProvider("google", ReasoningFallbackProvider(GoogleProvider(client, context)))
        registerProvider("claude", ReasoningFallbackProvider(ClaudeProvider(client, context)))
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            // v274：三套官方账号 OAuth 登录（实例由 app 侧 DataSourceModule 注册）
            is ProviderSetting.Codex -> getProvider("codex")
            is ProviderSetting.GeminiOAuth -> getProvider("gemini_oauth")
            is ProviderSetting.Grok -> getProvider("grok")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            // v323：Gemini 网页版（cookie 直连）
            is ProviderSetting.GeminiWeb -> getProvider("gemini_web")
        } as Provider<T>
    }
}
