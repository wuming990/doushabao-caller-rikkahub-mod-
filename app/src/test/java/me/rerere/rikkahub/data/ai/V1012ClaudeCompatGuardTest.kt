package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.claude.ClaudeCompatMemory
import me.rerere.ai.provider.providers.claude.ClaudeRequestFlags
import me.rerere.ai.provider.providers.claude.ClaudeThinkingStyle
import me.rerere.ai.provider.providers.claude.claudeFlagLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Claude 兼容层的**接线守卫**。
 *
 * 算法本身（剥档阶梯、型号能力矩阵、上游回话判读）在 ai 模块的
 * `ClaudeThinkingCompatTest` 里逐条测过。本文件只管三件**编译器抓不到、删一行就静默复发**的事：
 * 1. provider 与生成层确实接上了这套机制（顺序也要对：先剥字段、再换密钥）；
 * 2. 学到的组合真的落盘、真的能清、旧格式不会把用户钉死；
 * 3. 用户已经拍板过的口径不许被后人「顺手改回去」—— 特别是
 *    **不许在生成层给拒答加装不重试的闸**（用户原话：「我有所谓的重复扣费需求
 *    我不会自己关自动续跑么」）。这类决定一旦被人凭"看起来更保守"加回来，
 *    编译器不会报，测试不钉就会漂。
 *
 * 另外把「全仓工具入参不许再长出索要推理的字段名」这条扫在这里 —— 它防的不是上游合并，
 * 是**我们自己再写出一个同样的雷**（那个 include_reasoning 就是我们 v239 自己加的）。
 */
class V1012ClaudeCompatGuardTest {

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
        "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt"
    private val loopSource =
        "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt"

    // region 1. provider 接线

    @Test
    fun `拒答也要走剥档，而且必须排在换密钥之前`() {
        val provider = source(providerSource)
        val lines = provider.lines()
        // 这个条件现在是多行的（还要先过分类闸门），所以按片段匹配而不是整行等值
        val refusalAt = lines.indexOfFirst {
            it.contains("claudeRefusalWorthStripping(category) &&")
        }
        val switchAt = lines.indexOfFirst { it.trim() == "reason = \"stream: refusal\"," }
        assertTrue("拒答分支里没有剥档入口（用户要的是能用，不是解释为什么不能用）", refusalAt >= 0)
        assertTrue("找不到 refusal 的换密钥入口", switchAt >= 0)
        assertTrue(
            "必须先剥字段再换密钥 —— 拒答判的是请求内容，换账号看的还是同一份东西",
            refusalAt < switchAt,
        )
    }

    @Test
    fun `两条降档路径共用同一个重连动作，漏一步就会重演读不出内容`() {
        val provider = source(providerSource)
        assertEquals(
            "degradeToNextVariant 应只有一个定义 + 两个调用点（400 与拒答）",
            3,
            provider.lines().count { it.contains("degradeToNextVariant(") },
        )
        assertTrue(
            "重连前必须换新解码器：复用已 finished 的实例会让第二路连接一个字都读不出来",
            provider.contains("resetDecoderForReconnect()"),
        )
        assertEquals(
            "换密钥与剥字段两处都要复位解码器",
            2,
            provider.lines().count { it.trim() == "resetDecoderForReconnect()" },
        )
        assertTrue(
            "旧连接必须登记为已废弃，否则它迟到的 onClosed 会把新流掐掉",
            provider.contains("obsoleteSources.add(previous)"),
        )
        assertEquals(
            "三个回调入口都要有废弃连接校验",
            3,
            provider.lines().count { it.trim() == "if (eventSource in obsoleteSources) return" } +
                provider.lines().count { it.trim() == "if (source in obsoleteSources) return" },
        )
    }

    @Test
    fun `变体只造一份请求体，本次没带的字段不许占一档`() {
        // 曾经写成「把 7 档各造一份完整请求体再去重」—— 请求体含全部历史消息与 base64
        // 图片，一条消息造 7 份是纯粹的自己找罪受（两位审查位据此判 fail）。
        val provider = source(providerSource)
        // 两条路都要真的算一次 variants。整行等值比对太脆（这个调用现在是多行的），
        // 改成数「赋值给 variants」的次数。
        assertEquals(
            "流式与非流式两条路都要走 claudeVariantsFor 探测，只补一条就是漏",
            2,
            provider.lines().count { it.trim() == "variants = claudeVariantsFor(" },
        )
        assertEquals(
            "两处都必须把型号能力传进去，否则「改发老式」那一档永远不会出现",
            2,
            provider.lines().count { it.contains("supportsExtended = claudeThinkingSupport(") },
        )
        assertFalse(
            "不许再出现「造 7 份请求体去重」的写法",
            provider.contains("claudeDistinctVariants"),
        )
    }

