package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.runtime.buildAgentInputMessages
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import me.rerere.rikkahub.agent.runtime.buildIsolatedAssistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * v281：「允许子代理检索历史对话」这项能力的门禁测试。
 *
 * v280 把开关、工具装配、UI 都做了，却一条测试都没有，于是两个真问题一直没被发现：
 *
 * 1. **提示词没告诉子代理它能查历史。** 提示词里写死了「你只能读取工作区文件进行查证」，
 *    模型据此认定自己的能力边界就到工作区为止 —— 工具塞进 tools 列表也不会去用，开关白开。
 * 2. **列的是错的助手的历史。** 装配时传的是 `settings.getCurrentAssistant().id`（界面上
 *    此刻选中的助手），而不是派出这条子代理的那个对话的助手。用户派完就切走对话是常态，
 *    于是 `recent_chats` 列出另一个助手的会话，子代理还察觉不到。
 *
 * 这两条都在这里钉住。同时钉住那条一直守着的红线：隔离助手副本的
 * `enableRecentChatsReference` 必须仍是 false —— 新开关走的是独立路径，关掉它以后子代理
 * 行为必须与 v279 一字节不差。
 */
class AgentConversationMemoryTest {

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

    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
    private val storePath =
        "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"
    private val toolsPath =
        "app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt"
    private val assistantPath =
        "app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt"
    private val chatServicePath =
        "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
    private val chatPagePath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"
    private val filesPickerPath =
        "app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt"

    private fun thread(
        role: AgentRole = AgentRole.DEFAULT,
        writablePaths: List<String> = emptyList(),
    ) = AgentThread(
        id = "t-mem",
        conversationId = "c-mem",
        task = "确认这个写法当初为什么这么定",
        role = role,
        workspaceId = "ws-1",
        writablePaths = writablePaths,
        createdAt = Instant.now(),
    )

    // ---- 开关本身 ----

    @Test
    fun `开关默认必须是关的（隐私相关，不能默认外发历史）`() {
        assertFalse(Settings().allowAgentConversationSearch)
        val store = source(storePath)
        assertTrue(
            "读设置必须用 == true，缺 key 时才会落到 false；用 ?: false 也行但别写成 != false",
            store.contains("preferences[AGENT_CONVERSATION_SEARCH] == true"),
        )
        assertTrue(
            "字段默认值必须是 false",
            store.contains("val allowAgentConversationSearch: Boolean = false,"),
        )
    }

    // ---- 提示词：关 ----

    @Test
    fun `开关关闭时提示词一个字都不提历史检索`() {
        val prompt = buildAgentSystemPrompt(thread())
        assertFalse(prompt.contains("recent_chats"))
        assertFalse(prompt.contains("conversation_search"))
        assertFalse(prompt.contains("历史对话"))
        // 默认参数必须是 false —— 现有调用点全是单参数调用，默认值一旦翻成 true 就等于偷偷开权限
        assertEquals(buildAgentSystemPrompt(thread()), buildAgentSystemPrompt(thread(), false))
    }

    // ---- 提示词：开 ----

    @Test
    fun `开关打开时提示词必须把两把工具和用法讲清楚`() {
        val prompt = buildAgentSystemPrompt(thread(), memoryEnabled = true)
        assertTrue("必须点名 recent_chats", prompt.contains("recent_chats"))
        assertTrue("必须点名 conversation_search", prompt.contains("conversation_search"))
        // 关键一句：否则模型会被上面「只能读取工作区文件」那句挡住，拿着工具也不用
        assertTrue(
            "必须澄清「只能读工作区文件」指的是不能改文件，不是禁止查历史",
            prompt.contains("不是禁止你查历史"),
        )
        // 全文索引走 jieba 分词，整句话搜不中，这条提示直接决定搜得到搜不到
        assertTrue("必须教它用短关键词", prompt.contains("关键词要短"))
        assertTrue("必须要求写明出处", prompt.contains("对话标题"))
    }

