package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1013 审查位抓到的一批「读数会说谎 / 脱敏会漏 / 判据会误伤」问题的**回归钉**。
 *
 * 这一轮两位独立审查位都判 fail，共同点很集中：剥档机制本体是对的，坏在
 * 「给用户看的那张卡片说的不是真话」和「把不该算参数拒绝的东西当成参数拒绝」。
 * 本轮的验收判据本来就不是「一定成功」，而是「要么出正文，要么出一张读数正确的报错卡」——
 * 所以读数说谎等同于门禁失败，必须有用例钉着，不能只靠注释提醒。
 */
class ClaudeLadderAuditV1013Test {

    private val provider = ClaudeProvider(OkHttpClient())

    private fun buildRequest(
        setting: ProviderSetting.Claude,
        params: TextGenerationParams,
        flags: ClaudeRequestFlags,
    ): kotlinx.serialization.json.JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!,
            ClaudeRequestFlags::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider, setting, listOf(UIMessage.system("sys"), UIMessage.user("测试")), params, false, flags,
        ) as kotlinx.serialization.json.JsonObject
    }

    private fun reasoningParams(modelId: String) = TextGenerationParams(
        model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
        reasoningLevel = ReasoningLevel.HIGH,
        maxTokens = 16_000,
    )

    // ── 1. 「改发老式」那一档：以前只在 flag 上自证，没有一条用例碰过真实请求体 ──

    @Test
    fun `改发老式那一档真的把 adaptive 换成了 enabled 加预算`() {
        val setting = ProviderSetting.Claude()
        val params = reasoningParams("claude-opus-4-6")
        val base = buildRequest(setting, params, ClaudeRequestFlags())
        assertEquals(
            "完整形态应当发新式 adaptive",
            "adaptive",
            base["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )

        val legacyTier = claudeVariantsFor(base, ClaudeThinkingStyle.AUTO, supportsExtended = true)
            .first { it.useLegacyBudget }
        val body = buildRequest(setting, params, legacyTier)
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        val budget = thinking["budget_tokens"]!!.jsonPrimitive.int
        assertTrue("预算不得低于 1024，实测 $budget", budget >= 1024)
        assertTrue("预算必须小于 max_tokens，实测 $budget", budget < 16_000)
        assertNull(
            "这一档同时剥掉了思考强度，不该再带 output_config",
            body["output_config"],
        )
    }

    // ── 2. 读数诚实性 ──

    @Test
    fun `最后一档已经不发思考时，读数不许再声称改发老式预算`() {
        // buildClaudeThinkingFields 里 dropThinking 短路在前：最后一档一个 thinking 字段都不发。
        // 以前 label() 会说「已剥：…思考参数…思考改发老式预算」—— 同一行自相矛盾。
        val last = claudeFlagLadder().last()
        assertTrue("阶梯末档应当两者同时为真", last.dropThinking && last.useLegacyBudget)
        val label = last.label()
        assertFalse("读数说谎：$label", label.contains("老式"))
        assertTrue(label, label.contains("思考参数"))
        // 而「还在发思考」的那一档必须仍然报出来，不然又变成少报
        val mid = claudeFlagLadder().first { it.useLegacyBudget && !it.dropThinking }
        assertTrue(mid.label(), mid.label().contains("思考改发老式预算"))
    }

    @Test
    fun `诊断里的 max_tokens 必须是真值而不是字面量花括号`() {
        // 源码里曾经写成 "${'$'}{it…}"，Kotlin 会把 ${'$'} 当一个真美元符、后面的 {} 当普通文字，
        // 于是卡片上印的是模板本身。审查位用字节级证据确认过，这里是那条的回归钉。
        val body = buildRequest(ProviderSetting.Claude(), reasoningParams("claude-opus-5-5"), ClaudeRequestFlags())
        val fields = provider.claudeOptionalFieldsIn(body)
        assertTrue("实测=$fields", fields.contains("max_tokens=16000"))
        assertFalse(fields.joinToString(), fields.any { it.contains("{it.jsonPrimitive") })
    }

    @Test
    fun `起步档不为零时已试档数不能从 1 冒充`() {
        // variantStartIndex / 撞底下限都可能让起点不是第 0 档；写死 1 会把没试过的档位说成试过。
        val ladder = claudeFlagLadder()
        val start = ladder[3]
        val diag = ClaudeRejectDiagnostics(
            httpStatus = 400, variantsTried = 4, variantTotal = ladder.size,
            lastVariantLabel = start.label(), sentOptionalFields = listOf("max_tokens=64000"),
            upstreamExcerpt = null,
        )
        assertFalse("还没到底就不许说到底", diag.fullyStripped)
        val line = diag.readableLine()
        assertTrue("已试档数要按真实起点算：$line", line.contains("已试 4/${ladder.size} 档"))
    }

    // ── 3. 脱敏闸 ──

    @Test
    fun `上游回显请求内容时 system 与 tools 里的隐私也不能进可复制区`() {
        // 门槛要说清：值匹配只认长度 >= 12 的字符串值（见 LEAK_MIN_VALUE）。这不是偷工 ——
        // 助手提示词、记忆正文、工具说明书本来就都是长文本；而 "user" "adaptive" "web_search"
        // 这类脚手架词一旦参与匹配，会把「Unknown name \"cache_control\"…」这类**最有用**的
        // 诊断整段抹成占位符。下面三条都用真实长度，另有一条反向钉住「不误伤」。
        val body = buildJsonObject {
            put("system", buildJsonArray {
                add(buildJsonObject { put("text", "这是助手系统提示词的正文，里面写了工作区路径与操作规矩") })
            })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "这是长期记忆正文，记着用户的偏好与私人事项") })
            })
            put("tools", buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "read_user_private_notes_tool")
                        put("description", "这个工具会读取用户的私人笔记全文，属于最敏感的内容")
                    },
                )
            })
        }
        // 回显片段必须是请求体里**逐字存在**的子串，否则测不出这道闸（上一版这么写会假通过）
        listOf(
            "\"text\":\"这是助手系统提示词的正文，里面写了工作区路径与操作规矩\"",
            "\"content\":\"这是长期记忆正文，记着用户的偏好与私人事项\"",
            "\"description\":\"这个工具会读取用户的私人笔记全文，属于最敏感的内容\"",
        ).forEach { frag ->
            val echoed = "HTTP 400 Bad Request: your request was $frag (truncated)"
            val out = sanitizeClaudeUpstreamExcerpt(body, echoed).orEmpty()
            assertFalse("泄露了「$frag」：$out", out.contains(frag))
            assertTrue("应当换成占位符：$out", out.contains("已整段隐去"))
        }
        // 没回显时必须原样保留，否则报错卡就空了、用户什么上游信息都拿不到
        val benign = "Unknown name \"prompt_cache_key\" at 'request': Cannot find field."
        assertEquals(benign, sanitizeClaudeUpstreamExcerpt(body, benign))
        assertNull(sanitizeClaudeUpstreamExcerpt(body, "   "))
        assertNull(sanitizeClaudeUpstreamExcerpt(body, null))
        // 拿不到请求体时无从比对，原样给出（调用方总是带着 body，这里只是不把卡片弄空）
        assertEquals("仍然给出上游原文", sanitizeClaudeUpstreamExcerpt(null, "仍然给出上游原文"))
    }

    // ── 4. 判据别把不该剥的当成「参数被拒」 ──

    @Test
    fun `长度与额度类 400 不触发剥档，真机那句网关文案仍然要认`() {
        // 剥掉 top_p / 缓存断点改不了「太长」，白烧一整趟阶梯的真实请求（产出前被拒也计费）。
        listOf(
            "prompt is too long: 210000 tokens > 200000 maximum",
            "input length and max_tokens exceed context limit: 200000",
            "requested tokens exceed the model context window",
        ).forEach { text ->
            assertFalse("长度类不该触发剥档：$text", isClaudeParameterRejection(400, text))
        }
        // 但真机截图里那句必须继续认出来，否则本轮的降档就整个失效
        assertTrue(
            isClaudeParameterRejection(400, "The request could not be processed. Please check the request parameters."),
        )
        assertTrue(
            isClaudeParameterRejection(null, "Unknown name \"cache_control\" at 'request': Cannot find field."),
        )
    }

    // ── 5. 卡片文案只在真的发生过时才那么说 ──

    @Test
    fun `只有 1 档时报错卡不许宣称已经剥到只剩必需字段`() {
        val one = ClaudeRejectDiagnostics(
            httpStatus = 400, variantsTried = 1, variantTotal = 1,
            lastVariantLabel = "完整形态", sentOptionalFields = listOf("max_tokens=64000"),
            upstreamExcerpt = "check the request parameters",
        )
        val msg = ClaudeDegradeExhaustedException(one, one.upstreamExcerpt).message.orEmpty()
        assertFalse("没剥过却宣称剥过：$msg", msg.contains("已经把可选字段逐个剥到只剩必需字段"))
        assertTrue(msg, msg.contains("本来就没有可剥的可选字段"))
        // 「仍包含助手提示词与工具说明书原文」这句不能省：否则会被读成「请求已经空了」
        assertTrue(msg, msg.contains("仍包含助手提示词与工具说明书原文"))

        val many = ClaudeRejectDiagnostics(400, 7, 7, "已剥：思考参数", emptyList(), null)
        assertTrue(
            ClaudeDegradeExhaustedException(many, null).message.orEmpty()
                .contains("已经把可选字段逐个剥到只剩必需字段"),
        )
    }

    @Test
    fun `内容类拒答没剥过字段时文案不能说成剥过`() {
        val diag = ClaudeRejectDiagnostics(
            httpStatus = null, variantsTried = 1, variantTotal = 3,
            lastVariantLabel = "完整形态", sentOptionalFields = listOf("top_p"),
            upstreamExcerpt = null,
        )
        val msg = ClaudeRefusalException("cyber", null, diagnostics = diag).message.orEmpty()
        assertFalse("剥档没发生过却宣称剥过：$msg", msg.contains("可选字段已逐个剥到只剩必需字段仍被拒"))
        assertTrue(msg, msg.contains("本次没有剥过可选字段"))
        // 措辞红线：不许出现「替用户决定不重试」的说法
        listOf("所以没有重发。", "不再重试").forEach { banned ->
            assertFalse(banned, msg.contains(banned))
        }
    }

    @Test
    fun `网关点名工具名时原文要留着，同样的字符串出现在消息里仍要隐去`() {
        // 三审 P1：标识符跳过只对 system / tools 生效。Anthropic 自己就有
        // 「Invalid tools: my_tool」这种点名报错，抹掉就丢了唯一可操作的诊断；
        // 而 messages 里出现的同名内容可能是用户数据，那边不跳过。
        val toolsBody = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", "read_user_private_notes_tool") })
            })
        }
        val namesTool = "You have entered tool names that are invalid. Invalid tools: read_user_private_notes_tool"
        assertEquals(namesTool, sanitizeClaudeUpstreamExcerpt(toolsBody, namesTool))

        val messagesBody = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("name", "read_user_private_notes_tool") })
            })
        }
        val echoesMessage = "HTTP 400: your request was \"name\":\"read_user_private_notes_tool\" done"
        assertTrue(
            "messages 里的 name 不享受标识符豁免",
            sanitizeClaudeUpstreamExcerpt(messagesBody, echoesMessage).orEmpty().contains("已整段隐去"),
        )
    }

    @Test
    fun `中文被转义成 uXXXX 回显时也要隐去`() {
        // 三审 P1：fastjson / Jackson 那一类网关会把非 ASCII 转义后回显。
        // 不先还原就漏判成「没泄露」，而且是闸看起来在正常工作的那种漏。
        val secret = "这是助手系统提示词的正文，里面写了工作区路径与操作规矩"
        val body = buildJsonObject {
            put("system", buildJsonArray { add(buildJsonObject { put("text", secret) }) })
        }
        val escaped = secret.map {
            if (it.code < 0x80) it.toString() else "\\u%04x".format(it.code)
        }.joinToString("")
        assertTrue("转义回显必须被隐去", escaped.startsWith("\\u"))
        val out = sanitizeClaudeUpstreamExcerpt(body, "HTTP 400: your request was $escaped").orEmpty()
        assertTrue(out, out.contains("已整段隐去"))
        assertFalse(out, out.contains(secret))
    }

    @Test
    fun `只有拒答分类与参数拒绝才值得换档重发，其它错误一律不剥`() {
        // 三审 P1：非流式以前把 looksRejected 算出来却没当闸门，429 / 500 / 断网 /
        // 自家解坏响应体都会逐档重烧一遍阶梯（最多 7 次真实计费请求）。
        assertTrue(claudeShouldDegradeForError(isRefusal = true, worthStripping = true, looksLikeParameterRejection = false))
        assertFalse(claudeShouldDegradeForError(isRefusal = true, worthStripping = false, looksLikeParameterRejection = true))
        assertTrue(claudeShouldDegradeForError(isRefusal = false, worthStripping = false, looksLikeParameterRejection = true))
        assertFalse(claudeShouldDegradeForError(isRefusal = false, worthStripping = false, looksLikeParameterRejection = false))
    }

    @Test
    fun `上游把请求片段整段原样吐回来时也要隐去`() {
        // 这一头走的是「子串方向一」：片段短于一个完整字符串值，靠值匹配抓不到。
        val body = buildJsonObject {
            put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "这是一段足够长的助手系统提示词，用来验证方向二以外的那条路径也能兜住")
                })
            })
        }
        val verbatim = """[{"type":"text","text":"这是"""
        val out = sanitizeClaudeUpstreamExcerpt(body, verbatim).orEmpty()
        assertTrue("应当走整段回显那一头：$out", out.contains("已整段隐去"))
    }

    @Test
    fun `上游只是提到字段名时不许误伤，那种原文对排查最有用`() {
        // 反过来钉一条：脱敏不能把有用的诊断抹成占位符，否则本轮「读数正确」的验收就落空了。
        val body = buildJsonObject {
            put("system", buildJsonArray {
                add(buildJsonObject { put("text", "这是一段足够长的助手系统提示词，内容本身不该出现在下面那句里") })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", "web_search") })
            })
        }
        listOf(
            "\"thinking.type.enabled\" is not supported for this model",
            "Unknown name \"cache_control\" at 'request': Cannot find field.",
            "Input should be a valid boolean: cannot apply to null",
        ).forEach { text ->
            assertEquals("字段名不是隐私，原文要留着：$text", text, sanitizeClaudeUpstreamExcerpt(body, text))
        }
    }

    // ── 6. v1014：二审又抓到 4 处「只修了一半」，这里钉行为而不是钉字符串 ──

    private fun ownDecodeFailure(e: Throwable): Boolean {
        val m = ClaudeProvider::class.java.getDeclaredMethod(
            "isOurOwnDecodeFailure", Throwable::class.java,
        )
        m.isAccessible = true
        return m.invoke(provider, e) as Boolean
    }

    @Test
    fun `自家解坏响应体与上游嫌参数要分得开（流式与非流式同一条尺子）`() {
        assertTrue(ownDecodeFailure(kotlinx.serialization.SerializationException("Unexpected JSON token at offset 0")))
        assertTrue(ownDecodeFailure(IllegalArgumentException("Element JsonObject is not a JsonObject")))
        assertFalse(ownDecodeFailure(Exception("Unknown name \"x\" at 'request': Cannot find field.")))
        assertFalse(ownDecodeFailure(Exception("prompt is too long")))
    }

    @Test
    fun `回显里夹着 too long 时强字段信号优先，不许把整趟阶梯关掉`() {
        // 二审 P1：判据吃的是整段响应体，上游会把请求回显进去。用户聊过「rate limit」
        // 这类词就会命中否决词 —— 那是把真该剥的 400 直接判成「不是形状问题」。
        val echoed = "HTTP 400: {\"error\":{\"message\":\"Unknown name \\\"thinking\\\" at 'request': " +
            "Cannot find field. echo=...prompt is too long...\"}}"
        assertTrue("强字段信号必须赢过否决词", isClaudeParameterRejection(400, echoed))
        // 而真正的长度错误仍然不许触发剥档
        assertFalse(isClaudeParameterRejection(400, "prompt is too long: 210000 tokens > 200000 maximum"))
    }

    @Test
    fun `一档都没剥的拒答卡不许说已剥到底`() {
        val one = ClaudeRejectDiagnostics(400, 1, 1, "完整形态", listOf("max_tokens=64000"), null)
        val msg = ClaudeRefusalException("reasoning_extraction", null, diagnostics = one).message.orEmpty()
        assertFalse("1/1 时 fullyStripped 必须为假：$msg", msg.contains("已逐个剥到只剩必需字段"))
    }

    @Test
    fun `剥到一半要说还没到底，多密钥用户不许被写成只有一把`() {
        val mid = ClaudeRejectDiagnostics(400, 3, 8, "已剥：思考强度", listOf("top_p"), null)
        val msg = ClaudeRefusalException("cyber", null, diagnostics = mid).message.orEmpty()
        assertTrue(msg, msg.contains("还没剥到底"))
        assertTrue(msg, msg.contains("第 3/8 档"))
        val multiKey = ClaudeRefusalException("cyber", null, keyPoolSize = 3).message.orEmpty()
        assertTrue(multiKey, multiKey.contains("池里还有 3 把可换"))
        assertFalse(multiKey, multiKey.contains("只有一把密钥"))
    }
}
