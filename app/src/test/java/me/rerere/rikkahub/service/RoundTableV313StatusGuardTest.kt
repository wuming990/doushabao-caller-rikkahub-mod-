package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * v313 门禁：圆桌「看起来卡在最后一步」的两处修复。
 *
 * 用户报告（原话）：
 * > 「圆桌模式现在最后一步（主模型总结）疑似总结并且输出完毕后系统还会卡在主模型总结中
 * >  这一步，需要我来手动截断，我无法确认是软件还是中转站的问题。」
 *
 * 查清后是**两个互相独立的缺陷**：
 *
 * A. **状态文字在撒谎**。旧实现只要「没有任何座位在输出」就一律显示
 *    「主模型正在综合各方案…」，于是任务合同 / 出方案 / 反驳三个阶段之间的空档、
 *    以及反驳与最终拍板的检查点，全都被说成「正在总结」；用户无法据此判断
 *    到底是软件在等、还是中转站没挂断连接。现在按真实阶段报，并且检查点判据
 *    从「phase == PROPOSAL_GAP」改成「有没有打开的检查点提示」。
 *
 * B. **检查点上的按钮点不动（这才是「像有堵墙」）**。「用现有材料继续」的 enabled
 *    读的是停下那一刻记下的 usableCount 死数字。用户把半截内容勾成「算进结论」之后，
 *    数字没变，按钮永远灰着 —— 只能重跑或整轮作废。现在界面按当前勾选状态实时重算。
 *
 * A 组是源码接线断言（这两处都是"改回旧写法就静默退化"的文案/判据，编译器抓不到）；
 * B 组是真的调用被测函数，把「有内容」与「被勾选」两件事分开算清楚。
 */
class RoundTableV313StatusGuardTest {

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

    private val chatServicePath =
        "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
    private val panelPath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/RoundTableSeatPanel.kt"
    private val coordinatorPath =
        "app/src/main/java/me/rerere/rikkahub/service/RoundTableCoordinator.kt"

    // ---- A 组：状态文字必须说真话 ----

    @Test
    fun `状态文字不再无条件宣称正在总结`() {
        val service = source(chatServicePath)
        // 旧写法：没有座位在跑就硬编码「正在综合各方案」。它必须已经不在源码里。
        assertTrue(
            "running 为空时必须按真实阶段报，不许再一律写成「正在总结」",
            service.contains("if (running.isEmpty()) return roundTablePhaseText(state.phase)"),
        )
        // 每个阶段都要有自己的文案，且必须覆盖枚举全部六个值（少一个编译器会报，
        // 但「把 SUMMARY 之外的都指回 summarizing」这种偷懒编译器不报，只能靠断言）。
        listOf(
            "RoundTableRunPhase.CONTRACT -> R.string.round_table_status_phase_contract",
            "RoundTableRunPhase.PROPOSALS -> R.string.round_table_status_phase_proposals",
            "RoundTableRunPhase.REBUTTAL -> R.string.round_table_status_phase_rebuttal",
            "RoundTableRunPhase.FINISHED -> R.string.round_table_status_phase_finished",
            "RoundTableRunPhase.SUMMARY -> R.string.round_table_status_summarizing",
        ).forEach {
            assertTrue("阶段文案映射缺少：$it", service.contains(it))
        }
    }

    @Test
    fun `检查点判据看的是有没有打开检查点，而不是只认方案阶段`() {
        val service = source(chatServicePath)
        // 旧写法只认 PROPOSAL_GAP：反驳 / 最终拍板的检查点会把 phase 设回原阶段名，
        // 于是明明在等用户点按钮，状态文字却说「正在总结」。
        assertTrue(
            "检查点判据必须是 gapPrompt 是否存在",
            service.contains("if (state.gapPrompt != null)"),
        )
    }

    @Test
    fun `新增的四个阶段文案中英都存在`() {
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        listOf(
            "round_table_status_phase_contract",
            "round_table_status_phase_proposals",
            "round_table_status_phase_rebuttal",
            "round_table_status_phase_finished",
        ).forEach { key ->
            assertTrue("英文主表缺 $key", en.contains("name=\"$key\""))
            assertTrue("中文表缺 $key", zh.contains("name=\"$key\""))
        }
    }

    // ---- B 组：检查点上的可用份数必须实时 ----
    // 注：seat(..) / message(..) 是**文件级**函数（见本文件末尾）。
    // 放在类里时嵌套的 ScriptedCallbacks 取不到外层成员，编译器报
    // 「Outer class cannot be used as receiver」—— 非 inner 类没有外层接收者。

