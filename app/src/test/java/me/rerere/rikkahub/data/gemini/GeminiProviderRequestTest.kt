package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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

    // ---- v309：账号通道上「内置搜索」与「客户端工具」互斥，二选一一次算完 ----

    private fun toolsSummary(request: JsonObject): Pair<Boolean, List<String>> {
        val tools = request["tools"] as? JsonArray ?: return false to emptyList()
        val hasFunctions = tools.any { (it as? JsonObject)?.containsKey("functionDeclarations") == true }
        val builtIns = tools.flatMap { t ->
            ((t as? JsonObject)?.keys ?: emptySet()).filter { it != "functionDeclarations" }
        }
        return hasFunctions to builtIns
    }

    private fun modeOf(request: JsonObject): String? =
        (request["toolConfig"] as? JsonObject)?.get("functionCallingConfig")
            ?.let { it as? JsonObject }?.get("mode")?.jsonPrimitive?.contentOrNull

    private fun flagOf(request: JsonObject): Boolean? =
        (request["toolConfig"] as? JsonObject)?.get("includeServerSideToolInvocations")
            ?.jsonPrimitive?.booleanOrNull

    private fun requestWithFunctionTools(vararg names: String): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "functionDeclarations",
                            buildJsonArray {
                                names.forEach { name -> add(buildJsonObject { put("name", name) }) }
                            },
                        )
                    }
                )
            }
        )
    }

    private fun mixedRequest(): JsonObject = buildJsonObject {
        put("contents", "search something")
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "functionDeclarations",
                            buildJsonArray { add(buildJsonObject { put("name", "workspace_read_file") }) },
                        )
                    }
                )
                add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
            }
        )
        put("toolConfig", buildJsonObject { put("includeServerSideToolInvocations", true) })
    }

    private fun historyWithToolCall(): List<UIMessage> = listOf(
        UIMessage.user("读个文件"),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("内容")),
                )
            ),
        ),
    )

    @Test
    fun `a fresh mixed request keeps built-in search and drops client function tools`() {
        val shaped = resolveAntigravityToolShape(mixedRequest(), listOf(userMessage("搜点东西")))
        val (hasFunctions, builtIns) = toolsSummary(shaped)
        assertFalse("客户端函数工具必须被摘掉，否则服务端继续 400", hasFunctions)
        assertTrue("内置搜索必须保住，这是用户要的", builtIns.contains("googleSearch"))
        // 纯内置搜索请求不带 toolConfig（官方 gemini-cli 的 web-search 就是这个形状）
        assertNull(shaped["toolConfig"])
    }

    @Test
    fun `a chat whose history already used client tools keeps the tools instead`() {
        // 历史里的 functionCall / functionResponse 会被 buildContents 照原样回传，
        // 摘掉声明就成了「有调用记录、没声明」的不一致形状，后果没人验证过。
        val shaped = resolveAntigravityToolShape(mixedRequest(), historyWithToolCall())
        val (hasFunctions, builtIns) = toolsSummary(shaped)
        assertTrue("历史用过工具 → 声明必须保留", hasFunctions)
        assertTrue("反过来摘掉内置搜索", builtIns.isEmpty())
        assertNull(shaped["toolConfig"])
    }

    @Test
    fun `function declarations sharing one tool object with a built-in are still stripped`() {
        // 审查位抓到的静默失效形态：customBody 可以把两者塞进同一个 tool 对象。
        // 按条目过滤会整个保留（工具没摘掉、toolConfig 却被删），必须逐对象删键。
        val combined = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("functionDeclarations", buildJsonArray { add(buildJsonObject { put("name", "ask_user") }) })
                            put("googleSearch", buildJsonObject {})
                        }
                    )
                }
            )
            put("toolConfig", buildJsonObject { put("includeServerSideToolInvocations", true) })
        }
        val shaped = resolveAntigravityToolShape(combined, emptyList())
        val tools = requireNotNull(shaped["tools"] as? JsonArray)
        assertTrue(tools.none { (it as? JsonObject)?.containsKey("functionDeclarations") == true })
        assertTrue(tools.any { (it as? JsonObject)?.containsKey("googleSearch") == true })
    }

    @Test
    fun `an unknown extra tool key does not trigger the built-in downgrade`() {
        // 内置工具必须按白名单识别。customBody 塞进来的杂项键（toolType 之类）
        // 若被当成内置工具，会误摘用户正在用的客户端工具。
        val shaped = resolveAntigravityToolShape(
            buildJsonObject {
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("functionDeclarations", buildJsonArray { add(buildJsonObject { put("name", "ask_user") }) })
                                put("toolType", "SOMETHING")
                            }
                        )
                    }
                )
            },
            emptyList(),
        )
        val (hasFunctions, _) = toolsSummary(shaped)
        assertTrue("非白名单键不得触发降级", hasFunctions)
    }

    @Test
    fun `a function-tools-only request is left byte-for-byte untouched`() {
        // 日常工具调用（没开内置搜索）绝不许动 —— 这是用户每天在用的路径。
        val plain = requestWithFunctionTools("workspace_read_file", "ask_user")
        assertEquals(plain, resolveAntigravityToolShape(plain, emptyList()))
        assertNull(plain["toolConfig"])
    }

    @Test
    fun `a built-in-search-only request is left alone`() {
        val builtInOnly = buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject { put("googleSearch", buildJsonObject {}) }) })
        }
        assertEquals(builtInOnly, resolveAntigravityToolShape(builtInOnly, emptyList()))
    }

    @Test
    fun `a plain chat request without tools is left untouched`() {
        val plain = buildJsonObject { put("contents", "hi") }
        assertEquals(plain, resolveAntigravityToolShape(plain, emptyList()))
        assertNull(plain["toolConfig"])
    }

    @Test
    fun `the opt-in flag is never re-added for a built-in-only shape, in camelCase only`() {
        val shaped = resolveAntigravityToolShape(mixedRequest(), emptyList())
        val config = shaped["toolConfig"] as? JsonObject
        assertNull("混用表态已被真机证伪，不该再发", config)
        assertFalse(shaped.containsKey("tool_config"))
    }

    @Test
    fun `the Antigravity shape strips built-in search out of a fresh mixed request end to end`() {
        // v310：搜索改走独立请求后，主请求一律「保客户端工具、摘 googleSearch」。
        val shaped = withAntigravityRequestShape(mixedRequest(), listOf(userMessage("search something")))
        val (hasFunctions, builtIns) = toolsSummary(shaped)
        assertTrue("客户端工具必须原样保留", hasFunctions)
        assertFalse("googleSearch 绝不能再出现在主请求里", builtIns.contains("googleSearch"))
        assertNull("混发配套的服务端开关也要摘掉", shaped["toolConfig"])
        // 其余整形（sessionId / systemInstruction role）不受影响
        assertEquals(stableSessionId("search something"), shaped["sessionId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `the real request builder output stays wired to the shaper`() {
        // 发射端（ai 模块 GoogleProvider 写 flag 与两个 tool 对象）与整形端分处两个模块，
        // 只靠字面量对齐；上游一旦改名，其余手写 JSON 的测试不会报警。这条用真实 builder
        // 的输出喂进整形链，把两端钉在一起。
        val wire = me.rerere.ai.provider.providers.google.GoogleProvider(okhttp3.OkHttpClient())
        val method = wire.javaClass.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            me.rerere.ai.provider.TextGenerationParams::class.java,
        )
        method.isAccessible = true
        val built = method.invoke(
            wire,
            listOf(userMessage("search and call a tool")),
            me.rerere.ai.provider.TextGenerationParams(
                model = me.rerere.ai.provider.Model(
                    modelId = "gemini-3.7-flash",
                    abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL),
                    tools = setOf(me.rerere.ai.provider.BuiltInTools.Search),
                ),
                tools = listOf(
                    me.rerere.ai.core.Tool(
                        name = "save_weather",
                        description = "Save weather information",
                        execute = { emptyList() },
                    )
                ),
            ),
        ) as JsonObject

        // 前置：真实 builder 确实混发了两类工具（否则本测试守的东西不存在）
        val (hadFunctions, hadBuiltIns) = toolsSummary(built)
        assertTrue(hadFunctions)
        assertTrue(hadBuiltIns.contains("googleSearch"))
        assertEquals(true, flagOf(built))

        // v310：搜索改走独立请求后，主请求一律「保客户端工具、摘内置搜索、删混发开关」，
        // 无论历史有没有工具调用 —— 历史分支不再参与决策。
        val fresh = withAntigravityRequestShape(built, listOf(userMessage("search and call a tool")))
        val (freshFunctions, freshBuiltIns) = toolsSummary(fresh)
        assertTrue(freshFunctions)
        assertTrue(freshBuiltIns.isEmpty())
        assertNull(fresh["toolConfig"])

        // v310：历史分支不再参与决策 —— 无论有没有历史工具调用，都是「保客户端工具、摘搜索」。
        val reused = withAntigravityRequestShape(built, historyWithToolCall())
        val (reusedFunctions, reusedBuiltIns) = toolsSummary(reused)
        assertTrue(reusedFunctions)
        assertTrue(reusedBuiltIns.isEmpty())
        assertNull(reused["toolConfig"])
    }

    // ---- v310：内置搜索从主请求剥离（搜索改走独立请求） ----

    @Test
    fun `strip removes a built-in search key sharing a tool object with declarations`() {
        val combined = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("functionDeclarations", buildJsonArray { add(buildJsonObject { put("name", "ask_user") }) })
                            put("googleSearch", buildJsonObject {})
                        }
                    )
                }
            )
            put("toolConfig", buildJsonObject { put("includeServerSideToolInvocations", true) })
        }
        val stripped = stripBuiltInSearchTool(combined)
        val tools = requireNotNull(stripped["tools"] as? JsonArray)
        assertTrue("同对象里的函数声明必须保住", tools.any { (it as? JsonObject)?.containsKey("functionDeclarations") == true })
        assertFalse("googleSearch 必须逐键摘除", tools.any { (it as? JsonObject)?.containsKey("googleSearch") == true })
        assertNull("没有内置工具残留时，配套的服务端开关不该留下", stripped["toolConfig"])
    }

    @Test
    fun `strip prunes only the server-side flag and keeps the rest of tool config`() {
        val request = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
                    add(
                        buildJsonObject {
                            put("functionDeclarations", buildJsonArray { add(buildJsonObject { put("name", "ask_user") }) })
                        }
                    )
                }
            )
            put(
                "toolConfig",
                buildJsonObject {
                    put("functionCallingConfig", buildJsonObject { put("mode", "ANY") })
                    put("includeServerSideToolInvocations", true)
                }
            )
        }
        val stripped = stripBuiltInSearchTool(request)
        val tools = requireNotNull(stripped["tools"] as? JsonArray)
        assertTrue("函数工具必须留下", tools.any { (it as? JsonObject)?.containsKey("functionDeclarations") == true })
        assertFalse("googleSearch 被摘掉", tools.any { (it as? JsonObject)?.containsKey("googleSearch") == true })
        val config = requireNotNull(stripped["toolConfig"] as? JsonObject)
        assertFalse(config.containsKey("includeServerSideToolInvocations"))
        assertEquals("ANY", modeOf(stripped))
    }

    @Test
    fun `strip leaves a request without built-in search byte-for-byte untouched`() {
        val plain = requestWithFunctionTools("workspace_read_file", "ask_user")
        assertEquals(plain, stripBuiltInSearchTool(plain))
    }

    @Test
    fun `strip leaves a built-in-search-only request byte-for-byte untouched`() {
        // 没有函数工具 = 不存在混发 400，纯搜索请求原样保留（圆桌/子代理等路径继续可用）。
        val builtInOnly = buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject { put("googleSearch", buildJsonObject {}) }) })
        }
        assertEquals(builtInOnly, stripBuiltInSearchTool(builtInOnly))
    }

    @Test
    fun `urlContext survives the strip and still follows the exclusivity rule`() {
        // strip 只摘 googleSearch；urlContext 这类残留内置键继续走 v309 的互斥处理（纵深防御）。
        val request = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
                    add(buildJsonObject { put("urlContext", buildJsonObject {}) })
                    add(
                        buildJsonObject {
                            put("functionDeclarations", buildJsonArray { add(buildJsonObject { put("name", "ask_user") }) })
                        }
                    )
                }
            )
        }
        val fresh = withAntigravityRequestShape(request, listOf(userMessage("帮我查个网页")))
        val (freshFunctions, freshBuiltIns) = toolsSummary(fresh)
        assertFalse("新对话：urlContext 与函数工具互斥 → 摘函数工具", freshFunctions)
        assertTrue(freshBuiltIns.contains("urlContext"))
        assertFalse("googleSearch 已被 strip 摘除", freshBuiltIns.contains("googleSearch"))

        val reused = withAntigravityRequestShape(request, historyWithToolCall())
        val (reusedFunctions, reusedBuiltIns) = toolsSummary(reused)
        assertTrue("历史用过工具 → 保函数工具", reusedFunctions)
        assertFalse("反过来丢 urlContext", reusedBuiltIns.contains("urlContext"))
    }

    /** 造 contents 数组：深层嵌套的 buildJsonArray/buildJsonObject 括号极易写错，抽出来单独构造。 */
    private fun contentsWithText(text: String): JsonArray =
        buildJsonArray { add(buildJsonObject { put("text", text) }) }

    /** 造一个只含指定函数工具名的 tools 数组。 */
    private fun toolsWithFunctionNames(vararg names: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put(
                        "functionDeclarations",
                        buildJsonArray { names.forEach { add(buildJsonObject { put("name", it) }) } },
                    )
                }
            )
        }

    @Test
    fun `the tool shape log carries structure only, never conversation text`() {
        val request = buildJsonObject {
            put("contents", contentsWithText("我的私密对话内容"))
            put("tools", toolsWithFunctionNames("search_web"))
            put("toolConfig", buildJsonObject { put("includeServerSideToolInvocations", true) })
        }
        val line = requireNotNull(antigravityToolShapeLog(request, "gemini-3.8-flash"))
        assertTrue(line.contains("gemini-3.8-flash"))
        assertTrue(line.contains("search_web"))
        // 最关键的一条：对话正文绝不能进日志
        assertFalse(line.contains("我的私密对话内容"))
        assertFalse(line.contains("contents"))
    }

    @Test
    fun `a request with no tools at all produces no shape log`() {
        val request = buildJsonObject { put("contents", contentsWithText("hi")) }
        assertNull(antigravityToolShapeLog(request, "gemini-3.8-flash"))
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
