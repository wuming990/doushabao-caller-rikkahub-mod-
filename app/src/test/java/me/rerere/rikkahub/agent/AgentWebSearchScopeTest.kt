package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.runtime.agentWebSearchEnabledFrom
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v324：子代理「能不能联网、说明书怎么说」的门禁测试。
 *
 * 真机 bug（用户报告：探测位有时能联网、有时只会回一句「本子代理只能读取工作区文件」）：
 * 官方 3.1 起联网开关变成**每个对话各一份**（ConversationConfig.enableWebSearch），
 * ChatService.updateChatSearch 在会话已固定 config 时只写 config 就直接 return，
 * 助手上的 enableWebSearch 从没被改动过。而子代理装配工具时读的偏偏是
 * `settings.getCurrentAssistant().enableWebSearch` —— 读到一个永远没人改的旧值。
 * 于是「主对话开着网、探测位上不了网」；反向组合则是「主对话没开网、探测位却能上网」。
 *
 * 两路独立审查又抓到两条，一并钉住：
 * - 对话查不到时不许回退到「此刻选中的助手」再决定联网（那等于把刚修掉的错位请回来，
 *   而且联网会真实外发并消耗额度，与 v281 查历史那条「回退更好」的取舍性质不同）；
 * - 说明书不许宣称没装配的工具。scrape_web 只在搜索服务提供抓取参数时才装配
 *   （SearchTools 里 `if (service.scrapingParameters(options) != null)`，Brave 返回 null），
 *   第一版按布尔无条件宣称了它 —— 正是本轮要消灭的那类「说明书说有、手里没有」分叉。
 *   现在说明书照抄实际装配到的工具名，这类分叉在结构上不可能出现。
 *
 * 与 AgentV242StateGuardTest 的分工：那边钉源码接线（防止改回旧口径），
 * 这里钉**可执行行为**。刻意以行为测试为主 —— 上一版正是靠一句字面断言
 * （要求代码里必须出现 getCurrentAssistant().enableWebSearch）把过时口径焊死了，
 * 字面断言挡不住口径漂移。
 */
class AgentWebSearchScopeTest {

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
    private val repoPath =
        "app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt"

    private fun thread(role: AgentRole = AgentRole.EXPLORER) = AgentThread(
        id = "t-web",
        conversationId = "c-web",
        task = "查宁波、慈溪的招聘信息",
        role = role,
        workspaceId = "ws-1",
        createdAt = Instant.now(),
    )

    /** 助手 + 指向它的当前助手 id；[assistantWebSearch] 是助手上那份（旧口径读的就是它）。 */
    private fun settingsWith(assistantWebSearch: Boolean): Pair<Settings, Assistant> {
        val assistant = Assistant(enableWebSearch = assistantWebSearch)
        return Settings(assistants = listOf(assistant), assistantId = assistant.id) to assistant
    }

    // ---- 口径：会话视角优先，与主对话同源 ----

