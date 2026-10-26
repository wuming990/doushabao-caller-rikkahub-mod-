package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude 兼容层：请求剥档 + 上游回话判读。
 *
 * 起因（用户真机两张截图，两家不同中转站，同一个 claude-opus-5.5 系模型）：
 * · 一家回 HTTP 400「The request could not be processed. Please check the request
 *   parameters.」；
 * · 一家回 `stop_reason: refusal` + 分类 `reasoning_extraction`，只发「测试」两个字也拦。
 *
 * 前三轮各猜过一次元凶（先猜 `display=summarized`、再猜子代理工具入参
 * `include_reasoning`，后者被「关掉子代理照样被拦」这个对照实验否掉）。
 * **猜错的代价是每轮发一个版本、用户装一次、都没修好。** 所以现在不再猜：
 * 把请求里所有可选字段排成阶梯，被拒就剥一档，哪一档真吐出内容就记哪一档。
 * 本文件里 B / C / D 三个区就是钉这套机制的。
 */
class ClaudeThinkingCompatTest {

    private fun typeOf(fields: ClaudeThinkingFields): String? =
        fields.thinking?.get("type")?.jsonPrimitive?.contentOrNull

    // region A. 型号能力矩阵（逐行抄自官方支持表）

    private fun assertSupport(
        modelId: String,
        adaptive: Boolean,
        extended: Boolean,
        disable: Boolean,
    ) {
        assertEquals(
            "modelId=$modelId",
            ClaudeThinkingSupport(adaptive, extended, disable),
            claudeThinkingSupport(modelId),
        )
    }

    @Test
    fun `opus 5-5 只认 adaptive，enabled 与 disabled 都会 400`() {
        assertSupport("claude-opus-5-5", adaptive = true, extended = false, disable = false)
    }

    @Test
    fun `型号名里的点、大写、日期后缀都不能影响判定`() {
        // 用户两张截图里同时出现过 claude-opus-5-5 与 claude-opus-5.5 两种写法
        assertSupport("claude-opus-5.5", adaptive = true, extended = false, disable = false)
        assertSupport("Claude-Opus-5-5-20260101", adaptive = true, extended = false, disable = false)
        assertSupport("claude-sonnet-5-5", adaptive = true, extended = false, disable = false)
        assertSupport("claude-haiku-5-5", adaptive = true, extended = false, disable = false)
    }

    @Test
    fun `sonnet 5 与 opus 5 同代，也只认 adaptive`() {
        assertSupport("claude-sonnet-5-20260101", adaptive = true, extended = false, disable = false)
        assertSupport("claude-opus-5", adaptive = true, extended = false, disable = false)
    }

    @Test
    fun `fable 与 mythos 也是 adaptive only`() {
        assertSupport("claude-fable-5-1", adaptive = true, extended = false, disable = false)
        assertSupport("claude-mythos-5", adaptive = true, extended = false, disable = false)
    }

    @Test
    fun `4-6 是唯一两种写法都收的一代`() {
        assertSupport("claude-opus-4-6", adaptive = true, extended = true, disable = true)
        assertSupport("claude-sonnet-4-6", adaptive = true, extended = true, disable = true)
    }

    @Test
    fun `4-7 与 4-8 只认 adaptive 但还能关`() {
        assertSupport("claude-opus-4-7", adaptive = true, extended = false, disable = true)
        assertSupport("claude-opus-4-8", adaptive = true, extended = false, disable = true)
    }

    @Test
    fun `4-5 及更早只认老式预算，发 adaptive 必 400`() {
        assertSupport("claude-opus-4-5", adaptive = false, extended = true, disable = true)
        assertSupport("claude-sonnet-4-5-20250929", adaptive = false, extended = true, disable = true)
        assertSupport("claude-opus-4-1", adaptive = false, extended = true, disable = true)
    }

    @Test
    fun `八位日期不能被当成次版本号`() {
        // 第一版没带 (?!\d)，把 claude-sonnet-4-20250514 的日期读成「次版本 20250514」，
        // 于是 4.0 被归进 4.7 那一支、给只认老格式的型号发 adaptive —— 必 400。
        assertSupport("claude-sonnet-4-20250514", adaptive = false, extended = true, disable = true)
        assertSupport("claude-opus-4-20250514", adaptive = false, extended = true, disable = true)
        assertSupport("claude-haiku-4-5-20251001", adaptive = false, extended = true, disable = true)
    }

