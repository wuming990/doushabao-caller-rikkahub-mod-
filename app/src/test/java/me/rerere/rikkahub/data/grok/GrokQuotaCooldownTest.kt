package me.rerere.rikkahub.data.grok

import me.rerere.rikkahub.agent.model.AgentErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v284：Grok 免费额度用尽的识别、账号冷却、轮询跳过，以及那个数字误匹配的修复。
 *
 * 真机事故（用户实测）：
 * ```
 * You've used all the included free usage for model grok-4.6 for now.
 * Usage resets over a rolling 24-hour window — tokens (actual/limit): 672022/500000.
 * Upgrade to a Grok subscription for higher limits: https://grok.com/supergrok
 * ```
 * 三个问题一起暴露：
 * 1. 撞了这个限额的账号不会被标记，轮询下次还会派它上场（用户只有一个账号时表现为「轮询好像不存在」）；
 * 2. 额度面板读的是 credits 池，跟这个「按模型 + 24 小时滚动窗口」的 token 上限是两回事，
 *    于是「面板满格却发不出消息」；
 * 3. **错误分类把它判成了「值得重试」** —— 因为旧代码把 `"500"` 当关键词做 contains，
 *    而报错里的 `500000` 正好含 `500`，被当成 HTTP 500 服务器故障，自动续跑白烧好几轮。
 */
class GrokQuotaCooldownTest {

    private val realWorldError =
        "You've used all the included free usage for model grok-4.6 for now. " +
            "Usage resets over a rolling 24-hour window — tokens (actual/limit): 672022/500000. " +
            "Upgrade to a Grok subscription for higher limits: https://grok.com/supergrok"

    private val structuredBody =
        """{"code":"subscription:free-usage-exhausted","error":"$realWorldError"}"""

    private fun account(
        id: String,
        enabled: Boolean = true,
        status: GrokTokenStatus = GrokTokenStatus.AVAILABLE,
        cooldownUntil: Long = 0,
    ) = GrokAccount(
        id = id,
        name = id,
        accessToken = "at-$id",
        refreshToken = "rt-$id",
        expiresAt = 0,
        enabled = enabled,
        tokenStatus = status,
        cooldownUntil = cooldownUntil,
    )

    // ---- 识别 ----

    @Test
    fun `两种形态的报错都能认出是免费额度用尽`() {
        assertTrue(isGrokFreeUsageExhausted(realWorldError))
        assertTrue(isGrokFreeUsageExhausted(structuredBody))
        // 只有结构化错误码、没有那句英文，也要认
        assertTrue(isGrokFreeUsageExhausted("""{"code":"subscription:free-usage-exhausted"}"""))
        // 别的 429 不能被误认
        assertFalse(isGrokFreeUsageExhausted("429 Too Many Requests"))
        assertFalse(isGrokFreeUsageExhausted("You have run out of credits or need a Grok subscription."))
    }

    @Test
    fun `用量和模型名都能从报错里抠出来`() {
        val limit = parseGrokModelUsageLimit(realWorldError, nowMillis = 123L)
        assertNotNull(limit)
        assertEquals("grok-4.6", limit!!.model)
        assertEquals(672022L, limit.usedTokens)
        assertEquals(500000L, limit.limitTokens)
        assertEquals(123L, limit.observedAt)
        // 已经超了，界面要按「用尽」标红
        assertTrue(limit.usedTokens >= limit.limitTokens)
    }

    @Test
    fun `抠不出数字时返回 null，绝不编一个数字出来`() {
        assertNull(parseGrokModelUsageLimit("""{"code":"subscription:free-usage-exhausted"}"""))
        assertNull(parseGrokModelUsageLimit("429 Too Many Requests"))
        assertNull(parseGrokModelUsageLimit(""))
        // 上限为 0 的畸形数据也不要
        assertNull(
            parseGrokModelUsageLimit(
                "included free usage ... tokens (actual/limit): 100/0"
            )
        )
    }

    @Test
    fun `模型名缺失时用 unknown 占位，但数字照记`() {
        val limit = parseGrokModelUsageLimit(
            "You've used all the included free usage for now. tokens (actual/limit): 10/20"
        )
        assertNotNull(limit)
        assertEquals("unknown", limit!!.model)
        assertEquals(10L, limit.usedTokens)
    }

    // ---- 冷却与轮询 ----

    @Test
    fun `冷却期内的账号被判为不可用，过期后自动恢复`() {
        val now = 1_000_000L
        assertFalse(account("a", cooldownUntil = now + 1).isAvailable(now))
        assertTrue(account("a", cooldownUntil = now - 1).isAvailable(now))
        assertTrue(account("a", cooldownUntil = 0).isAvailable(now))
    }

