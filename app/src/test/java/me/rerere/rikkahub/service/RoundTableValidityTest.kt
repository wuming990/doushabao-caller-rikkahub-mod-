package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.prompts.RoundTableRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v213：区分"有文字"和"有结论"。
 *
 * 这些用例直接取自 v212 真实圆桌里出现过的坏结果：
 * 反驳员只把角色说明复述一遍，另一个模型只说"我会先核查源码"就结束了，
 * 但 v212 都把它们当成了可用方案送去最终拍板。
 */
class RoundTableValidityTest {

    private fun longFiller(chars: Int): String = "这是一段正常的分析内容，包含具体判断和依据。".repeat(chars / 20 + 1)

    @Test
    fun `normal proposal is usable`() {
        val text = buildString {
            appendLine("目标理解：把圆桌改成每个位置独立可控。")
            appendLine("已确认事实：ChatService 使用 awaitAll 等待全部模型。")
            appendLine("方案步骤：1 拆出座位状态；2 单座位可停可换；3 缺席检查点。")
            appendLine("风险：并发写入冲突。验证：单元测试覆盖尝试编号。")
            appendLine("止损与回滚：保留 .before 备份，可整包回退。")
            append(longFiller(400))
        }
        val verdict = RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, text)
        assertEquals(RoundTableContentVerdict.VALID, verdict)
        assertTrue(verdict.isUsable)
    }

    @Test
    fun `role prompt echo is flagged`() {
        val text = """
            你现在是【针对性反驳员】。你不是执行者，也不是拍板者，不要重写一份泛泛的方案。
            本轮通用约束（对所有角色一律有效）：我可以自由使用只读能力。
            我明白我的职责是逐条挑出具体主张来检查。
        """.trimIndent()
        val verdict = RoundTableValidity.evaluate(RoundTableRole.REBUTTAL, text)
        assertEquals(RoundTableContentVerdict.ROLE_ECHO, verdict)
        assertFalse(verdict.isUsable)
    }

    @Test
    fun `intent without result is flagged`() {
        val text = buildString {
            append("我将先核查源码，确认圆桌部分的真实实现，然后再回答这个问题。")
            repeat(6) { append("我需要逐个文件读取，包括服务层与界面层，读完之后再整理成可核对的清单。") }
        }
        val verdict = RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, text)
        assertEquals(RoundTableContentVerdict.INTENT_ONLY, verdict)
    }

    @Test
    fun `long answer that starts with a plan is still usable`() {
        val text = buildString {
            append("我会先核查源码，再给结论。")
            appendLine("核查结果如下：")
            appendLine("已确认事实：并行阶段使用 awaitAll，一个模型不结束整场就会等下去。")
            appendLine("结论：必须让每个位置独立可停可换。")
            appendLine("验证：新增座位状态机测试，覆盖尝试编号与旧结果丢弃。")
            append(longFiller(1_500))
        }
        // 开头虽然是"我会先…"，但正文给了结论且篇幅足够，不能误判
        assertEquals(
            RoundTableContentVerdict.VALID,
            RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, text),
        )
    }

    @Test
    fun `too short is flagged`() {
        assertEquals(
            RoundTableContentVerdict.TOO_SHORT,
            RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, "我觉得这个方案可以，没什么问题。"),
        )
        assertEquals(
            RoundTableContentVerdict.TOO_SHORT,
            RoundTableValidity.evaluate(RoundTableRole.CONTRACT, ""),
        )
    }

    @Test
    fun `contract stage allows shorter output`() {
        // 固定长度 250：合同角色下限 200，出方案角色下限 300
        val text = "任务合同：目标、交付物、完成标准、缺失信息都写清了。阻断级别 EVIDENCE_GAP。"
            .padEnd(250, '。')
        assertEquals(250, text.length)
        assertEquals(
            RoundTableContentVerdict.VALID,
            RoundTableValidity.evaluate(RoundTableRole.CONTRACT, text),
        )
        assertEquals(
            RoundTableContentVerdict.TOO_SHORT,
            RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, text),
        )
    }

    @Test
    fun `english intent only is flagged`() {
        val text = buildString {
            append("Let me first read the source files before answering. ")
            repeat(4) { append("I will list the files and read them one by one. ") }
        }
        assertEquals(
            RoundTableContentVerdict.INTENT_ONLY,
            RoundTableValidity.evaluate(RoundTableRole.EXPLORATION, text),
        )
    }
}
