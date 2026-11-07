package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.runtime.agentIdleTimeoutMillis
import me.rerere.rikkahub.agent.runtime.agentToolWaitCeilingMillis
import me.rerere.rikkahub.agent.runtime.effectiveResumeTotalMax
import me.rerere.rikkahub.agent.tools.agentReadBudgetChars
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

/** v1024：真实数值行为；先在v1023上运行，缺失新接口也必须报明确断言而非编译错误。 */
class V1024AgentNumericLimitsTest {
    private fun call(name: String, vararg args: Any): Any? {
        val type = try {
            Class.forName("me.rerere.rikkahub.data.datastore.AgentLimitValuesKt")
        } catch (_: ClassNotFoundException) {
            throw AssertionError("缺少手填数字/不限的统一解释，不能仅修改界面")
        }
        val types = args.map {
            when (it) {
                is Int -> Int::class.javaPrimitiveType!!
                is Boolean -> Boolean::class.javaPrimitiveType!!
                else -> it.javaClass
            }
        }.toTypedArray()
        val method = try { type.getDeclaredMethod(name, *types) } catch (_: NoSuchMethodException) {
            throw AssertionError("缺少实际数值策略：$name")
        }
        return method.invoke(null, *args)
    }

    @Test fun `手填正整数不再被8个96步256KB这些档位限制`() {
        assertEquals(999, call("parseAgentInteger", "999", true))
        assertEquals(1, call("parseAgentInteger", "1", true))
        assertEquals(Int.MAX_VALUE, call("parseAgentInteger", "2147483647", true))
    }
    @Test fun `无效或空输入不保存且不溢出变负数`() {
        listOf("", " ", "-1", "1.5", "abc", "2147483648").forEach {
            assertNull(it, call("parseAgentInteger", it, true))
        }
    }
    @Test fun `零只有允许时才可保存且正常去掉前后空白`() {
        assertEquals(0, call("parseAgentInteger", "0", true))
        assertNull(call("parseAgentInteger", "0", false))
        assertEquals(300, call("parseAgentInteger", " 300 ", false))
    }
    @Test fun `并发填零不拒绝已有很多活动任务`() {
        assertEquals(true, call("agentHasCapacity", 10000, 0))
        assertEquals(true, call("agentHasCapacity", 9, 20))
    }
    @Test fun `有限并发仍严格按用户填写数量生效`() {
        assertEquals(false, call("agentHasCapacity", 20, 20))
        assertEquals(false, call("agentHasCapacity", 21, 20))
        assertEquals(true, call("agentHasCapacity", 19, 20))
    }
    @Test fun `只读代理不再夹到96步`() {
        assertEquals(500, call("agentStepBudget", 500, false))
        assertEquals(1, call("agentStepBudget", 1, false))
    }
    @Test fun `编程位仍按一点五倍但计算不能先溢出`() {
        assertEquals(750, call("agentStepBudget", 500, true))
        assertEquals(1500000000, call("agentStepBudget", 1000000000, true))
        assertEquals(Int.MAX_VALUE, call("agentStepBudget", Int.MAX_VALUE, true))
    }
    @Test fun `零步数向现有生成接口表达不限而不是回退32步`() {
        assertEquals(Int.MAX_VALUE, call("agentStepBudget", 0, false))
        assertEquals(Int.MAX_VALUE, call("agentStepBudget", 0, true))
    }
    @Test fun `工具返回允许超过256KB且零不裁掉全部内容`() {
        assertEquals(1048576, Settings(toolOutputLimitKb = 1024).toolOutputCharLimit)
        assertEquals(Int.MAX_VALUE, Settings(toolOutputLimitKb = 0).toolOutputCharLimit)
    }
    @Test fun `工具大小换算超出单个字符串表示能力也不能溢出成负数`() {
        assertEquals(Int.MAX_VALUE, call("agentOutputCharLimit", 2097152))
        assertEquals(Int.MAX_VALUE, call("agentOutputCharLimit", Int.MAX_VALUE))
        assertEquals(1024, call("agentOutputCharLimit", 1))
    }
    @Test fun `读取预算对不限和大返回值不因乘三溢出变成一小段`() {
        assertEquals(1610612735, agentReadBudgetChars(Int.MAX_VALUE))
        assertEquals(786432, agentReadBudgetChars(1048576))
    }
    @Test fun `续跑设500时总预算不再被10的夹值压回20`() {
        assertEquals(1000, effectiveResumeTotalMax(500))
        assertEquals(Int.MAX_VALUE, effectiveResumeTotalMax(Int.MAX_VALUE))
    }
    @Test fun `续跑0仍关闭自动但保留原手动续跑余量`() {
        assertEquals(8, effectiveResumeTotalMax(0))
    }
    @Test fun `无产出判定不再把300分钟压成120分钟`() {
        assertEquals(18000000L, agentIdleTimeoutMillis(300))
        assertEquals(128849018820000L, agentIdleTimeoutMillis(Int.MAX_VALUE))
    }
    @Test fun `工具执行时间不再把300分钟压成60分钟`() {
        assertEquals(18000000L, agentToolWaitCeilingMillis(300))
        assertEquals(128849018820000L, agentToolWaitCeilingMillis(Int.MAX_VALUE))
    }
    @Test fun `时间参数零仍沿用旧默认10分钟不擅自解释为不限`() {
        assertEquals(600000L, agentIdleTimeoutMillis(0))
        assertEquals(600000L, agentToolWaitCeilingMillis(0))
    }
    @Test fun `并发不限时给模型的说明不能写成最多零个`() {
        assertEquals("unlimited", call("agentConcurrencyLabel", 0))
        assertEquals("20", call("agentConcurrencyLabel", 20))
    }
    @Test fun `手填1KB时序列化安全线必须留在用户值以内`() {
        assertTrue(me.rerere.rikkahub.agent.tools.agentReadSafeChars(1024) < 1024)
        assertEquals(31 * 1024, me.rerere.rikkahub.agent.tools.agentReadSafeChars(32 * 1024))
    }

