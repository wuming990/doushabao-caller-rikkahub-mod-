package me.rerere.ai.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.util.json
import kotlin.time.Clock

/**
 * 将 Provider 产生的通用流事件合并为一条 [UIMessage]。
 *
 * 文本、推理和图片可能交错到达，因此不能只更新最后一个 part。本类会在收到对应的 `Start`
 * 事件时，记录事件 id 在 [UIMessage.parts] 中的位置；后续 `Delta`、`Snapshot` 和 `End`
 * 事件再通过同一 id 定位并更新该 part。工具调用则直接使用
 * [UIMessagePart.Tool.toolCallId] 定位。
 *
 * [handle] 不会修改传入的消息列表，而是返回包含更新后消息的新列表。如果列表末尾不是助手
 * 消息，第一个事件会自动创建一条空的助手消息。收到 [StreamChunk.Finish] 后，会标记消息完成
 * 并清除内部状态。
 *
 * ## 工作过程
 *
 * 1. 接收一个 [StreamChunk]，检查消息列表非空。
 * 2. 如果列表末尾不是助手消息，创建一条带有当前 [model] id 的空助手消息。
 * 3. 根据事件类型更新助手消息：
 *    - `TextStart/Delta/End`：创建、追加并结束文本 part。
 *    - `ReasoningStart/Delta/End`：创建、追加并标记推理 part 的完成时间。
 *    - `ToolCallStart/Delta/End`：按调用 id 创建工具 part，并逐步拼接工具名和输入参数。
 *    - `ServerToolStart/InputDelta/InputEnd/End`：追踪服务端工具的 JSON 输入、结果和状态。
 *    - `ImageStart/Delta/End`：创建图片 part，并逐步追加 Base64 数据。
 *    - `ImageSnapshot`：用最新的完整 Base64 快照替换同 id 图片的旧数据。
 *    - `Annotations`：追加并去重消息注解。
 *    - `Usage`：将本次用量合并到消息已有的 Token 用量中。
 *    - `Finish`：设置消息完成时间，结束尚未关闭的推理 part，并清空事件索引。
 * 4. 使用 [UIMessage.copy] 生成更新后的助手消息，将其替换到列表末尾并返回新列表。
 *
 * 一段文本流的典型调用顺序如下：
 *
 * ```
 * TextStart(id) -> TextDelta(id, ...) -> TextDelta(id, ...) -> TextEnd(id) -> Finish
 * ```
 *
 * [StreamChunk.Finish] 只代表响应流正常结束，并不保证一定到达。网络错误、协议解析失败或上层取消
 * Flow 时，流可能直接异常结束。此时已经合并的内容仍然保留，但消息的 `finishedAt` 可能为空，尚未
 * 收到 `ReasoningEnd` 的推理 part 也不会由本类自动结束。调用方应在 Flow 的完成或异常处理中执行
 * 必要的 UI 收尾，并丢弃当前 handler；不要将它复用于下一条响应流。
 *
 * 该类保存着一次响应流的合并状态，不是无状态转换器。每条并发响应流都必须使用独立实例，且
 * 事件应按 Provider 产生的顺序交给同一实例处理。
 */