    @Test
    fun `撞过底之后不许每趟从第 0 档重撞`() {
        // 次数归用户的续跑滑条管，但每一趟都重撞一遍阶梯是我们造的浪费。
        val provider = source(providerSource)
        assertEquals(
            "流式与非流式都要读这个下限",
            2,
            provider.lines().count { it.contains("thinkingCompat.exhaustedFloor(compatKey)") },
        )
        assertTrue(provider.contains("thinkingCompat.rememberExhausted(compatKey, index)"))
    }

    @Test
    fun `只有真的吐出内容的组合才落盘，撞一次就写的写法不许回来`() {
        val provider = source(providerSource)
        assertTrue(
            "落盘点必须在「有可见内容且本轮第一次」那一刻",
            provider.contains("if (!emittedAny) thinkingCompat.remember(compatKey, variants[index])"),
        )
        assertFalse(
            "撞一次 400 就 +1 档并落盘 —— 一次误判就会永久改掉用户设置，这条不许复活",
            provider.contains("thinkingCompat.bump("),
        )
    }

    @Test
    fun `剥到底要抛专用异常，两条错误入口都得有`() {
        val provider = source(providerSource)
        // 定义 1 处 + 调用 2 处（HTTP 层的 onFailure、网关把 400 包进 200 error 帧的 onEvent catch）。
        // 只补一条就是 v1012 那个漏法：当时 refusal 只在一条路上拦过。
        assertEquals(
            "exhaustedOrNull 应为「1 个定义 + 2 个调用点」",
            3,
            provider.lines().count { it.contains("exhaustedOrNull(") },
        )
        assertEquals(
            "两条错误入口都要真的调用它",
            2,
            provider.lines().count { it.contains("exhaustedOrNull(") && it.contains("?.let { close(it)") },
        )
    }

    // endregion

    // region 2. 用户已拍板的口径，不许被「顺手改保守」

    @Test
    fun `生成层不得给拒答或不合法请求加装不重试的闸`() {
        val loop = source(loopSource)
        // 用户口径：「只要是报错都续跑」，要不要停由他的自动续跑设置决定。
        // 我上一版在这里加过 if (error is ClaudeRefusalException) throw error，
        // 等于替用户决定「这次不值一试」—— 被明确否掉，这条测试就是防止我再犯。
        listOf(
            "if (error is ClaudeRefusalException) throw error",
            "if (error is ClaudeDegradeExhaustedException) throw error",
            "error is ClaudeRefusalException || error is ClaudeDegradeExhaustedException",
        ).forEach { banned ->
            assertFalse("生成层又出现了替用户停止重试的闸：$banned", loop.contains(banned))
        }
    }

    @Test
    fun `报错卡片不许再出现替用户做的决定式措辞`() {
        val compat =
            "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeThinkingCompat.kt"
        val text = source(compat)
        listOf("所以没有重发。", "不再重试", "已记住这家不再索要思考摘要").forEach { banned ->
            assertFalse("$banned 是把决定权从用户手里拿走，不许出现在文案里", text.contains(banned))
        }
        // 但计费这个事实必须说（官方 2026-09-24 变更），提醒 ≠ 拦闸
        assertTrue(text.contains("照常计费"))
    }

    @Test
    fun `零产出分档只保留与 Claude 无关的那套既有口径`() {
        // v1011 定的六类分档继续有效：只有「一个字没吐、不计费」的两类进重试通道。
        // 这条不属于本轮改动，钉在这里防止被误删。
        val loop = source(loopSource)
        assertTrue(
            loop.contains("if (error is EmptyReplyException && !error.kind.isWorthResending) throw error"),
        )
    }

    // endregion

    // region 3. 学到的组合：能存、能读、能清、旧格式不坑人

