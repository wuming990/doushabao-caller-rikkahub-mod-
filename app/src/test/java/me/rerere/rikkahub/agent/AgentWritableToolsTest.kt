package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.runtime.AGENT_MAX_STEPS_READONLY
import me.rerere.rikkahub.agent.runtime.AGENT_MAX_STEPS_WRITABLE
import me.rerere.rikkahub.agent.runtime.buildAgentSystemPrompt
import me.rerere.rikkahub.agent.tools.countOccurrences
import me.rerere.rikkahub.agent.tools.createAgentWritableTools
import me.rerere.rikkahub.agent.tools.isWorkspacePathAllowed
import me.rerere.rikkahub.agent.tools.normalizeWorkspacePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * v236 门禁：编程位的写权限必须是**白名单制**，而且默认关着。
 *
 * 用户要的「一个负责编程的子代理」必须能真的改文件，否则这一环永远是空的。
 * 但放开写权限是本项目最敏感的一次改动，所以三条约束必须由测试锁死：
 *
 * 1. 没给白名单 = 一把写工具都不给（与 v218~v235 的纯只读行为完全一致）；
 * 2. 白名单之外的路径一律拒绝，`..` 目录穿越在归一化阶段就被打掉；
 * 3. 依然没有 shell、不能编译、不能删除或改名 —— 编译独占 Gradle，只能留在主模型手里。
 */
class AgentWritableToolsTest {

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

    private fun thread(
        role: AgentRole = AgentRole.PROGRAMMER,
        writablePaths: List<String> = emptyList(),
    ) = AgentThread(
        id = "t1",
        conversationId = "c1",
        task = "把 Foo.kt 里的拼写改对",
        role = role,
        workspaceId = "ws-1",
        writablePaths = writablePaths,
        createdAt = Instant.now(),
    )

    // ------------------------------------------------------------ 默认只读

    @Test
    fun `没有白名单时一把写工具都不给`() {
        assertTrue(createAgentWritableTools("ws-1", null, emptyList()).isEmpty())
        assertTrue(createAgentWritableTools("ws-1", null, listOf("", "  ")).isEmpty())
    }

    @Test
    fun `没有工作区时一把写工具都不给`() {
        assertTrue(createAgentWritableTools(null, null, listOf("app/src")).isEmpty())
        assertTrue(createAgentWritableTools("", null, listOf("app/src")).isEmpty())
    }

    @Test
    fun `线程默认不可写`() {
        assertFalse(thread().canWrite)
        assertTrue(thread(writablePaths = listOf("app/src/main/Foo.kt")).canWrite)
        assertFalse("全空白的白名单不算可写", thread(writablePaths = listOf("  ")).canWrite)
    }

    // ------------------------------------------------------------ 路径归一化

    @Test
    fun `路径归一化去掉工作区前缀与多余斜杠`() {
        assertEquals("app/src/Foo.kt", normalizeWorkspacePath("/workspace/app/src/Foo.kt"))
        assertEquals("app/src/Foo.kt", normalizeWorkspacePath("app/src/Foo.kt"))
        assertEquals("app/src/Foo.kt", normalizeWorkspacePath("//app//src//Foo.kt"))
        assertEquals("app/src/Foo.kt", normalizeWorkspacePath("app\\src\\Foo.kt"))
        assertEquals("app/src/Foo.kt", normalizeWorkspacePath("  ./app/./src/Foo.kt  "))
        assertEquals("", normalizeWorkspacePath(null))
        assertEquals("", normalizeWorkspacePath("   "))
    }

    @Test
    fun `目录穿越必须在归一化阶段就被打掉`() {
        assertEquals("etc/passwd", normalizeWorkspacePath("../../etc/passwd"))
        assertEquals("app/Foo.kt", normalizeWorkspacePath("app/src/../Foo.kt"))
        assertEquals("", normalizeWorkspacePath("../.."))
        // 归一化之后再比白名单，穿越写法拿不到白名单目录的授权
        assertFalse(
            isWorkspacePathAllowed(normalizeWorkspacePath("app/src/../../etc/passwd"), listOf("app/src"))
        )
    }

    // ------------------------------------------------------------ 白名单判定

    @Test
    fun `白名单支持精确文件与目录前缀`() {
        val allowed = listOf("app/src/main/Foo.kt", "app/src/test")
        assertTrue(isWorkspacePathAllowed("app/src/main/Foo.kt", allowed))
        assertTrue(isWorkspacePathAllowed("app/src/test/Bar.kt", allowed))
        assertTrue(isWorkspacePathAllowed("app/src/test/deep/Baz.kt", allowed))
    }

    @Test
    fun `白名单之外一律拒绝`() {
        val allowed = listOf("app/src/test")
        assertFalse(isWorkspacePathAllowed("app/src/main/Foo.kt", allowed))
        assertFalse(isWorkspacePathAllowed("build.gradle.kts", allowed))
        assertFalse("空路径不得放过", isWorkspacePathAllowed("", allowed))
        assertFalse("空白名单不得放过任何东西", isWorkspacePathAllowed("app/src/test/A.kt", emptyList()))
    }

