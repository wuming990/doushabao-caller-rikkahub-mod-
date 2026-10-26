package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1017 门禁：把「工具超长输出」的四处接线钉住。
 *
 * 动机（三家探索 + 反驳报告共同确认的真实缺陷）：
 * 1. 判据以前按**工具名**猜有没有终端，远程 SSH 工作区会收到一条
 *    `/tool_outputs/xxx.txt` 的死路径（文件在本机，远端没有）；
 * 2. 以前**先无条件写盘、后判断能不能读回**，没绑工作区时手机里留下一堆没人读的文件；
 * 3. 以前只留开头 4K，命令的报错与结论（都在末尾）被丢掉；
 * 4. 搜索结果 JSON 会被切碎，界面空白且不报错；
 * 5. 开机把 tool_outputs 整个目录清空，历史消息里的路径被杀一次进程就成死链。
 *
 * 这些都属于"整块覆盖回官方/旧实现也照样编译通过"的静默退化，只能靠源码钉子防复发。
 */
class V1017ToolOutputGuardTest {

    private val appJava = "app/src/main/java/me/rerere/rikkahub/"

    /**
     * v1017：取源码必须从 user.dir **上溯到仓库根**（认 settings.gradle.kts）。
     * 直接写相对路径会在 Gradle 下炸：AGP 给单元测试的工作目录是模块目录（app/），
     * 相对路径会变成 app/app/src/main/…  → FileNotFoundException。
     * 本仓其余守卫测试（如 GenerationStepStatsTest）都是这个写法，照它来。
     */
    private fun src(rel: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        return File(checkNotNull(directory) { "找不到仓库根（settings.gradle.kts）" }, appJava + rel).readText()
    }

    private val loop: String by lazy { src("data/ai/GenerationLoop.kt") }
    private val chatService: String by lazy { src("service/ChatService.kt") }
    private val app: String by lazy { src("RikkaHubApp.kt") }
    private val workspaceTools: String by lazy { src("data/ai/tools/WorkspaceTools.kt") }
    private val reminder: String by lazy { src("data/ai/transformers/WorkspaceReminderTransformer.kt") }

    @Test
    fun `截断必须按工具名豁免搜索结果`() {
        assertTrue(
            "主循环必须真的按豁免名单跳过截断，不许只留一份名单没人用（假开关）",
            loop.contains("if (toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output"),
        )
    }

    @Test
    fun `预览必须改成头尾双取且不许再谎称全文`() {
        assertTrue(
            "必须走 headAndTailPreview 纯函数",
            loop.contains("headAndTailPreview(fullText, TOOL_OUTPUT_PREVIEW_CHARS)"),
        )
        assertFalse(
            "不许再出现「Full output saved」这类话 —— 上游 shell 自己就可能丢内容，说全文是撒谎",
            loop.contains("Full output saved to"),
        )
    }

    @Test
    fun `必须先判断能否读回再决定写不写盘`() {
        val gate = loop.indexOf("val saved = if (canReadBack) {")
        val write = loop.indexOf("File(outputDir, fileName).writeText(fullText)")
        assertTrue("找不到能否读回的闸门", gate > 0)
        assertTrue("找不到写盘那一行", write > 0)
        assertTrue("闸门必须在写盘之前（旧写法是先写再判断，会留垃圾文件）", gate < write)
    }

    @Test
    fun `新判据默认不生效且只有主对话显式打开`() {
        assertTrue(
            "generateText 的 toolOutputsReadable 默认必须是 null（圆桌/群/子代理行为不变）",
            loop.contains("toolOutputsReadable: Boolean? = null,"),
        )
        assertTrue(
            "没传值时必须回落到旧的工具名判据，不许悄悄变成 false 收紧主对话以外的路径",
            loop.contains("val canReadBackToolOutputs = toolOutputsReadable"),
        )
        assertTrue(
            "主对话调用点必须显式传能力判据",
            chatService.contains("toolOutputsReadable = assistant.workspaceId?.toString()?.let { wsId ->"),
        )
        assertTrue(
            "主对话传的判据必须来自工作区能力 rootfs",
            chatService.contains("capabilities()?.rootfs"),
        )
        assertTrue(
            "判据必须同时要求 shell 已就绪（少这半句就会重新造出读不回来的死路径）",
            chatService.contains("ws.shellStatus == WorkspaceShellStatus.READY.name"),
        )
    }

    @Test
    fun `主对话与其余路径的隔离红线不许被牵连`() {
        // §4 禁区 #13：ChatService 整份文件不许出现工具输出长度上限那个参数名
        assertFalse(
            "ChatService 里不许出现长度上限参数名（注释也算，v314 为此真红过一次）",
            chatService.contains("toolOutputCharLimit"),
        )
    }

    @Test
    fun `开机不许再清空工具输出目录`() {
        val body = app.substringAfter("private fun cleanupToolOutputs()")
            .substringBefore("    private fun ")
        // ⚠️ 判据必须写成「调用形状」：我在生产代码的注释里解释过旧写法，
        // 只查词会被自己的注释打红（§10 第 50 条的同族坑）。
        assertFalse(
            "目录级递归删除会让历史消息里的路径变成死链",
            body.contains("dir.deleteRecursively()"),
        )
        assertTrue("必须改成按存活天数清理", body.contains("ttlMillis"))
        assertTrue("必须按总量配额清理", body.contains("maxBytes"))
    }

