package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v233 门禁：DeepSeek「专武模式 / 风神模式」必须彻底移除，且不得复活。
 *
 * ## 用户原话
 *
 * 「直接删掉 deepseek 的风神模式吧，测试效果不理想，不要留着占地方还容易产生 bug。」
 *
 * ## 为什么要用测试守住
 *
 * 这套机制横跨 7 个文件（生成链路、聊天服务、变换器、设置项、设置面板、两份文案表），
 * 历史上出现过「源码树被旧快照解包覆盖」导致整版回退的事故。只删一次不够 ——
 * 必须有断言证明它真的没了，否则下一次接手很容易把它连带恢复回来。
 *
 * ## 同时要证明「只删了它，没伤到别人」
 *
 * 圆桌会议、Codex 风格子代理、工作区工具三套机制与专武无关，
 * 这里一并断言它们的关键接线仍在原处。
 */
class DshRemovalGuardTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun repoFile(relative: String): File = File(repoRoot, relative)

    private fun source(relative: String): String {
        val file = repoFile(relative)
        if (!file.exists()) {
            throw AssertionError("找不到源码 $relative（repoRoot=${repoRoot.absolutePath}）")
        }
        return file.readText()
    }

    private val appJava = "app/src/main/java/me/rerere/rikkahub"

    // ---------------------------------------------------------------- 已彻底移除

    @Test
    fun `专武与风神的源码目录必须已经删除`() {
        listOf(
            "$appJava/data/ai/dsh",
            "$appJava/data/ai/router",
            "app/src/test/java/me/rerere/rikkahub/data/ai/dsh",
            "app/src/test/java/me/rerere/rikkahub/data/ai/router",
        ).forEach { relative ->
            assertFalse(
                "目录 $relative 必须已删除（专武 / 风神模式已整体移除）",
                repoFile(relative).exists(),
            )
        }

        listOf(
            "$appJava/data/ai/transformers/RouterGuidanceTransformer.kt",
            "app/src/test/java/me/rerere/rikkahub/data/ai/DshMinimalSystemPromptTest.kt",
        ).forEach { relative ->
            assertFalse(
                "文件 $relative 必须已删除",
                repoFile(relative).exists(),
            )
        }
    }

    @Test
    fun `ChatService 不得再引用专武或风神`() {
        val chatService = source("$appJava/service/ChatService.kt")
        listOf(
            "DshRouter",
            "RouterBand",
            "RouterGuidanceTransformer",
            "routerPersona",
            "routerBand",
            "DshAnchor",
            "dshAnchor",
            "DSH_MINIMAL_SYSTEM_PROMPT",
            "systemPromptOverride",
            "anchorFlavor",
            "asUserMessage",
        ).forEach { symbol ->
            assertFalse(
                "ChatService 不得再出现 $symbol",
                chatService.contains(symbol),
            )
        }
    }

    @Test
    fun `GenerationLoop 必须回到无锚定的形态`() {
        val handler = source("$appJava/data/ai/GenerationLoop.kt")
        listOf(
            "DshAnchorRuntime",
            "DshAnchorUnlock",
            "createDshUnlockTool",
            "DSH_REDUNDANT_APP_TOOL_NAMES",
            "enforceSystemPromptOverride",
            "systemPromptOverride",
            "toolsExclusive",
            "stepAnchor",
            "keepMinimalPrompt",
            "unlockOnDemand",
        ).forEach { symbol ->
            assertFalse(
                "GenerationLoop 不得再出现 $symbol",
                handler.contains(symbol),
            )
        }
    }

    @Test
    fun `工作区提醒必须只剩一种口径`() {
        val transformer = source("$appJava/data/ai/transformers/WorkspaceReminderTransformer.kt")
        listOf("anchorFlavor", "asUserMessage", "str_replace_editor").forEach { symbol ->
            assertFalse(
                "WorkspaceReminderTransformer 不得再出现 $symbol",
                transformer.contains(symbol),
            )
        }
        // 原始口径必须保留，否则模型不知道工作区工具叫什么
        assertTrue(
            "必须仍然点名 workspace_shell",
            transformer.contains("workspace_shell"),
        )
        assertTrue(
            "必须仍然追加到 system 消息",
            transformer.contains("messages.indexOfFirst { it.role == MessageRole.SYSTEM }"),
        )
    }

    @Test
    fun `设置项与设置面板不得再有专武开关`() {
        val prefs = source("$appJava/data/datastore/PreferencesStore.kt")
        listOf("enableDshRouter", "enableDshAnchor", "dsh_anchor_enabled", "DSH_ANCHOR_ENABLED")
            .forEach { symbol ->
                assertFalse("PreferencesStore 不得再出现 $symbol", prefs.contains(symbol))
            }

        val picker = source("$appJava/ui/components/ai/FilesPicker.kt")
        listOf("dshExpanded", "dsh_router_", "dsh_anchor_", "DshRouter", "RouterBand")
            .forEach { symbol ->
                assertFalse("FilesPicker 不得再出现 $symbol", picker.contains(symbol))
            }
    }

    @Test
    fun `两份文案表都不得再有 dsh 条目`() {
        listOf(
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-zh/strings.xml",
        ).forEach { relative ->
            assertFalse(
                "$relative 不得再有 dsh_ 文案",
                source(relative).contains("name=\"dsh_"),
            )
        }
    }

    // ---------------------------------------------------------------- 没有伤到别人

    @Test
    fun `圆桌与子代理的关键接线必须不受影响`() {
        val chatService = source("$appJava/service/ChatService.kt")
        assertTrue(
            "圆桌任务合同必须仍然拿到用户原话（v230 修复不许回退）",
            chatService.contains("coordinator.runGroup(listOf(RoundTableSeatIds.CONTRACT)) { sharedMaterials.toList() }"),
        )
        assertTrue(
            "工作区提醒必须仍然装配",
            chatService.contains("add(workspaceReminderTransformer)"),
        )

        val picker = source("$appJava/ui/components/ai/FilesPicker.kt")
        val roundTableIndex = picker.indexOf("R.string.round_table_setting_title")
        val agentIndex = picker.indexOf("R.string.agent_tools_section_title")
        assertTrue("圆桌与子代理两张折叠卡必须都还在", roundTableIndex >= 0 && agentIndex >= 0)
        assertTrue("圆桌必须仍然排在子代理前面", roundTableIndex < agentIndex)
    }

    @Test
    fun `工作区发布文件工具不得被顺手删掉`() {
        // 历史约束：workspace_publish_file 曾被当成「与 bash 重复」而撤掉，导致用户拿不到产物。
        val tools = source("$appJava/data/ai/tools/WorkspaceTools.kt")
        assertTrue(
            "workspace_publish_file 必须仍然存在",
            tools.contains("workspace_publish_file"),
        )
    }

    @Test
    fun `工作区图片发布必须能直接显示(v288)`() {
        // v288 真机实测（用户截图确认）：
        // · Markdown ![](file://...)          → 空白（markdown 库 XssSafeLinks 黑名单含 file:）
        // · Markdown ![](data:image/png;...)  → 空白
        // · 原始 HTML <img src="file://...">  → 正常显示（不经过那个过滤）
        // 因此发布工具必须：给图片额外产出 Image 部件（工具卡片缩略图）+ 把本地地址回给模型，
        // 并在说明里点明只能用 HTML 标签嵌入。少任何一条，用户就只能看到一张文件卡片。
        val tools = source("$appJava/data/ai/tools/WorkspaceTools.kt")
        assertTrue(
            "必须按 mime 判断是不是图片",
            tools.contains("val isImage = published.mime.startsWith(\"image/\")"),
        )
        assertTrue(
            "图片必须额外产出 Image 部件（否则工具卡片里没有缩略图）",
            tools.contains("add(UIMessagePart.Image(url = published.uri))"),
        )
        assertTrue(
            "Document 部件必须保留（它负责打开/导出/分享）",
            tools.contains("UIMessagePart.Document("),
        )
        assertTrue(
            "必须把本地地址回给模型，否则它只能手工推算宿主机路径",
            tools.contains("put(\"uri\", published.uri)"),
        )
        assertTrue(
            "必须直接给出可粘贴的 HTML 片段",
            tools.contains("put(\"inlineHtml\""),
        )
        assertTrue(
            "工具说明必须点明用 HTML 标签嵌入",
            tools.contains("<img src=\\\"THE_URI\\\" />"),
        )
        assertTrue(
            "工具说明必须警告 markdown 的 file: 写法不可用",
            tools.contains("blocks the file: scheme"),
        )
    }

    @Test
    fun `全仓不得残留任何 dsh 相关标识符`() {
        val offenders = mutableListOf<String>()
        val skipDirs = setOf("build", ".gradle", ".git", ".idea", "baselineprofile")
        val symbols = listOf(
            "DshRouter",
            "DshAnchor",
            "RouterGuidanceTransformer",
            "DSH_MINIMAL_SYSTEM_PROMPT",
            "DSH_REDUNDANT_APP_TOOL_NAMES",
            "enableDshRouter",
            "enableDshAnchor",
        )
        repoRoot.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.name != "DshRemovalGuardTest.kt" }
            .forEach { file ->
                val text = file.readText()
                symbols.filter { text.contains(it) }.forEach { hit ->
                    offenders += "${file.relativeTo(repoRoot).path} -> $hit"
                }
            }
        assertEquals("仍有文件引用已删除的专武 / 风神符号：$offenders", emptyList<String>(), offenders)
    }
}