    @Test
    fun `老命名把版本写在家族名前，不能被判成认不出来`() {
        // 「认不出来」的兜底是发 adaptive，而 3.x 收到 adaptive 必 400
        assertSupport("claude-3-5-sonnet-20241022", adaptive = false, extended = true, disable = true)
        assertSupport("claude-3-opus-20240229", adaptive = false, extended = true, disable = true)
        assertSupport("claude-3-haiku-20240307", adaptive = false, extended = true, disable = true)
    }

    @Test
    fun `认不出来的型号名走保守档：只发 adaptive，绝不发 disabled`() {
        assertSupport("gpt-4o", adaptive = true, extended = false, disable = false)
        assertSupport("claude", adaptive = true, extended = false, disable = false)
        assertSupport("my-claude-alias", adaptive = true, extended = false, disable = false)
    }

    // endregion

    // region B. 剥档阶梯

    private fun fieldsFor(flags: ClaudeRequestFlags, modelId: String = "claude-opus-5-5") =
        buildClaudeThinkingFields(
            style = ClaudeThinkingStyle.AUTO,
            support = claudeThinkingSupport(modelId),
            level = ReasoningLevel.MAX,
            maxTokens = 64_000,
            flags = flags,
        )

    @Test
    fun `阶梯是累积且单调的：后一档不会比前一档更花哨`() {
        val ladder = claudeFlagLadder()
        assertTrue(ladder.size >= 5)
        assertEquals(ClaudeRequestFlags(), ladder.first())
        // 前一档剥掉的东西，后一档必须也剥掉
        for (i in 1 until ladder.size) {
            val prev = ladder[i - 1]
            val cur = ladder[i]
            assertTrue("第 $i 档把 effort 又加回来了", cur.dropEffort || !prev.dropEffort)
            assertTrue("第 $i 档把摘要又加回来了", cur.dropSummary || !prev.dropSummary)
            assertTrue("第 $i 档把思考又加回来了", cur.dropThinking || !prev.dropThinking)
            assertTrue("第 $i 档把缓存又加回来了", cur.dropCacheControl || !prev.dropCacheControl)
            assertTrue("第 $i 档把 top_p 又加回来了", cur.dropTopP || !prev.dropTopP)
            assertTrue("第 $i 档把 temperature 又加回来了", cur.dropTemperature || !prev.dropTemperature)
        }
        // 最后一档必须把所有可剥项都剥干净
        assertTrue(ladder.last().dropThinking && ladder.last().dropCacheControl)
    }

    @Test
    fun `剥思考摘要那一档确实不再发 display`() {
        assertTrue(fieldsFor(ClaudeRequestFlags()).asksNothingSpecial())
        val full = fieldsFor(ClaudeRequestFlags())
        assertEquals("adaptive", typeOf(full))
        assertEquals("summarized", full.thinking?.get("display")?.jsonPrimitive?.content)
        assertEquals("max", full.outputConfig?.get("effort")?.jsonPrimitive?.content)

        val noEffort = fieldsFor(ClaudeRequestFlags(dropEffort = true))
        assertNull(noEffort.outputConfig)
        assertEquals("summarized", noEffort.thinking?.get("display")?.jsonPrimitive?.content)

        val noSummary = fieldsFor(ClaudeRequestFlags(dropEffort = true, dropSummary = true))
        assertNull(noSummary.thinking?.get("display"))
        assertEquals("adaptive", typeOf(noSummary))

        val noThinking = fieldsFor(ClaudeRequestFlags(dropThinking = true))
        assertFalse(noThinking.carriesThinking)
    }

    private fun ClaudeThinkingFields.asksNothingSpecial(): Boolean = thinking != null

