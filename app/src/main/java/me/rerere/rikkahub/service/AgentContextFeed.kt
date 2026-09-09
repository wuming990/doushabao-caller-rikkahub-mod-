package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * v239：把主对话原文打包成子代理能读的一段纯文本。
 *
 * ## 为什么要有它
 *
 * 用户原话：
 * > 「我不是让你像圆桌模式那样给全部上文么，打回去重做，
 * >  可以自己选择给不给全部上文，但不能做不到给不了全部上文。」
 *
 * 在此之前，子代理的 `context_summary` 只能由主模型**手打转述**：主模型没有任何
 * 工具能导出自己的对话原文，所以「完整上文」这件事在能力上根本做不到，
 * 每次都只能给一小段摘要。这个文件补上那个缺口 —— 主模型现在可以选择
 * 「这次把主对话原文整份带上」，也可以照旧只给一小段。选择权在主模型，
 * 但**「想给却给不了」这种情况不再存在**。
 *
 * ## 为什么放在 service 包而不是 agent 包
 *
 * 有一条架构红线（`AgentDependencyBoundaryTest` 守着）：agent 包不许引用
 * `ConversationSession` / `ChatService` / `AppDatabase` / `MessageNode`。
 * 主对话原文只有主会话侧拿得到，所以组装必须在这一侧完成，
 * agent 包那边只收一个字符串。红线不动，门禁不红。
 *
 * ## 刻意的取舍
 *
 * - **默认不带思考过程**：那部分往往比正文还长，且对「知道背景」没有帮助。
 *   带不带由参数决定，省掉多少字会写在文本里，用户和子代理都看得见。
 * - **图片 / 音视频 / 附件只写占位**：base64 图片一张就能吃掉几十万字符，
 *   把额度烧光还挤掉真正有用的内容。
 * - **工具调用只留摘要**：入参与结果各截一段。工具结果动辄几万字，
 *   全带上等于让子代理替主模型重读一遍所有工具输出。
 * - **超长时掐中间、保头尾**：开头是任务目标，结尾是最新指令，这两处最重要；
 *   中间省了多少字会明写出来，不做无声截断。
 */
object AgentContextFeed {

    /** 默认上限。既是成本闸门，也防止廉价模型的上下文窗口被一次塞爆。 */
    const val DEFAULT_MAX_CHARS = 120_000

    /** 允许的最小上限：再小就连头尾都放不下，没有意义。 */
    const val MIN_MAX_CHARS = 2_000

    /** 允许的最大上限：再大只会把钱烧在噪音上。 */
    const val HARD_MAX_CHARS = 400_000

    /** 这段文本的第一行。子代理提示词与测试都靠它认出「这是主对话原文」。 */
    const val HEADER = "===== 主对话完整记录（主代理直接下传）====="

    /** 子代理线程区块的标题。 */
    const val THREADS_HEADER = "===== 本会话已派出的子代理 ====="

    /** 中间被掐掉时的标记前缀。 */
    const val OMISSION_PREFIX = "⟪中间省略"

    /** 单条工具调用的入参 / 结果各自最多带这么多字。 */
    private const val TOOL_TEXT_LIMIT = 800

    /** 带思考过程时，单条思考最多带这么多字。 */
    private const val REASONING_LIMIT = 1_200

    /** 每条子代理线程的结论最多带这么多字。 */
    private const val THREAD_CONCLUSION_LIMIT = 1_500

    /**
     * 一条子代理线程的摘要。
     *
     * 刻意不直接收 `AgentThread`：那是 agent 包的类型，这里用一个本地小结构，
     * 既避开包依赖，也让这个对象可以在单元测试里随手造出来。
     */
    data class ThreadDigest(
        val id: String,
        val role: String,
        val status: String,
        val activeModelId: String? = null,
        val taskPreview: String = "",
        val conclusion: String = "",
    )

    /**
     * 把主对话渲染成一段纯文本。
     *
     * @param messages 主对话的全部消息（按时间顺序）
     * @param threads 本会话已派出的子代理线程摘要；空列表则不输出那一段
     * @param maxChars 整段文本的字符上限，会被夹进 [MIN_MAX_CHARS]..[HARD_MAX_CHARS]
     * @param includeReasoning 是否带上模型的思考过程
     */
    fun render(
        messages: List<UIMessage>,
        threads: List<ThreadDigest> = emptyList(),
        maxChars: Int = DEFAULT_MAX_CHARS,
        includeReasoning: Boolean = false,
    ): String {
        val limit = maxChars.coerceIn(MIN_MAX_CHARS, HARD_MAX_CHARS)
        val header = buildHeader(messages.size, includeReasoning)
        val threadsBlock = renderThreads(threads)
        val body = renderBody(messages, includeReasoning)

        // 头部说明与子代理区块永远完整保留（它们短、而且是最该被读到的部分），
        // 只有主对话正文参与裁剪。
        val fixedCost = header.length + threadsBlock.length
        val bodyBudget = (limit - fixedCost).coerceAtLeast(MIN_MAX_CHARS / 2)
        return header + clip(body, bodyBudget) + threadsBlock
    }

