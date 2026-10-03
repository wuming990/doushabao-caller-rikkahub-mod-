package me.rerere.rikkahub.agent

import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.agent.runtime.ResumeModelCandidate
import me.rerere.rikkahub.data.datastore.dedupeModelIds
import me.rerere.rikkahub.data.datastore.displayLabel
import me.rerere.rikkahub.data.datastore.modelLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v311 门禁：同名模型分属不同供应商时，必须能被区分、被选中、被排进候选链。
 *
 * 用户真机反馈原话：
 * > 「子代理同名时，即使我切换了其他同名子代理（不同上游供应商）还是会用旧的子代理
 * > 继续；而且我子代理是 a（供应商1）替补是 a b c（供应商2），会直接跳过替补
 * > a（供应商2）」
 *
 * 两条断言方向：
 * 1. **展示层**（可证的直接病因）：只印模型名时，换人前后一个字都不差，用户无从判断
 *    —— 所以带供应商标识的展示名必须两家不同。
 * 2. **编号层**（防御性加固）：若设置里真的出现两个相同的 Model.id，读设置时必须把
 *    后面的重发成不同编号，且重发是确定性的（同一份脏数据每次算出同一批新号）。
 */
class ModelSameNameResolutionTest {

    // ------------------------------------------------------------ 脚手架

    private fun model(name: String, id: Uuid = Uuid.random()): Model =
        Model(id = id, modelId = name, displayName = name, type = ModelType.CHAT)

    private fun provider(name: String, models: List<Model>, id: Uuid = Uuid.random()) =
        ProviderSetting.OpenAI(id = id, name = name, models = models)

    // ============================================================ 展示层

    @Test
    fun `同名不同供应商的模型，去重后展示名必须能各自指到自家`() {
        // 端到端：编号撞车 -> dedupe 重发第二条 -> 两条各自能被解析回自己的供应商
        // -> 展示名带供应商标识，用户看得出「换成了另一家的同名模型」。
        val sharedId = Uuid.random()
        val deduped = dedupeModelIds(
            listOf(
                provider("供应商1", listOf(model("glm-4.6", sharedId)), Uuid.random()),
                provider("供应商2", listOf(model("glm-4.6", sharedId)), Uuid.random()),
            )
        )
        assertEquals(1, deduped.reissued)
        val providers = deduped.providers
        val first = providers[0].models.single()
        val second = providers[1].models.single()

        val label1 = first.displayLabel(providers)
        val label2 = second.displayLabel(providers)

        assertNotEquals("两家同名模型的展示名必须能区分开", label1, label2)
        assertEquals("glm-4.6 · 供应商1", label1)
        assertEquals("glm-4.6 · 供应商2", label2)
    }

    @Test
    fun `供应商名缺失时不显示空尾巴`() {
        assertEquals("glm-4.6", modelLabel("glm-4.6", null))
        assertEquals("glm-4.6", modelLabel("glm-4.6", ""))
        assertEquals("glm-4.6", modelLabel("glm-4.6", "   "))
    }

    @Test
    fun `候选链快照的 label 走同一口径`() {
        val withProvider = ResumeModelCandidate(id = "x", displayName = "glm-4.6", providerName = "供应商2")
        val without = ResumeModelCandidate(id = "x", displayName = "glm-4.6")

        assertEquals("glm-4.6 · 供应商2", withProvider.label)
        assertEquals("glm-4.6", without.label)
        assertEquals(
            "候选 label 必须与 modelLabel 同一份实现，否则两个界面会漂成两种说法",
            modelLabel("glm-4.6", "供应商2"),
            withProvider.label,
        )
    }

    // ============================================================ 编号层

    @Test
    fun `重复编号只重发后面那条，第一个持有者的编号保持不动`() {
        val sharedId = Uuid.random()
        val first = model("glm-4.6", sharedId)
        val second = model("glm-4.6", sharedId)
        val result = dedupeModelIds(
            listOf(provider("供应商1", listOf(first)), provider("供应商2", listOf(second)))
        )

        assertEquals("应重发 1 条", 1, result.reissued)
        val fixedFirst = result.providers[0].models.single()
        val fixedSecond = result.providers[1].models.single()

        assertEquals("第一个持有者必须保留原编号（所有存量引用的实际指向不变）", sharedId, fixedFirst.id)
        assertNotEquals("第二条必须换成新编号", fixedFirst.id, fixedSecond.id)
        assertEquals("除编号外其余字段不得被改动", "glm-4.6", fixedSecond.modelId)
    }

    @Test
    fun `重发是确定性的，同一份脏数据每次算出同一批新号`() {
        // 供应商 id 也必须固定：新编号由「供应商 id + 原编号 + 模型名」算出，
        // 两次构造若用了不同的供应商 id，比的就不是同一份数据了。
        val sid = Uuid.random()
        val pid1 = Uuid.random()
        val pid2 = Uuid.random()
        fun dirty() = listOf(
            provider("供应商1", listOf(model("a", sid)), pid1),
            provider("供应商2", listOf(model("a", sid)), pid2),
        )

        val r1 = dedupeModelIds(dirty())
        val r2 = dedupeModelIds(dirty())

        assertEquals("第二次加载必须算出与第一次完全相同的新编号", r1.providers[1].models[0].id, r2.providers[1].models[0].id)
        assertEquals(1, r1.reissued)
        // 新号不得与任何已存在编号相同
        val all = r1.providers.flatMap { it.models }.map { it.id }
        assertEquals("去重后全树编号必须唯一", all.size, all.distinct().size)
    }

    @Test
    fun `本来没有重复时原样返回，不产生任何改动`() {
        val providers = listOf(
            provider("供应商1", listOf(model("a"), model("b"))),
            provider("供应商2", listOf(model("a"))),
        )
        val result = dedupeModelIds(providers)

        assertEquals(0, result.reissued)
        assertEquals("无重复时不得重建对象", providers, result.providers)
    }

    @Test
    fun `同一家供应商内部的重复编号也要被修掉`() {
        val sid = Uuid.random()
        val result = dedupeModelIds(
            provider("供应商1", listOf(model("a", sid), model("a", sid), model("c")))
                .let { listOf(it) }
        )
        // 三条里 a 出现两次（同 sid）、c 一条：第一个 a 保留，只有第二个 a 需要重发 = 1 条
        assertEquals(1, result.reissued)
        val ids = result.providers[0].models.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals("第一个仍保留原号", sid, ids[0])
    }

    @Test
    fun `去重后候选链能同时装下两家的同名模型`() {
        val sid = Uuid.random()
        val deduped = dedupeModelIds(
            listOf(
                provider("openrouter", listOf(model("kimi-k3", sid)), Uuid.random()),
                provider("自建中转", listOf(model("kimi-k3", sid)), Uuid.random()),
            )
        )
        val ids = deduped.providers.flatMap { it.models }.map { it.id }.distinct()
        assertTrue("两家同名模型去重后必须是两个不同编号，才能同时进候选链", ids.size == 2)
    }
}