    @Test
    fun `历史检索不按角色区分 —— 四种角色打开后都拿得到`() {
        AgentRole.entries.forEach { role ->
            val prompt = buildAgentSystemPrompt(thread(role = role), memoryEnabled = true)
            assertTrue(
                "$role 打开开关后也必须拿到历史检索说明（权限由用户开关给定，与角色无关）",
                prompt.contains("conversation_search"),
            )
        }
    }

    @Test
    fun `写权限与历史检索互不影响`() {
        val writable = buildAgentSystemPrompt(
            thread(role = AgentRole.PROGRAMMER, writablePaths = listOf("app/src")),
            memoryEnabled = true,
        )
        assertTrue("写权限说明还在", writable.contains("只能修改下面这些路径"))
        assertTrue("历史检索说明也在", writable.contains("conversation_search"))
        assertTrue("仍然没有终端", writable.contains("你没有终端"))

        val readOnly = buildAgentSystemPrompt(thread(), memoryEnabled = true)
        assertTrue(readOnly.contains("你只能读取工作区文件进行查证"))
        assertFalse("只读线程不该出现写白名单", readOnly.contains("只能修改下面这些路径"))
    }

    // ---- 两条提示词通路都要跟着开关走 ----

    @Test
    fun `隔离助手副本会带上这段说明，但记忆红线不许动`() {
        val on = buildIsolatedAssistant(Assistant(), thread(), memoryEnabled = true)
        assertTrue(on.systemPrompt.contains("conversation_search"))
        // 红线：这道保险守的是主对话那条共享路径，新开关不走它，也不许把它打开
        assertFalse(on.enableRecentChatsReference)
        assertFalse(on.enableMemory)
        assertFalse(on.useGlobalMemory)

        val off = buildIsolatedAssistant(Assistant(), thread())
        assertFalse(off.systemPrompt.contains("conversation_search"))
        assertFalse(off.enableRecentChatsReference)
    }

    @Test
    fun `输入消息里的系统提示也跟着开关走`() {
        val on = buildAgentInputMessages(
            thread(), emptyList(), resume = false, memoryEnabled = true,
        )
        assertTrue(on.first().toText().contains("conversation_search"))

        val off = buildAgentInputMessages(thread(), emptyList(), resume = false)
        assertFalse(off.first().toText().contains("conversation_search"))
    }

    // ---- 装配（源码级，防以后被改回去） ----

    @Test
    fun `工具装配必须受开关保护，而且只有这一处`() {
        val backend = source(backendPath)
        assertTrue(
            "必须由用户开关决定装不装",
            backend.contains("if (settings.allowAgentConversationSearch) {"),
        )
        assertEquals(
            "历史检索工具只能在这一处装配，不许有第二条路绕过开关",
            1,
            Regex("createConversationTools\\(").findAll(backend).count(),
        )
        assertTrue(
            "两条提示词通路都必须跟着同一个开关",
            Regex("memoryEnabled = settings\\.allowAgentConversationSearch")
                .findAll(backend).count() == 2,
        )
    }

    @Test
    fun `列历史要按派出子代理的那个对话的助手，不是界面上当前选中的助手`() {
        val backend = source(backendPath)
        assertTrue(
            "必须按 thread.conversationId 反查该对话的助手（v281 修的就是这个）",
            backend.contains("getConversationById(Uuid.parse(thread.conversationId))"),
        )
        assertTrue(
            "取的必须是那个对话的 assistantId（源码里可能换行，所以按正则匹配而不是单行子串）",
            Regex(
                "getConversationById\\(Uuid\\.parse\\(thread\\.conversationId\\)\\)\\s*\\?\\.assistantId"
            ).containsMatchIn(backend),
        )
        assertTrue(
            "查不到时才回退到当前助手",
            backend.contains("?: settings.getCurrentAssistant().id"),
        )
        assertFalse(
            "不许再把 getCurrentAssistant().id 直接当成 createConversationTools 的入参（v280 的 bug）",
            backend.contains("assistantId = settings.getCurrentAssistant().id"),
        )
        assertTrue(
            "查库要在 suspend 里做，装配函数必须是 suspend",
            backend.contains("private suspend fun buildAgentTools("),
        )
    }

