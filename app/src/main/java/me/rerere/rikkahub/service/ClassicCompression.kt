package me.rerere.rikkahub.service

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.CodexCompaction
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.insertContextCheckpoint

internal enum class ClassicCompressionIssue {
    GENERATING,
    NOTHING_TO_COMPRESS,
    NOT_ENOUGH_MESSAGES,
    PENDING_TOOLS,
    EMPTY_SUMMARY,
    CONVERSATION_CHANGED,
}

internal class ClassicCompressionException(val issue: ClassicCompressionIssue) : IllegalStateException(issue.name)

/**
 * v1032：「用户发言保护」标记行。
 *
 * 官方检查点式压缩会把压缩范围内的用户发言**一起写进摘要**，用户反馈原话被转述走样之后
 * 模型不再照办（智能压缩那条 Codex 路线本来就保留真实用户消息，经典压缩没有，是不一致的空缺）。
 * 开关打开时，把用户发言逐字附在摘要正文末尾。
 *
 * v1033 口径升级为**全量永久逐字**：被压缩掉的原始消息**从来没有被删除**
 * （官方检查点机制只是在历史里插入一条摘要卡片，`Conversation.insertContextCheckpoint` 只插入不删除），
 * 所以每次压缩都能从对话第一条真实用户发言开始逐字重取，连续多次压缩也不会让最早的原话退化成转述。
 * 按用户明确选定「不设上限，全部原话永久逐字留着」，不再做 token 预算截断；
 * 代价是摘要卡片与每次上传的体积随发言量单调增长，这条已写进界面说明文案。
 *
 * 标记写进摘要正文而不只是 metadata：发给模型的是正文文本，metadata 不会被上传。
 * 双语是因为这段文字既给模型读，也会在检查点卡片里给用户看。
 */
internal const val CLASSIC_VERBATIM_USER_MARKER =
    "## 用户原话逐字保留 / Verbatim user messages (not summarized)"

/**
 * v1033：「不设上限」的哨兵值，写成具名常量而不是裸 [Int.MAX_VALUE]。
 * 用户明确选定「全部原话永久逐字留着」，所以经典压缩这条路**故意没有上限**；
 * 谁想重新加上限，必须先拿到用户同意。名字本身就是为了阻止下一个人当成手滑改掉。
 */
internal const val CLASSIC_VERBATIM_NO_LIMIT = Int.MAX_VALUE

/**
 * v1032：生成要追加到摘要正文末尾的「用户原话」段；没有可保留的用户发言时返回**空串**
 * （摘要正文一个字都不加，与关闭开关完全一致）。
 *
 * v1033：**去掉 token 上限**。仍然走 [CodexCompaction.retainedUserMessages] 这条现成路径
 * （把预算传成 [Int.MAX_VALUE]），而不是另写一份提取函数 —— 因为「什么算用户原话」的判据
 * （角色是 USER、去空白、跳过以摘要前缀开头的粘贴内容）必须与智能压缩保持同一份，
 * 两处各写一遍必然长出两种口径。预算参数只在超限时才触发中间截断，
 * 传 [Int.MAX_VALUE] 时 `remaining` 恒为正（要溢出需累计 2^31 token，约 8GB 文本，实际不可达）。
 *
 * 只做**文本级**保护：图片/文件/音视频附件不是文字，逐字抄不出来，仍按旧行为由摘要描述。
 */
internal fun classicVerbatimUserAppendix(messages: List<UIMessage>): String {
    val texts = CodexCompaction.retainedUserMessages(
        messages.filterNot { it.isContextCheckpoint || it.isSynthetic },
        // v1033：用户选定的口径 —— 不设上限，全部原话永久逐字保留。
        maxTokens = CLASSIC_VERBATIM_NO_LIMIT,
    )
    if (texts.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine()
        appendLine(CLASSIC_VERBATIM_USER_MARKER)
        texts.forEachIndexed { index, text ->
            // 续行缩进三个空格，多行原话在 Markdown 里仍属于同一条，不会被渲染成断开的列表。
            append(index + 1).append(". ").append(text.replace("\n", "\n   ")).appendLine()
        }
    }.trimEnd()
}

