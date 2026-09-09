package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v263 门禁：主对话「异常截断自动续跑」防走丢测试。
 *
 * 背景：v262 合并上游 2.4.13 时，6 个带二改的文件被官方版本整覆盖、功能静默丢失，
 * 靠快照逐文件比对才找回。为防止 v263 的截断续跑功能在将来合并上游时再次被静默覆盖，
 * 这里直接读源码文件核对关键零件是否存在（与 AgentV247ReadSliceTest 同一套做法）。
 *
 * 只要有一处被覆盖（循环计数、接尾指令、断点基线推进、设置四件套、界面滑块、双语文案），
 * 本测试变红，编译门禁立刻能看见，不会再静默退化。
 */
class TruncationAutoResumeGuardTest {

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

    private val handlerPath =
        "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt"
    private val prefsPath =
        "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"
    private val settingUiPath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt"

    @Test
    fun `截断续跑的核心逻辑还在 GenerationLoop 里`() {
        val src = source(handlerPath)
        // 1) 次数上限判定存在（没有它就是无限续跑或一次不续）
        assertTrue(
            "缺少次数上限判定 truncatedResumeCount < maxTruncationResumes",
            src.contains("truncatedResumeCount < maxTruncationResumes")
        )
        // 2) 截断判定复用 AgentFinishReason.isTruncated（不要另写一套关键词表）
        assertTrue(
            "缺少截断判定 AgentFinishReason.isTruncated(lastAssistant?.finishReason)",
            src.contains("AgentFinishReason.isTruncated(lastAssistant?.finishReason)")
        )
        // 3) 接尾指令常量存在
        assertTrue(
            "缺少接尾指令 TRUNCATION_RESUME_INSTRUCTION",
            src.contains("TRUNCATION_RESUME_INSTRUCTION")
        )
        // 3b) v268：接尾指令 = 「继续」（真机实锤：长指令的声明段会被模型误当用户发言，
        //     「严禁重新开头」也被无视；换成模型最熟悉的「继续」无从误读）
        assertTrue(
            "接尾指令不再是「继续」",
            src.contains("private const val TRUNCATION_RESUME_INSTRUCTION: String = \"继续\"")
        )
        // 3c) v268：程序级去重保险（模型重写旧内容时剪掉逐字重复段）
        assertTrue(
            "缺少去重函数 stripResumeDuplication",
            // v272 起由 private 改 internal：圆桌续跑要复用同一份去重保护（防各自维护两套漂移）
            src.contains("internal fun stripResumeDuplication(oldText: String, newText: String): String")
        )
        assertTrue(
            "缺少去重阈值 RESUME_DUP_MIN_CHARS = 60",
            src.contains("RESUME_DUP_MIN_CHARS = 60")
        )
        assertTrue(
            "缺少轮级去重入口 stripResumeRoundDuplication",
            src.contains("stripResumeRoundDuplication(preResumeText, preResumePartCount)")
        )
        // 4) 续跑轮的展示基线推进到半截回答（保证增量拼进同一条消息，不多出一页）
        assertTrue(
            "缺少续跑基线推进 responseBaseMessages = messages",
            src.contains("responseBaseMessages = messages")
        )
        // 5) 续跑轮的请求输入要带半截回答 + 接尾指令（每轮从 internalMessages 重建，不累加）
        assertEquals(
            "两条续跑路径（截断/中断）都应走 buildResumeProviderInput",
            2,
            Regex("providerInput = buildResumeProviderInput\\(").findAll(src).count()
        )
        // 5b) v288：思考回灌整体废弃（做不到真正续接思维链，实测只造成多段思考堆叠与打转）。
        //     这里改成反向断言，钉住「不许复活」——续跑输入只带半截正文 + 「继续」。
        assertTrue(
            "思考回灌被复活了（trailingReasoningText 不该再出现）",
            !src.contains("trailingReasoningText")
        )
        assertTrue(
            "思考回灌被复活了（RESUME_THINKING_HEADER 不该再出现）",
            !src.contains("RESUME_THINKING_HEADER")
        )
        assertTrue(
            "思考回灌被复活了（RESUME_THINKING_MAX_CHARS 不该再出现）",
            !src.contains("RESUME_THINKING_MAX_CHARS")
        )
        // 6) 接缝合并（续跑新增文字并进原段落，不另起一段）
        assertTrue(
            "缺少接缝合并 mergeAdjacentTextParts",
            src.contains("mergeAdjacentTextParts")
        )
    }