    @Test
    fun `本次没带的字段不占档位，已到底的判断才不会被虚档骗过`() {
        // 用户没开提示词缓存、没设 top_p / temperature 时，剥它们的那几档算出来
        // 跟上一档逐字节相同。留着就是白撞，而且 variants.size 会谎报「还有退路」。
        val body = buildJsonObject {
            put("thinking", buildJsonObject {
                put("type", JsonPrimitive("adaptive"))
                put("display", JsonPrimitive("summarized"))
            })
            put("output_config", buildJsonObject { put("effort", JsonPrimitive("max")) })
        }
        val variants = claudeVariantsFor(body, ClaudeThinkingStyle.AUTO)
        assertTrue("应明显少于全 7 档，实际 ${variants.size}", variants.size < claudeFlagLadder().size)
        assertEquals("第一档永远是完整形态", ClaudeRequestFlags(), variants.first())
        // 最后一条剥掉的必须真是本次带了的东西（缓存 / top_p / 采样都不该出现）
        val last = variants.last()
        assertTrue(last.dropThinking)
        assertFalse("本次没带 cache_control，不该占一档", last.dropCacheControl)
        assertFalse("本次没带 top_p，不该占一档", last.dropTopP)
        assertFalse("本次没带 temperature，不该占一档", last.dropTemperature)
    }

    @Test
    fun `字段带齐时阶梯就该长一些，探测不能把该试的档吃掉`() {
        val body = buildJsonObject {
            put("thinking", buildJsonObject {
                put("type", JsonPrimitive("adaptive"))
                put("display", JsonPrimitive("summarized"))
            })
            put("output_config", buildJsonObject { put("effort", JsonPrimitive("max")) })
            put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
            put("top_p", JsonPrimitive(0.9))
            put("temperature", JsonPrimitive(0.7))
        }
        val narrow = claudeVariantsFor(body, ClaudeThinkingStyle.AUTO, supportsExtended = false)
        assertEquals("型号不支持老式时不该有「改发老式」这一档", 7, narrow.size)
        assertTrue(narrow.last().dropTemperature)
        assertFalse(narrow.last().useLegacyBudget)

        // 两种写法都收的型号（4.6 系）：必须多出一档「改发老式」，
        // 否则上游不认 adaptive 时我们只能整个关掉思考 —— 那是比 v1012 少做。
        val wide = claudeVariantsFor(body, ClaudeThinkingStyle.AUTO, supportsExtended = true)
        assertEquals("支持老式的型号要多一档", 8, wide.size)
        assertEquals(
            "「改发老式」必须排在「整个不发思考」之前（先试代价小的）",
            3,
            wide.indexOfFirst { it.useLegacyBudget },
        )
        val legacy = wide.first { it.useLegacyBudget }
        assertTrue(legacy.thinkingStillAdaptiveOrLegacy())
    }

    /** 「改发老式」那一档必须还在发思考，只是换成 enabled + 预算。 */
    private fun ClaudeRequestFlags.thinkingStillAdaptiveOrLegacy(): Boolean =
        !dropThinking && useLegacyBudget

    @Test
    fun `手动档恒为单格阶梯，界面那句「不参与自动剥档」由结构保证`() {
        // 不靠调用方记得判断 style —— 漏判一处就是界面说的和实际做的不一致。
        // 手动档：哪怕字段带得再齐，也只许一档 —— 用户钉死了格式，
        // 不许我们顺手把提示词缓存和采样参数也剥掉。
        val body = buildJsonObject {
            put("thinking", buildJsonObject {
                put("type", JsonPrimitive("adaptive"))
                put("display", JsonPrimitive("summarized"))
            })
            put("output_config", buildJsonObject { put("effort", JsonPrimitive("max")) })
            put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
            put("top_p", JsonPrimitive(0.9))
            put("temperature", JsonPrimitive(0.7))
        }
        listOf(
            ClaudeThinkingStyle.ADAPTIVE,
            ClaudeThinkingStyle.LEGACY,
            ClaudeThinkingStyle.OMIT,
        ).forEach { style ->
            assertEquals("$style 不该有第二档", 1, claudeVariantsFor(body, style).size)
        }
        assertFalse(buildClaudeThinkingFields(
            ClaudeThinkingStyle.OMIT, claudeThinkingSupport("claude-opus-5-5"),
            ReasoningLevel.MAX, 64_000, ClaudeRequestFlags(),
        ).carriesThinking)
    }

