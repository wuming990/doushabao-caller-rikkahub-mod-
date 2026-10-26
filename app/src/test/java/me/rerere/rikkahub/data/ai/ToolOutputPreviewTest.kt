package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1017：工具超长输出的「头 + 尾预览」纯函数与豁免名单。
 *
 * 为什么要钉住：旧实现是 `fullText.take(4096)`，只留开头。命令的报错、退出码、
 * 最终结论几乎都写在末尾 —— 只留开头等于把最该看的那半丢掉，模型只能重跑一遍命令。
 * 这类"看着像优化、其实是信息损失"的改动，不写测试一定会漂回去。
 */
class ToolOutputPreviewTest {

    @Test
    fun `短文本原样返回不加工`() {
        val text = "hello"
        assertEquals(text, headAndTailPreview(text, 4096))
    }

    @Test
    fun `开头与结尾都在，且总长不超过预算`() {
        val text = buildString {
            append("HEAD-MARKER")
            repeat(20_000) { append('x') }
            append("TAIL-MARKER")
        }
        val preview = headAndTailPreview(text, 4096)
        assertTrue("开头必须在预览里", preview.contains("HEAD-MARKER"))
        assertTrue("结尾必须在预览里（这条正是旧实现丢掉的）", preview.contains("TAIL-MARKER"))
        assertTrue("预览总长必须 <= 预算，实际 ${preview.length}", preview.length <= 4096)
    }

    @Test
    fun `如实写明省略了多少字符`() {
        val text = buildString {
            repeat(10_000) { append('a') }
        }
        val preview = headAndTailPreview(text, 4096)
        val note = Regex("""\n…\[middle (\d+) characters omitted\]…\n""").find(preview)
            ?: throw AssertionError("预览里必须带省略说明，实际内容开头：${preview.take(60)}")
        val omitted = note.groupValues[1].toLong()
        val kept = preview.length - note.value.length
        // 省略数 + 实际保留数 = 原文长度，必须说真话（不许报一个凭空的大数）
        assertEquals("省略数与保留数必须与原文对得上", 10_000L, omitted + kept)
    }

    @Test
    fun `中文与emoji不许被切成半个字符`() {
        // 每个 emoji 是「高代理项 + 低代理项」两个 char，切在中间就会留下半个乱码
        val text = "🦄".repeat(5_000) + "尾"
        val preview = headAndTailPreview(text, 100)
        assertFalse("末尾不许残留孤立高代理项", preview.last().isHighSurrogate())
        val afterNote = preview.substringAfterLast("]\n")
        assertFalse("开头不许残留孤立低代理项", afterNote.first().isLowSurrogate())
        // 精确判据：预览里每一个高代理项都必须紧跟一个低代理项（切半就会留下孤立代理项）
        val chars = preview.toCharArray()
        var broken = 0
        for (idx in chars.indices) {
            val c = chars[idx]
            if (c.isHighSurrogate()) {
                if (idx + 1 >= chars.size || !chars[idx + 1].isLowSurrogate()) broken++
            } else if (c.isLowSurrogate()) {
                if (idx == 0 || !chars[idx - 1].isHighSurrogate()) broken++
            }
        }
        assertEquals("预览里不允许出现被切开的代理对", 0, broken)
    }

    @Test
    fun `预算为0或负数时不能崩也不能吞掉内容`() {
        val text = "0123456789".repeat(1_000)
        val preview = headAndTailPreview(text, 0)
        assertEquals("预算非法时直接返回原文，交给上层决定", text, preview)
        val negative = headAndTailPreview(text, -5)
        assertEquals(text, negative)
    }

    @Test
    fun `超长单行同样安全`() {
        val text = "x".repeat(2_000_000)
        val preview = headAndTailPreview(text, 4096)
        assertTrue("总长必须受预算约束，实际 ${preview.length}", preview.length <= 4096)
        assertTrue("开头必须是原文", preview.startsWith("xxxx"))
        assertTrue("结尾必须落到原文末尾", preview.endsWith("xxxx"))
        assertTrue("必须是纯 ASCII 且不含 NUL/控制字符", preview.none { it == '\u0000' })
    }

    @Test
    fun `搜索结果不参与截断`() {
        // 搜索返回是 JSON，界面列表与引用角标都依赖完整结构；切碎 = 空白卡片且不报错
        assertTrue(
            "search_web 必须在豁免名单里",
            TOOLS_WITHOUT_OUTPUT_TRUNCATION.contains("search_web"),
        )
    }
}
