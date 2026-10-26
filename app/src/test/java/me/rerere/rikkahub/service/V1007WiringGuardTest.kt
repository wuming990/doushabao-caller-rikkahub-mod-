package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1007 接线守卫：这一轮修的四件事都属于「删一行/挪一段就静默复发，编译器抓不到」的形态。
 *
 * 与同目录其它 Guard 测试一样，**这里全部是读源码做字符串比对**，只证明接线还在，
 * 不证明逻辑正确。逻辑正确性由 AutoCompressTriggerResolutionTest + 真机验收负责。
 *
 * 四条：
 * 1. 自动压缩的「按模型触发值」必须按**会话当前模型**取（官方 3.1 把模型固定到会话上）；
 * 2. 自动压缩建分支后必须搬对话级设置（不搬 = 群组面板凭空消失，v318 真机踩过）；
 * 3. 设置→模型页必须有「压缩模型」的写入点（官方 3.1 删掉了这一项，数据还在但没有入口）；
 * 4. 消息下面的统计行里，速度/时长必须写在 segments.forEach **外面**（写在里面会按颜色段数
 *    重复好几遍，并把秒数挤成竖排 —— 用户报「统计很杂乱」的那次）；
 * 5. 备份恢复走的手写写路径（persistSettings）必须覆盖官方 3.1 新增的 5 个字段。
 */
class V1007WiringGuardTest {

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

    /** substringBefore 找不到分隔符时会返回整串，切片就会静默退化成整份文件；先确认两个分隔符都在。 */
    private fun slice(text: String, start: String, end: String): String {
        assertTrue("找不到起始标记：$start", text.contains(start))
        assertTrue("找不到结束标记：$end", text.contains(end))
        return text.substringAfter(start).substringBefore(end)
    }

    @Test
    fun `自动压缩的按模型触发值必须按会话模型取`() {
        val chatService = source(
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
        )
        val body = slice(
            chatService,
            "fun shouldAutoCompressConversation(",
            "fun estimateConversationTokens(",
        )
        assertTrue(
            "自动压缩判定必须用 settings.getChatModelOf(conversation) 取会话模型，否则按模型设的触发值不生效",
            body.contains("getChatModelOf(conversation)"),
        )
    }

    @Test
    fun `自动压缩建的分支必须搬对话级设置`() {
        val chatService = source(
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
        )
        val body = slice(
            chatService,
            "suspend fun autoCompressForkConversation(",
            "// ---- 对话状态更新 ----",
        )
        assertTrue(
            "自动压缩分支漏掉 carryOverForkSettings 时，新对话里讨论群组面板会凭空消失",
            body.contains("carryOverForkSettings(conversationId, forkConversation.id)"),
        )
    }

    @Test
    fun `设置里的压缩模型必须有写入点`() {
        val page = source(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt"
        )
        assertTrue(
            "官方 3.1 删掉了压缩模型入口，我们必须补回来，且必须真的写回 compressModelId",
            page.contains("copy(compressModelId = it.id)"),
        )
    }

    @Test
    fun `统计行里的速度与时长不许写在颜色段循环里`() {
        val nerdLine = source(
            "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt"
        )
        // v1014：终点锚从「// v303：两个速度都显示」换成 v269 那条 —— 不是把判据放松，
        // 而是 v303 那段注释连同速度读数一起搬进了 [SpeedReadout] 与面板行，锚点搬家了。
        // 判据本身一字未改：循环体里不许出现 StatsItem。
        // 「速度/时长一条消息只出现一次」由 V1014UsagePanelGuardTest 从另一头钉住。
        val inLoop = slice(
            nerdLine,
            "segments.forEach { segment ->",
            "// v269：续跑次数",
        )
        assertFalse(
            "速度/时长写在 segments.forEach 里面会按颜色段数重复显示，且把最后一段挤成竖排",
            inLoop.contains("StatsItem"),
        )
    }

    @Test
    fun `备份恢复这条手写写路径必须覆盖官方 3_1 的五个字段`() {
        val store = source(
            "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"
        )
        // persistSettings 专门给「Koin 还没起来时的备份恢复」用，不经过 SETTINGS_FIELDS；
        // 少一个键 = 恢复完备份该项变回默认/本机旧值。
        val body = slice(store, "private suspend fun persistSettings(", "    // 读取失败时绝不能回退为空配置")
        listOf(
            "preferences[ENABLE_AUTO_TITLE]",
            "preferences[TRANSLATE_TARGET_LANGUAGE]",
            "preferences[UPLOAD_S3_CONFIG]",
            "preferences[MEDIA_GENERATION_PROVIDERS]",
            "preferences[SPONSOR_ALERT_DISMISSED_AT]",
        ).forEach { key ->
            assertTrue("备份恢复写路径缺少 $key", body.contains(key))
        }
    }
}