    @Test
    fun `隔离规矩还在_默认关_只有主对话显式打开`() {
        // 引擎侧默认 0 = 关：圆桌 / 子代理 / 压缩 / 翻译不传参数，行为必须与改前一致
        val handler = source(handlerPath)
        assertTrue(
            "引擎默认值被改了，非主对话路径会被动获得续跑行为",
            handler.contains("truncationAutoResumeMax: Int = 0")
        )
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue(
            "主对话路径没有显式打开续跑开关",
            chat.contains("truncationAutoResumeMax = settings.truncationAutoResumeMax")
        )
        // 主对话路径只能出现一次显式打开（圆桌/压缩等其它调用点不得跟随）
        assertEquals(
            1,
            Regex("truncationAutoResumeMax = settings\\.truncationAutoResumeMax").findAll(chat).count()
        )
    }

    @Test
    fun `异常中断续跑的零件还在(v264_全拆黑名单v292)`() {
        val src = source(handlerPath)
        // 1) v292（用户拍板「全拆黑名单」）：续跑不再看错误分类——任何服务商报错都续跑。
        //    反向断言钉住：不允许把 FATAL 排除条件加回来。
        assertTrue(
            "续跑判定又把 FATAL 排除条件加回来了（用户 v292 拍板全拆黑名单）",
            !src.contains("classifyUpstreamError(error) != AgentErrorKind.FATAL")
        )
        // 1b) 分类函数已随黑名单移除，不许复活
        assertTrue(
            "classifyUpstreamError 又复活了（用户 v292 拍板全拆黑名单）",
            !src.contains("private fun classifyUpstreamError")
        )
        // 2) 整趟重试的判定只看「是否主对话续跑路径」与网络错误，不看错误分类
        assertTrue(
            "整趟重试判定缺少 val recoverable = error is IOException || allowUpstreamErrors",
            src.contains("val recoverable = error is IOException || allowUpstreamErrors")
        )
        // 3) 用户主动停止不被当成异常中断续跑（取消信号原样上抛 + ensureActive 双保险）
        assertTrue(
            "catch 内缺少取消保护（用户停止会被误续跑）",
            src.contains("if (error is CancellationException) throw error") &&
                Regex("currentCoroutineContext\\(\\)\\.ensureActive\\(\\)").findAll(src).count() >= 2
        )
        // 4) 中断续跑保留已写内容为基线
        assertTrue(
            "中断续跑缺少基线保留 responseBaseMessages = attemptMessages",
            src.contains("responseBaseMessages = attemptMessages")
        )
        // 5) 服务商临时故障整趟重试只在主对话开（allowUpstreamErrors 由续跑设置门控）
        assertEquals(
            "allowUpstreamErrors = truncationAutoResumeMax > 0 应恰好出现 2 次（流式 catch + 非流式）",
            2,
            Regex("allowUpstreamErrors = truncationAutoResumeMax > 0").findAll(src).count()
        )
        // 6) 官方断网重试语义保留：IOException 永远可重试
        assertTrue(
            "官方 IOException 重试语义被改坏",
            src.contains("val recoverable = error is IOException ||")
        )
    }

    @Test
    fun `续跑思考模式已整体废弃_反向门禁(v288)`() {
        // v288：思考回灌（把上一段思考当普通文本塞进 user 消息）做不到真正续接思维链——
        // 原生思考模型需要原样回传带签名的 reasoning 块。实测只造成多段思考堆叠与重复打转，
        // 用户拍板整体换掉。这里用反向断言钉住「不许复活」，同时保证测试数不减。
        val prefs = source(prefsPath)
        assertTrue(
            "Settings 不该再有思考回灌字段",
            !prefs.contains("val resumeWithPriorThinking")
        )
        assertTrue(
            "不该再有思考回灌的 DataStore 键",
            !prefs.contains("booleanPreferencesKey(\"resume_with_prior_thinking_v272\")")
        )
        val handler = source(handlerPath)
        assertTrue(
            "引擎不该再有思考回灌参数",
            !handler.contains("resumeWithPriorThinking")
        )
        assertTrue(
            "引擎不该再有取尾部思考的辅助函数",
            !handler.contains("trailingReasoningText")
        )
        assertTrue(
            "引擎不该再有思考转述头常量",
            !handler.contains("RESUME_THINKING_HEADER")
        )
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue(
            "主对话不该再接思考回灌",
            !chat.contains("resumeWithPriorThinking")
        )
        assertTrue(
            "设置页不该再有思考模式开关",
            !source(settingUiPath).contains("setting_resume_thinking_title")
        )
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文残留思考模式文案", !en.contains("setting_resume_thinking_title"))
        assertTrue("中文残留思考模式文案", !zh.contains("setting_resume_thinking_title"))
    }