    @Test
    fun `远程工作区不许拿到写文件与改文件工具`() {
        assertTrue(
            "必须按 fileWriteTools 能力过滤",
            workspaceTools.contains("filterNot { !canUseFileWriteTools && it.name in WorkspaceFileWriteToolNames }"),
        )
        assertTrue(
            "本地工作区（能力查不到时）必须保持现状，不许顺手少给工具",
            workspaceTools.contains("?: true"),
        )
    }

    @Test
    fun `所有通知小图标必须用我们自己的，不许残留官方图标`() {
        // v1017：历史遗留 —— NotificationUtil / WebServerService / ChatGenerationForegroundService
        // 早就换成我们的 ic_stat_v261，但 MemoryDream 与 MediaCreation 两条前台服务漏了，
        // 表现是「记忆整理 / 媒体生成的通知图标是官方的」。判据看**调用形状**，
        // 不查 DEX 字符串（R 类符号表会把两个名字都留在包里，查词必然误报）。
        val files = listOf(
            "service/MemoryDreamForegroundService.kt",
            "service/MediaCreationForegroundService.kt",
            "service/ChatGenerationForegroundService.kt",
            "service/WebServerService.kt",
            "utils/NotificationUtil.kt",
        )
        for (f in files) {
            val text = src(f)
            assertFalse("$f 不得再用官方通知图标", text.contains("R.drawable.ic_stat_rikkahub"))
            assertTrue("$f 必须用我们自己的通知图标", text.contains("R.drawable.ic_stat_v261"))
        }
    }

    @Test
    fun `SSH 工作区必须有能建出它的入口不许留半成品`() {
        val page = src("ui/pages/extensions/workspace/WorkspacePage.kt")
        assertTrue("列表页必须能导航到 SSH 编辑器（id 为空 = 新建）",
            page.contains("navController.navigate(Screen.WorkspaceSshEditor())"))
        assertTrue("新建按钮必须先开类型选择", page.contains("FloatingActionButton(onClick = { showTypeSheet = true })"))
        assertTrue("本地与 SSH 两个选项都要在",
            page.contains("workspace_page_type_local") && page.contains("workspace_page_type_ssh"))
        assertTrue("列表图标必须按类型区分", page.contains("if (workspace.isSsh()) HugeIcons.CloudServer else HugeIcons.FolderCode"))
    }

    @Test
    fun `读写工作区文件必须走按环境分发的方法不许只认本机 rootfs`() {
        // v1017：SSH 工作区的文件在远端机器上。rootfsFileSize / exportRootfsFile 是
        // 「只在本机 PRoot 目录里找」的实现，用它读远程工作区 = 读错机器或读不到，
        // 表现为「AI 说文件不存在」，而编译器与现有测试都抓不住。
        // 判据必须是「调用形状」：我在解释性注释里提到过这两个方法名，只查词会被注释打红。
        listOf("rootfsFileSize(workspaceId", "exportRootfsFile(workspaceId").forEach { localOnlyCall ->
            assertFalse(
                "WorkspaceTools.kt 不得再调用只认本机的 $localOnlyCall",
                workspaceTools.contains(localOnlyCall),
            )
        }
        assertTrue("必须使用按环境分发的取大小方法", workspaceTools.contains("absoluteFileSize(workspaceId, path)"))
        assertTrue("必须使用按环境分发的导出方法", workspaceTools.contains("exportAbsoluteFile(workspaceId, path"))
    }

    @Test
    fun `远程工作区必须按远程实际环境注入说明不许套用本机口径`() {
        // v1017 修正：原本这里钉的是「SSH 直接不注入」，那是我为了少给错误情报做的临时闸门；
        // 后来按官方 3.1.1 的正规机制融合（远程探测 shell 与发行版并缓存），断言跟着换成核真实机制。
        assertTrue("SSH 分支必须存在", reminder.contains("if (workspace.isSsh()) {"))
        assertTrue("必须走远程专用提示词", reminder.contains("buildSshWorkspacePrompt("))
        assertTrue("shell 与发行版必须按工作区分发取（不是本机 rootfs 硬路径）",
            reminder.contains("workspaceRepository.shell(workspaceId)"))
        assertTrue("探测结果要有缓存，避免每轮重连", reminder.contains("RemoteEnvironmentCache()"))
        assertTrue("连不上时要如实说明而不是抛错卡住对话", reminder.contains("buildUnreachablePrompt("))
        // 本机口径仍然只能用在本地分支上
        val sshBranch = reminder.indexOf("if (workspace.isSsh()) {")
        val localPrompt = reminder.indexOf("buildWorkspacePrompt(", sshBranch)
        assertTrue("本地口径必须在 SSH 分支之后（即走 else 分支）", sshBranch in 1 until localPrompt)
    }
}
