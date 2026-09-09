package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    /**
     * v290：数一把密钥串里实际有几把 key（分隔符规则与 [next] 完全一致）。
     * 流式路径的换 key 决策用它算「最多还能换几次」。
     */
    fun keyCount(keys: String): Int = splitKey(keys).size

    /**
     * v255：标记某个 key 最近失败过（余额不足 / 鉴权失败 / 限速）。
     * 冷却期内 [next] 会跳过它；实现方负责持久化与过期清理。默认空实现，不影响旧实现。
     */
    fun markFailed(providerId: String, key: String) {}

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

// v255.1：分隔符兼容中文输入法的全角标点 —— 用户真机踩过：用中文逗号「，」分隔多个
// key 时，旧正则只认半角逗号，整串（含全角逗号本身）被当成一个 key 发出去，
// OkHttp 直接报 "Unexpected char 0xff0c in Authorization value"。
// 现在半角/全角逗号、顿号「、」、半角/全角分号、全角空格（\u3000）与各种空白都算分隔符。
private val SPLIT_KEY_REGEX = "[\\s,，、;；\u3000]+".toRegex() // 空格换行逗号（半角/全角）顿号分号全角空格

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal class DefaultKeyRoulette : KeyRoulette {
    // v255：内存禁用表 providerId -> (key -> failedAt)，进程内有效，随 App 退出清空
    private val failedKeys = ConcurrentHashMap<String, MutableMap<String, Long>>()

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        val now = System.currentTimeMillis()
        val failed = currentFailed(providerId, keyList, now)
        val candidates = keyList.filter { it !in failed }
        // v255：全部在冷却期（或只有一个 key）→ 回退随机选，保证请求仍会发出
        return if (candidates.isNotEmpty()) candidates.random() else keyList.random()
    }

    override fun markFailed(providerId: String, key: String) {
        failedKeys.computeIfAbsent(providerId) { mutableMapOf() }[key] = System.currentTimeMillis()
    }

    private fun currentFailed(providerId: String, keyList: List<String>, now: Long): Set<String> {
        val m = failedKeys[providerId] ?: return emptySet()
        m.entries.removeIf { now - it.value >= KEY_FAILED_COOLDOWN_MS }
        if (m.isEmpty()) failedKeys.remove(providerId)
        return m.keys.filter { it in keyList }.toSet()
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val LRU_FAILED_CACHE_FILE = "lru_key_failed.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

/** v255：失败 key 的冷却期。期间轮询会跳过它，5 分钟后自动恢复参与选择。 */
internal const val KEY_FAILED_COOLDOWN_MS = 5 * 60 * 1000L

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

// v255：失败记录文件结构: Map<providerId, Map<apiKey, failedAtTimestamp>>
private typealias FailedCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val allFailed = loadFailedCache().toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // v255：冷却期内的 key 不参与选择；全部冷却中 → 回退普通 LRU（保证请求仍会发出）
            val failedKeys = (allFailed[providerId] ?: emptyMap())
                .filter { (k, failedAt) -> k in keyList && now - failedAt < KEY_FAILED_COOLDOWN_MS }
                .keys
                .toSet()
            val pool = keyList.filter { it !in failedKeys }.ifEmpty { keyList }

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = pool.firstOrNull { it !in providerCache }
                ?: providerCache.filterKeys { it in pool }.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    // v255：标记 key 失败，冷却期内不再选择它（持久化，与 LRU 记录同一把锁）
    override fun markFailed(providerId: String, key: String) {
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allFailed = loadFailedCache().toMutableMap()
            val providerFailed = (allFailed[providerId] ?: emptyMap())
                .filter { (_, failedAt) -> now - failedAt < KEY_FAILED_COOLDOWN_MS }
                .toMutableMap()
            providerFailed[key] = now
            allFailed[providerId] = providerFailed
            saveFailedCache(allFailed)
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }

    private fun loadFailedCache(): FailedCache {
        return try {
            val file = File(context.cacheDir, LRU_FAILED_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveFailedCache(cache: FailedCache) {
        try {
            File(context.cacheDir, LRU_FAILED_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