/**
 * v1033：剥掉摘要卡片正文里的「用户原话」段。
 *
 * 上一次压缩留下的卡片正文 = 旧摘要 + 旧原话段。这次压缩把它当历史喂给摘要模型时，
 * 模型会把那份逐字清单**再转述一遍**：既白花钱，又让逐字段随压缩次数按平方级膨胀
 * （第 k 次压缩的输入里带着前 k-1 张卡片各自的账本）。
 * 本次压缩的结果里本来就会重新挂上**全量**逐字清单，旧卡片那份转述毫无价值，直接剥掉。
 *
 * 只按标记行切分，且必须同时满足我们自己追加时的那个形状（前一空行 + 紧跟 `1. ` 编号），
 * 否则原样返回。v1033 审查位 P1：摘要正文本身也可能含这个标记 ——
 * 模型把上一版账本原样抄进摘要、或用户在摘要里粘过这段标题。
 * 单纯用 indexOf 会命中内容里那一处，把**摘要本体拦腰截断**，比不剥更糟。
 *
 * v1033 审查位 P2 进一步修正：不是「先看最后一处、形状不符就放弃」，而是**从后往前找第一个形状相符的**。
 * 否则当用户原话里恰好粘贴过这一行（它会作为账本条目出现在最后一处），
 * 整张卡片就永久剥不掉，等于白喂 token 让模型再转述一遍。
 * 形状判据仍然保底：对不上就一个字都不剥，宁可不剥也不许动正文。
 */
internal fun stripClassicVerbatimLedger(text: String): String {
    var index = text.lastIndexOf(CLASSIC_VERBATIM_USER_MARKER)
    while (index >= 0) {
        val before = text.substring(0, index)
        val after = text.substring(index + CLASSIC_VERBATIM_USER_MARKER.length)
        // classicVerbatimUserAppendix 的固定形状：正文 + 空行 + 标记行 + 紧跟 "1. " 编号。
        if (before.endsWith("\n\n") && after.startsWith("\n1. ")) return before.trimEnd()
        index = text.lastIndexOf(CLASSIC_VERBATIM_USER_MARKER, index - 1)
    }
    return text
}

/**
 * v1033：只对摘要卡片剥账本，其它消息一律原样返回。
 * 真实用户发言里很可能粘贴过带这个标记的文字，绝不能误伤 —— 判据先看 [isContextCheckpoint]。
 */
internal fun List<UIMessage>.strippingClassicVerbatimLedger(): List<UIMessage> = map { message ->
    if (!message.isContextCheckpoint) return@map message
    var stripped = false
    val parts = message.parts.map { part ->
        if (part is UIMessagePart.Text) {
            val next = stripClassicVerbatimLedger(part.text)
            if (next != part.text) {
                stripped = true
                // copy 只换正文，metadata（用量水位）原样保留 —— 编辑/重压都不许丢水位判据。
                part.copy(text = next)
            } else part
        } else part
    }
    // 卡片里没有「我们追加的那种账本」时返回原对象，不制造「内容没变却换了实例」的假差异。
    if (stripped) message.copy(parts = parts) else message
}

/**
 * v1019: Official 3.1.1 checkpoint workflow, isolated from our smart/automatic compaction.
 * Only model I/O is supplied by ChatService; the real session owns the latest state and persistence.
 */
