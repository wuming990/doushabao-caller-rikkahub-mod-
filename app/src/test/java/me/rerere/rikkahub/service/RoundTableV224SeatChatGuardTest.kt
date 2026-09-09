package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v224 门禁（源码级扫描）：锁死用户在 v223 验收时提出的两条整改要求，防止后续版本回退。
 *
 * 用户原话：
 * 1. 「不行，不是我想要的，我想要的是点击『查看过程』可以直接进入那个对话观看过程，不要这种。」
 *    → v223 的自绘只读页作废；每个位置必须有一条**真实 Conversation**，
 *      「查看过程」直接进原生聊天页，且必须能回到主对话。
 * 2. 「把子代理这些东西都放到一起，不要散落在外面影响我启用圆桌模式。」
 *    → 子代理设置必须收进独立折叠卡；总开关关闭后「代理活动」面板不得再占位。
 */
class RoundTableV224SeatChatGuardTest {

    private val moduleDir = File(System.getProperty("user.dir"))

    private fun sourceFile(relative: String): File? = listOf(
        File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
        File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
    ).firstOrNull { it.exists() }

    private fun source(relative: String): String =
        sourceFile(relative)?.readText()
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")

    private fun resource(relative: String): String {
        val file = listOf(
            File(moduleDir, "src/main/res/$relative"),
            File(moduleDir, "app/src/main/res/$relative"),
        ).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到资源 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    @Test
    fun `每个圆桌位置必须绑定一条真实对话`() {
        val seat = source("service/RoundTableSeat.kt")
        val run = source("service/RoundTableRun.kt")
        val chatService = source("service/ChatService.kt")

        assertTrue(
            "座位必须保存它自己那条对话的 ID",
            seat.contains("val chatConversationId: String?"),
        )
        assertTrue("座位必须提供 hasSeatChat 判定", seat.contains("val hasSeatChat: Boolean"))
        assertTrue(
            "状态机必须能把对话 ID 绑到座位上",
            run.contains("fun bindSeatConversation("),
        )
        assertTrue(
            "必须真的新建 Conversation，而不是自绘页面读主对话的某一页",
            chatService.contains("createRoundTableSeatConversation") &&
                chatService.contains("conversationRepo.insertConversation("),
        )
        assertTrue(
            "过程对话必须归到专门的文件夹，避免污染用户平时的对话列表",
            chatService.contains("ensureRoundTableProcessFolder") &&
                chatService.contains("folderRepository.createFolder("),
        )
    }

    @Test
    fun `位置的思考与工具过程必须实时写进它自己的对话`() {
        val chatService = source("service/ChatService.kt")

        assertTrue(
            "必须有把一轮问答写进过程对话的方法",
            chatService.contains("mirrorRoundTableSeatTurn"),
        )
        assertTrue(
            "执行器必须在流式过程中同步过程对话",
            chatService.contains("mirrorSeatChat("),
        )
        assertTrue(
            "过程对话必须真落库，否则用户开着那页看不到实时刷新",
            chatService.contains("mirrorRoundTableSeatTurn") && chatService.contains("saveConversation("),
        )
        assertTrue(
            "拍板位置也要有过程对话镜像",
            chatService.contains("streamFinalSnapshot"),
        )
        assertTrue(
            "终态结果必须同步到过程对话",
            chatService.contains("assistantMessage = tagged"),
        )
        assertTrue(
            "被停止 / 超时之类的说明也要写进过程对话",
            chatService.contains("noteSeatChat(") && chatService.contains("appendRoundTableSeatNote"),
        )
        assertTrue(
            "每轮尝试各占一组节点，续跑要看得出是新的一问一答",
            chatService.contains("class RoundTableSeatTurn"),
        )
    }

    @Test
    fun `查看过程必须进原生聊天页并能返回主对话`() {
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")
        val chatPage = source("ui/pages/chat/ChatPage.kt")
        val route = source("RouteActivity.kt")

        assertTrue("面板回调必须传对话 ID", panel.contains("onOpenSeatChat: (String) -> Unit"))
        assertTrue(
            "没建好过程对话时不能给出会点空的入口",
            panel.contains("if (seat.hasSeatChat)"),
        )
        assertTrue(
            "必须跳转到原生聊天页而不是自绘页面",
            chatPage.contains("Screen.Chat(") && chatPage.contains("showBack = true"),
        )
        assertTrue("路由必须带返回标记", route.contains("val showBack: Boolean = false"))
        assertTrue("聊天页必须接收返回标记", chatPage.contains("showBack: Boolean = false"))
        assertTrue(
            "顶栏必须能显示返回按钮",
            chatPage.contains("onBack: (() -> Unit)?") && chatPage.contains("if (onBack != null)"),
        )
    }

    @Test
    fun `v223 的自绘座位页必须已经移除`() {
        assertFalse(
            "用户明确否定了自绘只读页，不能再留在源码里",
            sourceFile("ui/pages/chat/RoundTableSeatPage.kt") != null,
        )
        val route = source("RouteActivity.kt")
        val chatPage = source("ui/pages/chat/ChatPage.kt")
        assertFalse("路由不得再注册自绘页", route.contains("RoundTableSeatChat"))
        assertFalse("聊天页不得再引用自绘页路由", chatPage.contains("RoundTableSeatChat"))
    }

    @Test
    fun `子代理设置必须收进折叠卡且不许挤占圆桌`() {
        val picker = source("ui/components/ai/FilesPicker.kt")

        assertTrue(
            "子代理设置必须有独立折叠开关",
            picker.contains("var agentExpanded by remember"),
        )
        assertTrue(
            "折叠卡必须使用子代理专属标题",
            picker.contains("R.string.agent_tools_section_title"),
        )
        assertTrue(
            "子代理各项设置必须放在折叠卡内",
            picker.contains("if (agentExpanded) {"),
        )

        val roundTableIndex = picker.indexOf("R.string.round_table_setting_title")
        val agentIndex = picker.indexOf("R.string.agent_tools_section_title")
        assertTrue("必须同时存在圆桌与子代理两张卡", roundTableIndex >= 0 && agentIndex >= 0)
        assertTrue(
            "圆桌必须排在子代理前面，用户主要用圆桌",
            roundTableIndex < agentIndex,
        )

        val switchIndex = picker.indexOf("R.string.agent_tools_switch_title")
        assertTrue(
            "子代理总开关必须位于折叠卡之后（即卡内），不能再平铺在圆桌上方",
            switchIndex > agentIndex,
        )
    }

    @Test
    fun `子代理关闭后代理活动面板不得占位`() {
        val chatPage = source("ui/pages/chat/ChatPage.kt")

        assertTrue(
            "代理活动面板必须受总开关控制",
            chatPage.contains("if (setting.enableAgentTools) {"),
        )
        val guardIndex = chatPage.indexOf("if (setting.enableAgentTools) {")
        val panelIndex = chatPage.indexOf("AgentActivityPanel(")
        assertTrue("代理活动面板必须包在开关判断里", guardIndex in 0 until panelIndex)
    }

    @Test
    fun `v224 新增文案必须中英文齐备`() {
        val en = resource("values/strings.xml")
        val zh = resource("values-zh/strings.xml")

        listOf(
            "round_table_seat_folder_name",
            "round_table_seat_chat_title",
            "round_table_seat_chat_back",
            "agent_tools_section_title",
            "agent_tools_section_summary_on",
            "agent_tools_section_summary_off",
            "agent_tools_section_desc",
        ).forEach { key ->
            assertTrue("英文缺少文案 $key", en.contains("name=\"$key\""))
            assertTrue("中文缺少文案 $key", zh.contains("name=\"$key\""))
        }
    }

    @Test
    fun `圆桌过程对话不得引用子代理包`() {
        listOf(
            "service/RoundTableSeat.kt",
            "service/RoundTableRun.kt",
            "service/RoundTableCoordinator.kt",
            "ui/pages/chat/RoundTableSeatPanel.kt",
        ).forEach { relative ->
            val violations = source(relative).lineSequence()
                .filter { line ->
                    val trimmed = line.trimStart()
                    !trimmed.startsWith("*") && !trimmed.startsWith("//")
                }
                .filter { it.contains("me.rerere.rikkahub.agent") }
                .toList()
            assertTrue("$relative 违规引用子代理包：$violations", violations.isEmpty())
        }
    }
}
