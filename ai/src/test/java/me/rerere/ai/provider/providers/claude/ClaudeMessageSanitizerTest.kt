package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二改版新增: Claude 请求体消毒层测试。
 *
 * 覆盖"先用 GPT/Gemini 再切到 Claude"时会产生的非法结构,
 * 这类结构会让 Anthropic 返回 400 ValidationException(如 "Invalid tool use format."),
 * 并且一旦出现就会卡死整个会话。
 */
class ClaudeMessageSanitizerTest {

    // ==================== thinking ====================

    @Test
    fun `thinking without signature is dropped`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("Question") }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "cross-model thinking, no signature")
                    }
                    addTextBlock("Answer")
                }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals(2, result.size)
        assertEquals(listOf("text"), result[1].blockTypes())
    }

    @Test
    fun `thinking with signature is kept`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "claude thinking")
                        put("signature", "sig_1")
                    }
                    addTextBlock("Answer")
                }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals(listOf("thinking", "text"), result[0].blockTypes())
    }

    @Test
    fun `thinking with blank signature is dropped`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "thinking")
                        put("signature", "")
                    }
                    addTextBlock("Answer")
                }
            }
        }

        assertEquals(listOf("text"), sanitizeClaudeMessages(messages)[0].blockTypes())
    }

    // ==================== 空 text ====================

    @Test
    fun `empty text block is dropped`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addTextBlock("")
                    addTextBlock("real content")
                }
            }
        }

        val content = sanitizeClaudeMessages(messages)[0].contentArray()
        assertEquals(1, content.size)
        assertEquals("real content", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `whitespace only text block is dropped`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addTextBlock("   \n  ")
                    addTextBlock("real content")
                }
            }
        }

        assertEquals(1, sanitizeClaudeMessages(messages)[0].contentArray().size)
    }

    // ==================== tool_result ====================

    @Test
    fun `tool_result with empty content array gets placeholder`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        putJsonArray("content") { }
                    }
                }
            }
        }

        val toolResult = sanitizeClaudeMessages(messages)[0].contentArray()[0].jsonObject
        val inner = toolResult["content"]!!.jsonArray
        assertEquals(1, inner.size)
        assertEquals(
            CLAUDE_EMPTY_TOOL_RESULT_PLACEHOLDER,
            inner[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `tool_result whose only block is empty text gets placeholder`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        putJsonArray("content") { addTextBlock("") }
                    }
                }
            }
        }

        val inner = sanitizeClaudeMessages(messages)[0]
            .contentArray()[0].jsonObject["content"]!!.jsonArray
        assertEquals(
            CLAUDE_EMPTY_TOOL_RESULT_PLACEHOLDER,
            inner[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `tool_result with real content is untouched`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        putJsonArray("content") { addTextBlock("output") }
                    }
                }
            }
        }

        val inner = sanitizeClaudeMessages(messages)[0]
            .contentArray()[0].jsonObject["content"]!!.jsonArray
        assertEquals("output", inner[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_result with blank string content gets placeholder`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        put("content", "")
                    }
                }
            }
        }

        val inner = sanitizeClaudeMessages(messages)[0]
            .contentArray()[0].jsonObject["content"]!!.jsonArray
        assertEquals(
            CLAUDE_EMPTY_TOOL_RESULT_PLACEHOLDER,
            inner[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
    }

    // ==================== 空消息与同角色合并 ====================

    @Test
    fun `message emptied by sanitizing is removed and neighbours merge`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("first question") }
            }
            // 只含跨模型思考的助手消息, 清理后为空
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "orphan thinking")
                    }
                }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("second question") }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals("空助手消息被删除后两条 user 必须合并", 1, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("first question", "second question"),
            result[0].contentArray().map { it.jsonObject["text"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `adjacent same role messages are merged`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("a") }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("b") }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") { addTextBlock("c") }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals(2, result.size)
        assertEquals(listOf("user", "assistant"), result.map { it.jsonObject["role"]?.jsonPrimitive?.content })
        assertEquals(2, result[0].contentArray().size)
    }

    @Test
    fun `roles alternate after sanitizing`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("q1") }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") { addTextBlock("") }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("q2") }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "no signature")
                    }
                }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("q3") }
            }
        }

        val roles = sanitizeClaudeMessages(messages).map { it.jsonObject["role"]?.jsonPrimitive?.content }

        assertEquals(listOf("user"), roles)
    }

    // ==================== tool_result 位置 ====================

    @Test
    fun `tool_result is moved to the front of user content`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", "call_1")
                        put("name", "search")
                        put("input", buildJsonObject { })
                    }
                }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        putJsonArray("content") { addTextBlock("result") }
                    }
                }
            }
            // 合并进来的普通用户消息, 必须排在 tool_result 之后
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("next question") }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals(2, result.size)
        assertEquals(listOf("tool_result", "text"), result[1].blockTypes())
    }

    // ==================== 幂等与不误伤 ====================

    @Test
    fun `normal conversation is unchanged`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") { addTextBlock("hello") }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") { addTextBlock("hi") }
            }
        }

        assertEquals(messages, sanitizeClaudeMessages(messages))
    }

    @Test
    fun `sanitizing is idempotent`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addTextBlock("")
                    addTextBlock("real")
                }
            }
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", "no signature")
                    }
                    addTextBlock("answer")
                }
            }
        }

        val once = sanitizeClaudeMessages(messages)
        assertEquals(once, sanitizeClaudeMessages(once))
    }

    @Test
    fun `tool_use and image blocks are preserved`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", "call_1")
                        put("name", "search")
                        put("input", buildJsonObject { put("q", "x") })
                    }
                }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "call_1")
                        putJsonArray("content") { addTextBlock("done") }
                    }
                    addJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", "image/png")
                            put("data", "AAAA")
                        })
                    }
                }
            }
        }

        val result = sanitizeClaudeMessages(messages)

        assertEquals(2, result.size)
        assertEquals(listOf("tool_use"), result[0].blockTypes())
        assertEquals(listOf("tool_result", "image"), result[1].blockTypes())
    }

    @Test
    fun `unknown content shape is passed through`() {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "user")
                put("content", "plain string content")
            }
        }

        assertEquals(messages, sanitizeClaudeMessages(messages))
    }

    @Test
    fun `empty input produces empty output`() {
        assertTrue(sanitizeClaudeMessages(JsonArray(emptyList())).isEmpty())
    }

    // ==================== helpers ====================

    private fun kotlinx.serialization.json.JsonArrayBuilder.addTextBlock(text: String) {
        addJsonObject {
            put("type", "text")
            put("text", text)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.contentArray(): List<kotlinx.serialization.json.JsonElement> =
        jsonObject["content"]!!.jsonArray.toList()

    private fun kotlinx.serialization.json.JsonElement.blockTypes(): List<String?> =
        contentArray().map { (it as JsonObject)["type"]?.jsonPrimitive?.content }
}
