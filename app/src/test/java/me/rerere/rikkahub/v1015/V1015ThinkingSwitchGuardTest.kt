package me.rerere.rikkahub.v1015

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1015 门禁：设置里的「显示思考内容」开关，以及文件选择器图标。
 *
 * 前者在合并 3.1.0 时出现的是最容易被忽略的一种坏法：**标题那行留着、开关整块没了**，
 * 设置页于是多出一个点不动的光标题，而字段默认值还在生效、没人能在界面上关它。
 * 后者是官方 3.1 把 DocumentsProvider 挪了包，我们带自定义图标的那行留在旧包的老文件里，
 * 结果系统文件选择器里又显示成官方图标。
 */
class V1015ThinkingSwitchGuardTest {

    private val uiPage = "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt"
    private val reasoning = "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt"
    private val provider = "app/src/main/java/me/rerere/rikkahub/service/WorkspaceDocumentsProvider.kt"

    @Test
    fun `显示思考内容必须有真的开关`() {
        assertTrue(
            "设置页缺 showThinkingContent 的勾选框（只剩标题 = 融合漏了整块）",
            V1015SourceGuard.codeHas(uiPage, "checked = displaySetting.showThinkingContent")
        )
        assertTrue(
            "设置页缺 showThinkingContent 的写回",
            V1015SourceGuard.codeHas(uiPage, "displaySetting.copy(showThinkingContent = it)")
        )
    }

    @Test
    fun `标题与说明两条文案都被引用`() {
        listOf(
            "setting_display_page_show_thinking_content_title",
            "setting_display_page_show_thinking_content_desc",
        ).forEach { key ->
            assertTrue("文案键 $key 已无人引用", V1015SourceGuard.stringKeyUsed(uiPage, key = key))
        }
    }

    @Test
    fun `思考内容的消费方仍读同一个字段`() {
        assertTrue(
            "ChatMessageReasoning 不再读 showThinkingContent —— 开关会变成假开关",
            V1015SourceGuard.codeHas(reasoning, "settings.displaySetting.showThinkingContent")
        )
    }

    @Test
    fun `系统文件选择器里用的是我们自己的图标`() {
        assertTrue(
            "DocumentsProvider 的图标退回官方 ic_launcher 了",
            V1015SourceGuard.codeHas(provider, "R.mipmap.ic_launcher_v261")
        )
        // 旧包残留那份不参与运行，清单必须注册 service 包这一份，否则改图标改错了文件。
        val manifest = V1015SourceGuard.source("app/src/main/AndroidManifest.xml")
        assertTrue(
            "清单注册的 DocumentsProvider 不是 service 包那一份",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", ".service.WorkspaceDocumentsProvider")
        )
    }
}