    @Test
    fun `新模型关思考时省略字段，而不是发必被拒的 disabled`() {
        // 官方：Opus 5.5「Requests that set thinking:{type:"disabled"} return a 400
        // error at every effort level」。旧代码正是在这里发 disabled。
        val off = buildClaudeThinkingFields(
            ClaudeThinkingStyle.AUTO, claudeThinkingSupport("claude-opus-5-5"),
            ReasoningLevel.OFF, 64_000, ClaudeRequestFlags(),
        )
        assertNull(off.thinking)
        // 老型号本来就支持关闭，照发 disabled
        assertEquals(
            "disabled",
            typeOf(buildClaudeThinkingFields(
                ClaudeThinkingStyle.AUTO, claudeThinkingSupport("claude-opus-4-5"),
                ReasoningLevel.OFF, 64_000, ClaudeRequestFlags(),
            )),
        )
        // 4.7 支持关闭，也不必省略
        assertEquals(
            "disabled",
            typeOf(buildClaudeThinkingFields(
                ClaudeThinkingStyle.AUTO, claudeThinkingSupport("claude-opus-4-7"),
                ReasoningLevel.OFF, 64_000, ClaudeRequestFlags(),
            )),
        )
    }

    @Test
    fun `老式预算必须落在 1024 到 max_tokens 之间，否则这个逃生口自己就是坏的`() {
        fun legacy(level: ReasoningLevel, maxTokens: Int) = buildClaudeThinkingFields(
            ClaudeThinkingStyle.AUTO, claudeThinkingSupport("claude-sonnet-4-5"),
            level, maxTokens, ClaudeRequestFlags(),
        )
        // LOW 的映射值是 1000，低于官方下限 1024
        assertEquals(1_024, legacy(ReasoningLevel.LOW, 64_000).thinking?.get("budget_tokens")?.jsonPrimitive?.int)
        // 用户把最大Token数调小后，预算夹到 max_tokens - 1
        assertEquals(1_999, legacy(ReasoningLevel.HIGH, 2_000).thinking?.get("budget_tokens")?.jsonPrimitive?.int)
        // 夹不出合法区间就宁可不发
        assertFalse(legacy(ReasoningLevel.HIGH, 1_024).carriesThinking)
    }