internal suspend fun ConversationSession.compressClassicHistory(
    keepRecentMessages: Int = 0,
    // v1032：用户发言保护，默认关闭 —— 关闭时摘要正文与 v1031 一字节不差。
    // 放在 generateSummary 之前：trailing lambda 永远绑最后一个参数，
    // 现有全部调用点（含 app/src/test 的 V1019Classic* 系列）都是「具名参数 + trailing lambda」，无需改写。
    protectUserMessages: Boolean = false,
    /**
     * v1034：一轮回复**中途**压缩时，调用方就是那趟生成任务本身（`isGenerating` 必然为真），
     * 所以需要一个显式豁免口。默认 false —— 现有全部调用点（手动压缩、自动压缩分叉、
     * V1019/V1032/V1033 的用例）行为一字不变，「正在生成就拒绝压缩」这条守卫照旧生效。
     *
     * 打开它必须同时满足三件事（由 ChatService 的中途压缩钩子负责保证，见该处注释）：
     * ① 只在工具批次全部执行完之后调；② 只在被压的那条回复就是对话末尾正在写的那条时调；
     * ③ 只做检查点式压缩（原始消息一条都不删），绝不使用整段替换历史的那条路。
     */
    allowWhileGenerating: Boolean = false,
    generateSummary: suspend (Conversation, List<UIMessage>) -> String,
) {
    if (isGenerating && !allowWhileGenerating) throw ClassicCompressionException(ClassicCompressionIssue.GENERATING)
    val conversation = state.value
    val nodes = conversation.messageNodes
    val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
    val endIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
    if (endIndex <= checkpointIndex + 1) {
        throw ClassicCompressionException(
            if (keepRecentMessages > 0) ClassicCompressionIssue.NOT_ENOUGH_MESSAGES
            else ClassicCompressionIssue.NOTHING_TO_COMPRESS,
        )
    }
    // Official rule: only tools inside the compression range must have finished.
    if (nodes.subList(checkpointIndex + 1, endIndex).any { node ->
            node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
        }) {
        throw ClassicCompressionException(ClassicCompressionIssue.PENDING_TOOLS)
    }
    val summaryInput = conversation.currentMessages.take(endIndex)
    // v1033：开启保护时，旧摘要卡片里已归档的逐字段不再重复喂给摘要模型（见 [strippingClassicVerbatimLedger]）。
    // 关闭保护时输入与 v1032 完全一致，摘要正文一字节都不变。
    val summary = generateSummary(
        conversation,
        if (protectUserMessages) summaryInput.strippingClassicVerbatimLedger() else summaryInput,
    )
    if (summary.isBlank()) throw ClassicCompressionException(ClassicCompressionIssue.EMPTY_SUMMARY)
    // v1032：用户发言保护最初只取「本次新压掉的那一段」，起点落在已有检查点之后 ——
    // 当时担心把上一个检查点（它也是 user 角色的消息，旧摘要就存在那条里）当用户发言再抄一遍，
    // 会让摘要逐次套娃膨胀。
    // v1033：范围重新放开到「从对话第一条真实用户发言到本次压缩终点」的全部原话。
    // 套娃的那个担心成立的前提是「旧卡片正文被当成用户发言」，而 [classicVerbatimUserAppendix]
    // 一直用 filterNot { isContextCheckpoint } 排掉了卡片本身，所以放开范围不会自抄摘要；
    // 真正需要的「别把账本再喂给模型转述一遍」改由上面的 stripping 解决。
    // 区间取法与生成摘要用的 [summaryInput] 严格同源（currentMessages 与 messageNodes 一一对应）。
    // 关闭开关时 checkpointText == summary，写下去的正文一字节都不变。
    val checkpointText = if (protectUserMessages) {
        summary + classicVerbatimUserAppendix(summaryInput)
    } else summary
    currentCoroutineContext().ensureActive()
    // Insert against the latest nodes, never replace history with the pre-request snapshot.
    var insertedCheckpointId: Uuid? = null
    var previousSuggestions: List<String> = emptyList()
    update { latest ->
        if (isGenerating && !allowWhileGenerating) throw ClassicCompressionException(ClassicCompressionIssue.GENERATING)
        // v1019：摘要只代表请求时那份选中内容；编辑/切分支后不能用旧摘要遮住新内容。
        val latestCheckpoint = latest.messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val latestEnd = latest.messageNodes.indexOfFirst { it.id == nodes[endIndex - 1].id } + 1
        val expectedRange = nodes.subList(checkpointIndex.coerceAtLeast(0), endIndex)
            .map { it.id to it.currentMessage }
        val actualRange = if (latestEnd > latestCheckpoint.coerceAtLeast(0)) {
            latest.messageNodes.subList(latestCheckpoint.coerceAtLeast(0), latestEnd)
                .map { it.id to it.currentMessage }
        } else emptyList()
        if (actualRange != expectedRange) throw ClassicCompressionException(ClassicCompressionIssue.CONVERSATION_CHANGED)
        previousSuggestions = latest.chatSuggestions
        val updated = latest.insertContextCheckpoint(nodes[endIndex - 1].id, checkpointText)
            ?: throw ClassicCompressionException(ClassicCompressionIssue.CONVERSATION_CHANGED)
        // v1019：只在新摘要里记下保留消息的用量水位，不改原消息/旧用量。
        // 不能用手机本地时间判断旧账：导入、改时区和重新生成旧消息都会让先后顺序失真。
        val checkpointIndex = updated.messageNodes.indexOfFirst { it.id == nodes[endIndex - 1].id } + 1
        val baselines = buildJsonObject {
            // v1019：保留区里每个节点的所有分支都记一份，切到未选中的旧分支时同样不误算。
            latest.messageNodes.drop(checkpointIndex).flatMap { node -> node.messages }
                .forEach { message -> if (message.usage != null) put(message.id.toString(), classicUsageStamp(message)) }
        }
        val checkpointNode = updated.messageNodes[checkpointIndex]
        insertedCheckpointId = checkpointNode.id
        val checkpoint = checkpointNode.currentMessage.copy(
            parts = listOf(UIMessagePart.Text(checkpointText, metadata = buildJsonObject {
                put(CLASSIC_USAGE_BASELINES_KEY, baselines)
            })),
        )
        updated.copy(
            messageNodes = updated.messageNodes.toMutableList().also {
                it[checkpointIndex] = checkpointNode.copy(messages = listOf(checkpoint))
            },
            chatSuggestions = emptyList(),
        )
    }
    try {
        // v1019：模型请求仍可随时取消；提交一旦开始，先完成这一笔本地保存，避免半提交。
        withContext(NonCancellable) { flush() }
    } catch (e: Exception) {
        // 只撤掉本次尚未落库的摘要，不拿旧会话整份回滚，不丢保存期间新来的消息。
        update { latest ->
            latest.copy(
                messageNodes = latest.messageNodes.filterNot { it.id == insertedCheckpointId },
                chatSuggestions = latest.chatSuggestions.ifEmpty { previousSuggestions },
            )
        }
        throw e
    }
}

