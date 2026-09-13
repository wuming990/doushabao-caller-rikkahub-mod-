package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v300：商汤 + kimi 屏蔽「询问用户」的判定真单测（不是读源码做字符串断言）。
 *
 * 背景：真机两轮取证确认，商汤网关在 kimi 模型上会把 ask_user 的 options 数组整段吃掉
 * —— 题干正常显示、选项一个都看不到，用户只能看自由文本框，等于「问了等于没问还被卡住」。
 * 用户拍板：这个组合不提供该工具；其它渠道、商汤的其它模型行为一字不变。
 *
 * 判定必须**精确到主机名**，不能用 contains —— 否则
 * `token.sensenova.cn.evil.com` 这种会被误判成商汤。
 */
class AskUserSupportTest {

    @Test
    fun `商汤加kimi小写_屏蔽`() {
        assertTrue(isAskUserUnsupported("token.sensenova.cn", "kimi-k3"))
    }

    @Test
    fun `商汤加kimi大写_同样屏蔽`() {
        assertTrue(isAskUserUnsupported("token.sensenova.cn", "Kimi-K3"))
    }

    @Test
    fun `商汤加deepseek_不屏蔽`() {
        assertFalse(isAskUserUnsupported("token.sensenova.cn", "deepseek-v4-pro"))
    }

    @Test
    fun `商汤加glm_不屏蔽`() {
        assertFalse(isAskUserUnsupported("token.sensenova.cn", "glm-5.2"))
    }

    @Test
    fun `其它渠道的kimi_不屏蔽`() {
        assertFalse(isAskUserUnsupported("api.moonshot.cn", "kimi-k3"))
    }

    @Test
    fun `看着像商汤其实是别的域名_不屏蔽`() {
        assertFalse(isAskUserUnsupported("token.sensenova.cn.evil.com", "kimi-k3"))
        assertFalse(isAskUserUnsupported("not-token.sensenova.cn", "kimi-k3"))
    }

    @Test
    fun `拿不到供应商或模型_一律不屏蔽`() {
        assertFalse(isAskUserUnsupported(null, "kimi-k3"))
        assertFalse(isAskUserUnsupported("token.sensenova.cn", null))
        assertFalse(isAskUserUnsupported(null, null))
    }

    // ---- providerBaseUrlOf：主机名只能按子类取 ----
    // ProviderSetting 是密封类，基类没有 baseUrl；只有 OpenAI/Google/Claude 有，
    // Codex/Grok/GeminiOAuth 是账号登录通道、本来就没有自定义主机名。
    // 这两组用例钉住「账号登录类必须落 null」，防止以后有人写成基类成员访问。

    @Test
    fun `有自定义地址的三个子类_原样取到各自 baseUrl`() {
        // providerBaseUrlOf 返回的是「用户填的完整地址」，主机名由 providerHostOf 再解析，
        // 所以这里断言完整地址（写成域名会红 —— 本用例第一版就是这么写错的）。
        assertEquals(
            "https://token.sensenova.cn/v1",
            providerBaseUrlOf(ProviderSetting.OpenAI(baseUrl = "https://token.sensenova.cn/v1"))
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            providerBaseUrlOf(ProviderSetting.Google(baseUrl = "https://generativelanguage.googleapis.com/v1beta"))
        )
        assertEquals(
            "https://api.anthropic.com/v1",
            providerBaseUrlOf(ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1"))
        )
    }

    @Test
    fun `账号登录类供应商_没有主机名_返回空`() {
        assertEquals(null, providerBaseUrlOf(ProviderSetting.Codex()))
        assertEquals(null, providerBaseUrlOf(ProviderSetting.Grok()))
        assertEquals(null, providerBaseUrlOf(ProviderSetting.GeminiOAuth()))
        assertEquals(null, providerBaseUrlOf(null))
    }
}
