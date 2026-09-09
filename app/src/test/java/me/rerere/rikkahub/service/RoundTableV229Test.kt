package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.prompts.RoundTableMaterial
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import me.rerere.rikkahub.data.ai.prompts.buildRoundTableStageMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * v229 圆桌三件事的回归测试：
 *
 * 1. **自动断点续跑**：截断 / 断线 / 卡死自动接着写，最多两次；
 *    「疑似无效（太短）」绝不自动续跑，整场停下等用户决策（用户明确要求，防止硬编噪音）；
 * 2. **复活后无人调度的死锁**：超时失败后换模型 / 重试，座位被复活成「排队中」，
 *    但那一组的调度器已经退出，于是永远没人跑它，界面上救援按钮还全都消失
 *    （真机现场：最终综合 · 排队中 · 已重跑 1 次，点什么都没反应）；
 * 3. **圆桌吞掉用户要求**：助手配了预设内容时，空任务也能开跑，
 *    结果每个模型都没拿到用户的要求，只能凭空编，用户输入框里的字还被清空了。
 */
class RoundTableV229Test {

    private var now = 1_000L

    private fun seat(
        seatId: String = RoundTableSeatIds.SUMMARY,
        status: RoundTableSeatStatus = RoundTableSeatStatus.FAILED,
        autoContinuations: Int = 0,
        suspectReason: RoundTableContentVerdict? = null,
        scheduled: Boolean = false,
    ) = RoundTableSeat(
        seatId = seatId,
        role = RoundTableRole.FINAL,
        slot = 700,
        modelId = Uuid.random(),
        modelName = "拍板模型",
        status = status,
        autoContinuations = autoContinuations,
        suspectReason = suspectReason,
        scheduled = scheduled,
    )

    private fun newRun(): RoundTableRun = RoundTableRun(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        nodeId = Uuid.random(),
        autoSummarize = true,
        seats = listOf(
            seat(seatId = RoundTableSeatIds.SUMMARY, status = RoundTableSeatStatus.PENDING),
            RoundTableSeat(
                seatId = RoundTableSeatIds.explorer(0),
                role = RoundTableRole.EXPLORATION,
                slot = 100,
                modelId = Uuid.random(),
                modelName = "探索模型1",
            ),
        ),
        nowMillis = { now },
    )

    // ---- 1. 自动断点续跑策略 ----

    @Test
    fun `断线且留下半截内容时自动接着写`() {
        val verdict = RoundTableAutoContinuePolicy.evaluate(
            seat = seat(),
            partialText = "已经写了一大段方案……",
            error = java.io.IOException("stream closed"),
            timedOut = false,
        )
        assertEquals(RoundTableAutoContinueVerdict.AUTO_CONTINUE, verdict)
        assertTrue(verdict.shouldContinue)
    }

    @Test
    fun `卡死即使一个字都没吐出来也自动救一次`() {
        // 用户明确要求：卡死（上游不稳定、网络中断）也续两次，两次不行才暂停
        val verdict = RoundTableAutoContinuePolicy.evaluate(
            seat = seat(status = RoundTableSeatStatus.RUNNING),
            partialText = "",
            error = null,
            timedOut = true,
        )
        assertEquals(RoundTableAutoContinueVerdict.AUTO_CONTINUE, verdict)
    }

    @Test
    fun `普通失败但没有半截内容时不算续跑`() {
        val verdict = RoundTableAutoContinuePolicy.evaluate(
            seat = seat(),
            partialText = "   ",
            error = java.io.IOException("boom"),
            timedOut = false,
        )
        assertEquals(RoundTableAutoContinueVerdict.NO_PARTIAL, verdict)
        assertFalse(verdict.shouldContinue)
    }

    @Test
    fun `疑似无效绝不自动续跑而是交给用户审阅`() {
        // 用户原话：太短不自动续，直接暂停等我看过之后决策是否算入方案，
        // 防止 AI 提出的方案过少被强制续跑，导致输出噪音。
        listOf(
            seat(status = RoundTableSeatStatus.SUSPECT),
            seat(suspectReason = RoundTableContentVerdict.TOO_SHORT),
            seat(suspectReason = RoundTableContentVerdict.ROLE_ECHO),
            seat(suspectReason = RoundTableContentVerdict.INTENT_ONLY),
        ).forEach { s ->
            assertEquals(
                "疑似无效必须停下等人",
                RoundTableAutoContinueVerdict.NEEDS_REVIEW,
                RoundTableAutoContinuePolicy.evaluate(
                    seat = s,
                    partialText = "只写了一点点",
                    error = null,
                    timedOut = false,
                ),
            )
        }
    }