    @Test fun `损坏存档负数必须回默认不能自动打开不限`() {
        for (defaultValue in listOf(4, 32, 64, 2, 10)) {
            assertEquals(defaultValue, call("normalizeAgentLimit", -9, defaultValue, true))
        }
        assertEquals(10, call("normalizeAgentLimit", -1, 10, false))
        assertEquals(10, call("normalizeAgentLimit", 0, 10, false))
        assertEquals(0, call("normalizeAgentLimit", 0, 4, true))
        assertEquals(999, call("normalizeAgentLimit", 999, 4, true))
    }
    @Test fun `无效负并发本身也不能被解释成不限`() {
        assertEquals(false, call("agentHasCapacity", 1000, -1))
        assertEquals(true, call("agentHasCapacity", 0, -1))
    }
    @Test fun `流水线等待预算在最大续跑值下不能溢出变成短等待`() {
        assertEquals(Long.MAX_VALUE, me.rerere.rikkahub.agent.runtime.pipelineStageWaitMillis(
            requestedMillis = 900000L, idleTimeoutMillis = 600000L, autoResumeMax = Int.MAX_VALUE,
        ))
        assertTrue(me.rerere.rikkahub.agent.runtime.pipelineStageWaitMillis(
            requestedMillis = 900000L, idleTimeoutMillis = Long.MAX_VALUE, autoResumeMax = Int.MAX_VALUE,
        ) > 0)
    }
    @Test fun `非流式关闭模型静默判断也不能关闭工具自己的等待设置`() {
        assertTrue(me.rerere.rikkahub.agent.runtime.shouldJudgeStuck(
            idleForMillis = 0, idleTimeoutMillis = 0, toolInFlight = true,
            toolRunningForMillis = 600001, toolWaitCeilingMillis = 600000,
        ))
    }

}