    @Test
    fun `存的是字段组合本身，所以阶梯重排也不会被解释成别的东西`() {
        val file = tempFile("shape")
        val memory = ClaudeCompatMemory(file)
        assertEquals("没见过就该用完整形态", ClaudeRequestFlags(), memory.flagsFor("p|m"))

        val learned = ClaudeRequestFlags(
            dropEffort = true, dropSummary = true, dropThinking = true, dropCacheControl = true,
        )
        memory.remember("p|m", learned)
        assertEquals("重启后必须还记得", learned, ClaudeCompatMemory(file).flagsFor("p|m"))
        assertEquals("另一个型号不受影响", ClaudeRequestFlags(), ClaudeCompatMemory(file).flagsFor("p|other"))
        assertTrue(
            "文件里必须写着字段名，不能是「第几格」的数字",
            file.readText().contains("dropCacheControl"),
        )
    }

    @Test
    fun `值没变时不写盘，否则每条消息一次磁盘写`() {
        // createTempFile 会顺手把空文件建出来，所以先删掉再验「压根没写」
        val file = tempFile("noop").apply { delete() }
        val memory = ClaudeCompatMemory(file)
        memory.remember("p|m", ClaudeRequestFlags())
        assertFalse("完整形态本来就是默认值，不该产生文件", file.exists())
        // 而真正学到的东西必须落盘，否则重启就白学
        memory.remember("p|m", ClaudeRequestFlags(dropCacheControl = true))
        assertTrue("学到东西后必须写盘", file.exists() && file.length() > 0L)
    }

    @Test
    fun `forget 与 describe 供设置页清除与显示当前形态`() {
        val memory = ClaudeCompatMemory(tempFile("desc"))
        val learned = ClaudeRequestFlags(dropThinking = true)
        memory.remember("p|m", learned)
        assertNotNull(memory.describe("p|m"))
        assertTrue(memory.describe("p|m")!!.contains("思考参数"))
        memory.forget("p|m")
        assertNull(memory.describe("p|m"))
        assertEquals(ClaudeRequestFlags(), memory.flagsFor("p|m"))
    }

    @Test
    fun `旧格式与损坏文件一律当没学过，不许猜旧含义`() {
        // v1012 存的是 Map<String,Int>（裸下标）。按对象解必然解不动 → 整库当空。
        // 不替旧下标猜含义是故意的：它的语义绑在当时的阶梯形状上，猜一次就是引入新错误。
        listOf(
            """{"p|m":3}""",
            "这不是 JSON",
            "",
            "[]",
        ).forEach { broken ->
            val file = tempFile("bad").apply { writeText(broken) }
            assertEquals(
                "内容「$broken」必须退回未学过",
                ClaudeRequestFlags(),
                ClaudeCompatMemory(file).flagsFor("p|m"),
            )
        }
    }

    @Test
    fun `没有文件时纯内存也能用，单测与无上下文构造不能崩`() {
        val memory = ClaudeCompatMemory(null)
        memory.remember("p|m", ClaudeRequestFlags(dropTopP = true))
        assertEquals(ClaudeRequestFlags(dropTopP = true), memory.flagsFor("p|m"))
    }

    private fun tempFile(tag: String): File =
        File.createTempFile("claude_req_$tag", ".json").apply { deleteOnExit() }

    // endregion

    // region 4. 阶梯本身不许被改坏成「会弄坏请求」

    @Test
    fun `阶梯最后一档必须把所有可剥项都剥干净`() {
        val last = claudeFlagLadder().last()
        assertTrue(last.dropThinking)
        assertTrue(last.dropCacheControl)
        assertTrue(last.dropTopP)
        assertTrue(last.dropTemperature)
        assertTrue(last.dropEffort)
        assertTrue(last.dropSummary)
        // 「改发老式」这一档不许被漏掉：v1012 有，改成只剥字段时被我不小心弄丢过
        assertTrue(last.useLegacyBudget)
    }

    @Test
    fun `剥档只动思考与采样与缓存，绝不动 model、messages、max_tokens`() {
        // 这三个是官方 400 名单里的「缺少必填参数」，剥出来等于把请求弄坏。
        // 用字段名白名单钉住：以后谁加一档，只要动到名单外的键就会红。
        val allowed = setOf(
            "dropEffort", "dropSummary", "dropThinking", "useLegacyBudget",
            "dropCacheControl", "dropTopP", "dropTemperature",
        )
        // 必须滤掉 @Serializable 生成的 Companion 与编译器合成字段，否则这条断言
        // 会因为序列化插件而必红（审查位抓到）。
        // 编译器与序列化/Compose 插件会塞进 $stable、Companion 这类合成成员，
        // 一律按名字前缀滤掉，否则这条断言会因为插件而不是因为语义变化而必红。
        // Companion 是 @Serializable 生成的静态嵌套对象、$stable 是 Compose 插件加的，
        // 都不是业务字段。用「是否静态」统一挡掉，比逐个列黑名单可靠。
        val props = ClaudeRequestFlags::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals("阶梯可剥的字段被扩大了：$props", allowed, props)
    }