    @Test
    fun `轮询跳过冷却中的账号`() {
        val now = 1_000_000L
        val accounts = listOf(
            account("a", cooldownUntil = now + 60_000),
            account("b"),
            account("c", cooldownUntil = now + 60_000),
        )
        // 从 0 开始找：a 在冷却 → 跳到 b
        assertEquals(1, selectGrokAccountIndex(accounts, startIndex = 0, nowMillis = now))
        // 从 2 开始找：c 在冷却 → 绕回 a（也在冷却）→ b
        assertEquals(1, selectGrokAccountIndex(accounts, startIndex = 2, nowMillis = now))
        // 冷却到点之后，a 必须能被重新派上场（时间参数一定要真的透传下去，
        // v284 第一版漏传了它，轮询实际上是拿真实系统时间在判断，冷却等于没生效）
        assertEquals(0, selectGrokAccountIndex(accounts, startIndex = 0, nowMillis = now + 120_000))
    }

    @Test
    fun `所有账号都在冷却时必须能忽略冷却挑出一个`() {
        // 这条是给「只有一个账号」的用户兜底的：撞一次额度就彻底发不出消息是不可接受的。
        val now = 1_000_000L
        val all = listOf(
            account("a", cooldownUntil = now + 60_000),
            account("b", cooldownUntil = now + 60_000),
        )
        assertNull("正常挑必须挑不出来", selectGrokAccountIndex(all, startIndex = 0, nowMillis = now))
        assertEquals(
            "忽略冷却时必须挑得出来",
            0,
            selectGrokAccountIndex(all, startIndex = 0, nowMillis = now, ignoreCooldown = true),
        )
    }

    @Test
    fun `忽略冷却不等于忽略手动关闭和令牌失效`() {
        val now = 1_000_000L
        assertFalse(
            "用户手动关掉的账号，任何情况下都不能被派上场",
            account("a", enabled = false, cooldownUntil = now + 1).isAvailable(now, ignoreCooldown = true),
        )
        assertFalse(
            "令牌已失效的账号同理",
            account("a", status = GrokTokenStatus.INVALID).isAvailable(now, ignoreCooldown = true),
        )
    }

    @Test
    fun `冷却字段有默认值，旧账号数据反序列化后不受影响`() {
        assertEquals(0L, account("a").cooldownUntil)
        assertTrue(account("a").isAvailable(System.currentTimeMillis()))
        assertTrue(GrokUsageSnapshot().modelLimits.isEmpty())
    }

    // ---- 错误分类（这是本轮最关键的一条）----

