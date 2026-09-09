package me.rerere.rikkahub.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v218：架构边界测试（第 0 阶段门禁）。
 *
 * 1) agent 包禁止引用圆桌与主会话组件：
 *    - import 禁止：me.rerere.rikkahub.service.RoundTable*、ConversationSession、ChatService、
 *      me.rerere.rikkahub.data.db.AppDatabase、MessageNode（主消息节点，防止代理内容进主会话）
 *    - 代码行禁止出现 RoundTable（注释中的说明文字除外，字段名 parentMessageNodeId 除外）
 * 2) 圆桌相关源码禁止引用 agent 包（双向隔离）。
 */
class AgentDependencyBoundaryTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val mainSrc: File = run {
        // Gradle 测试工作目录 = app 模块目录；IDE 直跑时可能是项目根目录
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub"),
        )
        candidates.firstOrNull { it.exists() }
            ?: candidates.first()
    }

    private fun collectKtFiles(dir: File): List<File> =
        if (!dir.exists()) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    /** 注释行（Kotlin 块注释 * 行、// 行）不算违规 */
    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }

    @Test
    fun `agent 包不引用圆桌或主会话组件`() {
        val agentDir = File(mainSrc, "agent")
        val files = collectKtFiles(agentDir)
        assertTrue(
            "agent 源码目录不存在: $agentDir（user.dir=${moduleDir.absolutePath}）",
            files.isNotEmpty()
        )

        val forbiddenImports = listOf(
            "me.rerere.rikkahub.service.RoundTable",
            "me.rerere.rikkahub.service.ConversationSession",
            "me.rerere.rikkahub.service.ChatService",
            "me.rerere.rikkahub.data.db.AppDatabase",
            "me.rerere.rikkahub.data.model.MessageNode",
            "me.rerere.rikkahub.data.db.entity.MessageNodeEntity",
        )

        val importViolations = files.flatMap { file ->
            file.readText().lineSequence()
                .filter { it.trimStart().startsWith("import") }
                .filter { line -> forbiddenImports.any { line.contains(it) } }
                .map { "${file.name}: ${it.trim()}" }
        }
        assertTrue(
            "agent 包违规 import（必须为零）:\n${importViolations.joinToString("\n")}",
            importViolations.isEmpty()
        )

        // 代码行（非注释、非 import）不允许出现 RoundTable 类型引用
        val codeViolations = files.flatMap { file ->
            file.readText().lineSequence()
                .filter { line ->
                    !isComment(line) &&
                        !line.trimStart().startsWith("import") &&
                        !line.trimStart().startsWith("package")
                }
                .filter { it.contains("RoundTable") }
                .map { "${file.name}: ${it.trim()}" }
        }
        assertTrue(
            "agent 包代码行违规引用 RoundTable（必须为零）:\n${codeViolations.joinToString("\n")}",
            codeViolations.isEmpty()
        )
    }

    @Test
    fun `圆桌相关源码不引用 agent 包`() {
        val forbidden = "me.rerere.rikkahub.agent"
        val roundTableFiles = buildList {
            val serviceDir = File(mainSrc, "service")
            if (serviceDir.exists()) {
                addAll(
                    serviceDir.walkTopDown()
                        .filter { it.isFile && it.name.startsWith("RoundTable") && it.name.endsWith(".kt") }
                        .toList()
                )
            }
            add(File(mainSrc, "ui/pages/chat/RoundTableSeatPanel.kt"))
            add(File(mainSrc, "ui/components/ai/FilesPicker.kt"))
        }.filter { it.exists() }
        assertTrue("未找到任何圆桌相关源码文件", roundTableFiles.isNotEmpty())

        val violations = roundTableFiles.flatMap { file ->
            file.readText().lineSequence()
                .filter { line -> !isComment(line) }
                .filter { it.contains(forbidden) }
                .map { "${file.name}: ${it.trim()}" }
        }
        assertTrue(
            "圆桌源码违规引用 agent 包（必须为零）:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