/** v1019：智能算法之外的历史读取适配；测试先证明旧读取会重带归档历史。 */
internal fun smartCompressionHistory(conversation: Conversation): List<UIMessage> =
    conversation.currentMessages.limitContext(0).filter { it.isValidToUpload() }

/**
 * v1019：把 ChatService.editMessage 里原有的节点写法原样搬出来（纯搬移，非摘要消息的行为一字不变），
 * 便于用真实数据测「编辑摘要」这条新判据。
 */
internal fun editMessageNodes(
    nodes: List<MessageNode>,
    messageId: Uuid,
    parts: List<UIMessagePart>,
): List<MessageNode>? {
    if (nodes.none { node -> node.messages.any { it.id == messageId } }) return null
    return nodes.map { node ->
        val original = node.messages.firstOrNull { it.id == messageId } ?: return@map node
        if (original.isContextCheckpoint) {
            // v1019：与官方 Conversation.editMessage 同一判据 —— 摘要原地改写。
            // 新建分支会让「当前消息」不再是检查点，limitContext 找不回它，
            // 已归档的整段历史会被重新发给模型（用户以为压好了，实际恢复全量计费）。
            node.copy(
                messages = node.messages.map {
                    if (it.id == messageId) it.copy(parts = parts.carryingCheckpointBaselinesFrom(original)) else it
                }
            )
        } else node.copy(
            messages = node.messages + UIMessage(
                role = node.role,
                parts = parts,
            ),
            selectIndex = node.messages.size
        )
    }
}

private const val CLASSIC_USAGE_BASELINES_KEY = "doushabao_classic_usage_baselines"

private fun classicUsageStamp(message: UIMessage): JsonObject = buildJsonObject {
    put("usage", Json.encodeToJsonElement(message.usage))
    put("last_usage", Json.encodeToJsonElement(message.lastUsage))
    put("finished_at", JsonPrimitive(message.finishedAt?.toString()))
    put("request_count", JsonPrimitive(message.cumulativeRequestCount))
    put("response_ms", JsonPrimitive(message.cumulativeResponseMs))
    put("generation_ms", JsonPrimitive(message.cumulativeGenerationMs))
}

/** New checkpoints use explicit old-usage baselines; imported official checkpoints keep official time fallback. */
/** 摘要正文上的旧用量水位（存在 Text part 的 metadata 里；原消息与数据库字段都不动）。 */
internal fun UIMessage.checkpointBaselines(): JsonObject? =
    parts.filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { it.metadata?.get(CLASSIC_USAGE_BASELINES_KEY) as? JsonObject }

/** v1019：原地改写摘要时把水位带到新正文上，编辑后不许退回本地时间判据。 */
private fun List<UIMessagePart>.carryingCheckpointBaselinesFrom(original: UIMessage): List<UIMessagePart> {
    val baselines = original.checkpointBaselines() ?: return this
    var attached = false
    return map { part ->
        if (part is UIMessagePart.Text && !attached) {
            attached = true
            part.copy(metadata = buildJsonObject {
                part.metadata?.forEach { (key, value) -> put(key, value) }
                put(CLASSIC_USAGE_BASELINES_KEY, baselines)
            })
        } else part
    }
}

internal fun isPreCheckpointUsage(message: UIMessage, checkpoint: UIMessage): Boolean {
    val baselines = checkpoint.checkpointBaselines()
    return if (baselines != null) {
        baselines[message.id.toString()] == classicUsageStamp(message)
    } else {
        message.createdAt < checkpoint.createdAt
    }
}