    @Test
    fun `免费额度用尽必须判成「再试也没用」，不能触发自动续跑`() {
        assertEquals(
            "24 小时内不可能恢复，续跑只会白烧次数",
            AgentErrorKind.FATAL,
            AgentErrorKind.classify(realWorldError),
        )
        assertEquals(AgentErrorKind.FATAL, AgentErrorKind.classify(structuredBody))
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("API error (status 429): subscription:free-usage-exhausted: ..."),
        )
    }

    @Test
    fun `报错里的大数字不再被当成 HTTP 状态码（v284 修的正是这个）`() {
        // 旧代码：RECOVERABLE_MARKERS 里有裸字符串 "500"，contains("672022/500000") 命中 → 误判可恢复。
        assertEquals(
            "token 数里的 500000 不是 HTTP 500",
            AgentErrorKind.UNKNOWN,
            AgentErrorKind.classify("tokens (actual/limit): 672022/500000"),
        )
        assertEquals(
            "金额里的 400 不是 HTTP 400",
            AgentErrorKind.UNKNOWN,
            AgentErrorKind.classify("charged 4000 credits"),
        )
        assertEquals(
            "订单号里的 429 不是 HTTP 429",
            AgentErrorKind.UNKNOWN,
            AgentErrorKind.classify("request id 14290000"),
        )
    }

    @Test
    fun `真正的状态码仍然照旧判定`() {
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("HTTP 500 Internal Server Error"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("status 429 Too Many Requests"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("502 Bad Gateway"))
        assertEquals(AgentErrorKind.FATAL, AgentErrorKind.classify("HTTP 401 Unauthorized"))
        assertEquals(AgentErrorKind.FATAL, AgentErrorKind.classify("403 Forbidden"))
        assertEquals(AgentErrorKind.FATAL, AgentErrorKind.classify("Error 404: model not found"))
        // 网络类关键词不受影响
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("connection reset by peer"))
        assertEquals(AgentErrorKind.RECOVERABLE, AgentErrorKind.classify("SocketTimeoutException"))
        // 空与无法判断
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify(null))
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify("   "))
        assertEquals(AgentErrorKind.UNKNOWN, AgentErrorKind.classify("something went wrong"))
    }

    @Test
    fun `文字标记优先于状态码`() {
        // xAI 的免费额度用尽带的是 429（本该可恢复），但文字说明它 24 小时内不会好 → 必须 FATAL。
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("status 429: you have used all the included free usage"),
        )
        // 反过来：401 配上限流字样，仍然按 401 处理（认证问题换多少次都没用）
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("HTTP 401 unauthorized (rate limit hint)"),
        )
    }

    // ---- v285：宽口径额度判定（v284 的真机故障就出在这里）----

    @Test
    fun `402 和 403 的余额用尽也必须认出来`() {
        // v284 只认 429 + 免费额度文案，于是真机上余额用尽的账号既不报错也不换号，
        // 用户必须手动去设置页点一次「刷新」才能继续用别的账号。
        assertTrue(isGrokQuotaExhausted("You have run out of credits or need a Grok subscription."))
        assertTrue(isGrokQuotaExhausted("""{"code":"personal-team-blocked:spending-limit"}"""))
        assertTrue(isGrokQuotaExhausted("402 personal-team-blocked:spending-limit"))
        // 429 那一种当然也还要认
        assertTrue(isGrokQuotaExhausted(realWorldError))
        assertTrue(isGrokQuotaExhausted(structuredBody))
    }

    @Test
    fun `客户端版本过期不算额度问题，换账号没有意义`() {
        // 这条要改的是我们自报的客户端版本号，把它当成额度问题会把所有账号白撞一遍
        assertFalse(
            isGrokQuotaExhausted(
                "426 Your Grok CLI version (none) is outdated. Please update to version 0.1.202 or later"
            )
        )
    }

    @Test
    fun `纯限速与空文本不算额度用尽`() {
        assertFalse(isGrokQuotaExhausted("429 Too Many Requests"))
        assertFalse(isGrokQuotaExhausted("rate limit exceeded, retry after 20s"))
        assertFalse(isGrokQuotaExhausted(""))
        assertFalse(isGrokQuotaExhausted("   "))
    }

    @Test
    fun `宽口径不能污染窄口径：余额用尽里没有数字，绝不能编`() {
        val creditsError = "You have run out of credits or need a Grok subscription."
        // 窄口径专门服务于「按模型限额」的数字显示，不该认这一种
        assertFalse(isGrokFreeUsageExhausted(creditsError))
        assertNull(parseGrokModelUsageLimit(creditsError))
    }

    @Test
    fun `402 与余额用尽必须判成「再试也没用」`() {
        // 兜底异常的消息形态（v285 新增）也要能被正确分级
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("Stream failed with HTTP 402 (server returned no error detail)"),
        )
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("You have run out of credits or need a Grok subscription."),
        )
        assertEquals(
            AgentErrorKind.FATAL,
            AgentErrorKind.classify("403 personal-team-blocked:spending-limit"),
        )
    }

    @Test
    fun `兜底异常里的状态码仍然按独立数字匹配，不会被大数字误伤`() {
        // v284 修过的那个坑不能因为新增 402 而复活
        assertEquals(
            AgentErrorKind.UNKNOWN,
            AgentErrorKind.classify("Stream failed: transferred 40200 bytes"),
        )
    }

    // ---- v285：一次请求内「零输出就换账号重发」的判定 ----

    @Test
    fun `零输出且确实是额度问题，才换下一个账号重发`() {
        val credits = "You have run out of credits or need a Grok subscription."
        assertTrue(
            shouldRetryWithNextGrokAccount(credits, alreadyEmitted = false, attempt = 1, maxAttempts = 3)
        )
        assertTrue(
            shouldRetryWithNextGrokAccount(realWorldError, alreadyEmitted = false, attempt = 2, maxAttempts = 3)
        )
    }

    @Test
    fun `已经吐出内容就绝不重发，否则用户会看到前半段重复`() {
        // 这是用户明确否掉过的体验：宁可报错，也不要内容重复
        val credits = "You have run out of credits or need a Grok subscription."
        assertFalse(
            shouldRetryWithNextGrokAccount(credits, alreadyEmitted = true, attempt = 1, maxAttempts = 3)
        )
    }

    @Test
    fun `试满上限就停手，不能为一条消息把所有账号都撞一遍`() {
        val credits = "You have run out of credits or need a Grok subscription."
        assertFalse(
            shouldRetryWithNextGrokAccount(credits, alreadyEmitted = false, attempt = 3, maxAttempts = 3)
        )
        // 只有一个账号时等价于「不重试」——换过去还是它自己
        assertFalse(
            shouldRetryWithNextGrokAccount(credits, alreadyEmitted = false, attempt = 1, maxAttempts = 1)
        )
    }

    @Test
    fun `不是额度问题就不换号`() {
        assertFalse(shouldRetryWithNextGrokAccount("SocketTimeoutException", false, 1, 3))
        assertFalse(shouldRetryWithNextGrokAccount("Failed to connect to cli-chat-proxy.grok.com", false, 1, 3))
        assertFalse(
            shouldRetryWithNextGrokAccount(
                "426 Your Grok CLI version (none) is outdated",
                false, 1, 3,
            )
        )
        assertFalse(shouldRetryWithNextGrokAccount("", false, 1, 3))
    }
}
