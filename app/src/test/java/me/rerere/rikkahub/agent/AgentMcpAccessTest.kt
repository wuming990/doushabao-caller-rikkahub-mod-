package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.agent.runtime.runAgentMcpToolGuarded
import me.rerere.rikkahub.agent.runtime.AgentMcpCapability
import me.rerere.rikkahub.agent.runtime.AgentMcpDecision
import me.rerere.rikkahub.agent.runtime.MAX_MCP_TOOL_NAME_LENGTH
import me.rerere.rikkahub.agent.runtime.agentMcpToolDescription
import me.rerere.rikkahub.agent.runtime.decideAgentMcpTools
import me.rerere.rikkahub.agent.runtime.dropDuplicateMcpNames
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.agent.runtime.AgentMcpKeptTool
import me.rerere.rikkahub.agent.runtime.buildAgentMcpTool
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.agentMcpToolRecords
import me.rerere.rikkahub.agent.runtime.agentMcpAssistantFor
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import me.rerere.rikkahub.agent.runtime.buildIsolatedAssistant
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * v1027：「允许子代理调用 MCP」开关的门禁测试。
 *
 * 背景：子代理一直用不了 MCP —— 隔离助手副本把 mcpServers 写死成 emptySet()
 * （注释原话「清空…MCP…等一切污染源」），工具装配里也没有 MCP 那一路。
 * 用户要求把这项能力交回自己决定，做成开关，默认关。
 *
 * 用户已确认的三条取舍，都在这里钉住：
 * 1. 一个开关管所有角色（不按角色再分一层白名单）；
 * 2. 默认关（关掉时行为必须与 v1026 一字节不差）；
 * 3. 不加额外限制 —— 能用哪些服务器仍由助手上的勾选决定，本开关不新增、不放宽。
 *
 * 另外钉住两件本轮刻意做的事：
 * - 口径必须按「派活那个对话」取，与 v324 联网那条同源；查不到对话时**失败关闭**。
 *   这条不是洁癖：MCP 会真实调用外部服务、可能改外部数据或产生费用，
 *   拿不准时拿「此刻界面上选中的助手」凑数，等于用无关对话的勾选替用户放权。
 * - 说明书必须把风险写出来（MCP 不受「不能执行命令/编译/删除」那层限制约束）。
 *   这句不是装饰：子代理原本的隔离承诺会被 MCP 绕过，必须让它知道边界变了。
 *
 * 与 v1026 的教训保持一致：这里以**行为断言**为主（直接调纯函数拿结果），
 * 源码接线断言只用于纯函数覆盖不到的装配路径。
 */
class AgentMcpAccessTest {

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
    private val storePath = "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"
    private val settingsPath = "app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt"
    private val chatToolFactoryPath =
        "app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt"
    private val mcpManagerPath =
        "app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt"

    /**
     * 造一条真实的 MCP 工具条目（serverId, serverName, McpTool）。
     *
     * v1027 复审后必须用**真的 McpTool**，不能再拿字符串三元组喂纯函数 ——
     * 复审指出：原先「非法名被跳过」「待批准被跳过」两条只是读源码字面量，
     * 把真正的过滤删掉、只留下计数，测试照样全绿。现在直接喂进决策函数看结果。
     */
    private fun mcpEntry(
        serverId: String,
        serverName: String,
        toolName: String,
        needsApproval: Boolean = false,
        enable: Boolean = true,
    ) = Triple(
        Uuid.parse(serverId),
        serverName,
        McpTool(name = toolName, needsApproval = needsApproval, enable = enable),
    )

    private fun decide(entries: List<Triple<Uuid, String, McpTool>>) = decideAgentMcpTools(entries)

    private fun keptNames(entries: List<Triple<Uuid, String, McpTool>>): List<String> =
        decide(entries).kept.map { it.displayName }

    /**
     * 用真实决策结果造能力对象 —— 形态与 `agentMcpTools` 的返回完全一致。
     *
     * 本轮就踩过一次：测试用只带名字、计数全零的对象去比决策结果，
     * 等于测了个空壳。要么用这个重载，要么显式传计数。
     */
    private fun capabilityOf(decision: AgentMcpDecision) = AgentMcpCapability(
        tools = decision.kept.map {
            Tool(name = it.displayName, description = "", execute = { emptyList() })
        },
        decision = decision,
    )

    /** 说明书唯一的输入形态：一个能力对象（不再是散装的名字列表 + 三个计数） */
    private fun capabilityOf(
        names: List<String>,
        approval: Int = 0,
        invalidName: Int = 0,
        overflow: Int = 0,
        conflict: Int = 0,
        ambiguous: Int = 0,
    ) = AgentMcpCapability(
        tools = names.map { Tool(name = it, description = "", execute = { emptyList() }) },
        decision = AgentMcpDecision(
            needsApprovalCount = approval,
            invalidNameCount = invalidName,
            nameOverflowCount = overflow,
            conflictDroppedCount = conflict,
            ambiguousServerCount = ambiguous,
        ),
    )


    private val serverA = Uuid.random()
    private val serverB = Uuid.random()
    private val serverC = Uuid.random()

    /**
     * 助手与对话必须共享**同一个** assistant.id。
     *
     * 上一版每次调用都新建 Assistant（id 是 random），于是 conversation.assistantId 与
     * 传进去的 Settings 里的助手对不上，getStoredAssistantOf 只能回退到「当前助手」——
     * 结果碰巧也对，但那是在测回退路径，不是测「按派活那个对话取」。
     * 这种歪打正着比直接失败更糟，所以把实例提到外面共享。
     */
    private val assistant = Assistant(name = "主助手", mcpServers = setOf(serverA))

    private fun thread(role: AgentRole = AgentRole.EXPLORER) = AgentThread(
        id = "t-mcp",
        conversationId = "c-mcp",
        task = "用 MCP 查一下外部系统里的订单状态",
        role = role,
        workspaceId = "ws-1",
        createdAt = Instant.now(),
    )

    /**
     * 助手上勾 serverA，会话 config 勾 serverB。
     *
     * 注意 serverB 必须同时出现在 Settings.mcpServers 里 —— Settings.getAssistantOf
     * 在会话固定过 config 时，会把不在全局列表里的服务器 id 滤掉（防引用已删除的服务器），
     * 这是官方既有语义，测试必须按真实语义构造，否则测的是另一回事。
     */
    private fun settingsWith(allowAgentMcp: Boolean): Settings = Settings(
        assistants = listOf(assistant),
        assistantId = assistant.id,
        mcpServers = listOf(
            McpServerConfig.SseTransportServer(id = serverA, url = "https://a.example/sse"),
            McpServerConfig.SseTransportServer(id = serverB, url = "https://b.example/sse"),
        ),
        allowAgentMcp = allowAgentMcp,
    )