    @Test
    fun `对话级模型记忆的四件套与接线(v268)`() {
        val prefs = source(prefsPath)
        // 数据层
        assertTrue(
            "Settings 缺字段 rememberModelPerConversation",
            prefs.contains("val rememberModelPerConversation: Boolean = true")
        )
        assertTrue(
            "Settings 缺字段 conversationModelIds",
            prefs.contains("val conversationModelIds: Map<Uuid, Uuid> = emptyMap()")
        )
        assertTrue(
            "缺少 key remember_model_per_conversation",
            prefs.contains("booleanPreferencesKey(\"remember_model_per_conversation\")")
        )
        assertTrue(
            "缺少 key conversation_model_ids",
            prefs.contains("stringPreferencesKey(\"conversation_model_ids\")")
        )
        assertTrue(
            "缺少读映射 rememberModelPerConversation = preferences[REMEMBER_MODEL_PER_CONVERSATION] != false",
            prefs.contains("rememberModelPerConversation = preferences[REMEMBER_MODEL_PER_CONVERSATION] != false")
        )
        assertTrue(
            "缺少写盘 preferences[CONVERSATION_MODEL_IDS]",
            prefs.contains("preferences[CONVERSATION_MODEL_IDS] = JsonInstant.encodeToString(")
        )
        // ChatVM：切换时记录 + 打开时恢复
        val vm = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue(
            "ChatVM init 缺少恢复逻辑锚点 conversationModelIds[_conversationId]",
            vm.contains("conversationModelIds[_conversationId]")
        )
        assertTrue(
            "ChatVM setChatModel 缺少记录逻辑 conversationModelIds + (_conversationId to model.id)",
            vm.contains("conversationModelIds + (_conversationId to model.id)")
        )
        // 设置页开关 + 双语文案
        assertTrue(
            "设置页缺开关 setting_remember_model_title",
            source(settingUiPath).contains("setting_remember_model_title")
        )
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺 setting_remember_model_title", en.contains("setting_remember_model_title"))
        assertTrue("中文缺 setting_remember_model_title", zh.contains("setting_remember_model_title"))
    }

