package me.rerere.rikkahub.data.grok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class GrokAccountRepository internal constructor(
    private val store: GrokCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != GrokTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = GrokTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GrokAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokenJson: String): GrokAccount = mutex.withLock {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val identity = parseGrokIdentity(
            token = token["id_token"]?.jsonPrimitive?.contentOrNull ?: accessToken,
            json = json,
        )
        val now = System.currentTimeMillis()
        val existing = state.accounts.firstOrNull {
            (it.userId.isNotBlank() && it.userId == identity.userId) ||
                (it.email.isNotBlank() && it.email == identity.email)
        }
        val account = GrokAccount(
            id = existing?.id ?: identity.userId.ifBlank { identity.email }.ifBlank { accessToken.take(16) },
            userId = identity.userId,
            name = identity.name,
            email = identity.email,
            accessToken = accessToken,
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: existing?.refreshToken
                ?: error("Missing refresh token"),
            expiresAt = now + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            enabled = existing?.enabled ?: true,
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = existing?.usage,
        )
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == account.id } + account
            )
        )
        account
    }

    /**
     * v282：批量导入外部工具导出的 OAuth 凭据。
     *
     * 与 [saveLogin] 分开而不是复用它的原因：[saveLogin] 认的是 xAI token 端点**响应**的形状
     * （身份全靠 JWT claim、有效期靠 `expires_in` 秒数、一次一个账号），而导出文件把身份写在字段上、
     * 有效期是绝对时刻，而且一个文件里可以带任意多个账号。硬套 [saveLogin] 会丢掉邮箱和账号名。
     *
     * 已存在的账号（先按 userId、再按 email 认）只换钥匙，**保留用户自己的启用开关与已读到的额度**。
     * 导入进来的账号一律标成 [GrokTokenStatus.UNKNOWN]：这两把钥匙是别处签发的，能不能用要等
     * 真正刷新或发一次请求才知道，不能凭文件存在就宣称可用。
     */
    suspend fun importCredentials(rawJson: String): GrokImportResult = mutex.withLock {
        val parsed = parseGrokCredentialImport(json.parseToJsonElement(rawJson), json)
        if (parsed.credentials.isEmpty()) {
            return@withLock GrokImportResult(imported = 0, updated = 0, skipped = parsed.skipped)
        }
        var imported = 0
        var updated = 0
        var accounts = state.accounts
        parsed.credentials.forEach { credential ->
            val existing = accounts.firstOrNull {
                (credential.userId.isNotBlank() && it.userId == credential.userId) ||
                    (credential.email.isNotBlank() && it.email == credential.email)
            }
            val account = GrokAccount(
                id = existing?.id
                    ?: credential.userId
                        .ifBlank { credential.email }
                        .ifBlank { credential.accessToken.take(16) },
                userId = credential.userId,
                name = credential.name,
                email = credential.email,
                accessToken = credential.accessToken,
                refreshToken = credential.refreshToken,
                expiresAt = credential.expiresAtMillis,
                enabled = existing?.enabled ?: true,
                tokenStatus = GrokTokenStatus.UNKNOWN,
                usage = existing?.usage,
            )
            accounts = accounts.filterNot { it.id == account.id } + account
            if (existing == null) imported++ else updated++
        }
        updateState(state.copy(accounts = accounts))
        GrokImportResult(imported = imported, updated = updated, skipped = parsed.skipped)
    }

    suspend fun acquireAccount(): GrokAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Grok account is signed in")
        // v284：先只在「没在冷却」的账号里挑；全部都在冷却时再忽略冷却挑一遍 ——
        // 否则只有一个账号的用户撞一次额度就彻底发不出消息了。
        pickAccountLocked(ignoreCooldown = false)
            ?: pickAccountLocked(ignoreCooldown = true)
            ?: error("No available Grok account")
    }

    private suspend fun pickAccountLocked(ignoreCooldown: Boolean): GrokAccount? {
        // v284：整批挑选用同一个时刻。分头各取一次 System.currentTimeMillis() 会出现
        // 「挑中时还没冷却、复核时刚好过界」的漂移，那种偶发问题排查成本极高。
        val now = System.currentTimeMillis()
        repeat(state.accounts.size) {
            val index = selectGrokAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
                nowMillis = now,
                ignoreCooldown = ignoreCooldown,
            ) ?: return null
            val candidate = state.accounts[index]
            if (!candidate.isAvailable(now, ignoreCooldown = ignoreCooldown)) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = try {
                ensureFreshLocked(candidate)
            } catch (cancel: CancellationException) {
                // v284：协程取消原样上抛。旧写法用 runCatching 兜底，会把「用户点了停止」
                // 吞成「这个账号刷新失败，换下一个」—— 停止不生效，还接着换号去刷新。
                throw cancel
            } catch (_: Exception) {
                return@repeat
            }
            return fresh
        }
        return null
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
    }

    /**
     * v284：撞到「免费额度用尽」时给账号上冷却，并把看到的用量记进快照。
     *
     * 冷却取 1 小时而不是别人常用的 24 小时：xAI 那个窗口是**滚动**的，会一点点释放，
     * 1 小时后值得再试一次。24 小时对个人用户等于把账号锁死一整天。
     *
     * 冷却只影响「有别的账号可选时优先跳过它」；全部账号都在冷却时 [acquireAccount] 会忽略冷却
     * 照样发（宁可再撞一次，也不能让只有一个账号的用户完全发不出消息）。
     */
    suspend fun markQuotaExhausted(accountId: String, limit: GrokModelUsageLimit?) = mutex.withLock {
        val now = System.currentTimeMillis()
        replaceAccount(accountId) { account ->
            val snapshot = account.usage ?: GrokUsageSnapshot()
            account.copy(
                cooldownUntil = now + QUOTA_COOLDOWN_MS,
                usage = if (limit == null) {
                    snapshot
                } else {
                    snapshot.copy(
                        // 同一个模型只留最新一条，别让列表越堆越长
                        modelLimits = snapshot.modelLimits.filterNot { it.model == limit.model } + limit,
                    )
                },
            )
        }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): GrokAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Grok account not found")
        val fresh = ensureFreshLocked(account, force = true)
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: GrokAccount,
        force: Boolean = false,
    ): GrokAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", GrokOAuthManager.CLIENT_ID)
                .add("refresh_token", account.refreshToken)
                .build()
            client.newCall(
                Request.Builder()
                    .url(GrokOAuthManager.TOKEN_URL)
                    .post(body)
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isGrokRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            // xAI rotates the refresh_token on every refresh.
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = GrokTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchUsageLocked(account: GrokAccount): GrokAccount {
        val credits = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(CREDITS_URL).grokBillingHeaders(account).get().build()
            ).await()
        }
        if (!credits.isSuccessful) {
            if (credits.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            }
            error("Failed to fetch Grok usage: ${credits.code}")
        }
        val snapshot = parseGrokCreditsUsage(
            json.parseToJsonElement(credits.body.string()).jsonObject
        )
        // Plan name is best-effort — never fail a usage refresh just because /settings is down.
        val planName = runCatching {
            val settings = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(SETTINGS_URL).grokBillingHeaders(account).get().build()
                ).await()
            }
            if (settings.isSuccessful) {
                parseGrokPlanName(json.parseToJsonElement(settings.body.string()).jsonObject)
            } else {
                null
            }
        }.getOrNull()
        val updated = account.copy(
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = snapshot.copy(planName = planName ?: account.usage?.planName),
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun Request.Builder.grokBillingHeaders(account: GrokAccount): Request.Builder {
        return addHeader("Authorization", "Bearer ${account.accessToken}")
            .addHeader("Accept", "application/json")
            .grokCliClientHeaders()
    }

    private fun replaceAccount(
        accountId: String,
        transform: (GrokAccount) -> GrokAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: GrokAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        private const val REFRESH_MARGIN_MS = 30_000L
        // Grok subscription usage lives on the CLI billing proxy (same surface the Grok CLI uses),
        // not on api.x.ai. The credits format returns the shared weekly pool.
        // v283：改用 GrokCliClient.BASE_URL 拼，避免以后代理域名变了这里漏改
        // （聊天那条已经踩过一次「两边地址不一致」的坑）。
        private const val CREDITS_URL = "${GrokCliClient.BASE_URL}/billing?format=credits"
        private const val SETTINGS_URL = "${GrokCliClient.BASE_URL}/settings"

        /**
         * v284：撞到免费额度上限后的冷却时长。
         *
         * 取 1 小时而不是外部实现常用的 24 小时：xAI 那个窗口是**滚动**的（往前数 24 小时的用量），
         * 会一点点释放，1 小时后值得再试。24 小时对个人用户等于锁死一整天。
         */
        private const val QUOTA_COOLDOWN_MS = 60 * 60 * 1000L
    }
}

internal fun isGrokRefreshAuthenticationFailure(
    statusCode: Int,
    responseBody: String,
    json: Json,
): Boolean {
    if (statusCode == 401) return true
    if (statusCode != 400) return false
    val errorCode = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return errorCode == "invalid_grant" || errorCode == "invalid_token"
}

internal fun selectGrokAccountIndex(
    accounts: List<GrokAccount>,
    startIndex: Int,
    nowMillis: Long = System.currentTimeMillis(),
    ignoreCooldown: Boolean = false,
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable(nowMillis, ignoreCooldown = ignoreCooldown)) return index
    }
    return null
}
