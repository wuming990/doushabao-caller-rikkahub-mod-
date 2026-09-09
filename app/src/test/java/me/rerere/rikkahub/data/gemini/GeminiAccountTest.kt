package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun account(
        id: String,
        enabled: Boolean = true,
        tokenStatus: GeminiTokenStatus = GeminiTokenStatus.AVAILABLE,
    ) = GeminiAccount(
        id = id,
        name = id,
        projectId = "project-$id",
        accessToken = "access-$id",
        refreshToken = "refresh-$id",
        expiresAt = Long.MAX_VALUE,
        enabled = enabled,
        tokenStatus = tokenStatus,
    )

    @Test
    fun `an enabled account with a live token is available`() {
        assertTrue(account("a").isAvailable())
    }

    @Test
    fun `a disabled account is not available`() {
        assertFalse(account("a", enabled = false).isAvailable())
    }

    @Test
    fun `an invalid token makes an account unavailable`() {
        assertFalse(account("a", tokenStatus = GeminiTokenStatus.INVALID).isAvailable())
    }

    @Test
    fun `an expired token is still selectable so the caller gets a chance to refresh it`() {
        // acquireAccount() refreshes whatever it picks, so treating EXPIRED as unavailable here
        // would strand the only signed-in account permanently after its first hour.
        assertTrue(account("a", tokenStatus = GeminiTokenStatus.EXPIRED).isAvailable())
    }

    @Test
    fun `selection starts at the rotation index`() {
        val accounts = listOf(account("a"), account("b"), account("c"))
        assertEquals(1, selectGeminiAccountIndex(accounts, startIndex = 1))
    }

    @Test
    fun `selection wraps past the end of the list`() {
        val accounts = listOf(account("a"), account("b"), account("c"))
        assertEquals(0, selectGeminiAccountIndex(accounts, startIndex = 3))
    }

    @Test
    fun `selection skips unavailable accounts`() {
        val accounts = listOf(
            account("a", enabled = false),
            account("b", tokenStatus = GeminiTokenStatus.INVALID),
            account("c"),
        )
        assertEquals(2, selectGeminiAccountIndex(accounts, startIndex = 0))
    }

    @Test
    fun `selection returns null when every account is unavailable`() {
        val accounts = listOf(
            account("a", enabled = false),
            account("b", tokenStatus = GeminiTokenStatus.INVALID),
        )
        assertNull(selectGeminiAccountIndex(accounts, startIndex = 0))
    }

    @Test
    fun `selection returns null for an empty list`() {
        assertNull(selectGeminiAccountIndex(emptyList(), startIndex = 0))
    }

    @Test
    fun `a 401 is an authentication failure`() {
        assertTrue(isGeminiRefreshAuthenticationFailure(401, "", json))
    }

    @Test
    fun `a 400 invalid_grant is an authentication failure`() {
        assertTrue(
            isGeminiRefreshAuthenticationFailure(
                400,
                """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
                json,
            )
        )
    }

    @Test
    fun `a 400 that is not a grant problem is not an authentication failure`() {
        // Marking the account INVALID here would force a re-login over a transient or
        // malformed-request 400, which the user cannot act on.
        assertFalse(
            isGeminiRefreshAuthenticationFailure(400, """{"error":"invalid_request"}""", json)
        )
    }

    @Test
    fun `a 400 with an unparseable body is not an authentication failure`() {
        assertFalse(isGeminiRefreshAuthenticationFailure(400, "<html>bad gateway</html>", json))
    }

    @Test
    fun `a 500 is not an authentication failure`() {
        assertFalse(isGeminiRefreshAuthenticationFailure(500, "", json))
    }

    private fun load(body: String) = json.parseToJsonElement(body).jsonObject

    @Test
    fun `an account already on a tier is onboarded against that tier`() {
        // Regression: sign-in used to refuse any response carrying currentTier at all, which
        // rejected ordinary accounts that had already used Code Assist once. currentTier is the
        // tier to onboard against, not a signal that the account is unusable.
        val tier = selectGeminiTier(load("""{"currentTier":{"id":"free-tier"}}"""))
        assertNotNull(tier)
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the current tier wins over the default allowed tier`() {
        val tier = selectGeminiTier(
            load(
                """
                {"currentTier":{"id":"free-tier"},
                 "allowedTiers":[{"id":"legacy-tier","isDefault":true}]}
                """
            )
        )
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the default allowed tier is used when there is no current tier`() {
        val tier = selectGeminiTier(
            load(
                """
                {"allowedTiers":[{"id":"other-tier"},{"id":"free-tier","isDefault":true}]}
                """
            )
        )
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no tier information at all leaves the caller on its legacy fallback`() {
        assertNull(selectGeminiTier(load("{}")))
    }

    @Test
    fun `a project delivered as a bare string is read`() {
        assertEquals("proj-1", readProjectId(load("""{"p":"proj-1"}""")["p"]))
    }

    @Test
    fun `a project delivered as an object is read from its id`() {
        assertEquals("proj-1", readProjectId(load("""{"p":{"id":"proj-1"}}""")["p"]))
    }

    @Test
    fun `a missing or blank project reads as null`() {
        // A blank string here would be stored as the account's project and every later request
        // would 400 against it, so it has to be treated the same as an absent one.
        assertNull(readProjectId(null))
        assertNull(readProjectId(load("""{"p":""}""")["p"]))
        assertNull(readProjectId(load("""{"p":{}}""")["p"]))
    }

    // ------------------------------------------------------------------------------------------
    // v280：额度面板改读官方的 retrieveUserQuotaSummary。
    //
    // 起因是真机 bug：账号周额度已耗尽（发消息 429 QUOTA_EXHAUSTED、163 小时后才重置），面板
    // 却仍显示 100%。旧数据源 fetchAvailableModels 报的是模型级短窗，对个人账号会「不管用了多少
    // 都报满格」。下面的断言盯住新解析，尤其是「0% 必须显示成 0%」和「缺字段绝不猜成满格」。
    // ------------------------------------------------------------------------------------------

    private val quotaSummaryBody = """
        {"groups":[
          {"displayName":"Gemini Models","buckets":[
            {"bucketId":"gemini-5h","displayName":"Five Hour Limit Remaining",
             "window":"5h","remainingFraction":0.42,"resetTime":"2026-09-03T18:00:00Z"},
            {"bucketId":"gemini-weekly","displayName":"Weekly Limit Remaining",
             "window":"weekly","remainingFraction":0.0,"resetTime":"2026-09-10T09:28:05Z"}]},
          {"displayName":"Claude and GPT models","buckets":[
            {"bucketId":"3p-5h","window":"5h","remainingFraction":1.0},
            {"bucketId":"3p-weekly","window":"weekly","remainingFraction":0.87}]}]}
    """.trimIndent()

    @Test
    fun `the quota summary is read as groups of buckets`() {
        val snapshot = parseGeminiQuotaSummary(load(quotaSummaryBody), nowMillis = 1_000L)
        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.groups.size)
        assertEquals("Gemini Models", snapshot.groups[0].name)
        assertEquals(2, snapshot.groups[0].buckets.size)
        assertEquals("Claude and GPT models", snapshot.groups[1].name)
        assertEquals(1_000L, snapshot.updatedAt)

        val weekly = snapshot.groups[0].buckets[1]
        assertEquals("gemini-weekly", weekly.bucketId)
        assertEquals("weekly", weekly.window)
        assertEquals("Weekly Limit Remaining", weekly.label)
        assertEquals(
            java.time.Instant.parse("2026-09-10T09:28:05Z").epochSecond,
            weekly.resetsAt,
        )
    }

    @Test
    fun `an exhausted bucket reads as 0 percent, not as missing (the v279 bug)`() {
        val snapshot = parseGeminiQuotaSummary(load(quotaSummaryBody))!!
        // This is the whole point: the user's weekly Gemini quota was gone and the panel said 100%.
        assertEquals(0.0, snapshot.groups[0].buckets[1].remainingFraction, 1e-9)
        assertEquals(0.0, snapshot.weekly!!.remainingFraction, 1e-9)
        // The other pool is untouched, which is what tells the user they can switch models.
        assertEquals(0.87, snapshot.groups[1].buckets[1].remainingFraction, 1e-9)
    }

    @Test
    fun `the legacy daily and weekly fields are backfilled from the Gemini group`() {
        val snapshot = parseGeminiQuotaSummary(load(quotaSummaryBody))!!
        // `daily` is a historical name that actually holds the five-hour bucket.
        assertEquals(0.42, snapshot.daily!!.remainingFraction, 1e-9)
        assertEquals(0.0, snapshot.weekly!!.remainingFraction, 1e-9)
    }

    @Test
    fun `the Gemini group wins the backfill even when it is not listed first`() {
        val reordered = parseGeminiQuotaSummary(load("""
            {"groups":[
              {"displayName":"Claude and GPT models","buckets":[
                {"bucketId":"3p-weekly","window":"weekly","remainingFraction":1.0}]},
              {"displayName":"Gemini Models","buckets":[
                {"bucketId":"gemini-weekly","window":"weekly","remainingFraction":0.11}]}]}
        """.trimIndent()))!!
        assertEquals(0.11, reordered.weekly!!.remainingFraction, 1e-9)
    }

    @Test
    fun `a bucket with no remainingFraction is dropped rather than assumed full`() {
        // Google has been observed to send resetTime only, with no fraction, once a pool is spent.
        // Treating that as 100% is exactly how the panel ended up lying to the user.
        val snapshot = parseGeminiQuotaSummary(load("""
            {"groups":[{"displayName":"Gemini Models","buckets":[
              {"bucketId":"gemini-5h","window":"5h","resetTime":"2026-09-03T18:00:00Z"},
              {"bucketId":"gemini-weekly","window":"weekly","remainingFraction":0.25}]}]}
        """.trimIndent()))!!
        assertEquals(1, snapshot.groups[0].buckets.size)
        assertEquals("gemini-weekly", snapshot.groups[0].buckets[0].bucketId)
        assertNull(snapshot.daily)
        assertEquals(0.25, snapshot.weekly!!.remainingFraction, 1e-9)
    }

    @Test
    fun `a response without usable buckets returns null so the caller can fall back`() {
        assertNull(parseGeminiQuotaSummary(load("{}")))
        assertNull(parseGeminiQuotaSummary(load("""{"groups":[]}""")))
        assertNull(parseGeminiQuotaSummary(load("""{"groups":[{"buckets":[]}]}""")))
        // Every bucket unreadable is the same as no buckets at all.
        assertNull(
            parseGeminiQuotaSummary(load("""{"groups":[{"buckets":[{"bucketId":"gemini-5h"}]}]}"""))
        )
    }

    @Test
    fun `the window is inferred from the bucket id when the window field is missing`() {
        val snapshot = parseGeminiQuotaSummary(load("""
            {"groups":[{"buckets":[
              {"bucketId":"gemini-5h","remainingFraction":0.5},
              {"bucketId":"gemini-weekly","remainingFraction":0.5}]}]}
        """.trimIndent()))!!
        assertEquals("5h", snapshot.groups[0].buckets[0].window)
        assertEquals("weekly", snapshot.groups[0].buckets[1].window)
        assertNotNull(snapshot.daily)
        assertNotNull(snapshot.weekly)
    }

    @Test
    fun `window identifiers are normalised from both spellings Google uses`() {
        assertEquals("weekly", normalizeGeminiQuotaWindow("weekly"))
        assertEquals("weekly", normalizeGeminiQuotaWindow("gemini-weekly"))
        assertEquals("weekly", normalizeGeminiQuotaWindow("Weekly Limit Remaining"))
        assertEquals("5h", normalizeGeminiQuotaWindow("5h"))
        assertEquals("5h", normalizeGeminiQuotaWindow("3p-5h"))
        assertEquals("5h", normalizeGeminiQuotaWindow("Five Hour Limit Remaining"))
        assertEquals("daily", normalizeGeminiQuotaWindow("daily"))
        // Anything unrecognised is passed through lowercased so the UI can show it verbatim.
        assertEquals("monthly-something", normalizeGeminiQuotaWindow("Monthly-Something"))
    }

    @Test
    fun `fractions outside 0 to 1 are clamped`() {
        val snapshot = parseGeminiQuotaSummary(load("""
            {"groups":[{"buckets":[
              {"bucketId":"gemini-5h","window":"5h","remainingFraction":1.4},
              {"bucketId":"gemini-weekly","window":"weekly","remainingFraction":-0.2}]}]}
        """.trimIndent()))!!
        assertEquals(1.0, snapshot.daily!!.remainingFraction, 1e-9)
        assertEquals(0.0, snapshot.weekly!!.remainingFraction, 1e-9)
    }

    // ---- v286：onboardUser 说「办完了」却不给项目编号（真机故障）----

    @Test
    fun `done 为真但项目编号是空对象时，必须判成「还没拿到」`() {
        // 用户新增第二个账号（免费层）时谷歌返回的原文，一字未改。
        // 旧代码只看 done 就收工，于是这里直接报错给用户；实际上开通是异步的，该继续重发。
        val real = load(
            """
            {"done":true,"response":{
              "@type":"type.googleapis.com/google.internal.cloud.code.v1internal.OnboardUserResponse",
              "cloudaicompanionProject":{}}}
            """.trimIndent()
        )
        assertNull("空对象不是编号，绝不能当成功", readOnboardProjectId(real))
    }

    @Test
    fun `编号的两种真实形态都要认：对象带 id 与裸字符串`() {
        assertEquals(
            "sunny-axle-1b54t",
            readOnboardProjectId(
                load("""{"done":true,"response":{"cloudaicompanionProject":{"id":"sunny-axle-1b54t"}}}""")
            ),
        )
        assertEquals(
            "cogent-snow-4mnnp",
            readOnboardProjectId(
                load("""{"done":true,"response":{"cloudaicompanionProject":"cogent-snow-4mnnp"}}""")
            ),
        )
    }

    @Test
    fun `操作还没完成时读不到编号，但不该当成异常`() {
        assertNull(readOnboardProjectId(load("""{"done":false,"name":"operations/op-42"}""")))
        assertNull(readOnboardProjectId(load("""{"done":true,"response":{}}""")))
        assertNull(readOnboardProjectId(load("{}")))
        assertNull(readOnboardProjectId(null))
    }

    @Test
    fun `空白编号等于没有编号`() {
        assertNull(
            readOnboardProjectId(
                load("""{"done":true,"response":{"cloudaicompanionProject":{"id":"   "}}}""")
            )
        )
        assertNull(
            readOnboardProjectId(load("""{"done":true,"response":{"cloudaicompanionProject":""}}"""))
        )
    }

    @Test
    fun `重试到底仍拿不到编号时，给用户的提示要说清两种情形`() {
        val msg = GeminiAccountRepository.ONBOARD_NO_PROJECT_MESSAGE
        assertTrue("要告诉新账号去官方端激活一次：$msg", msg.contains("Antigravity"))
        assertTrue("要告诉已激活的账号是谷歌侧还没就绪：$msg", msg.contains("过几分钟"))
        assertFalse("不该把内部英文错误名抛给用户：$msg", msg.contains("onboardUser"))
    }

    // ---- v287：账号分类诊断 + 后备路径 ----

    @Test
    fun `被谷歌判成「项目要你自己交」的账号必须识别出来`() {
        // 论坛 179466 的真实 loadCodeAssist 响应：付费订阅从未签发消费者资格，
        // 可用类型只剩 standard-tier 且标着「需自带项目」。
        val load = load(
            """
            {"allowedTiers":[{"id":"standard-tier","userDefinedCloudaicompanionProject":true}],
             "ineligibleTiers":[{"tierId":"free-tier","reasonCode":"UNSUPPORTED_CLIENT"}]}
            """.trimIndent()
        )
        assertTrue(requiresUserDefinedProject(load, "standard-tier"))
    }

    @Test
    fun `健康的免费层账号不该被判成「要你自己交」`() {
        // gemini-cli issue #17948 里健康账号的形态
        val load = load(
            """
            {"currentTier":{"id":"free-tier","isDefault":true},
             "allowedTiers":[{"id":"free-tier","isDefault":true},
                             {"id":"standard-tier","userDefinedCloudaicompanionProject":true}]}
            """.trimIndent()
        )
        assertFalse(requiresUserDefinedProject(load, "free-tier"))
        // 同一份响应下，选中 standard 才算「要你自己交」
        assertTrue(requiresUserDefinedProject(load, "standard-tier"))
    }

    @Test
    fun `没有这个标志时不许猜成「要你自己交」`() {
        assertFalse(requiresUserDefinedProject(load("{}"), "free-tier"))
        assertFalse(
            requiresUserDefinedProject(load("""{"allowedTiers":[{"id":"free-tier"}]}"""), "free-tier")
        )
        assertFalse(
            requiresUserDefinedProject(
                load("""{"allowedTiers":[{"id":"free-tier","userDefinedCloudaicompanionProject":false}]}"""),
                "free-tier",
            )
        )
    }

    @Test
    fun `诊断摘要要带上定位问题真正需要的那几项`() {
        val load = load(
            """
            {"allowedTiers":[{"id":"standard-tier","isDefault":true,"userDefinedCloudaicompanionProject":true}],
             "ineligibleTiers":[{"tierId":"free-tier","reasonCode":"INELIGIBLE_ACCOUNT"}]}
            """.trimIndent()
        )
        val summary = summarizeCodeAssistTiers(load, "standard-tier")
        assertTrue(summary, summary.contains("选用=standard-tier"))
        assertTrue(summary, summary.contains("需自带项目"))
        assertTrue(summary, summary.contains("free-tier→INELIGIBLE_ACCOUNT"))
        assertTrue(summary, summary.contains("当前=无"))
    }

    @Test
    fun `诊断摘要不能夹带邮箱之类的身份信息`() {
        // 这段摘要会原样出现在报错里给用户看、也可能被截图转发，只许带 tier 相关字段
        val load = load("""{"currentTier":{"id":"free-tier"},"userEmail":"someone@example.com"}""")
        val summary = summarizeCodeAssistTiers(load, "free-tier")
        assertFalse("不该泄露身份信息：$summary", summary.contains("example.com"))
        assertTrue(summary, summary.contains("当前=free-tier"))
    }

    @Test
    fun `项目列表的三种键名与两种形状都要认`() {
        assertEquals(
            "proj-a",
            readProjectIdFromProjectList(load("""{"cloudaicompanionProjects":[{"id":"proj-a"}]}""")),
        )
        assertEquals("proj-b", readProjectIdFromProjectList(load("""{"projects":["proj-b"]}""")))
        assertEquals("proj-c", readProjectIdFromProjectList(load("""{"projectIds":["proj-c"]}""")))
        assertEquals("proj-d", readProjectIdFromProjectList(load("""{"projects":{"id":"proj-d"}}""")))
    }

    @Test
    fun `项目列表为空或形状不认识时返回 null，不许瞎猜`() {
        assertNull(readProjectIdFromProjectList(load("{}")))
        assertNull(readProjectIdFromProjectList(load("""{"projects":[]}""")))
        assertNull(readProjectIdFromProjectList(load("""{"projects":[{"name":"没有 id"}]}""")))
        assertNull(readProjectIdFromProjectList(load("""{"projects":["  "]}""")))
        assertNull(readProjectIdFromProjectList(null))
    }

    @Test
    fun `「要你自己交项目」的提示不能再说「过几分钟再试」`() {
        val msg = GeminiAccountRepository.SELF_SERVE_PROJECT_MESSAGE
        assertTrue(msg, msg.contains("重试也没有用"))
        assertFalse("这种情况重试无意义，不能误导用户干等：$msg", msg.contains("过几分钟再试即可"))
    }
}
