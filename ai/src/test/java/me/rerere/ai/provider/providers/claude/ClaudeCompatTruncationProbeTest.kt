package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude（Anthropic Messages）兼容网关 · 截断与空回。
 *
 * 起因（用户真机口径，原话）：
 * ·「claude兼容格式好像有问题，输出会被截断导致空回，查下是不是有这回事」
 * ·「输出上限是无限，开启思考了，没有显示思考框，直接回复被截断了」
 *
 * 本文件先作为**取证**跑过一轮（结果记在 TASKBOARD）：前三条当时是红的，证明缺陷真实存在，
 * v1011 修完转绿；后三条是「现状刻画」，把上游真实形态钉住，防止以后有人把它们当 bug「顺手改」。
 *
 * 覆盖的三条缺陷（都是上游官方 3.1.0 原样存在的写法，不是二改引入）：
 * 1. 解析器只认 SSE 的 `event:` 行，不认数据包里的 `type` —— 网关不贴标签时正文一个字都解析不出来；
 * 2. 对不上号的段直接 `error()` 抛异常，把整条流打死，已写出来的内容一起作废；
 * 3. `error` 帧也只认标签 —— 网关把错误当普通 data 转发时**连报错都被吞**，
 *    于是「空回 + 零报错 + 不触发自动续跑」三件事同时发生。
 *
 * 修法依据（外部结论，均有出处）：Anthropic 官方 SDK 让「事件名」与「包内 type」互相辅
 * （`if is_dict(data) and "type" not in data: data["type"] = sse.event`）；LiteLLM 在缺
 * content_block_start 时补建段并保住那个 delta（PR #24767）；上游 RikkaHub 自己的修复原则是
 * 「异常帧安全跳过，不抛异常中断流式生成，避免已有回复内容丢失」（commit 9a0c8bd）。
 *
 * 安全性证明：本仓库录到的两条真实网关轨迹（OpenRouter、DeepSeek Anthropic 接口）共 150 帧，
 * 「外包装标签」与「包内 type」**逐帧一致**，所以这轮放宽对官方与主流网关是零行为变化。
 */
class ClaudeCompatTruncationProbeTest {

    private fun runEvents(events: List<SseEvent>): Pair<UIMessage, List<StreamChunk>> {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(Model(modelId = "claude-probe"))
        var messages = listOf(UIMessage.user("测试"))
        val chunks = mutableListOf<StreamChunk>()
        events.forEach { event ->
            decoder.accept(event).chunks.forEach { chunk ->
                chunks += chunk
                messages = handler.handle(messages, chunk)
            }
        }
        decoder.onClosed().forEach { chunk ->
            chunks += chunk
            messages = handler.handle(messages, chunk)
        }
        return messages.last() to chunks
    }

    private fun body(type: String, extra: String = ""): String = when {
        extra.isBlank() -> "{\"type\":\"$type\"}"
        else -> "{\"type\":\"$type\",$extra}"
    }

