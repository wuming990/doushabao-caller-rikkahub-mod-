package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1031 守卫：Gemini「反重力账号登录」这条通道的三件事必须同时成立 ——
 * ① 目录里不能混进必 404 的 `-disabled` 占位条目；
 * ② 裸名 `gemini-4-argon` 要补成官方默认档 `-medium`，已带档位的 id 一字不改；
 * ③ 拉取失败必须看得见、可重试，而且协程取消不能被当成失败吞掉。
 *
 * 全部按「真实判据」钉，不用注释满足：目录解析走私有函数 `mapAvailableModels`，
 * 与 [GeminiProviderAvailableModelsTest] 一样用反射打 `GeminiProviderKt` 门面；
 * 接线部分读源码原文（跨行写法一律用容忍换行的正则，见手册 §10 关于源码断言两类假失败）。
 */
class V1031AntigravityCatalogTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")
    private val json = Json { ignoreUnknownKeys = true }

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

    private val providerSource =
        "app/src/main/java/me/rerere/rikkahub/data/gemini/GeminiProvider.kt"
    private val repositorySource =
        "app/src/main/java/me/rerere/rikkahub/data/gemini/GeminiAccountRepository.kt"
    private val detailPageSource =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt"

    private fun mapModels(body: String): List<Model> {
        val method = geminiProviderKt.getDeclaredMethod(
            "mapAvailableModels",
            String::class.java,
            Json::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, body, json) as List<Model>
    }

    // region 1. 目录过滤

    @Test
    fun `v1031 disabled 占位条目不进目录 正常档位照常给出`() {
        val body = """
            {
              "models": {
                "gemini-4-argon-disabled": {"displayName": "Gemini 4 Argon"},
                "gemini-4-argon-low": {"displayName": "Gemini 4 Argon (Low)"},
                "gemini-4-argon-medium": {"displayName": "Gemini 4 Argon (Medium)"},
                "gemini-4-argon-high": {"displayName": "Gemini 4 Argon (High)"}
              }
            }
        """.trimIndent()
        val ids = mapModels(body).map { it.modelId }
        assertFalse("占位条目漏进目录: $ids", ids.contains("gemini-4-argon-disabled"))
        assertEquals(
            listOf("gemini-4-argon-high", "gemini-4-argon-low", "gemini-4-argon-medium"),
            ids.sorted(),
        )
    }

    @Test
    fun `v1031 占位判据覆盖下划线形态与大小写`() {
        assertTrue(isDisabledPlaceholderModelId("gemini-4-argon-disabled"))
        assertTrue(isDisabledPlaceholderModelId("gemini-4-argon-disabled-preview"))
        assertTrue(isDisabledPlaceholderModelId("Gemini-4-Argon_DISABLED"))
        assertFalse(isDisabledPlaceholderModelId("gemini-4-argon-high"))
        assertFalse(isDisabledPlaceholderModelId("gemini-3.8-flash-medium"))
    }

    @Test
    fun `v1031 普通条目上的 disabled 标志不挡 证据冲突时照谷歌返回`() {
        // magpie #3339 说普通 id 上的 disabled:true 是「模型在、只是这个会话没开」，仍应列出；
        // oh-my-pi 则连标志一起跳。两边互相矛盾，所以本轮不自造黑名单，只挡必 404 的 id 形态。
        val body = """
            {
              "models": {
                "gemini-3.8-flash-high": {"displayName": "Gemini 3.8 Flash (High)", "disabled": true},
                "gemini-4-argon-disabled": {"displayName": "Gemini 4 Argon"}
              }
            }
        """.trimIndent()
        val ids = mapModels(body).map { it.modelId }
        assertEquals(listOf("gemini-3.8-flash-high"), ids)
    }

    @Test
    fun `v1031 旧的 isInternal 与 pro-high 两条过滤没有被替换掉`() {
        val body = """
            {
              "models": {
                "gemini-internal-test": {"displayName": "Internal", "isInternal": true},
                "gemini-3.1-pro-high": {"displayName": "Gemini 3.1 Pro (High)"},
                "gemini-3.1-pro-low": {"displayName": "Gemini 3.1 Pro (Low)"}
              }
            }
        """.trimIndent()
        assertEquals(listOf("gemini-3.1-pro-low"), mapModels(body).map { it.modelId })
        assertEquals(setOf("gemini-3.1-pro-high"), DENIED_MODEL_IDS)
    }

    // endregion

    // region 2. 裸名补档位

    @Test
    fun `v1031 裸名 Argon 补成官方默认档 medium`() {
        // aistudio 的 Argon 开发者指南写 default thinking = medium；jcode PR #1834 同样映射 medium。
        assertEquals("gemini-4-argon-medium", antigravityWireModelId("gemini-4-argon"))
    }

    @Test
    fun `v1031 已经带档位的 id 一字不改`() {
        listOf(
            "gemini-4-argon-low",
            "gemini-4-argon-medium",
            "gemini-4-argon-high",
            "gemini-4-argon-disabled",
        ).forEach { assertEquals(it, antigravityWireModelId(it)) }
    }

    @Test
    fun `v1031 其他家族的裸名不动 不改现在能用的东西`() {
        // 没有公开证据证明这些裸名会 404；给能用的通道改行为就是制造新故障。
        listOf(
            "gemini-3.8-flash",
            "gemini-3.1-pro",
            "gemini-3.1-pro-low",
            "claude-sonnet-5-5",
            "claude-opus-5-5-high",
            "gpt-oss-120b",
        ).forEach { assertEquals(it, antigravityWireModelId(it)) }
    }

    @Test
    fun `v1031 请求体的 model 字段用的是补档位后的 id`() {
        val body = source(providerSource)
        assertTrue(
            "streamText 没有先把裸名补档位",
            Regex("""val\s+wireModelId\s*=\s*antigravityWireModelId\(\s*params\.model\.modelId\s*\)""")
                .containsMatchIn(body),
        )
        assertTrue(
            "请求体 model 字段还在直接发用户填的名字",
            Regex("""put\(\s*"model"\s*,\s*wireModelId\s*\)""").containsMatchIn(body),
        )
    }

    // endregion

    // region 3. 客户端身份与失败可见化

    @Test
    fun `v1031 全链路只有一个自报身份 没有第二处硬编码 UA`() {
        val body = source(repositorySource)
        val viaConst = Regex("""\.header\(\s*"User-Agent"\s*,\s*ANTIGRAVITY_USER_AGENT\s*\)""")
            .findAll(body).count()
        assertEquals("身份头必须恰好一处由常量提供", 1, viaConst)
        // 注释里提到字面量不算（手册 §10 的假失败教训），所以只认调用语法。
        assertFalse(
            "不许再出现第二处写死的 antigravity UA",
            Regex("\\.header\\(\\s*\"User-Agent\"\\s*,\\s*\"").containsMatchIn(body),
        )
    }

    @Test
    fun `v1031 拉取失败与加载状态对所有供应商可见 不再只给网页版`() {
        val body = source(detailPageSource)
        assertTrue(
            "加载中提示仍被网页版条件挡住",
            Regex("""if\s*\(\s*modelsLoading\s*\)\s*\{""").containsMatchIn(body),
        )
        assertTrue(
            "失败原因仍被网页版条件挡住",
            Regex("""if\s*\(\s*modelLoadError\s*!=\s*null\s*\)\s*\{""").containsMatchIn(body),
        )
        assertFalse(
            "网页版专属条件又回来了",
            Regex("""if\s*\(\s*showDisplayNames\s*&&\s*(modelsLoading|modelLoadError)""")
                .containsMatchIn(body),
        )
    }

    @Test
    fun `v1031 重新拉取按钮对所有供应商都能触发重取`() {
        val body = source(detailPageSource)
        assertTrue(
            "重试仍只对网页版生效",
            Regex("""onRetryModels\s*=\s*\{\s*webRetryKey\s*\+=\s*1\s*}""").containsMatchIn(body),
        )
        assertFalse(
            "重试条件里又混进了供应商判断",
            Regex("""onRetryModels\s*=\s*\{\s*if\s*\(\s*providerSetting""").containsMatchIn(body),
        )
    }

    @Test
    fun `v1031 账号通道拉取失败不再静默 且取消异常排在最前`() {
        val body = source(detailPageSource)
        assertTrue(
            "账号通道仍在用 println 或 printStackTrace 掩盖失败",
            Regex("""println\(\s*"loading models""").containsMatchIn(body).not(),
        )
        val cancelIdx = body.indexOf("catch (e: CancellationException)")
        val exceptionIdx = body.indexOf("catch (e: Exception)")
        assertTrue("缺少协程取消的原样上抛", cancelIdx >= 0)
        assertTrue(
            "CancellationException 必须排在 Exception 之前，否则永远走不到（v281 血泪）",
            exceptionIdx < 0 || cancelIdx < exceptionIdx,
        )
        assertTrue(
            "失败原因没有落到界面上",
            Regex("""webLoadError\s*=\s*failure\?\.let""").containsMatchIn(body),
        )
    }

    // endregion
}
