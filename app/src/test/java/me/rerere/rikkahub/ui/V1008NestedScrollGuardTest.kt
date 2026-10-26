package me.rerere.rikkahub.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1008：账号通道配置页不许自己再套一层竖向滚动。
 *
 * 真机崩溃原文：
 * `IllegalStateException: Vertically scrollable component was measured with an infinity
 * maximum height constraints…`，点进 Gemini / Grok / Codex 三个账号供应商就崩。
 *
 * 根因：这三个配置页是 2.5.6 时代留下的二改文件，自带
 * `Column(Modifier.verticalScroll(...))`；而官方 3.1 的供应商详情页
 * （`SettingProviderConfigPage`）本身已经是 `Column(...verticalScroll(...))`，
 * 我们直接把它渲染在里面 → 两层同向滚动 → 无限高度约束 → 崩。
 *
 * 这个守卫被打回过两轮，教训写在这里，改它之前先读完：
 *
 * 第一轮（裸 contains）：
 *  - 会被注释误伤（手册 §7.3 记过）；
 *  - 挡不住等价写法（先取 state 再传给 verticalScroll、折行、改成 LazyColumn）。
 *
 * 第二轮（正则剥注释）：
 *  - 剥块注释的正则不认字符串，`arrayOf("* / *")` 里的斜杠星号会被当成注释开头，
 *    GrokProviderConfigure.kt 的 181~544 行（整段账号列表）被吞掉，守卫看着在守、其实是瞎的；
 *  - `LazyColumn { … }` 尾随 lambda 无括号，只认 `LazyColumn(` 会漏判；
 *  - `substringBefore` 找不到分隔符会返回整段，切片静默退化成整文件 contains。
 *
 * 所以现在：剥注释用「认字符串 / 原始字符串 / 字符字面量」的状态机；剥完做长度自检；
 * 正则认 `(` 与 `{` 两种调用形态；切片前先断言分隔符真的存在。
 *
 * 这是源码文本守卫：只证明「接线还在」，不证明真机不崩。真机由用户验收。
 */