    private fun buildHeader(messageCount: Int, includeReasoning: Boolean): String = buildString {
        appendLine(HEADER)
        appendLine("这是主代理与用户之间的原始往来，共 $messageCount 条消息，给你当背景用。")
        appendLine("读法（很重要）：")
        appendLine("1. **任务书优先**。上下文与任务书冲突的地方，一律以任务书为准；")
        appendLine("2. 里面可能有**已经被推翻的旧结论**和走过的弯路，不要照着做；")
        appendLine("3. 不要复述这段上下文，也不要把里面的旧要求当成你这次的任务。")
        if (!includeReasoning) {
            appendLine("4. 模型的思考过程已省略，只保留正文与工具往来。")
        }
        appendLine()
    }

    private fun renderBody(messages: List<UIMessage>, includeReasoning: Boolean): String =
        buildString {
            messages.forEachIndexed { index, message ->
                appendLine("--- 第 ${index + 1} 条 · ${roleLabel(message.role)} ---")
                message.parts.forEach { part -> appendPart(part, includeReasoning) }
                appendLine()
            }
        }

    private fun StringBuilder.appendPart(part: UIMessagePart, includeReasoning: Boolean) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) appendLine(part.text)

            is UIMessagePart.Reasoning -> {
                val text = part.reasoning.trim()
                if (text.isEmpty()) return
                if (includeReasoning) {
                    appendLine("〔思考〕" + truncate(text, REASONING_LIMIT))
                } else {
                    appendLine("〔思考过程 ${text.length} 字，未下传〕")
                }
            }

            is UIMessagePart.Tool -> {
                appendLine("〔工具 ${part.toolName}〕入参：" + truncate(oneLine(part.input), TOOL_TEXT_LIMIT))
                if (part.output.isNotEmpty()) {
                    val outputText = part.output
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    appendLine(
                        "〔工具 ${part.toolName} 结果〕" +
                            truncate(oneLine(outputText), TOOL_TEXT_LIMIT)
                    )
                }
            }

            // 二进制附件一律只写占位：一张 base64 图片就能吃掉几十万字符
            is UIMessagePart.Image -> appendLine("〔图片附件，未下传〕")
            is UIMessagePart.Video -> appendLine("〔视频附件，未下传〕")
            is UIMessagePart.Audio -> appendLine("〔音频附件，未下传〕")
            is UIMessagePart.Document -> appendLine("〔文件附件：${part.fileName}，未下传〕")
            else -> Unit
        }
    }

    private fun renderThreads(threads: List<ThreadDigest>): String {
        if (threads.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine(THREADS_HEADER)
            appendLine("这些是本会话里已经派出去的子代理，含它们各自的最新结论。")
            threads.forEachIndexed { index, thread ->
                appendLine()
                append("[${index + 1}] ${thread.role} · ${thread.status}")
                thread.activeModelId?.takeIf { it.isNotBlank() }?.let { append(" · 模型 $it") }
                appendLine()
                if (thread.taskPreview.isNotBlank()) {
                    appendLine("  任务：" + oneLine(thread.taskPreview))
                }
                if (thread.conclusion.isNotBlank()) {
                    appendLine("  结论：" + truncate(thread.conclusion.trim(), THREAD_CONCLUSION_LIMIT))
                }
            }
        }
    }

    /**
     * 超长时掐掉中间，头尾各留一段。
     *
     * 头尾按上限的 40% 各留一份，剩下 20% 足够放省略标记，因此结果长度不会超过 [maxChars]。
     * 省了多少字明写在标记里 —— 无声截断会让子代理误以为自己看到了全部。
     */
    private fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = maxChars * 2 / 5
        val tail = maxChars * 2 / 5
        val omitted = text.length - head - tail
        val mark = "\n\n$OMISSION_PREFIX $omitted 字（原文共 ${text.length} 字，" +
            "超过本次上限 $maxChars 字，已保留开头与结尾）⟫\n\n"
        val markBudget = maxChars - head - tail
        return text.take(head) + mark.take(markBudget) + text.takeLast(tail)
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "…（另有 ${text.length - limit} 字）"

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun roleLabel(role: MessageRole): String = when (role) {
        MessageRole.USER -> "用户"
        MessageRole.ASSISTANT -> "主代理"
        MessageRole.SYSTEM -> "系统提示"
        MessageRole.TOOL -> "工具回执"
    }
}
