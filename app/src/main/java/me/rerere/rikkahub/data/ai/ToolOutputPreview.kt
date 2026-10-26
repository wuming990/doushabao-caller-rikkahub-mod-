package me.rerere.rikkahub.data.ai

/**
 * v1017：工具超长输出的裁剪规则（纯函数，不依赖 Android，便于直接跑单测）。
 *
 * 为什么单独成文件：`GenerationLoop.kt` 里那几个截断代码要碰 `Context`/`File`，
 * 在 JVM 单测里跑不动；把"怎么留摘要"这类判据抽出来，编译器与测试才说得了话
 * （同 v247 `sliceTextByLines` 的做法）。
 */

/**
 * 不参与截断的工具。
 *
 * `search_web` 的返回是 JSON，聊天界面的「搜索结果列表」与引用角标跳转都依赖
 * 完整结构；一旦被截断，解析失败后卡片是**空白且不报错**的（用户看到的就是
 * 「AI 明明说搜到了，我这里什么都没有」）。
 */
internal val TOOLS_WITHOUT_OUTPUT_TRUNCATION: Set<String> = setOf("search_web")

/** 省略说明本身占的字符预算（含数字位数，留足冗余） */
private const val OMITTED_NOTE_BUDGET = 96

/**
 * 头 + 尾双端预览。
 *
 * v1017 改动原因：旧写法 `fullText.take(4096)` 只留开头。命令输出的**报错、退出码、
 * 最终结论几乎都在末尾**，只留开头等于把最该看的部分丢掉，模型只能重跑一遍命令。
 * 现在按 3:2 分给头尾（各占 60% / 40%），中间如实写明省略了多少字符。
 *
 * 保证：`返回长度 <= budget`；不切断代理对（emoji / 生僻字），避免留下半个乱码字符。
 */
internal fun headAndTailPreview(text: String, budget: Int): String {
    if (budget <= 0 || text.length <= budget) return text

    val keep = (budget - OMITTED_NOTE_BUDGET).coerceAtLeast(16)
    val headChars = (keep * 3 / 5).coerceAtLeast(1)
    val tailChars = (keep - headChars).coerceAtLeast(1)

    val head = trimTrailingSurrogate(text.take(headChars))
    val rawTail = text.takeLast(tailChars)
    val tail = trimLeadingSurrogate(rawTail)

    val omitted = (text.length - head.length - tail.length).coerceAtLeast(0)
    val note = "\n…[middle $omitted characters omitted]…\n"
    return head + note + tail
}

/** 末尾若正好切在高代理项上，扔掉半个字符 */
private fun trimTrailingSurrogate(s: String): String =
    if (s.isNotEmpty() && s.last().isHighSurrogate()) s.dropLast(1) else s

/** 开头若正好切在低代理项上，扔掉半个字符 */
private fun trimLeadingSurrogate(s: String): String =
    if (s.isNotEmpty() && s.first().isLowSurrogate()) s.drop(1) else s
