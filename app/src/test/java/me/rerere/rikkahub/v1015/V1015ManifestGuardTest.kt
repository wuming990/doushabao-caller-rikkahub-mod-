package me.rerere.rikkahub.v1015

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1015 门禁：清单（AndroidManifest）与快捷方式（res/xml/shortcuts.xml）的接线防走丢。
 *
 * 为什么需要这一份：v1015 体检发现合并官方 3.1.0 时，本项目的 Manifest 从来没有被任何测试读过，
 * 于是三处「我们加过的注册」被官方版本整覆盖掉，全都**不报错、也不崩溃**：
 *   - WAKE_LOCK 权限 → 工作区终端跑长任务会被系统休眠打断；
 *   - 两个 OAuth 回跳页 → 账号登录授权后浏览器回跳 rikkahub://codex|gemini/oauth 无人承接，
 *     界面永远停在「等待授权」（就是用户报的「点登录就卡死」）；
 *   - 官方工作区传输服务 → 就算界面回来了，复制也会在后台起不来。
 * 另外快捷方式是三方错位（Manifest 注册新 action / shortcuts.xml 发旧 action / 代码只认旧 action），
 * 这种「两边各改一半」的问题静态看每个文件都自洽，只有对着比才看得出来，所以这里专门做一致性断言。
 */
class V1015ManifestGuardTest {

    private val manifest = V1015SourceGuard.source("app/src/main/AndroidManifest.xml")
    private val shortcuts = V1015SourceGuard.source("app/src/main/res/xml/shortcuts.xml")

    @Test
    fun `工作区终端需要的 WAKE_LOCK 权限还在清单里`() {
        assertTrue(
            "AndroidManifest 缺 WAKE_LOCK —— 工作区终端防睡眠会静默失效",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", "android.permission.WAKE_LOCK")
        )
    }

    @Test
    fun `两套官方账号 OAuth 回跳页都注册在清单里`() {
        // 类文件一直都在，丢的是注册；只查类名会误判，所以按 activity 的 android:name 属性查。
        assertTrue(
            "缺 CodexOAuthRedirectActivity 注册 —— ChatGPT 账号登录授权后回不来",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", ".ui.activity.CodexOAuthRedirectActivity")
        )
        assertTrue(
            "缺 GeminiOAuthRedirectActivity 注册 —— 谷歌账号登录授权后回不来",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", ".ui.activity.GeminiOAuthRedirectActivity")
        )
    }

    @Test
    fun `官方工作区复制的前台服务注册在清单里`() {
        assertTrue(
            "缺 OfficialTransferService 注册 —— 「复制官方工作区」没有后台传输",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", ".service.OfficialTransferService")
        )
        assertTrue(
            "OfficialTransferService 必须是 specialUse 前台服务类型",
            manifest.contains("resumable official workspace transfer with progress notification")
        )
    }

    @Test
    fun `本地文件浏览器权限与我们的启动图标没被官方值顶掉`() {
        // v1006 就记过这两条红线，这里再钉一次：整覆盖 Manifest 会让权限弹窗循环、图标退回官方。
        assertTrue(
            "缺 MANAGE_EXTERNAL_STORAGE —— 本地文件浏览器会陷入授权循环弹窗",
            V1015SourceGuard.xmlHasAttr(manifest, "android:name", "android.permission.MANAGE_EXTERNAL_STORAGE")
        )
        assertTrue(
            "应用图标不是我们自己的 ic_launcher_v261",
            V1015SourceGuard.xmlHasAttr(manifest, "android:icon", "@mipmap/ic_launcher_v261")
        )
    }

    @Test
    fun `快捷方式发出的动作与清单注册的动作完全一致`() {
        val inManifest = Regex("android:name=\"(me\\.rerere\\.rikkahub\\.action\\.[A-Z_]+)\"")
            .findAll(V1015SourceGuard.xmlCodeOnly(manifest)).map { it.groupValues[1] }.toSet()
        val inXml = Regex("android:action=\"(me\\.rerere\\.rikkahub\\.action\\.[A-Z_]+)\"")
            .findAll(V1015SourceGuard.xmlCodeOnly(shortcuts)).map { it.groupValues[1] }.toSet()
        assertTrue("快捷方式动作为空，接线可能被整片删掉", inXml.isNotEmpty() && inManifest.isNotEmpty())
        assertTrue(
            "shortcuts.xml 发出的动作清单没注册，点了没反应：多出 ${inXml - inManifest}",
            inManifest.containsAll(inXml)
        )
        // 反向陷阱（二审计出）：两侧都用同一个前缀正则抓，前缀一旦写错就同时从两边消失、照绿。
        // 所以再把「本次必须存在的两个具体动作」逐个钉死，并在下面断言 XML 里确实出现过完整前缀。
        listOf(
            "me.rerere.rikkahub.action.TRANSLATE",
            "me.rerere.rikkahub.action.MEDIA_CREATION",
        ).forEach { full ->
            assertTrue("清单缺完整动作名 $full", V1015SourceGuard.xmlCodeOnly(manifest).contains(full))
            assertTrue("shortcuts.xml 缺完整动作名 $full", V1015SourceGuard.xmlCodeOnly(shortcuts).contains(full))
        }
    }

    @Test
    fun `每条快捷方式的 data 前缀都能在清单里找到对应注册`() {
        // 二审计出：原来只取第一条 data，后面新增的 shortcut 换 scheme 不会被发现。现在逐条比。
        val xml = V1015SourceGuard.xmlCodeOnly(shortcuts)
        val datas = Regex("android:data=\"([a-z]+)://([^\"]*)\"").findAll(xml).map {
            it.groupValues[1] to it.groupValues[2]
        }.toList()
        assertTrue("shortcuts.xml 里一条 data 都没有", datas.isNotEmpty())
        val manifestCode = V1015SourceGuard.xmlCodeOnly(manifest)
        datas.forEach { (scheme, host) ->
            assertTrue(
                "清单里没有 $scheme://$host 对应的 scheme+host 注册 —— 这条快捷方式点了没人接",
                manifestCode.contains("android:scheme=\"$scheme\"") &&
                    manifestCode.contains("android:host=\"$host\"")
            )
        }
        assertTrue(
            "我们的快捷方式必须走 rikkahubmod（与官方 App 区分，不抢它的 rikkahub://shortcut）",
            datas.any { it.first == "rikkahubmod" }
        )
    }

    @Test
    fun `路由页真的会处理媒体创建动作而不是只注册不接`() {
        val route = "app/src/main/java/me/rerere/rikkahub/RouteActivity.kt"
        assertTrue(
            "RouteActivity 缺 ACTION_MEDIA_CREATION 常量或分派 —— 注册了动作却没人接",
            V1015SourceGuard.codeHas(route, "ACTION_MEDIA_CREATION")
        )
        assertTrue(
            "openNewMediaCreation 没有被任何分派点调用（v1015 抓到的正是这种死函数）",
            V1015SourceGuard.codeOnly(V1015SourceGuard.source(route))
                .split("private fun openNewMediaCreation")
                .first().contains("openNewMediaCreation(backStack)")
        )
    }
}
