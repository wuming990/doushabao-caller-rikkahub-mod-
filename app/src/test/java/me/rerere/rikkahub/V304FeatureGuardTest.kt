package me.rerere.rikkahub

import org.junit.Assert.assertEquals
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
        val prefs = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt") + "\n" + source("app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt") // v323：官方 3.1 把 Settings 字段拆到 Settings.kt，判据改读两个文件（断言内容未改）
        assertTrue(prefs.contains("val SKILL_ORDER = stringPreferencesKey(\"skill_order\")"))
        assertTrue(prefs.contains("preferences[SKILL_ORDER] = encodeSkillOrder(settings.skillOrder)"))
        assertTrue(prefs.contains("skillOrder = decodeSkillOrder(preferences[SKILL_ORDER]),"))
        assertTrue(prefs.contains("internal fun encodeSkillOrder(names: List<String>): String"))
        assertTrue(prefs.contains("internal fun decodeSkillOrder(raw: String?): List<String>"))
        // v323：官方 3.1 把技能管理从 data/files 迁到 data/skills（只是文件位置变了，
        // 「按用户拖动顺序排序」这个功能没变），判据跟着改到新路径；其余断言一个字没动。
        val manager = source("app/src/main/java/me/rerere/rikkahub/data/skills/SkillManager.kt")
        assertTrue(manager.contains("sortSkillsByOrder(raw, settingsStore.settingsFlow.value.skillOrder)"))
        val vm = source("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt")
        assertTrue(vm.contains("fun reorderSkills(ordered: List<SkillMetadata>)"))
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsPage.kt")
        assertTrue(page.contains("dragToReorder("))
    }

    @Test
    fun `会话视角与发送链路没被官方覆盖(v323)`() {
        val service = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue(
            "主对话生成必须用会话视角助手（settings.getAssistantOf(conversation)）",
            service.contains("settings.getAssistantOf(initialConversation)"),
        )
        assertTrue(
            "主对话生成必须用会话视角模型（settings.getChatModelOf(conversation)）",
            service.contains("settings.getChatModelOf(initialConversation)"),
        )
        val searchFn = service
            .substringAfter("suspend fun updateChatSearch")
            .substringBefore("suspend fun updateChatAssistant")
        assertTrue(
            "updateChatSearch 不许 bindConfig（会把新会话配置提前固定死）",
            !searchFn.contains("bindConfig("),
        )
        assertTrue(
            "updateChatSearch 的 config 分支必须真的落库",
            searchFn.contains("updateConversationConfig"),
        )
        assertTrue(
            "updateChatAssistant 必须真的写 conversation.config",
            service.contains("conversationRepo::updateConversationConfig"),
        )

        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue("发送前自动压缩分叉丢了", page.contains("vm.shouldAutoCompressBeforeSend()"))
        assertTrue("附件处理中发送拦截丢了", page.contains("inputState.hasPendingProcessing()"))
        assertTrue("附件失败时发送拦截丢了", page.contains("inputState.hasFailedPending()"))
        assertTrue(
            "聊天页必须用会话视角助手，不许用 setting.getCurrentAssistant()",
            page.contains("val assistant by vm.assistant.collectAsStateWithLifecycle()"),
        )
        // v323：反向断言只认调用语法，避免被解释这条坑的注释误伤（手册 §7.3）。
        assertTrue(
            "聊天页仍有把全局当前助手当会话助手的写法",
            !Regex("""val\s+assistant\s*=\s*setting\.getCurrentAssistant\s*\(""").containsMatchIn(page),
        )
        // v323：文件面板/输入框都必须走 VM（否则会话视角助手会被整份写回全局助手设置）
        assertEquals(
            "onUpdateAssistant 必须走 vm::updateAssistant（两处：输入框 + 文件面板）",
            2,
            Regex("""onUpdateAssistant\s*=\s*vm::updateAssistant""").findAll(page).count(),
        )

        val prefs = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        listOf(
            "enableAutoTitle = preferences[ENABLE_AUTO_TITLE]",
            "translateTargetLanguage = preferences[TRANSLATE_TARGET_LANGUAGE]",
            "uploadS3Config = preferences[UPLOAD_S3_CONFIG]",
            "mediaGenerationProviders = preferences[MEDIA_GENERATION_PROVIDERS]",
            "sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT]",
        ).forEach { needle ->
            assertTrue("设置读路径漏字段：$needle", prefs.contains(needle))
        }
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
