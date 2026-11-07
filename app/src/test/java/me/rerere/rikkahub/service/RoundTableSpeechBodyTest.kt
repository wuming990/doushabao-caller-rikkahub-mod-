package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.withoutRoundTableToolParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1029：圆桌「讨论只保留发言正文」。
 *
 * 用户要求直接复用讨论群组已经验证过的那套逻辑（v321 的群聊同名开关）：
 * 对话记录一个字都不删，只在下一次请求里剥掉圆桌页的搜索、工具与思考。
 * 这里既测共用过滤函数本身，也钉住圆桌页标记、请求视图接线与界面开关。
 */
class RoundTableSpeechBodyTest {

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")

    private fun source(relative: String): String {
        val file = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        ).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    private fun resource(relative: String): String {
        val file = listOf(
            File(moduleDir, "src/main/res/$relative"),
            File(moduleDir, "app/src/main/res/$relative"),
        ).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到资源 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    private fun toolPart(id: String = "t1") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "search",
        input = "{}",
        output = listOf(UIMessagePart.Text("搜索结果原文")),
    )

    private fun roundTablePage(): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("最终结论"),
            UIMessagePart.Image("file:///workspace/result.png"),
            toolPart(),
            UIMessagePart.ServerTool(
                toolCallId = "s1",
                toolName = "google_search",
                status = ServerToolStatus.COMPLETED,
            ),
            UIMessagePart.Reasoning("思考过程"),
        ),
        fromRoundTable = true,
    )

    @Test
    fun `只改圆桌页的请求视图，正文与附件保留，工具搜索与思考被摘掉`() {
        val normal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("普通聊天"), toolPart("n1")),
        )
        val page = roundTablePage()

        val filtered = listOf(page, normal).withoutRoundTableToolParts()

        assertEquals(2, filtered.size)
        assertSame("普通聊天的消息一个字都不许动", normal, filtered[1])
        assertEquals(2, filtered[0].parts.size)
        assertEquals("最终结论", (filtered[0].parts[0] as UIMessagePart.Text).text)
        assertEquals(
            "file:///workspace/result.png",
            (filtered[0].parts[1] as UIMessagePart.Image).url,
        )
        assertTrue(
            "工具、搜索与思考都不能进请求",
            filtered[0].parts.none {
                it is UIMessagePart.Tool || it is UIMessagePart.ServerTool || it is UIMessagePart.Reasoning
            },
        )
        assertEquals("原对话记录必须保留全部 5 个 part，不许删数据", 5, page.parts.size)
    }

    @Test
    fun `整条只有工具与思考时留占位，空 metadata Text 也不能当正文`() {
        val onlyTools = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(toolPart(), UIMessagePart.Reasoning("只有思考")),
            fromRoundTable = true,
        )
        val metadataOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "",
                    metadata = buildJsonObject { put("rt_role", "FINAL") },
                ),
                toolPart("t2"),
                UIMessagePart.Reasoning("只有思考"),
            ),
            fromRoundTable = true,
        )

        val filtered = listOf(onlyTools, metadataOnly).withoutRoundTableToolParts()

        filtered.forEach { message ->
            assertEquals(1, message.parts.size)
            val placeholder = (message.parts[0] as UIMessagePart.Text).text
            assertTrue("必须明确说明正文为空", placeholder.contains("正文为空"))
        }
    }

    @Test
    fun `旧圆桌页没有 fromRoundTable 也能靠 rt_role 识别`() {
        val legacyPage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "旧版本留下的圆桌结论",
                    metadata = buildJsonObject { put("rt_role", "FINAL") },
                ),
                toolPart("old"),
                UIMessagePart.Reasoning("旧的思考"),
            ),
        )

        val filtered = listOf(legacyPage).withoutRoundTableToolParts().single()

        assertEquals(1, filtered.parts.size)
        assertEquals("旧版本留下的圆桌结论", (filtered.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `没有可摘的内容时原样返回，不制造无谓副本`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("正文")),
            fromRoundTable = true,
        )

        assertSame(message, listOf(message).withoutRoundTableToolParts().single())
    }

    @Test
    fun `过滤函数必须复用群聊判定，不许另写一套`() {
        val groupChat = source("data/ai/prompts/GroupChat.kt")
        val start = groupChat.indexOf("fun List<UIMessage>.withoutRoundTableToolParts()")
        val end = groupChat.indexOf("fun List<UIMessage>.withoutGroupChatToolParts(", start)
        assertTrue("找不到圆桌过滤函数", start >= 0 && end > start)
        val body = groupChat.substring(start, end)
        assertTrue("必须复用 isGroupToolPart", body.contains("isGroupToolPart(part)"))
        assertTrue("必须复用 isGroupReasoningPart", body.contains("isGroupReasoningPart(part)"))
    }

    @Test
    fun `主对话 generateText 的 messages 入参不许被裁剪，开关只能传参`() {
        val chatService = source("service/ChatService.kt")
        val callStart = chatService.lastIndexOf("generationLoop.generateText(")
        val callEnd = chatService.indexOf("memories = memoryRepository.getFiles(", callStart)
        assertTrue("找不到主对话 generateText 调用", callStart >= 0 && callEnd > callStart)
        val call = chatService.substring(callStart, callEnd)

        assertTrue(
            "messages 必须传原始 currentMessages（生成回写基线）",
            call.contains("messages = conversation.currentMessages.let {"),
        )
        assertFalse(
            "绝不能在 messages 入参上裁剪：那份会被按 id 写回对话，等于永久删记录",
            call.contains("withoutRoundTableToolParts"),
        )
        assertTrue(
            "开关必须以 stripRoundTableToolParts 传给生成层",
            call.contains("stripRoundTableToolParts = settings.roundTableSetting.keepOnlySpeechBody,"),
        )
    }

    @Test
    fun `生成层默认关闭，且过滤只做在请求视图上`() {
        val loop = source("data/ai/GenerationLoop.kt")
        assertEquals(
            "generateText 与内部函数的新参数默认都必须是 false",
            2,
            Regex("stripRoundTableToolParts: Boolean = false,").findAll(loop).count(),
        )
        val filterIndex = loop.indexOf("if (stripRoundTableToolParts)")
        val limitIndex = loop.indexOf(".limitContext(assistant.contextMessageLimit)", filterIndex)
        assertTrue("找不到请求视图里的圆桌过滤", filterIndex >= 0)
        assertTrue("过滤必须在 limitContext 之前（与群聊同一位置）", limitIndex > filterIndex)
        assertTrue(
            "必须调用共用的 withoutRoundTableToolParts",
            loop.substring(filterIndex, limitIndex).contains("it.withoutRoundTableToolParts()"),
        )
    }

    @Test
    fun `圆桌页必须打来源标记，圆桌自己的两处上文都要按开关过滤`() {
        val chatService = source("service/ChatService.kt")

        val writeStart = chatService.indexOf("private suspend fun writeRoundTablePage(")
        val writeEnd = chatService.indexOf("/** v209：删掉某个槽位", writeStart)
        assertTrue("找不到 writeRoundTablePage 切片", writeStart >= 0 && writeEnd > writeStart)
        assertTrue(
            "圆桌页必须打 fromRoundTable 标记",
            chatService.substring(writeStart, writeEnd).contains("message.copy(fromRoundTable = true)"),
        )

        assertTrue(
            "runRoundTable 的 baseMessages 要按开关过滤",
            chatService.contains("baseMessages = conversation.currentMessages.let { messages ->") &&
                chatService.contains("messages.withoutRoundTableToolParts()"),
        )
        assertTrue(
            "手动综合已有方案的 baseMessages 也要按开关过滤",
            chatService.contains(
                "baseMessages = conversation.messageNodes.take(nodeIndex).map { it.currentMessage }.let { messages ->"
            ),
        )

        val messageModel = File(moduleDir, "ai/src/main/java/me/rerere/ai/ui/Message.kt").let {
            if (it.exists()) it else File(moduleDir, "../ai/src/main/java/me/rerere/ai/ui/Message.kt")
        }
        assertTrue(
            "UIMessage 必须有 fromRoundTable 字段",
            messageModel.readText().contains("val fromRoundTable: Boolean = false"),
        )
    }

    @Test
    fun `圆桌设置界面必须有开关且写回配置`() {
        val picker = source("ui/components/ai/FilesPicker.kt")

        assertTrue(picker.contains("R.string.round_table_body_only_title"))
        assertTrue(picker.contains("roundTable.keepOnlySpeechBody"))
        assertTrue(picker.contains("it.copy(keepOnlySpeechBody = checked)"))
    }

    @Test
    fun `子代理四个开关的介绍文字必须删掉，标题不再带 Codex 风格`() {
        val picker = source("ui/components/ai/FilesPicker.kt")
        listOf(
            "agent_tools_switch_title",
            "agent_tools_assistant_switch_title",
            "agent_tools_memory_title",
            "agent_tools_mcp_title",
        ).forEach { key ->
            val index = picker.indexOf("R.string.$key")
            assertTrue("找不到 $key 的开关", index >= 0)
            val block = Regex(
                """R\.string\.$key\)[\s\S]{0,400}?checked ="""
            ).find(picker.substring(index))?.value
                ?: throw AssertionError("$key 的开关块不完整")
            assertTrue("$key 的介绍文字必须删掉", block.contains("desc = null"))
            assertFalse("$key 不许再挂任何说明资源", block.contains("stringResource(R.string.agent_tools_"))
        }

        val zh = resource("values-zh/strings.xml")
        assertFalse(
            "中文标题不再带（Codex 风格）",
            zh.contains("<string name=\"agent_tools_section_title\">子代理（Codex 风格）</string>"),
        )
        assertFalse(
            "中文开关标题不再带（Codex 风格）",
            zh.contains("<string name=\"agent_tools_switch_title\">子代理（Codex 风格）</string>"),
        )
        val en = resource("values/strings.xml")
        assertFalse(
            "英文标题不再带 Codex style",
            en.contains("<string name=\"agent_tools_section_title\">Subagents (Codex style)</string>"),
        )
        assertFalse(
            "英文开关标题不再带 Codex style",
            en.contains("<string name=\"agent_tools_switch_title\">Subagents (Codex style)</string>"),
        )
    }

    @Test
    fun `关于页官网必须指向我们的仓库`() {
        val about = source("ui/pages/setting/SettingAboutPage.kt")
        val url = "https://github.com/wuming990/doushabao-caller-rikkahub-mod-"

        assertTrue("官网行必须使用我们的仓库地址", about.contains("context.openUrl(\"$url\")"))
        assertTrue("必须显示我们的仓库地址", about.contains("Text(\"$url\")"))
        assertFalse("官网不许再指向 rikka-ai.com", about.contains("https://rikka-ai.com"))
    }

    @Test
    fun `圆桌新开关中英文文案齐备`() {
        listOf(
            "round_table_body_only_title",
            "round_table_body_only_desc",
        ).forEach { key ->
            assertTrue("中文缺少 $key", resource("values-zh/strings.xml").contains("name=\"$key\""))
            assertTrue("英文缺少 $key", resource("values/strings.xml").contains("name=\"$key\""))
        }
    }
}
