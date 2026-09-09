package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers the CCA request post-processing helpers in [GeminiProvider]: Code Assist fronts
 * Anthropic models that reject a thinking budget under 1024, so `raiseThinkingBudgetToClaudeFloor`
 * must run - and be seen by `raiseMaxTokensAboveThinkingBudget` - before the request is sent.
 * Both are file-private top-level functions, so reflection targets the `GeminiProviderKt` facade
 * class Kotlin generates for a file's top-level declarations.
 */
class GeminiProviderRequestTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")

    private fun invokeRaiseThinkingBudget(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseThinkingBudgetToClaudeFloor",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun invokeRaiseMaxTokens(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseMaxTokensAboveThinkingBudget",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun requestWithBudget(budget: Int): JsonObject = buildJsonObject {
        put("generationConfig", buildJsonObject {
            put("thinkingConfig", buildJsonObject {
                put("thinkingBudget", budget)
            })
        })
    }

    private fun budgetOf(request: JsonObject): Int? =
        request["generationConfig"]?.jsonObject
            ?.get("thinkingConfig")?.jsonObject
            ?.get("thinkingBudget")?.jsonPrimitive?.intOrNull

    @Test
    fun `a budget below Claude's floor is raised to 1024`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(1000))
        assertEquals(1024, budgetOf(raised))
    }

    @Test
    fun `a budget of 0 (reasoning off) is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(0))
        assertEquals(0, budgetOf(raised))
    }

    @Test
    fun `a budget already above the floor is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(8000))
        assertEquals(8000, budgetOf(raised))
    }

    @Test
    fun `the clamp runs before the max-tokens raise so the raise sees 1024, not 1000`() {
        val clamped = invokeRaiseThinkingBudget(requestWithBudget(1000))
        val finalRequest = invokeRaiseMaxTokens(clamped)
        val maxTokens = finalRequest["generationConfig"]?.jsonObject
            ?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull
        // 1024 (clamped budget) + THINKING_ANSWER_HEADROOM (8192); if the clamp ran after this
        // raise instead of before it, the result would be 1000 + 8192 = 9192.
        assertEquals(1024 + 8192, maxTokens)
    }

    // ------------------------------------------------------------------------------------------
    // v279：个人 Google 账号走 Antigravity 通道时全部 429 的三处成因，每一处都由下面的断言盯住。
    // 端点分区（daily vs prod）、信封上的 agent 流量标识、以及自报客户端形态。
    // ------------------------------------------------------------------------------------------

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `generate traffic goes to the daily endpoint, not the enterprise-only prod one`() {
        assertEquals(
            "https://daily-cloudcode-pa.googleapis.com",
            GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT,
        )
        // loadCodeAssist deliberately stays on prod: it is the account classification call and
        // answers consumer accounts there. Only the generate path is gated to enterprise.
        assertEquals(
            "https://cloudcode-pa.googleapis.com",
            GeminiAccountRepository.CODE_ASSIST_ENDPOINT,
        )
        assertNotEquals(
            GeminiAccountRepository.CODE_ASSIST_ENDPOINT,
            GeminiAccountRepository.DAILY_CODE_ASSIST_ENDPOINT,
        )
    }

    @Test
    fun `the self-reported client is a shape Antigravity actually ships`() {
        // The old `antigravity/hub/<ver> android/<arch>` was invented here; the backend routes
        // quota on this string, so it has to match a real build.
        assertTrue(ANTIGRAVITY_USER_AGENT.startsWith("antigravity/$ANTIGRAVITY_VERSION "))
        assertFalse(ANTIGRAVITY_USER_AGENT.contains("hub/"))
        assertFalse(ANTIGRAVITY_USER_AGENT.contains("android/"))
    }

    @Test
    fun `a request id is unique per call and carries the agent prefix`() {
        val id = newAntigravityRequestId(nowMillis = 1_700_000_000_000, random = Random(1))
        assertTrue(id.startsWith("agent-1700000000000-"))
        assertEquals(9, id.removePrefix("agent-1700000000000-").length)
        assertTrue(id.removePrefix("agent-1700000000000-").all { it.isLowerCase() || it.isDigit() })
        assertNotEquals(newAntigravityRequestId(), newAntigravityRequestId(nowMillis = 1L))
    }

    @Test
    fun `the session id is stable for the same first user message and differs across chats`() {
        val first = stableSessionId("what is the weather")
        assertEquals(first, stableSessionId("what is the weather"))
        assertNotEquals(first, stableSessionId("something else entirely"))
        // Antigravity's own format: a leading dash then a non-negative 63-bit value.
        assertTrue(first.startsWith("-"))
        assertTrue(first.removePrefix("-").toLong() >= 0L)
    }

    @Test
    fun `the session id comes from the first user message, so later turns keep it`() {
        val opening = userMessage("first question")
        val laterTurn = listOf(
            opening,
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))),
            userMessage("follow-up"),
        )
        val firstTurn = withStableSessionId(buildJsonObject {}, listOf(opening))
        val secondTurn = withStableSessionId(buildJsonObject {}, laterTurn)
        assertEquals(
            firstTurn["sessionId"]?.jsonPrimitive?.contentOrNull,
            secondTurn["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            stableSessionId("first question"),
            firstTurn["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `nothing to derive a session from leaves the request untouched`() {
        val noMessages = withStableSessionId(buildJsonObject { put("contents", "x") }, emptyList())
        assertNull(noMessages["sessionId"])
        val blank = withStableSessionId(buildJsonObject {}, listOf(userMessage("   ")))
        assertNull(blank["sessionId"])
        // A caller that already set one wins - this must never overwrite an explicit id.
        val explicit = withStableSessionId(
            buildJsonObject { put("sessionId", "-42") },
            listOf(userMessage("ignored")),
        )
        assertEquals("-42", explicit["sessionId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a system instruction is tagged with the user role Antigravity expects`() {
        val tagged = withSystemInstructionRole(
            buildJsonObject { put("systemInstruction", buildJsonObject { put("parts", "x") }) }
        )
        assertEquals(
            "user",
            tagged["systemInstruction"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull,
        )
        // An explicit role is left alone, and a request without a system instruction is untouched.
        val explicit = withSystemInstructionRole(
            buildJsonObject { put("systemInstruction", buildJsonObject { put("role", "model") }) }
        )
        assertEquals(
            "model",
            explicit["systemInstruction"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull,
        )
        val none = withSystemInstructionRole(buildJsonObject { put("contents", "x") })
        assertNull(none["systemInstruction"])
    }

    @Test
    fun `the Antigravity shape applies both the session id and the instruction role at once`() {
        val shaped = withAntigravityRequestShape(
            buildJsonObject { put("systemInstruction", buildJsonObject { put("parts", "x") }) },
            listOf(userMessage("hello")),
        )
        assertEquals(
            stableSessionId("hello"),
            shaped["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "user",
            shaped["systemInstruction"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `a bare 429 with no structured fields is called out as account-level`() {
        val bare = invokeQuotaHint(429, buildJsonObject {})
        assertTrue(bare.contains("账号级限流"))
        // Every other status stays silent - the hint must not leak onto unrelated errors.
        assertEquals("", invokeQuotaHint(400, buildJsonObject {}))
        assertEquals("", invokeQuotaHint(null, buildJsonObject {}))
    }

    @Test
    fun `a short retry delay is the only case that says "wait and retry"`() {
        val hint = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("retryDelay", "30s")
        }))
        assertTrue(hint.contains("短时限流"))
        assertTrue(hint.contains("30 秒"))
    }

    @Test
    fun `a week-long retry delay must not be taken at face value (the v279 and v280 bugs)`() {
        // The real 429 the user hit: retryDelay 590255.84s = 163h57m.
        // v279 called it "短时速率限制" and told him to retry shortly.
        // v280 called it "账号配额已用尽，约 6 天 19 小时后重置。现在重试没有用".
        // Both were wrong: he was back the next morning, ~10 hours later, and the quota
        // panel showed 100% the whole time. What actually throttles here is an
        // undocumented short-term limit, while the delay Google returns is the *weekly*
        // bucket's resetTime (gemini-cli #14883 / #13158 / #12940).
        val hint = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("retryDelay", "590255.840167514s")
        }))
        // The number is still surfaced, but labelled as the weekly boundary - not as the wait.
        assertTrue(hint.contains("6 天 19 小时"))
        assertTrue(hint.contains("每周额度"))
        assertTrue(hint.contains("几小时就自动恢复"))
        assertFalse("不能再说成配额用尽、必须等满一周", hint.contains("账号配额已用尽"))
        assertFalse(hint.contains("短时限流"))
        // The way out is a model on a different quota pool, so the hint has to say so.
        assertTrue(hint.contains("Claude"))
    }

    @Test
    fun `a delay inside one day is still reported at face value`() {
        // 十几个小时属于可信量级（5 小时桶真耗尽、或 Google 明确给出的等待），照实说。
        val halfDay = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("retryDelay", "12h")
        }))
        assertTrue(halfDay.contains("账号配额已用尽"))
        assertTrue(halfDay.contains("12 小时"))
        assertFalse(halfDay.contains("每周额度"))

        // 边界：整整一天仍算可信，超过一天才判为「Google 给的数字不可信」。
        assertTrue(
            invokeQuotaHint(429, errorWithDetails(buildJsonObject { put("retryDelay", "86400s") }))
                .contains("账号配额已用尽"),
        )
        assertTrue(
            invokeQuotaHint(429, errorWithDetails(buildJsonObject { put("retryDelay", "86401s") }))
                .contains("每周额度"),
        )
    }

    @Test
    fun `an untrustworthy delay outranks QUOTA_EXHAUSTED, but MODEL_CAPACITY outranks it`() {
        // 真实响应里 QUOTA_EXHAUSTED 常常跟着周桶的 resetTime 一起回来 —— 那时该说的是
        // 「几小时就好」，不是「等一周」。
        val exhaustedWithWeekDelay = invokeQuotaHint(429, errorWithDetails(
            buildJsonObject { put("reason", "QUOTA_EXHAUSTED") },
            buildJsonObject { put("retryDelay", "163h") },
        ))
        assertTrue(exhaustedWithWeekDelay.contains("每周额度"))
        assertFalse(exhaustedWithWeekDelay.contains("现在重试没有用"))

        // 容量不足跟额度无关，是最明确的信号，仍然排在最前面。
        val capacityWithWeekDelay = invokeQuotaHint(429, errorWithDetails(
            buildJsonObject { put("reason", "MODEL_CAPACITY_EXHAUSTED") },
            buildJsonObject { put("retryDelay", "163h") },
        ))
        assertTrue(capacityWithWeekDelay.contains("容量不足"))
        assertFalse(capacityWithWeekDelay.contains("每周额度"))
    }

    @Test
    fun `the reason field wins over the delay length`() {
        // QUOTA_EXHAUSTED with a deceptively short delay is still exhausted quota.
        val exhausted = invokeQuotaHint(429, errorWithDetails(
            buildJsonObject {
                put("reason", "QUOTA_EXHAUSTED")
                put("domain", "cloudcode-pa.googleapis.com")
            },
            buildJsonObject { put("retryDelay", "20s") },
        ))
        assertTrue(exhausted.contains("账号配额已用尽"))
        assertFalse(exhausted.contains("短时限流"))

        val capacity = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("reason", "MODEL_CAPACITY_EXHAUSTED")
        }))
        assertTrue(capacity.contains("容量不足"))
        assertFalse(capacity.contains("账号配额已用尽"))
    }

    @Test
    fun `the delay is also read from quotaResetDelay, including inside metadata`() {
        val topLevel = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("quotaResetDelay", "45s")
        }))
        assertTrue(topLevel.contains("45 秒"))

        val nested = invokeQuotaHint(429, errorWithDetails(buildJsonObject {
            put("metadata", buildJsonObject { put("quotaResetDelay", "2h30m") })
        }))
        assertTrue(nested.contains("账号配额已用尽"))
        assertTrue(nested.contains("2 小时 30 分"))
    }

    @Test
    fun `Google's duration strings are parsed for every unit that shows up in practice`() {
        assertEquals(590255.840167514, parseDurationSeconds("590255.840167514s")!!, 1e-6)
        assertEquals(0.539477544, parseDurationSeconds("539.477544ms")!!, 1e-9)
        assertEquals(600.0, parseDurationSeconds("10m")!!, 1e-9)
        assertEquals(5400.0, parseDurationSeconds("1h30m")!!, 1e-9)
        assertEquals(86400.0, parseDurationSeconds("1d")!!, 1e-9)
        // No unit at all means seconds; blank or unparseable means "no information".
        assertEquals(300.0, parseDurationSeconds("300")!!, 1e-9)
        assertNull(parseDurationSeconds("   "))
        assertNull(parseDurationSeconds("soon"))
    }

    @Test
    fun `the wait is rendered with at most two units`() {
        assertEquals("6 天 19 小时", formatQuotaWait(590255.84))
        assertEquals("7 天", formatQuotaWait(604800.0))
        assertEquals("2 小时 30 分", formatQuotaWait(9000.0))
        assertEquals("5 小时", formatQuotaWait(18000.0))
        assertEquals("3 分", formatQuotaWait(185.0))
        assertEquals("45 秒", formatQuotaWait(45.0))
        assertEquals("0 秒", formatQuotaWait(-1.0))
    }

    private fun errorWithDetails(vararg details: JsonObject): JsonObject = buildJsonObject {
        put("details", kotlinx.serialization.json.buildJsonArray {
            details.forEach { add(it) }
        })
    }

    private fun invokeQuotaHint(code: Int?, error: JsonObject): String {
        val method = geminiProviderKt.getDeclaredMethod(
            "quotaHint",
            Integer::class.java,
            JsonObject::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, code, error) as String
    }
}
