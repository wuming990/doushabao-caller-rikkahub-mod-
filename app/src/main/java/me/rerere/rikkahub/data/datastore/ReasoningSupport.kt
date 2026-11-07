package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting

/**
 * v1022: A saved GeminiWeb model may predate the REASONING ability flag. Its provider type is
 * authoritative, so existing Argon models get the thinking picker without being re-fetched.
 */
fun Model.supportsReasoning(providers: List<ProviderSetting>): Boolean =
    abilities.contains(ModelAbility.REASONING) || findProvider(providers) is ProviderSetting.GeminiWeb


