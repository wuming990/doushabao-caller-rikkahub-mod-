package me.rerere.rikkahub.agent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.runtime.resolveModelChain
import me.rerere.rikkahub.data.datastore.AGENT_FALLBACK_MODEL_LIMIT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.decodeAgentFallbackModelIds
import me.rerere.rikkahub.data.datastore.encodeAgentFallbackModelIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v237 门禁：用户必须真的有地方指定备用模型，而且指定之后候选链必须真的变长。
 *
 * ## 用户真机反馈原话
 *
 * > 「目前的问题是我根本没有选择备用模型的地方，只有一个模型你怎么切换可用模型续跑？」
 *
 * 这句话戳破了 v236 的一个真实缺陷：v236 号称做了「备用模型保底」，
 * 但候选链的四层来源（线程点名 → 角色覆盖 → 子代理默认 → 主对话模型）在**默认配置下**
 * 前三层全是 null，去重后只剩 1 个模型；而 `GenerationAgentBackend.run` 里换人的代码
 * 写在 `if (index > 0)` 里 —— 链子只有 1 节时这个条件恒假，换人代码一次都进不去。
 * 结果就是用户看到的「一个模型崩了就直接失败」。
 *
 * v236 的门禁之所以没抓到，是因为它全是「grep 源码字符串」，
 * **候选链的实际输出从未被执行验证过**。本测试类补的正是这一块：
 * 直接喂 Settings 调 [resolveModelChain]，断言返回列表的长度与顺序。
 */
class AgentFallbackModelPickerTest {

    // ------------------------------------------------------------ 脚手架

    private fun model(name: String): Model =
        Model(modelId = name, displayName = name, type = ModelType.CHAT)

