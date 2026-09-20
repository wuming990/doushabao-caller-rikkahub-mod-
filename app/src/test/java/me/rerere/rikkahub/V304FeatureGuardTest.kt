package me.rerere.rikkahub

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v304：四个新功能的「零件防覆盖守卫」。
 *
 * 只读源码做文本断言（不执行逻辑）—— 这几处分别埋在 Compose 手势、DataStore、
 * 子代理运行时与生成循环里，单测造不出对象。真正的算法单测在同模块的：
 * · data/files/SkillOrderTest（技能顺序 + 拖动换位）
 * · agent/runtime/ResumeModelRotationTest（续跑换模型的轮次推进）
 * · ui/components/ai/ConversationTokenCostTest（按次计价）
 *
 * 合并上游后本测试变红 = 某个功能被整覆盖了，必须手工融合，**不许直接把测试改绿**。
 */
class V304FeatureGuardTest {

    private val root: java.io.File? by lazy {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun source(vararg path: String): String {
        val dir = checkNotNull(root) { "找不到工程根目录" }
        return java.io.File(dir, path.joinToString("/")).readText()
    }

    @Test
    fun `文件夹长按拖动排序的零件没被覆盖`() {
        val drawer = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt")
        assertTrue(drawer.contains("dragToReorder("))
        assertTrue(drawer.contains("onReorder = { drawerVm.reorderFolders(it) },"))
        val vm = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt")
        assertTrue(vm.contains("fun reorderFolders(ordered: List<Folder>)"))
        val dao = source("app/src/main/java/me/rerere/rikkahub/data/db/dao/FolderDAO.kt")
        assertTrue(dao.contains("suspend fun updateAll(folders: List<FolderEntity>)"))
        assertTrue(dao.contains("suspend fun nextSortIndex(assistantId: String): Int"))
    }

    @Test
    fun `技能顺序的五环节与拖动接线没被覆盖`() {
        val prefs = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue(prefs.contains("val SKILL_ORDER = stringPreferencesKey(\"skill_order\")"))
        assertTrue(prefs.contains("preferences[SKILL_ORDER] = encodeSkillOrder(settings.skillOrder)"))
        assertTrue(prefs.contains("skillOrder = decodeSkillOrder(preferences[SKILL_ORDER]),"))
        assertTrue(prefs.contains("internal fun encodeSkillOrder(names: List<String>): String"))
        assertTrue(prefs.contains("internal fun decodeSkillOrder(raw: String?): List<String>"))
        val manager = source("app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt")
        assertTrue(manager.contains("sortSkillsByOrder(raw, settingsStore.settingsFlow.value.skillOrder)"))
        val vm = source("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt")
        assertTrue(vm.contains("fun reorderSkills(ordered: List<SkillMetadata>)"))
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsPage.kt")
        assertTrue(page.contains("dragToReorder("))
    }

    @Test
    fun `子代理续跑用完换模型的零件没被覆盖`() {
        val manager = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/AgentThreadManager.kt")
        assertTrue(
            manager.contains(
                "internal fun nextResumeModelRound(chainSize: Int, currentRound: Int): Int?"
            )
        )
        assertTrue(manager.contains("nextResumeModelRound(chain.size, currentRound)"))
        assertTrue(manager.contains("resume(threadId, keepModelRotation = true)"))
        val backend = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        assertTrue(backend.contains("preferredStartModels.remove(thread.id)"))
        val module = source("app/src/main/java/me/rerere/rikkahub/di/AgentModule.kt")
        assertTrue(module.contains("resumeModelChain = { thread ->"))
    }

    @Test
    fun `按次计价的零件没被覆盖`() {
        val model = source("ai/src/main/java/me/rerere/ai/provider/Model.kt")
        assertTrue(model.contains("val requestPrice: Double? = null"))
        val message = source("ai/src/main/java/me/rerere/ai/ui/Message.kt")
        assertTrue(message.contains("val cumulativeRequestCount: Int? = null"))
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(loop.contains("cumulativeRequestCount = entryCumulativeRequestCount + roundsRequestCount"))
        // v304 必修项（审查位抓到）：请求次数必须与「本轮有没有报用量」解耦，
        // 否则整趟都不报用量的服务商会被算成 0 次请求 → 按次价格算出 0 元。
        // 反向钉住旧写法：`val total = roundsUsage ?: return` 不许复活。
        assertTrue(loop.contains("if (total == null && roundsRequestCount <= 0) return"))
        assertTrue(!loop.contains("val total = roundsUsage ?: return"))
        val dao = source("app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt")
        assertTrue(dao.contains("private fun sumOfRequestCount(): String"))
        assertTrue(dao.contains("sumOfRequestCount()} AS requestCount"))
        val cost = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/ConversationTokenCost.kt")
        assertTrue(cost.contains("price.perRequest ?: 0.0"))
        assertTrue(cost.contains("|| perRequest != null"))
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt")
        assertTrue(page.contains("R.string.setting_provider_page_model_request_price"))
    }
}
