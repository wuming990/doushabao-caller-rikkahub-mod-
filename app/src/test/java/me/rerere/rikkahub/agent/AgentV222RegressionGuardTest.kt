package me.rerere.rikkahub.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v222 门禁（源码级扫描）：锁死 v221 的两项缺陷与过程不可视问题，防止再次回归。
 *
 * 1) AgentThreadManager 的收尾状态写入必须包在 NonCancellable 中；
 * 2) AgentThreadPage 必须包含截断提示横幅与「继续」/「继续输出」按钮；
 * 3) GenerationAgentBackend 必须保留【思考】与【工具】渲染，不得只用 toText()；
 * 4) AgentDatabase 必须为 version 2 且配置了 AGENT_MIGRATION_1_2。
 */
class AgentV222RegressionGuardTest {

    private val moduleDir = File(System.getProperty("user.dir"))

    private fun source(relative: String): String {
        val candidates = listOf(
            File(moduleDir, "src/main/java/me/rerere/rikkahub/$relative"),
            File(moduleDir, "app/src/main/java/me/rerere/rikkahub/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码 $relative（user.dir=${moduleDir.absolutePath}）")
        return file.readText()
    }

    @Test
    fun `AgentThreadManager 必须在 NonCancellable 中落库收尾状态`() {
        val text = source("agent/runtime/AgentThreadManager.kt")
        assertTrue(
            "AgentThreadManager 必须 import NonCancellable",
            text.contains("import kotlinx.coroutines.NonCancellable"),
        )
        assertTrue(
            "收尾写入必须使用 withContext(NonCancellable)",
            text.contains("withContext(NonCancellable)"),
        )
        assertTrue(
            "必须提供 resume 续跑函数",
            text.contains("suspend fun resume("),
        )
    }

    @Test
    fun `AgentThreadPage 必须包含截断横幅与继续按钮`() {
        val text = source("ui/pages/chat/AgentThreadPage.kt")
        assertTrue("全屏页必须有继续输出按钮", text.contains("继续输出") || text.contains("继续（"))
        assertTrue("全屏页必须有截断提示", text.contains("truncation_banner") || text.contains("达到模型单次字数上限"))
        assertTrue("全屏页必须有正在停止过渡态展示", text.contains("正在停止…"))
    }

    @Test
    fun `GenerationAgentBackend 必须保留思考过程与实时工具`() {
        val text = source("agent/runtime/GenerationAgentBackend.kt")
        assertTrue("必须包含【思考】标记", text.contains("【思考】"))
        assertTrue("必须包含【工具】标记", text.contains("【工具】"))
        assertTrue("必须包含 AGENT_RESUME_INSTRUCTION 续跑提示词", text.contains("AGENT_RESUME_INSTRUCTION"))
        assertTrue("必须支持 resume 参数", text.contains("resume: Boolean"))
    }

    @Test
    fun `AgentDatabase 必须是版本 3 且配置了全部迁移`() {
        val dbText = source("agent/db/AgentDatabase.kt")
        val moduleText = source("di/AgentModule.kt")

        // v236：2 → 3（新增 writable_paths / active_model_id）。
        // 主数据库 AppDatabase 仍必须是 24（另有门禁守护），本次一个字没碰。
        assertTrue("数据库版本必须为 3", dbText.contains("version = 3"))
        assertTrue("必须定义 AGENT_MIGRATION_1_2", dbText.contains("AGENT_MIGRATION_1_2"))
        assertTrue("必须定义 AGENT_MIGRATION_2_3", dbText.contains("AGENT_MIGRATION_2_3"))
        assertTrue(
            "v236 迁移必须只加列，绝不允许破坏性迁移",
            dbText.contains("ALTER TABLE agent_threads ADD COLUMN writable_paths"),
        )
        assertTrue(
            "写白名单默认必须为空（旧线程升级后不得凭空获得写权限）",
            dbText.contains("ADD COLUMN writable_paths TEXT DEFAULT ''"),
        )
        assertTrue(
            "AgentModule 必须装配全部迁移",
            moduleText.contains("addMigrations(AGENT_MIGRATION_1_2, AGENT_MIGRATION_2_3)"),
        )
        assertTrue(
            "绝不允许 fallbackToDestructiveMigration（会清掉子代理历史）",
            !moduleText.contains("fallbackToDestructiveMigration"),
        )
    }
}
