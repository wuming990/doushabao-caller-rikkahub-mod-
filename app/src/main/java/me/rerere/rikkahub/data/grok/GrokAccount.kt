package me.rerere.rikkahub.data.grok

import kotlinx.serialization.Serializable

@Serializable
data class GrokAccount(
    val id: String,
    val userId: String = "",
    val name: String,
    val email: String = "",
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GrokTokenStatus = GrokTokenStatus.UNKNOWN,
    val usage: GrokUsageSnapshot? = null,
    /**
     * v284：撞到「免费额度用尽」后的冷却截止时刻（毫秒），0 = 不在冷却。
     *
     * 有默认值，所以旧快照反序列化安全。
     */
    val cooldownUntil: Long = 0,
)

@Serializable
enum class GrokTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

/**
 * v282：一次凭据导入的结果。
 *
 * [imported] 新增的账号数，[updated] 已存在被换了钥匙的账号数，
 * [skipped] 文件里缺 access/refresh token 而被丢掉的条目数。
 */
data class GrokImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int = 0,
) {
    val total: Int get() = imported + updated
}

@Serializable
data class GrokUsageSnapshot(
    val weekly: GrokUsageWindow? = null,
    val planName: String? = null,
    val onDemandCap: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v284：按模型的 24 小时滚动窗口限额。
     *
     * **只能从 429 报错里学到**，没有接口能查 —— 所以这里装的是「最近一次撞墙时看到的数字」，
     * 不是实时读数。界面必须把观测时刻一起显示出来，否则又变成另一种骗人。
     */
    val modelLimits: List<GrokModelUsageLimit> = emptyList(),
)

/**
 * v284：一个模型在 24 小时滚动窗口里的用量与上限（单位：token）。
 *
 * 真机样例：`grok-4.6` 已用 672022 / 上限 500000（免费额度，已超 34%）。
 * 上限按模型而定，社区记录过 50 万、100 万、200 万几种。
 */
@Serializable
data class GrokModelUsageLimit(
    val model: String,
    val usedTokens: Long,
    val limitTokens: Long,
    val observedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class GrokUsageWindow(
    val usedPercent: Double,
    val resetsAt: Long? = null, // epoch seconds
    val periodDurationMs: Long? = null,
)

/**
 * v284 起多了一个 [ignoreCooldown]。
 *
 * 冷却是「撞过免费额度上限、暂时别派它上场」的标记。但**所有账号都在冷却时不能干等着** ——
 * 那种情况下（尤其用户只有一个账号）宁可再撞一次墙，也不能让他完全发不出消息。
 * 调用方先按 `ignoreCooldown = false` 挑一轮，全军覆没再用 `true` 挑一轮。
 * 与密钥轮盘那边「全部 key 都在冷却期就回退到全池」是同一个取舍。
 */
internal fun GrokAccount.isAvailable(
    nowMillis: Long = System.currentTimeMillis(),
    ignoreCooldown: Boolean = false,
): Boolean {
    if (!enabled || tokenStatus == GrokTokenStatus.INVALID) return false
    if (!ignoreCooldown && cooldownUntil > nowMillis) return false
    val weekly = usage?.weekly
    // Exhausted only when the weekly pool is spent AND there is no pay-as-you-go cap to fall back
    // on AND the window has not already rolled over.
    val exhausted = weekly != null &&
        weekly.usedPercent >= 100.0 &&
        (usage.onDemandCap <= 0.0) &&
        (weekly.resetsAt == null || weekly.resetsAt * 1000 > nowMillis)
    return !exhausted
}
