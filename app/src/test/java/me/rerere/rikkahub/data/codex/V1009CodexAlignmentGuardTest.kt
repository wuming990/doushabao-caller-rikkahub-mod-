package me.rerere.rikkahub.data.codex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1009：Codex 通道必须走官方新版 ChatGPT 那条实现，不许再自己拼 Codex 请求。
 *
 * 背景（真机 2026-10）：登录成功、模型列表能拉到，一发消息就
 * `401 We got your request, but your ChatGPT login did not make it to this service.`
 * 根因是 v274 那套自拼请求（固定 codex_cli_rs/0.144.5 的 UA、自加 OpenAI-Beta、
 * 用通用 Responses 能力集、没有 session-id）和官方当前客户端对不上。
 *
 * 这个守卫只钉「接线」：源码里必须出现对 [me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider]
 * 的调用，并且不许再出现自拼请求用的 ResponseAPI / CODEX_API_BASE。
 * 它是源码文本守卫，不证明真机能通；真机由用户验收。
 *
 * v1009 审查位补充的三条（都在这里钉住）：
 * - 首次 401 不许标失效，只有「刷新后仍 401」才可以（判据是 `priorResponse != null`）；
 * - 401 重试必须走 `refreshToken`（只换票、不掺用量抓取）；
 * - MAX 思考档必须降成 XHIGH，否则 `effort:"max"` 会被 Codex 后端拒。
 */
class V1009CodexAlignmentGuardTest {

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

    private val providerSource: String
        get() = source("app/src/main/java/me/rerere/rikkahub/data/codex/CodexProvider.kt")

    private val chatServiceSource: String
        get() = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")

    @Test
    fun `Codex 的三条主路径都交给官方 ChatGPTProvider`() {
        val text = providerSource
        assertTrue("必须构造官方 ChatGPTProvider", text.contains("ChatGPTProvider("))
        assertTrue("listModels 必须走官方通道", text.contains("chatGpt.listModels("))
        assertTrue("streamText 必须走官方通道", text.contains("chatGpt.streamText("))
        assertTrue("generateText 必须走官方通道", text.contains("chatGpt.generateText("))
    }

    @Test
    fun `Codex 不许再自拼 Requests Responses 请求`() {
        val text = providerSource
        assertFalse("不许再直接造 ResponseAPI", text.contains("ResponseAPI("))
        assertFalse("不许再写死 Codex 接口前缀", text.contains("CODEX_API_BASE"))
    }

    @Test
    fun `账号池与额度记账这层壳还在`() {
        val text = providerSource
        assertTrue("必须仍然从账号仓库取号", text.contains("repository.acquireAccount()"))
        assertTrue("必须仍然记额度", text.contains("repository.updateUsage("))
        assertTrue(
            "401 重试必须只换票（refreshToken），不许用会顺带抓用量的 refreshAccount",
            text.contains("repository.refreshToken(current.id)"),
        )
        assertFalse(
            "401 重试不许用 refreshAccount —— 用量接口一失败就会退回旧票，等于白重试",
            text.contains("repository.refreshAccount(current.id"),
        )
    }

    @Test
    fun `首次 401 不标失效，只有刷新后仍 401 才退休账号`() {
        val text = providerSource
        assertTrue("必须保留 401 的失效判定", text.contains("repository.markInvalid(account.id)"))
        assertTrue(
            "判据必须是「刷新后仍 401」（priorResponse != null），否则会把健康账号打出轮换池",
            text.contains("response.priorResponse != null"),
        )
    }

    @Test
    fun `MAX 思考档必须降成 xhigh 再发`() {
        val text = providerSource
        assertTrue("委托前必须过 codexSafe()", text.contains("params.codexSafe()"))
        assertTrue("降档判据必须存在", text.contains("codexSafeReasoningLevel("))
    }

    @Test
    fun `开批时把点名第一位真的落进 run（ChatService 接线）`() {
        // 审查位 v1009 抓的漏测：只测 GroupChatRun 的话，ChatService 忘了传 firstSpeaker 也照样绿。
        val text = chatServiceSource
        assertTrue(
            "startGroupDiscussion 必须在 beginRun 成功后落名字",
            text.contains("if (firstSpeaker != null) run.requestSpeaker(firstSpeaker)"),
        )
        assertTrue(
            "requestGroupSpeakerNow 开批时必须把点名的模型传进去",
            text.contains("startGroupDiscussion(conversationId, firstSpeaker = modelId)"),
        )
    }
}
