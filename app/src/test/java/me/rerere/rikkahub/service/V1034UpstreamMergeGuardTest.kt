package me.rerere.rikkahub.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * v1034 守卫：兼容官方 3.2.0 之后，几条**必须长期成立**的接线。
 *
 * 背景（用户原话）：「以后合并上游新版本时，必须按这个流程执行，一步都不能省
 * （上次兼容差点把二改软件自己做的功能覆盖掉）」。本文件钉住这一轮最容易复发的四类问题：
 * ① 官方新功能被照抄成「另一套压缩」，把用户在二改里做的选择作废；
 * ② 官方新设置字段只进写路径、没进我们的手写读路径（v1004 / v323 同族事故）；
 * ③ 广告位借官方更新偷偷回来（用户明确要求去广告、不影响原主）；
 * ④ 中途压缩默认关闭、按用户模式执行，只在工具结果收完后接检查点。
 */
class V1034UpstreamMergeGuardTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    private val chatService = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
    private val prefs = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
    private val settingsKt = source("app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt")
    private val generalPage = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt")
    private val defaults = source("app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt")
    private val recommended = source("app/src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt")

    /**
     * 取某个函数的函数体（**含签名行**），到「缩进回到 4 空格且不是 }」的下一段为止。
     *
     * ⚠️ 这里踩过坑（v1034 首轮 6 条判据全红）：切片从签名行开始，而第一版过滤条件要求
     * 行首有缩进 —— 签名行本身没有缩进，takeWhile 第一轮就停，函数体取成空串，
     * 于是"怎么读都是空、怎么改都是红"。任何切片写法都必须自己先验非空。
     */
    private fun bodyOf(src: String, signature: String, label: String): String {
        val i = src.indexOf(signature)
        assertTrue("$label：找不到 $signature", i >= 0)
        val lines = src.substring(i).split("\n")
        val rest = lines.drop(1).takeWhile { it.isBlank() || it.startsWith("        ") || it == "    }" }
        val body = lines[0] + "\n" + rest.joinToString("\n")
        assertTrue("$label：切片为空（写法又出问题了）", body.length > signature.length + 40)
        return body
    }

    private val autoCompressBody: String by lazy {
        bodyOf(chatService, "private fun launchAutoCompressIfNeeded", "自动压缩触发函数")
    }

    @Test
    fun `v1034 自动压缩开关默认必须是关闭`() {
        assertTrue(
            "官方 3.2.0 的 enableAutoCompress 默认必须是 false：默认打开等于擅自改变用户现有体验",
            Regex("""val\s+enableAutoCompress:\s*Boolean\s*=\s*false""").containsMatchIn(settingsKt),
        )
    }

    @Test
    fun `v1034 官方新设置字段必须写读成对（我们的读取是逐字段手写的）`() {
        for (key in listOf("ENABLE_AUTO_COMPRESS", "AUTO_COMPRESS_THRESHOLD")) {
            assertTrue("$key 缺键定义", prefs.contains("val $key = "))
            assertTrue("$key 缺整份落盘（备份恢复路径）写入", prefs.contains("preferences[$key] = "))
            assertTrue("$key 缺手写读路径：只写不读会把用户的开关打回默认，变成假开关",
                Regex("""= \(?preferences\[$key\]""").containsMatchIn(prefs))
        }
    }

    @Test
    fun `v1034 触发点必须真接在回复结束处，不许做成假开关`() {
        val body = bodyOf(chatService, "private fun onSessionGenerationFinished", "回复结束收尾函数")
        assertTrue("回复结束后没有调用 launchAutoCompressIfNeeded（官方的触发时机被丢了）",
            body.contains("launchAutoCompressIfNeeded(session)"))
        assertTrue("上一轮失败/被取消时不该压：缺 cause == null 判据", body.contains("cause == null"))
    }

    @Test
    fun `v1034 自动压缩必须走我们已有的两条压缩，不许另起官方那套`() {
        assertTrue("没有跟随用户的压缩模式二选一", autoCompressBody.contains("settings.compressUseClassicMode"))
        assertTrue("经典分支没走我们带用户原话保护的那条压缩",
            autoCompressBody.contains("startManualClassicCompression("))
        assertTrue("智能分支没走我们那条压缩", autoCompressBody.contains("startManualCompression("))
        // 官方照抄版会走它自己的检查点压缩：那条不读我们的压缩模型、也不带原话账本
        // ⚠️ 判据必须查**调用形态**（带左括号），不能查裸标识符：v1034 首轮就被自己坑了两次 ——
        // 一处在 ChatService 的 KDoc 里写了「为什么不照抄官方的 insertCompressionCheckpoint」，
        // 另一处在别的注释里提到 session.isCompressing；裸标识符判据把注释也算成命中，
        // 白报"功能被覆盖"。（本项目老规矩：注释也参与计数，改写法要先看谁盯着这行字。）
        for (banned in listOf("insertCompressionCheckpoint(", "compressMidTurn(", "launchCompressionJob(")) {
            assertFalse("出现了官方那套压缩执行路径调用 $banned（会把用户的压缩设置作废）", chatService.contains(banned))
        }
    }

    @Test
    fun `v1034 分派到两条压缩时必须把我们那些设置原样带过去`() {
        assertTrue("经典分支丢了「用户发言保护」",
            autoCompressBody.contains("protectUserMessages = settings.compressClassicProtectUserMessages"))
        assertTrue("智能分支丢了固定压缩模型来源",
            autoCompressBody.contains("modelSource = CompressionModelSource.FIXED"))
    }

    @Test
    fun `v1034 阈值判据拿不到真实占用时绝不能压`() {
        val body = bodyOf(chatService, "private fun reachesAutoCompressThreshold", "阈值判据函数")
        assertTrue("缺「读不到占用就不压」的守卫", body.contains("contextTokens == null"))
        assertTrue("缺「模型没声明窗口长度就不压」的守卫", body.contains("model.contextLength <= 0"))
        assertTrue("阈值没跟随用户设置", body.contains("settings.autoCompressThreshold"))
    }

    @Test
    fun `v1034 同一条回复只许试一次自动压缩`() {
        assertTrue("缺 autoCompressedReplyId 去重：压缩失败会每轮反复重压",
            autoCompressBody.contains("reply.id == session.autoCompressedReplyId"))
        assertTrue("没有把这条回复登记为已试过", autoCompressBody.contains("session.autoCompressedReplyId = reply.id"))
    }

    @Test
    fun `v1034 插队判据换成官方统一写法但保留我们更严的工具判据`() {
        val body = bodyOf(chatService, "private fun dispatchInbox", "收件箱派发函数")
        assertTrue("没用官方统一的 canStartNextTurn", body.contains("session.canStartNextTurn"))
        assertTrue("我们那条更严的「任何待处理工具都不插队」判据被放宽了",
            body.contains("it is UIMessagePart.Tool && it.isPending"))
        assertFalse("旧的压缩中标记判据复活（会话里这个字段官方已删，留着就编译不过）",
            chatService.contains("session.isCompressing"))
    }

    /** 用户选定 B 方案：本轮就把官方 3.2.0 的「中途压缩」接完。以下 8 条钉住接线完整性。 */

    @Test
    fun `v1034 生成循环钩子默认必须是不压`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(
            "pauseAfterTools 默认值不是 { false } —— 默认值一旦为真，主对话每轮都会停下来压缩，等于擅自改变现有行为",
            loop.contains("pauseAfterTools: suspend (UIMessage) -> Boolean = { false },"),
        )
        assertTrue(
            "钩子必须在「工具批次写回消息之后」调用，且要防末尾无消息",
            loop.contains("if (messages.lastOrNull()?.let { pauseAfterTools(it) } == true) break"),
        )
    }

    @Test
    fun `v1034 中途压缩只许主对话打开，其余路径一律不传`() {
        // 隔离规矩（PROJECT-MANUAL §4 禁区13 / §5.1）：默认关，只有主对话显式打开。
        assertTrue(
            "ChatService 里 pauseAfterTools 应当只出现在主对话那一处调用点，出现多次说明别的链路也被接上了",
            Regex("""pauseAfterTools\s*=\s*pause@""").findAll(chatService).count() == 1,
        )
        for (rel in listOf(
            "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt",
            "app/src/main/java/me/rerere/rikkahub/service/GroupChatEngine.kt",
            "app/src/main/java/me/rerere/rikkahub/service/RoundTableCoordinator.kt",
        )) {
            assertFalse("非主对话链路被接上了中途压缩钩子：$rel", source(rel).contains("pauseAfterTools"))
        }
    }

    @Test
    fun `v1034 钩子第一件事必须尊重用户的开关`() {
        val hook = chatService.substring(chatService.indexOf("pauseAfterTools = pause@"))
            .lineSequence().take(6).joinToString("\n")
        assertTrue(
            "钩子没先看 enableAutoCompress 开关：关掉开关也照压，等于凭空加戏",
            hook.contains("if (!settings.enableAutoCompress) return@pause false"),
        )
    }

    @Test
    fun `v1034 中途暂停不许发本轮结束事件`() {
        val idx = chatService.indexOf("if (!midTurnPaused) {")
        assertTrue("onCompletion 里没有闸门：中途压缩会把半截回复当完整回复收尾（通知误报、语音抢读、标题与建议提前生成）", idx >= 0)
        val gated = chatService.substring(idx, idx + 900)
        assertTrue("闸门里没包住 ChatGenerationEnded", gated.contains("AppEvent.ChatGenerationEnded"))
        assertTrue("generationEndReported 被挪到闸门外，会让 onFailure 再补发一次结束事件",
            gated.contains("generationEndReported = true"))
    }

    @Test
    fun `v1034 一轮内部的续写循环必须有出口`() {
        assertTrue("缺少 midTurnPaused 判据的 break —— 没有出口就是死循环",
            chatService.contains("if (!midTurnPaused) break"))
        assertTrue("续写时必须重新取压缩后的最新历史",
            chatService.contains("generationMessages = getConversationFlow(conversationId).value.currentMessages"))
        assertTrue("循环开头必须重置暂停标记，否则第二轮起会永远停在「已暂停」状态",
            chatService.contains("            while (true) {\n                midTurnPaused = false"))
    }

    @Test
    fun `v1034 中途压缩必须跟随智能经典二选一且不删除已有消息`() {
        val body = bodyOf(chatService, "private suspend fun compressDuringActiveReply(", "中途压缩执行入口")
        assertTrue("中途压缩不能替用户固定选经典", body.contains("if (settings.compressUseClassicMode)"))
        assertTrue("经典分支必须仍使用原有经典压缩", body.contains("compressConversationClassic("))
        assertTrue("智能分支必须复用原有智能摘要算法", body.contains("compressConversation("))
        assertTrue("智能分支只能取摘要结果，不许替换正在生成的历史", body.contains("checkpointResult ="))
        assertTrue("两条路线最终都只插入检查点", body.contains("session.compressClassicHistory("))
        assertFalse("中途不应另开异步手动压缩任务", body.contains("startManualCompression("))
        assertTrue("生成任务内部必须显式豁免生成中守卫", body.contains("allowWhileGenerating = true"))
    }

    @Test
    fun `v1034 中途压缩实际执行必须在流收完之后避免覆盖未保存的工具结果`() {
        val hookStart = chatService.indexOf("pauseAfterTools = pause@")
        val hookEnd = chatService.indexOf("stripGroupToolParts = true", hookStart.coerceAtLeast(0))
        assertTrue("找不到中途暂停钩子边界", hookStart >= 0 && hookEnd > hookStart)
        val hook = chatService.substring(hookStart, hookEnd)
        assertFalse("钩子只许提出暂停，不许在流生产端压缩", hook.contains("compressDuringActiveReply("))
        assertFalse("钩子不能在工具结果尚在流里时插检查点", hook.contains("compressConversationClassic("))
        val collectStart = chatService.indexOf("}.collect { chunk ->", hookEnd)
        assertTrue("找不到主生成流的collect", collectStart > hookEnd)
        // 此处collect的顶层闭合固定为12空格；内部when/工具写回块的闭合都是更深缩进。
        // 必须独立锁闭合位置，不能只比较break和调用先后（整段移进collect会绕过旧判据）。
        val collectEnd = chatService.indexOf("\n            }\n", collectStart)
        assertTrue("找不到collect顶层闭合", collectEnd > collectStart)
        val collectBody = chatService.substring(collectStart, collectEnd)
        assertFalse("工具结果尚未收完时不许压缩", collectBody.contains("compressDuringActiveReply("))
        val drain = chatService.indexOf("if (!midTurnPaused) break", collectStart)
        val execute = chatService.indexOf("compressDuringActiveReply(conversationId, settings)", collectStart)
        assertTrue("暂停判据必须在collect闭合后", drain > collectEnd)
        assertTrue("压缩调用必须在collect闭合并确认暂停之后", execute > drain)
        assertTrue("失败仍要明确显示原因", chatService.substring(drain, execute + 1000).contains("error_title_auto_compress"))
    }

    @Test
    fun `v1034 生成中守卫只能被显式豁免且默认关闭`() {
        val cc = source("app/src/main/java/me/rerere/rikkahub/service/ClassicCompression.kt")
        assertTrue("allowWhileGenerating 默认不是 false，「正在生成不许压」这条守卫对所有调用者都失效了",
            cc.contains("allowWhileGenerating: Boolean = false,"))
        assertEquals("两处生成中守卫必须都改成可豁免的写法（少一处就会在中途压缩时误报）",
            2, Regex("isGenerating && !allowWhileGenerating").findAll(cc).count())
    }

    @Test
    fun `v1034 中途压缩必须有防来回压的判据`() {
        val hook = chatService.substring(chatService.indexOf("pauseAfterTools = pause@"))
            .lineSequence().take(20).joinToString("\n")
        assertTrue("缺「同一条回复只试一次」", hook.contains("session.autoCompressedReplyId"))
        assertTrue("缺「压缩后新开的那段只走一步就又到阈值 ⇒ 本轮不再压」——摘要本身超长时会来回压",
            hook.contains("afterMidTurnCompression && (message.stepCount ?: 0) <= 1"))
        assertTrue("重新生成较早消息时不许压（后面还挂着别的节点）", hook.contains("if (messageRange != null) return@pause false"))
    }

    @Test
    fun `v1034 官方 midTurn 增量必须落在我们自己的 OfficialCompressPrompt 文件里`() {
        // v1019 那轮我们把官方的 buildCompressPrompt 搬进了自己的文件；照官方路径打补丁会静默丢失
        val official = source("app/src/main/java/me/rerere/rikkahub/data/ai/prompts/OfficialCompressPrompt.kt")
        val ours = source("app/src/main/java/me/rerere/rikkahub/data/ai/prompts/CompressPrompt.kt")
        assertTrue("OfficialCompressPrompt 少了 midTurn 形参", official.contains("midTurn: Boolean = false"))
        assertTrue("midTurn 的默认值必须是 false（默认开=擅自改现有压缩）",
            !Regex("""midTurn:\s*Boolean\s*=\s*true""").containsMatchIn(official))
        assertTrue("官方中途压缩那段说明没进来",
            official.contains("in the middle of your work on the user's latest request"))
        assertFalse("智能压缩的提示词文件被误打了官方补丁", ours.contains("midTurn"))
    }

    @Test
    fun `v1034 广告位不许借官方更新回来`() {
        for (banned in listOf("APIMart", "302.AI", "随想AI网关", "MaruCode", "AiHubMix", "小马算力", "硅基流动", "STA1N", "sta1n.ai")) {
            assertFalse("内置供应商清单里又出现了推广位 $banned（用户明确要求去广告、不给原主带干扰）",
                defaults.contains(banned))
        }
        assertTrue("推荐供应商清单必须是空表（v305 用户要求去广告，官方后来往这里加推广也不采纳）", recommended.contains("RECOMMENDED_PROVIDERS: List<ProviderSetting> = emptyList()"))
        assertFalse("推荐清单里回来了 sta1n", recommended.contains("sta1n"))
    }

    @Test
    fun `v1034 设置页开关必须真读写这两个字段`() {
        assertTrue("开关没绑到 enableAutoCompress", generalPage.contains("checked = settings.enableAutoCompress"))
        assertTrue("开关没写回 enableAutoCompress", generalPage.contains("copy(enableAutoCompress = it)"))
        assertTrue("阈值滑条没用官方同一区间常量（读写夹值与界面必须同一个来源）",
            generalPage.contains("valueRange = AUTO_COMPRESS_THRESHOLD_RANGE"))
    }

    @Test
    fun `v1034 官方新增的顶层枚举只许声明一次`() {
        // 真踩记录（v1034 双审两票独立抓到同一条 P0）：先手工补了一遍枚举定义，
        // 之后又跑了一轮"官方小块自动落盘"，官方那份也被打了进来 → Redeclaration 编译必炸。
        val api = source("ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt")
        assertEquals(
            "ReasoningReplayFormat 必须只声明一次（重复声明 = 编译期 Redeclaration 致命错误）",
            1, Regex("internal enum class ReasoningReplayFormat").findAll(api).count(),
        )
        assertEquals(
            "requiresReasoningContentReplay 必须只有一份（我们的 v1010 helper 不许被复制）",
            1, Regex("internal fun requiresReasoningContentReplay").findAll(api).count(),
        )
    }

    @Test
    fun `v1034 版本号与包名`() {
        val gradle = source("app/build.gradle.kts")
        // v1035：保留上游融合守卫，只同步本轮覆盖安装版本。
        // v1036：经典压缩参数排除接线修复，递增覆盖安装版本。
        // v1037：反重力视频入口兼容，递增覆盖安装版本。
        // v1038：新增RT/JSON导入，继续递增覆盖安装版本。
        // v1039：Codex 模型目录与子代理外层用量，递增覆盖安装版本。
        // v1040：子代理共享思考等级，递增覆盖安装版本。
        assertTrue("versionCode 必须升到 1040", gradle.contains("versionCode = 1040"))
        assertTrue("versionName 必须是 3.2.0-mod38", gradle.contains("versionName = \"3.2.0-mod38\""))
        assertTrue("applicationId 不许变（与官方共存、可覆盖安装的前提）",
            gradle.contains("applicationId = \"me.rerere.rikkahub.mod\""))
    }

    @Test
    fun `v1034 数据库版本保持 32 与 3`() {
        assertTrue("AppDatabase 必须 32（官方 3.2.0 也没动库，动它要先问用户）",
            source("app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt").contains("version = 32,"))
        assertTrue("AgentDatabase 必须 3",
            source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDatabase.kt").contains("version = 3,"))
    }

    @Test
    fun `v1034 GPT思考卡片必须与上游3_2_0逐字节一致`() {
        val upstream = File(repoRoot, "../rikkahub-3.2.0/app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt")
        assumeTrue("本机没有官方 3.2.0 基线，跳过（该检查要求基线目录在旁边）", upstream.exists())
        val ours = File(repoRoot, "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt")
        assertTrue("红线：思考卡片不许我们自己动", ours.readBytes().contentEquals(upstream.readBytes()))
    }

    @Test
    fun `v1034 主对话红线不许被官方更新牵连`() {
        val loop = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertFalse("生成层不许读设置里的工具输出上限（主对话必须与上游一致）",
            loop.contains("settings.toolOutputCharLimit"))
        assertFalse("ChatService 不许出现 toolOutputCharLimit", chatService.contains("toolOutputCharLimit"))
        assertTrue("generateText 默认值必须是上游常量", loop.contains("toolOutputCharLimit: Int = MAX_TOOL_OUTPUT_CHARS"))
    }

    @Test
    fun `v1034 官方新增文件必须真的在树里`() {
        for (p in listOf(
            "app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/PhoneUseTool.kt",
            "app/src/main/java/me/rerere/rikkahub/phoneuse/PhoneController.kt",
            "app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/PermissionCards.kt",
            "ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsContent.kt",
            "app/src/main/res/xml/network_security_config.xml",
            "app/src/github/java/me/rerere/rikkahub/phoneuse/PhoneUseAccessibilityService.kt",
        )) {
            assertTrue("缺官方新增文件 $p", File(repoRoot, p).exists())
        }
        assertFalse("官方已删除的旧权限卡文件不许留下（会与 PermissionCards 重复定义）",
            File(repoRoot, "app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/LocalNetworkPermissionCard.kt").exists())
    }

    @Test
    fun `v1034 smart checkpoint callback is opt in and bypasses both original history replacement paths`() {
        val start = chatService.indexOf("suspend fun compressConversation(")
        val end = chatService.indexOf("fun shouldAutoCompressConversation(", start.coerceAtLeast(0))
        assertTrue("找不到智能压缩边界", start >= 0 && end > start)
        val smart = chatService.substring(start, end)
        assertTrue("可选结果回调默认必须null", smart.contains("checkpointResult: ((List<MessageNode>) -> Unit)? = null,"))
        val resultStart = smart.indexOf("if (checkpointResult != null) {")
        val normalStart = smart.indexOf("} else if (expectedManualSource != null) {", resultStart.coerceAtLeast(0))
        assertTrue("结果回调与原写回必须互斥", resultStart >= 0 && normalStart > resultStart)
        val resultBranch = smart.substring(resultStart, normalStart)
        assertTrue(resultBranch.contains("checkpointResult(newMessageNodes)"))
        assertFalse("结果回调不能写数据库", resultBranch.contains("saveConversation("))
        assertFalse("结果回调不能替换手动历史", resultBranch.contains("commitManualSmartCompression("))
        assertTrue("普通智能模式的原提交仍须保留", smart.substring(normalStart).contains("commitManualSmartCompression("))
        assertTrue("自动分叉的原提交仍须保留", smart.substring(normalStart).contains("saveConversation("))
    }
}
