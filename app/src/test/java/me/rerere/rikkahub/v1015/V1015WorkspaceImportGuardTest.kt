package me.rerere.rikkahub.v1015

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1015 门禁：「复制官方 RikkaHub 工作区」防走丢。
 *
 * 这处功能是 v1015 体检里最严重的一例：合并官方 3.1.0 时 WorkspacePage.kt 被官方版本
 * 逐字节整覆盖（922 行 → 250 行），整套界面就此消失，而**数据层、页面控制层、52 条文案全在**，
 * 所以编译照过、测试照绿、用户只是发现入口没了。判据因此必须同时盯三件事：
 * 界面代码还在、控制层每个动作都有调用点、文案键不是孤儿。
 */
class V1015WorkspaceImportGuardTest {

    private val page = "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspacePage.kt"
    private val section = "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/OfficialWorkspaceSection.kt"
    private val vm = "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceVM.kt"

    @Test
    fun `官方工作区卡片组件文件还在`() {
        assertTrue("OfficialWorkspaceSection.kt 不见了 —— 卡片整块被删", V1015SourceGuard.exists(section))
        assertTrue(
            "卡片函数不可见（internal 被改回 private 或删掉）",
            V1015SourceGuard.codeHas(section, "internal fun OfficialRikkaHubWorkspaceCard(")
        )
    }

    @Test
    fun `页面里有官方工作区界面的实码而不是只留文案`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(page))
        val hits = Regex("[Oo]fficial").findAll(code).count()
        assertTrue(
            "WorkspacePage 里 official 相关写法只剩 $hits 处（被整覆盖会退回 0，v322 时代有上百处）",
            hits >= 25
        )
    }

    @Test
    fun `控制层每个动作都有界面调用点`() {
        // 这一条正是 v1015 事故的形状：VM 函数全在、没人调用 = 假装有功能。
        listOf(
            "vm.loadOfficialWorkspaces" to "浏览官方工作区",
            "vm.importOfficialWorkspace" to "发起复制",
            "vm.resumeOfficialTransfer" to "断点续传",
            "vm.discardOfficialTransfer" to "丢弃半成品",
            "vm.cancelOfficialImport" to "暂停/取消",
            "vm.dismissImportOutcome" to "结果关窗",
            "vm.resetOfficialConnectionState" to "重连时清态",
        ).forEach { (call, what) ->
            assertTrue("界面缺 $what 的调用点「$call」", V1015SourceGuard.codeHas(page, call))
        }
        assertTrue("控制层状态没被界面收集", V1015SourceGuard.codeHas(page, "vm.officialMigration.collectAsStateWithLifecycle"))
        assertTrue("传输进度没被界面收集", V1015SourceGuard.codeHas(page, "vm.officialImport.collectAsStateWithLifecycle"))
    }

    @Test
    fun `页面注入复制功能要用的存储与设置通道`() {
        assertTrue("缺 sharedStorageManager 注入", V1015SourceGuard.codeHas(page, "sharedStorageManager: SharedStorageManager = koinInject()"))
        assertTrue("缺 settingsStore 注入", V1015SourceGuard.codeHas(page, "settingsStore: SettingsStore = koinInject()"))
        assertTrue("缺已连接目录的判定", V1015SourceGuard.codeHas(page, "OfficialRikkaHubStorage.isOfficialTree"))
    }

    @Test
    fun `关键文案键不是孤儿`() {
        val files = arrayOf(page, section)
        listOf(
            "workspace_official_card_title",
            "workspace_official_card_import",
            "workspace_official_import_confirm_title",
            "workspace_official_transfer_resume",
            "workspace_official_transfer_discard_desc",
            "workspace_official_app_missing",
        ).forEach { key ->
            assertTrue("文案键 $key 已无人引用（界面入口丢了）", V1015SourceGuard.stringKeyUsed(*files, key = key))
        }
    }

    @Test
    fun `控制层与传输服务本体都还在`() {
        assertTrue("WorkspaceVM 缺发起复制的动作", V1015SourceGuard.codeHas(vm, "fun importOfficialWorkspace("))
        assertTrue("WorkspaceVM 缺断点续传", V1015SourceGuard.codeHas(vm, "fun resumeOfficialTransfer("))
        assertTrue(
            "清单里没注册前台服务",
            V1015SourceGuard.xmlHasAttr(
                V1015SourceGuard.source("app/src/main/AndroidManifest.xml"),
                "android:name", ".service.OfficialTransferService"
            )
        )
    }
}