    @Test
    fun `用户主动停止的位置不自动接着写`() {
        listOf(RoundTableSeatStatus.STOPPED, RoundTableSeatStatus.SKIPPED).forEach { status ->
            assertEquals(
                RoundTableAutoContinueVerdict.USER_STOPPED,
                RoundTableAutoContinuePolicy.evaluate(
                    seat = seat(status = status),
                    partialText = "半截内容",
                    error = null,
                    timedOut = false,
                ),
            )
        }
    }

    @Test
    fun `自动续跑预算是两次用完就停下等人`() {
        assertEquals(2, RoundTableAutoContinuePolicy.MAX_AUTO_CONTINUATIONS)
        assertEquals(
            RoundTableAutoContinueVerdict.BUDGET_USED,
            RoundTableAutoContinuePolicy.evaluate(
                seat = seat(autoContinuations = 2),
                partialText = "半截内容",
                error = null,
                timedOut = true,
            ),
        )
    }

    @Test
    fun `鉴权余额类错误不自动续跑`() {
        assertEquals(
            RoundTableAutoContinueVerdict.NOT_RETRYABLE,
            RoundTableAutoContinuePolicy.evaluate(
                seat = seat(),
                partialText = "半截内容",
                error = IllegalStateException("insufficient balance 余额不足"),
                timedOut = false,
            ),
        )
    }

    @Test
    fun `自动续跑只计续跑次数不算重跑`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.SUMMARY
        assertTrue(run.prepareAutoContinue(seatId))
        val s = run.seat(seatId)!!
        assertEquals(1, s.autoContinuations)
        assertEquals(1, s.continuations)
        assertEquals(0, s.reruns) // 续跑不是重跑
        assertEquals(RoundTableSeatStatus.PENDING, s.status)
        assertFalse(s.truncated)