    /**
     * 缺陷 1：Anthropic 官方每帧都带 `event:` 行；部分兼容网关只发 `data:` 行，
     * 于是传输层给到解码器的事件名可能是 null，也可能是字段名本身（我们抓到的
     * OpenRouter 形态就是 `event="data"`）。
     *
     * 期望：事件名不可用时回退到包里的 `type`，正文照常落地。
     */
    @Test
    fun `frames without a usable event name must still decode text via the json type field`() {
        val frames = listOf(
            null,   // 完全没有 event 行
            "data", // 没有 event 行时被记成字段名（OpenRouter 真实轨迹里的形态）
            "message", // 传输层给的默认事件名，也不是 Anthropic 的事件名
        )
        frames.forEach { name ->
            val events = listOf(
                SseEvent(event = name, data = body("message_start", "\"message\":{\"id\":\"msg_1\"}")),
                SseEvent(event = name, data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
                SseEvent(event = name, data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好世界\"}")),
                SseEvent(event = name, data = body("content_block_stop", "\"index\":0")),
                SseEvent(event = name, data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
                SseEvent(event = name, data = body("message_stop")),
            )
            val (message, _) = runEvents(events)
            assertEquals("事件名=$name 时正文被整段丢弃（兼容网关按这个形态发就空回）", "你好世界", message.toText())
            assertEquals("事件名=$name 时结束原因没落地", "end_turn", message.finishReason)
            assertEquals("事件名=$name 时不该被判成断流", false, message.truncatedWithoutSentinel)
        }
    }

    /**
     * 缺陷 1 的另一半：网关连 `content_block_start` 都漏发（LiteLLM 在 Bedrock converse
     * 路径上踩过同一个坑 —— 短回复完全为空、长回复开头几个字被吞）。
     * 期望：按 delta 类型补建这个段，**并且这个 delta 本身不能被丢掉**。
     */
    @Test
    fun `deltas without a start frame must still be kept by lazily opening the block`() {
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_start_missing\"}")),
            // 没有 content_block_start，直接来 delta
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"开头就被吞掉的字\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"和后面的字\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        )
        val (message, _) = runEvents(events)
        assertEquals("漏发开始帧时正文必须完整", "开头就被吞掉的字和后面的字", message.toText())
        assertEquals(1, message.parts.filterIsInstance<UIMessagePart.Text>().size)
    }

    /**
     * 缺陷 2：多出一个对不上号的收尾帧（网关重复发、或开始帧本来就漏了）。
     * 旧写法 `error("Unknown content block index")` 会让整条流抛异常，**已经写出来的正文一起作废**。
     * 期望：忽略这一帧，已落地的正文保留（照上游官方 9a0c8bd 那条修复的原则：异常帧安全跳过）。
     */
    @Test
    fun `stray content_block_stop for an unknown index must not abort the stream`() {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(Model(modelId = "claude-probe"))
        var messages = listOf(UIMessage.user("测试"))
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_3\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"已经写出来的正文\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":7")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        )
        events.forEachIndexed { i, event ->
            try {
                decoder.accept(event).chunks.forEach { chunk -> messages = handler.handle(messages, chunk) }
            } catch (e: Throwable) {
                fail("第 ${i + 1} 帧就让整条流抛了：${e.message}")
            }
        }
        assertEquals("多余收尾帧之后正文应保留", "已经写出来的正文", messages.last().toText())
    }

    /**
     * 没有开始帧的**工具段**：我们无从知道工具名，只能跳过这一段 —— 但绝不许把整条流打死，
     * 同一条消息里别的正文段必须照常落地。（这条是防止「放宽」被写成「什么都硬兜」。）
     */
    @Test
    fun `tool delta without a start frame is skipped without killing the sibling text block`() {
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_tool\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"正文还在\"}")),
            // 工具段没有开始帧，只有 input_json_delta
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":3,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":1}\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":3")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        )
        val (message, _) = runEvents(events)
        assertEquals("同消息里的正文不能被工具段的异常形态带走", "正文还在", message.toText())
    }

    /**
     * 缺陷 3：错误帧没有事件名（部分网关把 error 当普通 data 转发）。
     * 现状后果：错误被吞 → 空回 + 零报错 + 自动续跑不触发（用户报的就是这个）。
     */
    @Test
    fun `error frame carried without the event name must surface instead of going silent`() {
        val errorData = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
        listOf(null, "data").forEach { name ->
            val thrown = try {
                ClaudeStreamDecoder().accept(SseEvent(event = name, data = errorData))
                null
            } catch (e: Throwable) {
                e
            }
            assertNotNull("事件名=$name 时错误帧被静默吞掉了", thrown)
            assertTrue(
                "错误原文要能带出去（用户要看得见是哪里坏了）",
                thrown?.message?.contains("Overloaded") == true,
            )
        }
    }

    /**
     * 审查位 P1 抓到的一半：网关把错误当普通 data 转发时，载荷常常是
     * `{"error":{...}}` 而且**连 type 都没有**。旧写法（以及只做「包内 type 回退」的修法）
     * 仍然会把这一帧静默丢掉 —— 同仓 OpenAI 通道早就「见 error 字段就抛」
     *（ChatCompletionsStreamDecoder.kt:46），这里必须与它同口径。
     */
    @Test
    fun `error payload without any type field must still surface`() {
        val errorData = "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
        listOf(null, "data", "content_block_delta", "message_delta").forEach { name ->
            val thrown = try {
                ClaudeStreamDecoder().accept(SseEvent(event = name, data = errorData))
                null
            } catch (e: Throwable) {
                e
            }
            assertNotNull("事件名=$name 时不带 type 的错误载荷被静默丢掉了", thrown)
            assertTrue(
                "错误原文要能带出去",
                thrown?.message?.contains("Overloaded") == true,
            )
        }
    }

    /**
     * 审查位 P1 抓到的另一半：放宽之后「认不出的帧」变成了**安静地丢**。
     * 没有开始帧的工具段就是这一类 —— 我们无从知道工具名，只能丢，但必须留痕，
     * 否则下次用户再报空回，我们连「网关有没有发过我们看不懂的东西」都不知道。
     */
    @Test
    fun `skipped frames must be counted so a silent drop still leaves evidence`() {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(Model(modelId = "claude-probe"))
        var messages = listOf(UIMessage.user("测试"))
        listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_skip\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"正文保住\"}")),
            // ① 没有开始帧的工具段：补不出来 → 跳过并计数
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":3,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}")),
            // ② 对不上号的收尾帧 → 忽略并计数
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":9")),
            // ③ 官方也不认识的 delta 类型 → 计数
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"brand_new_delta\",\"text\":\"x\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        ).forEach { event ->
            decoder.accept(event).chunks.forEach { chunk -> messages = handler.handle(messages, chunk) }
        }
        assertEquals("正文不能被跳过的帧带走", "正文保住", messages.last().toText())
        // 二审收紧：审查位指出 >= 3 太松（多计也会绿）。这里正好三帧认不出来，必须精确。
        assertEquals(
            "必须恰好计到三帧：无开始帧的工具段 / 对不上号的收尾帧 / 不认识的 delta 类型",
            3,
            decoder.skippedFrameCount,
        )
        assertNotNull("还要留下最后一条原因，便于定位", decoder.lastSkipReason)
        assertTrue(
            "最后一条原因要能看出是哪一类帧（实际=${decoder.lastSkipReason}）",
            decoder.lastSkipReason?.contains("不认识的 delta 类型") == true,
        )
    }

    /**
     * 反向保护：官方形态（每帧都带正确事件名）的行为必须一字不变。
     * 用「事件名与包内 type 故意矛盾」来证明**事件名仍然优先**。
     */
    @Test
    fun `a usable event name still takes precedence over the json type field`() {
        val events = listOf(
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            // 事件名说是 message_delta（不该建段），包里 type 故意写 content_block_delta
            SseEvent(event = "message_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"不该出现\"}")),
        )
        val (message, _) = runEvents(events)
        assertEquals("事件名可用时必须仍按事件名分派", "", message.toText())
    }

    /**
     * 现状刻画 1：Anthropic 的时序是 message_delta（带 stop_reason）排在 message_stop 之前，
     * 也就是**正文与工具段全部发完**才给结束原因。所以「收到结束原因但没收到 message_stop」
     * 时内容其实是齐的，不续跑是对的 —— 这条钉住那个结论，防止有人把它「修」成每轮白烧一次。
     */
    @Test
    fun `characterization - break after message_delta leaves content complete and a non-blank finish reason`() {
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_4\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"已经写完\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            // 没有 message_stop，直接断流
        )
        val (message, _) = runEvents(events)
        assertEquals("断流标记为真", true, message.truncatedWithoutSentinel)
        assertEquals("结束原因非空 → 上层「无声截断」判据落空（按官方时序这是对的）", "end_turn", message.finishReason)
        assertEquals("正文完整", "已经写完", message.toText())
    }

    /**
     * 现状刻画 2：思考吃满 max_tokens —— Anthropic 官方文档与 Claude Code 官方 issue
     * （anthropics/claude-code#68882）的形态：HTTP 200、stop_reason=max_tokens、
     * content 里只有一个 thinking 块、没有 text 块。
     * 这条固定「有思考、零正文、结束原因是 max_tokens」的事实，供上层截断判定与提示分类对照。
     */
    @Test
    fun `characterization - thinking consuming the whole budget yields a message with zero text`() {
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_5\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想了很久但配额用完了\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"max_tokens\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        )
        val (message, _) = runEvents(events)
        assertTrue("这种形态没有正文", message.toText().isEmpty())
        assertEquals("结束原因要落到 max_tokens，上层才认得出截断", "max_tokens", message.finishReason)
        assertEquals(1, message.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
    }

    /**
     * 现状刻画 3：`display` 在较新模型上默认 `omitted` —— 思考块只带 signature、`thinking`
     * 字段是空串。这正是用户说的「开启思考了却没有显示思考框」：思考块存在，但没有可见文字。
     */
    @Test
    fun `characterization - omitted thinking leaves nothing visible at all`() {
        val events = listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_6\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig_abc\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        )
        val (message, _) = runEvents(events)
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().singleOrNull()
        assertNotNull("思考块应当存在（只是没有可见文字）", reasoning)
        assertEquals("omitted 形态下思考文字是空串", "", reasoning?.reasoning)
        assertTrue("正文为空", message.toText().isEmpty())
    }

    /** 轨迹自检：包里 type 与事件名一致是这轮放宽「零影响」的前提，这里把它钉成断言。 */
    @Test
    fun `recorded real traces always carry a json type matching the event name`() {
        traceDirs().forEach { dir ->
            val events = java.io.File(dir, "events.jsonl")
            if (!events.exists()) return@forEach
            events.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val obj = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                }.getOrNull() ?: return@forEach
                val name = obj["event"]?.jsonPrimitive?.contentOrNull
                val data = obj["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (data == "[DONE]") return@forEach // OpenRouter 自己多塞的尾帧，非 Anthropic 协议
                val inner = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject }
                    .getOrNull() ?: return@forEach
                val innerType = inner["type"]?.jsonPrimitive?.contentOrNull
                assertEquals("${dir.name}: 外包装标签与包内 type 必须一致", name, innerType)
            }
        }
    }

    /** 二审 P2：官方本来就有的 citations_delta 不该被当成「网关报文异常」。 */
    @Test
    fun `citations delta is recognised and not counted as a skipped frame`() {
        val decoder = ClaudeStreamDecoder()
        listOf(
            SseEvent(event = "message_start", data = body("message_start", "\"message\":{\"id\":\"msg_cit\"}")),
            SseEvent(event = "content_block_start", data = body("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"正文\"}")),
            SseEvent(event = "content_block_delta", data = body("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"web_location\",\"url\":\"https://x\"}}")),
            SseEvent(event = "content_block_stop", data = body("content_block_stop", "\"index\":0")),
            SseEvent(event = "message_delta", data = body("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\"}")),
            SseEvent(event = "message_stop", data = body("message_stop")),
        ).forEach { decoder.accept(it) }
        assertEquals("引用帧是官方形态，不该计入跳帧", 0, decoder.skippedFrameCount)
    }

    /**
     * 二审 P1 的回归网（两个审查位独立抓到）：**真实官方轨迹回放后，跳帧计数必须为 0**。
     *
     * 之前那版写法用 `blocks.remove(index)?.let{...} ?: skipFrame(...)`，把「段找到了、只是本来
     * 就没有收尾 chunk 可发」也算成「对不上号」。官方的服务端工具**结果**块
     *（web_search_tool_result）正是这种：开帧时已经发过 ServerToolEnd，endBlock 按设计返回 null。
     * 后果是「凡用过 Claude 内置搜索的回复，收尾日志都撒谎说内容可能有缺失」——
     * 而这套计数唯一的用途就是排查空回，它一上来就在正常流量上骗人。
     *
     * 仓库里就带着证据：deepseek-anthropic-tool/events.jsonl 里 index=4 的
     * web_search_tool_result 开帧，紧跟一条同 index 的 content_block_stop。
     */
    @Test
    fun `replaying every recorded real claude trace must not skip a single frame`() {
        val dirs = traceDirs().filter { java.io.File(it, "events.jsonl").exists() }
        assertTrue("至少要能回放两条真实 Claude 轨迹（OpenRouter + DeepSeek Anthropic 接口）", dirs.size >= 2)
        dirs.forEach { dir ->
            val decoder = ClaudeStreamDecoder()
            readTrace(dir).forEach { event ->
                runCatching { decoder.accept(event) } // 这些轨迹里没有 error 帧；真抛了也不该污染计数
            }
            runCatching { decoder.onClosed() }
            assertEquals(
                "${dir.name}: 合规报文不该有任何跳帧（实际=${decoder.lastSkipReason}）",
                0,
                decoder.skippedFrameCount,
            )
        }
    }

    private fun readTrace(dir: java.io.File): List<SseEvent> =
        java.io.File(dir, "events.jsonl").readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
            val data = obj["data"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SseEvent(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                event = obj["event"]?.jsonPrimitive?.contentOrNull,
                data = data,
            )
        }

    private fun traceDirs(): List<java.io.File> {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").exists() }
        val dir = if (root != null) {
            java.io.File(root, "ai/src/test/resources/stream-traces/generated/claude")
        } else {
            java.io.File("src/test/resources/stream-traces/generated/claude")
        }
        return dir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    }
}