class StreamChunkHandler(
    private val model: Model? = null,
    /**
     * v303：单调时钟（默认 System.nanoTime）。留成参数是为了让单测能喂假时间 ——
     * 时间相关的逻辑必须能注入「现在几点」才测得准，见项目手册的踩坑记录。
     */
    private val nowNanos: () -> Long = { System.nanoTime() },
) {
    // v297：本轮请求服务商报的用量（水位语义，同轮内多个 usage 分片互相覆盖）。
    // 上层在每轮结束时读它做「真实消耗」累加 —— 只有 handler 自己知道本轮报了什么，
    // 消息对象上的 usage 分不清「本轮没报」和「本轮报得跟上次一样」。
    private var roundUsage: TokenUsage? = null

    /** 本轮服务商报出的用量；一条都没报时为 null（上层据此决定要不要计入累计）。 */
    val reportedUsage: TokenUsage? get() = roundUsage

    // v303：本轮耗时的三个时刻。构造时 = 请求发出；第一个内容分片 = 首字；
    // 收到 Finish = 写完。没有 Finish 时（异常中断/用户停止）在第一次被读取时冻结为当时时刻，
    // 免得后续再读把空白时间算进去。
    private val startedNanos: Long = nowNanos()
    private var firstContentNanos: Long? = null
    private var endNanos: Long? = null

    /**
     * v303：本轮请求的耗时切分，供上层累加成「本条回答的真实耗时」。
     *
     * 外面工具算「每秒多少 token」分两派，两派的分母都不含工具执行时间与用户审批等待：
     * - [RoundTiming.generationMs] = 不含首字等待（LM Studio / Cherry Studio 那一派）
     * - [RoundTiming.responseMs] = 含首字等待（Claude Code 插件 / ccstatusline 那一派）
     */
    val roundTiming: RoundTiming
        get() {
            val end = endNanos ?: nowNanos().also { endNanos = it }
            val responseMs = ((end - startedNanos) / 1_000_000L).coerceAtLeast(0L)
            val generationMs = firstContentNanos
                ?.let { ((end - it) / 1_000_000L).coerceAtLeast(0L) }
            return RoundTiming(responseMs = responseMs, generationMs = generationMs)
        }

    // Map 的值是对应 part 在当前助手消息 parts 列表中的下标。
    private val textPartIndexes = mutableMapOf<String, Int>()
    private val reasoningPartIndexes = mutableMapOf<String, Int>()
    private val imagePartIndexes = mutableMapOf<String, Int>()
    private val serverToolInputBuffers = mutableMapOf<String, StringBuilder>()

    /**
     * 将一个 [chunk] 合并进消息列表末尾的助手消息，并返回新的消息列表。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun handle(messages: List<UIMessage>, chunk: StreamChunk): List<UIMessage> {
        require(messages.isNotEmpty()) { "messages must not be empty" }

        val targetMessages = if (messages.last().role != MessageRole.ASSISTANT) {
            messages + UIMessage(modelId = model?.id, role = MessageRole.ASSISTANT, parts = emptyList())
        } else {
            messages
        }
        // v303：第一个「内容分片」出现的时刻就是首字时刻。思考分片也算 —— 服务商把思考 token
        // 计入输出，它占的时间必须留在分母里，排掉会把生成速度算得虚高。
        if (firstContentNanos == null && chunk.producesOutput()) {
            firstContentNanos = nowNanos()
        }
        val updatedMessage = append(targetMessages.last(), chunk)
        return targetMessages.dropLast(1) + updatedMessage
    }

    private fun append(message: UIMessage, chunk: StreamChunk): UIMessage = with(message) {
        when (chunk) {
            is StreamChunk.TextStart -> {
                if (chunk.id in textPartIndexes) this
                else copy(parts = parts + UIMessagePart.Text("", chunk.metadata)).also {
                    textPartIndexes[chunk.id] = parts.size
                }
            }
            is StreamChunk.TextDelta -> {
                val index = textPartIndexes[chunk.id]
                // 容忍 Provider 未发送 Start：首次收到 Delta 时直接创建对应 part。
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Text) {
                    copy(parts = parts + UIMessagePart.Text(chunk.text, chunk.metadata)).also {
                        textPartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val text = get(index) as UIMessagePart.Text
                        set(
                            index, text.copy(
                                text = text.text + chunk.text,
                                metadata = chunk.metadata ?: text.metadata,
                            )
                        )
                    })
                }
            }

            is StreamChunk.TextEnd -> this.also { textPartIndexes.remove(chunk.id) }
            is StreamChunk.ReasoningStart -> {
                if (chunk.id in reasoningPartIndexes) this
                else copy(parts = parts + UIMessagePart.Reasoning(
                    reasoning = "",
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = chunk.metadata,
                    reasoningType = chunk.reasoningType,
                )).also { reasoningPartIndexes[chunk.id] = parts.size }
            }

            is StreamChunk.ReasoningDelta -> {
                val index = reasoningPartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    copy(parts = parts + UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )).also { reasoningPartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val reasoning = get(index) as UIMessagePart.Reasoning
                        set(index, reasoning.copy(
                            reasoning = reasoning.reasoning + chunk.text,
                            metadata = chunk.metadata ?: reasoning.metadata,
                            reasoningType = chunk.reasoningType,
                        ))
                    })
                }
            }

            is StreamChunk.ReasoningEnd -> {
                val index = reasoningPartIndexes.remove(chunk.id)
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) this
                else copy(parts = parts.toMutableList().apply {
                    val reasoning = get(index) as UIMessagePart.Reasoning
                    set(index, reasoning.copy(
                        finishedAt = Clock.System.now(),
                        metadata = chunk.metadata ?: reasoning.metadata,
                    ))
                })
            }

            is StreamChunk.ToolCallStart -> {
                if (parts.any { it is UIMessagePart.Tool && it.toolCallId == chunk.id }) this
                else copy(parts = parts + UIMessagePart.Tool(
                    toolCallId = chunk.id,
                    toolName = chunk.toolName,
                    input = "",
                    metadata = chunk.metadata,
                ))
            }

            is StreamChunk.ToolCallDelta -> copy(parts = parts.map { part ->
                // 工具调用可以并行生成，通过 toolCallId 而不是 part 位置识别目标。
                if (part is UIMessagePart.Tool && part.toolCallId == chunk.id) {
                    part.copy(
                        toolName = part.toolName + chunk.toolNameDelta,
                        input = part.input + chunk.inputDelta,
                        metadata = chunk.metadata ?: part.metadata,
                    )
                } else part
            })

            is StreamChunk.ToolCallEnd -> this
            is StreamChunk.ServerToolStart -> {
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input,
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = chunk.metadata,
                    ))
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val tool = get(index) as UIMessagePart.ServerTool
                        set(index, tool.copy(
                            toolName = chunk.toolName.ifBlank { tool.toolName },
                            input = chunk.input ?: tool.input,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        ))
                    })
                }
            }

            is StreamChunk.ServerToolInputDelta -> {
                val buffer = serverToolInputBuffers.getOrPut(chunk.id) { StringBuilder() }
                buffer.append(chunk.inputDelta)
                updateServerTool(chunk.id) { tool ->
                    tool.copy(metadata = mergeMetadata(tool.metadata, chunk.metadata))
                }
            }

            is StreamChunk.ServerToolInputEnd -> {
                val input = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                if (input == null) this else updateServerTool(chunk.id) { it.copy(input = input) }
            }

            is StreamChunk.ServerToolEnd -> {
                val bufferedInput = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = "",
                        input = chunk.input ?: bufferedInput,
                        output = chunk.output,
                        status = chunk.status,
                        metadata = chunk.metadata,
                    ))
                } else {
                    updateServerTool(chunk.id) { tool ->
                        tool.copy(
                            input = chunk.input ?: bufferedInput ?: tool.input,
                            output = chunk.output ?: tool.output,
                            status = chunk.status,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        )
                    }
                }
            }
            is StreamChunk.ImageStart -> {
                if (chunk.id in imagePartIndexes) this
                else copy(parts = parts + UIMessagePart.Image(
                    url = "data:${chunk.mimeType};base64,",
                    metadata = chunk.metadata,
                )).also { imagePartIndexes[chunk.id] = parts.size }
            }

            is StreamChunk.ImageDelta -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(parts = parts + UIMessagePart.Image(chunk.data, chunk.metadata)).also {
                        imagePartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        set(index, image.copy(
                            url = image.url + chunk.data,
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageSnapshot -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(
                        parts = parts + UIMessagePart.Image(
                            url = "data:image/png;base64,${chunk.data}",
                            metadata = chunk.metadata,
                        )
                    ).also { imagePartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        // Snapshot 是一张完整的可渲染图片，只替换 data URL 的数据部分；
                        // 与 ImageDelta 不同，它不会把数据追加到上一帧之后。
                        val dataUrlPrefix = image.url.substringBefore(",").takeIf { it.startsWith("data:") }
                            ?: "data:image/png;base64"
                        set(index, image.copy(
                            url = "$dataUrlPrefix,${chunk.data}",
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageEnd -> this.also { imagePartIndexes.remove(chunk.id) }
            is StreamChunk.Annotations -> copy(annotations = (annotations + chunk.annotations).distinct())
            // v297：本轮用量另存一份（同轮内多个 usage 分片按水位互相覆盖），
            // 上层每轮结束读 reportedUsage 累加「真实消耗」；消息上的 usage 仍是水位口径。
            is StreamChunk.Usage -> {
                roundUsage = roundUsage.merge(chunk.usage)
                copy(usage = usage.merge(chunk.usage))
            }
            is StreamChunk.Finish -> copy(
                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                // v221：保留 Provider 的结束原因，供子代理/圆桌识别 length/max_tokens 截断
                finishReason = chunk.finishReason,
                // v269：保留「流结束时从未收到协议终止信号」这一事实，供上层识别无声截断
                truncatedWithoutSentinel = chunk.terminatedWithoutSentinel,
            ).finishReasoning().also {
                // v303：本轮「写完」的时刻
                endNanos = nowNanos()
                // Finish 同时结束尚未显式结束的 reasoning，并释放本次响应流的索引状态。
                textPartIndexes.clear()
                reasoningPartIndexes.clear()
                imagePartIndexes.clear()
                serverToolInputBuffers.clear()
            }
        }
    }

    private fun UIMessage.updateServerTool(
        id: String,
        transform: (UIMessagePart.ServerTool) -> UIMessagePart.ServerTool,
    ): UIMessage = copy(parts = parts.map { part ->
        if (part is UIMessagePart.ServerTool && part.toolCallId == id) transform(part) else part
    })
}

private fun parseServerToolJson(value: String) = runCatching {
    json.parseToJsonElement(value)
}.getOrElse { JsonPrimitive(value) }

private fun mergeMetadata(old: JsonObject?, new: JsonObject?): JsonObject? = when {
    old == null -> new
    new == null -> old
    else -> JsonObject(old + new)
}

/**
 * v303：一轮请求的耗时切分（毫秒）。
 *
 * 为什么拆两段：业界算「每秒多少 token」分两派 —— 一派只看「模型真正在吐字」的时间
 * （LM Studio、Cherry Studio：分母扣掉首字等待），另一派看「这次请求等了多久」
 * （Claude Code 插件、ccstatusline：分母含首字等待）。两个数回答的是不同问题，所以都留。
 * 两派都**不含工具执行时间与用户审批等待**（工具跑在两次请求之间，天然不在任何一轮里）。
 */
data class RoundTiming(
    /** 从请求发出到本轮结束（含首字等待）。 */
    val responseMs: Long,
    /** 从吐出第一个内容分片到本轮结束；一个内容都没吐时为 null。 */
    val generationMs: Long?,
)

/**
 * v303：这个分片算不算「模型吐出来的内容」。
 *
 * 思考分片也算（服务商把思考 token 计入输出，时间必须留在分母里，否则速度虚高）；
 * 工具调用参数、图像同理 —— 它们都计入输出。只带用量或收尾信息的分片一律不算。
 */
private fun StreamChunk.producesOutput(): Boolean = when (this) {
    is StreamChunk.TextDelta,
    is StreamChunk.ReasoningDelta,
    is StreamChunk.ToolCallDelta,
    is StreamChunk.ServerToolInputDelta,
    is StreamChunk.ImageDelta,
    is StreamChunk.ImageSnapshot,
    -> true

    else -> false
}

fun List<UIMessage>.handleTextGenerationResult(
    result: TextGenerationResult,
    model: Model? = null,
): List<UIMessage> {
    require(isNotEmpty()) { "messages must not be empty" }
    val incoming = result.message.copy(
        modelId = model?.id,
        usage = result.usage,
        finishReason = result.finishReason,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ).finishReasoning()
    return if (last().role != incoming.role) {
        this + incoming
    } else {
        dropLast(1) + last().appendMessage(incoming).copy(
            modelId = model?.id ?: last().modelId,
            usage = last().usage.merge(result.usage ?: TokenUsage()),
            finishedAt = incoming.finishedAt,
        ).finishReasoning()
    }
}

private fun UIMessage.appendMessage(delta: UIMessage): UIMessage {
    var newParts = delta.parts.fold(parts) { acc, deltaPart ->
        when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty()) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Text) {
                        acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                    } else {
                        acc + deltaPart
                    }
                }
            }

            // 非流式解析已经产出完整可渲染的 URL(data URI 或 http)，
            // 这里每张图片都是独立的完整图片，不能拼接 URL 或补 data 前缀。
            is UIMessagePart.Image -> acc + deltaPart

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (lastPart is UIMessagePart.Reasoning &&
                        lastPart.reasoningType == deltaPart.reasoningType
                    ) {
                        acc.dropLast(1) + UIMessagePart.Reasoning(
                            reasoning = lastPart.reasoning + deltaPart.reasoning,
                            createdAt = lastPart.createdAt,
                            finishedAt = null,
                            metadata = deltaPart.metadata ?: lastPart.metadata,
                            reasoningType = lastPart.reasoningType,
                        )
                    } else {
                        acc + deltaPart
                    }
                }
            }

            is UIMessagePart.Tool -> {
                if (deltaPart.toolCallId.isBlank()) {
                    val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                    if (lastTool != null) {
                        acc.map { part -> if (part === lastTool) part.merge(deltaPart) else part }
                    } else {
                        acc + deltaPart.copy()
                    }
                } else {
                    val existingPart = acc.find {
                        it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                    } as? UIMessagePart.Tool
                    if (existingPart == null) {
                        acc + deltaPart.copy()
                    } else {
                        acc.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                part.merge(deltaPart)
                            } else {
                                part
                            }
                        }
                    }
                }
            }

            is UIMessagePart.ServerTool -> {
                val existingPart = acc.find {
                    it is UIMessagePart.ServerTool && it.toolCallId == deltaPart.toolCallId
                } as? UIMessagePart.ServerTool
                if (existingPart == null) {
                    acc + deltaPart
                } else {
                    acc.map { part ->
                        if (part is UIMessagePart.ServerTool && part.toolCallId == deltaPart.toolCallId) {
                            part.copy(
                                toolName = deltaPart.toolName.ifBlank { part.toolName },
                                input = deltaPart.input ?: part.input,
                                output = deltaPart.output ?: part.output,
                                status = deltaPart.status,
                                metadata = mergeMetadata(part.metadata, deltaPart.metadata),
                            )
                        } else part
                    }
                }
            }

            else -> acc
        }
    }

    if (parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
        delta.parts.filterIsInstance<UIMessagePart.Reasoning>().isEmpty()
    ) {
        newParts = newParts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(finishedAt = Clock.System.now())
            } else {
                part
            }
        }
    }

    return copy(
        parts = newParts,
        annotations = delta.annotations.ifEmpty { annotations },
    )
}