    @Test
    fun `effort 绝不塞进 thinking 里，官方说那样会 ValidationException`() {
        val f = fieldsFor(ClaudeRequestFlags())
        assertNull(f.thinking?.get("effort"))
        assertEquals("max", f.outputConfig?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `AUTO 档不发 effort，官方没有 auto 这个 effort 值`() {
        val f = buildClaudeThinkingFields(
            ClaudeThinkingStyle.AUTO, claudeThinkingSupport("claude-opus-5-5"),
            ReasoningLevel.AUTO, 64_000, ClaudeRequestFlags(),
        )
        assertEquals("adaptive", typeOf(f))
        assertNull(f.outputConfig)
    }

    // endregion

    // region C. 上游回话判读

    @Test
    fun `真机截图那句网关 400 要认成参数拒绝，有状态码与没有都要认`() {
        val text = "The request could not be processed. Please check the request parameters."
        assertTrue(isClaudeParameterRejection(400, text))
        // 网关把它包进 HTTP 200 的 SSE error 帧时没有状态码可依据
        assertTrue(isClaudeParameterRejection(null, text))
    }

    @Test
    fun `官方 400 的几种典型写法都要认`() {
        assertTrue(isClaudeParameterRejection(400, "Unknown name \"cache_control\" at 'request': Cannot find field."))
        assertTrue(isClaudeParameterRejection(400, "\"thinking.type.enabled\" is not supported for this model"))
        assertTrue(isClaudeParameterRejection(null, "invalid_request_error: something"))
    }

    @Test
    fun `换密钥与续跑能解决的错误，不该在这里抢着剥字段`() {
        assertFalse(isClaudeParameterRejection(429, "rate limit exceeded"))
        assertFalse(isClaudeParameterRejection(401, "invalid api key"))
        assertFalse(isClaudeParameterRejection(402, "insufficient balance"))
        assertFalse(isClaudeParameterRejection(500, "api_error internal error"))
        assertFalse(isClaudeParameterRejection(529, "overloaded_error"))
        assertFalse(isClaudeParameterRejection(null, "Connection reset by peer"))
        assertFalse(isClaudeParameterRejection(null, null))
        assertFalse(isClaudeParameterRejection(null, "   "))
    }

    @Test
    fun `非流式的错误文本能反解出状态码，但只认我们自己拼的前缀`() {
        assertEquals(400, claudeStatusCodeFromMessage("Failed to get response: 400 {\"type\":\"error\"}"))
        assertEquals(429, claudeStatusCodeFromMessage("Failed to get response: 429 slow down"))
        // 正文里随便一个三位数不能被读成状态码
        assertNull(claudeStatusCodeFromMessage("upstream request id 40021 failed"))
        assertNull(claudeStatusCodeFromMessage(null))
    }

    // endregion

    // region D. 诊断读数与文案

    @Test
    fun `读数说清试了几档、最后一档剥掉了什么`() {
        val line = ClaudeRejectDiagnostics(
            httpStatus = 400,
            variantsTried = 5,
            variantTotal = 7,
            lastVariantLabel = ClaudeRequestFlags(
                dropEffort = true, dropSummary = true, dropThinking = true, dropCacheControl = true,
            ).label(),
            sentOptionalFields = listOf("top_p", "max_tokens=64000"),
            upstreamExcerpt = "check the request parameters",
        ).readableLine()
        assertTrue(line, line.contains("已试 5/7 档"))
        assertTrue(line, line.contains("已剥：思考强度、思考摘要、思考参数、提示词缓存"))
        assertTrue(line, line.contains("HTTP 400"))
        assertTrue(line, line.contains("max_tokens=64000"))
        assertEquals("完整形态", ClaudeRequestFlags().label())
    }

    @Test
    fun `剥到底的异常必须说清仍然包含什么，别让人以为请求空了`() {
        val message = ClaudeDegradeExhaustedException(
            diagnostics = ClaudeRejectDiagnostics(400, 7, 7, "完整形态", emptyList(), null),
            upstreamMessage = "The request could not be processed.",
        ).message.orEmpty()
        assertTrue(message, message.contains("仍包含助手提示词与工具说明书原文"))
        // 「最简形态」单独出现会被下一个人读成「请求已经空了」，从而错误地排除文本嫌疑
        assertTrue(message, message.contains("The request could not be processed"))
    }

    @Test
    fun `卡片第一行必须是人话，英文分类名只进可复制区`() {
        // 用户在这一轮里说过好几次「我看不懂」，而 v1012 卡片上那行
        // 「分类 reasoning_extraction」对他没有任何信息量。
        val first = ClaudeRefusalException("reasoning_extraction", null).message.orEmpty()
            .lineSequence().first()
        assertTrue("第一行得说人话：$first", first.contains("认为我们在索要它的内部思考"))
        assertFalse("第一行不许有英文名：$first", first.contains("reasoning_extraction"))
        listOf("cyber", "bio", "frontier_llm", "general_harms", null, "未知分类X").forEach {
            assertTrue("$it", claudeRefusalCategoryInChinese(it).isNotBlank())
        }
    }

    @Test
    fun `文案只陈述真发生过的事`() {
        // v1012 那句「已试过其它密钥仍被拒」对单密钥用户是假的 —— 他一把都没换过。
        val single = ClaudeRefusalException("cyber", null).message.orEmpty()
        assertTrue(single, single.contains("只有一把密钥"))
        assertFalse(single, single.contains("换过其它密钥仍然被拒"))
        val multi = ClaudeRefusalException("cyber", null, triedOtherKeys = true).message.orEmpty()
        assertTrue(multi, multi.contains("换过其它密钥仍然被拒"))
        assertFalse(multi, multi.contains("只有一把密钥"))
        assertFalse(ClaudeRefusalException(null, null).message.orEmpty().contains("null"))
    }

    @Test
    fun `计费提醒只出现在官方真的计费的那三类，且不许写成不让人重试`() {
        // 官方 2026-09-24 变更：bio / frontier_llm / reasoning_extraction 产出前被拒也计费。
        // 用户口径很明确：「我有所谓的重复扣费需求我不会自己关自动续跑么」——
        // 所以这里只允许「说明事实」，不允许出现「所以没有重发 / 不再重试」这类替用户做的决定。
        listOf("bio", "frontier_llm", "reasoning_extraction").forEach {
            val msg = ClaudeRefusalException(it, null).message.orEmpty()
            assertTrue("$it 该提示计费", msg.contains("照常计费"))
            assertFalse("$it 不许替用户关掉重试", msg.contains("所以没有重发"))
        }
        listOf("cyber", "general_harms", null).forEach {
            assertFalse(
                "$it 不在计费名单里，不该吓唬用户",
                ClaudeRefusalException(it, null).message.orEmpty().contains("照常计费"),
            )
        }
    }

    private fun jsonObjectOf(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    @Test
    fun `stop_details 的分类与说明要拼出来，两个都为 null 时不能崩`() {
        assertEquals(
            "分类 cyber，declined",
            claudeRefusalDetail(jsonObjectOf("category" to "cyber", "explanation" to "declined")),
        )
        assertEquals("说明只有一句", claudeRefusalDetail(jsonObjectOf("explanation" to "说明只有一句")))
        assertNull(claudeRefusalDetail(jsonObjectOf("type" to "refusal")))
        assertNull(claudeRefusalDetail(jsonObjectOf("category" to "", "explanation" to "  ")))
        assertNull(claudeRefusalDetail(null))
        assertNull(
            claudeRefusalDetail(
                buildJsonObject {
                    put("category", JsonNull)
                    put("explanation", JsonNull)
                },
            ),
        )
    }

    // endregion

    // region E. 解码器取证

    private fun decode(events: List<SseEvent>): ClaudeStreamDecoder {
        val decoder = ClaudeStreamDecoder()
        events.forEach { decoder.accept(it) }
        return decoder
    }

    @Test
    fun `refusal 的 stop_details 不再被丢掉，分类单独留一份给程序判读`() {
        val decoder = decode(
            listOf(
                SseEvent(
                    event = "message_delta",
                    data = """{"type":"message_delta","delta":{"stop_reason":"refusal",""" +
                        """"stop_details":{"type":"refusal","category":"reasoning_extraction",""" +
                        """"explanation":"declined"}}}""",
                ),
            ),
        )
        assertEquals("refusal", decoder.finishReason)
        assertEquals("reasoning_extraction", decoder.refusalCategory)
        assertEquals("分类 reasoning_extraction，declined", decoder.refusalDetail)
    }

    @Test
    fun `上游把 stop_details 显式发成 null 时不能崩`() {
        // 真实轨迹实锤：正常收尾的 message_delta 里带 "stop_details":null（不是缺字段）。
        // 用 ?.jsonObject 取它会对 JsonNull 抛 IllegalArgumentException，把整条流打死 ——
        // 是 ai 模块的轨迹回放测试抓到的，这条用例就是那次翻车的固化。
        val decoder = decode(
            listOf(
                SseEvent(
                    event = "message_delta",
                    data = """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_details":null}}""",
                ),
            ),
        )
        assertEquals("end_turn", decoder.finishReason)
        assertNull(decoder.refusalDetail)
        assertNull(decoder.refusalCategory)
    }

    @Test
    fun `category 为 null 的拒答不能崩，也不能被当成有分类`() {
        val decoder = decode(
            listOf(
                SseEvent(
                    event = "message_delta",
                    data = """{"type":"message_delta","delta":{"stop_reason":"refusal",""" +
                        """"stop_details":{"type":"refusal","category":null,"explanation":null}}}""",
                ),
            ),
        )
        assertEquals("refusal", decoder.finishReason)
        assertNull(decoder.refusalCategory)
        assertNull(decoder.refusalDetail)
    }

    @Test
    fun `收尾帧与计量帧不算内容，零产出时依然允许换密钥重发`() {
        assertFalse(StreamChunk.Finish(finishReason = "refusal").isClaudeVisibleContent())
        assertFalse(StreamChunk.Usage(me.rerere.ai.core.TokenUsage(promptTokens = 1)).isClaudeVisibleContent())
        assertTrue(StreamChunk.TextDelta("id", "你好").isClaudeVisibleContent())
        assertTrue(StreamChunk.ToolCallStart("id", "tool").isClaudeVisibleContent())
        assertTrue(StreamChunk.ReasoningStart("id").isClaudeVisibleContent())
    }

    // endregion

    // region F. 接线证明 + 结构性红线

    private fun requestBody(
        modelId: String,
        level: ReasoningLevel,
        flags: ClaudeRequestFlags = ClaudeRequestFlags(),
        style: ClaudeThinkingStyle = ClaudeThinkingStyle.AUTO,
        promptCaching: Boolean = false,
        maxTokens: Int? = null,
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!,
            ClaudeRequestFlags::class.java,
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
            reasoningLevel = level,
            maxTokens = maxTokens,
            topP = 0.9f,
            temperature = 0.7f,
        )
        return method.invoke(
            ClaudeProvider(OkHttpClient()),
            ProviderSetting.Claude(thinkingCompat = style, promptCaching = promptCaching),
            listOf(UIMessage.user("测试")),
            params,
            false,
            flags,
        ) as JsonObject
    }

    @Test
    fun `请求体接线：新模型关思考时整个 thinking 键都不出现`() {
        assertNull(requestBody("claude-opus-5.5", ReasoningLevel.OFF)["thinking"])
    }

    @Test
    fun `请求体接线：老模型开思考时发的是 enabled 加预算`() {
        val body = requestBody("claude-sonnet-4-5", ReasoningLevel.HIGH)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body["output_config"])
    }

    @Test
    fun `请求体接线：剥缓存那一档确实把顶层 cache_control 拿掉了`() {
        // 顶层 cache_control 是本次最可疑的字段之一：Anthropic 的 Messages API 没有这个
        // 顶层字段（缓存断点挂在内容块上），严格上游会当成未知字段 400。
        val on = ClaudeRequestFlags()
        val off = ClaudeRequestFlags(
            dropEffort = true, dropSummary = true, dropThinking = true, dropCacheControl = true,
        )
        assertTrue(requestBody("claude-opus-5-5", ReasoningLevel.HIGH, on, promptCaching = true)["cache_control"] != null)
        assertNull(requestBody("claude-opus-5-5", ReasoningLevel.HIGH, off, promptCaching = true)["cache_control"])
    }

    @Test
    fun `请求体接线：top_p 与 temperature 都能剥`() {
        // 前提要摆对：开了思考时官方不允许再传 temperature，所以我们的代码本来就
        // 不发它（params.reasoningLevel.isEnabled 那道判断）。用 HIGH 档测 temperature
        // 的剥除，测的其实是一个压根不存在的字段。
        val topP = requestBody("claude-opus-5-5", ReasoningLevel.HIGH)
        assertTrue("top_p 该带上", topP["top_p"] != null)
        assertNull("开思考时本来就不该发 temperature", topP["temperature"])
        val noTopP = requestBody(
            "claude-opus-5-5", ReasoningLevel.HIGH,
            ClaudeRequestFlags(dropTopP = true),
        )
        assertNull(noTopP["top_p"])

        // 关掉思考才会发 temperature，这时才谈得上剥它
        val withTemp = requestBody("claude-opus-5-5", ReasoningLevel.OFF)
        assertTrue("关思考后 temperature 该带上", withTemp["temperature"] != null)
        val noTemp = requestBody(
            "claude-opus-5-5", ReasoningLevel.OFF,
            ClaudeRequestFlags(dropTemperature = true),
        )
        assertNull(noTemp["temperature"])
    }

    @Test
    fun `max_tokens 语义不许动（用户选项 c，v1011 定，v1012 继续有效）`() {
        val body = requestBody("claude-opus-5-5", ReasoningLevel.HIGH, maxTokens = null)
        assertEquals(64_000, body["max_tokens"]?.jsonPrimitive?.int)
        val given = requestBody("claude-opus-5-5", ReasoningLevel.HIGH, maxTokens = 1234)
        assertEquals(1_234, given["max_tokens"]?.jsonPrimitive?.int)
    }

    @Test
    fun `剥档绝不能动到必需字段：model、messages、max_tokens 每一档都得在`() {
        // 这三样缺一个就是官方 400 名单里的「缺少必填参数」，剥出来等于把请求弄坏。
        for (flags in claudeFlagLadder()) {
            val body = requestBody("claude-opus-5-5", ReasoningLevel.HIGH, flags)
            assertTrue("第 $flags 档丢了 model", body["model"] != null)
            assertTrue("第 $flags 档丢了 messages", body["messages"] != null)
            assertTrue("第 $flags 档丢了 max_tokens", body["max_tokens"] != null)
            assertTrue("第 $flags 档丢了 stream", body["stream"] != null)
        }
    }

    @Test
    fun `剥档只允许动这几个键，其余字段每一档都逐字节相同`() {
        val mutable = setOf("thinking", "output_config", "cache_control", "top_p", "temperature")
        val base = requestBody("claude-opus-5-5", ReasoningLevel.MAX, ClaudeRequestFlags(), promptCaching = true)
        for (flags in claudeFlagLadder()) {
            val body = requestBody("claude-opus-5-5", ReasoningLevel.MAX, flags, promptCaching = true)
            val others = (body.keys + base.keys).distinct() - mutable
            assertTrue(
                "$flags 这档动了别的键：$others",
                others.all { body[it] == base[it] },
            )
        }
    }

    // endregion
}