    @Test
    fun `双模式压缩的零件都在(v268)`() {
        // 对话框：双模式状态 + 模式切换 + 经典参数 + 确认分支
        val dialog = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt")
        assertTrue("对话框缺双模式状态 classicMode", dialog.contains("var classicMode by remember { mutableStateOf(initialClassicMode) }"))
        assertTrue("对话框缺经典模式确认分支 onConfirmClassic", dialog.contains("onConfirmClassic(additionalPrompt, selectedTokens, keepRecentMessages)"))
        assertTrue("对话框缺模式切换 UI chat_page_compress_mode_smart", dialog.contains("chat_page_compress_mode_smart"))
        assertTrue("对话框缺目标token选项", dialog.contains("listOf(500, 1000, 2000, 4000)"))
        assertTrue("对话框缺保留条数输入 OutlinedNumberInput", dialog.contains("OutlinedNumberInput("))
        // 服务层：经典压缩函数 + 官方旧版提示词（不读设置里的 Codex 提示词）
        val service = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue("ChatService 缺经典压缩函数 compressConversationClassic", service.contains("suspend fun compressConversationClassic("))
        assertTrue("经典压缩必须用 LEGACY_COMPRESS_PROMPT 而不是设置的 compressPrompt",
            service.contains("val prompt = LEGACY_COMPRESS_PROMPT.applyPlaceholders("))
        val prompts = source("app/src/main/java/me/rerere/rikkahub/data/ai/prompts/CompressPrompt.kt")
        assertTrue("缺官方旧版提示词常量 LEGACY_COMPRESS_PROMPT", prompts.contains("internal val LEGACY_COMPRESS_PROMPT"))
        // VM：两个入口 + 模式记忆
        val vm = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue("ChatVM 缺经典压缩入口 handleCompressContextClassic", vm.contains("fun handleCompressContextClassic("))
        assertTrue("ChatVM 智能模式缺模式记忆 compressUseClassicMode = false", vm.contains("compressUseClassicMode = false"))
        assertTrue("ChatVM 经典模式缺模式记忆 compressUseClassicMode = true", vm.contains("compressUseClassicMode = true"))
        // 接线：FilesPicker 参数 + ChatPage 传递
        val picker = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("FilesPicker 缺经典压缩参数 onCompressContextClassic", picker.contains("onCompressContextClassic: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job"))
        assertTrue("FilesPicker 缺初始模式传递 initialClassicMode = settings.compressUseClassicMode", picker.contains("initialClassicMode = settings.compressUseClassicMode"))
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue("ChatPage 缺经典压缩接线 onCompressContextClassic", page.contains("onCompressContextClassic = { additionalPrompt, targetTokens, keepRecentMessages ->"))
        // 设置四件套 + 双语文案
        val prefs = source(prefsPath)
        assertTrue("Settings 缺字段 compressUseClassicMode", prefs.contains("val compressUseClassicMode: Boolean = false"))
        assertTrue("缺少读映射 compressUseClassicMode = preferences[COMPRESS_USE_CLASSIC_MODE] == true",
            prefs.contains("compressUseClassicMode = preferences[COMPRESS_USE_CLASSIC_MODE] == true"))
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺 chat_page_compress_mode_smart", en.contains("chat_page_compress_mode_smart"))
        assertTrue("英文缺 chat_page_compress_mode_classic", en.contains("chat_page_compress_mode_classic"))
        assertTrue("中文缺 chat_page_compress_mode_smart", zh.contains("chat_page_compress_mode_smart"))
        assertTrue("中文缺 chat_page_compress_mode_classic", zh.contains("chat_page_compress_mode_classic"))
    }

    @Test
    fun `断网重试的官方逻辑没有被截断续跑改坏`() {
        val src = source(handlerPath)
        assertTrue(
            "断网重试上限 MAX_PROVIDER_NETWORK_RETRIES = 3 丢了",
            src.contains("MAX_PROVIDER_NETWORK_RETRIES = 3")
        )
        assertTrue(
            "断网重试的输入快照语义 responseBaseMessages 声明丢了",
            src.contains("responseBaseMessages")
        )
    }

    @Test
    fun `设置四件套完整(字段+存取键+读+写)`() {
        val src = source(prefsPath)
        // 字段声明
        assertTrue(
            "Settings 缺字段 truncationAutoResumeMax",
            src.contains("val truncationAutoResumeMax: Int = 3")
        )
        // 存取键声明
        assertTrue(
            "缺少 intPreferencesKey(\"truncation_auto_resume_max\")",
            src.contains("intPreferencesKey(\"truncation_auto_resume_max\")")
        )
        // 读：默认 3、范围 0~10
        assertTrue(
            "缺少读取映射 truncationAutoResumeMax = (preferences[TRUNCATION_AUTO_RESUME_MAX]",
            src.contains("truncationAutoResumeMax = (preferences[TRUNCATION_AUTO_RESUME_MAX] ?: 3).coerceIn(0, 99)")
        )
        // 写
        assertTrue(
            "缺少写盘 preferences[TRUNCATION_AUTO_RESUME_MAX] = settings.truncationAutoResumeMax",
            src.contains("preferences[TRUNCATION_AUTO_RESUME_MAX] = settings.truncationAutoResumeMax.coerceIn(0, 99)")
        )
    }