class V1008NestedScrollGuardTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    /**
     * 去掉行注释与块注释，但**不碰字符串字面量**。
     *
     * 必须认三种会“吃掉注释符号”的写法：普通字符串（含转义）、Kotlin 原始字符串、字符字面量。
     * 不认字符串就会出现「`arrayOf("* / *")` 把后半个文件当注释吞掉」这种事。
     * 换行一律保留，便于行号对照。
     */
    internal fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inRawString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (inLine) {
                if (c == '\n') {
                    inLine = false
                    out.append(c)
                }
                i++
                continue
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false
                    i += 2
                } else {
                    if (c == '\n') out.append('\n')
                    i++
                }
                continue
            }
            if (inRawString) {
                if (c == '"' && text.startsWith(RAW, i)) {
                    inRawString = false
                    out.append(RAW)
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
                continue
            }
            if (inString) {
                if (c == '\\' && i + 1 < text.length) {
                    out.append(c).append(text[i + 1])
                    i += 2
                } else {
                    if (c == '"') inString = false
                    out.append(c)
                    i++
                }
                continue
            }
            if (inChar) {
                if (c == '\\' && i + 1 < text.length) {
                    out.append(c).append(text[i + 1])
                    i += 2
                } else {
                    if (c == '\'') inChar = false
                    out.append(c)
                    i++
                }
                continue
            }
            when {
                c == '/' && next == '/' -> {
                    inLine = true
                    i += 2
                }

                c == '/' && next == '*' -> {
                    inBlock = true
                    i += 2
                }

                c == '"' && text.startsWith(RAW, i) -> {
                    inRawString = true
                    out.append(RAW)
                    i += 3
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                c == '\'' -> {
                    inChar = true
                    out.append(c)
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 剥注释 + 长度自检。
     *
     * 自检防的正是上面那个坑：剥完只剩不到一半，说明把正常代码当注释吞了，
     * 此时断言全绿也是假的 —— 宁可当场报错。
     */
    private fun code(relative: String): String {
        val raw = source(relative)
        val stripped = stripComments(raw)
        assertTrue(
            "$relative 剥注释后只剩 ${stripped.length}/${raw.length} 字符，疑似把字符串里的注释符号当成了注释",
            stripped.length * 2 >= raw.length,
        )
        return stripped
    }

    private val configureFiles = listOf(
        "GeminiProviderConfigure",
        "GrokProviderConfigure",
        "CodexProviderConfigure",
    ).map { "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/$it.kt" }

    // ---------- 守卫机制自测（防「守卫自己是瞎的」） ----------

    @Test
    fun `字符串里的斜杠星号不能被当成注释开头`() {
        val sample = "fun f() {\n" +
            "    val mime = arrayOf(\"*/*\")\n" +
            "    val mod = Modifier.verticalScroll(st)\n" +
            "}\n"
        assertTrue(
            "字符串里的斜杠星号被当成块注释，会把后面的代码整段吞掉",
            VERTICAL_SCROLL_CALL.containsMatchIn(stripComments(sample)),
        )
    }

    @Test
    fun `注释里的调用必须被剥掉`() {
        val sample = "// Modifier.verticalScroll(state)\n" +
            "/* Modifier.verticalScroll(state) */\n" +
            "val x = 1\n"
        assertFalse(
            "注释里提到调用不该把守卫打红",
            VERTICAL_SCROLL_CALL.containsMatchIn(stripComments(sample)),
        )
    }

    @Test
    fun `原始字符串里的双斜杠不能被当成行注释`() {
        val sample = "val url = " + RAW + "https://example.com" + RAW + "\n" +
            "val list = LazyColumn { }\n"
        assertTrue(
            "原始字符串里的双斜杠被当成行注释，后面同一行代码被吞",
            LAZY_COLUMN_CALL.containsMatchIn(stripComments(sample)),
        )
    }

    // ---------- 对真实源码的断言 ----------

    @Test
    fun `账号配置页不许自带竖向滚动或竖向列表`() {
        configureFiles.forEach { path ->
            val body = code(path)
            assertFalse(
                "$path 又套了 verticalScroll：外层供应商详情页已经可滚动，这会触发无限高度约束崩溃",
                VERTICAL_SCROLL_CALL.containsMatchIn(body),
            )
            assertFalse(
                "$path 用了 LazyColumn（含尾随 lambda 写法）：它同样会在无限高度约束下崩溃，滚动请交给外层页面",
                LAZY_COLUMN_CALL.containsMatchIn(body),
            )
        }
    }

    @Test
    fun `配置页的外层必须自己负责滚动`() {
        val detail = code(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt"
        )
        val marker = "private fun SettingProviderConfigPage("
        assertTrue("找不到 $marker —— 官方可能改了这个页面的结构，需要人工重看", detail.contains(marker))
        val afterMarker = detail.substringAfter(marker)
        val section = afterMarker.substringBefore("ProviderConfigure(")
        assertTrue(
            "找不到第一次 ProviderConfigure( 调用 —— 切片会静默退化成整文件，断言就变成假绿",
            section.length < afterMarker.length,
        )
        assertTrue(
            "SettingProviderConfigPage 必须在渲染 ProviderConfigure 之前套上 verticalScroll，否则整页滚不动",
            VERTICAL_SCROLL_CALL.containsMatchIn(section),
        )
    }

    @Test
    fun `三个账号配置组件必须仍被 ProviderConfigure 派发`() {
        val dispatch = code(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt"
        )
        listOf("GrokProviderConfigure(", "CodexProviderConfigure(", "GeminiProviderConfigure(")
            .forEach { call ->
                assertTrue(
                    "$call 没有被 ProviderConfigure 引用 —— 组件成孤儿，上面的守卫就白守了",
                    dispatch.contains(call),
                )
            }
    }

    private companion object {
        /** Kotlin 原始字符串的三个引号；用常量拼，避免在源码里写嵌套三引号。 */
        const val RAW = "\"\"\""

        val VERTICAL_SCROLL_CALL = Regex("\\.verticalScroll\\s*\\(")
        val LAZY_COLUMN_CALL = Regex("\\bLazyColumn\\s*[({]")
    }
}
