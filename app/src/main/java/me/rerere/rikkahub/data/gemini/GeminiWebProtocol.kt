package me.rerere.rikkahub.data.gemini

import java.io.IOException
import java.util.Locale
import me.rerere.ai.core.ReasoningLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.StreamChunk
import okio.BufferedSource

/**
 * v1020：网页协议判据参考 HanaokaYuzu/Gemini-API 的账号动态目录及累计正文语义。
 * 固定参照 commit 1ad0d4a93e505e77c79769d78ef2997641f973c8：
 * client.py _fetch_user_status / types/availablemodel.py / utils/parsing.py。
 * 型号身份以账号返回的 opaque ID 为准，不能靠列表顺序或自行编的版本名选择。
 */
internal data class GeminiWebModel(
    val modelId: String,
    val displayName: String,
    val category: String,
    val number: Int,
    val capacity: Int,
    val capacityField: Int,
) {
    // v1022: The wire value is the real web thinking tier (low=1, medium=4, high=2), not a display label.
    fun modelHeader(sessionUuid: String, thinkingWire: Int = 1): String = buildJsonArray {
        add(1); add(JsonNull); add(JsonNull); add(JsonNull); add(modelId)
        add(JsonNull); add(JsonNull); add(0)
        add(buildJsonArray { add(4); add(5); add(6); add(8) })
        add(JsonNull); add(JsonNull)
        if (capacityField == 13) add(JsonNull)
        add(capacity)
        add(JsonNull); add(JsonNull); add(number)
        add(thinkingWire) // v1022: must stay identical to inner[80]; see geminiWebThinkingWire().
        add(sessionUuid)
    }.toString()
}

internal fun JsonElement?.webAt(vararg path: Int): JsonElement? {
    var current = this
    for (index in path) current = (current as? JsonArray)?.getOrNull(index)
    return current?.takeUnless { it is JsonNull }
}

