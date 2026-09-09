package me.rerere.rikkahub.agent

import me.rerere.rikkahub.data.datastore.decodeAgentRoleModelOverrides
import me.rerere.rikkahub.data.datastore.encodeAgentRoleModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v220 门禁：子代理模型设置的持久化必须稳定。
 *
 * 用户实际反馈的 bug：「明明设置过了子代理模型，但有时候又会自动删除、恢复默认」。
 * 已核实的两个真实原因：
 * 1) v219 的角色级模型表编码后含 null 值，读回时用 Map<String, String> 反序列化会整表抛异常，
 *    被 runCatching 吞掉后**所有角色模型一起丢失**；
 * 2) enableAgentTools / agentModelId 在 v219 完全没有写入 DataStore（只存在于内存），
 *    DataStore 下一次发射就会用默认值覆盖 —— 该项由 Settings 字段与读写键覆盖测试守护。
 *
 * 本测试锁死第 1 类原因：编解码往返不丢数据、单条坏数据不连带清空其它角色。
 */
class AgentSettingsPersistenceTest {

    private val explorerModel = Uuid.random()
    private val reviewerModel = Uuid.random()

    @Test
    fun `角色模型编解码往返不丢数据`() {
        val original = mapOf<String, Uuid?>(
            "explorer" to explorerModel,
            "reviewer" to reviewerModel,
        )

        val restored = decodeAgentRoleModelOverrides(encodeAgentRoleModelOverrides(original))

        assertEquals(original, restored)
    }

    @Test
    fun `null 值角色被写成跟随默认而不是让整表报废`() {
        val original = mapOf(
            "default" to null,
            "explorer" to explorerModel,
        )

        val encoded = encodeAgentRoleModelOverrides(original)
        val restored = decodeAgentRoleModelOverrides(encoded)

        // null 等价于「跟随默认模型」，不写入；但 explorer 必须保住
        assertEquals(1, restored.size)
        assertEquals(explorerModel, restored["explorer"])
        assertNull(restored["default"])
    }

    @Test
    fun `v219 遗留的含 null 值 JSON 仍能读出可用项`() {
        // v219 实际写出的格式：{"default":null,"explorer":"<uuid>"}
        val legacy = """{"default":null,"explorer":"$explorerModel"}"""

        val restored = decodeAgentRoleModelOverrides(legacy)

        assertEquals(explorerModel, restored["explorer"])
        assertEquals(1, restored.size)
    }

    @Test
    fun `单条坏 UUID 不会连带清空其它角色`() {
        val broken = """{"explorer":"not-a-uuid","reviewer":"$reviewerModel"}"""

        val restored = decodeAgentRoleModelOverrides(broken)

        assertEquals(reviewerModel, restored["reviewer"])
        assertNull(restored["explorer"])
    }

    @Test
    fun `整段 JSON 损坏时返回空表而不是抛异常`() {
        assertTrue(decodeAgentRoleModelOverrides("{not json").isEmpty())
        assertTrue(decodeAgentRoleModelOverrides("").isEmpty())
        assertTrue(decodeAgentRoleModelOverrides(null).isEmpty())
    }

    @Test
    fun `角色名大小写与空格被归一化到 AgentRole 约定的小写形式`() {
        val restored = decodeAgentRoleModelOverrides(
            encodeAgentRoleModelOverrides(mapOf(" Explorer " to explorerModel))
        )

        assertEquals(explorerModel, restored["explorer"])
    }
}
