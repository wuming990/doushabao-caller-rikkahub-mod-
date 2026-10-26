package me.rerere.rikkahub.v1015

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1015 门禁：本地备份导入/导出必须走应用级作用域（v302 的修复）。
 *
 * v1015 体检发现：v302 那轮把导入导出从页面作用域挪进 ViewModel 之后，**一条守卫测试都没写**，
 * 于是合并官方 3.1.0 时页面被官方版本整覆盖、又退回 rememberCoroutineScope，而 BackupVM 里的
 * 后台机制一行没丢、只是没人调用 —— 官方 issue #711 同款的 ForgottenCoroutineScopeException
 * 会再次把「页面被回收」误报成「恢复失败」。这一族正是「实现还在但入口没了」的静默退化。
 */
class V1015BackupWiringGuardTest {

    private val tab = "app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ImportExportTab.kt"
    private val vm = "app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt"

    @Test
    fun `页面从 ViewModel 取进行状态与结果`() {
        listOf(
            "vm.localTransfer.collectAsStateWithLifecycle()",
            "vm.exportTo(it)",
            "vm.importFrom(it, importKind)",
            "vm.consumeLocalTransfer()",
            "LocalTransferState.Done",
            "LocalTransferState.Failed",
        ).forEach { needle ->
            assertTrue("ImportExportTab 缺接线「$needle」", V1015SourceGuard.codeHas(tab, needle))
        }
    }

    @Test
    fun `页面不得再出现会被回收取消的页面作用域写法`() {
        // 判据只在剥掉注释的代码部分上算 —— 注释里正是用来解释这些旧写法为什么不能用的。
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(tab))
        listOf(
            "rememberCoroutineScope",
            "scope.launch",
            "vm.exportToFile()",
            "vm.restoreFromLocalFile(",
            "vm.restoreFromChatBox(",
            "vm.restoreFromCherryStudio(",
        ).forEach { banned ->
            assertFalse("ImportExportTab 又出现页面作用域写法「$banned」", code.contains(banned))
        }
    }

    @Test
    fun `导入导出的真实工作仍在应用级作用域里跑`() {
        assertTrue("BackupVM 没注入 AppScope", V1015SourceGuard.codeHas(vm, "private val appScope: AppScope,"))
        assertTrue("beginLocalTransfer 没用 appScope.launch", V1015SourceGuard.codeHas(vm, "appScope.launch"))
    }

    @Test
    fun `取消一律原样上抛不许吞成失败`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(vm))
        // 判据按 BackupVM 的真实写法来：catch 住 CancellationException → 清回 Idle → 原样 throw。
        // 上一版这里写成找 "is CancellationException"，代码里根本没这个写法 —— 凭空判据等于没判。
        assertTrue("缺 CancellationException 的 catch", code.contains("catch (e: CancellationException)"))
        assertTrue(
            "取消没有原样上抛（吞掉会让停止按钮失效）",
            Regex("catch \\(e: CancellationException\\)[\\s\\S]{0,160}?throw e").containsMatchIn(code)
        )
        assertTrue(
            "取消时没把进行状态清回 Idle",
            Regex("catch \\(e: CancellationException\\)[\\s\\S]{0,160}?LocalTransferState\\.Idle").containsMatchIn(code)
        )
    }

    @Test
    fun `临时文件在 finally 里删除`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(vm))
        Regex("finally\\s*\\{[^}]*delete\\(\\)").also { m ->
            assertTrue("导入/导出的临时文件没有在 finally 里删除", m.containsMatchIn(code))
        }
    }

    @Test
    fun `结果报告后要清态避免回到本页重复弹提示`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(tab))
        val n = Regex("vm\\.consumeLocalTransfer\\(\\)").findAll(code).count()
        assertTrue("Done 与 Failed 两支都要 consumeLocalTransfer，现在只找到 $n 处", n >= 2)
    }
}