    @Test
    fun `设置界面滑块与双语文案还在`() {
        assertTrue(
            "设置页缺少滑块项 setting_truncation_auto_resume_title",
            source(settingUiPath).contains("setting_truncation_auto_resume_title")
        )
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺 setting_truncation_auto_resume_title", en.contains("setting_truncation_auto_resume_title"))
        assertTrue("英文缺 chat_generation_truncation_resuming", en.contains("chat_generation_truncation_resuming"))
        assertTrue("中文缺 setting_truncation_auto_resume_title", zh.contains("setting_truncation_auto_resume_title"))
        assertTrue("中文缺 chat_generation_truncation_resuming", zh.contains("chat_generation_truncation_resuming"))
        // v264：异常中断续写的独立文案，中英文都要有
        assertTrue("英文缺 chat_generation_interrupted_resuming", en.contains("chat_generation_interrupted_resuming"))
        assertTrue("中文缺 chat_generation_interrupted_resuming", zh.contains("chat_generation_interrupted_resuming"))
        assertTrue(
            "GenerationLoop 缺少对 interrupted_resuming 文案的引用",
            source(handlerPath).contains("R.string.chat_generation_interrupted_resuming")
        )
    }

    @Test
    fun `续跑次数记累计账_重跑不清零(v270)`() {
        val handler = source(handlerPath)
        assertTrue(
            "必须有基线快照：同一条消息重跑时，上一轮已记的次数不能被本轮清零",
            handler.contains("val baselineResumeCount = responseBaseMessages.lastOrNull()"),
        )
        assertTrue(
            "写入必须用 基线+本轮：直接覆盖会出现「续跑2次」掉线后倒退成「续跑1次」",
            handler.contains("resumeCount = baselineResumeCount + truncatedResumeCount"),
        )
    }

    @Test
    fun `空正文催答的零件都在(v295)`() {
        val src = source(handlerPath)
        // 本轮零输出检测：正文与工具都没新增才催（以工具收尾的轮次不能误催）
        assertTrue("缺 noToolThisRound 判定", src.contains("val noToolThisRound = lastAssistant?.parts"))
        assertTrue("缺 emptyThisRound 判定", src.contains("val emptyThisRound = partialText.isBlank() && noToolThisRound"))
        // 空正文必须能进续跑通道（原判定要求 partialText 非空，会把空回挡在外面）
        assertTrue("续跑判定未放行空正文", src.contains("(partialText.isNotBlank() || emptyThisRound)"))
        // 催答输入不拼空 assistant 消息（商汤网关对空 content 行为不明示、可能拒收）
        assertTrue("空正文催答仍可能拼空助手消息", src.contains("if (partialText.isNotBlank())"))
    }

    @Test
    fun `询问卡片无选项降级与诊断的零件都在(v295)`() {
        val src = source("app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt")
        // 4 处 options 空判定：single 降级 + multi 降级 + 提交判定 + 答案组装
        assertEquals(
            "single/multi 降级、提交判定、答案组装共 4 处 options 空判定必须都在（缺一处=卡死回归）",
            4,
            Regex("if \\(q\\.options\\.isEmpty\\(\\)\\)").findAll(src).count()
        )
        // v297：解析搬进 AskUserParsing.kt 后，「整体读不出」的日志文案随之改名；
        // 断言跟着换成新文案，强度不变（两处第一现场都必须还在）。
        assertTrue("缺诊断日志（参数形状不符、一题都读不出）", src.contains("ask_user 未解析出任何问题"))
        assertTrue("缺诊断日志（选项缺失第一现场）", src.contains("ask_user 选项缺失"))
    }

    @Test
    fun `强制翻阅skill模式的零件都在_默认关(v294)`() {
        val tools = source("app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt")
        assertTrue("SkillsTools 缺 forceReview 参数且默认必须 false", tools.contains("forceReview: Boolean = false"))
        assertTrue("缺强制指令注入段", tools.contains("<mandatory_skill_review>"))
        assertTrue("注入必须是条件式的（默认零注入）", tools.contains("if (forceReview)"))
        // 无已开启 skill 时零注入（原有早退逻辑必须还在）
        assertTrue("空清单早退逻辑被改坏", tools.contains("if (available.isEmpty()) return emptyList()"))
        val prefs = source(prefsPath)
        assertTrue("DisplaySetting 缺默认关闭的字段", prefs.contains("val forceSkillReview: Boolean = false"))
        // 两处调用点都必须接线
        val factory = source("app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt")
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue("ChatToolFactory 未接线", factory.contains("forceReview = settings.displaySetting.forceSkillReview"))
        assertTrue("ChatService 未接线", chat.contains("forceReview = settings.displaySetting.forceSkillReview"))
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt")
        assertTrue("设置页缺开关", ui.contains("setting_display_page_force_skill_review_title"))
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺文案", en.contains("setting_display_page_force_skill_review_title"))
        assertTrue("中文缺文案", zh.contains("setting_display_page_force_skill_review_title"))
    }

