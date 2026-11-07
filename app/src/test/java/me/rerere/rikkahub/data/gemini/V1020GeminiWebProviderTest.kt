package me.rerere.rikkahub.data.gemini

import java.net.URLDecoder
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.supportsReasoning
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1020：对 Gemini 网页版供应商执行真实 public API 的协议回归。
 *
 * 所有网络都由 OkHttp interceptor 固定应答；任何未列出的 URL/host 会立即失败，
 * 因而这些测试不会连接 Google，也不使用真实 Cookie、at 或账号数据。
 */
class V1020GeminiWebProviderTest {
    @Test
    fun `目录从账号状态RPC获取Argon并保留账号opaque模型ID`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 77)))

        val models = fixture.provider.listModels(setting())

        assertEquals(listOf(ARGON_ID), models.map { it.modelId })
        assertEquals("Gemini 4 Argon", models.single().displayName)
    }

    @Test
    fun `同名不同opaqueID的账号模型都保留`() = runBlocking {
        val fixture = fixture(
            models = listOf(
                modelItem("argon-account-id-a", "MODEL", "Gemini 4 Argon", number17 = 77),
                modelItem("argon-account-id-b", "MODEL", "Gemini 4 Argon", number17 = 78),
            ),
        )

        val models = fixture.provider.listModels(setting())

        assertEquals(listOf("argon-account-id-a", "argon-account-id-b"), models.map { it.modelId })
        assertEquals(2, models.count { it.displayName == "Gemini 4 Argon" })
    }

    @Test
    fun `同一provider换cookie后重新读取对应账号目录`() = runBlocking {
        val fixture = fixture(modelsForCookie = { cookie ->
            if (cookie.contains("account-one")) {
                listOf(modelItem("account-one-model", "MODEL", "Account One", number17 = 11))
            } else {
                listOf(modelItem("account-two-model", "MODEL", "Account Two", number17 = 22))
            }
        })

        val first = fixture.provider.listModels(setting(cookie = fakeCookie("account-one")))
        val second = fixture.provider.listModels(setting(cookie = fakeCookie("account-two")))

        assertEquals(listOf("account-one-model"), first.map { it.modelId })
        assertEquals(listOf("account-two-model"), second.map { it.modelId })
    }

    @Test
    fun `读取otAQ7b状态RPC而不是静态模型名单`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 77)))

        fixture.provider.listModels(setting())

        val request = fixture.requests.single { it.url.encodedPath == BATCH_PATH }
        assertEquals("POST", request.method)
        val rpc = Json.Default.parseToJsonElement(formValues(request).getValue("f.req"))
            .jsonArray[0].jsonArray[0].jsonArray
        assertEquals("otAQ7b", rpc[0].jsonPrimitive.content)
        assertEquals("[]", rpc[1].jsonPrimitive.content)
        assertEquals(JsonNull, rpc[2])
        assertEquals("generic", rpc[3].jsonPrimitive.content)
    }

    @Test
    fun `账号状态不是已登录时不返回静态模型`() = runBlocking {
        val fixture = fixture(accountStatus = 1016)

        val failure = captureFailure { fixture.provider.listModels(setting()) }

        assertNotNull(failure)
    }

    @Test
    fun `状态RPC HTTP失败时不返回静态模型`() = runBlocking {
        val fixture = fixture(statusHttpCode = 503)

        val failure = captureFailure { fixture.provider.listModels(setting()) }

        assertNotNull(failure)
    }

    @Test
    fun `账号目录为空时不回退到静态模型`() = runBlocking {
        val fixture = fixture(models = emptyList())
        var returned: List<Model>? = null
        val failure = captureFailure { returned = fixture.provider.listModels(setting()) }

        assertTrue(failure != null || returned.isNullOrEmpty())
        assertFalse(returned.orEmpty().any { it.modelId in LEGACY_STATIC_IDS })
    }

    @Test
    fun `请求模型头第四槽使用账号返回的opaqueID`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 77)))

        collect(fixture, model(ARGON_ID, "Gemini 4 Argon"))

        val header = requestModelHeader(fixture.requests.single { it.url.encodedPath == STREAM_PATH })
        assertEquals(ARGON_ID, header[4].jsonPrimitive.content)
    }

    @Test
    fun `header尾部modelnumber和inner79都来自模型项17`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem("flash-account-id", "MODEL", "Flash", number17 = 73)))

        collect(fixture, model("flash-account-id", "Flash"))

        val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
        val header = requestModelHeader(request)
        val inner = requestInner(request)
        assertEquals("73", header[header.size - 3].jsonPrimitive.content)
        assertEquals("73", inner[79].jsonPrimitive.content)
        assertEquals(header[header.size - 3], inner[79])
        assertEquals("1", header[header.size - 2].jsonPrimitive.content)
    }

    @Test
    fun `模型项17缺失时modelnumber回退到模型项9`() = runBlocking {
        val fixture = fixture(
            models = listOf(modelItem("fallback-number-id", "MODEL", "Fallback", number17 = null, number9 = 31)),
        )

        collect(fixture, model("fallback-number-id", "Fallback"))

        val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
        val header = requestModelHeader(request)
        assertEquals("31", header[header.size - 3].jsonPrimitive.content)
        assertEquals("31", requestInner(request)[79].jsonPrimitive.content)
    }

    @Test
    fun `tier字段8容量2写入header11`() = runBlocking {
        val fixture = fixture(
            models = listOf(modelItem(ARGON_ID, "MODEL", "Argon", number17 = 77)),
            tierFlags = JsonArray(listOf(number(8))),
        )

        collect(fixture, model(ARGON_ID, "Argon"))

        assertEquals("2", requestModelHeader(fixture.requests.single { it.url.encodedPath == STREAM_PATH })[11].jsonPrimitive.content)
    }

    @Test
    fun `capability字段115容量4写入header11`() = runBlocking {
        val fixture = fixture(
            models = listOf(modelItem(ARGON_ID, "MODEL", "Argon", number17 = 77)),
            capabilityFlags = JsonArray(listOf(number(115))),
        )

        collect(fixture, model(ARGON_ID, "Argon"))

        assertEquals("4", requestModelHeader(fixture.requests.single { it.url.encodedPath == STREAM_PATH })[11].jsonPrimitive.content)
    }

    @Test
    fun `tier字段21时容量使用模型项13而不是header11`() = runBlocking {
        val fixture = fixture(
            models = listOf(modelItem(ARGON_ID, "MODEL", "Argon", number17 = 77, capacity13 = 1)),
            tierFlags = JsonArray(listOf(number(21))),
        )

        collect(fixture, model(ARGON_ID, "Argon"))

        val header = requestModelHeader(fixture.requests.single { it.url.encodedPath == STREAM_PATH })
        assertEquals(JsonNull, header[11])
        assertEquals("1", header[12].jsonPrimitive.content)
    }

    @Test
    fun `未知opaqueID不能静默调用默认模型`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem("known-account-id", "MODEL", "Known", number17 = 12)))

        val failure = captureFailure {
            fixture.provider.streamText(setting(), listOf(userMessage("test")), params(model("unknown-account-id"))).toList()
        }

        assertNotNull(failure)
        assertTrue(fixture.requests.none { it.url.encodedPath == STREAM_PATH })
    }

    @Test
    fun `旧3点8别名在账号仅有3点5时不能偷换`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem("flash-35-account-id", "MODEL", "Gemini 3.5 Flash", number17 = 35)))

        val failure = captureFailure {
            fixture.provider.streamText(
                setting(),
                listOf(userMessage("test")),
                params(model("gemini-3.8-flash", "Gemini 3.8 Flash")),
            ).toList()
        }

        assertNotNull(failure)
        assertTrue(fixture.requests.none { it.url.encodedPath == STREAM_PATH })
    }

    @Test
    fun `目录精确含3点8全名时可规范化解析并使用真实ID`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem("flash-38-account-id", "MODEL", "Gemini 3.8 Flash", number17 = 38)))

        collect(fixture, model("  gemini 3.8 flash  ", " GEMINI 3.8 FLASH "))

        assertEquals(
            "flash-38-account-id",
            requestModelHeader(fixture.requests.single { it.url.encodedPath == STREAM_PATH })[4].jsonPrimitive.content,
        )
    }

    @Test
    fun `累计快照A到AB到ABC最终只显示ABC`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "A", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "AB", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "ABC", 2)), saveSignal = "saved")),
            ),
        )

        val result = collect(fixture)

        assertEquals("ABC", result.text)
    }

    @Test
    fun `相同累计帧重复到达不会重复正文`() = runBlocking {
        val repeated = streamPayload(listOf(candidate("rcid-main", "相同帧", 1)))
        val fixture = fixture(
            streamRows = listOf(
                streamRow(repeated),
                streamRow(repeated),
                streamRow(streamPayload(listOf(candidate("rcid-main", "相同帧", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("相同帧", collect(fixture).text)
    }

    @Test
    fun `中间文字修订后最终以最新全文为准`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "The cat", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "The cot", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "The coat", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("The coat", collect(fixture).text)
    }

    @Test
    fun `累计正文缩短后不保留旧尾巴`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "这是一段较长的草稿", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "短稿", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "短稿", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("短稿", collect(fixture).text)
    }

    @Test
    fun `模型真实生成的连续重复字不被去重`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "哈哈哈哈", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("哈哈哈哈", collect(fixture).text)
    }

    @Test
    fun `中文和emoji快照按Unicode完整保留`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "你好，世界🙂🌍", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-main", "你好，世界🙂🌍再见", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("你好，世界🙂🌍再见", collect(fixture).text)
    }

    @Test
    fun `同一帧多个候选只输出所选候选`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-first", "第一候选", 1), candidate("rcid-second", "第二候选", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-first", "第一候选完成", 2), candidate("rcid-second", "第二候选完成", 2)), saveSignal = "saved")),
            ),
        )

        val text = collect(fixture).text

        assertEquals("第一候选完成", text)
        assertFalse(text.contains("第二候选"))
    }

    @Test
    fun `候选顺序改变时继续跟踪原rcid`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-first", "甲", 1), candidate("rcid-second", "乙", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-second", "乙完成", 2), candidate("rcid-first", "甲完成", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("甲完成", collect(fixture).text)
    }

    @Test
    fun `原rcid消失后新候选替换旧草稿`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-old", "旧草稿", 1)))),
                streamRow(streamPayload(listOf(candidate("rcid-new", "新候选正文", 2)), saveSignal = "saved")),
            ),
        )

        assertEquals("新候选正文", collect(fixture).text)
    }

    @Test
    fun `正文后错误码1037真实上抛且不以stop结束`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "已有正文", 1)))),
                streamRow(streamPayload(emptyList()), errorCode = 1037),
            ),
        )
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("1037"))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
        assertEquals("已有正文", renderedText(chunks))
    }

    @Test
    fun `正文后错误码1052真实上抛且不以stop结束`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "已有正文", 1)))),
                streamRow(streamPayload(emptyList()), errorCode = 1052),
            ),
        )
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("1052"))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
        assertEquals("已有正文", renderedText(chunks))
    }

    @Test
    fun `候选状态2代表正常stop结束`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "完成正文", 2)))),
            ),
        )

        val result = collect(fixture)

        assertEquals("完成正文", result.text)
        assertEquals("stop", result.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `只有进行中候选1到EOF不能伪装正常stop`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "未完成正文", 1)))),
            ),
        )
        val result = collect(fixture)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("未完成正文", result.text)
        assertNull(finish.finishReason)
        assertTrue(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `payload25保存信号不等于候选状态1已完成`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "已保存正文", 1)), saveSignal = "saved")),
            ),
        )

        val result = collect(fixture)

        assertEquals("已保存正文", result.text)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertNull(finish.finishReason)
        assertTrue(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `generateText与streamText正文一致并返回finishReason`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "最终正文🙂", 2)), saveSignal = "saved")),
            ),
        )
        val selectedModel = model(ARGON_ID, "Gemini 4 Argon")
        val streamed = collect(fixture, selectedModel)
        val generated = fixture.provider.generateText(
            setting(),
            listOf(userMessage("test")),
            params(selectedModel),
        )

        assertEquals(streamed.text, messageText(generated.message))
        assertEquals("stop", generated.finishReason)
    }

    @Test
    fun `空正文不能伪装成成功回复`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(
                streamRow(streamPayload(listOf(candidate("rcid-main", "", 2)), saveSignal = "saved")),
            ),
        )

        val failure = collectFailure(fixture)

        assertNotNull(failure)
    }

    @Test
    fun `缺Cookie时失败且不发出任何网络请求`() = runBlocking {
        val fixture = fixture()

        val failure = captureFailure {
            fixture.provider.streamText(
                setting(cookie = ""),
                listOf(userMessage("test")),
                params(model()),
            ).toList()
        }

        assertNotNull(failure)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `app页面CancellationException原样上抛`() = runBlocking {
        val fixture = fixture(appFailure = CancellationException("fake cancellation"))

        val failure = captureFailure {
            fixture.provider.streamText(setting(), listOf(userMessage("test")), params(model())).toList()
        }

        assertTrue(failure is CancellationException)
        assertTrue(fixture.requests.single().url.encodedPath == APP_PATH)
    }

    @Test
    fun `existing text delta events still append literally`() {
        assertEquals("AAB", renderedText(listOf(
            StreamChunk.TextDelta("ordinary", "A"),
            StreamChunk.TextDelta("ordinary", "AB"),
        )))
    }

    @Test
    fun `ambiguous display name never guesses between distinct model IDs`() = runBlocking {
        val fixture = fixture(models = listOf(
            modelItem("first-id", "Pro", "Gemini 4 Argon", number17 = 7),
            modelItem("second-id", "Pro", "Gemini 4 Argon", number17 = 8),
        ))
        val failure = captureFailure {
            fixture.provider.streamText(setting(), listOf(userMessage("test")), params(model("Gemini 4 Argon"))).toList()
        }
        assertNotNull(failure)
        assertTrue(fixture.requests.none { it.url.encodedPath == STREAM_PATH })
    }

    @Test
    fun `new stream snapshots do not replace earlier tool step prose`() = runBlocking {
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("main", "A", 1)))),
            streamRow(streamPayload(listOf(candidate("main", "AB", 2)))),
        ))
        val chunks = fixture.provider.streamText(setting(), listOf(userMessage("test")), params(model())).toList()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Earlier step. "))))
        for (chunk in chunks) messages = handler.handle(messages, chunk)
        assertEquals("Earlier step. AB", messageText(messages.last()))
    }

    @Test
    fun `partial JSON frame after prose remains an error not a clean stop`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(streamRow(streamPayload(listOf(candidate("main", "已有正文", 1))))),
            streamSuffix = "\n25\n[[\"wrb.fr\",null,",
        )
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("数据帧"))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
        assertEquals("已有正文", renderedText(chunks))
    }

    @Test
    fun `later malformed frame must not discard earlier decoded prose in same socket read`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(streamRow(streamPayload(listOf(candidate("main", "已有正文", 1))))),
            streamSuffix = "\nnot-json",
        )
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("数据帧"))
        assertEquals("已有正文", renderedText(chunks))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
    }

    @Test
    fun `web model picker title shows the account name rather than opaque ID`() {
        assertEquals("Gemini 4 Argon", geminiWebPickerLabel(model(ARGON_ID, "Gemini 4 Argon"), true))
        assertEquals(ARGON_ID, geminiWebPickerLabel(model(ARGON_ID, "   "), true))
    }

    @Test
    fun `other provider picker titles remain the original model ID`() {
        assertEquals("plain-model-id", geminiWebPickerLabel(model("plain-model-id", "Custom display name"), false))
    }

    @Test
    fun `length marker without payload after completed prose cannot be a clean stop`() = runBlocking {
        val fixture = fixture(
            streamRows = listOf(streamRow(streamPayload(listOf(candidate("main", "已完整解出的正文", 2))))),
            streamSuffix = "\n25\n",
        )
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)
        assertTrue(failure is IOException)
        assertEquals("已完整解出的正文", renderedText(chunks))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
    }

    @Test
    fun `frame sizes use UTF16 and accept the newline counting variants`() = runBlocking {
        val payload = "[[\"中文🙂\"]]"
        for (extraUnits in 0..2) {
            val frames = mutableListOf<JsonArray>()
            val wire = "${payload.length + extraUnits}\n$payload\n"
            readGeminiWebFrames(Buffer().writeUtf8(wire), Json.Default) { frames.add(it) }
            assertEquals(1, frames.size)
            assertEquals("中文🙂", frames.single()[0].jsonArray[0].jsonPrimitive.content)
        }
    }

    @Test
    fun `inconsistent advertised frame length is an error after earlier frame delivery`() = runBlocking {
        val frames = mutableListOf<JsonArray>()
        val failure = captureFailure {
            readGeminiWebFrames(Buffer().writeUtf8("5\n[[1]]\n99\n[[2]]\n"), Json.Default) { frames.add(it) }
        }
        assertTrue(failure is IOException)
        assertEquals(1, frames.size)
        assertEquals("1", frames.single()[0].jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `web provider emits only one snapshot text ID even when candidates change`() = runBlocking {
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("old", "第一稿", 1), candidate("other", "其他草稿", 1)))),
            streamRow(streamPayload(listOf(candidate("replacement", "最终稿", 2)))),
        ))
        val result = collect(fixture)
        assertTrue(result.chunks.none { it is StreamChunk.TextDelta })
        val snapshots = result.chunks.filterIsInstance<StreamChunk.TextSnapshot>()
        assertEquals(setOf("gemini-web"), snapshots.map { it.id }.toSet())
        assertEquals("最终稿", result.text)
    }

    @Test
    fun `replacement candidate without text cannot finish using the old draft`() = runBlocking {
        val noText = jsonArray(9, 0 to text("new"), 8 to JsonArray(listOf(number(2))))
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("old", "旧草稿", 1)))),
            streamRow(streamPayload(listOf(noText))),
        ))
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks)
        assertTrue(failure is IOException)
        assertEquals("", renderedText(chunks))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
    }

    @Test
    fun `replacement metadata clears old draft before its own text arrives`() = runBlocking {
        val noText = jsonArray(9, 0 to text("new"), 8 to JsonArray(listOf(number(1))))
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("old", "旧草稿", 1)))),
            streamRow(streamPayload(listOf(noText))),
            streamRow(streamPayload(listOf(candidate("new", "新正文", 2)))),
        ))
        val result = collect(fixture)
        assertEquals(listOf("旧草稿", "", "新正文"), result.chunks.filterIsInstance<StreamChunk.TextSnapshot>().map { it.text })
        assertEquals("新正文", result.text)
    }

    @Test
    fun `candidate without an identity is not mixed into the real candidate`() = runBlocking {
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("real", "真实草稿", 1)))),
            streamRow(streamPayload(listOf(candidate("", "无身份的内容", 2)))),
            streamRow(streamPayload(listOf(candidate("real", "真实完成", 2)))),
        ))
        val result = collect(fixture)
        assertTrue(result.chunks.filterIsInstance<StreamChunk.TextSnapshot>().none { it.text.contains("无身份") })
        assertEquals("真实完成", result.text)
    }

    @Test
    fun `model list UI state carries the actual HTTP failure instead of a blank success`() = runBlocking {
        val fixture = fixture(statusHttpCode = 503)
        val result = loadGeminiWebModelsForUi { fixture.provider.listModels(setting()) }
        assertTrue(result.models.isEmpty())
        assertTrue(result.error.orEmpty().contains("503"))
    }

    @Test
    fun `model list UI state exposes successful current account names`() = runBlocking {
        val fixture = fixture()
        val result = loadGeminiWebModelsForUi { fixture.provider.listModels(setting()) }
        assertNull(result.error)
        assertEquals(listOf(ARGON_ID), result.models.map { it.modelId })
    }

    @Test
    fun `model list UI loading must propagate cancellation rather than show failure`() = runBlocking {
        val fixture = fixture(appFailure = CancellationException("fake user cancellation"))
        val failure = captureFailure { loadGeminiWebModelsForUi { fixture.provider.listModels(setting()) } }
        assertTrue(failure is CancellationException)
    }

    @Test
    fun `fast protocol failure cannot cancel prose queued to a slow UI collector`() = runBlocking {
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("real", "先交付的正文", 1)))),
            streamRow(streamPayload(emptyList()), errorCode = 1037),
        ))
        val chunks = mutableListOf<StreamChunk>()
        val failure = collectFailure(fixture, chunks = chunks, slowCollectorMillis = 20)
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("1037"))
        assertEquals("先交付的正文", renderedText(chunks))
        assertTrue(chunks.none { it is StreamChunk.Finish && it.finishReason == "stop" })
    }

    @Test
    fun `confirmed completion survives same-body metadata without a repeated state`() = runBlocking {
        val metadata = jsonArray(9, 0 to text("real"), 1 to JsonArray(listOf(text("完整正文"))))
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("real", "完整正文", 2)))),
            streamRow(streamPayload(listOf(metadata))),
        ))
        val result = collect(fixture)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("完整正文", result.text)
        assertEquals("stop", finish.finishReason)
        assertFalse(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `explicit ongoing state revokes earlier completion even with the same body`() = runBlocking {
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("real", "正文", 2)))),
            streamRow(streamPayload(listOf(candidate("real", "正文", 1)))),
        ))
        val finish = collect(fixture).chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertNull(finish.finishReason)
        assertTrue(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `new body without completion does not inherit earlier completion`() = runBlocking {
        val changed = jsonArray(9, 0 to text("real"), 1 to JsonArray(listOf(text("正文加上新草稿"))))
        val fixture = fixture(streamRows = listOf(
            streamRow(streamPayload(listOf(candidate("real", "正文", 2)))),
            streamRow(streamPayload(listOf(changed))),
        ))
        val result = collect(fixture)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("正文加上新草稿", result.text)
        assertNull(finish.finishReason)
        assertTrue(finish.terminatedWithoutSentinel)
    }

    @Test
    fun `AUTO Argon at transport stays low so background calls do not inherit high`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 8)))
        collect(fixture, model(ARGON_ID, "Gemini 4 Argon"), reasoningLevel = ReasoningLevel.AUTO)
        val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
        val header = requestModelHeader(request)
        assertEquals("1", header[header.size - 2].jsonPrimitive.content)
        assertEquals("1", requestInner(request)[80].jsonPrimitive.content)
    }

    @Test
    fun `explicit HIGH XHIGH MAX ask for high thinking wire`() = runBlocking {
        for (level in listOf(ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX)) {
            val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 8)))
            collect(fixture, model(ARGON_ID, "Gemini 4 Argon"), reasoningLevel = level)
            val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
            val header = requestModelHeader(request)
            assertEquals("level=$level header", "2", header[header.size - 2].jsonPrimitive.content)
            assertEquals("level=$level body", "2", requestInner(request)[80].jsonPrimitive.content)
        }
    }

    @Test
    fun `explicit MEDIUM asks for server-side medium wire`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 8)))
        collect(fixture, model(ARGON_ID, "Gemini 4 Argon"), reasoningLevel = ReasoningLevel.MEDIUM)
        val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
        val header = requestModelHeader(request)
        assertEquals("4", header[header.size - 2].jsonPrimitive.content)
        assertEquals("4", requestInner(request)[80].jsonPrimitive.content)
    }

    @Test
    fun `explicit LOW and OFF ask for standard low wire`() = runBlocking {
        for (level in listOf(ReasoningLevel.LOW, ReasoningLevel.OFF)) {
            val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 8)))
            collect(fixture, model(ARGON_ID, "Gemini 4 Argon"), reasoningLevel = level)
            val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
            val header = requestModelHeader(request)
            assertEquals("level=$level header", "1", header[header.size - 2].jsonPrimitive.content)
            assertEquals("level=$level body", "1", requestInner(request)[80].jsonPrimitive.content)
        }
    }

    @Test
    fun `AUTO non-Argon web model keeps the existing low wire`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem("flash-id", "MODEL", "Gemini 3.8 Flash", number17 = 38)))
        collect(fixture, model("flash-id", "Gemini 3.8 Flash"), reasoningLevel = ReasoningLevel.AUTO)
        val request = fixture.requests.single { it.url.encodedPath == STREAM_PATH }
        val header = requestModelHeader(request)
        assertEquals("1", header[header.size - 2].jsonPrimitive.content)
        assertEquals("1", requestInner(request)[80].jsonPrimitive.content)
    }

    @Test
    fun `web model list has no cross-provider ability flag but the provider type enables the picker`() = runBlocking {
        val fixture = fixture(models = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 8)))
        val models = fixture.provider.listModels(setting())
        assertTrue(models.single().abilities.isEmpty())
        assertTrue(models.single().supportsReasoning(listOf(setting().copy(models = models))))
    }

    private fun renderedText(chunks: List<StreamChunk>): String {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        for (chunk in chunks) messages = handler.handle(messages, chunk)
        return messageText(messages.last())
    }

    private data class Fixture(
        val provider: GeminiWebProvider,
        val requests: CopyOnWriteArrayList<Request>,
    )

    private data class StreamResult(
        val chunks: List<StreamChunk>,
        val message: UIMessage,
    ) {
        val text: String get() = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    }

    private fun fixture(
        models: List<JsonArray> = listOf(modelItem(ARGON_ID, "MODEL", "Gemini 4 Argon", number17 = 77)),
        streamRows: List<JsonArray> = listOf(
            streamRow(streamPayload(listOf(candidate("rcid-main", "fixture response", 2)), saveSignal = "saved")),
        ),
        accountStatus: Int = 1000,
        statusHttpCode: Int = 200,
        appHttpCode: Int = 200,
        streamHttpCode: Int = 200,
        tierFlags: JsonArray = JsonArray(emptyList()),
        capabilityFlags: JsonArray = JsonArray(emptyList()),
        modelsForCookie: ((String) -> List<JsonArray>)? = null,
        appFailure: Throwable? = null,
        streamSuffix: String = "",
    ): Fixture {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            check(request.url.host == HOST) { "Unexpected network host: ${request.url.host}" }
            when (request.url.encodedPath) {
                APP_PATH -> {
                    appFailure?.let { throw it }
                    response(request, appHttpCode, appHtml())
                }
                BATCH_PATH -> {
                    val cookie = request.header("cookie").orEmpty()
                    val accountModels = modelsForCookie?.invoke(cookie) ?: models
                    response(
                        request,
                        statusHttpCode,
                        statusFrame(statusBody(accountModels, accountStatus, tierFlags, capabilityFlags)),
                    )
                }
                STREAM_PATH -> response(request, streamHttpCode, streamResponse(streamRows) + streamSuffix)
                else -> error("Unexpected network URL: ${request.url}")
            }
        }).build()
        return Fixture(GeminiWebProvider(client, Json.Default), requests)
    }

    private suspend fun collect(
        fixture: Fixture,
        selectedModel: Model = model(),
        selectedSetting: ProviderSetting.GeminiWeb = setting(),
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    ): StreamResult {
        val chunks = fixture.provider.streamText(
            selectedSetting,
            listOf(userMessage("test")),
            params(selectedModel, reasoningLevel),
        ).toList()
        val handler = StreamChunkHandler(selectedModel)
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        chunks.forEach { chunk -> messages = handler.handle(messages, chunk) }
        return StreamResult(chunks, messages.last())
    }

    private suspend fun collectFailure(
        fixture: Fixture,
        selectedModel: Model = model(),
        chunks: MutableList<StreamChunk> = mutableListOf(),
        slowCollectorMillis: Long = 0,
    ): Throwable? {
        val handler = StreamChunkHandler(selectedModel)
        var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        return captureFailure {
            fixture.provider.streamText(
                setting(),
                listOf(userMessage("test")),
                params(selectedModel),
            ).collect { chunk ->
                if (slowCollectorMillis > 0) delay(slowCollectorMillis)
                chunks += chunk
                messages = handler.handle(messages, chunk)
            }
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }

    private fun setting(
        cookie: String = fakeCookie("fake-account"),
    ) = ProviderSetting.GeminiWeb(cookie = cookie)

    private fun fakeCookie(account: String): String =
        "__Secure-1PSID=fake-$account; __Secure-1PSIDTS=fake-ts-$account"

    private fun model(
        id: String = ARGON_ID,
        displayName: String = "Gemini 4 Argon",
    ) = Model(modelId = id, displayName = displayName)

    private fun params(model: Model, reasoningLevel: ReasoningLevel = ReasoningLevel.OFF) =
        TextGenerationParams(model = model, reasoningLevel = reasoningLevel)

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun messageText(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun response(request: Request, code: Int, content: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Fixture failure")
            .body(content.toResponseBody("text/plain; charset=utf-8".toMediaType()))
            .build()

    private fun appHtml(): String =
        """<html><script>window.data={"SNlM0e":"fake-at","cfb2h":"fake-bl","FdrFJe":"fake-sid"}</script></html>"""

    private fun statusBody(
        models: List<JsonArray>,
        accountStatus: Int,
        tierFlags: JsonArray,
        capabilityFlags: JsonArray,
    ): JsonArray = jsonArray(
        18,
        14 to number(accountStatus),
        15 to JsonArray(models),
        16 to tierFlags,
        17 to capabilityFlags,
    )

    private fun statusFrame(body: JsonArray): String {
        val row = JsonArray(
            listOf(
                text("wrb.fr"),
                text("otAQ7b"),
                text(body.toString()),
            ),
        )
        return ")]}'\n" + framed(JsonArray(listOf(row)))
    }

    private fun modelItem(
        id: String,
        category: String,
        displayName: String,
        number17: Int?,
        number9: Int = 9,
        capacity13: Int? = null,
    ): JsonArray {
        val fields = mutableListOf<Pair<Int, JsonElement>>(
            0 to text(id),
            1 to text(category),
            9 to number(number9),
            11 to text(displayName),
        )
        number17?.let { fields += 17 to number(it) }
        capacity13?.let { fields += 13 to number(it) }
        return jsonArray(18, *fields.toTypedArray())
    }

    private fun streamPayload(
        candidates: List<JsonArray>,
        saveSignal: String? = null,
    ): JsonArray {
        val fields = mutableListOf<Pair<Int, JsonElement>>(4 to JsonArray(candidates))
        saveSignal?.let { fields += 25 to text(it) }
        return jsonArray(26, *fields.toTypedArray())
    }

    private fun candidate(rcid: String, completeText: String, status: Int): JsonArray = jsonArray(
        9,
        0 to text(rcid),
        1 to JsonArray(listOf(text(completeText))),
        8 to JsonArray(listOf(number(status))),
    )

    private fun streamRow(payload: JsonArray, errorCode: Int? = null): JsonArray {
        val fields = mutableListOf<Pair<Int, JsonElement>>(
            0 to text("wrb.fr"),
            1 to text("StreamGenerate"),
            2 to text(payload.toString()),
        )
        errorCode?.let { fields += 5 to errorField(it) }
        return jsonArray(6, *fields.toTypedArray())
    }

    /** row[5][2][0][1][0] is Google's outer stream error code. */
    private fun errorField(code: Int): JsonArray = jsonArray(
        3,
        2 to jsonArray(
            1,
            0 to jsonArray(
                2,
                1 to jsonArray(1, 0 to number(code)),
            ),
        ),
    )

    private fun streamResponse(rows: List<JsonArray>): String = ")]}'\n" + rows.joinToString(separator = "") { framed(JsonArray(listOf(it))) }

    /** Google protocol frames use a UTF-16 length line followed by one JSON line. */
    private fun framed(json: JsonElement): String {
        val encoded = json.toString()
        return "${encoded.length}\n$encoded\n"
    }

    private fun jsonArray(size: Int, vararg fields: Pair<Int, JsonElement>): JsonArray =
        JsonArray(MutableList<JsonElement>(size) { JsonNull }.apply {
            fields.forEach { (index, value) -> this[index] = value }
        })

    private fun flagArray(size: Int, vararg fields: Pair<Int, JsonElement>): JsonArray =
        jsonArray(size, *fields)

    private fun text(value: String) = JsonPrimitive(value)
    private fun number(value: Int) = JsonPrimitive(value)

    private fun formValues(request: Request): Map<String, String> {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8().split('&').associate { pair ->
            val separator = pair.indexOf('=')
            val key = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            URLDecoder.decode(key, Charsets.UTF_8.name()) to URLDecoder.decode(value, Charsets.UTF_8.name())
        }
    }

    private fun requestInner(request: Request): JsonArray {
        val requestEnvelope = Json.Default.parseToJsonElement(formValues(request).getValue("f.req")).jsonArray
        return Json.Default.parseToJsonElement(requestEnvelope[1].jsonPrimitive.content).jsonArray
    }

    private fun requestModelHeader(request: Request): JsonArray =
        Json.Default.parseToJsonElement(request.header(MODEL_HEADER)!!).jsonArray

    private companion object {
        const val HOST = "gemini.google.com"
        const val APP_PATH = "/app"
        const val BATCH_PATH = "/_/BardChatUi/data/batchexecute"
        const val STREAM_PATH = "/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
        const val MODEL_HEADER = "x-goog-ext-525001261-jspb"
        const val ARGON_ID = "argon-account-id"
        val LEGACY_STATIC_IDS = setOf("gemini-3.1-pro", "gemini-3.8-flash", "gemini-3.5-flash-lite")
    }
}
