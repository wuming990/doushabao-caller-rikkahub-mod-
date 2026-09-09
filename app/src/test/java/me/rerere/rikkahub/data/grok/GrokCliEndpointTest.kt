package me.rerere.rikkahub.data.grok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v283：Grok 通道必须打官方 CLI 代理，并且自报官方客户端身份。
 *
 * 真机事故：v274~v282 的聊天请求打的是公开开发者接口 `https://api.x.ai/v1`，用户导入自己的
 * Grok 凭据后发消息，得到
 * `You have run out of credits or need a Grok subscription (type=personal-team-blocked:spending-limit)`。
 *
 * 那不是账号没订阅 —— 两个地址是两个额度池：公开接口花的是按量付费额度（要单独充钱），
 * SuperGrok / X Premium+ 订阅带的日常额度**只在 `cli-chat-proxy.grok.com` 上生效**。
 * 用户导出的凭据文件里 `base_url` 写的也正是这个代理地址；而本项目当初查额度抄对了这个地址
 * （`GrokAccountRepository` 的注释里甚至写着「not on api.x.ai」），聊天却漏了。
 *
 * 该代理还会校验调用方是不是官方客户端，缺 `x-grok-client-*` 时返回
 * `426 Your Grok CLI version (none) is outdated`。
 *
 * 这批断言钉三件事：地址不许退回公开接口、五个客户端标识头齐全、**两条通路的头必须一致**
 * （OkHttp 那条走 `grokCliClientHeaders()`，聊天那条走 `customHeaders`，值不一致就会出现
 * 「查额度正常但聊天被拒」这类极难排查的现象）。
 */
class GrokCliEndpointTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    private val providerPath =
        "app/src/main/java/me/rerere/rikkahub/data/grok/GrokProvider.kt"
    private val jsonPath =
        "app/src/main/java/me/rerere/rikkahub/data/grok/GrokJson.kt"
    private val repositoryPath =
        "app/src/main/java/me/rerere/rikkahub/data/grok/GrokAccountRepository.kt"

    @Test
    fun `地址必须是官方 CLI 代理，不能是公开开发者接口`() {
        assertEquals("https://cli-chat-proxy.grok.com/v1", GrokCliClient.BASE_URL)
        assertFalse(
            "公开接口用的是另一个（按量付费）额度池，拿 OAuth 凭据打它必然报「没额度」",
            GrokCliClient.BASE_URL.contains("api.x.ai"),
        )
    }

    @Test
    fun `聊天 模型列表 图片三条请求都走同一个地址常量`() {
        val provider = source(providerPath)
        assertTrue(
            "API_BASE 必须引用 GrokCliClient.BASE_URL，不许再写死地址",
            provider.contains("const val API_BASE = GrokCliClient.BASE_URL"),
        )
        // 注释里提到 api.x.ai 是为了留下事故记录，但代码里不许再出现它作为字符串常量
        val codeOnly = provider.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "代码里不许再出现公开接口地址",
            codeOnly.contains("\"https://api.x.ai"),
        )
    }

    @Test
    fun `查额度的地址也从同一个常量拼，两边不许再各写一份`() {
        val repository = source(repositoryPath)
        assertTrue(repository.contains("\"\${GrokCliClient.BASE_URL}/billing?format=credits\""))
        assertTrue(repository.contains("\"\${GrokCliClient.BASE_URL}/settings\""))
    }

    @Test
    fun `五个客户端标识头齐全`() {
        // 值取自用户手上提取工具导出的真实头部，不是自造形态 ——
        // Gemini 那边曾因自造 UA 被分进最差配额桶。
        assertTrue(GrokCliClient.USER_AGENT.contains("grok-pager"))
        assertTrue(GrokCliClient.USER_AGENT.contains("grok-shell"))
        assertEquals("xai-grok-cli", GrokCliClient.TOKEN_AUTH)
        assertEquals("grok-pager", GrokCliClient.CLIENT_IDENTIFIER)
        assertTrue("版本号必须是 x.y.z 形状", Regex("""^\d+\.\d+\.\d+$""").matches(GrokCliClient.CLIENT_VERSION))
        assertEquals("authenticate-response", GrokCliClient.AUTHENTICATE_RESPONSE)

        val jsonFile = source(jsonPath)
        listOf(
            "User-Agent",
            "X-XAI-Token-Auth",
            "x-grok-client-identifier",
            "x-grok-client-version",
            "x-authenticateresponse",
        ).forEach { header ->
            assertTrue(
                "grokCliClientHeaders 缺少 $header —— 代理会以 426「版本太旧」拒掉请求",
                jsonFile.contains("\"$header\""),
            )
        }
    }

    @Test
    fun `两条通路的客户端标识头必须一字不差`() {
        // OkHttp 那条（模型列表 / 图片 / 查额度）走 grokCliClientHeaders()；
        // 聊天那条走 ResponseAPI，只能通过 customHeaders 传，所以同一组值写了两遍。
        // 两边一旦不一致，就会出现「查额度正常、聊天被拒」这种极难排查的现象。
        val jsonFile = source(jsonPath)
        val provider = source(providerPath)

        val names = listOf(
            "User-Agent",
            "X-XAI-Token-Auth",
            "x-grok-client-identifier",
            "x-grok-client-version",
            "x-authenticateresponse",
        )
        val constants = listOf(
            "GrokCliClient.USER_AGENT",
            "GrokCliClient.TOKEN_AUTH",
            "GrokCliClient.CLIENT_IDENTIFIER",
            "GrokCliClient.CLIENT_VERSION",
            "GrokCliClient.AUTHENTICATE_RESPONSE",
        )

        names.zip(constants).forEach { (header, constant) ->
            assertTrue(
                "grokCliClientHeaders 里 $header 必须用 $constant，不许写死字面量",
                jsonFile.contains(".header(\"$header\", $constant)"),
            )
            assertTrue(
                "聊天的 CLI_CLIENT_HEADERS 里 $header 必须用同一个常量 $constant",
                provider.contains("CustomHeader(\"$header\", $constant)"),
            )
        }

        assertTrue(
            "聊天必须真的把这组头挂上去",
            provider.contains("customHeaders = params.customHeaders + CLI_CLIENT_HEADERS"),
        )
    }

    @Test
    fun `OAuth 登录那条路的自报身份不受影响`() {
        // 登录打的是 auth.x.ai（另一个服务，不是 CLI 代理），它的 UA 与代理无关，
        // v283 刻意没动 —— 动它等于在没有证据的情况下改一条已经能跑通登录流程的路。
        assertEquals("grok-cli", GrokOAuthManager.USER_AGENT)
        assertEquals("https://auth.x.ai", GrokOAuthManager.ISSUER)
    }
}
