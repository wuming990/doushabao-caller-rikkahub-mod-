package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
        // 包一层 ReasoningFallbackProvider：任意思考档位被服务端拒绝时，
        // 自动沿档位链降到该模型可用的最高档重发一次（黑名单跳级，进程内有效）
        registerProvider("openai", ReasoningFallbackProvider(OpenAIProvider(client, context)))
        registerProvider("google", ReasoningFallbackProvider(GoogleProvider(client, context)))
        registerProvider("claude", ReasoningFallbackProvider(ClaudeProvider(client, context)))
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
            // v274：三套官方账号 OAuth 登录（实例由 app 侧 DataSourceModule 注册，照搬 ExTV）
            is ProviderSetting.Codex -> getProvider("codex")
            is ProviderSetting.Grok -> getProvider("grok")
            is ProviderSetting.GeminiOAuth -> getProvider("gemini_oauth")
        } as Provider<T>
    }
}