    /**
     * 按座位 id 决定这次生成给什么：[outputs] 里的座位返回对应文本，其余抛错。
     * 走的是调度器的真实路径 —— 结果由 finishWithResult 落进它自己的 results。
     */
    private class ScriptedCallbacks(
        val outputs: Map<String, String>,
    ) : RoundTableSeatCallbacks {
        override suspend fun generate(
            seat: RoundTableSeat,
            attempt: Int,
            materials: List<me.rerere.rikkahub.data.ai.prompts.RoundTableMaterial>,
            continueFrom: String?,
            onActivity: (Int) -> Unit,
        ): UIMessage {
            val text = outputs[seat.seatId] ?: throw IllegalStateException("模拟失败：${seat.seatId}")
            return message(text)
        }

        override suspend fun writeResult(
            seat: RoundTableSeat,
            attempt: Int,
            message: UIMessage,
            verdict: RoundTableContentVerdict,
            truncated: Boolean,
        ) = Unit

        override suspend fun writeNotice(seat: RoundTableSeat, notice: RoundTableSeatNotice) = Unit

        override suspend fun writePartialResult(
            seat: RoundTableSeat,
            notice: RoundTableSeatNotice,
        ): UIMessage? = null

        override fun partialTextOf(seat: RoundTableSeat): String = ""

        override fun resolveModelName(seat: RoundTableSeat): String? = seat.modelName

        override fun onStateChanged() = Unit
    }

    @Test
    fun `有内容的座位与勾选状态分开计算`() = kotlinx.coroutines.runBlocking {
        val run = RoundTableRun(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            nodeId = Uuid.random(),
            autoSummarize = true,
            seats = listOf(seat("a"), seat("b"), seat("c")),
            nowMillis = { 0L },
        )
        val coordinator = RoundTableCoordinator(
            run = run,
            callbacks = ScriptedCallbacks(mapOf("a" to "方案 A", "b" to "   ")),
            nowMillis = { 0L },
            watchdogIntervalMillis = 20L,
            autoRetryDelayMillis = 1L,
        )
        // c 没给输出 → 生成抛错；a 有内容、b 只有空白
        kotlinx.coroutines.withTimeout(5_000L) {
            coordinator.runGroup(listOf("a", "b", "c")) { emptyList() }
        }

        // b 只有空白 = 没内容；c 报错 = 没结果。只有 a 真的留有内容。
        assertEquals(setOf("a"), coordinator.seatIdsWithResults(listOf("a", "b", "c")))

        // 「有内容」与「被勾选」是两件事：勾掉 a，前者不变、后者归零
        run.setIncludeInSummary("a", include = false)
        assertEquals(setOf("a"), coordinator.seatIdsWithResults(listOf("a", "b", "c")))
        assertEquals(0, coordinator.usableResults(listOf("a", "b", "c")).size)

        // 用户把 a 勾回结论 —— 旧版此刻按钮仍灰（死数字没变），新版界面会重算成 1
        run.setIncludeInSummary("a", include = true)
        assertEquals(1, coordinator.usableResults(listOf("a", "b", "c")).size)
    }

    @Test
    fun `检查点提示带上座位清单，界面才能实时重算`() {
        val service = source(chatServicePath)
        // 两处开检查点的地方（方案检查点 + 阶段审阅检查点）都必须带座位清单
        assertTrue(
            "审阅检查点必须带 seatIds",
            service.contains("seatIds = seatIds.toSet()"),
        )
        assertTrue(
            "审阅检查点必须带「哪些座位真有内容」",
            service.contains("seatsWithResults = coordinator.seatIdsWithResults(seatIds)"),
        )
        assertTrue(
            "方案检查点同样要带",
            service.contains("seatsWithResults = coordinator.seatIdsWithResults(proposalSeatIds)"),
        )
    }

    @Test
    fun `面板用实时份数决定按钮能不能点`() {
        val panel = source(panelPath)
        // 旧写法 enabled = gap.usableCount > 0 读的是死数字，勾回结论也点不动。
        assertTrue(
            "按钮启用条件必须用实时值",
            panel.contains("enabled = gapUsableCount > 0"),
        )
        assertTrue(
            "实时值必须是「有内容 ∩ 已勾选」",
            panel.contains("it.seatId in g.seatsWithResults && it.includeInSummary"),
        )
        // 没带清单时退回快照数字，行为不变（向后兼容，不许直接崩或显示 0）
        assertTrue(
            "缺清单时必须退回快照数字",
            panel.contains("if (g.seatIds.isEmpty()) g.usableCount"),
        )
        assertTrue(
            "面板不许再用死数字当启用条件",
            !panel.contains("enabled = gap.usableCount > 0"),
        )
    }

    @Test
    fun `调度器新增的是查询函数，没有改动任何既有判定`() {
        val coordinator = source(coordinatorPath)
        assertTrue(
            "seatIdsWithResults 必须是纯查询（只读 results，不写状态）",
            coordinator.contains("fun seatIdsWithResults(seatIds: List<String>): Set<String>"),
        )
        // 空白内容不算有内容 —— 否则「勾回一个空页」会让按钮假亮
        assertTrue(
            "必须按 toText().isNotBlank() 过滤",
            coordinator.contains("results[it.seatId]?.toText()?.isNotBlank() == true"),
        )
    }
}

/** 文件级：类内的嵌套假实现也要用，放类里就没有外层接收者了 */
private fun seat(id: String) = RoundTableSeat(
    seatId = id,
    role = RoundTableRole.EXPLORATION,
    slot = 1,
    modelId = Uuid.random(),
    modelName = "模型 $id",
)

private fun message(text: String) = UIMessage(
    role = MessageRole.ASSISTANT,
    parts = listOf(UIMessagePart.Text(text)),
)
