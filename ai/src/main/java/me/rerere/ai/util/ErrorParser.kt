package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

class HttpException(
    message: String,
    // v274：可选状态码（照搬 ExTV——Gemini 官方账号通道用它区分 401 等；默认 null，
    // 现有所有构造点不受影响）
    val statusCode: Int? = null,
) : RuntimeException(message)

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}

/**
 * v285：流式请求失败、但既没有异常对象也读不到错误正文时的兜底异常。
 *
 * ## 这是在补一个会造成「空回复、零报错」的真实缺陷
 *
 * SSE 建流失败时 OkHttp 会回调 `EventSourceListener.onFailure(source, t, response)`，
 * 而在「响应不是 event-stream」这条路上它给的 `t` 是 **null**；错误正文又可能读不出来
 * （[stringSafe] 只认 OkHttp 原装的 `RealResponseBody`，被拦截器重建过或已消费的 body
 * 一律返回 null）。两个都空时，旧代码执行的是 `close(null)` ——
 * 在 `callbackFlow` 里那等于告诉上层「这次回答顺利结束了」。
 *
 * 后果有三层：界面上是空回复、一个字都没有；没有任何报错可看；
 * 而且自动续跑因为「看起来是正常完成」也不会触发。
 *
 * 谷歌通道早就有这个兜底（`Exception("Unknown error: ${response.code}")`），
 * OpenAI 兼容通道（含 Grok / Codex 这些借道它的官方账号通道）与 Claude 通道一直没有。
 *
 * ## 为什么一定要把状态码写进 message
 *
 * 上层的错误分级（能不能自动续跑）是按 message 里的状态码判的：
 * 429/500/502/503/504 值得重试，400/401/403/404 重试也没用。
 * 兜底异常如果只写一句「未知错误」，续跑判断就只能猜。
 */
fun streamFailureFallback(response: Response?, bodyRaw: String? = null): HttpException {
    val snippet = bodyRaw?.trim()?.takeIf { it.isNotBlank() }?.take(500)
    val code = response?.code
    val message = when {
        code != null && snippet != null -> "Stream failed with HTTP $code: $snippet"
        code != null -> "Stream failed with HTTP $code (server returned no error detail)"
        snippet != null -> "Stream failed: $snippet"
        else -> "Stream failed before any response was received (no error detail)"
    }
    return HttpException(message, statusCode = code)
}
