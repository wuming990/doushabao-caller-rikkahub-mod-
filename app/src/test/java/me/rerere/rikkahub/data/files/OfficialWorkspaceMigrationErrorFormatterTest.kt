package me.rerere.rikkahub.data.files

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复制失败弹窗必须告诉用户：复制到第几个文件、卡在哪个位置、在做什么操作、系统原因。
 * 避免再次出现只有“复制失败”四个字的不可诊断界面。
 */
class OfficialWorkspaceMigrationErrorFormatterTest {
    @Test
    fun `failure message includes counts path operation and reason`() {
        val message = OfficialWorkspaceMigrationErrorFormatter.format(
            copiedFiles = 12,
            copiedDirectories = 3,
            currentPath = "skills/demo",
            operation = "读取官方文件",
            causeMessage = "Permission denied",
        )

        assertTrue(message.contains("12"))
        assertTrue(message.contains("3"))
        assertTrue(message.contains("skills/demo"))
        assertTrue(message.contains("读取官方文件"))
        assertTrue(message.contains("Permission denied"))
    }

    @Test
    fun `blank reason and empty path fall back to readable text`() {
        val message = OfficialWorkspaceMigrationErrorFormatter.format(
            copiedFiles = 0,
            copiedDirectories = 0,
            currentPath = "",
            operation = "读取文件夹",
            causeMessage = "   ",
        )

        assertTrue(message.contains("系统没有提供具体原因"))
        assertTrue(message.contains("工作区根目录"))
    }
}