        assertTrue(run.prepareAutoContinue(seatId))
        assertEquals(2, run.seat(seatId)!!.autoContinuations)
        // 预算用完
        assertFalse("第三次必须被预算挡住", run.prepareAutoContinue(seatId))
    }

    // ---- 2. 复活后无人调度的死锁 ----

    @Test
    fun `座位被调度器接管与释放时快照要同步`() {
        val run = newRun()
        val seatId = RoundTableSeatIds.SUMMARY
        assertFalse(run.isSeatClaimed(seatId))
        assertFalse(run.seat(seatId)!!.scheduled)

        assertTrue(run.tryClaimSeat(seatId))
        assertTrue(run.isSeatClaimed(seatId))
        assertTrue("快照里必须能看出有人在跑它", run.seat(seatId)!!.scheduled)
        assertFalse("同一个座位不能被抢两次", run.tryClaimSeat(seatId))

        run.releaseSeat(seatId)
        assertFalse(run.isSeatClaimed(seatId))
        assertFalse(run.seat(seatId)!!.scheduled)
    }

    @Test
    fun `排队中但没人负责就是卡住必须能被识别出来`() {
        // 真机死锁现场：最终综合超时失败 → 用户点换模型 → 座位复活成「排队中」→
        // 那一组的调度器已经退出，没人跑它，也没人消费指令。
        val stalled = seat(status = RoundTableSeatStatus.PENDING, scheduled = false)
        assertTrue("排队中且无人负责 = 卡住", stalled.stalledUnscheduled)

        val running = seat(status = RoundTableSeatStatus.PENDING, scheduled = true)
        assertFalse("有人负责就不算卡住", running.stalledUnscheduled)

        val done = seat(status = RoundTableSeatStatus.SUCCEEDED, scheduled = false)
        assertFalse("已经有结论的位置不算卡住", done.stalledUnscheduled)
    }

    // ---- 3. 圆桌吞掉用户要求 ----

    @Test
    fun `预设内容不算用户说过的话`() {
        val preset = listOf("你是我的执行型 AI 协作伙伴。……")
        assertFalse(
            "只有预设内容时必须判定为『用户还没提要求』",
            RoundTableTaskGuard.hasRealUserTask(userTexts = preset, presetTexts = preset),
        )
        assertTrue(
            "用户真的说过话就要认",
            RoundTableTaskGuard.hasRealUserTask(
                userTexts = preset + "帮我做一个黑洞模拟器",
                presetTexts = preset,
            ),
        )
    }

    @Test
    fun `空任务加上只有预设内容时绝不允许开跑`() {
        val preset = listOf("预设指令")
        assertFalse(
            "这正是真机事故的入口：空任务被放行，圆桌整轮没人知道用户要什么",
            RoundTableTaskGuard.canStart(
                taskText = "   ",
                hasAttachment = false,
                userTexts = preset,
                presetTexts = preset,
            ),
        )
    }

    @Test
    fun `写了任务或带了附件或真的聊过都可以开跑`() {
        val preset = listOf("预设指令")
        assertTrue(
            RoundTableTaskGuard.canStart("做个网页", false, preset, preset),
        )
        assertTrue(
            "只丢附件也算给了材料",
            RoundTableTaskGuard.canStart("", true, preset, preset),
        )
        assertTrue(
            RoundTableTaskGuard.canStart("", false, preset + "我要改这个 bug", preset),
        )
    }

    // ---- 4. 源码级门禁：锁死这几条行为不被后续改动悄悄破坏 ----

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")

    private fun source(relative: String): String {
        val file = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        ).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    @Test
    fun `门禁：调度器必须在失败 超时 截断三处都尝试自动续跑`() {
        val coordinator = source("service/RoundTableCoordinator.kt")
        assertTrue(
            "必须有统一的自动续跑入口",
            coordinator.contains("private suspend fun tryAutoContinue("),
        )
        assertTrue(
            "断线失败时要先尝试续跑",
            coordinator.contains("if (tryAutoContinue(seatId, error = error, timedOut = false))"),
        )
        assertTrue(
            "卡死超时也要先尝试续跑",
            coordinator.contains("if (tryAutoContinue(seatId, error = null, timedOut = true))"),
        )
        assertTrue(
            "被截断的产出要自动接着写",
            coordinator.contains("if (truncated && tryAutoContinue(seatId, error = null, timedOut = false))"),
        )
        assertTrue(
            "续跑必须把半截内容记成前缀",
            coordinator.contains("continuePrefixes[seatId] = partial"),
        )
    }

    @Test
    fun `门禁：复活类指令在没人负责时必须自己补跑`() {
        val chatService = source("service/ChatService.kt")
        assertTrue(
            "必须能查询座位有没有人负责",
            chatService.contains("val scheduled = run.isSeatClaimed(seatId)"),
        )
        assertTrue(
            "重试 / 换模型 / 继续输出都算复活类指令",
            chatService.contains("val revives = command is RoundTableSeatCommand.Retry"),
        )
        assertTrue(
            "没人负责时必须补一个执行协程，而不是把座位丢在排队中",
            chatService.contains("val rescueContext = if (revives && !scheduled)"),
        )
        assertTrue(
            "没人负责、或上一条指令没人接住时，停止 / 跳过必须就地落终态",
            chatService.contains("if ((!scheduled || commandStuck) && !revives)"),
        )
        assertTrue(
            "v239：必须能识别「上一条指令还挂着没人接」这种卡死形态",
            chatService.contains("val commandStuck = run.peekCommand(seatId) != null"),
        )
    }

    @Test
    fun `门禁：最后一步没成功不许直接收工`() {
        val chatService = source("service/ChatService.kt")
        assertTrue(
            "必须有整场暂停的审阅检查点",
            chatService.contains("private suspend fun awaitRoundTableReview("),
        )
        assertTrue(
            "最终综合阶段必须走审阅检查点",
            chatService.contains("stage = RoundTableGapStage.SUMMARY"),
        )
        assertTrue(
            "疑似无效的阶段也要停下等人",
            chatService.contains("stage = RoundTableGapStage.REVIEW"),
        )
    }

    @Test
    fun `门禁：用户任务必须原子写入 回读校验 并固定成材料`() {
        val chatService = source("service/ChatService.kt")
        assertTrue(
            "必须用原子读改写保存用户任务",
            chatService.contains("private suspend fun mutateAndSaveConversation("),
        )
        assertTrue(
            "保存用户任务必须走原子路径",
            chatService.contains("mutateAndSaveConversation(conversationId) { current ->"),
        )
        assertTrue(
            "写完必须回读校验",
            chatService.contains("runRoundTable: task message missing after save, repairing once"),
        )
        assertTrue(
            "补不上必须报错停下而不是静默跑完",
            chatService.contains("R.string.round_table_error_task_lost"),
        )
        assertTrue(
            "任务原文必须固定成一份材料",
            chatService.contains("R.string.round_table_material_task"),
        )
        assertTrue(
            "预设内容不算用户说过的话",
            chatService.contains("RoundTableTaskGuard.hasRealUserTask(existingUserTexts, presetTexts)"),
        )
    }

    @Test
    fun `任务合同必须收到本轮任务原文而不是空材料`() {
        val task = "查一下圆桌任务合同为什么看不到用户原话"
        val stage = buildRoundTableStageMessage(
            role = RoundTableRole.CONTRACT,
            materials = listOf(
                RoundTableMaterial(
                    label = "本轮任务（用户原话）",
                    text = task,
                )
            ),
        )
        assertTrue("合同阶段提示词必须包含材料标签", stage.contains("【本轮任务（用户原话）】"))
        assertTrue("合同阶段提示词必须包含用户原文", stage.contains(task))
        assertFalse("有任务材料时不得再显示没有额外材料", stage.contains("本阶段没有额外材料"))

        val chatService = source("service/ChatService.kt")
        assertTrue(
            "合同座位必须接收共享材料",
            chatService.contains(
                "coordinator.runGroup(listOf(RoundTableSeatIds.CONTRACT)) { sharedMaterials.toList() }"
            ),
        )
        assertFalse(
            "合同座位不得再被传入空材料",
            chatService.contains(
                "coordinator.runGroup(listOf(RoundTableSeatIds.CONTRACT)) { emptyList() }"
            ),
        )
    }

    @Test
    fun `任务材料必须在合同阶段之前已经固定`() {
        val chatService = source("service/ChatService.kt")
        val materialsStart = chatService.indexOf("val sharedMaterials = mutableListOf<RoundTableMaterial>()")
        val taskMaterial = chatService.indexOf("R.string.round_table_material_task")
        val contractRun = chatService.indexOf(
            "coordinator.runGroup(listOf(RoundTableSeatIds.CONTRACT))"
        )
        assertTrue("共享材料必须先于合同阶段建立", materialsStart >= 0 && materialsStart < contractRun)
        assertTrue("任务材料必须先于合同阶段建立", taskMaterial >= 0 && taskMaterial < contractRun)
    }

    @Test
    fun `门禁：初始化不得用空对话覆盖已有消息`() {
        val chatService = source("service/ChatService.kt")
        assertTrue(
            "内存里已经有消息就必须跳过初始化覆盖",
            chatService.contains("initializeConversation: memory already has messages, skip for"),
        )
    }

    @Test
    fun `门禁：界面必须给卡住的座位救援入口与反馈`() {
        val panel = source("ui/pages/chat/RoundTableSeatPanel.kt")
        assertTrue(
            "有待处理指令时任何状态都要给反馈",
            panel.contains("R.string.round_table_panel_state_command_pending"),
        )
        assertTrue(
            "卡住状态要明确告诉用户怎么救",
            panel.contains("R.string.round_table_panel_state_stalled"),
        )
        assertTrue(
            "卡住时必须显示重试入口",
            panel.contains("if (seat.status.isMissingResult || seat.stalledUnscheduled)"),
        )
        assertTrue(
            "卡住时也要能继续输出",
            panel.contains("if (seat.canContinue || seat.stalledUnscheduled)"),
        )
    }

    @Test
    fun `门禁：确认框不得因为预设内容就放行空任务`() {
        val page = source("ui/pages/chat/ChatPage.kt")
        assertTrue(
            "开始按钮必须走统一守卫",
            page.contains("RoundTableTaskGuard.canStart("),
        )
        assertFalse(
            "不得再用「有历史就放行」的旧写法",
            page.contains("val canStart = taskText.isNotBlank() || hasHistory"),
        )
        assertTrue(
            "只有真的带上了任务才允许清空输入框",
            page.contains("if (parts.isNotEmpty()) {"),
        )
    }
}