    @Test
    fun `重点标色插件的零件都在_默认零注入(v270)`() {
        val handler = source(handlerPath)
        assertTrue("缺中性注入文案常量", handler.contains("HIGHLIGHT_KEY_POINTS_INJECTION"))
        assertTrue("注入必须是条件式的（默认 false = 零注入）", handler.contains("if (highlightKeyPoints)"))
        assertTrue("streamText 参数默认必须 false", handler.contains("highlightKeyPoints: Boolean = false"))
        val service = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertTrue(
            "只有主对话显式接线；其余路径不传，行为与改前一致",
            service.contains("highlightKeyPoints = settings.displaySetting.highlightKeyPoints"),
        )
        val prefs = source(prefsPath)
        assertTrue("DisplaySetting 缺默认关闭的字段", prefs.contains("val highlightKeyPoints: Boolean = false"))
        val ui = source("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt")
        assertTrue("设置页缺开关", ui.contains("setting_display_page_highlight_key_points_title"))
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺文案", en.contains("setting_display_page_highlight_key_points_title"))
        assertTrue("中文缺文案", zh.contains("setting_display_page_highlight_key_points_title"))
    }

    @Test
    fun `续跑记账发生在瞬间且三处口径统一为累计值(v272)`() {
        val handler = source(handlerPath)
        // 四处写入（截断瞬间 / 断线-发继续瞬间 / 断线-重新生成瞬间 / 最终收尾）一律「基线+本轮」，
        // 少任何一处都会重现「掉线后底部计数不涨」或「倒退」。
        // v288：新增「重新生成最后一次输出」这条路——它把半截丢掉，所以记账必须写到
        // 保留下来的展示基线上（写到即将被丢弃的半截上等于没记），因此从 3 处变 4 处。
        assertEquals(
            "记账写入必须恰好 4 处且全部为 基线+本轮",
            4,
            Regex("resumeCount = baselineResumeCount \\+ truncatedResumeCount").findAll(handler).count()
        )
        assertEquals(
            "两条续跑路径的预算判定都必须按 基线+本轮 累计（总次数封顶在设置值）",
            2,
            Regex("baselineResumeCount \\+ truncatedResumeCount < (maxTruncationResumes|interruptedMaxResumes)").findAll(handler).count()
        )
        assertEquals(
            "两条弹窗都必须报累计值（基线+本轮），不再从 1 重数",
            2,
            Regex("baselineResumeCount \\+ truncatedResumeCount,").findAll(handler).count()
        )
    }

