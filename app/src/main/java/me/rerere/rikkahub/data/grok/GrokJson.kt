package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.time.OffsetDateTime
import java.util.Base64

internal data class GrokIdentity(
    val userId: String,
    val email: String,
    val name: String,
)

/**
 * v283：官方 Grok CLI 的客户端形态。
 *
 * ## 为什么必须打这个地址而不是 api.x.ai
 *
 * v274~v282 的聊天请求打的是公开开发者接口 `https://api.x.ai/v1`，真机结果是
 * `403/402 You have run out of credits or need a Grok subscription`
 * （结构化错误码 `personal-team-blocked:spending-limit`）。
 *
 * 那不是「账号没订阅」，而是**两个额度池**：`api.x.ai` 走的是按量付费的开发者额度（要单独充钱），
 * 而 SuperGrok / X Premium+ 订阅带的日常额度只在官方 CLI 用的这个代理
 * `https://cli-chat-proxy.grok.com/v1` 上生效。用 grok CLI 的 OAuth 凭据打公开接口，
 * 拿到的就是「没额度」—— 外部项目（oh-my-pi #5978）用同一套凭据实测过两个地址，报错与我们
 * 真机一字不差。用户导出的凭据文件里 `base_url` 字段写的也正是这个代理地址。
 *
 * ## 为什么还要自报客户端身份
 *
 * 这个代理会校验调用方是不是官方客户端：缺 `x-grok-client-version` /
 * `x-grok-client-identifier` 时它返回
 * `426 Your Grok CLI version (none) is outdated`。下面这组值取自用户手上提取工具导出的
 * **真实**头部（2026-09-04），不是自造形态 —— Gemini 那边曾因自造 UA 被分进最差配额桶，
 * 这里不再犯。
 *
 * ## 版本号会过期
 *
 * xAI 会随 CLI 升级抬高最低版本要求，届时代理会回 426 并在正文里点明需要哪个版本。
 * 那时只改这里的 [CLIENT_VERSION] 与 [USER_AGENT] 即可，不要去动请求逻辑。
 */
internal object GrokCliClient {
    const val BASE_URL = "https://cli-chat-proxy.grok.com/v1"
    const val USER_AGENT = "grok-pager/0.2.93 grok-shell/0.2.93 (linux; x86_64)"
    const val TOKEN_AUTH = "xai-grok-cli"
    const val CLIENT_IDENTIFIER = "grok-pager"
    const val CLIENT_VERSION = "0.2.93"
    const val AUTHENTICATE_RESPONSE = "authenticate-response"
}

/**
 * v283：给打 [GrokCliClient.BASE_URL] 的请求补上官方客户端标识头。
 *
 * 用 `header` 而不是 `addHeader`：同名头出现两次时 xAI 的代理会按哪一个算并不确定，
 * 覆盖比追加安全。Authorization 由调用方各自设置，这里不碰。
 */
internal fun Request.Builder.grokCliClientHeaders(): Request.Builder = this
    .header("User-Agent", GrokCliClient.USER_AGENT)
    .header("X-XAI-Token-Auth", GrokCliClient.TOKEN_AUTH)
    .header("x-grok-client-identifier", GrokCliClient.CLIENT_IDENTIFIER)
    .header("x-grok-client-version", GrokCliClient.CLIENT_VERSION)
    .header("x-authenticateresponse", GrokCliClient.AUTHENTICATE_RESPONSE)

/**
 * xAI OAuth access/id tokens are JWTs. Pull the identity claims from the payload so signed-in
 * accounts show a human-readable name. Falls back gracefully when a claim is absent.
 */
internal fun parseGrokIdentity(token: String, json: Json): GrokIdentity {
    val claims = runCatching {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid JWT" }
        val payload = Base64.getUrlDecoder().decode(parts[1])
        json.parseToJsonElement(payload.decodeToString()).jsonObject
    }.getOrNull()

    fun claim(key: String): String? = claims?.get(key)?.jsonPrimitive?.contentOrNull

    val email = claim("email").orEmpty()
    val userId = claim("sub") ?: claim("user_id") ?: email
    val name = claim("name")
        ?: claim("preferred_username")
        ?: claim("given_name")
        ?: email.substringBefore('@').ifBlank { "Grok" }
    return GrokIdentity(userId = userId, email = email, name = name)
}

private const val WEEKLY_PERIOD_TYPE = "USAGE_PERIOD_TYPE_WEEKLY"

/** v282：一条从外部工具导出的 Grok OAuth 凭据（导入用）。 */
internal data class GrokImportedCredential(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val name: String,
    /** 绝对过期时刻（毫秒）。解析不出来或已过期时为 0 —— 让刷新逻辑进门先换一把新钥匙。 */
    val expiresAtMillis: Long,
)

/** v282：一个凭据文件的解析结果。[skipped] 是缺 access/refresh token 被丢掉的条目数。 */
internal data class GrokCredentialImportFile(
    val credentials: List<GrokImportedCredential>,
    val skipped: Int,
)

