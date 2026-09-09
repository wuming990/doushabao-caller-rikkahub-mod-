package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.Serializable

/**
 * One signed-in Google account usable against Cloud Code Assist.
 *
 * [projectId] is the `cloudaicompanionProject` resolved once at sign-in through
 * loadCodeAssist / onboardUser. Every generate request has to carry it, so it is stored with the
 * tokens rather than rediscovered per request.
 */
@Serializable
data class GeminiAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val projectId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GeminiTokenStatus = GeminiTokenStatus.UNKNOWN,
    val usage: GeminiUsageSnapshot? = null,
)

/**
 * What is left of the account's Code Assist quota.
 *
 * v280：数据源从 `fetchAvailableModels` 换成官方自己用的 `retrieveUserQuotaSummary`。
 *
 * 前者报的是**模型级**的短窗信息，对个人账号根本不可靠 —— 外部项目实测「不管实际用了多少，
 * 永远返回 remainingFraction: 1」，本项目真机也撞上了：账号周额度已耗尽（发消息 429
 * QUOTA_EXHAUSTED、163 小时后才重置），面板却仍显示 100%。后者返回的是**账号级**的分组桶
 * （Gemini 一组、Claude/GPT 一组，每组各有 5 小时桶与每周桶），就是官方客户端配额面板的口径。
 *
 * [groups] 是新口径；[daily] 与 [weekly] 保留下来只为两件事：旧数据反序列化不炸，以及
 * `retrieveUserQuotaSummary` 拿不到时回退旧解析仍有地方放。**注意 [daily] 这个名字是历史遗留，
 * 装的实际是「5 小时窗」** —— 旧解析靠「重置时间超过一天就算周、否则算日」来猜，5 小时窗一直被
 * 猜成「日」，界面上那行「日限额」从来就不是一天。
 */
@Serializable
data class GeminiUsageSnapshot(
    val daily: GeminiUsageWindow? = null,
    val weekly: GeminiUsageWindow? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val groups: List<GeminiQuotaGroup> = emptyList(),
)

@Serializable
data class GeminiUsageWindow(
    val remainingFraction: Double,
    val resetsAt: Long? = null, // epoch seconds
)

/**
 * v280：`retrieveUserQuotaSummary` 的一组配额 —— 官方按模型家族分组，同一组内的模型共用同一份
 * 额度。Gemini 的 Pro 与 Flash 在同一组里（按 API 价格折算扣同一个池），Claude / GPT 那些是
 * **另一组、另一份额度**，所以 Gemini 用完时它们通常还能用。
 *
 * 所有字段都有默认值：旧版本存下来的快照里没有这一层，反序列化要能安全落到空列表。
 */
@Serializable
data class GeminiQuotaGroup(
    val name: String = "",
    val buckets: List<GeminiQuotaBucket> = emptyList(),
)

/**
 * v280：一条配额桶。[window] 已归一化成 `5h` 或 `weekly`（官方在 `window` 字段与 `bucketId`
 * 前缀里都可能给，见 normalizeQuotaWindow）；归一化不出来时保留原值，界面会退回显示 [label]。
 */
@Serializable
data class GeminiQuotaBucket(
    val bucketId: String = "",
    val label: String = "",
    val window: String = "",
    val remainingFraction: Double,
    val resetsAt: Long? = null, // epoch seconds
)

@Serializable
enum class GeminiTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

internal fun GeminiAccount.isAvailable(): Boolean =
    enabled && tokenStatus != GeminiTokenStatus.INVALID
