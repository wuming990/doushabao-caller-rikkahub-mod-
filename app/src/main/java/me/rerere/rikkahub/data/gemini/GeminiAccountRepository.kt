package me.rerere.rikkahub.data.gemini

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAccountRepository internal constructor(
    private val store: GeminiCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != GeminiTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = GeminiTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GeminiAccount>> = _accounts.asStateFlow()

    /**
     * Persist a freshly exchanged token set.
     *
     * Both the sign-in identity and the Cloud Code Assist project are resolved here, outside the
     * lock, because each is a network round trip and holding the mutex across them would stall
     * every concurrent generate request behind a sign-in.
     */
    suspend fun saveLogin(tokenJson: String): GeminiAccount {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing refresh token. Sign in again and grant offline access.")
        val identity = fetchIdentity(accessToken)
        val projectId = discoverProject(accessToken)
        val expiresAt = System.currentTimeMillis() + (
            token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            ) * 1000

        return mutex.withLock {
            val id = identity.email.ifBlank { projectId }
            val existing = state.accounts.firstOrNull { it.id == id }
            val account = GeminiAccount(
                id = id,
                name = identity.name.ifBlank { identity.email.ifBlank { "Google account" } },
                email = identity.email,
                projectId = projectId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                enabled = existing?.enabled ?: true,
                tokenStatus = GeminiTokenStatus.AVAILABLE,
            )
            updateState(
                state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account)
            )
            account
        }
    }

    suspend fun acquireAccount(): GeminiAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Google account is signed in")
        repeat(state.accounts.size) {
            val index = selectGeminiAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
            ) ?: error("No available Google account")
            val candidate = state.accounts[index]
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Google account")
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): GeminiAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Google account not found")
        val fresh = ensureFreshLocked(account, force = true)
        // Quota is informational, so a backend that will not report it must not turn a perfectly
        // good token refresh into a failure.
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: GeminiAccount,
        force: Boolean = false,
    ): GeminiAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(GeminiOAuthManager.TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("client_id", GeminiOAuthManager.CLIENT_ID)
                            .add("client_secret", GeminiOAuthManager.CLIENT_SECRET)
                            .add("refresh_token", account.refreshToken)
                            .add("grant_type", "refresh_token")
                            .build()
                    )
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isGeminiRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = GeminiTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    /**
     * Refresh the account's quota.
     *
     * v280：主路径改成官方客户端自己用的 `retrieveUserQuotaSummary` —— 它返回的是**账号级**的
     * 分组桶（Gemini 一组、Claude/GPT 一组，每组各有 5 小时桶与每周桶），正是官方配额面板的
     * 口径。`fetchAvailableModels` 降级为退路：那个接口报的是**模型级**短窗，对个人账号会
     * 「不管实际用了多少都报 100%」（外部项目实测），本项目真机也撞上了 —— 账号周额度已耗尽
     * （发消息 429 QUOTA_EXHAUSTED、163 小时后重置），面板却仍显示满格。
     *
     * 读额度必须与发消息打同一个主机：外部项目实测同一账号同一 RPC 在 prod 与 daily 上的数值
     * 能差几十倍，所以这里也走 [DAILY_CODE_ASSIST_ENDPOINT]。
     */
    private suspend fun fetchUsageLocked(account: GeminiAccount): GeminiAccount {
        val summary = runCatching { fetchQuotaSummaryLocked(account) }
            .onFailure { Log.w(TAG, "retrieveUserQuotaSummary failed: ${it.message}") }
            .getOrNull()
        if (summary != null) {
            val updated = account.copy(usage = summary)
            replaceAccount(account.id) { updated }
            return updated
        }
        // 退路会给出偏乐观的读数（见上），所以留一条日志说明当前看到的数字是哪来的。
        Log.w(TAG, "falling back to fetchAvailableModels for quota; readings may be optimistic")
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$DAILY_CODE_ASSIST_ENDPOINT/v1internal:fetchAvailableModels")
                    .antigravityHeaders(account.accessToken)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).await()
        }
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Failed to fetch Gemini usage: ${response.code} $body")
        }
        val snapshot = parseGeminiQuotaUsage(json.parseToJsonElement(body).jsonObject)
        if (snapshot == null) {
            // Keeping the previous snapshot beats blanking the card, but the user is then looking
            // at a stale reading, so say why rather than failing silently.
            Log.w(TAG, "fetchAvailableModels reported no quota; keeping the previous snapshot")
            return account
        }
        val updated = account.copy(usage = snapshot)
        replaceAccount(account.id) { updated }
        return updated
    }

    /**
     * v280：官方配额面板的数据源。
     *
     * 请求体带上 project 是照官方客户端的做法；空 body 也被服务端接受，但带上更接近真实客户端。
     * 返回 null 表示「响应能解析但里面没有任何配额桶」—— 交由调用方决定是否回退，而不是在这里
     * 悄悄当成 0% 或 100%。
     */
    private suspend fun fetchQuotaSummaryLocked(account: GeminiAccount): GeminiUsageSnapshot? {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$DAILY_CODE_ASSIST_ENDPOINT/v1internal:retrieveUserQuotaSummary")
                    .antigravityHeaders(account.accessToken)
                    .post(
                        json.encodeToString(
                            buildJsonObject { put("project", account.projectId) }
                        ).toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            ).await()
        }
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Failed to fetch Gemini quota summary: ${response.code} $body")
        }
        return parseGeminiQuotaSummary(json.parseToJsonElement(body).jsonObject)
    }

    private suspend fun fetchIdentity(accessToken: String): GeminiIdentity =
        withContext(Dispatchers.IO) {
            val response = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(USERINFO_URL)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                ).await()
            }.getOrNull() ?: return@withContext GeminiIdentity()
            if (!response.isSuccessful) {
                response.close()
                return@withContext GeminiIdentity()
            }
            val body = runCatching {
                json.parseToJsonElement(response.body.string()).jsonObject
            }.getOrNull() ?: return@withContext GeminiIdentity()
            GeminiIdentity(
                email = body["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    /**
     * Resolve the `cloudaicompanionProject` this account generates against.
     *
     * Mirrors Antigravity's own onboarding: loadCodeAssist either hands back a project outright
     * or reports the tier to onboard against, and an account that has never used Code Assist is
     * provisioned one by onboardUser.
     */
    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:loadCodeAssist")
                .antigravityHeaders(accessToken)
                .post(
                    json.encodeToString(
                        buildJsonObject {
                            put("metadata", clientMetadataJson())
                        }
                    ).toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val loadBody = loadResponse.body.string()
        if (!loadResponse.isSuccessful) {
            error("loadCodeAssist failed: ${loadResponse.code} $loadBody")
        }
        val load = json.parseToJsonElement(loadBody).jsonObject

        readProjectId(load["cloudaicompanionProject"])
            ?.let { return@withContext it }

        val tierId = selectGeminiTier(load)?.get("id")?.jsonPrimitive?.contentOrNull ?: TIER_LEGACY
        val diagnosis = summarizeCodeAssistTiers(load, tierId)
        Log.i(TAG, "discoverProject: $diagnosis")

        // onboardUser returns a long-running operation that is usually already finished. When it
        // is not, Antigravity re-sends the same request rather than polling the operation by name,
        // so the provisioning it kicked off is picked up by the next call's response.
        //
        // v286：**`done = true` 不代表项目编号已经生成好了。** 谷歌那边的开通是异步的，
        // 所以退出条件看的是「编号拿到没有」，不是 `done`。
        //
        // v287：但真机结果证明重试也救不了 —— 用户的免费层账号重发 8 次全是空编号。根因是
        // 谷歌把账号归到了「项目要你自己交」的类型（见 [requiresUserDefinedProject]），
        // 那种情况下重发多少次都一样，只是白让用户等 20 秒。所以先判一下再决定要不要重试，
        // 并且加两条后备路径。
        //
        // 顺手记下两条不能走的路：
        // - 免费层也是**有**托管项目编号的（不是「免费就没有」），拿空编号去发消息会被
        //   400「Invalid project resource name projects/」拒掉，所以这个编号绕不过去；
        // - 给免费层的 onboardUser 传 cloudaicompanionProject 会 400 FAILED_PRECONDITION，
        //   所以请求体里只能有 tierId + metadata。
        val selfServe = requiresUserDefinedProject(load, tierId)
        var lastOperation: JsonObject? = null

        if (!selfServe) {
            for (attempt in 0 until ONBOARD_MAX_ATTEMPTS) {
                if (attempt > 0) delay(ONBOARD_RETRY_INTERVAL_MS)
                val result = onboardOnce(accessToken, tierId, DAILY_CODE_ASSIST_ENDPOINT)
                if (result.failureBody != null) {
                    // 第一次就 HTTP 失败（401 / 403 / 资格不符这类）是硬错误，照旧立刻报出来；
                    // 后续尝试的偶发失败不打断重试。
                    if (attempt == 0) {
                        error("onboardUser failed: ${result.httpCode} ${result.failureBody}")
                    }
                    continue
                }
                lastOperation = result.operation ?: lastOperation
                result.projectId?.let { return@withContext it }
            }
        }

        // v287 后备路径 1：同一个 onboardUser 换 prod 端点再试一次。
        // 官方把 onboardUser 放在 daily（v279 照此改过），但有第三方实现反馈 prod 对
        // 「解析托管项目」支持得更好。这一次调用成本极低，失败也不影响后面的路。
        val prodAttempt = try {
            onboardOnce(accessToken, tierId, CODE_ASSIST_ENDPOINT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "onboardUser on prod failed: ${e.message}")
            null
        }
        prodAttempt?.let { result ->
            lastOperation = result.operation ?: lastOperation
            result.projectId?.let { return@withContext it }
        }

        // v287 后备路径 2：直接问「这个账号名下有哪些项目」，两个端点各试一次。
        listCompanionProject(accessToken, DAILY_CODE_ASSIST_ENDPOINT)
            ?.let { return@withContext it }
        listCompanionProject(accessToken, CODE_ASSIST_ENDPOINT)
            ?.let { return@withContext it }

        val reason = if (selfServe) SELF_SERVE_PROJECT_MESSAGE else ONBOARD_NO_PROJECT_MESSAGE
        error("$reason（诊断：$diagnosis；最后一次响应：$lastOperation）")
    }

    private data class OnboardAttempt(
        val httpCode: Int,
        val operation: JsonObject? = null,
        val projectId: String? = null,
        val failureBody: String? = null,
    )

    /**
     * v287：发一次 onboardUser 并解析结果。抽出来是为了让「daily 端点重试」与
     * 「prod 端点兜底一次」共用同一份请求构造，不至于两处渐渐写歪。
     *
     * HTTP 失败不抛异常，交给调用方决定是硬错误还是可以继续 —— 后备路径上的失败不该带崩登录。
     */
    private suspend fun onboardOnce(
        accessToken: String,
        tierId: String,
        endpoint: String,
    ): OnboardAttempt {
        val response = client.newCall(
            Request.Builder()
                .url("$endpoint/v1internal:onboardUser")
                .antigravityHeaders(accessToken)
                .post(
                    json.encodeToString(
                        buildJsonObject {
                            put("tierId", tierId)
                            put("metadata", clientMetadataJson())
                        }
                    ).toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            return OnboardAttempt(httpCode = response.code, failureBody = body)
        }
        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }
        return OnboardAttempt(
            httpCode = response.code,
            operation = parsed,
            projectId = readOnboardProjectId(parsed),
        )
    }

    /**
     * v287：后备路径 —— 直接问谷歌「这个账号名下有哪些 Cloud AI Companion 项目」。
     *
     * 官方没公开这个接口，响应结构靠第三方实现反推（见 [readProjectIdFromProjectList]）。
     * 它是兜底手段，任何失败都只当「这条路走不通」返回 null，绝不把整个登录带崩；
     * 但**协程取消必须原样上抛**，否则用户点取消会被吞成「这条路失败，继续下一条」。
     */
    private suspend fun listCompanionProject(accessToken: String, endpoint: String): String? {
        val response = try {
            client.newCall(
                Request.Builder()
                    .url("$endpoint/v1internal:listCloudAICompanionProjects")
                    .antigravityHeaders(accessToken)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).await()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "listCloudAICompanionProjects on $endpoint failed: ${e.message}")
            return null
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "listCloudAICompanionProjects on $endpoint: HTTP ${response.code}")
            return null
        }
        val parsed = try {
            json.parseToJsonElement(response.body.string()).jsonObject
        } catch (_: Exception) {
            null
        }
        return readProjectIdFromProjectList(parsed)
    }

    private fun replaceAccount(
        accountId: String,
        transform: (GeminiAccount) -> GeminiAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: GeminiAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        const val CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"

        // v279：个人 Google 账号（loadCodeAssist 响应里 gcpManaged=false，含 Free/Pro/Ultra/
        // Google One）在 Cloud Code Assist 上被严格绑定到 daily 后端；把生成类请求发到上面那个
        // prod 端点会 100% 返回 429 RESOURCE_EXHAUSTED，因为 Google 只对 enterprise/
        // GCP-licensed 账号开放 prod（router-for-me/CLIProxyAPI issue #5209 实测 24/24，
        // PR #5228 复测同一 token 在 prod 仍 429）。官方 agy 客户端的分工是 loadCodeAssist
        // 留在 prod 做账号分类，onboardUser 与所有生成类请求走 daily（PR #3254 把 onboardUser
        // 从 prod 改到了 daily）。遇 429 绝不在两个端点之间来回回退：那会把健康账号误判成额度
        // 耗尽，触发 #5209 描述的 cooldown 级联。
        const val DAILY_CODE_ASSIST_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
        private const val REFRESH_MARGIN_MS = 30_000L
        private const val TAG = "GeminiAccountRepository"
        private const val TIER_LEGACY = "legacy-tier"

        /**
         * v286：拿不到项目编号时的重发间隔与次数。
         *
         * 8 次 × 2.5 秒 ≈ 最长等 20 秒。取这个量级的理由：谷歌的开通是异步的，而 Provision
         * 请求本身还有频率配额（打太密会 429，反而更慢）。外部实现取 5~10 次 / 2~5 秒，
         * 20 秒也是用户还愿意等着看结果的上限。
         */
        private const val ONBOARD_RETRY_INTERVAL_MS = 2_500L
        private const val ONBOARD_MAX_ATTEMPTS = 8

        /**
         * v286：重试到底还是拿不到项目编号时给用户看的话。
         *
         * 写成中文人话而不是内部错误字符串，因为它会原样出现在界面上。两种真实情形都要覆盖：
         * 账号从没在官方端激活过（要去激活），以及官方端已能用但谷歌侧绑定状态异常（只能等）。
         */
        const val ONBOARD_NO_PROJECT_MESSAGE =
            "谷歌没有给这个账号分配项目编号，已重试多次仍为空。" +
                "如果这是新账号，请先用官方 Antigravity 客户端登录它、发一条消息完成首次激活，再回来重新添加；" +
                "如果它在官方端已经能正常使用，那是谷歌侧的项目绑定还没就绪，通常过几分钟再试即可"

        /**
         * v287：谷歌把账号归到「项目要你自己交」的类型时给用户看的话。
         *
         * 这种情况**重试毫无意义**，所以文案不能再说「过几分钟再试」，必须说清是资格问题。
         * 判定依据见 [requiresUserDefinedProject]。
         */
        const val SELF_SERVE_PROJECT_MESSAGE =
            "谷歌把这个账号归到了「需要你自己提供 Google Cloud 项目」的类型，不是免费托管层，" +
                "所以它不会自动分配项目编号，重试也没有用。" +
                "常见原因是这个账号不符合免费层资格（地区、账号类型或年龄限制），或者被谷歌重新归类了。" +
                "可以换一个个人 Gmail 账号试试；如果它在官方端能正常使用，那是谷歌侧的资格判定问题，软件这边改不了"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private data class GeminiIdentity(
    val email: String = "",
    val name: String = "",
)

/**
 * The Cloud Code Assist backend gates model routing and quota on the client it believes it is
 * talking to, so every call identifies itself as an Antigravity build. Unlike the Gemini CLI,
 * Antigravity sends no `Client-Metadata` header: the same information travels in the request
 * body instead.
 *
 * v279：原先自报 `antigravity/hub/<version> android/<arch>`，拼出的是一个官方从未发布过的
 * 客户端形态 —— 已知被后端接受的只有 `antigravity/<semver> <goos>/<goarch>`（CLI 与桌面）、
 * `antigravity/cli/<version> (...)`（agy CLI）和 Electron 的完整 Chrome UA 三种，而且官方
 * 没有 Android 客户端，所以 `hub/` 与 `android/` 两段都是本项目自己造的。后端按自报客户端
 * 路由配额，认不出的形态会被分进最差的桶，因此改成社区实测可用的桌面形态
 * （sipeed/picoclaw 的 antigravity_provider.go 用的就是 `antigravity/<ver> linux/amd64`）。
 * 版本号继续取 [ANTIGRAVITY_VERSION]，必须跟着官方最新版走：太旧的版本会被直接拒绝
 * （"This version of Antigravity is no longer supported"，badlogic/pi-mono issue #1079）。
 */
internal fun Request.Builder.antigravityHeaders(accessToken: String): Request.Builder =
    header("Authorization", "Bearer $accessToken")
        .header("User-Agent", ANTIGRAVITY_USER_AGENT)

internal fun clientMetadataJson(): JsonObject = buildJsonObject {
    put("ideType", "ANTIGRAVITY")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}

// v275：2.1.4 → 2.11.0 对齐官方最新版（2026-08-26 发布）。
// 用户真机登录成功后所有模型请求被 429 RESOURCE_EXHAUSTED 拦截（额度面板 100% 剩余）。
// 官方 2.11.0 changelog 明确修过「cold-start token endpoint routing 引发的 429 resource
// exhaustion」，且 Cloud Code Assist 后端按自报客户端版本路由模型与配额——旧版本号
// 会被分进旧的待遇桶。2.11.0 修复清单见 antigravity.google/changelog。
internal const val ANTIGRAVITY_VERSION = "2.11.0"

// v279：必须声明在 ANTIGRAVITY_VERSION 之后 —— Kotlin 顶层 const val 的初始化器不允许前向
// 引用同文件里更靠后声明的 const，写在前面会直接编译失败（"Variable 'ANTIGRAVITY_VERSION'
// must be initialized"）。函数体里引用不受此限，只有属性初始化器有这个顺序要求。
internal const val ANTIGRAVITY_USER_AGENT = "antigravity/$ANTIGRAVITY_VERSION linux/amd64"

internal fun isGeminiRefreshAuthenticationFailure(
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

/**
 * Pick the tier to onboard against from a `loadCodeAssist` response.
 *
 * `currentTier` wins when present: it is the tier the account is already on, not a signal that
 * the account is unusable. Only when there is no current tier does the default entry in
 * `allowedTiers` apply. Returning null leaves the caller on the legacy tier, which is the same
 * fallback Antigravity uses.
 */
internal fun selectGeminiTier(load: JsonObject): JsonObject? =
    load["currentTier"]?.jsonObject
        ?: load["allowedTiers"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }

private const val WINDOW_DAILY = "daily"
private const val WINDOW_WEEKLY = "weekly"
private const val WINDOW_5H = "5h"
private const val ONE_DAY_SECONDS = 24 * 60 * 60L

/**
 * v280：解析 `retrieveUserQuotaSummary`。
 *
 * 形状（官方客户端配额面板用的就是这个）：
 * ```
 * {"groups":[
 *   {"displayName":"Gemini Models","buckets":[
 *     {"bucketId":"gemini-5h","displayName":"Five Hour Limit Remaining",
 *      "window":"5h","remainingFraction":0.42,"resetTime":"2026-09-03T18:00:00Z"},
 *     {"bucketId":"gemini-weekly","window":"weekly","remainingFraction":0.0, ...}]},
 *   {"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-5h", ...}]}]}
 * ```
 *
 * 返回 null 有两种情形：根本没有 `groups`（形状变了或不是这个接口的响应），或者所有组都没有
 * 一条能读出 `remainingFraction` 的桶。两种都交给调用方决定要不要回退，**绝不猜成 100%** ——
 * 「缺字段就当满格」正是 v279 面板骗人的根源。
 *
 * 同时把 Gemini 那一组的两个桶回填进旧的 [GeminiUsageSnapshot.daily] / [GeminiUsageSnapshot.weekly]
 * 字段：老界面与任何还在读那两个字段的地方不至于突然空掉。注意 `daily` 装的是 5 小时桶
 * （那个字段名是历史遗留，见其文档）。
 */
internal fun parseGeminiQuotaSummary(
    root: JsonObject,
    nowMillis: Long = System.currentTimeMillis(),
): GeminiUsageSnapshot? {
    val rawGroups = (root["groups"] ?: root["quotaGroups"]) as? kotlinx.serialization.json.JsonArray
        ?: return null
    val groups = rawGroups.mapNotNull { element ->
        val group = element as? JsonObject ?: return@mapNotNull null
        val buckets = (group["buckets"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(::readGeminiQuotaBucket)
            .orEmpty()
        if (buckets.isEmpty()) return@mapNotNull null
        GeminiQuotaGroup(
            name = group["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            buckets = buckets,
        )
    }
    if (groups.isEmpty()) return null
    // 回填旧字段时优先认 Gemini 那一组：这是本项目的主用途，Claude/GPT 组只是备用池。
    val primary = groups.firstOrNull { group ->
        group.buckets.any { it.bucketId.startsWith("gemini") }
    } ?: groups.first()
    return GeminiUsageSnapshot(
        daily = primary.buckets.firstOrNull { it.window == WINDOW_5H }
            ?.let { GeminiUsageWindow(it.remainingFraction, it.resetsAt) },
        weekly = primary.buckets.firstOrNull { it.window == WINDOW_WEEKLY }
            ?.let { GeminiUsageWindow(it.remainingFraction, it.resetsAt) },
        updatedAt = nowMillis,
        groups = groups,
    )
}

/** v280：读一条桶。读不出 `remainingFraction` 就整条丢掉 —— 宁可少显示一行，也不显示假数字。 */
private fun readGeminiQuotaBucket(bucket: JsonObject): GeminiQuotaBucket? {
    val fraction = bucket["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: return null
    val bucketId = bucket["bucketId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val declaredWindow = bucket["window"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return GeminiQuotaBucket(
        bucketId = bucketId,
        label = bucket["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        window = normalizeGeminiQuotaWindow(declaredWindow.ifBlank { bucketId }),
        remainingFraction = fraction.coerceIn(0.0, 1.0),
        resetsAt = parseQuotaResetTime(bucket["resetTime"]?.jsonPrimitive?.contentOrNull),
    )
}

/**
 * v280：窗口标识归一化。
 *
 * 官方在 `window` 字段给 `5h` / `weekly`，而 `bucketId` 形如 `gemini-5h` / `3p-weekly`，所以两处
 * 都能认。认不出来时原样返回，界面会退回显示官方给的 displayName —— 服务端加了新窗口（比如
 * 日桶或月桶）时不至于显示成空白。
 */
internal fun normalizeGeminiQuotaWindow(raw: String): String {
    val text = raw.lowercase()
    return when {
        text.contains("week") -> WINDOW_WEEKLY
        text.contains("5h") || text.contains("five") -> WINDOW_5H
        text.contains("day") || text.contains("daily") -> WINDOW_DAILY
        else -> text
    }
}

// Quota can arrive under any of these keys, singular or as an array. The two prefixed ones name
// their own window; the bare ones have to be classified from what is inside them.
private val QUOTA_FIELDS = listOf(
    "quotaInfo" to null,
    "quotaInfos" to null,
    "dailyQuotaInfo" to WINDOW_DAILY,
    "dailyQuotaInfos" to WINDOW_DAILY,
    "weeklyQuotaInfo" to WINDOW_WEEKLY,
    "weeklyQuotaInfos" to WINDOW_WEEKLY,
)

/**
 * Collapse the per-model quota in a `fetchAvailableModels` response into one reading per window.
 *
 * Returns null when the response carries no quota at all, which keeps a backend that stops
 * reporting it from wiping a snapshot the user is still looking at.
 */
internal fun parseGeminiQuotaUsage(
    root: JsonObject,
    nowMillis: Long = System.currentTimeMillis(),
): GeminiUsageSnapshot? {
    val models = root["models"] as? JsonObject ?: return null
    var daily: GeminiUsageWindow? = null
    var weekly: GeminiUsageWindow? = null
    for (modelElement in models.values) {
        val model = modelElement as? JsonObject ?: continue
        for ((field, declaredWindow) in QUOTA_FIELDS) {
            for (info in quotaInfosIn(model[field])) {
                val fraction = info["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: continue
                val resetsAt = parseQuotaResetTime(info["resetTime"]?.jsonPrimitive?.contentOrNull)
                val window = GeminiUsageWindow(fraction.coerceIn(0.0, 1.0), resetsAt)
                val id = declaredWindow ?: classifyQuotaWindow(info, resetsAt, nowMillis)
                if (id == WINDOW_WEEKLY) {
                    weekly = scarcerOf(weekly, window)
                } else {
                    daily = scarcerOf(daily, window)
                }
            }
        }
    }
    if (daily == null && weekly == null) return null
    return GeminiUsageSnapshot(daily = daily, weekly = weekly, updatedAt = nowMillis)
}

private fun quotaInfosIn(element: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
    when (element) {
        is JsonObject -> listOf(element)
        is kotlinx.serialization.json.JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

private fun scarcerOf(current: GeminiUsageWindow?, candidate: GeminiUsageWindow) =
    if (current == null || candidate.remainingFraction < current.remainingFraction) {
        candidate
    } else {
        current
    }

private fun classifyQuotaWindow(
    info: JsonObject,
    resetsAt: Long?,
    nowMillis: Long,
): String {
    val source = listOfNotNull(
        info["windowId"]?.jsonPrimitive?.contentOrNull,
        info["windowLabel"]?.jsonPrimitive?.contentOrNull,
    ).joinToString(" ").lowercase()
    if (source.contains("week") || source.contains("7d")) return WINDOW_WEEKLY
    if (source.contains("day") || source.contains("24h")) return WINDOW_DAILY
    // Nothing labelled it, so fall back to how far out it resets: anything more than a day away
    // cannot be a daily window.
    val secondsUntilReset = resetsAt?.minus(nowMillis / 1000) ?: return WINDOW_DAILY
    return if (secondsUntilReset > ONE_DAY_SECONDS) WINDOW_WEEKLY else WINDOW_DAILY
}

private fun parseQuotaResetTime(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(raw).epochSecond }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
}

/**
 * Read a `cloudaicompanionProject` value, which comes back either as a bare string or as an
 * object carrying an `id` depending on the tier, so accept both rather than assuming one shape.
 */
internal fun readProjectId(element: kotlinx.serialization.json.JsonElement?): String? =
    ((element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        ?: (element as? JsonPrimitive)?.contentOrNull)
        ?.takeIf { it.isNotBlank() }

/**
 * v286：从一次 `onboardUser` 的响应里读项目编号，读不到就返回 null。
 *
 * 抽成顶层函数是为了能真单测 —— 真机故障的那个响应形态（`done=true` 但
 * `cloudaicompanionProject` 是空对象 `{}`）光看代码是看不出问题的。
 *
 * **判断成功的唯一标准是「编号读到了」，不是 `done`。** 谷歌会先回「操作已完成」，
 * 编号却要等后台开通真正落地才出现；把 `done` 当成功就会在这里过早放弃。
 */
internal fun readOnboardProjectId(operation: JsonObject?): String? =
    readProjectId(operation?.get("response")?.jsonObject?.get("cloudaicompanionProject"))

/**
 * v287：从 `listCloudAICompanionProjects` 的响应里挑一个项目编号。
 *
 * 这个接口是 `onboardUser` 拿不到编号时的后备路径（pi-antigravity、@raquezha/antigravity
 * 都这么用）。官方没有公开它的响应结构，第三方实现见过三种键名、每一项可能是裸字符串也可能是
 * 带 `id` 的对象，所以三种键名都认、两种形状都认 —— 宽容解析比赌一种形状安全。
 */
internal fun readProjectIdFromProjectList(body: JsonObject?): String? {
    if (body == null) return null
    listOf("cloudaicompanionProjects", "projects", "projectIds").forEach { key ->
        val element = body[key] ?: return@forEach
        if (element is JsonArray) {
            element.forEach { item -> readProjectId(item)?.let { return it } }
        } else {
            readProjectId(element)?.let { return it }
        }
    }
    return null
}

/**
 * v287：这个账号是不是被谷歌归到了「项目要你自己交」的类型。
 *
 * ## 为什么必须先判这一下
 *
 * v286 的真机结果：重发 onboardUser 8 次，每次都是 `done=true` + 空编号。原因不是「还没开通完」，
 * 而是**谷歌根本不打算给这个账号自动分配项目**。
 *
 * 谷歌的账号分三类（官方 gemini-cli `types.ts` 的 `UserTierId`）：
 * - `free-tier`：项目由谷歌托管，**会**自动分配；
 * - `standard-tier`：标了 `userDefinedCloudaicompanionProject = true`，**要用户自带** GCP 项目；
 * - `legacy-tier`：老账号。
 *
 * 当服务端把账号归到 standard-tier、而请求里又没带项目时，onboardUser 的返回**恰好就是
 * `done=true` + 空编号**（sub2api#3443 实录）。这种情况重发多少次都一样，
 * 干等只是白白让用户多看 20 秒转圈。
 */
internal fun requiresUserDefinedProject(load: JsonObject, selectedTierId: String): Boolean {
    val tier = load["allowedTiers"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == selectedTierId }
        ?: load["currentTier"]?.jsonObject
            ?.takeIf { it["id"]?.jsonPrimitive?.contentOrNull == selectedTierId }
    return tier?.get("userDefinedCloudaicompanionProject")?.jsonPrimitive?.booleanOrNull == true
}

/**
 * v287：把 `loadCodeAssist` 响应里决定成败的那几项摘成一行，附在报错后面。
 *
 * 这是「选错类型 / 账号不合格 / 谷歌侧绑定卡死」三种情况的唯一分水岭，v286 之前它被整份丢掉，
 * 导致真机失败时完全无从判断。**只摘 tier 相关字段，不带邮箱等身份信息。**
 *
 * 输出形如：
 * `选用=standard-tier；当前=无；可用=standard-tier(默认)(需自带项目)；不合格=free-tier→INELIGIBLE_ACCOUNT`
 */
internal fun summarizeCodeAssistTiers(load: JsonObject, selectedTierId: String): String {
    val current = load["currentTier"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    val allowed = load["allowedTiers"]?.jsonArray
        ?.map { it.jsonObject }
        ?.joinToString(", ") { tier ->
            buildString {
                append(tier["id"]?.jsonPrimitive?.contentOrNull ?: "?")
                if (tier["isDefault"]?.jsonPrimitive?.booleanOrNull == true) append("(默认)")
                if (tier["userDefinedCloudaicompanionProject"]?.jsonPrimitive?.booleanOrNull == true) {
                    append("(需自带项目)")
                }
            }
        }
        .orEmpty()
    val ineligible = load["ineligibleTiers"]?.jsonArray
        ?.map { it.jsonObject }
        ?.joinToString(", ") { tier ->
            val id = tier["tierId"]?.jsonPrimitive?.contentOrNull ?: "?"
            val reason = tier["reasonCode"]?.jsonPrimitive?.contentOrNull ?: "?"
            "$id→$reason"
        }
        .orEmpty()
    return "选用=$selectedTierId；当前=${current ?: "无"}；" +
        "可用=${allowed.ifBlank { "无" }}；不合格=${ineligible.ifBlank { "无" }}"
}

internal fun selectGeminiAccountIndex(
    accounts: List<GeminiAccount>,
    startIndex: Int,
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable()) return index
    }
    return null
}
