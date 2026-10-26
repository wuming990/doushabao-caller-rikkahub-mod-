package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v303：两个速度的零件防覆盖守卫。
 *
 * 只读源码做文本断言（不执行逻辑），因为这几处埋在 Compose 与生成循环里、单测造不出对象。
 * 真正的换算逻辑单测在同目录 TokenSpeedTest。合并上游后本测试变红 = 这个功能被整覆盖了，
 * 必须手工融合，不许直接把测试改绿。
 */
class TokenSpeedDisplayGuardTest {

    private val root: java.io.File? by lazy {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun source(vararg path: String): String {
        val dir = checkNotNull(root) { "找不到工程根目录" }
        return java.io.File(dir, path.joinToString("/")).readText()
    }

    @Test
    fun `界面两个速度与标签没被覆盖`() {
        val nerd = source(
            "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt"
        )
        assertTrue(nerd.contains("message.cumulativeGenerationMs"))
        assertTrue(nerd.contains("message.cumulativeResponseMs"))
        // v1014 三审 P3：这两条原来钉的是界面上的「生成 / 响应」字样，而 v1014 把两格改成了
        // 「速度 / 响应速度」两行 —— 那时它们只剩注释能满足，变成「删注释才红、删功能反而绿」
        // 的假守卫。改成钉真实符号与文案键：功能被整覆盖掉时必须当场红。
        assertTrue(nerd.contains("chat_message_usage_response_speed"))
        assertTrue(nerd.contains("SpeedReadout"))
        assertTrue(nerd.contains("headlineTps"))
    }

    @Test
    fun `底层计时与消息字段没被覆盖`() {
        val handler = source("ai/src/main/java/me/rerere/ai/ui/StreamChunkHandler.kt")
        assertTrue(handler.contains("firstContentNanos"))
        assertTrue(handler.contains("val roundTiming: RoundTiming"))
        val message = source("ai/src/main/java/me/rerere/ai/ui/Message.kt")
        assertTrue(message.contains("val cumulativeResponseMs: Long? = null"))
        assertTrue(message.contains("val cumulativeGenerationMs: Long? = null"))
    }

    @Test
    fun `生成循环的累计账没被覆盖`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(loop.contains("cumulativeResponseMs = entryResponseMs + roundsResponseMs"))
        assertTrue(loop.contains("cumulativeGenerationMs = totalGenerationMs"))
    }
}