    @Test
    fun `目录前缀不得意外放开同名前缀的兄弟文件`() {
        // 「app」不该顺带放开「application.kt」——必须按路径分段比，不能裸 startsWith
        val allowed = listOf("app")
        assertTrue(isWorkspacePathAllowed("app/src/Foo.kt", allowed))
        assertFalse(isWorkspacePathAllowed("application.kt", allowed))
        assertFalse(isWorkspacePathAllowed("apple/Foo.kt", allowed))
    }

    // ------------------------------------------------------------ 精确替换的计数

    @Test
    fun `出现次数统计不重叠且不死循环`() {
        assertEquals(0, countOccurrences("abc", "z"))
        assertEquals(1, countOccurrences("abc", "b"))
        assertEquals(3, countOccurrences("ababab", "ab"))
        assertEquals(1, countOccurrences("aaa", "aaa"))
        assertEquals("空串必须返回 0，不能死循环", 0, countOccurrences("aaa", ""))
    }

    // ------------------------------------------------------------ 提示词

    @Test
    fun `没有写权限时提示词仍然写明不能改文件`() {
        val prompt = buildAgentSystemPrompt(thread(role = AgentRole.EXPLORER))
        assertTrue(prompt.contains("不能修改任何文件"))
        assertFalse(prompt.contains("只能修改下面这些路径"))
    }

    @Test
    fun `有写权限时提示词必须逐条列出白名单并声明没有终端`() {
        val prompt = buildAgentSystemPrompt(
            thread(writablePaths = listOf("app/src/main/Foo.kt", "app/src/test"))
        )
        assertTrue(prompt.contains("只能修改下面这些路径"))
        assertTrue(prompt.contains("- app/src/main/Foo.kt"))
        assertTrue(prompt.contains("- app/src/test"))
        assertTrue("必须说明没有终端、不能编译", prompt.contains("不能执行命令、不能编译"))
        assertTrue("必须说明编译由主代理负责", prompt.contains("编译与测试由主代理统一执行"))
        assertTrue("编程位必须被要求写清改了哪几处", prompt.contains("改前 → 改后"))
    }

    @Test
    fun `审查位必须被要求给出通过与否`() {
        val prompt = buildAgentSystemPrompt(thread(role = AgentRole.REVIEWER))
        assertTrue(prompt.contains("\"verdict\": \"pass\""))
        assertTrue(prompt.contains("\"verdict\": \"fail\""))
        assertTrue("没把握必须写 fail", prompt.contains("没有把握就写 fail"))
    }

    // ------------------------------------------------------------ 步数上限

    @Test
    fun `写权限线程的步数上限必须明显高于只读线程`() {
        assertEquals("v240：只读位从 20 提到 32（20 步在真机上被撞满过）", 32, AGENT_MAX_STEPS_READONLY)
        assertTrue(
            "编程位必须明显多于只读位（读→改→再读确认，一轮吃好几步）",
            AGENT_MAX_STEPS_WRITABLE >= AGENT_MAX_STEPS_READONLY * 3 / 2,
        )
        assertTrue("但也不能无上限", AGENT_MAX_STEPS_WRITABLE <= 64)
    }

    // ------------------------------------------------------------ 源码门禁

    @Test
    fun `写工具集绝不能出现终端编译删除改名等能力`() {
        val writable = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentWritableTools.kt")
        listOf(
            "workspace_shell",
            "workspace_publish_file",
            "executeCommand",
            "deleteFile",
            "moveFile",
            "importFile",
            "gradlew",
            "assembleRelease",
        ).forEach {
            assertFalse("写工具集不得出现 $it", writable.contains(it))
        }
        // 只允许这两把
        assertTrue(writable.contains("name = \"workspace_write_file\""))
        assertTrue(writable.contains("name = \"workspace_edit_file\""))
        assertEquals(
            "写工具只允许 2 把",
            2,
            Regex("name = \"workspace_[a-z_]+\"").findAll(writable).count(),
        )
    }

    @Test
    fun `每一次写入之前都必须过白名单检查`() {
        val writable = source("app/src/main/java/me/rerere/rikkahub/agent/tools/AgentWritableTools.kt")
        // 两把工具各一处检查，缺一处就等于开了后门
        assertEquals(
            "两把写工具都必须检查白名单",
            2,
            Regex("!isWorkspacePathAllowed\\(path, allowed\\) -> deniedResult").findAll(writable).count(),
        )
        assertTrue(
            "白名单为空必须直接返回空工具列表",
            writable.contains("if (allowed.isEmpty()) return emptyList()"),
        )
    }

    @Test
    fun `后端装配写工具时必须以线程白名单为唯一依据`() {
        val backend = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertTrue(
            "写工具必须由 thread.writablePaths 决定，不能由角色决定",
            backend.contains("createAgentWritableTools(thread.workspaceId, workspaceRepository, thread.writablePaths)"),
        )
        assertFalse(
            "绝不允许按角色直接给写权限",
            backend.contains("role == AgentRole.PROGRAMMER) createAgentWritableTools"),
        )
    }

    @Test
    fun `写白名单必须落库以便续跑后仍然有效`() {
        val entities = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentEntities.kt")
        assertTrue(entities.contains("@ColumnInfo(\"writable_paths\""))
        val repo = source("app/src/main/java/me/rerere/rikkahub/agent/repo/AgentThreadRepository.kt")
        assertTrue(repo.contains("writablePaths = encodeWritablePaths(writablePaths)"))
        assertTrue(repo.contains("writablePaths = decodeWritablePaths(writablePaths)"))
    }
}