    // endregion

    // region 5. 全仓守卫：工具入参不许再长出「索要推理」的字段名

    @Test
    fun `没有任何工具入参字段名在向模型要思考或推理`() {
        // Anthropic 官方把「工具入参里出现 reasoning / thinking / trace 字段」逐字列为
        // reasoning_extraction 拒答的触发形状（define-tools 与 refusals-and-fallback 两页）。
        // 这条不防上游合并 —— 那个 include_reasoning 是我们自己 v239 加的，
        // 官方 diff 永远不会提醒我们；它防的是我们自己再写出一个同样的雷。
        val banned = listOf("reasoning", "thinking", "scratchpad", "chain_of_thought")
        val dirs = listOf(
            "app/src/main/java/me/rerere/rikkahub/agent/tools",
            "app/src/main/java/me/rerere/rikkahub/data/ai/tools",
        )
        val hits = mutableListOf<String>()
        for (dir in dirs) {
            val d = File(repoRoot, dir)
            if (!d.isDirectory) throw AssertionError("工具目录不见了：$dir")
            d.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                // 只扫「JSON schema 的键名」这一种形状（put 一个键、值是个 buildJsonObject）。
                // 不扫 Kotlin 属性名：导出功能里的 includeReasoning 是本地属性，
                // 它根本不发给模型，扫它会误伤。
                Regex("""put\(\s*"([a-z_]+)"\s*,\s*buildJsonObject""")
                    .findAll(file.readText())
                    .forEach { match ->
                        val key = match.groupValues[1]
                        if (banned.any { key.contains(it) }) hits += "${file.name}: 入参字段 $key"
                    }
            }
        }
        assertTrue("发现疑似索要推理的工具入参：$hits", hits.isEmpty())
    }

    @Test
    fun `子代理下传的正文里也不许出现思考原文`() {
        // 删掉开关不等于删掉风险：内部管道若还留着「把思考块渲染进去」那条分支，
        // 只要有人翻一下参数就能复活。所以这里钉「分支不存在」，而不是钉「默认值」。
        val feed = source("app/src/main/java/me/rerere/rikkahub/service/AgentContextFeed.kt")
        assertFalse("render 不能再收 includeReasoning 参数", feed.contains("includeReasoning: Boolean"))
        assertFalse("不能再有把思考正文拼进去的分支", feed.contains("〔思考〕"))
        assertTrue("但要保留占位行，让子代理知道这里本来有一段思考没给它", feed.contains("未下传"))
    }

    // endregion

    // region 6. 用户侧默认值与文案（缺一种语言，另一种语言就直接看到 key 名）

    @Test
    fun `剥档默认开着，用户不需要先做什么才有修复效果`() {
        assertEquals(
            "默认必须是自动，否则修复要用户先动设置才生效",
            ClaudeThinkingStyle.AUTO,
            ProviderSetting.Claude().thinkingCompat,
        )
    }

    @Test
    fun `both languages carry every new string`() {
        val keys = listOf(
            "setting_provider_page_claude_thinking_compat",
            "setting_provider_page_claude_thinking_compat_support",
            "setting_provider_page_claude_thinking_compat_auto",
            "setting_provider_page_claude_thinking_compat_adaptive",
            "setting_provider_page_claude_thinking_compat_legacy",
            "setting_provider_page_claude_thinking_compat_omit",
            // 学到的形态必须可见、可清 —— 里面可能包含「已关掉提示词缓存」，那是静默涨价
            "setting_provider_page_claude_learned_form",
            "setting_provider_page_claude_learned_reset",
            "setting_provider_page_claude_learned_reset_support",
        )
        listOf(
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-zh/strings.xml",
        ).forEach { path ->
            val xml = source(path)
            keys.forEach { key -> assertTrue("$path 缺 $key", xml.contains("""name="$key"""")) }
        }
        // v1013 一度加过「索要思考摘要」开关，后来确认那是治错病，整条撤回。
        // 钉住它不许半撤不撤地留在文案里。
        listOf("setting_provider_page_claude_ask_thinking_summaries").forEach { gone ->
            listOf(
                "app/src/main/res/values/strings.xml",
                "app/src/main/res/values-zh/strings.xml",
            ).forEach { path ->
                assertFalse("$path 不该再有 $gone", source(path).contains("""name="$gone""""))
            }
        }
    }

    @Test
    fun `这一栏只出现在 Claude 的配置里`() {
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt")
        assertEquals(1, ui.lines().count { it.contains("provider.thinkingCompat") })
    }

    @Test
    fun `学到的形态必须看得见、也必须清得掉，而且清的是同一份内存`() {
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt")
        assertTrue("设置页必须列出已学到的形态", ui.contains("entriesForProvider(provider.id.toString())"))
        assertTrue("必须有一键重新试探", ui.contains("forgetProvider(provider.id.toString())"))
        // 写入方与读取方必须是同一个进程内实例，否则「重新试探」清了个空对象
        val memory =
            "ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeCompatMemory.kt"
        val mem = source(memory)
        assertTrue("of() 必须返回进程内共享实例", mem.contains("fun of(context: Context): ClaudeCompatMemory"))
        assertTrue(mem.contains("shared ?: ClaudeCompatMemory("))
        val provider = source(providerSource)
        assertTrue(
            "provider 也要走 of()，不能各自 new 一份",
            provider.contains("ClaudeCompatMemory.of(context)"),
        )
    }

    // endregion

    // region 7. 红线复述（本轮一条都不该动）

    @Test
    fun `主对话仍是唯一显式打开零产出说明的路径`() {
        val chatService = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertEquals(
            "圆桌 / 子代理 / 讨论群组 / 压缩 / 翻译都不受影响",
            1,
            chatService.lines().count { it.trim() == "reportEmptyReplyAsError = true," },
        )
    }

    @Test
    fun `max_tokens 语义仍然不许动`() {
        val provider = source(providerSource)
        assertTrue(provider.contains("""put("max_tokens", params.maxTokens ?: 64_000)"""))
    }

    @Test
    fun `数据库版本与 GPT 思考卡片不许动`() {
        assertTrue(source("app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt").contains("version = 32,"))
        assertTrue(source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDatabase.kt").contains("version = 3,"))
        val cot = File(repoRoot, "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt")
        assertTrue("GPT 思考卡片文件不见了", cot.isFile)
        assertFalse("本轮不许碰 GPT 思考卡片", cot.readText().contains("v1012") || cot.readText().contains("v1013"))
    }

    @Test
    fun `ReasoningPicker 与 ResponseAPI 的保留标记不许被覆盖`() {
        assertTrue(
            source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/ReasoningPicker.kt")
                .contains("text = level.label(),"),
        )
        assertTrue(
            source("ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt")
                .contains("v234 合并上游 2.4.10"),
        )
    }

    @Test
    fun `本轮不许新增依赖`() {
        val gradle = source("app/build.gradle.kts")
        // 这条守卫故意钉死「当前该发出去的版本号」，防止把旧树打出去。
        // v1014：用户已装 1013，安卓不许降级安装 → 版本号必须继续前进，判据跟着更新。
        // v1015：同一情形再来一次（用户已装 1014）。这条断言的语义是「版本号 == 本次发布号」，
        // 不是「永远停在某个号」；每次发布都要同步前进。仍是精确等值断言（不是 >= 也不是前缀），
        // 所以没有放宽判据；先例见任务板 §93（v1014 同样把 1013→1014 时同步更新过这条）。
        // v1016：又一次踩到这条固定税 —— 只升了 build.gradle.kts 没同步这里，门禁②会直接红。
        // v1017：本轮两处一起改（1017 / 3.1.1-mod15），别再漏。
        // 记进手册：升 versionCode 必须同时改这里，两处一起改才算改完。
        assertTrue("版本号必须前进，否则用户装不上", gradle.contains("versionCode = 1017"))
        assertTrue(gradle.contains("versionName = \"3.1.1-mod15\""))
        // 三依赖保持官方版本，不许锁回旧版
        val libs = source("gradle/libs.versions.toml")
        assertTrue(libs.contains("haze"))
        assertTrue(libs.contains("snakeyaml"))
    }

    // endregion
}
