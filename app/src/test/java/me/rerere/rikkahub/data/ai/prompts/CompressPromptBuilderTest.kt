package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖式自定义提示词模型下：基础提示词（默认或用户覆盖）原样生效，
 * 单次附加说明最后附加；留空附加时基础保持完全不变。
 */
class CompressPromptBuilderTest {
    @Test
    fun `blank additional keeps base unchanged`() {
        assertEquals("BASE", buildCompactionPrompt("BASE", ""))
        assertEquals("BASE", buildCompactionPrompt("BASE", " \n "))
    }

    @Test
    fun `custom base is used as-is when provided`() {
        assertEquals("请用中文总结", buildCompactionPrompt("请用中文总结", ""))
    }

    @Test
    fun `additional prompt is appended after base`() {
        val out = buildCompactionPrompt("BASE", "本次附加")
        assertTrue(out.startsWith("BASE"))
        assertTrue(out.endsWith("本次附加"))
        assertTrue(out.indexOf("本次附加") > out.indexOf("BASE"))
    }
}