internal fun JsonElement?.webString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.webInt(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun geminiWebRows(envelope: JsonArray): List<JsonArray> =
    if (envelope.firstOrNull() is JsonPrimitive) listOf(envelope)
    else envelope.mapNotNull { it as? JsonArray }

/** v1020：只解析 otAQ7b 自身的返回值；目录为空/失效如实报错，不伪造三个旧模型。 */
internal fun parseGeminiWebCatalog(rows: List<JsonArray>, json: Json): List<GeminiWebModel> {
    for (row in rows) {
        if (row.webAt(1).webString() != "otAQ7b") continue
        if (row.webAt(5, 0).webInt() == 7) {
            throw IOException("Gemini 网页版拒绝读取模型列表：登录态未认证，请重新登录。")
        }
        val bodyText = row.webAt(2).webString() ?: continue
        val body = json.parseToJsonElement(bodyText) as? JsonArray ?: continue
        val status = body.webAt(14).webInt()
        if (status == 1016) {
            throw IOException("Gemini 网页版登录态未认证（1016），请重新登录后拉取模型。")
        }
        val entries = body.webAt(15) as? JsonArray ?: continue
        val tier = (body.webAt(16) as? JsonArray).orEmpty().mapNotNull { it.webInt() }.toSet()
        val capabilities = (body.webAt(17) as? JsonArray).orEmpty().mapNotNull { it.webInt() }.toSet()
        val (capacity, capacityField) = when {
            21 in tier -> 1 to 13
            22 in tier -> 2 to 13
            115 in capabilities -> 4 to 12
            16 in tier || 106 in capabilities -> 3 to 12
            8 in tier || 19 in capabilities -> 2 to 12
            else -> 1 to 12
        }
        val models = entries.mapNotNull { entry ->
            val id = entry.webAt(0).webString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val category = entry.webAt(1).webString()?.takeIf { it.isNotBlank() }
                ?: entry.webAt(10).webString().orEmpty()
            val display = entry.webAt(11).webString()?.takeIf { it.isNotBlank() }
                ?: entry.webAt(19).webString()?.takeIf { it.isNotBlank() }
                ?: category.ifBlank { id }
            GeminiWebModel(
                modelId = id,
                displayName = display,
                category = category,
                number = entry.webAt(17).webInt() ?: entry.webAt(9).webInt() ?: 1,
                capacity = capacity,
                capacityField = capacityField,
            )
        }.distinctBy { it.modelId }
        if (models.isNotEmpty()) return models
        throw IOException("Gemini 网页版没有为当前账号返回可用模型（账号状态：${status ?: "未提供"}）。请在 Google 网页确认模型后重新拉取。")
    }
    throw IOException("Gemini 网页版模型列表格式无法识别，未回退到旧模型；请检查登录态或更新兼容。")
}

/**
 * v1022: Google web exposes three thinking wires:
 * low=1, medium=4 (server-side, no reasoning chain), high=2 (returns [37][0][0] chain).
 * AUTO always sends the platform default low=1; there is no automatic promotion.
 * A user-selected MAX/HIGH/XHIGH is downgraded to high=2 at the wire.
 * OFF/LOW stay at the platform's minimum; the web endpoint has no true zero-reasoning tier.
 */
internal fun GeminiWebModel.isArgonModel(): Boolean =
    number == 8 || displayName.contains("argon", ignoreCase = true) || category.contains("argon", ignoreCase = true)

internal fun geminiWebThinkingWire(level: ReasoningLevel, isArgon: Boolean): Int = when (level) {
    ReasoningLevel.OFF, ReasoningLevel.LOW -> 1
    // Medium=4 is only documented for Argon; do not send an unverified value to other web models.
    ReasoningLevel.MEDIUM -> if (isArgon) 4 else 1
    ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> 2
    // AUTO stays low at the transport layer so background title/memory/test calls do not pay for high.
    // Only an explicit MAX/HIGH/XHIGH selection reaches this branch's 2.
    ReasoningLevel.AUTO -> 1
}

internal data class GeminiWebModelLoadResult(
    val models: List<me.rerere.ai.provider.Model> = emptyList(),
    val error: String? = null,
)

/** v1020：模型页显示真实失败原因；主动取消不伪装成失败，也不回传陈旧目录。 */
internal suspend fun loadGeminiWebModelsForUi(load: suspend () -> List<me.rerere.ai.provider.Model>): GeminiWebModelLoadResult =
    try {
        GeminiWebModelLoadResult(models = load())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        GeminiWebModelLoadResult(error = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
    }

/** v1020：只有网页模型选择页显示账号真实名称；其他供应商原有ID显示不变。 */
internal fun geminiWebPickerLabel(model: me.rerere.ai.provider.Model, showDisplayName: Boolean): String =
    if (showDisplayName) model.displayName.ifBlank { model.modelId } else model.modelId

/**
 * v1020：旧配置只按完整型号精确兼容，不剥掉版本号去猜 Flash/Pro。
 * 名称有歧义时提示用户选择账号真实 ID，绝不把 3.8 静默路由到 3.5。
 */
internal fun resolveGeminiWebModel(models: List<GeminiWebModel>, requested: String): GeminiWebModel {
    models.firstOrNull { it.modelId == requested.trim() }?.let { return it }
    fun normalized(value: String): String = value.trim().removeSuffix("（网页）")
        .lowercase(Locale.ROOT).replace(Regex("[\\s_]+"), "-")
    val target = normalized(requested)
    val matches = models.filter { model ->
        target.isNotEmpty() && (target == normalized(model.displayName) ||
            (model.category.isNotBlank() && target in setOf(normalized(model.category), "gemini-${normalized(model.category)}")))
    }
    if (matches.size == 1) return matches.single()
    if (matches.size > 1) {
        throw IOException("Gemini 网页版有多个同名模型「$requested」，请重新拉取并选择对应模型 ID，不能自动猜选。")
    }
    throw IOException("Gemini 网页版当前账号目录中找不到「$requested」。旧型号名称或标识可能已变化，请到供应商的模型页重新拉取并选择；没有偷偷切换其他模型。")
}

/**
 * v1020：按 JSON 结构增量解帧，支持长度行、NDJSON、分块/多行 JSON 和 UTF-16 字符。
 * 长度按 UTF-16 核对，同时兼容计入0/1/2个分隔换行的返回；不用UTF-8字节数裁切。
 * 只在一帧完整闭合后解析；EOF 仍有半帧则报错，不能静默丢尾再宣称 stop。
 */
private class GeminiWebFrameParser(private val json: Json) {
    private val buffer = StringBuilder()
    private val brackets = ArrayDeque<Char>()
    private var quoted = false
    private var escaped = false
    private var prefixPosition = 0
    private val lengthDigits = StringBuilder()
    private var declaredLength: Long? = null
    private val prefix = ")]}'"

    suspend fun feed(chars: CharArray, count: Int, onFrame: suspend (JsonArray) -> Unit) {
        for (i in 0 until count) {
            val char = chars[i]
            if (brackets.isEmpty()) {
                if (prefixPosition != 0) {
                    if (char != prefix[prefixPosition]) throw IOException("Gemini 网页版响应前缀不完整。")
                    prefixPosition++
                    if (prefixPosition == prefix.length) prefixPosition = 0
                    continue
                }
                when {
                    char in '0'..'9' -> {
                        if (declaredLength != null) throw IOException("Gemini 网页版长度行后缺少数据帧。")
                        lengthDigits.append(char)
                        continue
                    }
                    char.isWhitespace() -> {
                        if (lengthDigits.isNotEmpty()) {
                            declaredLength = lengthDigits.toString().toLongOrNull()
                                ?: throw IOException("Gemini 网页版数据帧长度无效。")
                            lengthDigits.setLength(0)
                            if (declaredLength == 0L) declaredLength = null
                        }
                        continue
                    }
                    char == ')' -> {
                        if (declaredLength != null || lengthDigits.isNotEmpty()) throw IOException("Gemini 网页版长度行后缺少数据帧。")
                        prefixPosition = 1
                        continue
                    }
                    char == '[' -> {
                        if (lengthDigits.isNotEmpty()) throw IOException("Gemini 网页版长度行没有正确结束。")
                        brackets.addLast(char); buffer.append(char); continue
                    }
                    else -> throw IOException("Gemini 网页版返回了无法识别的数据帧。")
                }
            }
            buffer.append(char)
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            when (char) {
                '"' -> quoted = true
                '[', '{' -> brackets.addLast(char)
                ']', '}' -> {
                    val expected = if (char == ']') '[' else '{'
                    if (brackets.removeLastOrNull() != expected) throw IOException("Gemini 网页版数据帧括号不匹配。")
                    if (brackets.isEmpty()) {
                        declaredLength?.let { expected ->
                            if (expected - buffer.length.toLong() !in 0L..2L) {
                                throw IOException("Gemini 网页版数据帧长度不符，回复未完整接收。")
                            }
                        }
                        declaredLength = null
                        val frame = json.parseToJsonElement(buffer.toString()) as? JsonArray
                            ?: throw IOException("Gemini 网页版数据帧不是数组。")
                        buffer.setLength(0)
                        // v1020：完整一帧立即交付，后面坏帧不能吞掉前面已经收到的正文。
                        onFrame(frame)
                    }
                }
            }
        }
    }

    fun finish() {
        if (brackets.isNotEmpty() || buffer.isNotEmpty() || prefixPosition != 0 || lengthDigits.isNotEmpty() || declaredLength != null) {
            throw IOException("Gemini 网页版响应在数据帧中途断开，回复未完成。")
        }
    }
}

internal suspend fun readGeminiWebFrames(
    source: BufferedSource,
    json: Json,
    onFrame: suspend (JsonArray) -> Unit,
) {
    val parser = GeminiWebFrameParser(json)
    val reader = source.inputStream().reader(Charsets.UTF_8)
    // Reader handles UTF-8 split between socket reads; Kotlin Char accounting is UTF-16.
    val chars = CharArray(4096)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = reader.read(chars)
        if (count < 0) break
        parser.feed(chars, count, onFrame)
    }
    currentCoroutineContext().ensureActive()
    parser.finish()
}

/** v1020：一个请求一份状态。保留所选 rcid；多个候选不能混进同一篇回答。 */
internal class GeminiWebStreamDecoder(private val json: Json) {
    private var selectedId: String? = null
    private var lastText: String? = null
    private var lastThought: String? = null // v1021: independent native-thought snapshot per selected rcid.
    private var completed = false

    fun accept(row: JsonArray): List<StreamChunk> {
        val outerCode = row.webAt(5, 2, 0, 1, 0).webInt()
        if (outerCode != null && outerCode != 0) throwWebError(outerCode)
        if (row.webAt(0).webString() != "wrb.fr") return emptyList()
        val raw = row.webAt(2).webString() ?: return emptyList()
        val payload = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        val legacyCode = payload.webAt(5).webInt()
        if (legacyCode != null && legacyCode != 0) throwWebError(legacyCode)
        // v1020：[25]只表示已保存历史，不表示生成完成；完成仍以所选候选[8][0]为准。
        val candidates = (payload.webAt(4) as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
            .filter { !it.webAt(0).webString().isNullOrBlank() }
        val candidate = candidates.firstOrNull { selectedId != null && it.webAt(0).webString() == selectedId }
            ?: candidates.firstOrNull()
        val events = mutableListOf<StreamChunk>()
        if (candidate != null) {
            val nextId = candidate.webAt(0).webString()
            val text = candidate.webAt(1, 0).webString()
            // v1021: Reference _parse_candidate separates body[1][0] and native thoughts[37][0][0].
            // Missing is a sparse frame; an explicit empty string withdraws the previous thought draft.
            val thought = candidate.webAt(37, 0, 0).webString()
            if (nextId != selectedId) {
                val previousText = lastText
                val previousThought = lastThought
                selectedId = nextId
                completed = false
                lastText = null
                lastThought = null
                if (thought == null && previousThought != null) {
                    events += StreamChunk.ReasoningSnapshot(id = "gemini-web-thought", text = "")
                }
                // v1020：新候选还没正文时同步清掉旧草稿，不能拿旧候选冒充新候选完成。
                if (text == null && previousText != null) {
                    events += StreamChunk.TextSnapshot(id = "gemini-web", text = "")
                }
            }
            val thoughtChanged = thought != null && thought != lastThought
            if (thoughtChanged) {
                lastThought = thought
                events += StreamChunk.ReasoningSnapshot(id = "gemini-web-thought", text = requireNotNull(thought))
            }
            val textChanged = text != null && text != lastText
            if (textChanged) {
                // 权威全文替换当前草稿，不用模糊“文本去重”删除模型确实写出的重复字句。
                lastText = text
                events += StreamChunk.TextSnapshot(id = "gemini-web", text = requireNotNull(text))
            }
            val state = candidate.webAt(8, 0).webInt()
            if (state != null) completed = state == 2
            // v1021: Native thought summaries can change independently; do not revoke a completed body for that alone.
            else if (textChanged) completed = false
        }
        return events
    }

    fun finish(modelId: String): StreamChunk.Finish {
        if (lastText.isNullOrEmpty()) throw IOException("Gemini 网页版没有返回可见正文。")
        return StreamChunk.Finish(
            finishReason = if (completed) "stop" else null,
            model = modelId,
            terminatedWithoutSentinel = !completed,
        )
    }

    private fun throwWebError(code: Int): Nothing {
        val detail = when (code) {
            1037 -> "当前模型额度或速率受限"
            1050 -> "模型与当前网页会话不一致"
            1052 -> "模型选择参数已失效或该模型当前不可用"
            1060 -> "Google 暂时限制了当前网络"
            else -> "Google 返回异常"
        }
        throw IOException("Gemini 网页版错误码 $code：$detail")
    }
}
