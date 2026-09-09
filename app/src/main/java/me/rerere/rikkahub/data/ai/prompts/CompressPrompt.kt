package me.rerere.rikkahub.data.ai.prompts

/**
 * 照抄 Codex 的 compaction 提示词 (codex-rs/prompts/templates/compact/prompt.md)。
 *
 * Codex 不在提示词里限制摘要长度, 也不给摘要请求设置 maxTokens, 因此这里同样不带
 * {target_tokens} 之类的占位符; 历史内容作为真实对话消息发送, 该提示词只作为最后一条
 * user message 追加。
 */
internal const val DEFAULT_COMPRESS_PROMPT = """You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

Include:
- Current progress and key decisions made
- Important context, constraints, or user preferences
- What remains to be done (clear next steps)
- Any critical data, examples, or references needed to continue

Be concise, structured, and focused on helping the next LLM seamlessly continue the work."""

/**
 * 照抄 Codex 的 summary 前缀 (codex-rs/prompts/templates/compact/summary_prefix.md)。
 * 压缩后的摘要以该前缀开头, 并作为 user message 保存, 供下一轮模型继续工作。
 */
internal const val COMPRESS_SUMMARY_PREFIX =
    "Another language model started to solve this problem and produced a summary of its thinking process. " +
        "You also have access to the state of the tools that were used by that language model. " +
        "Use this to build on the work that has already been done and avoid duplicating work. " +
        "Here is the summary produced by the other language model, use the information in this summary " +
        "to assist with your own analysis:"

/** Codex: COMPACT_USER_MESSAGE_MAX_TOKENS, 压缩后保留的用户消息 token 预算。 */
internal const val COMPRESS_RETAINED_USER_MESSAGE_MAX_TOKENS = 20_000

/** Codex: APPROX_BYTES_PER_TOKEN, token 估算按 UTF-8 字节数除以 4。 */
internal const val COMPRESS_APPROX_BYTES_PER_TOKEN = 4

/**
 * 旧版压缩提示词识别：带占位符({content}/{target_tokens}/{locale}/{additional_context})
 * 或要求以 [Context checkpoint] 开头的旧风格提示词，读取设置时自动重置为 Codex 提示词，
 * 避免新版压缩时占位符不被替换而产生乱码。
 */
internal fun isLegacyCompressPrompt(prompt: String): Boolean = LEGACY_COMPRESS_PROMPT_MARKERS.any {
    prompt.contains(it)
}

/**
 * 组装最终压缩提示词：基础提示词（内置默认或用户自定义覆盖）+
 * 单次压缩的附加说明（最后附加）。空白附加自动忽略。
 */
internal fun buildCompactionPrompt(
    basePrompt: String,
    additionalPrompt: String,
): String = buildString {
    append(basePrompt)
    val additional = additionalPrompt.trim()
    if (additional.isNotBlank()) {
        appendLine()
        appendLine()
        append(additional)
    }
}

private val LEGACY_COMPRESS_PROMPT_MARKERS = listOf(
    "{content}",
    "{target_tokens}",
    "{additional_context}",
    "{locale}",
    "[Context checkpoint]",
)

/**
 * v268：官方旧版压缩提示词（「经典压缩」模式用，逐字取自上游 2.4.15）。
 * 带占位符（{content}/{target_tokens}/{locale}/{additional_context}），由经典模式内部填充。
 * 故意不读 settings.compressPrompt —— 设置里存的是 Codex 提示词（v219 起已迁移），
 * 两者混用会导致占位符不被替换产生乱码。
 */
internal val LEGACY_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
