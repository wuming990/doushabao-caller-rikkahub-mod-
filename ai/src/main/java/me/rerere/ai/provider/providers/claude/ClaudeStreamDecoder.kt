package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null

    /**
     * v1012：结束原因改为对外可读（仍只由本类写入）。
     *
     * 原来它是 private：整条流里唯一出口是 StreamChunk.Finish，往下交给消息处理。
     * 但 refusal 要在「流刚收尾、还没交给上层」这一刻决定要不要换一把密钥重发，
     * 那个位置在 Provider 的 onEvent 里，拿不到已经发出去的 chunk，只能从这里取。
     */
    var finishReason: String? = null
        private set

    /**
     * v1012：上游 `stop_details` 折成的人话说明（只有 refusal 才会填）。
     * 用途同上 —— 由 Provider 包进 [ClaudeRefusalException] 交给界面显示。
     */
    var refusalDetail: String? = null
        private set

    /**
     * v1013：`stop_details.category` 的**原文**（reasoning_extraction / cyber / bio /
     * frontier_llm / general_harms，也可能为 null）。
     *
     * 为什么人话那份之外还要留一份原文：Provider 要**按分类决定下一步做什么** ——
     * reasoning_extraction 值得退掉「索要思考摘要」再试一次，其余分类是内容问题、
     * 退参数没用。展示给人看的始终是人话那份，不拿分类去猜行为。
     */
    var refusalCategory: String? = null
        private set

    private var finished = false

    /**
     * v1011（审查位 P1 抓到）：这一条流里被跳过的帧数。
     *
     * 放宽之前，对不上号的段是「响亮地抛异常、整条流打死」；放宽之后变成「安静地跳过」。
     * 后者对用户更好（正文保住了），但**不能一点痕迹都不留** —— 否则下一次再有人报「空回」，
     * 我们连「网关有没有发过我们认不出的东西」都不知道。没有开始帧的工具段就属于这一类：
     * 我们无从知道工具名，只能丢，而同一条消息里有正文时上层也不会报错。
     * 由 ClaudeProvider 在流结束时打一条日志（ai 模块的单测不开 returnDefaultValues，
     * 解码器里直接调用 android.util.Log 会让单测崩，所以日志挂在这条流的使用方）。
     */
    var skippedFrameCount: Int = 0
        private set

    private fun skipFrame(reason: String, event: SseEvent, detail: String? = null) {
        skippedFrameCount++
        // 只在内存里留最近一条原因，供 Provider 收尾时带上（不做环形缓冲，够用即可）
        lastSkipReason = "$reason${detail?.let { "（$it）" }.orEmpty()}，末帧事件名=${event.event ?: "<无>"}"
    }

    /** 最后一次跳帧的原因，只用于诊断日志。 */
    var lastSkipReason: String? = null
        private set

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val dataJson = json.parseToJsonElement(event.data).jsonObject
        // v1011：事件名不可用时回退到包内的 type（见 resolveEventName）。
        val eventName = resolveEventName(event, dataJson)
        // v1011（审查位 P1 抓到）：错误帧**不能只靠事件名或包内 type 认**。不少网关把错误当
        // 普通 data 转发，载荷就是 {"error":{...}} 而且不带 type —— 那种帧整帧被静默丢掉，
        // 正好复现本轮要消灭的「空回 + 零报错 + 不触发续跑」。同仓 OpenAI 通道早就是
        // 「见 error 字段就抛」（ChatCompletionsStreamDecoder.kt:46），这里与它对齐。
        // 零误伤证明：本仓库录到的 1620 帧真实轨迹（Claude / OpenAI / Google 各通道）里，
        // 没有任何一帧正常数据带顶层 error 键。
        val errorPayload = dataJson["error"]?.takeUnless { it is JsonNull }
        if (eventName == "error" || errorPayload != null) {
            throw (errorPayload ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
        }
        dataJson["delta"]?.jsonObject?.let { delta ->
            delta["stop_reason"]?.jsonPrimitive?.contentOrNull?.let {
                finishReason = it
            }
            // v1012：上游 stop_details 的分类与说明。非 refusal 时上游会把这个字段
            // **显式发成 null**（真实轨迹实锤：`"stop_details":null`），所以必须用 `as?` 而不是
            // `?.jsonObject` —— 后者对 JsonNull 直接抛 IllegalArgumentException。
            // （v1011 给 error 字段踩过同一个坑，当时写了 takeUnless { it is JsonNull }。）
            (delta["stop_details"] as? JsonObject)?.let { details ->
                claudeRefusalDetail(details)?.let { refusalDetail = it }
                // v1013：只在拿到非空值时写 —— 上游可能发多条 message_delta，
                // 别让后面那条的 null 把已经读到的分类覆盖掉。
                details["category"]?.jsonPrimitive?.contentOrNull?.let { refusalCategory = it }
            }
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (eventName == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                // v1011（审查位 P2）：这个 index 如果已经被 lazilyOpenBlock 补建过（网关把
                // delta 先发、开始帧迟到或漏发），必须**沿用已经建好的 id**。StreamChunkHandler
                // 是按 id 找 part 的，id 换掉会把同一段正文拆成两段。
                val blockId = blocks[index]?.id
                    ?: contentBlock["id"]?.jsonPrimitive?.contentOrNull
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    }
                    else -> null
                }
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata)
                when (kind) {
                    "text" -> add(StreamChunk.TextStart(blockId))
                    "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                    "tool_use" -> {
                        add(StreamChunk.ToolCallStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        val input = contentBlock["input"]?.jsonObject
                        if (input != null && input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(
                                id = blockId,
                                inputDelta = json.encodeToString(input),
                            ))
                        }
                    }
                    else -> if (kind.isClaudeServerToolUseType()) {
                        add(StreamChunk.ServerToolStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = contentBlock["input"],
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = contentBlock,
                                callIndex = index,
                            ).toMetadata(),
                        ))
                    } else if (kind.isClaudeServerToolResultType()) {
                        val output = contentBlock["content"]
                        add(StreamChunk.ServerToolEnd(
                            id = blockId,
                            output = output,
                            status = if (output.isClaudeServerToolError()) {
                                ServerToolStatus.FAILED
                            } else {
                                ServerToolStatus.COMPLETED
                            },
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = contentBlock,
                                resultIndex = index,
                            ).toMetadata(),
                        ))
                    } else {
                        // v1011 二审补（审查位 P2）：认不出的块类型以前是「既不产出也不留痕」，
                        // 与本轮「跳过的帧必须看得见」不一致。
                        skipFrame("不认识的 block 类型", event, kind)
                    }
                }
            } else if (eventName == "content_block_start") {
                skipFrame("开始帧缺 index 或 content_block", event)
            }

            if (eventName == "content_block_delta" && index != null) {
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                // v1011：网关漏发 content_block_start 时（LiteLLM 踩过同一个坑：短回复完全为空、
                // 长回复开头几个字被吞），按 delta 类型补建这个段，**并且把这个 delta 本身也保住**。
                // 补不出来的段（没有开始帧的 tool_use，我们无从知道工具名）只跳过这一帧 ——
                // 旧写法是 error("Unknown content block index") 直接打死整条流，已经写出来的正文
                // 全部作废，这与上游官方自己那条修复的原则相反（9a0c8bd：异常帧安全跳过，
                // 不抛异常中断流式生成，避免已有回复内容丢失）。
                val block = blocks[index] ?: lazilyOpenBlock(index, deltaType)
                if (block != null) {
                    when (deltaType) {
                        "text_delta" -> add(StreamChunk.TextDelta(
                            block.id,
                            delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        "thinking_delta" -> add(StreamChunk.ReasoningDelta(
                            block.id,
                            delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                            block.metadata,
                        ))
                        "signature_delta" -> {
                            val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.let {
                                ClaudeReasoningMetadata(signature = it).toMetadata()
                            }
                            blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                            add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                        }
                        "input_json_delta" -> {
                            val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (block.kind.isClaudeServerToolUseType()) {
                                add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                            } else {
                                add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                            }
                        }
                        // v1011 二审补（两个审查位都提到）：citations_delta 是**官方就有**的 delta
                        // 类型（引用/文档引用场景）。本项目不渲染引用，收到它本来就没有可产出的
                        // 东西，但那不是「网关报文异常」。以前会落到下面的 else 被计成跳帧，
                        // 于是官方正常回复也会让收尾日志说「内容可能有缺失」—— 在这里显式点名，
                        // 既不产出也不计数。
                        "citations_delta" -> Unit
                        else -> skipFrame("不认识的 delta 类型", event, deltaType)
                    }
                } else {
                    skipFrame("该段没有开始帧、也补不出来（多半是工具段，无从知道工具名）", event, deltaType)
                }
            } else if (eventName == "content_block_delta") {
                skipFrame("delta 帧缺 index", event)
            }

            if (eventName == "content_block_stop" && index != null) {
                // v1011：对不上号的收尾帧（重复发、或开始帧本来就漏了）忽略掉就行，
                // 不再 error() 打死整条流。
                //
                // v1011 二审修正（两个审查位独立抓到）：**不能**写成
                // `blocks.remove(index)?.let { endBlock(it)?.let(::add) } ?: skipFrame(...)` ——
                // 那样会把「段找到了、只是本来就没有收尾 chunk 可发」也算成「对不上号」。
               // 官方的服务端工具**结果**块（web_search_tool_result 等）正是这种：开帧时已经
                // 发过 ServerToolEnd，endBlock 按设计返回 null。用 elvis 混判的后果是
                // 「凡用过 Claude 内置搜索的回复，收尾日志都撒谎说内容可能有缺失」——
                // 而这套计数唯一的用途就是排查空回，一开始就在正常流程上骗人。
                // 证据：真实官方轨迹 events.jsonl:126-127（index=4 的 web_search_tool_result
                // 开帧后紧跟它的 content_block_stop）。
                val closed = blocks.remove(index)
                if (closed == null) {
                    skipFrame("收尾帧对不上号", event, "index=$index")
                } else {
                    endBlock(closed)?.let(::add)
                }
            } else if (eventName == "content_block_stop") {
                skipFrame("收尾帧缺 index", event)
            } else if (eventName == null) {
                // v1011 二审补（审查位 P2）：事件名与包内 type 都认不出来的帧，既不派发也不留痕
                // 就等于「安静地丢」，与本轮「不能一点痕迹都不留」的目标相反。
                skipFrame("事件名与包内 type 都认不出来", event)
            }
        }

        return if (eventName == "message_stop") {
            DecodeResult(chunks + finish(), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    /**
     * v1011：Anthropic 官方每一帧**同时**给「SSE 事件名」和「包内 type」，而且两处内容逐字相同
     * （本项目录到的 OpenRouter 与 DeepSeek Anthropic 接口共 150 帧，已逐帧核对一致）。
     *
     * 但第三方「Claude 兼容」网关里有一类只发 `data:` 行、不贴 `event:` 标签：此时传输层交给
     * 我们的事件名可能是 null，也可能是字段名本身（我们那条 OpenRouter 真实轨迹里，最后多出来的
     * `[DONE]` 帧就被记成 `event="data"`）。旧实现**只比事件名**，标签一对不上就整帧跳过 ——
     * 结果是一个字都解析不出来、连错误都被吞掉、还不算失败，界面上就是「空回 + 零报错」。
     *
     * 事件名认得出来时走原路（官方与主流网关行为逐字不变），认不出来才回退到包内 type。
     */
    private fun resolveEventName(event: SseEvent, dataJson: JsonObject): String? =
        event.event?.takeIf { it in KNOWN_CLAUDE_EVENTS }
            ?: dataJson["type"]?.jsonPrimitive?.contentOrNull

    /**
     * v1011：开始帧漏发时按 delta 类型补建一个段。补不出来（例如没有开始帧的 tool_use，
     * 我们无从知道工具名）返回 null，由调用方跳过这一帧。
     */
    private fun lazilyOpenBlock(index: Int, deltaType: String?): ClaudeStreamBlock? {
        val kind = when (deltaType) {
            "text_delta" -> "text"
            "thinking_delta", "signature_delta" -> "thinking"
            else -> return null
        }
        val blockId = "${responseId ?: "response"}:block-$index"
        return ClaudeStreamBlock(kind, blockId, null).also { blocks[index] = it }
    }

    // v269：Claude 的终止信号是单独的 message_stop 事件，排在正文之后。
    // 走到这里说明连 message_stop 都没收到就断了 —— 正文中途掐断的典型形态。
    override fun onClosed(): List<StreamChunk> = finish(terminatedWithoutSentinel = true)

    private fun finish(terminatedWithoutSentinel: Boolean = false): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.mapNotNull(::endBlock).forEach(::add)
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel, terminatedWithoutSentinel))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): StreamChunk? = when (block.kind) {
        "text" -> StreamChunk.TextEnd(block.id)
        "thinking", "redacted_thinking" -> StreamChunk.ReasoningEnd(block.id, block.metadata)
        "tool_use" -> StreamChunk.ToolCallEnd(block.id)
        else -> if (block.kind.isClaudeServerToolUseType()) {
            StreamChunk.ServerToolInputEnd(block.id)
        } else {
            null
        }
    }

    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
            // v297：思考 token 单列（output_tokens 已含 thinking，只作分解展示，不再相加）
            reasoningTokens = (usageJson["output_tokens_details"] as? JsonObject)
                ?.get("thinking_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
    )
}

/**
 * v1011：Anthropic Messages 流里合法的事件名。事件名落在这个集合外（缺失、被记成字段名、
 * 或网关自造的名字）时，改用包里自己写的 `type` 分派 —— 官方两者一致，所以官方路径零变化。
 */
private val KNOWN_CLAUDE_EVENTS = setOf(
    "message_start",
    "content_block_start",
    "content_block_delta",
    "content_block_stop",
    "message_delta",
    "message_stop",
    "ping",
    "error",
)
