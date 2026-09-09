package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v248：检索遍历必须有刹车。
 *
 * ## 为什么加这一组用例（真机事故）
 *
 * 一条 programmer 子代理开局并行发了 3 个 `workspace_find_files`
 * （pattern 分别是 `**​/TASKBOARD.md`、`**​/AGENTS.md`、`**​/PROJECT-MANUAL.md`，从工作区根搜），
 * **跑了 8 分 35 秒还没返回**，被人工停止，什么活都没干成。
 *
 * 根因：这台设备的工作区整棵树有 44 万个条目，而这类模式全树往往只有 1 个匹配 ——
 * 旧实现「凑够 500 个匹配就停」的额度永远凑不满，于是必然走完全树。
 *
 * ## 三道刹车
 *
 * 1. **目录剪枝**：`build` / `.git` / `node_modules` 这类构建与缓存目录直接跳过整棵子树。
 *    本机实测：源码树 91,819 个条目 → 15,547 个（`build` 一家占 83%）。
 * 2. **时间预算**：到点就带着已找到的结果返回（主防线，跨设备都成立）。
 * 3. **条目预算**：第二道闸。
 *
 * 而且被截断时必须**如实告知**，否则模型会把「没扫完」当成「文件不存在」，
 * 基于错误前提继续往下做 —— 那比慢更危险。
 */
class WorkspaceSearchBudgetTest {

    private fun tempRoot(): File = Files.createTempDirectory("workspace-budget-test").toFile()

    // ------------------------------------------------------------ 剪枝清单

    @Test
    fun `构建与缓存目录必须跳过，用户资产目录绝对不许跳`() {
        listOf(
            ".git", ".gradle", ".idea", ".vscode", ".kotlin", ".cxx",
            "build", "node_modules", "__pycache__", ".venv", "venv",
            ".dart_tool", ".pub-cache", ".cache", ".m2", ".npm",
        ).forEach {
            assertTrue("$it 应当被跳过", shouldSkipSearchDirectory(it))
        }
        // 这些是用户的东西，替用户决定跳过是错的
        listOf("src", "app", "dist", "tools", "patches", "logs", "upstream", "docs", "assets")
            .forEach {
                assertFalse("$it 绝对不能被跳过", shouldSkipSearchDirectory(it))
            }
    }

    @Test
    fun `剪枝必须真的生效 —— build 里的同名文件不该被找到`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "src/Main.kt", "fun main() {}")
        fs.writeText(root, "build/generated/Main.kt", "// 产物，不该被搜到")
        fs.writeText(root, "node_modules/pkg/index.kt", "// 依赖，不该被搜到")
        fs.writeText(root, "dist/Main.kt", "// 用户资产，应该能被搜到")

        val found = fs.glob(root, "**/*.kt", budgeted = true).map { it.path }.sorted()
        assertEquals(listOf("dist/Main.kt", "src/Main.kt"), found)
    }

    @Test
    fun `起点目录本身不许被剪 —— 明确指定 build 也要能搜`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "build/generated/Gen.kt", "// 明确要看产物时也得看得到")

        val found = fs.glob(root, "**/*.kt", path = "build", budgeted = true).map { it.path }
        assertEquals(listOf("build/generated/Gen.kt"), found)
    }

    @Test
    fun `按内容搜索同样要剪枝`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "src/A.kt", "needle here")
        fs.writeText(root, "build/B.kt", "needle here too")

        val matches = fs.grep(root, "needle", budgeted = true)
        assertEquals(listOf("src/A.kt"), matches.map { it.path })
    }

    // ------------------------------------------------------------ 预算与如实告知

    @Test
    fun `扫完了就不该说没扫完`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "src/A.kt", "a")
        val scan = WorkspaceScanReport()
        fs.glob(root, "**/*.kt", report = scan, budgeted = true)

        assertFalse(scan.stoppedEarly)
        assertNull("扫完了就不必啰嗦", scan.hintOrNull())
        assertTrue("必须记下扫了多少", scan.visitedEntries > 0)
    }

    @Test
    fun `条目预算用尽必须停下并如实告知`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxWalkEntries = 5))
        repeat(40) { fs.writeText(root, "src/file$it.txt", "x") }

        val scan = WorkspaceScanReport()
        // 搜一个不存在的名字：匹配额度永远凑不满，正是事故里那种模式
        val found = fs.glob(root, "**/never-exists.md", report = scan, budgeted = true)

        assertTrue("必须提前停", scan.stoppedEarly)
        assertEquals("entry-budget", scan.stopReason)
        assertNotNull("必须告诉模型结果可能不完整", scan.hintOrNull())
        assertTrue(
            "提示里要教它怎么办",
            scan.hintOrNull()!!.contains("path"),
        )
        assertTrue("没找到就是没找到，不许编", found.isEmpty())
    }

    @Test
    fun `时间预算用尽必须停下并标明原因`() {
        val root = tempRoot()
        // 预算 1 毫秒：只要条目够多，必然在中途到点
        val fs = WorkspaceFileSystem(WorkspaceConfig(walkTimeBudgetMillis = 1))
        // 时钟每 512 个条目查一次，所以要造得比 512 多
        repeat(1500) { fs.writeText(root, "src/d${it % 10}/file$it.txt", "x") }

        val scan = WorkspaceScanReport()
        fs.glob(root, "**/never-exists.md", report = scan, budgeted = true)

        assertTrue("必须提前停", scan.stoppedEarly)
        assertEquals("time-budget", scan.stopReason)
        assertNotNull(scan.hintOrNull())
    }

    @Test
    fun `不传报告对象也不许崩`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxWalkEntries = 3))
        repeat(20) { fs.writeText(root, "src/f$it.txt", "x") }
        // 旧调用方（例如既有单测）不传 report，必须照样能用
        assertTrue(fs.glob(root, "**/never-exists.md", budgeted = true).isEmpty())
        assertTrue(fs.grep(root, "never-exists-content", budgeted = true).isEmpty())
    }

    @Test
    fun `跳过的目录数要如实计数`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "src/A.kt", "a")
        fs.writeText(root, "build/B.kt", "b")
        fs.writeText(root, "node_modules/C.kt", "c")

        val scan = WorkspaceScanReport()
        fs.glob(root, "**/*.kt", report = scan, budgeted = true)
        assertEquals("build 与 node_modules 各算一个", 2, scan.skippedDirectories)
    }

    // ------------------------------------------------------------ 原有语义不许回归

    @Test
    fun `匹配额度满了就停，不再往下扫`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        repeat(20) { fs.writeText(root, "src/f$it.txt", "x") }

        assertEquals(3, fs.glob(root, "**/*.txt").size)
    }

    @Test
    fun `搜索命中额度满了就停`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxSearchResults = 4))
        repeat(20) { fs.writeText(root, "src/f$it.txt", "needle") }

        assertEquals(4, fs.grep(root, "needle").size)
    }

    @Test
    fun `隐藏的同步中间文件仍然要被忽略`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "src/A.kt", "a")
        File(root, "src/.l2s.tmp.kt").writeText("同步中间文件")

        assertEquals(listOf("src/A.kt"), fs.glob(root, "**/*.kt", budgeted = true).map { it.path })
    }
}