    private fun conversationWith(config: ConversationConfig?): Conversation = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
        config = config,
    )

    // ---- 默认必须是关 ----

    @Test
    fun `开关默认必须是关（这是放权开关，不能默认放）`() {
        assertFalse("字段默认值必须是 false", Settings().allowAgentMcp)
        val store = source(storePath) + "\n" + source(settingsPath)
        assertTrue(
            "读设置必须用 == true，缺 key 时才落到 false；写成 != false 会让老存档被判定为开启",
            store.contains("preferences[AGENT_MCP] == true"),
        )
        assertTrue("必须有写盘，否则下次 DataStore 发射会被默认值覆盖回去",
            store.contains("preferences[AGENT_MCP] = settings.allowAgentMcp"))
        assertTrue("字段默认值必须是 false",
            store.contains("val allowAgentMcp: Boolean = false,"))
    }

    // ---- 失败关闭与口径 ----

    @Test
    fun `开关关着时一个 MCP 工具都不给`() {
        val settings = settingsWith(allowAgentMcp = false)
        val conversation = conversationWith(ConversationConfig(mcpServers = setOf(serverB)))
        assertNull(
            "开关关着必须返回 null（行为与 v1026 一致）",
            agentMcpAssistantFor(settings, conversation),
        )
    }

    @Test
    fun `查不到派活对话时失败关闭，不许拿此刻选中的助手凑数`() {
        val settings = settingsWith(allowAgentMcp = true)
        assertNull(
            "MCP 会真实调用外部服务、可能改外部数据或产生费用；拿不准用户意图时一律不放权",
            agentMcpAssistantFor(settings, null),
        )
    }

    @Test
    fun `派活对话所属的助手被删除时也必须失败关闭`() {
        // 🔴 复审抓出来的第二条真问题：getAssistantOf 内部是 getStoredAssistantOf，
        // 助手查不到时会**退回此刻选中的助手**。上一版把这条当成「与主对话同源」放过了，
        // 但注释同时写着「绝不拿此刻选中的助手凑数」—— 代码与注释互相矛盾，
        // 而真实后果是用「别的助手勾的 MCP 服务器」去调外部服务，属于越权放权。
        // 联网那一路保留回退是有意的（v324 口径，只读、不改外部数据），这里必须更严。
        val survivor = Assistant(name = "还活着的助手", mcpServers = setOf(serverB))
        val settings = Settings(
            assistants = listOf(survivor),
            assistantId = survivor.id,
            mcpServers = listOf(
                McpServerConfig.SseTransportServer(id = serverA, url = "https://a.example/sse"),
                McpServerConfig.SseTransportServer(id = serverB, url = "https://b.example/sse"),
            ),
            allowAgentMcp = true,
        )
        // 这个对话属于一个已经被删掉的助手，而界面上此刻选中的是另一个助手
        val orphan = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
            config = null,
        )
        assertNull(
            "所属助手已删除 → 不许改用此刻选中助手的 MCP 勾选",
            agentMcpAssistantFor(settings, orphan),
        )
    }

    @Test
    fun `开关打开时按派活那个对话的勾选，不按助手上的勾选`() {
        val settings = settingsWith(allowAgentMcp = true)
        // 助手勾 serverA，这个对话勾 serverB —— 必须拿到 B，与主对话同源
        val conversation = conversationWith(ConversationConfig(mcpServers = setOf(serverB)))
        val result = agentMcpAssistantFor(settings, conversation)
        assertNotNull("开关开着必须给出助手副本", result)
        assertEquals(
            "会话固定过 config 时必须取会话上的勾选（否则又回到 v324 修掉的那种错位）",
            setOf(serverB), result!!.mcpServers,
        )
    }

    @Test
    fun `会话明确没勾任何 MCP 时不得回落到助手的勾选`() {
        val settings = settingsWith(allowAgentMcp = true)
        val conversation = conversationWith(ConversationConfig(mcpServers = emptySet()))
        val result = agentMcpAssistantFor(settings, conversation)
        assertNotNull(result)
        assertTrue(
            "会话上明确是空 —— 必须尊重它，不许偷偷用助手上的 serverA",
            result!!.mcpServers.isEmpty(),
        )
    }

    @Test
    fun `会话还没固定配置时才跟随助手`() {
        val settings = settingsWith(allowAgentMcp = true)
        val conversation = conversationWith(config = null)
        val result = agentMcpAssistantFor(settings, conversation)
        assertNotNull(result)
        assertEquals(
            "没固定 config 时走助手，与主对话同一条读取路径",
            setOf(serverA), result!!.mcpServers,
        )
    }

    // ---- 服务器名合法性（与主对话同一套规则）----

    @Test
    fun `非法服务器名要跳过而不是炸掉整趟子代理，而且必须计入数量`() {
        // 主对话遇到非法名直接抛 InvalidMcpServerNamesException；子代理里抛会把整趟任务
        // 炸掉而用户看不见原因，所以这里改成跳过。
        //
        // 🔴 复审指出原先这条只检查源码里有没有那个函数名 —— 把过滤删掉照样绿。
        // 现在真的喂进决策函数：非法名那条不许出现在结果里，而且数量必须被记下来，
        // 否则说明书无从交代「开关打开了却什么都没发生」。
        // 🔴 第七轮返工：三条记录必须分属**三个不同 serverId**。
        // 原先后两条共用同一个 id 又都叫 refund，按第六轮统一后的计数口径
        // （以逻辑工具身份为单位）它们其实是同一个工具的两份非法配置，只该算 1 个 ——
        // 用两个 id 才能真的测到「两个用不了的工具」。
        val id = "66666666-6666-6666-6666-666666666666"
        val idCn = "66666667-6666-6666-6666-666666666667"
        val idDash = "66666668-6666-6666-6666-666666666668"
        val decision = decide(
            listOf(
                mcpEntry(id, "orders", "lookup"),
                mcpEntry(idCn, "中文名服务器", "refund"),
                mcpEntry(idDash, "has-dash", "drop"),
            ),
        )
        assertEquals("非法名的两条不许挂上来", listOf("lookup"), decision.kept.map { it.tool.name })
        assertEquals("非法名数量必须如实计入", 2, decision.invalidNameCount)
        val backend = source(backendPath)
        assertFalse(
            "子代理不许照抄主对话那种抛异常做法",
            backend.contains("throw InvalidMcpServerNamesException"),
        )
        // 字符集规则必须与主对话同一份，不许两边各写一套
        assertTrue(
            "子代理那份规则必须与主对话 ChatToolFactory 字面一致",
            backend.contains("name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }") &&
                source(chatToolFactoryPath)
                    .contains("name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }"),
        )
    }

    // ---- 说明书：能力必须写出来，风险也必须写出来 ----

    @Test
    fun `装配了 MCP 时说明书必须讲清数量与风险边界`() {
        val names = listOf("mcp__orders__lookup", "mcp__orders__refund")
        val prompt = buildAgentSystemPrompt(thread(), mcp = capabilityOf(names))
        assertTrue("必须点名 MCP", prompt.contains("MCP"))
        assertTrue("标题里必须报出实际可用数量", prompt.contains("可用 ${names.size} 个"))
        assertTrue("正文里也必须报出同一个数量", prompt.contains("挂了 ${names.size} 个 MCP 工具"))
        assertTrue(
            "必须说明这些工具会真实调用外部服务",
            prompt.contains("真实调用外部服务"),
        )
        assertTrue(
            "必须说明 MCP 绕过了本地那层限制 —— 子代理原本以为自己也受它约束",
            prompt.contains("不受上面「不能执行命令、不能编译、不能删除」那层限制约束"),
        )
        assertTrue("必须要求拿不准时交回主代理", prompt.contains("交回主代理"))
        assertTrue("必须要求报告里写清调用过什么", prompt.contains("报告里必须写清楚"))
    }

    @Test
    fun `没装配 MCP 时说明书一个字都不提 MCP`() {
        val prompt = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList()))
        assertFalse(prompt.contains("MCP 外部工具"))
        assertFalse(prompt.contains("真实调用外部服务"))
    }

    @Test
    fun `说明书数量必须来自实际装配结果，不许自己判断开关`() {
        val backend = source(backendPath)
        // v1027 复审后整条链只传一个能力对象：说明书的名字与各类跳过数量
        // （计数项目前有五类：审批 / 非法服务器名 / 名字超长 / 重复 serverId / 防御性重名丢弃）
        // 全部由那个对象派生，不存在「第二处计算」的地方。
        assertTrue(
            "说明书用的名字必须取自能力对象，不许另算",
            backend.contains("val mcpToolNames = mcp.toolNames"),
        )
        assertTrue(
            "闸门必须是「有工具 或 有任何一种跳过」，全空才整段不提 MCP",
            backend.contains("if (mcpToolNames.isNotEmpty() || mcp.skippedTotal > 0) {"),
        )
        assertTrue(
            "三个跳过数量必须由决策对象派生，说明书拿不到第二手的数字",
            backend.contains("val approvalSkippedCount: Int get() = decision.needsApprovalCount") &&
                backend.contains("val invalidNameSkippedCount: Int get() = decision.invalidNameCount") &&
                backend.contains("val nameOverflowCount: Int get() = decision.nameOverflowCount"),
        )
        assertFalse(
            "🔴 散装参数必须已经消失 —— 上一版就是靠四个函数六个调用点手传三个数，" +
            "红验证证明「只改说明书侧」测试照样绿，串参数也真发生过一次",
            Regex("""mcp(ToolNames|ApprovalSkippedCount|InvalidNameSkippedCount)\s*:\s*""").containsMatchIn(backend),
        )
        assertEquals(
            "两处生成路径（messages 里的 system 与隔离助手副本）都必须带上同一个对象",
            2, Regex("""mcp = mcpCapability,""").findAll(backend).count(),
        )
        assertFalse(
            "说明书里不许再读一次 allowAgentMcp —— 重复判断就是下一次分叉的起点",
            Regex("""mcpToolNames[\s\S]{0,200}settings\.allowAgentMcp""").containsMatchIn(
                backend.substringAfter("internal fun buildAgentSystemPrompt"),
            ),
        )
    }
    @Test
    fun `默认参数必须是空列表，否则等于偷偷给所有子代理放 MCP 说明`() {
        assertTrue(
            buildAgentSystemPrompt(thread()) ==
                buildAgentSystemPrompt(thread(), false, emptyList(), capabilityOf(emptyList())),
        )
    }

    // ---- 本轮两路审查各抓到一条真问题（第五轮）----

    @Test
    fun `撞名的 MCP 工具必须各自拿到唯一名字`() {
        // 服务器名不保证唯一（SettingMcpDetailPage 只校验字符集、不查重），而执行层是
        // tools.find { it.name == toolName }（GenerationLoop:741）只取第一个匹配 ——
        // 两台同名服务器提供同名工具时，想调 A 的结果会落到 B 上执行，对外部数据是真实误操作。
        val idA = "11111111-1111-1111-1111-111111111111"
        val idB = "22222222-2222-2222-2222-222222222222"
        val decision = decide(
            listOf(
                mcpEntry(idA, "orders", "refund"),
                mcpEntry(idB, "orders", "refund"),
                mcpEntry(idA, "orders", "lookup"),
            ),
        )
        val names = decision.kept.map { it.displayName }
        assertEquals("三条都要留下", 3, names.size)
        assertEquals("名字必须互不相同", names.size, names.distinct().size)
        // 而且必须**各自指向自己的服务器** —— 名字唯一只是手段，路由正确才是目的
        val byName = decision.kept.associateBy { it.displayName }
        assertEquals(Uuid.parse(idA), byName.values.toList().first { it.tool.name == "lookup" }.serverId)
        assertTrue(
            "撞名的两条都要带上服务器 id 片段，不能偏向先出现的那条",
            names.filter { it != "mcp__orders__lookup" }.let { pair ->
                pair[0].contains(idA.replace("-", "").take(8)) &&
                    pair[1].contains(idB.replace("-", "").take(8))
            },
        )
        assertTrue(
            "没撞名的那条保持干净可读，不许为了极少见的冲突把所有人都搞长",
            names.contains("mcp__orders__lookup"),
        )
    }

    @Test
    fun `只有一个服务器时名字保持干净`() {
        val names = keptNames(
            listOf(mcpEntry("33333333-3333-3333-3333-333333333333", "orders", "lookup")),
        )
        assertEquals(listOf("mcp__orders__lookup"), names)
    }

    @Test
    fun `服务器 id 前 8 位相同时也必须拿到不同名字`() {
        // 🔴 复审抓出来的真问题：上一版只取 id 前 8 位，而 UUID 前 8 位相同是真实存在的
        //（批量生成、导入的配置都会）。原先的测试样例用 1111…/2222…，前缀天生不同，
        // 等于从没覆盖过这条路；而红验证注入「写死前 8 位」时，因为还有序号兜底
        // 让名单两两不同，测试照样全绿 —— 双重摆设。现在要求前缀自动加长到真正够用。
        val idA = "deadbeef-0000-0000-0000-000000000001"
        val idB = "deadbeef-0000-0000-0000-000000000002"
        val decision = decide(
            listOf(mcpEntry(idA, "orders", "refund"), mcpEntry(idB, "orders", "refund")),
        )
        val names = decision.kept.map { it.displayName }
        assertEquals("两条都要留下", 2, decision.kept.size)
        assertEquals("前 8 位相同就必须自动加长", names.size, names.distinct().size)
        // 名字要能定位到具体服务器：拿 id 的**尾段**去查，不许用无关序号糊过去
        val byName = decision.kept.associateBy { it.displayName }
        decision.kept.forEach { kept ->
            val tail = kept.serverId.toString().replace("-", "").takeLast(4)
            assertTrue(
                "名字 ${kept.displayName} 里必须带着能定位服务器的 id 片段",
                kept.displayName.contains(tail),
            )
        }
        assertEquals(2, byName.size)
        assertEquals(
            "两条各自路由到自己的服务器，绝不允许都指向同一台",
            setOf(Uuid.parse(idA), Uuid.parse(idB)),
            decision.kept.map { it.serverId }.toSet(),
        )
    }

    @Test
    fun `同一台服务器里两个同名工具只留一条，不许编号骗人`() {
        // 复审指出上一版的 `_2` 序号兜底是骗人的：名字确实两两不同了，
        // 可那个尾巴跟服务器身份毫无关系，模型看到 mcp__orders__refund_2
        // 完全不知道自己调的是哪一台。而这两条本来就是**同一个 serverId + 同一个工具名**，
        // 指向同一个外部目标，留一条就够了 —— 不需要编个假身份挂在那里。
        val same = "44444444-4444-4444-4444-444444444444"
        val decision = decide(
            listOf(mcpEntry(same, "orders", "refund"), mcpEntry(same, "orders", "refund")),
        )
        assertEquals("同服务器同工具名就是同一个工具，只留一条", 1, decision.kept.size)
        assertEquals(
            "不许出现跟服务器身份无关的序号尾巴",
            listOf("mcp__orders__refund"), decision.kept.map { it.displayName },
        )
    }

    @Test
    fun `撞名处理后的名字仍然只含合法字符并且不超长度上限`() {
        // 🔴 复审第二条必修：全项目没有任何地方限制工具名长度，名字是原样发给供应商的
        //（ChatCompletionsAPI 里 put("name", tool.name)）。OpenAI/Claude 上限 64、Gemini 63，
        // 一旦越线上游会把**整个请求**拒掉，用户看到的是「莫名其妙发不出去」，
        // 而不是「某个工具用不了」。加区分码时必须守住这条线。
        val names = keptNames(
            listOf(
                mcpEntry("deadbeef-1111-2222-3333-444455556666", "orders", "refund"),
                mcpEntry("deadbeef-1111-2222-3333-444455557777", "orders", "refund"),
            ),
        )
        names.forEach {
            assertTrue("名字含非法字符：$it", it.all { ch -> ch.isLetterOrDigit() || ch == '_' })
            assertTrue("名字超过供应商上限（${it.length} > $MAX_MCP_TOOL_NAME_LENGTH）：$it",
                it.length <= MAX_MCP_TOOL_NAME_LENGTH)
        }
    }

    @Test
    fun `加完区分码会超出名字上限时整组不挂并计入数量`() {
        // 🔴 复审必修：全项目没有任何地方限制工具名长度，名字原样发给供应商
        //（ChatCompletionsAPI 里 put("name", tool.name)）。OpenAI/Claude 上限 64、Gemini 63。
        // 一旦越线，上游拒的是**整个请求**，用户看到「莫名其妙发不出去」而不是「某个工具用不了」。
        // 所以这里宁可整组不挂：既不能发超长名字，也不能偏向先出现的那条打到错误服务器。
        // 构造必须按上限算清楚，不能靠口算（上一版我把前提写错了：
        // 基础名字自己就 75 字符，那测的是「基础名字超长」，不是「加区分码才超长」）。
        // 名字结构：mcp__(5) + 服务器名 + _(1) + 区分码(w) + __(2) + 工具名
        val longServer = "orderserver" + "x".repeat(9)   // 20 字符，全合法字符
        val longTool = "tool" + "x".repeat(32)           // 36 字符
        val idA = "88888888-8888-8888-8888-888888888801"
        val idB = "88888888-8888-8888-8888-888888888802"
        val baseLength = 5 + longServer.length + 2 + longTool.length   // 不加区分码
        // 区分码至少要加 2 个字符（一个下划线 + 至少 1 位 id）。
        // 故意不去引用实现里的 private 阶梯常量 —— 那是实现细节，
        // 测试只要建立在「任何可辨识的区分码都放不下」这个事实上就够了。
        val shortestSuffixedLength = baseLength + 2
        assertTrue(
            "前提一：不加区分码时必须放得下（实际 $baseLength，上限 $MAX_MCP_TOOL_NAME_LENGTH）",
            baseLength <= MAX_MCP_TOOL_NAME_LENGTH,
        )
        assertTrue(
            "前提二：连最短的区分码都放不下（实际 $shortestSuffixedLength > $MAX_MCP_TOOL_NAME_LENGTH）",
            shortestSuffixedLength > MAX_MCP_TOOL_NAME_LENGTH,
        )

        val decision = decide(
            listOf(
                mcpEntry(idA, longServer, longTool),
                mcpEntry(idB, longServer, longTool),
            ),
        )
        assertEquals("加区分码必超长 → 两条都不许挂", 0, decision.kept.size)
        assertEquals("丢弃数量必须如实计入，说明书才讲得出原因", 2, decision.nameOverflowCount)
    }

    @Test
    fun `工具名含非法字符时必须清洗，但执行目标必须是原始工具名`() {
        // 🔴 联网核实到的第二半问题：我只卡了长度，没管字符集。
        // OpenAI/Claude 只接受 ^[a-zA-Z0-9_-]{1,64}$，Gemini 要求字母或下划线开头。
        // 而 MCP 工具名完全由外部服务器决定，公开案例里 `codesys.project.open`
        // 这种带点号的名字就被 Claude 直接 400，拒的是整个请求。
        val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val decision = decide(listOf(mcpEntry(id, "codesys", "project.open")))
        assertEquals("清洗后仍要挂上", 1, decision.kept.size)
        val kept = decision.kept.first()
        assertTrue(
            "给模型看的名字必须只含合法字符，实际：${kept.displayName}",
            kept.displayName.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' },
        )
        // 清洗只改名字，不改执行目标 —— 路由必须还打到原始工具名
        assertEquals("执行用的原始工具名不许被清洗掉", "project.open", kept.tool.name)
        assertEquals("执行用的 serverId 不许变", Uuid.parse(id), kept.serverId)
    }

    @Test
    fun `同一身份一条要批准一条不要时，整条都不许挂上`() {
        // 🔴 第五轮审查抓到的真漏洞：过滤与去重顺序反了。
        // 同一 (serverId, 工具名) 存在两条记录（一条 needsApproval=true、一条 false）时，
        // 上一版先滤掉 true 那条、再按身份去重 → 保留了 false 那条 →
        // **子代理以无审批状态执行了本该用户批准的工具**，等于替用户放权。
        val id = "12341234-1234-1234-1234-123412341234"
        val decision = decide(
            listOf(
                mcpEntry(id, "orders", "refund", needsApproval = true),
                mcpEntry(id, "orders", "refund", needsApproval = false),
                mcpEntry(id, "orders", "lookup", needsApproval = false),
            ),
        )
        assertEquals("要批准的同身份整条不挂，只留 lookup", listOf("lookup"), decision.kept.map { it.tool.name })
        // 🔴 第七轮口径统一（第六轮审查点名）：计数以**逻辑工具身份**为单位，
        // 不是以配置记录条数为单位。refund 这个工具用不了就是 1 个，
        // 哪怕它背后有两份配置记录 —— 说明书讲的是「N 个工具」，量纲必须对得上。
        assertEquals("按工具身份计数，refund 只算 1 个用不了的工具", 1, decision.needsApprovalCount)
        assertTrue(
            "绝不允许把需要批准的同名工具当成不需要批准",
            decision.kept.none { it.tool.name == "refund" },
        )
    }

    @Test
    fun `防御性去重函数本身必须有行为测试`() {
        // 这段分支在 claim 正确时不可达，所以必须单独测；
        // 否则它就是一段永远没有证据的代码（前几轮「假守卫」教训）。
        val (out, dropped) = dropDuplicateMcpNames(listOf("a", "b", "a", null, "b"))
        assertEquals(listOf("a", "b", null, null, null), out)
        assertEquals("丢掉两条重复", 2, dropped)
        val (clean, none) = dropDuplicateMcpNames(listOf("x", "y"))
        assertEquals(listOf("x", "y"), clean)
        assertEquals(0, none)

        // 这个分支在 claim 正确时走不到，所以装配接线必须另外钉住：
        // 否则「算出来了但没交给决策对象」这种丢失没有任何证据能发现。
        val backend = source(backendPath)
        assertTrue(
            "装配必须真的调用防御性去重",
            backend.contains("val (finalPlanned, conflictDropped) = dropDuplicateMcpNames(planned.toList())"),
        )
        assertTrue(
            "去重丢弃数必须单独进决策，不许混进超长计数",
            backend.contains("conflictDroppedCount = conflictDropped,"),
        )
    }

    @Test
    fun `重复丢弃的原因必须单独讲，不许说成名字超长`() {
        val onlyConflict = buildAgentSystemPrompt(
            thread(), mcp = capabilityOf(emptyList(), conflict = 2),
        )
        assertTrue("必须说明是撞成同一个名字", onlyConflict.contains("撞成同一个名字"))
        assertFalse("不许把它说成超长", onlyConflict.contains("超过供应商函数名长度上限"))
        val withTools = buildAgentSystemPrompt(
            thread(),
            mcp = capabilityOf(listOf("mcp__orders__lookup"), conflict = 1),
        )
        assertTrue("部分挂上时也要交代", withTools.contains("1 个与其他工具撞成同一个名字"))
    }

    @Test
    fun `清洗撞名再叠上更长的真实名字也不许撞车、更不许抛异常`() {
        // 🔴 第五轮双审共同抓到的真缺陷：get.file / get_file / get_file_2 三件套。
        // 前两个清洗后同名 → 第二个编号成 _2 → 正好等于第三个工具的本名 →
        // 上一版的 check() 直接抛异常，整趟子代理装配失败（不是少一个工具，是全炸）。
        val id = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        val decision = decide(
            listOf(
                mcpEntry(id, "files", "get.file"),
                mcpEntry(id, "files", "get_file"),
                mcpEntry(id, "files", "get_file_2"),
            ),
        )
        assertEquals("三条都必须挂上", 3, decision.kept.size)
        val names = decision.kept.map { it.displayName }
        assertEquals("名字必须两两不同（这一条就是上一版会炸的输入）", 3, names.distinct().size)
        assertEquals(
            "三条的原始工具名必须各自保留，执行不会串",
            setOf("get.file", "get_file", "get_file_2"),
            decision.kept.map { it.tool.name }.toSet(),
        )
        names.forEach {
            assertTrue("名字超上限：$it", it.length <= MAX_MCP_TOOL_NAME_LENGTH)
            assertTrue("名字含非法字符：$it", it.all { c -> c.isLetterOrDigit() || c == '_' })
        }
    }

    @Test
    fun `单条工具被清洗过也必须带身份说明`() {
        // 没撞名、只是被清洗（project.open → project_open）时，
        // 上一版不写身份说明，模型看到 project_open 根本不知道对应哪个原始工具。
        val id = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        val kept = decide(listOf(mcpEntry(id, "codesys", "project.open"))).kept.single()
        assertEquals("清洗后的名字", "mcp__codesys__project_open", kept.displayName)
        assertNotNull("清洗过的必须带身份说明", kept.identityNote)
        assertTrue("必须写出原始工具名", kept.identityNote!!.contains("project.open"))
        assertTrue(
            "描述里也要能看见原始名",
            agentMcpToolDescription(kept).contains("project.open"),
        )
    }

    @Test
    fun `身份说明必须使用实际采用的区分码而不是固定前8位`() {
        // 两个服务器 id 前 8 位相同、只有尾段不同时，名字会自动加长区分；
        // 但上一版身份说明固定写前 8 位 —— 两条说明会一模一样，等于没说。
        val idA = "ffffffff-0000-0000-0000-000000000001"
        val idB = "ffffffff-0000-0000-0000-000000000002"
        val decision = decide(
            listOf(mcpEntry(idA, "orders", "refund"), mcpEntry(idB, "orders", "refund")),
        )
        assertEquals(2, decision.kept.size)
        val notes = decision.kept.map { it.identityNote }
        assertEquals("两条身份说明必须不同", 2, notes.distinct().size)
        notes.forEach {
            assertNotNull(it)
            assertTrue("说明里必须能定位到具体服务器：$it", it!!.contains("id "))
        }
    }

    @Test
    fun `身份说明必须真的进到模型看得见的描述里`() {
        // 名字被清洗/编号之后，模型唯一的分辨依据就是 description。
        // 身份说明只存在决策对象里等于没送出去 —— 这一步此前只有主模型看代码确认。
        val id = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val decision = decide(
            listOf(mcpEntry(id, "files", "get.file"), mcpEntry(id, "files", "get_file")),
        )
        assertEquals(2, decision.kept.size)
        decision.kept.forEach { kept ->
            val desc = agentMcpToolDescription(kept)
            assertTrue(
                "描述里必须写明原始工具名，实际：$desc",
                desc.contains(kept.tool.name),
            )
            assertTrue("描述里必须写明服务器名", desc.contains("files"))
        }
        // 没被清洗、没撞名的普通工具，描述必须保持原样 —— 不许把身份说明到处乱塞
        val plain = decide(listOf(mcpEntry(id, "orders", "lookup"))).kept.single()
        assertEquals("无身份说明时描述原样", "", agentMcpToolDescription(plain))
        assertNull("干净的名字不该带身份说明", plain.identityNote)

        // 🔴 光测这个纯函数本身不算数：本轮红验证把装配点改成「只取原始描述」之后，
        // 这条测试照样全绿 —— 因为测试绕过了装配点。必须同时钉住装配点真的在用它。
        val backend = source(backendPath)
        assertTrue(
            "装配点必须真的调用描述函数，否则身份说明永远进不到模型眼前",
            backend.contains("description = agentMcpToolDescription(kept),"),
        )
        assertFalse(
            "装配点不许退回只取原始描述",
            backend.contains("description = kept.tool.description ?: \"\","),
        )
    }

    @Test
    fun `同一台服务器上清洗后撞名的两个工具都要挂上且不许抛异常`() {
        // 真实形状：同一台服务器同时提供 get.file 与 get_file。
        // 清洗后两者变成同一个片段，而服务器 id 区分码在这里帮不上忙 ——
        // 上一版的 check() 会直接抛异常，整趟子代理炸掉且用户看不见原因。
        val id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val decision = decide(
            listOf(mcpEntry(id, "files", "get.file"), mcpEntry(id, "files", "get_file")),
        )
        assertEquals("两条都必须挂上，一条都不许静默丢", 2, decision.kept.size)
        val names = decision.kept.map { it.displayName }
        assertEquals("名字必须两两不同", names.size, names.distinct().size)
        assertEquals("原始工具名各自保留，执行不会串", setOf("get.file", "get_file"),
            decision.kept.map { it.tool.name }.toSet())
        // 名字被编号区分时，必须另有一处写清实际调用的是哪个原始工具
        decision.kept.forEach { kept ->
            assertNotNull("清洗撞名的条目必须带身份说明", kept.identityNote)
            assertTrue(
                "身份说明必须写明原始工具名（实际：${kept.identityNote}）",
                kept.identityNote!!.contains(kept.tool.name),
            )
        }
    }

    @Test
    fun `不撞名但工具名过长时同样必须不挂并计入数量`() {
        // 🔴 第四轮复审抓到的旁路：上一版只在「撞名分支」里查长度，
        // 未撞名的那条直接放行基础名。审查位给的复现很具体：
        // 服务器名 orders、工具名 51 字符 → 基础名 64 字符 > 上限 63，
        // 照样能发出去 —— 后果与撞名超长一模一样（整个请求被供应商拒）。
        // 现在长度判断只有一个闸门，用边界对照把它钉死。
        val id = "99999999-9999-9999-9999-999999999999"
        // 基础名结构：mcp__(5) + 服务器名 + __(2) + 工具名
        // orders = 6 → 工具名 50 字符时基础名正好 63（放得下），51 字符时 64（放不下）
        val fits = "t".repeat(MAX_MCP_TOOL_NAME_LENGTH - 5 - "orders".length - 2)
        val over = fits + "x"
        // 边界必须实测，不许靠注释里的口算（上一版就是这么把 7 算错的）
        assertEquals(
            "对照必须卡在边界：fits 拼出来正好等于上限",
            MAX_MCP_TOOL_NAME_LENGTH, "mcp__orders__${fits}".length,
        )
        assertEquals(
            "对照必须卡在边界：over 拼出来正好比上限多 1",
            MAX_MCP_TOOL_NAME_LENGTH + 1, "mcp__orders__${over}".length,
        )
        val decision = decide(listOf(mcpEntry(id, "orders", fits), mcpEntry(id, "orders", over)))
        assertEquals("卡在上限内的必须保留", listOf(fits), decision.kept.map { it.tool.name })
        assertEquals("超出一个字符的必须丢弃", 1, decision.nameOverflowCount)
        assertTrue(
            "保留下来的名字都不许超上限",
            decision.kept.all { it.displayName.length <= MAX_MCP_TOOL_NAME_LENGTH },
        )
    }

    @Test
    fun `说明书不许把超长只说成是重名造成的`() {
        // 超长有两种成因：工具名自己就长（不撞名）、或两台服务器重名加区分码顶爆。
        // 上一版措辞只讲第二种，复审指出这会让第一种成因说法失真。
        val onlyOverflow = buildAgentSystemPrompt(
            thread(), mcp = capabilityOf(emptyList(), overflow = 2),
        )
        assertTrue("必须提到工具名本身太长这一成因", onlyOverflow.contains("工具名本身太长"))
        assertTrue("必须提到重名加区分码这一成因", onlyOverflow.contains("两台服务器"))
        assertTrue("必须讲清硬发的后果", onlyOverflow.contains("整个请求被供应商拒"))
        assertFalse("不许谎称已经挂上", onlyOverflow.contains("本次还给你挂了"))
    }

    @Test
    fun `说明书不许谎称非法名与主对话同样只是排除`() {
        // 第四轮复审抓到的措辞谎：说明书原本写「与主对话一样被排除在外」，
        // 而 ChatToolFactory 对非法服务器名是 throw InvalidMcpServerNamesException（直接报错），
        // 不是排除。让模型以为两边一样，它就不会把「主对话其实早就报错了」这件事回报上来。
        val prompt = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList(), invalidName = 2))
        assertFalse(
            "不许说成与主对话一样排除",
            prompt.contains("与主对话一样被排除"),
        )
        assertTrue("必须说清主对话是直接报错", prompt.contains("主对话碰到这种名字会直接报错"))
        assertTrue("必须说清子代理改成跳过是为了不炸掉整趟", prompt.contains("不炸掉整趟任务"))
    }

    @Test
    fun `设置文案必须列全三类用不上的原因`() {
        // 用户明确选了「不新增限制」，所以我们替他做的每一次放弃都必须在界面上写清楚，
        // 不能只写在给 AI 的说明书里 —— 那等于用户在不知情时少了一项能力。
        val zh = source("app/src/main/res/values-zh/strings.xml")
        val line = zh.substringAfter("<string name=\"agent_tools_mcp_desc\">")
            .substringBefore("</string>")
        assertTrue("必须说清有四类常见原因（少一类就是隐瞒）",
            line.contains("有四类常见原因会让工具即使开着也用不上"))
        assertTrue("措辞必须写「常见」：第五类（防御性重名丢弃）正常不可达，界面上不列它，" +
                    "但也不能把话说成「只有这些」",
            line.contains("四类常见原因"))
        assertTrue("结尾必须留口子给第四类原因",
            line.contains("这几种情况子代理都会在报告里说明原因"))
        assertTrue("① 需要批准", line.contains("需要批准"))
        assertTrue("② 服务器名不合法", line.contains("服务器名含中文或符号"))
        assertTrue("③ 名字超过长度上限", line.contains("超过供应商长度上限"))
        assertTrue("④ 同一台服务器多份相同 id 的配置（第八轮入口 5）",
            line.contains("同一台服务器在设置里存在多份相同 id 的配置"))
        assertTrue("④ 必须讲清后果是可能打错服务器",
            line.contains("把外部操作发往你没看到的另一台"))
        // 主对话对非法名是抛异常报错，不许在文案里说成「一样跳过」
        assertTrue("必须区分主对话报错与子代理跳过", line.contains("主对话碰到会直接报错"))
    }

    @Test
    fun `说明书必须把名字超长这第四种原因也讲出来`() {
        // 四种「开关开着却没挂上」的原因，说明书必须按实际发生的那几种讲，少讲一种就是骗人。
        val onlyOverflow = buildAgentSystemPrompt(
            thread(), mcp = capabilityOf(emptyList(), overflow = 2),
        )
        assertTrue("必须说明开关是开着的", onlyOverflow.contains("MCP 开关是开着的"))
        // 措辞已从「只说重名」改成「两种成因都讲」，断言跟着改成钉住新措辞
        assertTrue("必须报出超长的数量", onlyOverflow.contains("2 个的名字超过供应商函数名长度上限"))
        assertTrue("必须讲清上限数字", onlyOverflow.contains("${MAX_MCP_TOOL_NAME_LENGTH} 字符"))
        assertTrue("必须说清硬发的后果", onlyOverflow.contains("整个请求被供应商拒"))
        assertFalse("没挂上不许宣称挂了工具", onlyOverflow.contains("本次还给你挂了"))

        val withTools = buildAgentSystemPrompt(
            thread(),
            mcp = capabilityOf(listOf("mcp__orders__lookup"), overflow = 3),
        )
        assertTrue("部分挂上也要补充交代超长那几条", withTools.contains("3 个名字超过供应商函数名长度上限"))
    }

    @Test
    fun `需要用户批准的 MCP 工具必须被跳过，而且不许把审批标志改成自动通过`() {
        // 主对话的审批依赖用户点「批准」的界面；子代理链路既没有批准入口，
        // 也没有审批完成后的恢复路径。真挂上去，GenerationLoop 会把工具置成 Pending
        // 然后 break 等用户（GenerationLoop:659、688-691），子代理永远等不到 ——
        // 报告不完整又被判成「截断」，于是触发自动续跑，什么也没做成还白烧额度。
        //
        // 🔴 复审指出原先这条只读源码字面量：把过滤删掉、只留计数，测试照样绿。
        // 现在真的喂一批工具进决策函数看结果。
        val decision = decide(
            listOf(
                mcpEntry("55555555-5555-5555-5555-555555555555", "orders", "lookup"),
                mcpEntry("55555555-5555-5555-5555-555555555555", "orders", "refund", needsApproval = true),
                mcpEntry("55555555-5555-5555-5555-555555555555", "orders", "drop", needsApproval = true),
            ),
        )
        assertEquals("只有不需要批准的那条能挂上", listOf("lookup"), decision.kept.map { it.tool.name })
        assertEquals("被跳过的两条必须如实计数", 2, decision.needsApprovalCount)
        // 刻意**不**改成 needsApproval = { false }：那是用户自己设的保护，
        // 改掉等于替用户自动批准外部操作，属于偷偷放权，比卡住更糟。
        val backend = source(backendPath)
        assertFalse(
            "绝不允许把 needsApproval 写死成 false（那等于绕过用户的审批保护）",
            Regex("""needsApproval\s*=\s*\{\s*false\s*\}""").containsMatchIn(backend),
        )
        assertTrue(
            "装配处必须原样传递审批标志，这样即使过滤逻辑以后被改动，也不会悄悄变成全部自动批准",
            backend.contains("needsApproval = { kept.tool.needsApproval },"),
        )
    }

    @Test
    fun `说明书必须如实交代被跳过的工具数量`() {
        // 一个都没挂上时也要说：不说的话模型只看到「没有 MCP 工具」，
        // 会以为自己压根没这能力，或者凭空编一个工具名去调。
        val noneUsable = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList(), approval = 3))
        assertTrue("必须说明开关是开着的", noneUsable.contains("MCP 开关是开着的"))
        assertTrue("必须报出被跳过的数量", noneUsable.contains("3 个要求用户手动批准"))
        assertTrue("必须明确本次手里没有 MCP 工具", noneUsable.contains("所以本次你手里没有 MCP 工具"))
        assertTrue("必须要求交回主代理", noneUsable.contains("交回主代理处理"))
        assertFalse(
            "没有可用工具时不许宣称挂了工具",
            noneUsable.contains("本次还给你挂了"),
        )

        val partial = buildAgentSystemPrompt(
            thread(),
            mcp = capabilityOf(listOf("mcp__orders__lookup"), approval = 2),
        )
        assertTrue("有可用工具时照常说明", partial.contains("本次还给你挂了 1 个 MCP 工具"))
        // v1027 复审后文案改成「按原因拼接」，所以断言跟着改成新措辞（不是迁就实现，
        // 而是文案本身变了；理由：非法服务器名也要能同样讲出来，两类原因得共用一套拼接）
        assertTrue("部分被跳过也必须补充说明", partial.contains("另有 2 个要求用户手动批准"))
        assertTrue("补充说明必须交代清楚为什么没挂上", partial.contains("的 MCP 工具没有挂上来"))
    }

    @Test
    fun `服务器名不合法被丢掉时说明书也必须讲清楚原因`() {
        // 复审第三处发现：非法名的工具在装配阶段就被 filter 掉，原本说明书一个字不提，
        // 用户把开关打开却「什么都没发生」，模型也只会以为自己压根没这能力 ——
        // 与 v281「能力不写进说明书等于白开」、v324「说明书与工具各判各的」是同一类错。
        val prompt = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList(), invalidName = 4))
        assertTrue("必须说明开关是开着的", prompt.contains("MCP 开关是开着的"))
        assertTrue("必须报出被丢掉的数量", prompt.contains("4 个所在的 MCP 服务器名称不合法"))
        assertTrue("必须讲清不合法的判据", prompt.contains("只允许英文字母和数字"))
        assertTrue("必须明确本次手里没有 MCP 工具", prompt.contains("所以本次你手里没有 MCP 工具"))
        assertFalse("没挂上就不许宣称挂了工具", prompt.contains("本次还给你挂了"))

        // 两类原因同时存在时，两条都得讲，不许只讲一半
        val both = buildAgentSystemPrompt(
            thread(),
            mcp = capabilityOf(
                listOf("mcp__orders__lookup"), approval = 2, invalidName = 3,
            ),
        )
        assertTrue("待批准那条要讲", both.contains("2 个要求用户手动批准"))
        assertTrue("非法名那条也要讲", both.contains("3 个所在的 MCP 服务器名称不合法"))
    }

    @Test
    fun `各类跳过数量必须由同一份决策同时供给装配与说明书`() {
        // 🔴 本轮红验证抓到两条摆设：
        //   ① 把装配返回值里的计数拿掉，说明书侧的测试照样全绿（因为测试直接调说明书，绕过了装配）；
        //   ② 把 available 的过滤删掉、只留计数，也照样全绿。
        // 上一版的修法是补更多字面量断言 —— 那还是同一种病。
        // 现在两件事都由**同一个决策对象**供给：装配用 decision.kept，说明书用 decision 的计数，
        // 结构上不可能一个说有一个说无。这里既验行为（真调用），也验接线（装配真用它）。
        val id = "77777777-7777-7777-7777-777777777777"
        val entries = listOf(
            mcpEntry(id, "orders", "lookup"),
            mcpEntry(id, "orders", "refund", needsApproval = true),
            mcpEntry(id, "坏名字", "drop"),
        )
        val decision = decide(entries)
        // 必须用「与真实装配同形态」的构造器：把 decision 整个交给能力对象
        val capability = capabilityOf(decision)
        // 行为：能力对象报出来的数字，必须与决策完全一致
        // 先把决策本身钉死，再比一致性 —— 否则「一致」可能是 0 与 0 一致，空断言
        assertEquals("三条里只有一条能挂上", 1, decision.kept.size)
        assertEquals("待批准应有 1 条", 1, decision.needsApprovalCount)
        assertEquals("非法名应有 1 条", 1, decision.invalidNameCount)
        assertEquals("本例不该有超长丢弃", 0, decision.nameOverflowCount)
        assertEquals("待批准数一致", decision.needsApprovalCount, capability.approvalSkippedCount)
        assertEquals("非法名数一致", decision.invalidNameCount, capability.invalidNameSkippedCount)
        assertEquals("超长数一致", decision.nameOverflowCount, capability.nameOverflowCount)
        assertEquals("可用数就是 kept 的大小", decision.kept.size, capability.toolNames.size)
        assertEquals("跳过总数是四类原因之和（本例只有审批与非法名）", 2, capability.skippedTotal)

        // 接线：装配必须真的把那份 decision 交给能力对象，不许只交工具列表
        val backend = source(backendPath)
        assertTrue(
            "装配必须先调用纯决策函数",
            // 🔴 第七轮双审抓到的越权入口 4 就藏在这一行背后：原先喂的是 getAllAvailableTools，
            // 它在枚举阶段就把 enable=false 的记录滤掉，决策函数收到的输入天生缺一条。
            // 这句断言如果还钉着旧写法，就等于把那条漏洞锁进测试里当"正确行为"。
            // 🔴 第九轮又进一步：服务器清单必须与助手勾选取自**同一份 settings 快照**，
            // 所以这里钉的是 agentMcpToolRecords(settings.mcpServers, assistant.mcpServers)。
            backend.contains("val records = agentMcpToolRecords(settings.mcpServers, assistant.mcpServers)") &&
                backend.contains("val decision = decideAgentMcpTools(records.entries, records.ambiguousServerCount)"),

        )
        assertFalse(
            "不许退回主对话那条枚举路径（它会过滤 tool.enable）",
            backend.contains("decideAgentMcpTools(mcpManager.getAllAvailableTools(assistant)")
                // 只交记录、把台数丢掉同样算退回入口 4/5（丢了计数，说明书就讲不出原因）
                || backend.contains("decideAgentMcpTools(records.entries)")
                || backend.contains("decideAgentMcpTools(records.entries, 0)"),
        )
        assertTrue(
            "能力对象必须带上那份决策，否则说明书只能重新判断",
            backend.contains("return AgentMcpCapability(tools = tools, decision = decision)"),
        )
        assertTrue(
            "工具必须由决策留下的条目生成，名字用 displayName",
            backend.contains("val tools = decision.kept.map { kept ->"),
        )
    }
    @Test
    fun `默认关或未开启时非法名计数也不许让说明书提到 MCP`() {
        // 用户要求「关掉时与 v1026 一字节不差」：三个计数全零时必须整段不提 MCP
        val none = buildAgentSystemPrompt(thread())
        assertFalse(none.contains("MCP 外部工具"))
        assertTrue(
            "非法名计数默认必须是零",
            buildAgentSystemPrompt(thread()) ==
                buildAgentSystemPrompt(thread(), false, emptyList(), capabilityOf(emptyList())),
        )
    }

    @Test
    fun `既没工具也没跳过时说明书一个字都不提 MCP`() {
        val prompt = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList()))
        assertFalse(prompt.contains("MCP 外部工具"))
        assertFalse(prompt.contains("真实调用外部服务"))
    }

    @Test
    fun `被跳过数量默认必须是零`() {
        // 三类计数的默认值必须是零，否则「关掉开关时与 v1026 一字节不差」这条就破了：
        // 任何一个非零默认都会让说明书凭空多出一段 MCP 说明。
        val zero = AgentMcpDecision()
        assertEquals("待批准计数默认必须是零", 0, zero.needsApprovalCount)
        assertEquals("非法服务器名计数默认必须是零", 0, zero.invalidNameCount)
        assertEquals("名字超长计数默认必须是零", 0, zero.nameOverflowCount)
        assertEquals("默认跳过总数必须是零", 0, capabilityOf(emptyList()).skippedTotal)
        assertTrue(
            "默认形态与显式传空能力对象必须产出同一份说明书",
            buildAgentSystemPrompt(thread()) ==
                buildAgentSystemPrompt(thread(), false, emptyList(), capabilityOf(emptyList())),
        )
    }

    // ---- 装配接线与红线 ----

    @Test
    fun `装配必须接收外部算好的 MCP 列表，且不按角色过滤`() {
        val backend = source(backendPath)
        assertTrue(
            "buildAgentTools 必须接收 mcpTools 参数",
            backend.contains("mcpTools: List<Tool>,"),
        )
        assertTrue(
            "装配处只按列表非空判断，不许再叠一层角色白名单（用户明确选了「一个开关管所有角色」）",
            backend.contains("if (mcpTools.isNotEmpty()) {"),
        )
        assertFalse(
            "MCP 装配不许按角色过滤",
            Regex("""role == AgentRole\.\w+\) \{\n\s*addAll\(mcpTools""").containsMatchIn(backend),
        )
    }

    @Test
    fun `红线：隔离助手副本的 mcpServers 必须仍是 emptySet`() {
        // 那条「副本清空污染源」的设计不动。MCP 走的是独立装配路径，
        // 关掉开关时子代理行为必须与 v1026 一字节不差。
        val isolated = buildIsolatedAssistant(Assistant(mcpServers = setOf(serverA)), thread())
        assertTrue("副本必须仍然清空 MCP", isolated.mcpServers.isEmpty())
        assertTrue(
            "源码里那句 emptySet 不许被改成读真实开关",
            source(backendPath).contains("mcpServers = emptySet(),"),
        )
    }

    @Test
    fun `主对话与圆桌不许受这个开关影响`() {
        // 用户要的是「给子代理加一个选项」，不是改主对话。
        assertFalse(
            "主对话工具工厂不许读 allowAgentMcp",
            source(chatToolFactoryPath).contains("allowAgentMcp"),
        )
        val backend = source(backendPath)
        // 🔴 上一版从 `internal fun agentMcpAssistantFor` 之后开始数，漏掉了 run() 里
        // 更早的那处 `settings.allowAgentMcp`（第五轮审查指出）。改成扫描全文并精确白名单：
        // 只允许两处 —— ① agentMcpAssistantFor 里的失败关闭判断；② run() 里决定要不要查会话。
        val sites = Regex("""settings\.allowAgentMcp""").findAll(backend).toList()
        assertEquals(
            "开关只允许出现在这两个判定点（多一处就是又加了判断点）",
            2, sites.size,
        )
        assertTrue(
            "必须有一处是 run() 里的会话查询条件",
            backend.contains("thread.role == AgentRole.EXPLORER || settings.allowAgentMcp"),
        )
        assertTrue(
            "必须有一处是 agentMcpAssistantFor 的失败关闭",
            backend.contains("if (!settings.allowAgentMcp) return null"),
        )
    }

    @Test
    fun `联网与MCP共用一趟会话查询，不许各读一次库`() {
        val backend = source(backendPath)
        assertTrue(
            "run() 必须只查一次会话再分发给两边",
            backend.contains("val dispatchConversation = if (needsConversation) threadConversation(thread) else null"),
        )
        assertEquals(
            "threadConversation(thread) 在 run() 里只许出现一次",
            1,
            Regex("threadConversation\\(thread\\)").findAll(
                // 必须用 override suspend fun run( 定位：早先用 "private suspend fun run"
                // 会匹配到 runWithModel 的前缀，区间切错、计数变 0 —— 是断言自己写错了。
                backend.substringAfter("override suspend fun run(")
                    .substringBefore("private fun agentWebSearchEnabled"),
            ).count(),
        )
        assertTrue(
            "非探测位且 MCP 关着时不许读会话库（v1026 抓过的开销回归，别改回去）",
            backend.contains("thread.role == AgentRole.EXPLORER || settings.allowAgentMcp"),
        )
    }

    // ---- 🔴 第七轮返工：第六轮双审点名的越权入口、缺失行为证据与计数口径 ----

    @Test
    fun `非法服务器名那条要批准时，不能借合法名的重复配置绕过审批`() {
        // 第六轮**两路审查独立**抓到同一条越权路径：非法服务器名的过滤排在身份归并**之前**。
        // 同一个 serverId + 同一个工具名有两份配置记录时 ——
        //   记录一：服务器名非法（中文），needsApproval=true  ← 用户自己设的保护
        //   记录二：服务器名合法，needsApproval=false
        // 上一版先把记录一滤掉，归并时只剩记录二，于是这个「要求用户批准」的工具
        // 被挂上并以**无审批状态执行**，等于替用户放权。
        // 第五轮已经为「审批过滤排在归并前」栽过一次，这是同一类病的第二个入口。
        val id = "8b00e1f2-0000-4000-8000-000000000001"
        val decision = decide(
            listOf(
                mcpEntry(id, "中文服务器", "refund", needsApproval = true),
                mcpEntry(id, "orders", "refund", needsApproval = false),
            ),
        )
        assertEquals("这个身份一条都不许挂", emptyList<String>(), decision.kept.map { it.tool.name })
        assertTrue(
            "绝不允许「需要批准的工具」以无审批状态挂上",
            decision.kept.none { it.tool.name == "refund" },
        )
        assertEquals("按身份计，因审批被跳过 1 个", 1, decision.needsApprovalCount)
        // 原因互斥：这个身份已经被「要求批准」吸收了，再计一次非法名就是重复报告，
        // 说明书会出现「同一个工具被算进两种原因」。
        assertEquals("不许把同一身份重复计入非法名", 0, decision.invalidNameCount)
    }

    @Test
    fun `enable=false 的记录既不挂载也不计入任何跳过原因`() {
        // 第六轮审查指出：mcpEntry 一直有 enable 参数，但从来没有被喂进决策函数 ——
        // 「禁用工具不进子代理」这条只有源码证据、没有行为证据，是典型的假守卫。
        // 用户禁用的工具不该挂上，更不该被算成「因为审批/非法名/超长而用不了」，
        // 否则说明书会把用户自己的禁用说成软件的缺陷。
        val idA = "8b00e1f2-0000-4000-8000-000000000002"
        val idB = "8b00e1f2-0000-4000-8000-000000000003"
        val idC = "8b00e1f2-0000-4000-8000-000000000004"
        val decision = decide(
            listOf(
                mcpEntry(idA, "orders", "lookup", enable = false),
                mcpEntry(idB, "orders", "refund", needsApproval = true, enable = false),
                mcpEntry(idC, "中文名服务器", "drop", enable = false),
            ),
        )
        assertEquals("禁用的一条都不许挂", 0, decision.kept.size)
        assertEquals("禁用不算审批跳过", 0, decision.needsApprovalCount)
        assertEquals("禁用不算非法名跳过", 0, decision.invalidNameCount)
        assertEquals("禁用不算超长", 0, decision.nameOverflowCount)
        assertEquals("禁用不算重名丢弃", 0, decision.conflictDroppedCount)
    }

    @Test
    fun `同一身份既有非法名又有合法名时取合法名那条且不计数`() {
        // 新口径的正向半边：名字合法性是「选代表」的条件，不是「先删记录」。
        // 同一个工具确实存在一份名字合法的配置时，工具要正常挂上，
        // 而且不能被算成「因为服务器名不合法而用不了」。
        val id = "8b00e1f2-0000-4000-8000-000000000005"
        val decision = decide(
            listOf(
                mcpEntry(id, "中文服务器", "lookup"),
                mcpEntry(id, "orders", "lookup"),
            ),
        )
        assertEquals("合法名那条被选为代表并挂上", listOf("lookup"), decision.kept.map { it.tool.name })
        assertEquals(
            "给模型看的名字必须来自合法名那条，不是中文那条",
            "mcp__orders__lookup",
            decision.kept.first().displayName,
        )
        assertEquals("该身份其实可用，非法名计数必须是 0", 0, decision.invalidNameCount)
    }

    @Test
    fun `同一身份的两条非法配置只算一个用不了的工具`() {
        // 把统一的计数口径钉死：四个计数（审批/非法名/超长/重名丢弃）全部以
        // 逻辑工具身份为单位。超长与冲突本来就是这个量纲，原先审批与非法名
        // 按配置记录数计 —— 同一份数据两种口径，说明书那句「N 个工具」必然误报。
        val id = "8b00e1f2-0000-4000-8000-000000000006"
        val decision = decide(
            listOf(
                mcpEntry(id, "中文名服务器", "refund"),
                mcpEntry(id, "has-dash", "refund"),
            ),
        )
        assertEquals("一个工具都挂不上", 0, decision.kept.size)
        assertEquals("同一个工具的两份非法配置只算 1 个", 1, decision.invalidNameCount)
    }

    @Test
    fun `被禁用那份配置要求批准时，不能借启用那份不要求的记录绕过审批`() {
        // 🔴 第七轮自查出的**第三个同类入口**（前两路由审查位分别抓过）。
        // 上一版把 `filter { tool.enable }` 排在身份归并之前，于是同一身份
        //   记录一：禁用 + needsApproval=true   ← 用户设的保护，只是这份配置没启用
        //   记录二：启用 + needsApproval=false
        // 记录一被当成「禁用」扔掉，归并时只剩记录二 → 这个要求批准的工具照样挂上，
        // 并以无审批状态执行。行为模型推演确认确实越权。
        // 现在的规矩是「先归并，再在身份内部逐条问」，审批问的是**全部配置**。
        val id = "8b00e1f2-0000-4000-8000-000000000007"
        val decision = decide(
            listOf(
                mcpEntry(id, "orders", "refund", needsApproval = true, enable = false),
                mcpEntry(id, "orders", "refund", needsApproval = false, enable = true),
            ),
        )
        assertEquals("这个身份一条都不许挂", emptyList<String>(), decision.kept.map { it.tool.name })
        assertTrue(
            "绝不允许「任何一份配置要求批准」的工具挂上去",
            decision.kept.none { it.tool.name == "refund" },
        )
        assertEquals("按身份计，因审批被跳过 1 个", 1, decision.needsApprovalCount)
        assertEquals("原因已被审批吸收，不许再计进非法名", 0, decision.invalidNameCount)
    }

    @Test
    fun `三份配置混合时审批仍压过禁用与名字合法性`() {
        // 把判定顺序钉死：a 全部禁用→不算原因；b 任一配置要求批准→整条不挂；
        // c 才轮到名字合法性。顺序反了就会「禁用记录洗掉审批要求」或「审批原因被报成非法名」。
        val id = "8b00e1f2-0000-4000-8000-000000000008"
        val decision = decide(
            listOf(
                mcpEntry(id, "中文服务器", "refund", needsApproval = true, enable = true),
                mcpEntry(id, "orders", "refund", needsApproval = false, enable = false),
                mcpEntry(id, "orders", "refund", needsApproval = false, enable = true),
            ),
        )
        assertEquals("三份配置里任一份要求批准，整条身份都不挂", 0, decision.kept.size)
        assertEquals("只计一个工具身份，原因归审批", 1, decision.needsApprovalCount)
        assertEquals("同一个身份不许既报审批又报非法名", 0, decision.invalidNameCount)
    }

    // ---- 🔴 第七轮补审：越权入口 4 的「真实枚举链路」证据 ----
    //
    // 上一轮所有 MCP 用例都直接喂 decideAgentMcpTools，于是「McpManager 在枚举阶段
    // 就把 enable=false 的记录滤掉」这件事没有任何测试能看见：纯函数拿到完整输入时
    // 表现完美，生产链路却永远送不完整输入。这是假守卫最隐蔽的一种 ——
    // 测试没写错，只是测的那一层根本不是出问题的那一层。
    // 下面的用例从真实的配置对象出发，一路走到决策结果。

    private fun serverWith(
        id: Uuid,
        name: String,
        enableServer: Boolean = true,
        url: String = "https://x.example/sse",
        tools: List<McpTool>,
    ) = McpServerConfig.SseTransportServer(
        id = id,
        commonOptions = McpCommonOptions(enable = enableServer, name = name, tools = tools),
        url = url,
    )

    private fun toolOf(name: String, approval: Boolean = false, enable: Boolean = true) =
        McpTool(name = name, needsApproval = approval, enable = enable)

    @Test
    fun `枚举必须把禁用记录一起交给决策函数，否则审批保护是空话`() {
        val tools = listOf(
            toolOf("refund", approval = true, enable = false),
            toolOf("refund", approval = false, enable = true),
        )
        val records = agentMcpToolRecords(
            listOf(serverWith(serverA, "orders", tools = tools)), setOf(serverA),
        )
        assertEquals("禁用的那份必须仍在枚举结果里 —— 它带着用户的审批要求", 2, records.entries.size)
        val decision = decideAgentMcpTools(records.entries, records.ambiguousServerCount)
        assertEquals("任一份配置要求批准，整个身份都不许挂", 0, decision.kept.size)
        assertEquals("按身份计，一个工具因审批被跳过", 1, decision.needsApprovalCount)
    }

    @Test
    fun `枚举保留服务器禁用与未勾选这两道真实边界`() {
        val tools = listOf(toolOf("lookup"))
        val servers = listOf(
            serverWith(serverA, "a-on", tools = tools),
            serverWith(serverB, "b-off", enableServer = false, tools = tools),
        )
        assertEquals(
            "只勾 A：只有 A 的记录，B 服务器自身已禁用",
            listOf(serverA to "a-on"),
            agentMcpToolRecords(servers, setOf(serverA)).entries.map { it.first to it.second },
        )
        assertEquals("一台都没勾 → 一条都不给", 0, agentMcpToolRecords(servers, emptySet()).entries.size)
        assertEquals(
            "勾了但服务器自身禁用 → 仍然不给（不能为了保留禁用记录就把这道边界放宽）",
            0, agentMcpToolRecords(servers, setOf(serverB)).entries.size,
        )
    }

    @Test
    fun `接线：子代理不许退回主对话那条会过滤禁用的枚举`() {
        val backend = source(backendPath)
        assertTrue(
            "装配必须走子代理专用枚举",
            backend.contains("val records = agentMcpToolRecords(settings.mcpServers, assistant.mcpServers)") &&
                backend.contains("decideAgentMcpTools(records.entries, records.ambiguousServerCount)"),
        )
        assertTrue(
            "🔴 服务器清单与勾选必须来自**同一份** settings 快照（第九轮抓的漏洞）",
            backend.contains("agentMcpToolRecords(settings.mcpServers, assistant.mcpServers)"),
        )
        // 🔴 第十一轮审查：这里原是 assertFalse(backend.contains("settingsStore.settingsFlow.value.mcpServers"))，
        // 第十轮把执行期取数拆成两步（先 val live = ...，再 servers = live.mcpServers）之后，
        // 那串整字面量永远不出现 —— 于是成了一句永远为真的断言，什么都没守住。
        // 真实意图有两面，必须分开钉：
        //   装配期：服务器表与勾选来自**同一份** settings 快照，不许自己再去读 store；
        //   执行期：必须读**实时** store，否则「执行前复查」就是拿旧结论执行新状态。
        val assemble = backend.substringAfter("private fun agentMcpTools(")
            .substringBefore("val tools = decision.kept.map")
        // 用计数而不是 assertFalse(contains)：后者会让「执行期必须出现同一串字面量」这条
        // 被静态体检当成反向守卫失效（实际是两段不同的代码，必须分开看）。
        assertEquals(
            "装配期不许绕过快照去直接读 store 拿服务器表",
            0,
            Regex("settingsStore\\.settingsFlow\\.value").findAll(assemble).count(),
        )
        val guarded = backend.substringAfter("val tools = decision.kept.map")
            .substringBefore("return AgentMcpCapability(")
        assertTrue(
            "执行期必须读实时设置，否则「执行前复查」名不副实",
            guarded.contains("val live = settingsStore.settingsFlow.value"),
        )
        // 主对话那条必须原样保留：那边「工具被禁用就不该出现」才是正确语义。
        val manager = source(mcpManagerPath)
        val available = manager.substringAfter("fun getAllAvailableTools(")
            .substringBefore("suspend fun callTool(")
        assertTrue(
            "主对话那条枚举的 tool.enable 过滤不许被删（删了等于改主对话行为）",
            available.contains(".filter { tool -> tool.enable }"),
        )
    }

    @Test
    fun `子代理那条枚举体内绝不允许出现工具级 enable 过滤`() {
        // 🔴 第八轮审查点名：上一版只截「成员方法之后」的文本，而 P10 破法注入的是
        // **顶层那个纯枚举函数**（它在成员方法之前），切片范围根本不相交 ——
        // 于是这条测试对 P10 完全是摆设。现在两处都查。
        val manager = source(mcpManagerPath)
        // 顶层函数以列 0 的 `}` 收尾，这个边界比「找下一个声明」可靠得多
        val topEnum = manager.substringAfter("internal fun agentMcpToolRecords(")
            .substringBefore("\n}\n")
        assertFalse(
            "顶层枚举不许过滤 tool.enable（入口 4 的真正落点）",
            topEnum.contains(".filter { tool -> tool.enable }"),
        )
        assertTrue(
            "切片必须真的切到了枚举主体，否则上面那句是空断言",
            topEnum.contains("commonOptions.tools.map") && topEnum.contains("selectedServerIds"),
        )
        // 🔴 第九轮审查抓到的快照不一致（已修）：原先存在一个自读 settingsStore 的
        // 子代理枚举成员方法，而助手勾选来自 run() 开头那份快照 —— 两半不同时刻。
        // 这里反向钉住它不许回来，否则「已撤销勾选的服务器仍被挂上」会重新出现。
        assertFalse(
            "不许再有自读 store 的子代理枚举成员方法",
            manager.contains("fun getAllToolRecordsForAgent") ||
                Regex("""fun\s+\w*[Aa]gent\w*\([^)]*\)[^{=\n]*settingsFlow""").containsMatchIn(manager),
        )
        assertTrue(
            "枚举只能是那个只吃入参的顶层纯函数，判断点只留一处",
            manager.contains("internal fun agentMcpToolRecords("),
        )
        val top = manager.substringAfter("internal fun agentMcpToolRecords(")
            .substringBefore("): AgentMcpToolRecords {")
        assertTrue("顶层枚举必须只吃服务器清单", top.contains("servers: List<McpServerConfig>"))
        assertTrue("以及助手/会话勾选的 id 集合", top.contains("selectedServerIds: Set<Uuid>"))
        assertFalse(
            "而且不许偷偷再读别的状态（读了就变成第二处判断点）",
            top.contains("settingsStore") || top.contains("Assistant"),
        )
    }

    @Test
    fun `真实枚举与纯决策串起来时，越权组合一条都挂不上`() {
        // 三个入口一次性串测：审批维度、非法名维度、禁用维度，全部从配置对象出发。
        val servers = listOf(
            serverWith(
                serverA, "orders", tools = listOf(
                    toolOf("refund", approval = true, enable = false),   // 入口 3/4
                    toolOf("refund", approval = false, enable = true),
                    toolOf("lookup", approval = true, enable = true),     // 入口 1
                    toolOf("drop", approval = false, enable = true),      // 正常可用
                ),
            ),
            serverWith(
                // 入口 2：非法服务器名 + 审批
                serverB, "中文服务器", tools = listOf(
                    toolOf("audit", approval = true, enable = true),
                    toolOf("audit", approval = false, enable = true),
                ),
            ),
            serverWith(
                // 第八轮补：服务器自身被禁用。这台整台不该出现在枚举结果里，
                // 少了这个样本，「删掉服务器级 enable 检查」这种破法就测不出来。
                serverC, "off-server", enableServer = false,
                tools = listOf(toolOf("shutdown", approval = false, enable = true)),
            ),
        )
        val recs = agentMcpToolRecords(servers, setOf(serverA, serverB, serverC))
        val decision = decideAgentMcpTools(recs.entries, recs.ambiguousServerCount)
        assertTrue("被禁用的那台服务器一条记录都不许交出去", recs.entries.none { it.first == serverC })
        assertEquals("只有谁都不要求批准的 drop 能挂上", listOf("drop"), decision.kept.map { it.tool.name })
        // refund（禁用那份要求批准）、lookup（本身要求批准）、audit（中文名那份要求批准）
        // 三个身份都因审批不挂。audit 同时还有「服务器名不合法」的问题，但原因必须互斥 ——
        // 被审批吸收过就不许再计进非法名，否则同一个工具会在说明书里出现两次。
        assertEquals("三个身份都因审批不挂（含被禁用与非法名那两条）", 3, decision.needsApprovalCount)
        assertEquals("audit 已被审批吸收，不许重复计入非法名原因", 0, decision.invalidNameCount)
        assertTrue(
            "任何要求批准的工具都不许出现在手里",
            decision.kept.none { it.tool.name in setOf("refund", "lookup", "audit") },
        )
    }

    // ---- 🔴 第八轮补审：越权入口 5（同一 serverId 出现多份配置） ----
    //
    // 危害与前四次不同：那四次是「该问用户的没问」，这一次是「可能打到另一台服务器」。
    // 连接层 McpSessionRegistry.reconcile 用 associateBy { it.id } 建会话表，
    // 重复 key 后者覆盖前者；执行又是 sessions[serverId] 按 id 取会话。
    // 而决策函数取代表用的是列表里**第一份**符合条件的记录 ——
    // 于是模型看到的服务器名与参数 schema 来自第一份，实际调用打到最后一份。
    // 唯一安全做法是失败关闭：宁可一个工具都不给，也不能猜哪份才是用户要的。

    @Test
    fun `同一服务器 id 有多份配置时整台不挂并计台数`() {
        val dup = listOf(
            serverWith(serverA, "orders", url = "https://first.example/sse",
                tools = listOf(toolOf("lookup"))),
            serverWith(serverA, "orders-副本", url = "https://second.example/sse",
                tools = listOf(toolOf("pay"))),
        )
        val recs = agentMcpToolRecords(dup, setOf(serverA))
        assertEquals("重复 id 的服务器一条记录都不许交出去", 0, recs.entries.size)
        assertEquals("被整台放弃的服务器数必须带出来，说明书才讲得出原因", 1, recs.ambiguousServerCount)
        val decision = decideAgentMcpTools(recs.entries, recs.ambiguousServerCount)
        assertEquals("决策结果里不许出现这台服务器的工具", 0, decision.kept.size)
        assertEquals("台数必须进决策对象", 1, decision.ambiguousServerCount)
        assertNotEquals(
            "两份配置必须真的不同（含连接地址），否则测的是最无害的那种重复",
            dup[0], dup[1],
        )
    }

    @Test
    fun `重复配置只放弃那一台，不牵连其它正常服务器`() {
        val servers = listOf(
            serverWith(serverA, "dup-1", url = "https://a1.example/sse", tools = listOf(toolOf("lookup"))),
            serverWith(serverA, "dup-2", url = "https://a2.example/sse", tools = listOf(toolOf("pay"))),
            serverWith(serverB, "clean", url = "https://b.example/sse", tools = listOf(toolOf("read"))),
        )
        val recs = agentMcpToolRecords(servers, setOf(serverA, serverB))
        assertEquals("只有干净的 B 还能交出去", listOf("read"), recs.entries.map { it.third.name })
        assertEquals("只计 A 这一台", 1, recs.ambiguousServerCount)
    }

    @Test
    fun `第五类原因必须进 skippedTotal，否则说明书会一个字不提`() {
        // 说明书只在 toolNames 非空 或 skippedTotal>0 时才提 MCP。
        // 如果 ambiguousServerCount 没算进 skippedTotal，那么「只因为有重复配置
        // 而全军覆没」这种情况会被当成「什么都没发生」静默掉 —— 用户开了开关却
        // 看不到任何解释。这条断言把这个耦合钉住，而不是只测说明书文本。
        val cap = capabilityOf(emptyList(), ambiguous = 3)
        assertEquals("重复配置必须计入跳过总数", 3, cap.skippedTotal)
        val prompt = buildAgentSystemPrompt(thread(), mcp = cap)
        assertTrue("所以说明书必须开口提 MCP", prompt.contains("MCP 开关是开着的"))
    }

    @Test
    fun `说明书必须把重复配置这第五类原因单独讲出来`() {
        val onlyAmbiguous = buildAgentSystemPrompt(thread(), mcp = capabilityOf(emptyList(), ambiguous = 2))
        assertTrue("必须报出台数", onlyAmbiguous.contains("2 台 MCP 服务器在设置里存在多份相同 id 的配置"))
        assertTrue("必须讲清后果是可能打错服务器",
            onlyAmbiguous.contains("可能把外部操作发往你没看到的那台服务器"))
        assertTrue("必须给出处置办法", onlyAmbiguous.contains("请把重复的那一份配置删掉再试"))
        assertTrue("量纲必须是「台」而不是「个工具」", onlyAmbiguous.contains("2 台 MCP 服务器"))

        val partial = buildAgentSystemPrompt(
            thread(), mcp = capabilityOf(listOf("mcp__orders__lookup"), ambiguous = 1),
        )
        assertTrue("部分挂上时也必须提这一类", partial.contains("1 台服务器有多份相同 id 的重复配置"))
    }

    // ---- 🔴 第八轮补审：Tool 闭包层的行为证据（此前只有源码字面量） ----

    @Test
    fun `装配闭包必须用原始 serverId 与原始工具名，绝不从显示名反推`() {
        val id = "8b00e1f2-0000-4000-8000-000000000010"
        val schema = InputSchema.Obj(buildJsonObject {
            put("orderId", buildJsonObject { put("type", "string") })
        })
        val tool = McpTool(
            name = "pay.order", needsApproval = true, enable = true,
            description = "给订单退款", inputSchema = schema,
        )
        val kept = AgentMcpKeptTool(
            serverId = Uuid.parse(id),
            serverName = "orders",
            tool = tool,
            displayName = "mcp__orders__pay_order_2",      // 被清洗并加了区分码之后的名字
            identityNote = "实际调用服务器 orders 的工具 pay.order",
        )
        var seen: Pair<Uuid, String>? = null
        val built = buildAgentMcpTool(kept) { sid, name, _ ->
            seen = sid to name
            emptyList()
        }

        assertEquals("给模型看的名字用 displayName", "mcp__orders__pay_order_2", built.name)
        assertSame("parameters 必须返回代表记录的 inputSchema", schema, built.parameters())
        assertTrue("needsApproval 必须如实传递，不许写死 false",
            built.needsApproval(buildJsonObject {}))
        assertTrue("名字被改过，描述里必须带身份说明",
            built.description.contains("实际调用服务器 orders 的工具 pay.order"))

        runBlocking { built.execute(buildJsonObject { put("orderId", "o-1") }) }
        assertEquals("执行必须打原始 serverId", Uuid.parse(id), seen!!.first)
        assertEquals(
            "执行必须打**原始**工具名，不能拿清洗过的显示名去反推",
            "pay.order", seen!!.second,
        )
        assertNotEquals(
            "显示名与真实工具名此时必须确实不同，否则这条测试什么都没钉住",
            kept.displayName, kept.tool.name,
        )
    }

    // ---- 🔴 第八轮补审：越权入口 6（判重写在服务器级 enable 过滤之后） ----
    //
    // 这是同一个洞的第六次现身，而且就藏在我为入口 5 写的代码里。
    // 原顺序：先按「服务器 enable + 勾选」过滤，再在剩下的里面判重复 id。
    // 于是「同 id 两份配置，一份整台停用且其工具 needsApproval=true、
    // 另一份启用且同名工具 needsApproval=false」时，停用那份在判重之前就消失了：
    // 入口 5 的检测看不到重复，入口 4 的审批保护也看不到那条要求批准的记录，
    // 结果与入口 4 完全同构 —— 审批要求被洗掉，工具以无审批状态执行。
    // 死规矩：**判重与归并必须在任何过滤之前**。

    @Test
    fun `同 id 两份配置一份整台停用时也必须整台放弃而不是只留下启用那份`() {
        val servers = listOf(
            serverWith(serverA, "orders", enableServer = false, url = "https://a1.example/sse",
                tools = listOf(toolOf("refund", approval = true))),
            serverWith(serverA, "orders", enableServer = true, url = "https://a2.example/sse",
                tools = listOf(toolOf("refund", approval = false))),
        )
        val recs = agentMcpToolRecords(servers, setOf(serverA))
        assertEquals("停用那份带着审批要求，判重必须在过滤之前就看到它", 0, recs.entries.size)
        assertEquals("必须计为一台歧义服务器", 1, recs.ambiguousServerCount)
        val decision = decideAgentMcpTools(recs.entries, recs.ambiguousServerCount)
        assertEquals("这台服务器一个工具都不许挂上", 0, decision.kept.size)
    }

    @Test
    fun `同 id 内容完全相同也整台放弃，不许靠「其实一样」放行`() {
        val one = serverWith(serverA, "orders", tools = listOf(toolOf("lookup")))
        val recs = agentMcpToolRecords(listOf(one, one), setOf(serverA))
        assertEquals("内容一致也不放行：连接层只认一份，没人能保证两份永远同步", 0, recs.entries.size)
        assertEquals(1, recs.ambiguousServerCount)
    }


    // ---- 🔴 第九轮补审：勾选相关性与计数口径的行为证据 ----

    @Test
    fun `未勾选的服务器就算有重复配置也不许计入歧义台数`() {
        // 剔除仍然针对全部重复 id（安全不打折），但计数只报与本次勾选有关的：
        // 否则一台用户压根没选的服务器会跑进说明书，说成「你的工具为什么用不了」。
        val dupUnselected = listOf(
            serverWith(serverC, "dup-x1", url = "https://c1.example/sse", tools = listOf(toolOf("thing"))),
            serverWith(serverC, "dup-x2", url = "https://c2.example/sse", tools = listOf(toolOf("thing"))),
            serverWith(serverA, "orders", tools = listOf(toolOf("lookup"))),
        )
        val recs = agentMcpToolRecords(dupUnselected, setOf(serverA))
        assertEquals("勾了的 A 正常交出", listOf("lookup"), recs.entries.map { it.third.name })
        assertEquals("没勾的 C 不许报进说明书", 0, recs.ambiguousServerCount)
        val both = agentMcpToolRecords(dupUnselected, setOf(serverA, serverC))
        assertEquals("一旦勾上 C，重复配置就必须报出来", 1, both.ambiguousServerCount)
        assertTrue("但仍然只交出 A 的工具，C 一条都不给", both.entries.none { it.first == serverC })
    }

    @Test
    fun `勾选集合里的服务器 id 已被删除时不得凭空多出工具`() {
        // 用户勾了一个设置里已经不存在的 id（删过服务器，或旧备份恢复过来的）。
        // 必须什么都拿不到，而且不许被算成「重复配置」—— 那是两种不同的原因。
        val gone = Uuid.random()
        val servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup"))))
        val recs = agentMcpToolRecords(servers, setOf(gone))
        assertEquals("已删除的 id 不该提供任何工具", 0, recs.entries.size)
        assertEquals("也不该被误报成重复配置", 0, recs.ambiguousServerCount)
        val mixed = agentMcpToolRecords(servers, setOf(gone, serverA))
        assertEquals("混着勾时仍只交出真实存在的那台", listOf("lookup"), mixed.entries.map { it.third.name })
    }

    @Test
    fun `闭包在不需要批准时也必须如实传false而不是写死true`() {
        // 上一版闭包测试只验了 needsApproval=true 那一侧；写死 true 同样错 ——
        // 那会把本来不需要批准的工具卡成永远等不到批准，子代理静默完不成任务。
        val id = "8b00e1f2-0000-4000-8000-000000000011"
        val kept = AgentMcpKeptTool(
            serverId = Uuid.parse(id),
            serverName = "orders",
            tool = McpTool(name = "read", needsApproval = false, enable = true),
            displayName = "mcp__orders__read",
        )
        val built = buildAgentMcpTool(kept) { _, _, _ -> emptyList() }
        assertFalse(
            "needsApproval=false 必须原样透传，不许写死 true",
            built.needsApproval(buildJsonObject {}),
        )
    }

    @Test
    fun `装配必须真的走失败关闭helper不许自己内联判断`() {
        // agentMcpAssistantFor 的三处失败关闭有行为测试，但「装配真的调用它」这一件事
        // 此前只有我在代码里看到。把它抽走、换成直接读当前助手，测试照样全绿。
        val backend = source(backendPath)
        assertTrue(
            "子代理 MCP 装配必须先过失败关闭",
            backend.contains(
                "val assistant = agentMcpAssistantFor(settings, conversation) ?: return AgentMcpCapability()",
            ),
        )
    }

    @Test
    fun `单份配置被服务器级禁用时正常排除，但不许被误报成重复配置`() {
        // 反向半边：新顺序把判重提到最前，绝不能顺手把「一份配置 + 被禁用」也算成歧义，
        // 否则说明书会把用户的正常停用说成配置错误。
        val servers = listOf(
            serverWith(serverA, "orders", enableServer = false, tools = listOf(toolOf("lookup"))),
            serverWith(serverB, "ok", tools = listOf(toolOf("read"))),
        )
        val recs = agentMcpToolRecords(servers, setOf(serverA, serverB))
        assertEquals("被禁用的那台不给工具", listOf("read"), recs.entries.map { it.third.name })
        assertEquals("但它不是重复配置，不许计进歧义台数", 0, recs.ambiguousServerCount)
    }

    // ============================================================
    // v1027 第十轮：执行前实时复查的**行为**证据
    //
    // 装配只证明「几分钟前那一刻」安全。执行时用户可能已经改了设置。
    // 下面这些用例不是「源码里有这句话所以通过」——它们注入一个会记账的 call，
    // 真的去捅那道门：情况变了必须拒绝，且 call 的调用次数必须是 0。
    // 生产装配路径调用的就是同一个 runAgentMcpToolGuarded，不是测试另写的仿制品。
    // ============================================================

    private data class GuardProbe(
        val text: String,
        val calls: Int,
        val seen: String,
        val seenArgs: String,
    )

    private fun probeGuarded(
        servers: List<McpServerConfig>,
        selected: Set<Uuid>?,
        serverId: Uuid = serverA,
        toolName: String = "lookup",
        allowed: Boolean = true,
    ): GuardProbe = runBlocking {
        var calls = 0
        var seen = ""
        var seenArgs = ""
        val out = runAgentMcpToolGuarded(
            servers = servers,
            serverId = serverId,
            toolName = toolName,
            args = buildJsonObject { put("orderId", "A-1") },
            liveSelectedServerIds = selected,
            agentMcpAllowed = allowed,
            call = { sid, name, passedArgs ->
                calls += 1
                seen = "$sid|$name"
                seenArgs = passedArgs.toString()
                listOf(UIMessagePart.Text("EXECUTED"))
            },
        )
        GuardProbe(
            text = out.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
            calls = calls,
            seen = seen,
            seenArgs = seenArgs,
        )
    }

    @Test
    fun `执行前总开关被关掉时必须拒绝，一次外部调用都不许发生`() {
        // 🔴 第十一轮双审独立抓到的漏洞：用户运行中途撤回授权，上一版照样外发。
        // 服务器本身一切正常，只把总开关关掉 —— 必须拒绝。
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup")))),
            selected = setOf(serverA),
            allowed = false,
        )
        assertEquals("撤权后绝不许再打外部服务器", 0, p.calls)
        assertTrue("必须说清是总开关被关，而不是含糊失败", p.text.contains("总开关在执行前被关掉了"))
        assertTrue("必须让子代理回主对话报告，而不是自己重试", p.text.contains("uncertainties"))
        assertFalse("拒绝时不许伪装成执行成功", p.text.contains("EXECUTED"))
    }

    @Test
    fun `执行前服务器配置消失时必须拒绝，而且一次外部调用都不许发生`() {
        // 装配时 A 还在，执行前用户把它从设置里删了。
        val p = probeGuarded(servers = emptyList(), selected = setOf(serverA))
        assertEquals("删掉配置后绝不许再打外部服务器", 0, p.calls)
        assertTrue("必须给出可读原因，子代理才写得进报告", p.text.contains("配置已经从设置里消失了"))
        assertFalse("拒绝时不许伪装成执行成功", p.text.contains("EXECUTED"))
    }

    @Test
    fun `执行前多出同 id 第二份配置时必须拒绝，绝不许猜哪一台`() {
        val p = probeGuarded(
            servers = listOf(
                serverWith(serverA, "orders", url = "https://first.example/sse", tools = listOf(toolOf("lookup"))),
                serverWith(serverA, "orders-副本", url = "https://second.example/sse", tools = listOf(toolOf("lookup"))),
            ),
            selected = setOf(serverA),
        )
        assertEquals("连接层只认一份，猜错就是打错服务器", 0, p.calls)
        assertTrue("必须点名是重复配置，而不是含糊说失败", p.text.contains("2 份相同 id 的配置"))
    }

    @Test
    fun `执行前服务器被停用时必须拒绝`() {
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", enableServer = false, tools = listOf(toolOf("lookup")))),
            selected = setOf(serverA),
        )
        assertEquals(0, p.calls)
        assertTrue(p.text.contains("已被停用"))
    }

    @Test
    fun `执行前被取消勾选时必须拒绝`() {
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup")))),
            selected = setOf(serverB),
        )
        assertEquals("取消勾选后不许继续用旧勾选执行", 0, p.calls)
        assertTrue(p.text.contains("已经不在勾选列表里"))
    }

    @Test
    fun `执行前工具被改成需要批准时必须拒绝，绝不替用户放行`() {
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup", approval = true)))),
            selected = setOf(serverA),
        )
        assertEquals("需要批准就必须一次都不调用", 0, p.calls)
        assertTrue(p.text.contains("需要用户手动批准"))
        assertTrue("必须让子代理回主对话找人批，而不是自己想办法绕", p.text.contains("uncertainties"))
    }

    @Test
    fun `同名两条工具里有一条要批准时也必须拒绝，不许只看第一条就外发`() {
        // 🔴 第十一轮双审：执行期原来用 firstOrNull 只看同名第一条。
        // 关键是把「不要求批准」那条放在**第一条**：退回 firstOrNull 就会看到它 → 直接外发 → 红。
        // （反过来把要批准那条放第一条会「碰巧拒绝」，测不出问题，所以顺序不能随便换。）
        val p = probeGuarded(
            servers = listOf(
                serverWith(
                    serverA, "orders",
                    tools = listOf(
                        toolOf("lookup", approval = false, enable = true),
                        toolOf("lookup", approval = true, enable = true),
                    ),
                ),
            ),
            selected = setOf(serverA),
        )
        assertEquals("同名里只要有一条要批准，就一次都不许调", 0, p.calls)
        assertTrue("必须说清是要用户批准", p.text.contains("需要用户手动批准"))
    }

    @Test
    fun `同名两条工具全停用才报停用，有一条启用就必须放行`() {
        // 前半：两条都停用 → 拒绝，而且文案必须是「停用」不是「要审批」。
        val allOff = probeGuarded(
            servers = listOf(
                serverWith(
                    serverA, "orders",
                    tools = listOf(
                        toolOf("lookup", enable = false),
                        toolOf("lookup", enable = false),
                    ),
                ),
            ),
            selected = setOf(serverA),
        )
        assertEquals(0, allOff.calls)
        assertTrue("两条都停用必须报停用", allOff.text.contains("已被停用"))
        assertFalse("不许把它说成需要审批", allOff.text.contains("需要用户手动批准"))

        // 后半（反向对照）：第一条停用、第二条启用 → 必须放行。
        // 退回 firstOrNull 会看到第一条 enable=false 从而误拒 → 这条会红。
        val partlyOn = probeGuarded(
            servers = listOf(
                serverWith(
                    serverA, "orders",
                    tools = listOf(
                        toolOf("lookup", enable = false),
                        toolOf("lookup", enable = true),
                    ),
                ),
            ),
            selected = setOf(serverA),
        )
        assertEquals("还有一条启用就必须放行，不许误报成停用", 1, partlyOn.calls)
        assertEquals("EXECUTED", partlyOn.text)
    }

    @Test
    fun `执行前工具被删掉时必须拒绝，而不是拿着旧名字硬打`() {
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("other")))),
            selected = setOf(serverA),
        )
        assertEquals(0, p.calls)
        assertTrue(p.text.contains("已经找不到了"))
    }

    @Test
    fun `一切没变时必须恰好调用一次，并且服务器 id 与原始工具名原样传出`() {
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup")))),
            selected = setOf(serverA),
        )
        assertEquals("正常情况必须执行，不许把复查做成一律拒绝", 1, p.calls)
        assertEquals("执行目标必须是原始 id 与原始工具名", "$serverA|lookup", p.seen)
        assertEquals("EXECUTED", p.text)
        // 🔴 第十一轮审查：之前两处注入都把 args 丢掉（call = { sid, name, _ -> }），
        // 于是「参数原样传出」只是一句没人验证过的话。现在真的把它记下来断言。
        assertTrue(
            "参数必须原样传到外部调用，不许在守卫里被换掉或丢掉，实际=$p.seenArgs",
            p.seenArgs.contains("A-1"),
        )
    }

    @Test
    fun `勾选来自会话配置时跳过勾选维度，但不许因此误拒正常配置`() {
        // liveSelectedServerIds == null 表示「勾选固定在这个对话自己的 config 上」，
        // 执行期拿库里的助手勾选去硬比会误拒正常配置，所以这一维跳过。
        // 这条钉住「跳过 ≠ 一律拒绝」，也钉住 null 没有被当成空集处理。
        val p = probeGuarded(
            servers = listOf(serverWith(serverA, "orders", tools = listOf(toolOf("lookup")))),
            selected = null,
        )
        assertEquals("跳过勾选维度不等于拒绝正常配置", 1, p.calls)
        assertEquals("EXECUTED", p.text)
    }

    @Test
    fun `红线：装配处必须走复查函数，不许自己再内联一份判断`() {
        val backend = source(backendPath)
        assertTrue(
            "复查必须真的接在装配 lambda 上，否则生产路径上等于没有这道门",
            backend.contains("call = { guardedId, guardedTool, guardedArgs ->"),
        )
        assertTrue(
            "复查通过后必须转到 mcpManager.callTool，不许半路吞掉调用",
            backend.contains("mcpManager.callTool(guardedId, guardedTool, guardedArgs)"),
        )
        assertFalse(
            "装配处不许再内联一份自己的复查（两份判断迟早分叉）",
            backend.contains("agentMcpStalenessNote(live.mcpServers"),
        )
        // 🔴 第十一轮双审：上面三条只钉住「转交」，钉不住「喂进去的是不是实时数据」。
        // 把 servers 换成装配期的旧快照 settings.mcpServers、或把勾选维度写死 null，
        // 全部测试仍会绿 —— 那正是入口 7 时序版回归的原形。下面三条把那两根线钉死。
        assertTrue(
            "复查必须用**实时**服务器表，不许退回装配期快照",
            backend.contains("servers = live.mcpServers,"),
        )
        assertTrue(
            "复查必须用**实时**勾选集合（null 只能来自「勾选在会话 config 上」这一分支）",
            backend.contains("liveSelectedServerIds = liveSelected,"),
        )
        assertTrue(
            "总开关也必须复查，且必须取实时值（live.allowAgentMcp，不能取旧快照）",
            backend.contains("agentMcpAllowed = live.allowAgentMcp,"),
        )
        // 第十一轮双审 D：上面「开关名只许出现 2 处」那条旧守卫，语义已被本轮改变
        // （执行期多了一个判定点）。与其靠正则「恰好不匹配它」，不如把它显式登记成正向断言。
        assertEquals(
            "执行期判定总开关的地方必须恰好一处（多一处就是又加了判断点）",
            1,
            Regex("live\\.allowAgentMcp").findAll(backend).count(),
        )
        // 切片的两个锚点必须真的存在，否则 substringAfter/Before 会退化成整段，
        // 那条计数断言就会在语义漂移的情况下「碰巧通过」。
        assertTrue("切片起点锚点必须存在", backend.contains("private fun agentMcpTools("))
        assertTrue("切片终点锚点必须存在", backend.contains("val tools = decision.kept.map"))
        assertTrue("执行段锚点必须存在", backend.contains("return AgentMcpCapability("))
    }

}