    @Test
    fun `查库不许把「用户点停止」吞掉`() {
        val backend = source(backendPath)
        val body = backend
            .substringAfter("private suspend fun agentMemoryAssistantId(")
            .substringBefore("\n    }")
        assertFalse(
            "不许用 runCatching —— 它连 CancellationException 一起吞，用户点停止就不会立刻生效。" +
                "只认调用语法（runCatching {），否则会被注释里提到这个词的说明文字误伤",
            Regex("runCatching\\s*\\{").containsMatchIn(body),
        )
        assertTrue(
            "必须显式把协程取消原样上抛",
            body.contains("catch (e: CancellationException)") && body.contains("throw e"),
        )
        assertTrue(
            "其他异常才允许当成「查不到」回退",
            body.contains("catch (e: Exception)"),
        )
    }

    // ---- v284：助手级子代理开关 ----

    @Test
    fun `助手级子代理开关默认打开，升级后行为不变`() {
        // 默认必须是 true：否则老用户升级后会发现所有助手突然都没有子代理了。
        assertTrue(Assistant().enableAgentTools)
        assertTrue(
            "字段默认值必须写成 true",
            source(assistantPath).contains("val enableAgentTools: Boolean = true,"),
        )
    }

    @Test
    fun `总闸和分闸必须同时打开才装配子代理工具`() {
        val service = source(chatServicePath)
        assertTrue(
            "主对话装配子代理控制工具时必须同时看全局开关与当前助手开关",
            service.contains("if (settings.enableAgentTools && assistant.enableAgentTools) {"),
        )
        assertEquals(
            "只能有这一处装配，不许有旁路绕过助手级开关",
            1,
            Regex("createAgentControlTools\\(").findAll(service).count(),
        )
    }

    @Test
    fun `子代理面板同样受助手级开关控制，且不破坏 v224 的总闸门禁`() {
        val chatPage = source(chatPagePath)
        // v224 门禁：总闸关掉后面板完全不显示（有专门的测试按这一行原文钉住，别改写法）
        assertTrue(chatPage.contains("if (setting.enableAgentTools) {"))
        // v284：总闸开着时，还要看当前助手
        assertTrue(
            "面板必须再判一次助手级开关，否则关掉的助手仍会看到子代理面板",
            chatPage.contains("if (assistant.enableAgentTools) {"),
        )
    }

    @Test
    fun `设置界面要有助手级开关，并且改的是当前助手而不是全局`() {
        val picker = source(filesPickerPath)
        assertTrue(picker.contains("R.string.agent_tools_assistant_switch_title"))
        assertTrue(
            "开关状态必须读当前助手的字段",
            picker.contains("checked = agentAssistant.enableAgentTools"),
        )
        assertTrue(
            "写回时必须只改当前助手那一条，别把整份助手列表覆盖掉",
            picker.contains("assistant.copy(enableAgentTools = checked)"),
        )
        assertTrue(
            "全局总闸那个开关必须还在（两个开关是并存关系）",
            picker.contains("checked = settings.enableAgentTools"),
        )
    }

    @Test
    fun `子代理拿到的两把工具就是主对话那两把，没有夹带写能力`() {
        val tools = source(toolsPath)
        assertTrue(tools.contains("name = \"recent_chats\""))
        assertTrue(tools.contains("name = \"conversation_search\""))
        assertEquals(
            "这个文件只该提供这两把工具",
            2,
            Regex("Tool\\(\\s*\\n\\s*name = ").findAll(tools).count(),
        )
        assertFalse(
            "历史工具里绝不许出现删除/写入类动作",
            tools.contains("delete") || tools.contains("insert") || tools.contains("update("),
        )
    }
}
