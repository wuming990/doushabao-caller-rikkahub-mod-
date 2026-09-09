package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.util.HttpException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReasoningCompatibilityTest {
    private fun params(
        level: ReasoningLevel = ReasoningLevel.MAX,
        modelId: String = "test",
    ) = TextGenerationParams(
        model = Model(modelId = modelId),
        reasoningLevel = level,
    )

    @Before
    fun setUp() = MaxEffortDenyList.clear()

    @After
    fun tearDown() = MaxEffortDenyList.clear()

    @Test
    fun `MAX is now an official level, not a side flag`() {
        assertTrue(params().isMaxReasoningRequested())
        assertFalse(params(level = ReasoningLevel.XHIGH).isMaxReasoningRequested())
        assertFalse(params(level = ReasoningLevel.HIGH).isMaxReasoningRequested())
        assertEquals("max", ReasoningLevel.MAX.effort)
    }

    @Test
    fun `downgrade lands on the official xhigh level`() {
        assertEquals(ReasoningLevel.XHIGH, params().downgradeMaxToXHigh().reasoningLevel)
    }

    @Test
    fun `downgrade never touches non MAX levels`() {
        listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        ).forEach { level ->
            assertEquals(level, params(level = level).downgradeMaxToXHigh().reasoningLevel)
            assertEquals(level, params(level = level).downgradeIfMaxDenied().reasoningLevel)
        }
    }

    @Test
    fun `a model denied at runtime is downgraded before the request goes out`() {
        val p = params(modelId = "relay/weird-model")
        assertEquals(ReasoningLevel.MAX, p.downgradeIfMaxDenied().reasoningLevel)

        MaxEffortDenyList.deny("relay/weird-model")
        assertEquals(ReasoningLevel.XHIGH, p.downgradeIfMaxDenied().reasoningLevel)
        // 名字里的大小写与分隔符差异不应绕过黑名单
        assertEquals(
            ReasoningLevel.XHIGH,
            params(modelId = "Relay/Weird_Model").downgradeIfMaxDenied().reasoningLevel,
        )
        // 其他模型不受影响
        assertEquals(
            ReasoningLevel.MAX,
            params(modelId = "another-model").downgradeIfMaxDenied().reasoningLevel,
        )
    }

    @Test
    fun `deny list is cleared on demand`() {
        MaxEffortDenyList.deny("a-model")
        assertTrue(MaxEffortDenyList.isDenied("a-model"))
        MaxEffortDenyList.clear()
        assertFalse(MaxEffortDenyList.isDenied("a-model"))
    }

    @Test
    fun `effort rejection is recognised for real world wordings`() {
        listOf(
            "Invalid value for 'reasoning_effort': expected one of low, medium, high",
            "unsupported reasoning_effort: max",
            "400 Bad Request: reasoning.effort must be one of [minimal, low, medium, high]",
            "Unrecognized value 'max' for parameter effort",
            "thinking_level is not supported for this model",
            "output_config.effort: invalid enum value",
        ).forEach { assertTrue(it, isMaxEffortRejection(HttpException(it))) }
    }

    @Test
    fun `unrelated failures never trigger a downgrade retry`() {
        listOf(
            "max_tokens is too large for this model",
            "This model's maximum context length is 128000 tokens",
            "Rate limit reached, please try again later",
            "Insufficient balance",
            "Request timed out",
            "invalid api key",
            "",
        ).forEach { assertFalse(it, isMaxEffortRejection(HttpException(it))) }
    }

    @Test
    fun `rejection detection also looks at the cause`() {
        val wrapped = RuntimeException(
            "request failed",
            HttpException("invalid value for reasoning_effort: max"),
        )
        assertTrue(isMaxEffortRejection(wrapped))
    }

