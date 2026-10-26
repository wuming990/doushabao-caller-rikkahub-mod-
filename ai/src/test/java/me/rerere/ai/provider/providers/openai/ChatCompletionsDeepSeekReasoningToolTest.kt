package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1010：DeepSeek 思考模式 + 带 tools 时，历史每条 assistant 都必须带回 reasoning_content。
 *
 * 真机症状（用户原话）：「是需要新版本后新开对话才行么，旧版本的对话升级新版本后还是会有这个报错」，
 * 报错原文：「历史消息缺少 reasoning_content，当前可用路线无法继续工具调用」。
 *
 * 官方文档与社区实测的结论：上游偶尔就是会返回**一轮零思考的工具调用**（reasoning_tokens = 0），
 * 这种轮次客户端造不出思考内容；不补字段，这条消息一旦进了历史，**整个会话之后每一句都会 400**。
 * 成熟做法（qwen-code / langchain / openclaw / hermes 完全一致）：字段必须存在，值给空串即可。
 */
class ChatCompletionsDeepSeekReasoningToolTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        alwaysIncludeReasoning: Boolean,
        includeHistoryReasoning: Boolean = true,
    ): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            List::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            api,
            messages,
            includeHistoryReasoning,
            false,
            listOf(Modality.TEXT, Modality.IMAGE),
            alwaysIncludeReasoning,
        ) as JsonArray
    }

    /** 走**真正的请求构造**，钉住「闸门 → 传参」这条接线（审查位 v1010 P1-1） */
    private fun buildBody(modelId: String, withTools: Boolean, history: List<UIMessage>): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(modelId = modelId, abilities = listOf(ModelAbility.TOOL)),
            tools = if (withTools) {
                listOf(Tool(name = "t1", description = "d", execute = { emptyList() }))
            } else {
                emptyList()
            },
        )
        return method.invoke(api, history, params, ProviderSetting.OpenAI(), false) as JsonObject
    }

    private fun JsonObject.messagesOf(role: String): List<JsonObject> =
        this["messages"]?.jsonArray.orEmpty()
            .filter { ((it as? JsonObject)?.get("role"))?.jsonPrimitive?.content == role }
            .map { it as JsonObject }

    private fun zeroReasoningToolTurn() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "wire-1",
                toolName = "search_web",
                input = "{}",
                output = listOf(UIMessagePart.Text("结果")),
            ),
        ),
    )

    private fun JsonObject.reasoningField(): String? =
        this["reasoning_content"]?.jsonPrimitive?.content

    @Test
    fun `闸门只在 DeepSeek 且这次带工具时打开`() {
        assertTrue(requiresReasoningContentReplay("deepseek-v4.1-flash-1017", hasTools = true))
        assertTrue(requiresReasoningContentReplay("DeepSeek-R1", hasTools = true))
        // 不带 tools 时官方明确「无需回传，传了也忽略」→ 不该多塞字段
        assertFalse(requiresReasoningContentReplay("deepseek-chat", hasTools = false))
        assertFalse(requiresReasoningContentReplay("gpt-5", hasTools = true))
    }

    @Test
    fun `工具轮没有思考时补空串，字段必须存在`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search_web",
                    input = """{"query":"x"}""",
                    output = listOf(UIMessagePart.Text("结果")),
                ),
            ),
        )

        val payload = buildMessages(listOf(message), alwaysIncludeReasoning = true)
            .first { (it as JsonObject)["role"]?.jsonPrimitive?.content == "assistant" }
            as JsonObject

        assertTrue("reasoning_content 字段必须存在", payload.containsKey("reasoning_content"))
        assertEquals("值给空串即可通过校验", "", payload.reasoningField())
        assertTrue("tool_calls 必须原样保留（不许把本轮工具弄丢）", payload.containsKey("tool_calls"))
    }

    @Test
    fun `不补标记时行为与改前一字不差`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("结果")),
                ),
            ),
        )

        val payload = buildMessages(listOf(message), alwaysIncludeReasoning = false)
            .first { (it as JsonObject)["role"]?.jsonPrimitive?.content == "assistant" }
            as JsonObject

        assertFalse("没打开标记就不该多塞字段", payload.containsKey("reasoning_content"))
    }

    @Test
    fun `有思考时原样带回，不被空串覆盖`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("这一轮真的想过"),
                UIMessagePart.Text("结论"),
            ),
        )

        val payload = buildMessages(listOf(message), alwaysIncludeReasoning = true)
            .first() as JsonObject

        assertEquals("这一轮真的想过", payload.reasoningField())
    }

    @Test
    fun `纯文本的助手消息也要补（openclaw 实测：漏了照样 400）`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("只是回答了一句话")),
        )

        val payload = buildMessages(listOf(message), alwaysIncludeReasoning = true)
            .first() as JsonObject

        assertTrue("不带 tool_calls 的普通助手消息同样要有这个字段", payload.containsKey("reasoning_content"))
        assertEquals("", payload.reasoningField())
    }
    // ---- 审查位 v1010 P1 补的接线与形状 ----

    @Test
    fun `接线：deepseek 且带 tools 时，真请求体里必须出现该字段`() {
        val body = buildBody("deepseek-v4-flash", withTools = true, history = listOf(zeroReasoningToolTurn()))
        val assistant = body.messagesOf("assistant").single()
        assertTrue("请求体确实带了 tools", body.containsKey("tools"))
        assertEquals("", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `接线：非 deepseek 模型一字不动`() {
        val body = buildBody("gpt-5", withTools = true, history = listOf(zeroReasoningToolTurn()))
        val assistant = body.messagesOf("assistant").single()
        assertFalse("别的模型不该被牵连", assistant.containsKey("reasoning_content"))
    }

    @Test
    fun `接线：deepseek 但这次不带 tools 时不补字段`() {
        val body = buildBody("deepseek-v4-flash", withTools = false, history = listOf(zeroReasoningToolTurn()))
        val assistant = body.messagesOf("assistant").single()
        assertFalse("不带 tools 时官方明确无需回传", assistant.containsKey("reasoning_content"))
    }

    @Test
    fun `形状：role=tool 的结果消息不许带 reasoning_content`() {
        val body = buildBody("deepseek-v4-flash", withTools = true, history = listOf(zeroReasoningToolTurn()))
        val toolMessages = body.messagesOf("tool")
        assertTrue("应当有一条 tool 结果消息", toolMessages.isNotEmpty())
        assertTrue(
            "只许给 assistant 补字段",
            toolMessages.all { !it.containsKey("reasoning_content") },
        )
    }

    @Test
    fun `用户关掉「回传历史思考」时补空串，而不是把原文塞回去`() {
        // 这正是真机报错的形状：有 tool_calls、开关关着、历史里没有可用思考
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("库里存着的思考原文"),
                UIMessagePart.Tool("off-1", "search_web", "{}", listOf(UIMessagePart.Text("结果"))),
            ),
        )
        val payload = buildMessages(
            listOf(message),
            alwaysIncludeReasoning = true,
            includeHistoryReasoning = false,
        ).first { (it as JsonObject)["role"]?.jsonPrimitive?.content == "assistant" } as JsonObject

        assertEquals("开关关着就不该把原文发出去", "", payload["reasoning_content"]?.jsonPrimitive?.content)
    }
}
