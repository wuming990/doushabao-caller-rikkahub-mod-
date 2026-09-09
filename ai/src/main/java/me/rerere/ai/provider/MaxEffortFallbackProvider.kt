package me.rerere.ai.provider

import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ReasoningFallbackProvider

/**
 * 已废弃：v219 起由 [ReasoningFallbackProvider] 取代（从只兜 MAX 泛化为所有思考档位）。
 * 保留类名仅为兼容历史引用与旧测试；行为与 [ReasoningFallbackProvider] 完全一致。
 */
@Deprecated("Use ReasoningFallbackProvider (v219 generic reasoning fallback)")
internal class MaxEffortFallbackProvider<T : ProviderSetting>(
    delegate: Provider<T>,
) : Provider<T> by ReasoningFallbackProvider(delegate)