/**
 * v282：解析「Grok 账号提取」这类外部工具导出的凭据文件。
 *
 * 三种形态都认（前两种是用户真机遇到的）：
 * - `{"provider":"build","accounts":[{...},{...}]}` —— 批量导出，一次可以带任意多个账号；
 * - `{"access_token":"...","refresh_token":"..."}` —— 单账号，顶层就是账号本身；
 * - `[{...},{...}]` —— 裸数组。
 *
 * 字段名也是两套都认：同一个工具的不同导出格式里，用户 id 有时写在 `user_id`、有时写在 `sub`
 * （另一个则留空字符串），过期时刻有时叫 `expires_at`、有时叫 `expired`。所以一律「哪个有值用哪个」，
 * 不能按固定字段名读。
 *
 * 缺 `access_token` 或 `refresh_token` 的条目直接丢掉并计入 [GrokCredentialImportFile.skipped]：
 * 少任何一把钥匙这个账号都用不起来，存进去只会在轮询时白撞一次墙。
 */
internal fun parseGrokCredentialImport(
    root: JsonElement,
    json: Json,
    nowMillis: Long = System.currentTimeMillis(),
): GrokCredentialImportFile {
    val entries = when {
        root is JsonArray -> root.mapNotNull { it as? JsonObject }
        root is JsonObject -> (root["accounts"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: listOf(root)

        else -> emptyList()
    }
    val credentials = entries.mapNotNull { readGrokImportedCredential(it, json, nowMillis) }
    return GrokCredentialImportFile(
        credentials = credentials,
        skipped = entries.size - credentials.size,
    )
}

private fun readGrokImportedCredential(
    entry: JsonObject,
    json: Json,
    nowMillis: Long,
): GrokImportedCredential? {
    fun text(vararg keys: String): String = keys
        .firstNotNullOfOrNull { key ->
            entry[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        }
        .orEmpty()

    val accessToken = text("access_token")
    val refreshToken = text("refresh_token")
    if (accessToken.isEmpty() || refreshToken.isEmpty()) return null

    // 身份优先用文件里写明的字段。JWT 的 payload 里往往只有 sub、没有 email claim，
    // 所以 JWT 只作兜底 —— 反过来（只信 JWT）会让账号列表里全是「Grok」这种没法分辨的名字。
    val identity = parseGrokIdentity(token = accessToken, json = json)
    val declaredName = text("name")
    val email = text("email")
        .ifEmpty { declaredName.takeIf { it.contains('@') }.orEmpty() }
        .ifEmpty { identity.email }
    val userId = text("user_id", "sub", "principal_id").ifEmpty { identity.userId }
    val name = declaredName
        .takeUnless { it.isEmpty() || it.contains('@') }
        ?: email.substringBefore('@').ifBlank { identity.name }

    return GrokImportedCredential(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = userId,
        email = email,
        name = name.ifBlank { "Grok" },
        expiresAtMillis = parseGrokImportExpiry(
            raw = text("expires_at", "expired", "expiry"),
            nowMillis = nowMillis,
        ),
    )
}

/**
 * v282：把导出文件里的过期时刻转成毫秒；返回 0 表示「当作已过期」。
 *
 * 为什么宁可归零：导出文件里的 access_token 常常只剩几小时、甚至导出时就已经作废，
 * 而真正长期有效的是 refresh_token。归零会让这个账号在第一次被用到之前先刷一把新钥匙，
 * 比拿一把过期钥匙去撞墙、被记成「账号无效」要好。
 */
internal fun parseGrokImportExpiry(raw: String, nowMillis: Long = System.currentTimeMillis()): Long {
    if (raw.isBlank()) return 0
    val millis = runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()
        ?: return 0
    return if (millis > nowMillis) millis else 0
}

/**
 * Parse the shared-pool billing snapshot from `GET /v1/billing?format=credits`. This is the same
 * proto-JSON shape the Grok CLI consumes; zero-valued fields are omitted (an absent
 * `creditUsagePercent` genuinely means 0). The weekly window is only surfaced when the account's
 * current period is weekly — a legacy monthly-only account has no weekly pool.
 */
internal fun parseGrokCreditsUsage(root: JsonObject): GrokUsageSnapshot {
    val config = root["config"]?.jsonObject
    val period = config?.get("currentPeriod")?.jsonObject
    val periodType = period?.get("type")?.jsonPrimitive?.contentOrNull
    val usedPercent = config?.get("creditUsagePercent")?.jsonPrimitive?.doubleOrNull ?: 0.0
    val onDemandCap = config?.get("onDemandCap")?.jsonObject
        ?.get("val")?.jsonPrimitive?.doubleOrNull ?: 0.0

    val weekly = if (periodType == WEEKLY_PERIOD_TYPE) {
        val start = period["start"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        val end = period["end"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        GrokUsageWindow(
            usedPercent = usedPercent,
            resetsAt = end,
            periodDurationMs = if (start != null && end != null) (end - start) * 1000 else null,
        )
    } else {
        null
    }
    return GrokUsageSnapshot(weekly = weekly, onDemandCap = onDemandCap)
}

/** The subscription tier name from `GET /v1/settings` (e.g. "SuperGrok"), or null if absent. */
internal fun parseGrokPlanName(root: JsonObject): String? =
    root["subscription_tier_display"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

/**
 * v284：xAI 免费额度用尽的结构化错误码。
 *
 * 真机 429 正文：
 * ```
 * {"code":"subscription:free-usage-exhausted",
 *  "error":"You've used all the included free usage for model grok-4.6 for now.
 *           Usage resets over a rolling 24-hour window — tokens (actual/limit): 672022/500000.
 *           Upgrade to a Grok subscription for higher limits: https://grok.com/supergrok"}
 * ```
 * 这个限额**按模型分开算**、走 24 小时滚动窗口，而且**没有接口能查** ——
 * `/billing?format=credits` 反映的是 credits 池，跟它是两回事（真机现象正是「面板满格但发不出消息」）。
 */
private const val GROK_FREE_USAGE_CODE = "free-usage-exhausted"
private const val GROK_FREE_USAGE_TEXT = "included free usage"

/** v284：这条报错是不是「免费额度用尽」。判到就该给账号上冷却，重试没有意义。 */
internal fun isGrokFreeUsageExhausted(raw: String): Boolean {
    val text = raw.lowercase()
    return text.contains(GROK_FREE_USAGE_CODE) || text.contains(GROK_FREE_USAGE_TEXT)
}

/**
 * v285：宽口径判定「这个账号的额度/余额没了，换个账号才有意义」。
 *
 * ## 为什么不能只认 429 + 免费额度那一种文案（v284 的真机故障）
 *
 * v284 只在「HTTP 429 且正文含免费额度字样」时给账号上冷却，真机结果是：账号余额用尽后
 * **既不报错也不换号**，用户必须手动去设置页点一次「刷新」才能继续用别的账号。
 *
 * 联网核验后的事实是 —— 同一件事「这个号不能用了」，xAI 在不同接口/不同层级上给的状态码不一样：
 *
 * | 报错 | 状态码 |
 * |---|---|
 * | `subscription:free-usage-exhausted`（按模型的 24 小时滚动窗口用尽） | 429 |
 * | `personal-team-blocked:spending-limit` + `You have run out of credits or need a Grok subscription.` | **402 或 403**（两者都被真实观测到） |
 *
 * 所以判定必须**只看报错文字、不看状态码**。这也是几个成熟代理实现的一致做法（例如 oh-my-pi 的
 * `isUsageLimit` 只匹配消息文本，其测试注释原文写着「xAI surfaces account exhaustion as
 * 403 + 'run out of credits' / spending-limit, not 429. Must rotate」）。
 *
 * ## 故意**不**算在内的几种
 *
 * - `426 ... Grok CLI version ... outdated`：是我们自报的客户端版本过期，换多少个账号都一样，
 *   要改的是 [GrokCliClient.CLIENT_VERSION]。
 * - 401 / token 失效：走 [GrokAccountRepository.markInvalid]，那是「钥匙坏了」不是「额度没了」。
 * - 纯限速（`rate limit` / `too many requests` 而没有额度字样）：等一会儿就好，不该锁一小时。
 */
private val GROK_QUOTA_MARKERS = listOf(
    GROK_FREE_USAGE_CODE,
    GROK_FREE_USAGE_TEXT,
    "run out of credits",
    "spending-limit",
    "personal-team-blocked",
    "need a grok subscription",
    "usage limit reached",
    "quota exceeded",
)

internal fun isGrokQuotaExhausted(raw: String): Boolean {
    if (raw.isBlank()) return false
    val text = raw.lowercase()
    // 客户端版本过期与账号额度无关，绝不能把它当成「换个号试试」
    if (text.contains("outdated") && text.contains("grok cli")) return false
    return GROK_QUOTA_MARKERS.any { text.contains(it) }
}

// 模型名与用量数字都从报错正文里抠。两个都可能缺（xAI 换过文案），缺了就只上冷却、不显示数字。
private val GROK_MODEL_REGEX = Regex("""for model\s+([A-Za-z0-9._\-]+)""", RegexOption.IGNORE_CASE)
private val GROK_TOKENS_REGEX = Regex("""actual\s*/\s*limit\s*\)?\s*:\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)

/**
 * v284：从「免费额度用尽」的报错里抠出模型名与用量。
 *
 * 解析不出数字时返回 null —— 宁可界面上不显示，也不要编一个数字出来。
 */
internal fun parseGrokModelUsageLimit(
    raw: String,
    nowMillis: Long = System.currentTimeMillis(),
): GrokModelUsageLimit? {
    if (!isGrokFreeUsageExhausted(raw)) return null
    val tokens = GROK_TOKENS_REGEX.find(raw) ?: return null
    val used = tokens.groupValues[1].toLongOrNull() ?: return null
    val limit = tokens.groupValues[2].toLongOrNull() ?: return null
    if (limit <= 0) return null
    return GrokModelUsageLimit(
        model = GROK_MODEL_REGEX.find(raw)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "unknown" },
        usedTokens = used,
        limitTokens = limit,
        observedAt = nowMillis,
    )
}

private fun parseIsoEpochSeconds(raw: String): Long? =
    runCatching { OffsetDateTime.parse(raw.trim()).toEpochSecond() }.getOrNull()