    private fun settingsWith(
        models: List<Model>,
        chatModelId: Uuid,
        agentModelId: Uuid? = null,
        agentFallbackModelIds: List<Uuid> = emptyList(),
        agentRoleModelOverrides: Map<String, Uuid?> = emptyMap(),
    ): Settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(name = "测试服务商", models = models)),
        chatModelId = chatModelId,
        agentModelId = agentModelId,
        agentFallbackModelIds = agentFallbackModelIds,
        agentRoleModelOverrides = agentRoleModelOverrides,
    )

    private fun thread(modelId: String? = null, role: AgentRole = AgentRole.DEFAULT) =
        AgentThread(
            conversationId = "conv-1",
            task = "随便查一下",
            modelId = modelId,
            role = role,
            createdAt = Instant.now(),
        )

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

    // ------------------------------------------------ 核心回归：链子会不会塌成 1 个

    @Test
    fun `什么都不配时候选链只有一个模型这就是用户遇到的缺陷`() {
        val main = model("主对话模型")
        val settings = settingsWith(models = listOf(main), chatModelId = main.id)

        val chain = resolveModelChain(thread(), settings)

        assertEquals(
            "默认配置下候选链就是只有 1 节 —— 这不是断言错误，是必须被 v237 解决的现状",
            1,
            chain.size,
        )
        assertEquals(main.id, chain.single().id)
    }

    @Test
    fun `配了备用模型之后候选链必须真的变长换人才可能发生`() {
        val main = model("主对话模型")
        val cheap = model("便宜的子代理模型")
        val backup = model("备用模型")
        val settings = settingsWith(
            models = listOf(main, cheap, backup),
            chatModelId = main.id,
            agentModelId = cheap.id,
            agentFallbackModelIds = listOf(backup.id),
        )

        val chain = resolveModelChain(thread(), settings)

        assertTrue("链子必须至少 2 节，否则 if (index > 0) 永远进不去", chain.size >= 2)
        assertEquals(
            "顺序必须是：子代理默认 → 备用模型 → 主对话模型",
            listOf(cheap.id, backup.id, main.id),
            chain.map { it.id },
        )
    }

    @Test
    fun `只配备用模型不配子代理模型也要能保底`() {
        val main = model("主对话模型")
        val backup = model("备用模型")
        val settings = settingsWith(
            models = listOf(main, backup),
            chatModelId = main.id,
            agentFallbackModelIds = listOf(backup.id),
        )

        val chain = resolveModelChain(thread(), settings)

        assertEquals(listOf(backup.id, main.id), chain.map { it.id })
    }

    @Test
    fun `备用模型必须排在主对话模型之前否则贵的先跑等于白配`() {
        val main = model("贵的主对话模型")
        val b1 = model("便宜备用1")
        val b2 = model("便宜备用2")
        val settings = settingsWith(
            models = listOf(main, b1, b2),
            chatModelId = main.id,
            agentFallbackModelIds = listOf(b1.id, b2.id),
        )

        val chain = resolveModelChain(thread(), settings).map { it.id }

        assertTrue("备用1 必须早于主对话模型", chain.indexOf(b1.id) < chain.indexOf(main.id))
        assertTrue("备用2 必须早于主对话模型", chain.indexOf(b2.id) < chain.indexOf(main.id))
    }

    @Test
    fun `角色级模型仍然是第一棒备用模型不能把它挤掉`() {
        val main = model("主对话模型")
        val cheap = model("子代理默认")
        val roleModel = model("探索角色专用")
        val backup = model("备用模型")
        val settings = settingsWith(
            models = listOf(main, cheap, roleModel, backup),
            chatModelId = main.id,
            agentModelId = cheap.id,
            agentFallbackModelIds = listOf(backup.id),
            agentRoleModelOverrides = mapOf("explorer" to roleModel.id),
        )

        val chain = resolveModelChain(thread(role = AgentRole.EXPLORER), settings)

        assertEquals(
            listOf(roleModel.id, cheap.id, backup.id, main.id),
            chain.map { it.id },
        )
    }

    @Test
    fun `备用模型和已有模型重复时必须去重不能白烧配额`() {
        val main = model("主对话模型")
        val cheap = model("子代理默认")
        val settings = settingsWith(
            models = listOf(main, cheap),
            chatModelId = main.id,
            agentModelId = cheap.id,
            // 故意把两个已经在链子里的模型再填一遍
            agentFallbackModelIds = listOf(cheap.id, main.id),
        )

        val chain = resolveModelChain(thread(), settings)

        assertEquals("重复的候选必须被去掉", listOf(cheap.id, main.id), chain.map { it.id })
    }

    @Test
    fun `备用模型被删掉之后必须被跳过而不是让整条链报错`() {
        val main = model("主对话模型")
        val deleted = model("已经被删掉的模型")
        val settings = settingsWith(
            // 注意 deleted 没有加进 providers，模拟用户把模型删了
            models = listOf(main),
            chatModelId = main.id,
            agentFallbackModelIds = listOf(deleted.id),
        )

        val chain = resolveModelChain(thread(), settings)

        assertEquals("删掉的模型要静默跳过", listOf(main.id), chain.map { it.id })
    }

    @Test
    fun `主模型派发时点名的模型永远是第一棒`() {
        val main = model("主对话模型")
        val named = model("主模型点名的")
        val backup = model("备用模型")
        val settings = settingsWith(
            models = listOf(main, named, backup),
            chatModelId = main.id,
            agentFallbackModelIds = listOf(backup.id),
        )

        val chain = resolveModelChain(thread(modelId = named.id.toString()), settings)

        assertEquals(listOf(named.id, backup.id, main.id), chain.map { it.id })
    }

    // ------------------------------------------------------------ 落盘编解码

    @Test
    fun `备用模型列表必须能原样存下来再读回来`() {
        val ids = listOf(Uuid.random(), Uuid.random())

        val restored = decodeAgentFallbackModelIds(encodeAgentFallbackModelIds(ids))

        assertEquals("顺序也要保住，顺序决定先试谁", ids, restored)
    }

    @Test
    fun `超过三个必须被截掉两侧都要截`() {
        val ids = List(6) { Uuid.random() }

        assertEquals(
            "encode 侧必须截断",
            AGENT_FALLBACK_MODEL_LIMIT,
            decodeAgentFallbackModelIds(encodeAgentFallbackModelIds(ids)).size,
        )
        // 直接伪造一份超长的存档（比如旧版本或手改的备份文件），decode 也必须自己截
        val oversized = "[" + ids.joinToString(",") { "\"$it\"" } + "]"
        assertEquals(
            "decode 侧也必须截断，不能信任存档内容",
            AGENT_FALLBACK_MODEL_LIMIT,
            decodeAgentFallbackModelIds(oversized).size,
        )
    }

    @Test
    fun `重复的备用模型在落盘时就要去掉`() {
        val a = Uuid.random()

        val restored = decodeAgentFallbackModelIds(encodeAgentFallbackModelIds(listOf(a, a, a)))

        assertEquals(listOf(a), restored)
    }

    @Test
    fun `存档坏了不能崩必须退回空列表`() {
        assertTrue(decodeAgentFallbackModelIds(null).isEmpty())
        assertTrue(decodeAgentFallbackModelIds("").isEmpty())
        assertTrue(decodeAgentFallbackModelIds("   ").isEmpty())
        assertTrue(decodeAgentFallbackModelIds("[not json").isEmpty())
        assertTrue(decodeAgentFallbackModelIds("{\"a\":1}").isEmpty())
        // 混了坏 uuid：坏的跳过，好的留下
        val good = Uuid.random()
        assertEquals(
            listOf(good),
            decodeAgentFallbackModelIds("[\"不是uuid\",\"$good\"]"),
        )
    }

    // ------------------------------------------------------- 落盘链路（v220 教训）

    @Test
    fun `备用模型必须读也必须写否则用户配好的会自己消失`() {
        val store = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue(
            "缺少 DataStore key",
            store.contains("val AGENT_FALLBACK_MODELS = stringPreferencesKey(\"agent_fallback_models\")"),
        )
        assertTrue(
            "缺少读回：Settings 构造时必须 decode",
            store.contains("agentFallbackModelIds = decodeAgentFallbackModelIds(preferences[AGENT_FALLBACK_MODELS])"),
        )
        assertTrue(
            "缺少落盘：v219 就是因为只读不写，用户配好的子代理模型会自己变回默认（v220 才修）",
            store.contains("encodeAgentFallbackModelIds(settings.agentFallbackModelIds)"),
        )
        assertTrue(
            "Settings 字段必须有默认值，否则旧备份文件反序列化会炸",
            store.contains("val agentFallbackModelIds: List<Uuid> = emptyList(),"),
        )
    }

    // ------------------------------------------------------------ 界面入口

    @Test
    fun `设置界面必须真的有一个添加备用模型的入口`() {
        val picker = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue(
            "缺少多选面板，用户就还是没地方选 —— 这正是用户的原话反馈",
            picker.contains("state = agentFallbackPicker,"),
        )
        assertTrue("缺少添加按钮文案", picker.contains("R.string.agent_tools_fallback_add"))
        assertTrue(
            "满 3 个之后必须禁用添加按钮",
            picker.contains("enabled = agentFallbackModelIds.size < AGENT_FALLBACK_MODEL_LIMIT"),
        )
        assertTrue(
            "必须能逐个移除",
            picker.contains("R.string.agent_tools_fallback_remove"),
        )
    }

    @Test
    fun `设置界面必须显示接力顺序并在只有一个模型时明确警告`() {
        val picker = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("缺少接力顺序预览", picker.contains("val agentChainModelIds = buildList"))
        assertTrue(
            "缺少「只有 1 个模型」的警告 —— 用户此前正是因为界面什么都不说才以为已经生效",
            picker.contains("R.string.agent_tools_chain_warn"),
        )
        assertTrue(
            "预览顺序必须跟运行时一致：备用模型排在主对话模型之前",
            picker.substringAfter("val agentChainModelIds = buildList")
                .substringBefore("agentChainHasFallback")
                .let { block ->
                    block.indexOf("addAll(agentFallbackModelIds)") in 0 until block.indexOf("add(settings.chatModelId)")
                },
        )
        assertTrue(
            "预览必须过滤已删除的模型，否则会显示出比实际更多的保底层数",
            picker.contains("filter { settings.findModelById(it) != null }"),
        )
    }

    @Test
    fun `子代理界面必须显示这次实际是哪个模型跑的`() {
        val threadPage = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt")
        assertTrue(
            "详情页必须显示实际模型，否则用户看不出有没有换过人",
            threadPage.contains("实际跑的模型："),
        )
        assertTrue(
            "换过备用模型时要特别标出来",
            threadPage.contains("（中途换过备用模型）"),
        )
        assertTrue(
            "是否换过必须由真实事件决定，不能凭猜",
            threadPage.contains("events.any { it.type == \"MODEL_FALLBACK\" }"),
        )
        val panel = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentActivityPanel.kt")
        assertTrue("弹窗详情也要显示实际模型", panel.contains("实际跑的模型："))
    }

    @Test
    fun `v236 那句错误的注释必须被改掉免得后人照着删掉备用模型`() {
        val backend =
            source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertFalse(
            "「这条链不需要任何新设置」这个判断已被用户实测推翻，注释必须改掉",
            backend.contains("这条链不需要任何新设置：你只要在设置里给子代理配了便宜模型"),
        )
        assertTrue(
            "候选链必须真的把备用模型加进去",
            backend.contains("settings.agentFallbackModelIds.forEach { add(it.toString()) }"),
        )
    }
}
