package me.rerere.rikkahub.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v223 门禁（源码级扫描）：锁死用户提出的三个圆桌缺陷，防止后续版本回归。
 *
 * 用户原话（同一条需求里同时点名了子代理与圆桌）：
 * 1. 「圆桌会议目前也有这种问题，看不到模型具体在干嘛，很容易误判」；
 * 2. 「没有续跑功能，不稳定的模型已经跑得差不多了，结果截断了，前面的时间和花费都浪费了」；
 * 3. 「每个模型相当于一个额外的对话，直接给我一个直接去到对话里面看的选项…需要保证可以正常回到主对话」。
 */
class RoundTableV223RegressionGuardTest {

    private val moduleDir = File(System.getProperty("user.dir"))

    private fun source(relative: String): String {
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    private fun resource(relative: String): String {
        val candidates = listOf(
            File(moduleDir, "src/main/res/$relative"),
            File(moduleDir, "app/src/main/res/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到资源 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    @Test
    fun `圆桌必须消费 finishReason 做截断识别`() {
        val coordinator = source("service/RoundTableCoordinator.kt")
        val seat = source("service/RoundTableSeat.kt")

        assertTrue(
            "调度器必须读取 message.finishReason，否则半截产出会被当成完整成功",
            coordinator.contains("message.finishReason"),
        )
        assertTrue(
            "必须使用 RoundTableFinishReason.isTruncated 判定截断",
            coordinator.contains("RoundTableFinishReason.isTruncated"),
        )
        assertTrue("座位必须保存 finishReason 字段", seat.contains("val finishReason: String?"))
        assertTrue("座位必须保存 truncated 字段", seat.contains("val truncated: Boolean"))
    }

    @Test
    fun `圆桌必须提供继续输出而不是只能从头重跑`() {
        val seat = source("service/RoundTableSeat.kt")
        val run = source("service/RoundTableRun.kt")
        val coordinator = source("service/RoundTableCoordinator.kt")
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")

        assertTrue("必须存在 Continue 指令", seat.contains("data object Continue : RoundTableSeatCommand"))
        assertTrue("座位必须有 canContinue 判定", seat.contains("val canContinue: Boolean"))
        assertTrue("状态机必须处理 Continue", run.contains("is RoundTableSeatCommand.Continue"))
        assertTrue("续跑必须单独计数", seat.contains("val continuations: Int"))
        assertTrue(
            "调度器必须维护续跑前缀，否则续跑等于从头重写",
            coordinator.contains("continuePrefixes"),
        )
        assertTrue(
            "generate 必须接收 continueFrom 参数",
            coordinator.contains("continueFrom: String?"),
        )
        assertTrue(
            "重试/换模型必须清掉续跑前缀，否则会把旧内容混进新模型",
            coordinator.contains("continuePrefixes.remove(seatId)"),
        )
        assertTrue("面板必须有继续输出按钮", panel.contains("RoundTableSeatCommand.Continue"))
    }

    @Test
    fun `圆桌续跑必须把已产出内容回灌给模型`() {
        val chatService = source("service/ChatService.kt")
        val prompts = source("data/ai/prompts/RoundTable.kt")

        assertTrue(
            "必须定义续跑指令提示词",
            prompts.contains("ROUND_TABLE_CONTINUE_INSTRUCTION"),
        )
        assertTrue(
            "续跑提示词必须明确要求不要重复已写内容",
            prompts.contains("不要重复已经写过的内容"),
        )
        assertTrue(
            "ChatService 必须把续跑上文拼进请求",
            chatService.contains("continuationMessages"),
        )
        assertTrue(
            "必须把续跑产出与旧内容合并成完整一页",
            chatService.contains("mergeRoundTableContinuation"),
        )
    }

    @Test
    fun `圆桌每个座位在跑的过程中必须实时写页`() {
        val chatService = source("service/ChatService.kt")

        assertTrue(
            "非拍板座位必须有实时流式写页方法",
            chatService.contains("streamSeatSnapshot"),
        )
        assertTrue(
            "实时写页必须复用稳定页面 ID，避免刷出多份重复页",
            chatService.contains("stablePageId"),
        )
        assertTrue(
            "实时写页必须节流，不能每个 token 都落库",
            chatService.contains("lastLivePageAt"),
        )
        assertTrue(
            "旧尝试的迟到流式数据不得覆盖新页面",
            chatService.contains("if (!run.isCurrentAttempt(seat.seatId, attempt)) return"),
        )
    }

    @Test
    fun `圆桌必须有可返回主对话的查看过程入口`() {
        val route = source("RouteActivity.kt")
        val chatPage = source("ui/pages/chat/ChatPage.kt")
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")

        // v224 起「查看过程」不再是自绘页面，而是直接进这个位置自己的真对话，
        // 因此这里只锁「有入口 + 能回主对话」这两件事本身。
        assertTrue("面板必须有查看入口", panel.contains("onOpenSeatChat"))
        assertTrue(
            "查看过程必须跳转到真正的聊天页",
            chatPage.contains("Screen.Chat(") && chatPage.contains("showBack = true"),
        )
        assertTrue("路由必须支持返回按钮参数", route.contains("val showBack: Boolean"))
        assertTrue(
            "聊天页必须能显示返回按钮，避免用户被困在过程对话里",
            chatPage.contains("showBack: Boolean") && chatPage.contains("navController.popBackStack()"),
        )
    }

    @Test
    fun `圆桌结束后仍要能查看与续跑`() {
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")
        val chatService = source("service/ChatService.kt")

        assertTrue(
            "面板不能在圆桌结束时无条件隐藏，否则截断的位置没法续跑",
            panel.contains("state.isFinished && state.seats.none { it.canContinue }"),
        )
        assertTrue(
            "结束后必须保留续跑上下文",
            chatService.contains("roundTableContinuationContexts"),
        )
        assertTrue(
            "结束后不得立刻丢弃 run，否则座位指令无处可投",
            !chatService.contains("roundTableRuns.remove(conversationId, finishedRun)"),
        )
    }

    @Test
    fun `圆桌新增文案必须中英文齐备`() {
        val en = resource("values/strings.xml")
        val zh = resource("values-zh/strings.xml")

        listOf(
            "round_table_panel_action_continue",
            "round_table_panel_action_view",
            "round_table_panel_state_succeeded_truncated",
            "round_table_panel_continuation",
            "round_table_notice_truncated",
            "round_table_seat_chat_title",
            "round_table_seat_chat_back",
            "round_table_seat_folder_name",
        ).forEach { key ->
            assertTrue("英文缺少文案 $key", en.contains("name=\"$key\""))
            assertTrue("中文缺少文案 $key", zh.contains("name=\"$key\""))
        }
    }

    @Test
    fun `圆桌源码不得引用子代理包`() {
        listOf(
            "service/RoundTableSeat.kt",
            "service/RoundTableRun.kt",
            "service/RoundTableCoordinator.kt",
            "ui/pages/chat/RoundTableSeatPanel.kt",
        ).forEach { relative ->
            val text = source(relative)
            val violations = text.lineSequence()
                .filter { line ->
                    val trimmed = line.trimStart()
                    !trimmed.startsWith("*") && !trimmed.startsWith("//")
                }
                .filter { it.contains("me.rerere.rikkahub.agent") }
                .toList()
            assertTrue("$relative 违规引用子代理包：$violations", violations.isEmpty())
        }
    }

    @Test
    fun `圆桌必须识别无声截断并在续跑时防复读(v272)`() {
        val coordinator = source("service/RoundTableCoordinator.kt")
        assertTrue(
            "调度器必须消费 truncatedWithoutSentinel（无声截断），否则半截产出被当成完整成功",
            coordinator.contains("truncatedWithoutSentinel"),
        )
        assertTrue(
            "无声截断必须双条件判定（无结束原因才算），否则 Gemini 正常收尾会被误伤",
            coordinator.contains("finishReason.isNullOrBlank()"),
        )
        val chatService = source("service/ChatService.kt")
        assertTrue(
            "圆桌续跑必须复用主对话的逐字去重保护（模型从头复述旧内容时剪掉重复段）",
            chatService.contains("stripResumeDuplication("),
        )
        assertTrue(
            "必须有圆桌续跑去重入口 stripRoundTableContinuationDuplication",
            chatService.contains("stripRoundTableContinuationDuplication"),
        )
    }
}