// ================= v219 泛化保底机制测试 =================

    private fun paramsAll(level: ReasoningLevel, modelId: String = "relay/gemini-3-x") =
        TextGenerationParams(model = Model(modelId = modelId), reasoningLevel = level)

    @Test
    fun `降级链从高到低且以关闭思考为终点`() {
        assertEquals(
            listOf(
                ReasoningLevel.MAX,
                ReasoningLevel.XHIGH,
                ReasoningLevel.HIGH,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.LOW,
                ReasoningLevel.AUTO,
                ReasoningLevel.OFF,
            ),
            REASONING_DOWNGRADE_CHAIN,
        )
        assertEquals(ReasoningLevel.OFF, REASONING_DOWNGRADE_CHAIN.last())
    }

    @Test
    fun `请求前黑名单跳级直达可用最高档`() {
        // HIGH 被拒 → 用户选 MAX（高于 HIGH）直接跳到 HIGH 之下第一个可用档 MEDIUM
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.MEDIUM, paramsAll(ReasoningLevel.MAX).withEffectiveReasoningLevel().reasoningLevel)
        // 用户选 XHIGH（仍高于 HIGH）同样跳到 MEDIUM
        assertEquals(ReasoningLevel.MEDIUM, paramsAll(ReasoningLevel.XHIGH).withEffectiveReasoningLevel().reasoningLevel)
        MaxEffortDenyList.clear()
    }

    @Test
    fun `请求档位低于最高被拒档位时乐观保持`() {
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.MEDIUM)
        // 用户选 LOW（低于 MEDIUM）→ 更温和的档位，保持
        assertEquals(ReasoningLevel.LOW, paramsAll(ReasoningLevel.LOW).withEffectiveReasoningLevel().reasoningLevel)
        MaxEffortDenyList.clear()
    }

    @Test
    fun `请求档位本身被拒时降到下一可用档`() {
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.MEDIUM, paramsAll(ReasoningLevel.HIGH).withEffectiveReasoningLevel().reasoningLevel)
        MaxEffortDenyList.clear()
    }

    @Test
    fun `被拒后取链上第一个可用档位`() {
        val p = paramsAll(ReasoningLevel.HIGH)
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.HIGH)
        // HIGH 被拒 → 下一个可用是 MEDIUM（HIGH 之后第一个不在黑名单的）
        assertEquals(ReasoningLevel.MEDIUM, p.nextUsableReasoningLevel(ReasoningLevel.HIGH))
        // 连续两档被拒 → 跳到 LOW
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.MEDIUM)
        assertEquals(ReasoningLevel.LOW, p.nextUsableReasoningLevel(ReasoningLevel.HIGH))
        MaxEffortDenyList.clear()
    }

    @Test
    fun `关闭思考被拒时返回 null 表示链已到底`() {
        val p = paramsAll(ReasoningLevel.OFF)
        MaxEffortDenyList.deny("relay/gemini-3-x", ReasoningLevel.OFF)
        assertEquals(null, p.nextUsableReasoningLevel(ReasoningLevel.OFF))
        MaxEffortDenyList.clear()
    }

    @Test
    fun `旧单参 deny 等价于拒绝 MAX 档`() {
        MaxEffortDenyList.deny("relay/gemini-3-x")
        assertTrue(MaxEffortDenyList.isDenied("relay/gemini-3-x"))
        assertTrue(MaxEffortDenyList.isDenied("relay/gemini-3-x", ReasoningLevel.MAX))
        assertFalse(MaxEffortDenyList.isDenied("relay/gemini-3-x", ReasoningLevel.XHIGH))
        assertTrue(MaxEffortDenyList.isDeniedAny("relay/gemini-3-x"))
        MaxEffortDenyList.clear()
    }

    @Test
    fun `isReasoningRejection 与旧 isMaxEffortRejection 语义一致`() {
        assertTrue(isReasoningRejection(HttpException("Thinking level MINIMAL is not supported for this model")))
        assertFalse(isReasoningRejection(HttpException("rate limit exceeded")))
    }
    @Test
    fun `商汤 deepseek 输出预算兜底只在四条同时成立时生效(v297)`() {
        // 真跑纯函数。四条：商汤 host + deepseek 模型 + 用户没填输出上限 + 思考开着。
        // 少一条都必须返回 null（= 行为与改前一字不差，不给任何渠道加限制）。
        val sensenova = "token.sensenova.cn"
        assertEquals(65_536, sensenovaDeepSeekOutputBudget(sensenova, "deepseek-v4-flash", null, true))
        // 大小写与带前缀写法都要认（模型列表里的 id 形态不统一）
        assertEquals(65_536, sensenovaDeepSeekOutputBudget(sensenova, "DeepSeek-V4-Pro", null, true))
        // 用户填过就尊重用户值
        assertEquals(null, sensenovaDeepSeekOutputBudget(sensenova, "deepseek-v4-flash", 4096, true))
        // 关掉思考时不兜底（正文本来就有整份配额）
        assertEquals(null, sensenovaDeepSeekOutputBudget(sensenova, "deepseek-v4-flash", null, false))
        // 商汤上的其他模型（kimi 等）一律不动
        assertEquals(null, sensenovaDeepSeekOutputBudget(sensenova, "kimi-k3", null, true))
        // 其他渠道一律不动（包括 deepseek 官方站，它自己的默认配额够用）
        assertEquals(null, sensenovaDeepSeekOutputBudget("api.deepseek.com", "deepseek-chat", null, true))
        assertEquals(null, sensenovaDeepSeekOutputBudget("openrouter.ai", "deepseek-v4", null, true))
    }

    @Test
    fun `商汤撤掉表外字段且思考档位不做映射(v297)`() {
        // 源码门禁：v296 给商汤单开了分支、发未列出的思考开关字段（方向错），
        // v297 按用户口径把档位映射也撤掉（真机实测 MAX 比 high 思考更久，钳档会砍掉能力），
        // 所以商汤必须回到 else 兜底 —— 这两条钉住"不许再加回来"。
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val src = java.io.File(
            dir, "ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt"
        ).readText()
        assertFalse("商汤仍单开分派分支（会被映射档位或发表外字段）", src.contains("\"token.sensenova.cn\" ->"))
        assertTrue("else 兜底的档位原样发送被改动", src.contains("put(\"reasoning_effort\", if (level.effort == \"none\") \"low\" else level.effort)"))
        assertTrue("输出预算兜底未接进请求体", src.contains("sensenovaDeepSeekOutputBudget("))
    }
}