    @Test
    fun `对话里开着联网就必须给联网工具，即使助手上那份开关是关的`() {
        // 用户实测撞到的组合：会话固定 config 后点联网，只写 config，助手仍是 false。
        val (settings, assistant) = settingsWith(assistantWebSearch = false)
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = ConversationConfig(enableWebSearch = true),
        )
        assertTrue(
            "会话已开联网时子代理必须拿到搜索工具（旧实现读助手开关，这里会判成 false）",
            agentWebSearchEnabledFrom(conversation, settings),
        )
    }

    @Test
    fun `对话里关着联网就不能给联网工具，即使助手上那份开关是开的`() {
        // 反向组合：旧实现会让探测位偷偷上网、多烧额度。
        val (settings, assistant) = settingsWith(assistantWebSearch = true)
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = ConversationConfig(enableWebSearch = false),
        )
        assertFalse(
            "会话明确关着联网时，子代理不许联网",
            agentWebSearchEnabledFrom(conversation, settings),
        )
    }

    @Test
    fun `会话还没固定配置时跟随助手`() {
        val (settingsOff, assistantOff) = settingsWith(assistantWebSearch = false)
        val (settingsOn, assistantOn) = settingsWith(assistantWebSearch = true)
        assertFalse(
            agentWebSearchEnabledFrom(
                Conversation(assistantId = assistantOff.id, messageNodes = emptyList(), config = null),
                settingsOff,
            ),
        )
        assertTrue(
            agentWebSearchEnabledFrom(
                Conversation(assistantId = assistantOn.id, messageNodes = emptyList(), config = null),
                settingsOn,
            ),
        )
    }

    // ---- 失败关闭（审查发现①）----

    @Test
    fun `对话查不到时失败关闭，不许拿此刻选中的助手当依据`() {
        // 这条钉的是审查抓到的回退分支：当前助手开着联网时，旧写法会返回 true，
        // 等于用「无关对话」的开关替用户决定要不要外发查询、要不要消耗额度。
        val (settings, _) = settingsWith(assistantWebSearch = true)
        assertFalse(
            "派活对话查不到（已删 / id 非法 / 读库异常）时一律不给联网工具",
            agentWebSearchEnabledFrom(null, settings),
        )
    }

    @Test
    fun `助手被删除时仍与主对话同源，而不是另起一套判断`() {
        // 方向 A 的要求是「跟你在界面上看到的完全一致」。对话还在、助手被删时，
        // 主对话走的也是 Settings.getAssistantOf 内部的 getStoredAssistantOf 回退，
        // 这里保持同源；刻意不复用「查不到对话」那条回退。
        val (settings, _) = settingsWith(assistantWebSearch = true)
        val orphan = Conversation(
            assistantId = Uuid.random(), // 已经不存在的助手 id
            messageNodes = emptyList(),
            config = null,
        )
        assertTrue(
            "与主对话同一条读取路径，不许在这里额外收紧成关闭（那会制造新的不一致）",
            agentWebSearchEnabledFrom(orphan, settings),
        )
    }

    // ---- 说明书：只说手里真有的工具（审查发现②）----

    @Test
    fun `只装配了 search_web 时，说明书不许提 scrape_web`() {
        // Brave 这类不提供抓取参数的服务根本不会装配 scrape_web。
        val prompt = buildAgentSystemPrompt(thread(), webSearchToolNames = listOf("search_web"))
        assertTrue(prompt.contains("search_web"))
        assertFalse(
            "说明书宣称一个没装配的工具，就是本轮修的那类分叉，结构上必须不可能发生",
            prompt.contains("scrape_web"),
        )
        assertTrue(prompt.contains("没列出来的那个不存在"))
    }

    @Test
    fun `两把都装配时才两把都提`() {
        val prompt = buildAgentSystemPrompt(
            thread(),
            webSearchToolNames = listOf("search_web", "scrape_web"),
        )
        assertTrue(prompt.contains("search_web"))
        assertTrue(prompt.contains("scrape_web"))
        assertTrue(
            "必须明确禁止再写「我无法联网搜索」这类自我设限结论",
            prompt.contains("我无法联网搜索"),
        )
        assertTrue(
            "必须纠正那句「只能读取工作区文件」的适用范围",
            prompt.contains("不能改文件、不能执行命令"),
        )
    }

    @Test
    fun `没装配任何联网工具时说明书一个字都不提联网`() {
        val prompt = buildAgentSystemPrompt(thread(), webSearchToolNames = emptyList())
        assertFalse(prompt.contains("search_web"))
        assertFalse(prompt.contains("scrape_web"))
        assertFalse(prompt.contains("联网搜索（本次已开启）"))
    }

    @Test
    fun `非探测位拿不到工具，所以说明书自然不提联网`() {
        // 说明书不再重复判断角色：它的唯一依据是实际装配结果。
        // 装配侧的角色红线（buildAgentTools 里 thread.role == AgentRole.EXPLORER）
        // 保证编程位/审查位的 toolNames 恒为空，因此这里传空列表即是它们的真实情况。
        for (role in listOf(AgentRole.DEFAULT, AgentRole.PROGRAMMER, AgentRole.REVIEWER)) {
            val prompt = buildAgentSystemPrompt(thread(role))
            assertFalse("$role 不该看到联网工具说明", prompt.contains("search_web"))
        }
    }

    @Test
    fun `默认参数必须是空列表，否则等于偷偷给所有子代理开联网说明`() {
        // 现有调用点里有单参数调用（测试与旧路径），默认值一旦变成非空就是偷偷宣称能力。
        assertTrue(
            buildAgentSystemPrompt(thread()) ==
                buildAgentSystemPrompt(thread(), false, emptyList()),
        )
    }

    // ---- 接线：单一事实来源 ----

    @Test
    fun `必须先装配再从装配结果取工具名喂说明书`() {
        val backend = source(backendPath)
        val assembleAt = backend.indexOf(
            "val tools: List<Tool> = buildAgentTools(thread, settings, webSearchEnabled, mcpCapability.tools)",
        )
        val namesAt = backend.indexOf("val webSearchToolNames = tools.map { it.name }")
        val messagesAt = backend.indexOf("val messages = buildAgentInputMessages(")
        assertTrue("装配语句缺失", assembleAt > 0)
        assertTrue("必须从 tools 派生工具名，不许自己再判断一次", namesAt > 0)
        assertTrue("装配必须先于说明书", assembleAt < namesAt && namesAt < messagesAt)
        assertTrue(
            "说明书必须用派生出来的名字",
            Regex("webSearchToolNames = webSearchToolNames,").findAll(backend).count() >= 3,
        )
    }

    @Test
    fun `说明书侧不许再自行判断角色或开关`() {
        // 重复判断就是下一次分叉的起点 —— 本轮第一版正是这么写的。
        val backend = source(backendPath)
        assertFalse(
            "提示词里不许出现 webSearchEnabled 这种自行判断",
            backend.contains("webSearchEnabled && thread.role"),
        )
        assertTrue(
            "说明书的唯一依据必须是实际装配结果非空",
            backend.contains("if (webSearchToolNames.isNotEmpty()) {"),
        )
    }

    @Test
    fun `装配侧的角色红线必须原样保留`() {
        val backend = source(backendPath)
        assertTrue(
            "「只给探测位开」是用户定的规矩，不许因为说明书改成照抄结果就从装配侧删掉",
            backend.contains("thread.role == AgentRole.EXPLORER && webSearchEnabled"),
        )
    }

    // ---- 开销回归（第二轮独立审查抓到）----

    @Test
    fun `算联网开关不许把整份消息历史读进内存`() {
        // getConversationById 内部会 loadMessageNodes，按页循环读到空为止；
        // 只为了算一个布尔开关就走它，等于每次派发都付一趟与对话长度成正比的读取。
        val backend = source(backendPath)
        val repo = source(repoPath)
        assertTrue(
            "联网判定必须走只读元数据的轻量入口",
            backend.contains(
                "conversationRepository.getConversationMetadataById(Uuid.parse(thread.conversationId))",
            ),
        )
        assertTrue(
            "轻量入口确实不许碰消息节点",
            repo.contains("conversationEntityToConversation(entity, emptyList())"),
        )
        assertTrue(
            "轻量入口必须仍走 DAO 的单行查询，不能反过来调用全量方法",
            repo.contains("suspend fun getConversationMetadataById(uuid: Uuid): Conversation?"),
        )
    }

    @Test
    fun `非探测位且MCP关着时不许读会话库`() {
        // 编程位、审查位拿不到搜索工具，让它们先查一趟库纯属白花（v1026 审查抓过的开销回归）。
        //
        // v1027 之后查库被提到 run() 里（联网与 MCP 共用一趟），所以「先判断再查库」这件事
        // 现在由 needsConversation 承担；agentWebSearchEnabled 里那句角色短路仍然保留，
        // 作为装配之外的第二道保险。两处都要在，缺一处都不算守住。
        val backend = source(backendPath)
        val needsAt = backend.indexOf(
            "val needsConversation = thread.role == AgentRole.EXPLORER || settings.allowAgentMcp",
        )
        val queryAt = backend.indexOf(
            "val dispatchConversation = if (needsConversation) threadConversation(thread) else null",
        )
        assertTrue("必须先算「要不要查库」", needsAt > 0)
        assertTrue("决定必须在真正查库之前，否则省不掉任何东西", needsAt < queryAt)
        assertTrue(
            "agentWebSearchEnabled 里的角色短路必须仍然保留（双保险）",
            backend.contains("if (thread.role != AgentRole.EXPLORER) return false"),
        )
    }

    @Test
    fun `红线：隔离助手副本的 enableWebSearch 必须仍然是 false`() {
        // 那份副本只负责生成参数，不参与工具装配；这条红线被测试钉着，不许借本次改动放开。
        val backend = source(backendPath)
        assertTrue(backend.contains("enableWebSearch = false,"))
        assertFalse(
            "不许把副本里那句改成读真实开关",
            backend.contains("enableWebSearch = webSearchEnabled,") ||
                backend.contains("enableWebSearch = webSearchToolNames"),
        )
    }
}
