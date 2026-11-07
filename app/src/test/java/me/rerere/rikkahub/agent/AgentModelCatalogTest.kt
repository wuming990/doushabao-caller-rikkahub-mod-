package me.rerere.rikkahub.agent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.agent.model.buildAgentModelCatalog
import me.rerere.rikkahub.agent.model.resolveAgentModelRef
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v1029：主模型点名子代理模型时的「默认 + 备用 1~3」清单。
 *
 * 这里测纯函数，不依赖 Android。重点是：
 * - ref 顺序固定（default / fallback_1 / fallback_2 / fallback_3）；
 * - 缺少默认时回落到主对话模型；
 * - 被删除的备用模型跳过，但保留下标（fallback_2 还是 2，不会挤成 1）；
 * - ref 大小写不敏感，未知引用返回 null 让工具层明确报错。
 */
class AgentModelCatalogTest {

    private val provider = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "测试供应商",
        models = listOf(
            Model(id = Uuid.random(), modelId = "model-a", displayName = "模型A"),
            Model(id = Uuid.random(), modelId = "model-b", displayName = "模型B"),
            Model(id = Uuid.random(), modelId = "model-c", displayName = "模型C"),
        ),
    )

    private fun settings(
        agentModelId: Uuid? = provider.models[0].id,
        fallbacks: List<Uuid> = listOf(provider.models[1].id, provider.models[2].id),
    ) = Settings(
        providers = listOf(provider),
        chatModelId = provider.models[0].id,
        agentModelId = agentModelId,
        agentFallbackModelIds = fallbacks,
    )

    @Test
    fun `默认与备用按顺序进入清单`() {
        val catalog = buildAgentModelCatalog(settings())

        assertEquals(listOf("default", "fallback_1", "fallback_2"), catalog.map { it.ref })
        assertEquals("模型A · 测试供应商", catalog[0].label)
        assertEquals(provider.models[1].id.toString(), catalog[1].modelId)
        assertEquals("模型C · 测试供应商", catalog[2].label)
    }

    @Test
    fun `未配置默认时回落到主对话模型`() {
        val catalog = buildAgentModelCatalog(settings(agentModelId = null))

        assertEquals(
            provider.models[0].id.toString(),
            catalog.first { it.ref == "default" }.modelId,
        )
    }

    @Test
    fun `被删除的备用模型跳过但保留原序号`() {
        val catalog = buildAgentModelCatalog(
            settings(fallbacks = listOf(Uuid.random(), provider.models[2].id)),
        )

        assertEquals(listOf("default", "fallback_2"), catalog.map { it.ref })
        assertEquals(provider.models[2].id.toString(), catalog[1].modelId)
    }

    @Test
    fun `ref 大小写不敏感且未知引用返回 null`() {
        val catalog = buildAgentModelCatalog(settings())

        assertEquals("fallback_1", resolveAgentModelRef(catalog, " Fallback_1 ")?.ref)
        assertNull(resolveAgentModelRef(catalog, "fallback_9"))
        assertNull(resolveAgentModelRef(catalog, ""))
        assertNull(resolveAgentModelRef(catalog, null))
    }
}