    @Test
    fun `续跑方式的四件套与接线(v288)`() {
        val prefs = source(prefsPath)
        assertTrue(
            "缺枚举 ResumeStrategy（两种续跑方式）",
            prefs.contains("enum class ResumeStrategy")
        )
        assertTrue(
            "ResumeStrategy 必须同时有 CONTINUE 与 REGENERATE 两项",
            prefs.contains("    CONTINUE,") && prefs.contains("    REGENERATE,")
        )
        assertTrue(
            "Settings 缺字段 resumeStrategy，默认必须是 CONTINUE（= 原有行为）",
            prefs.contains("val resumeStrategy: ResumeStrategy = ResumeStrategy.CONTINUE,")
        )
        assertTrue(
            "缺少 key stringPreferencesKey(\"resume_strategy_v288\")",
            prefs.contains("stringPreferencesKey(\"resume_strategy_v288\")")
        )
        assertTrue(
            "缺少读映射 resumeStrategy = preferences[RESUME_STRATEGY_V288]",
            prefs.contains("resumeStrategy = preferences[RESUME_STRATEGY_V288]")
        )
        assertTrue(
            "键缺席时必须回落 CONTINUE",
            prefs.contains("?: ResumeStrategy.CONTINUE,")
        )
        assertTrue(
            "缺少写盘 preferences[RESUME_STRATEGY_V288] = settings.resumeStrategy.name",
            prefs.contains("preferences[RESUME_STRATEGY_V288] = settings.resumeStrategy.name")
        )
        // 引擎：公开入口 + 私有实现各一处参数，默认 CONTINUE（其余调用方行为不变）
        val handler = source(handlerPath)
        assertEquals(
            "引擎的公开入口与私有实现都要有 resumeStrategy 参数且默认 CONTINUE",
            2,
            Regex("resumeStrategy: ResumeStrategy = ResumeStrategy\\.CONTINUE,").findAll(handler).count()
        )
        assertTrue(
            "引擎缺 REGENERATE 分支（重新生成最后一次输出）",
            handler.contains("val regenerating = resumeStrategy == ResumeStrategy.REGENERATE") &&
                handler.contains("if (regenerating) {")
        )
        // 主对话唯一接线
        val chat = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertEquals(
            "主对话应恰好一次显式传 resumeStrategy",
            1,
            Regex("resumeStrategy = settings\\.resumeStrategy").findAll(chat).count()
        )
        // 设置页二选一 + 双语文案
        val ui = source(settingUiPath)
        assertTrue("设置页缺 setting_resume_strategy_title", ui.contains("setting_resume_strategy_title"))
        assertTrue("设置页缺「发送继续」选项文案", ui.contains("setting_resume_strategy_continue"))
        assertTrue("设置页缺「重新生成」选项文案", ui.contains("setting_resume_strategy_regenerate"))
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        listOf(
            "setting_resume_strategy_title",
            "setting_resume_strategy_desc",
            "setting_resume_strategy_continue",
            "setting_resume_strategy_regenerate",
        ).forEach { key ->
            assertTrue("英文缺文案 $key", en.contains("name=\"$key\""))
            assertTrue("中文缺文案 $key", zh.contains("name=\"$key\""))
        }
    }

    @Test
    fun `撞输出上限强制走继续_不受续跑方式影响(v288)`() {
        // 用户拍板的取舍（做法 A）：撞输出上限时「重新生成」必然再撞同一个上限，
        // 是必然失败而不是取舍，所以那条路强制发「继续」，不看用户选的方式。
        val handler = source(handlerPath)
        // 续跑输入构造器：定义 1 处 + 截断路径 1 处 + 中断路径的 CONTINUE 分支 1 处
        assertEquals(
            "buildResumeProviderInput 的定义与两处调用都必须在（截断路径 + 中断路径的继续分支）",
            3,
            Regex("buildResumeProviderInput").findAll(handler).count()
        )
        // 截断判定块里只能出现在「继续」这一条路上：整个引擎只允许读一次 resumeStrategy，
        // 读出来存进 regenerating，之后都用它 —— 这样截断那条路不可能悄悄接上开关。
        assertEquals(
            "引擎里读取续跑方式的地方必须只有一处（存进 regenerating 后共用）",
            1,
            Regex("resumeStrategy == ResumeStrategy\\.REGENERATE").findAll(handler).count()
        )
        assertTrue(
            "缺少方式判断的落点 val regenerating",
            handler.contains("val regenerating = resumeStrategy == ResumeStrategy.REGENERATE")
        )
        assertTrue(
            "缺少按方式分派的分支 if (regenerating)",
            handler.contains("if (regenerating) {")
        )
        assertTrue(
            "截断路径的注释必须写明「强制走继续」的理由，防止以后有人顺手接上开关",
            handler.contains("撞输出上限这条路强制走「继续」")
        )
        // REGENERATE 时必须把已记的续跑次数搬到基线上，否则数字会随被丢弃的半截一起消失
        assertTrue(
            "REGENERATE 分支必须把 resumeCount 写回展示基线",
            handler.contains("responseBaseMessages = responseBaseMessages.toMutableList()") &&
                handler.contains("list[baseIdx] = list[baseIdx].copy(")
        )
        // 重新生成有自己的状态提示（否则用户看不出是在重来还是在续写）
        val en = source("app/src/main/res/values/strings.xml")
        val zh = source("app/src/main/res/values-zh/strings.xml")
        assertTrue("英文缺重新生成状态提示", en.contains("name=\"chat_generation_interrupted_regenerating\""))
        assertTrue("中文缺重新生成状态提示", zh.contains("name=\"chat_generation_interrupted_regenerating\""))
        assertTrue(
            "引擎必须按方式分派状态提示",
            handler.contains("R.string.chat_generation_interrupted_regenerating")
        )
    }
}
