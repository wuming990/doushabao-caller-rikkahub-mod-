package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1014：用量面板对齐官方显示 + 速度读数必须同源。
 *
 * 用户拿两张截图对比出来的问题有两个，第二个是真 bug：
 * 1. 官方那几行明细（步数 / 总输入 / 缓存总量 / 总输出 / 速度 / 时长）我们被 `steps > 1`
 *    整块挡住了，单步对话时面板里一行明细都没有；
 * 2. 底部小字与面板**各算一套**：同一条消息上出现过「底部 8.9 tok/s、面板 136.7 tok/s」。
 *    官方两处是同一个数。
 *
 * 速度口径按用户拍板保持我们自己的算法（v303 那套：分子是真实累计输出，分母不含工具执行
 * 与用户审批等待），本轮只把**显示**对齐官方。官方只有一行速度，我们多出来的那行按自己的
 * 标签叫「响应速度」。
 */
class V1014UsagePanelGuardTest {

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

    private val nerdLine =
        "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt"

    /** 只看用量面板那一段，别把底部小字的判据混进来（底部单步不占位是**故意**的）。 */
    private fun panelSource(): String {
        val text = source(nerdLine)
        val from = text.indexOf("private fun UsageDetails(")
        assertTrue("找不到 UsageDetails 面板函数", from >= 0)
        val to = text.indexOf("private data class UsageSegment", from)
        assertTrue("面板函数终点定位失败", to > from)
        return text.substring(from, to)
    }

    // ── 1. 读数优先级（行为断言） ──

    @Test
    fun `底部小字优先取纯吐字口径，取不到才退回响应口径`() {
        assertEquals(136.7f, SpeedReadout(136.7f, 76.6f, 81.6f).headlineTps!!, 0.001f)
        assertEquals(76.6f, SpeedReadout(null, 76.6f, 81.6f).headlineTps!!, 0.001f)
    }

    @Test
    fun `一个数都算不出来时不许编一个`() {
        assertNull(SpeedReadout(null, null, null).headlineTps)
    }

    // ── 2. 面板结构 ──

    @Test
    fun `明细行不再被步数挡住，单步对话也要有`() {
        val panel = panelSource()
        assertFalse(
            "steps > 1 的整块开关不许再包住明细行（用户截图里面板空就是因为这个）",
            panel.contains("if (stats.steps > 1)"),
        )
        assertTrue(
            panel.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_step_count), stats.steps.toString())"),
        )
        assertTrue(
            panel.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_total_input), total.promptTokens.exact())"),
        )
        assertTrue(
            panel.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_total_output), total.completionTokens.exact())"),
        )
    }

    @Test
    fun `速度与时长改成行，两处同源，且速度行只放纯吐字口径`() {
        val text = source(nerdLine)
        assertTrue(
            text.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_speed), \"\${it.toFixed(1)} tok/s\")"),
        )
        assertTrue(
            text.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_response_speed), \"\${it.toFixed(1)} tok/s\")"),
        )
        assertTrue(
            text.contains("UsageDetailRow(stringResource(R.string.chat_message_usage_duration), \"\${it.toFixed(1)}s\")"),
        )
        // 三审 P2：这一行过去挂 headlineTps（会兜到响应速度），标签写着「速度」印的却是
        // 含首字等待的数。现在只许挂 generationTps。
        assertTrue("速度行又去顶替响应口径了", text.contains("readout?.generationTps?.let {"))
        assertFalse("速度行不许再挂 headlineTps", text.contains("headlineTps?.let {"))
        // 底部小字必须与面板同源；旧的独立算法不许留在原地
        assertFalse(
            "底部又单独算一套的话，就会再出现 8.9 与 136.7 并排",
            text.contains("usage.completionTokens.toFloat() / it * 1000"),
        )
        assertTrue(text.contains("tps = SpeedReadout.of(this, usage)?.headlineTps"))
        assertTrue(text.contains("val readout = SpeedReadout.of(message, total)"))
    }

    @Test
    fun `旧消息的生成耗时不许被丢掉`() {
        // 三审 P2：上一版 fallback 只算墙钟，而旧消息存着 generationTimeMs —— 丢掉它会让
        // 旧消息的速度数凭空变慢。兜底链必须还在。
        val text = source(nerdLine)
        assertTrue(text.contains("computeTokenSpeed(total.completionTokens, message.generationTimeMs)"))
    }

    @Test
    fun `两个速度相等时不重复占一行`() {
        val text = source(nerdLine)
        assertTrue(
            "只有兜底口径可用时两个数会相同，重复一行就是噪音",
            text.contains("?.takeIf { it != readout.generationTps }"),
        )
    }

    @Test
    fun `假插值写法不许回来`() {
        // v1013 / v1014 两次真踩：写成 "${'$'}{'{'}it...{'}'}" 时 Kotlin 打印的是模板文字本身，
        // 面板会显示 {it.toFixed(1)} 而不是数字。
        val text = source(nerdLine)
        assertFalse("面板里又出现假插值", text.contains("\${'{'}"))
        // 反证：正确写法必须真的在位（只删坏写法会被误判成通过）
        assertTrue("正确的真插值写法不在位", text.contains("\"\${it.toFixed(1)} tok/s\""))
    }

    @Test
    fun `摘掉的死字段不许被人加回来`() {
        // v1014 摘除 NerdStats.durationMillis：它以前只给底部那个速度数当分母，
        // 现在底部与面板同源，留着只会让人以为时长还从这儿出。
        val text = source(nerdLine)
        assertFalse(text.contains("val durationMillis: Long?"))
        assertFalse(text.contains("durationMillis = durationMillis"))
    }

    @Test
    fun `新文案两种语言都在，别只加一边`() {
        listOf(
            "app/src/main/res/values/strings.xml" to "Response speed",
            "app/src/main/res/values-zh/strings.xml" to "响应速度",
        ).forEach { (path, expect) ->
            val text = source(path)
            val line = text.lines().firstOrNull { it.contains("chat_message_usage_response_speed") }
            assertTrue("$path 缺 chat_message_usage_response_speed", line != null)
            assertTrue("$path 内容不对：$line", line!!.contains(expect))
        }
    }
}
