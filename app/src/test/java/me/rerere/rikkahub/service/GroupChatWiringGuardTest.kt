package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v313：AI 讨论群组的**接线守卫**。
 *
 * ⚠️ 定位先说清楚：这个文件全部是「读源码做字符串比对」，它防的是
 * **将来合并上游或重构时把关键接线改回旧写法**，**不证明逻辑正确**。
 * 逻辑正确性由 GroupChatTest（真跑纯函数与状态机）+ 用户真机验收负责。
 *
 * 为什么群功能特别需要这一层：它的两条 P0（主持人污染讨论记录、等回答时永久卡死）
 * 都是「删掉一行参数或一个按钮就静默复发」的形态，编译器与真跑测试都抓不到。
 */
class GroupChatWiringGuardTest {

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

    /**
     * v315 复审位抓的教训：`substringBefore` 找不到分隔符时会**返回整串**，
     * 切片就从「这个函数」静默退化成「整份文件」—— 反向断言会误红、正向断言会变假绿，
     * 而守卫文件本身不会报任何错。所以切片前必须先确认两个分隔符都存在，缺一个直接判失败。
     */
    private fun sliceSection(source: String, from: String, to: String): String {
        assertTrue("切片起点不存在，守卫会静默失效：$from", source.contains(from))
        assertTrue("切片终点不存在，守卫会静默失效：$to", source.contains(to))
        return source.substringAfter(from).substringBefore(to)
    }

    private val base = "app/src/main/java/me/rerere/rikkahub/"
    private val engine = source(base + "service/GroupChatEngine.kt")
    private val run = source(base + "service/GroupChatRun.kt")
    private val chatService = source(base + "service/ChatService.kt")
    private val panel = source(base + "ui/pages/chat/GroupChatPanel.kt")
    private val mainModel = source(base + "data/model/GroupChat.kt")
    private val chatPage = source(base + "ui/pages/chat/ChatPage.kt")
    private val chatVM = source(base + "ui/pages/chat/ChatVM.kt")
    private val store = source(base + "data/datastore/PreferencesStore.kt") +
        "\n" + source(base + "data/datastore/Settings.kt") // v323：官方 3.1 把 Settings 字段拆到 Settings.kt，判据改读两个文件（断言内容未改）

    // ---- P0-1：主持人挑人不得写进对话 ----

    @Test
    fun `主持人那次调用必须显式关闭落库`() {
        assertTrue(
            "Host.speak 必须有 persist 参数",
            engine.contains("persist: Boolean = true,"),
        )
        assertTrue(
            "主持人调用必须传 persist = false",
            engine.contains("persist = false,"),
        )
        assertTrue(
            "实现方必须真的按 persist 分岔（流式那一段）",
            chatService.contains("if (!persist) return@collect"),
        )
        assertTrue(
            "收尾落库也必须受 persist 控制",
            chatService.contains("if (finished != null && persist)"),
        )
    }

    // ---- P0-2：等待回答必须有出口 ----

    @Test
    fun `等待回答时面板必须给出出口`() {
        assertTrue(
            "状态机要有放弃等待的出口",
            run.contains("fun markQuestionAbandoned()"),
        )
        assertTrue(
            "等待回答时必须显示「跳过提问」",
            panel.contains("onClick = onSkipQuestion"),
        )
        assertTrue(
            "主按钮必须由纯函数决定（等回答时自然变成「跳过提问」，不会错误地给出「继续讨论」）",
            panel.contains("when (primary)"),
        )
        assertTrue(
            "停止按钮必须覆盖「正在说话」与「正在等你回答」" +
                "（圆桌 v239 的教训：该有的出口不能消失；v314 起停下后它会消失，" +
                "因为那时已经有「继续讨论」这个出口）",
            panel.contains("if (busy || waiting)"),
        )
        assertTrue(
            "服务层要有跳过入口",
            chatService.contains("fun skipGroupQuestion(conversationId: Uuid)"),
        )
        assertTrue(
            "提问已被作废时要走兜底（否则判据永远不再成立）",
            chatService.contains("run.markQuestionAbandoned()"),
        )
    }

    // ---- 主对话红线：普通对话不许被牵连 ----

    @Test
    fun `用户回答提问时先问群引擎，不是群才走主对话原路`() {
        assertTrue(
            "分岔必须在 handleToolAnswer 里",
            chatVM.contains("if (chatService.answerGroupQuestion(_conversationId, toolCallId, answer)) return"),
        )
        assertTrue(
            "分岔后仍保留主对话原调用（普通对话行为一字不变）",
            chatVM.contains(
                "chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)"
            ),
        )
        // answerGroupQuestion 的第一道闸：没有群 run 立刻返回 false
        assertTrue("切片锚点必须存在", chatService.contains("fun answerGroupQuestion("))
        val answer = chatService.substringAfter("fun answerGroupQuestion(")
        assertTrue(
            "没有群 run 必须立刻返回 false，不做任何副作用",
            answer.contains("val run = groupRuns[conversationId] ?: return false"),
        )
        assertTrue(
            "不在等待回答状态也必须立刻返回 false",
            answer.contains("if (!run.isWaitingForUserAnswer()) return false"),
        )
    }

    @Test
    fun `群面板没建过群时完全不渲染`() {
        assertTrue(
            "state 为空且没建过群 → 直接 return",
            panel.contains("if (state == null && !hasConfig) return"),
        )
    }

    @Test
    fun `群生成用自己的续写次数，不借主对话那条滑条`() {
        // v313 这里原本是「群发言一律不许传 truncationAutoResumeMax」—— v314 用户拍板改了：
        // 「成员说到一半被掐断就真的停在半截」，要求群也能自动接着说完。
        // 但**次数必须来自群自己的设置**（GroupChatConfig.resumeMax），
        // 主对话那条滑条 settings.truncationAutoResumeMax 在群里一个字都不许出现 ——
        // 那才是「借主对话的开关」，会连带改变主对话行为（禁区 #13）。
        val groupTurn = sliceSection(
            chatService,
            "private suspend fun speakGroupTurn(",
            "private fun tryGroupInterjection(",
        )
        assertTrue(
            "群发言必须接上续写次数（来自群自己的设置）",
            groupTurn.contains("truncationAutoResumeMax = groupResumeMax,"),
        )
        assertTrue(
            "次数必须真的读 GroupChatConfig.resumeMax",
            groupTurn.contains("groupChatConfigs[conversationId]?.resumeMax"),
        )
        assertFalse(
            "群发言不许读主对话那条滑条",
            groupTurn.contains("settings.truncationAutoResumeMax"),
        )
        assertTrue(
            "接话方式跟随用户全局选的那个（用户 v314 拍板）",
            groupTurn.contains("resumeStrategy = settings.resumeStrategy,"),
        )
        assertTrue(
            "主持人那次调用（persist = false）不续写：它只要一个名字，续写白花钱",
            groupTurn.contains("val groupResumeMax = if (persist)"),
        )
        assertFalse(
            "群发言仍然不许传 highlightKeyPoints",
            groupTurn.contains("highlightKeyPoints ="),
        )
        assertFalse(
            "群发言仍然不许传 toolOutputCharLimit（那是子代理专用）",
            groupTurn.contains("toolOutputCharLimit ="),
        )
    }

    // ---- v321：「讨论只保留发言正文」接线守卫 ----

    @Test
    fun `v321 讨论只保留发言正文的接线必须完整（不许做成假开关）`() {
        // 这条守的是「编译照过、门禁全绿、开关按了却没反应」那一类退化。
        // 本仓库在 v313 就专门反对过没实现的骗人开关（GroupChatConfig 里那个
        // firstRoundBlind 就是因为引擎没实现而被删掉，不是留着骗人）。
        val prompts = source(base + "data/ai/prompts/GroupChat.kt")
        val loop = source(base + "data/ai/GenerationLoop.kt")
        // ① 引擎三处上文（成员发言 / 主持人挑人 / 答完提问续写）都必须**现读**全局设置。
        //    任何一处被改成字面量 false，界面上那个开关就管不到那一路 —— 三处口径会漂。
        assertEquals(
            "引擎必须有三处把全局设置传给 transcriptFor",
            3,
            Regex("settings\\.groupChatCleanContext,").findAll(engine).count(),
        )
        // ② 主对话那条路必须按设置传，不许写死成 true 或 false
        assertTrue(
            "主对话必须按用户设置决定是否连思考一起剥",
            chatService.contains("stripGroupReasoningParts = settings.groupChatCleanContext,"),
        )
        // ③ 群成员自己那条路**不许**传这两个剥参数：它要看得见自己刚搜到的东西
        //    （v319 定下的口径）。传上参数编译照样通过，症状却是成员开始重复搜索、
        //    忘了上一句的依据 —— 编译器与真跑测试都抓不到，只能靠这条反向断言钉。
        val groupTurn = sliceSection(
            chatService,
            "private suspend fun speakGroupTurn(",
            "private fun tryGroupInterjection(",
        )
        assertFalse(
            "群发言不许传 stripGroupToolParts（成员要看得见自己搜到的东西）",
            groupTurn.contains("stripGroupToolParts ="),
        )
        assertFalse(
            "群发言不许传 stripGroupReasoningParts（同上）",
            groupTurn.contains("stripGroupReasoningParts ="),
        )
        // ④ 设置默认必须关，且缺键时读到 false（用 `== true`，不用 `!= false`）
        assertTrue(
            "设置字段必须默认 false",
            store.contains("val groupChatCleanContext: Boolean = false,"),
        )
        assertTrue(
            "读取必须用 == true，缺键不能当 true",
            store.contains("groupChatCleanContext = preferences[GROUP_CHAT_CLEAN_CONTEXT] == true,"),
        )
        // ⑤ 界面开关必须走**全局**回调。为什么不塞进 GroupChatConfig：那份配置会被
        //    「彻底删除」（v315 的动作），塞进去就会在删配置后静默失效。
        assertTrue(
            "界面开关必须调用全局回调",
            panel.contains("onChange = { onCleanContextChange(it) },"),
        )
        assertFalse(
            "不许把这个开关塞进群配置（那条路会被彻底删除清掉）",
            mainModel.contains("cleanContext"),
        )
        // ⑥ 生成层两处默认都必须是 false（禁区 #13：新能力默认关闭、显式传参才打开）
        assertEquals(
            "生成层 generateText 与内部函数的新参数默认都必须是 false",
            2,
            Regex("stripGroupReasoningParts: Boolean = false,").findAll(loop).count(),
        )
        // ⑦ 剥离只做在「发给模型的请求视图」上：必须返回新对象，不许改传入的消息
        //    （用户选的是「记录一个字都不删」，界面上仍可回看群里当时查了什么）
        assertTrue(
            "剥完必须 copy 出新消息，不许就地改",
            prompts.contains("return copy(parts = kept)"),
        )
    }

    // ---- v322：群设置里「换」按钮点了没反应 ----

    @Test
    fun `v322 换人按钮必须真的打开选择弹层（点了没反应的老毛病）`() {
        // 真机症状：群讨论设置里点「换」毫无反应。
        // 根因：「换」的 onClick 只写了 `swapIndex = index`，**没有 picker.open()**；
        // 而 ModelListSheet 第一行是 `if (!state.visible) return`（ModelList.kt:272），
        // visible 只由 open() 打开 —— 弹层永远不渲染，下面 onSelect 里那套
        // 「原地替换、重复成员自动挪位」的逻辑写得再对也永远触发不到。
        // 这类退化编译器抓不到（少一行调用照样编译），只能靠这条接线守卫钉住。
        val dialog = sliceSection(
            panel,
            "internal fun GroupChatSettingsDialog(",
            "private fun currentDraftConfig(",
        )
        assertTrue(
            "「换」必须同时做两件事：记下待替换位置 + 打开选择弹层",
            Regex("swapIndex = index\\s*\\n\\s*picker\\.open\\(\\)").containsMatchIn(dialog),
        )
        assertTrue(
            "「添加成员」必须显式清掉待替换标记（否则加人会被当成换人）",
            Regex("swapIndex = null\\s*\\n\\s*picker\\.open\\(\\)").containsMatchIn(dialog),
        )
        assertTrue(
            "弹层关闭后必须清掉待替换标记（点了「换」又取消的场景）",
            // 连 effect 体一起钉住：只钉 `LaunchedEffect(picker.visible)` 这串文本的话，
            // 将来把条件写成 `if (picker.visible)`（语义反了 —— 弹层一打开就把标记清掉，
            // 换人退化成加人）守卫仍然全绿，那就是假守卫（v322 审查位抓的 P2）。
            Regex(
                "LaunchedEffect\\(picker\\.visible\\)\\s*\\{\\s*\\n\\s*" +
                    "if \\(!picker\\.visible\\) swapIndex = null"
            ).containsMatchIn(dialog),
        )
        // 这两条正则要求「两行紧邻」（中间只允许空白）。将来若把它们提取成 helper、
        // 或只是插入一行注释而变红，请先说明理由再改判据 —— 不要当成假红直接放宽，
        // 因为漏掉的正是「少写一行调用」这种编译器抓不到的退化（本次真机 bug 就是这一类）。
    }

    // ---- v314：插话（我说的话进入讨论，由成员接着回应） ----

    @Test
    fun `插话只在建过群的对话里生效，普通对话一字不变`() {
        val send = sliceSection(
            chatService,
            "fun sendMessage(conversationId: Uuid",
            "fun enqueueVoiceMessage(",
        )
        assertTrue(
            "sendMessage 必须先问群插话",
            send.contains("if (answer && tryGroupInterjection(conversationId, content)) return"),
        )
        val interjection = sliceSection(
            chatService,
            "private fun tryGroupInterjection(",
            "fun startGroupDiscussion(",
        )
        assertTrue(
            "判据只有一条：这个对话建过群；没有群配置（或已暂停）立刻返回 false",
            interjection.contains(
                "val config = settings.groupChatConfigs[conversationId]?.takeIf { it.enabled } ?: return false"
            ),
        )
        assertTrue(
            "插话内容必须立刻入库（否则下一位成员在上文里看不到我这句话）",
            interjection.contains("mutateAndSaveConversation(conversationId)"),
        )
        assertTrue(
            "没在跑时要顺手开一批（用户 v314 拍板 A：我插一句，大家接着说）",
            interjection.contains("shouldAutoContinueAfterInterjection(status)"),
        )
    }

    @Test
    fun `插话绝不许触发助手的普通回复`() {
        // 插话一旦走进官方那条 handleMessageComplete，群讨论会悄悄变回一对一聊天，
        // 而且用户看不到任何提示。判据：群插话分支里不许出现消息队列或助手回复调用。
        val interjection = sliceSection(
            chatService,
            "private fun tryGroupInterjection(",
            "fun startGroupDiscussion(",
        )
        assertFalse(
            "群插话不许把消息塞进队列（那会触发助手的普通回复）",
            Regex("messageQueue\\s*\\.").containsMatchIn(interjection),
        )
        assertFalse(
            "群插话不许调用 handleMessageComplete",
            Regex("handleMessageComplete\\s*\\(").containsMatchIn(interjection),
        )
    }

    // ---- v314：面板三问（怎么开始 / 怎么停 / 怎么插话） ----

    @Test
    fun `面板三个动作都要看得见`() {
        assertTrue("要有开始/继续的大按钮", panel.contains("onClick = onContinue"))
        assertTrue("要有停止", panel.contains("onClick = onStop"))
        assertTrue("要有插话按钮", panel.contains("onClick = onInterject"))
        assertTrue("插话按钮文案要说人话", panel.contains("✏️ 我要插话"))
        assertTrue(
            "面板上必须写明怎么插话（v313 用户完全不知道能插话）",
            panel.contains("你这句话会进入讨论"),
        )
        // v316：两个入口都得分清「第一次开始」与「接着聊」，措辞跟着界面走
        assertTrue(
            "写题目入口要分清开始与继续",
            panel.contains("✍️ 写题目开始") && panel.contains("✍️ 写题目继续"),
        )
        assertTrue(
            "直接开跑入口也要分清开始与继续",
            panel.contains("▶ 直接开始讨论") && panel.contains("▶ 直接继续讨论"),
        )
        assertTrue(
            "写题目入口要告诉用户能加附件（本轮改动的全部意义）",
            panel.contains("可加图片/文件"),
        )
        assertTrue(
            "ChatPage 要把插话回调接上",
            chatPage.contains("onInterject = {"),
        )
        assertTrue(
            "「我要插话」必须真的把光标送进输入框",
            chatPage.contains("focusRequestSignal = groupInterjectSignal"),
        )
        assertTrue(
            "输入框要接受这个信号（默认 0 = 行为不变）",
            source(base + "ui/components/ai/ChatInput.kt").contains("focusRequestSignal: Int = 0,"),
        )
    }

    @Test
    fun `停下之后停止按钮要消失，用户才看得出点生效了`() {
        // v313 里停止按钮一到非 IDLE 就一直挂着，用户点了以为没反应（v314 用户原话）。
        // 取舍：让它在「没有东西在跑」时消失，但仍然保证**每个状态都有出口** ——
        // 停着的时候有「开始/继续讨论」，等回答的时候有「跳过这次提问」（圆桌 v239 的规矩）。
        assertTrue(
            "停止按钮只在真的能停时出现",
            panel.contains("if (busy || waiting)"),
        )
        assertTrue(
            "停着的时候必须有继续入口（否则用户才是真被困住）",
            panel.contains("GroupPrimaryAction.DISCUSSION ->"),
        )
        assertTrue(
            "等回答时必须有出口",
            panel.contains("onClick = onSkipQuestion"),
        )
    }

    // ---- v314：续写次数设置 + 退出口 ----

    @Test
    fun `群设置里有续写次数、预选方案，暂停与彻底删除必须分开`() {
        assertTrue("弹窗要有续写次数滑块", panel.contains("GroupChatConfig.MAX_RESUME_MAX"))
        assertTrue(
            "保存时必须真的写 resumeMax（不能沿用 initial 那个死值）",
            panel.contains("resumeMax = resumeMax,"),
        )
        // v315：一键套用成员组合（用户口径：「没有预设方案选择……选一个一键套用，不用自己一个个挑」）
        // v316：v315 的「快速开始（一键选成员）」降级成兜底并改名，用户自己攒的方案另有区块
        assertTrue("弹窗要有系统挑人区", panel.contains("快速开始（系统帮你挑人）"))
        assertTrue("弹窗要有用户方案区", panel.contains("我的方案（一键整套套用）"))
        assertTrue(
            "要真的调用预设函数填成员",
            panel.contains("applyGroupPreset(kind, availableForPreset)"),
        )
        // v315：暂停保留配置 vs 彻底删除清空 —— 两个动作不能混成一个
        assertTrue("要有暂停/启用入口", panel.contains("onPauseToggle(!initial.enabled)"))
        assertTrue("要有彻底删除入口（二次确认）", panel.contains("onClick = { confirmDelete = true }"))
        assertTrue("暂停要落到 VM", chatPage.contains("vm.setGroupEnabled(enabled)"))
        assertTrue("彻底删除要落到 VM", chatPage.contains("vm.deleteGroupConfig()"))
        assertTrue(
            "暂停只改 enabled，绝不许删配置（用户口径：暂停后成员和次数都要留着）",
            chatVM.contains("current.copy(enabled = enabled)"),
        )
        assertTrue(
            "只有彻底删除才动 groupChatConfigs",
            chatVM.contains("settings.groupChatConfigs - _conversationId"),
        )
        // v315 曾要求「点开始先弹框」；v316 用户改口径为「走输入框，好配图片/文件」，
        // 于是弹框撤掉、改成两个入口并存。断言跟着钉新现实，不留旧口径的假绿。
        assertTrue(
            "面板要有「写题目开始」入口（走输入框，可加图片/文件）",
            panel.contains("onClick = onStartWithTopic"),
        )
        assertTrue(
            "「写题目开始」必须真的把光标送进输入框（复用 v314 的 focusRequestSignal 通道）",
            chatPage.contains("onStartWithTopic = {") &&
                chatPage.contains("groupInterjectSignal += 1"),
        )
        assertTrue(
            "「直接开跑」入口也要留着（v315 的「不写题目也能开始」是明确能力，不许悄悄砍掉）",
            panel.contains("onClick = onContinue"),
        )
        assertTrue(
            "直接开跑要落到 VM",
            chatPage.contains("onContinue = { vm.startGroupDiscussion() }"),
        )
        assertTrue(
            "服务层要把题目作为用户自己的发言入库（不然成员看不到）",
            chatService.contains("trimmedTopic.isNotEmpty()"),
        )
    }

    @Test
    fun `暂停的群不许再拦截用户输入`() {
        // 暂停 = 配置留着但不用：这时用户在对话里打字必须走普通聊天（助手回复），
        // 而不是被当成插话吞进讨论；引擎也不能继续跑。
        assertTrue(
            "插话判据必须排除暂停态",
            chatService.contains("settings.groupChatConfigs[conversationId]?.takeIf { it.enabled } ?: return false"),
        )
        assertTrue(
            "开跑判据也要排除暂停态",
            chatService.contains("settings.groupChatConfigs[conversationId]?.takeIf { it.enabled } ?: return"),
        )
        assertTrue(
            "引擎遇到暂停的群要停下",
            engine.contains("settings.groupChatConfigs[conversation.id]?.takeIf { it.enabled }"),
        )
        assertTrue(
            "面板要有暂停态开关（否则用户看不到「重新启用」）",
            chatPage.contains("paused = groupConfig?.enabled == false"),
        )
        assertTrue(
            "面板本身要接住这个开关",
            panel.contains("paused: Boolean,"),
        )
        assertTrue(
            "面板暂停态要有启用按钮",
            panel.contains("▶ 重新启用讨论群"),
        )
    }

    @Test
    fun `群功能不用临时指令写进对话`() {
        // 引擎给成员的上文 = 历史 + 一条不入库的指令。落库只走 upsertGroupUtterance，
        // 绝不能用 updateCurrentMessages（它按下标整表对齐，会把那条指令一起存进去）
        val groupSection = sliceSection(
            chatService,
            "// ---- v313：AI 讨论群组 ----",
            "// ---- 处理工具调用审批 ----",
        )
        // ⚠️ 只认**调用语法**，不能直接 contains 这个名字：本文件上方的注释里
        // 就写着「刻意不用 updateCurrentMessages」，宽断言会被自己的注释打红
        // （手册 §7.3 记过这个坑：反向断言的字面量会误伤解释它的注释）。
        assertFalse(
            "群接线里不许真的调用 updateCurrentMessages",
            Regex("updateCurrentMessages\\s*\\(").containsMatchIn(groupSection),
        )
        assertTrue(
            "必须走带 targetMessageId 的 upsert",
            groupSection.contains("upsertGroupUtterance("),
        )
    }

    // ---- 存储：不动数据库版本 ----

    @Test
    fun `群设置存 DataStore 而不是加数据库列`() {
        assertTrue(store.contains("val GROUP_CHAT_CONFIGS = stringPreferencesKey(\"group_chat_configs\")"))
        assertTrue(store.contains("preferences[GROUP_CHAT_CONFIGS] ="))
        assertTrue(store.contains("groupChatConfigs = preferences[GROUP_CHAT_CONFIGS]"))
        assertTrue(store.contains("val groupChatConfigs: Map<Uuid, GroupChatConfig> = emptyMap(),"))
        // 解码必须容错：坏 JSON 不能把整个 Settings 读崩
        assertTrue("切片锚点必须存在", store.contains("groupChatConfigs = preferences[GROUP_CHAT_CONFIGS]"))
        val decode = store.substringAfter("groupChatConfigs = preferences[GROUP_CHAT_CONFIGS]")
        assertTrue("解码必须 runCatching", decode.contains("runCatching"))
        assertTrue("坏数据要退回空表", decode.contains("getOrDefault(emptyMap())"))

        // 数据库版本没动
        val db = source(base + "data/db/AppDatabase.kt")
        assertTrue("AppDatabase 必须保持 32（v1017 用户授权31升32）", db.contains("version = 32,"))
        val agentDb = source(base + "agent/db/AgentDatabase.kt")
        assertTrue("AgentDatabase 必须保持 3", agentDb.contains("version = 3,"))
    }

    // ---- 入口必须闭环，否则功能等于不存在 ----

    @Test
    fun `群设置入口与弹窗接线闭环`() {
        assertTrue(
            "FilesPicker 要有群入口参数",
            source(base + "ui/components/ai/FilesPicker.kt").contains("onOpenGroupSettings: () -> Unit = {}"),
        )
        assertTrue(
            "ChatPage 要把状态与回调传给面板",
            chatPage.contains("onSkipQuestion = { vm.skipGroupQuestion() }"),
        )
        assertTrue(
            "ChatPage 要渲染设置弹窗",
            chatPage.contains("GroupChatSettingsDialog("),
        )
        assertTrue(
            "弹窗保存要落到 VM",
            chatPage.contains("vm.saveGroupConfig(config)"),
        )
        assertTrue(
            "VM 要写进 groupChatConfigs",
            chatVM.contains("groupChatConfigs = settings.groupChatConfigs + (_conversationId to config)"),
        )
    }

    @Test
    fun `主持人必须能在界面上选出来`() {
        // 审查位抓到：选了「主持人安排」却没有任何地方指定是谁 → 引擎退回轮流，这个模式在界面上根本用不了
        assertTrue(panel.contains("moderatorPicker.open()"))
        assertTrue(panel.contains("onSelect = { model: Model -> moderator = model.id }"))
        assertTrue(
            "保存时必须真的写 moderatorModelId（不能沿用 initial 那个死值）",
            panel.contains("moderatorModelId = moderator,"),
        )
    }

    @Test
    fun `界面不许留没实现的假开关`() {
        // v313 自查删掉了 firstRoundBlind：引擎没实现它，留着就是骗人的开关。
        // 反向断言：不许再出现「界面有开关、引擎不读」的组合。
        assertFalse(panel.contains("firstRoundBlind"))
        assertFalse(engine.contains("firstRoundBlind"))
        assertFalse(run.contains("firstRoundBlind"))
    }

    @Test
    fun `取消异常必须原样上抛，不许被当成成员失败`() {
        // 用户点停止 = 立刻停，不能变成「这个成员失败了，换下一个继续烧钱」
        val catchBlocks = Regex("catch \\(e: CancellationException\\)").findAll(engine).count()
        assertTrue(
            "引擎里每个 catch(Exception) 之前都要有 CancellationException 分支",
            catchBlocks >= 2,
        )
        // v320：落 STOPPED 之前必须先对场次编号 —— 被新一批顶掉的旧协程收尾时不许盖状态
        //（旧断言钉的是「有 markStopped() 这一行」这个字面写法；本轮把它换成了带场次校验的版本，
        //  按手册口径是**更新判据并写明理由**，不是把代码改回去迎合断言。）
        assertEquals("两处取消收尾都要走场次校验", 2, Regex("run\\.markStoppedIfCurrent\\(epoch\\)").findAll(engine).count())
        assertFalse("引擎里不许再有无条件的 markStopped()", engine.contains("run.markStopped()"))
    }

    // ---- v314 审查位抓出的三条（都核实为真，别改回去） ----

    @Test
    fun `插话不许把成员提问顶掉`() {
        // 成员提问期间用户插话 → 最后一条消息变成用户消息。只认「最后一条」的定位会失配：
        // 回答会掉回主对话路径（A 提问 B 回答），面板还卡在「有成员在等你回答」。
        assertTrue(
            "必须有一个「从后往前找那条挂着提问的助手消息」的入口",
            chatService.contains("private fun findPendingGroupAsk("),
        )
        val helper = sliceSection(
            chatService,
            "private fun findPendingGroupAsk(",
            "fun answerGroupQuestion(",
        )
        assertTrue("要找的是带未执行提问的那条消息", helper.contains("currentMessages.asReversed()"))
        assertFalse("helper 里不许再只认最后一条", helper.contains("currentMessages.lastOrNull()"))
        assertTrue(
            "回答提问与跳过提问都必须走这个 helper（定义 1 + 使用 2）",
            Regex("findPendingGroupAsk\\(").findAll(chatService).count() >= 3,
        )
        assertFalse(
            "提问定位不许再直接取最后一条消息",
            Regex("val pendingAsk = lastMessage\\?\\.getTools\\(\\)").containsMatchIn(chatService),
        )
        assertTrue(
            "成员答完接着写时，也要从后往前找回那条提问消息（否则一次发言会被拆成两条）",
            engine.contains("currentMessages.asReversed()"),
        )
    }

    @Test
    fun `解散群之后面板必须能真正消失`() {
        // 只删配置不清状态 → 面板残留成空壳：「继续讨论」点下去因为配置没了直接 return。
        assertTrue(
            "服务层要有清状态的入口",
            chatService.contains("internal fun clearGroupRunState(conversationId: Uuid)"),
        )
        assertTrue(
            "清状态必须把 flow 的值置空（remove 掉的话订阅方永远收不到 null，面板下不去）",
            chatService.contains("groupRunFlows[conversationId]?.value = null"),
        )
        // v314 复审位抓的竞态：被取消的 job 收尾时还会 publish 一次，把刚清空的状态写回去
        assertTrue(
            "publish 必须先核对「这条 run 还归我管」",
            chatService.contains("if (groupRuns[snapshot.conversationId] !== run) return"),
        )
        assertTrue(
            "解散要落到 VM",
            chatVM.contains("chatService.clearGroupRunState(_conversationId)"),
        )
        assertTrue(
            "解散前要先停下正在跑的讨论",
            chatVM.contains("chatService.stopGroupDiscussion(_conversationId)"),
        )
    }

    @Test
    fun `插话与面板必须用同一个「成员够不够」口径`() {
        // hasEnoughMembers 只数 id，模型被删掉时它会说够、面板却说不够 —— 于是插话会开出一批
        // 必然空转的讨论（复审位 v314 抓的口径不一致）。
        val interjection = sliceSection(
            chatService,
            "private fun tryGroupInterjection(",
            "fun startGroupDiscussion(",
        )
        assertTrue(
            "插话必须按「模型是否真的还在」过滤",
            interjection.contains("sanitizeGroupMembers(config.memberModelIds, existingModelIds)"),
        )
        assertFalse(
            "插话不许再用只数 id 的 hasEnoughMembers",
            interjection.contains("config.hasEnoughMembers"),
        )
        assertTrue(
            "面板要用同一套口径（按模型是否存在来数）",
            chatPage.contains("count { it in existing } >= GroupChatConfig.MIN_MEMBERS"),
        )
    }

    @Test
    fun `面板主按钮只有一个来源，每个取值都被界面消费`() {
        // 审查位指出旧写法里 SKIP_QUESTION / NONE 两个返回值从没被界面用过 = 半个假开关
        assertTrue("面板必须用 when 全量分派", panel.contains("when (primary)"))
        listOf(
            "GroupPrimaryAction.DISCUSSION ->",
            "GroupPrimaryAction.SKIP_QUESTION ->",
            "GroupPrimaryAction.NONE ->",
        ).forEach { branch ->
            assertTrue("每个取值都要被消费：$branch", panel.contains(branch))
        }
        assertTrue(
            "等回答时保留一道独立兜底（纯函数被改坏也不许焊死）",
            panel.contains("if (waiting && primary != GroupPrimaryAction.SKIP_QUESTION)"),
        )
        assertTrue(
            "成员不足时面板要说清楚（不然用户只看到「点了没反应」）",
            panel.contains("membersEnough") && panel.contains("成员不足两个"),
        )
        assertTrue(
            "ChatPage 要把成员够不够算给面板",
            chatPage.contains("membersEnough = groupMembersEnough,"),
        )
        assertTrue(
            "面板必须按纯函数的**当前签名**调用（v314 曾漏改调用点，导致编译直接失败）",
            panel.contains("resolveGroupPrimaryAction(status)"),
        )
        assertFalse(
            "面板不许再传第二个实参（纯函数已收敛成一参）",
            panel.contains("resolveGroupPrimaryAction(status, hasSpokenBefore)"),
        )
    }

    @Test
    fun `插话顺手清掉猜你想问`() {
        val interjection = sliceSection(
            chatService,
            "private fun tryGroupInterjection(",
            "fun startGroupDiscussion(",
        )
        assertTrue(
            "插话入库时要清 chatSuggestions（旧建议点一下会塞进输入框，语境不符）",
            interjection.contains("chatSuggestions = emptyList(),"),
        )
    }

    // ===== v316：用户自己攒的方案 —— 界面开关必须与底层行为同一次落地 =====

    @Test
    fun `用户方案区必须真的清洗套用并真的落盘`() {
        val dialog = sliceSection(panel, "internal fun GroupChatSettingsDialog(", "private fun PresetNameDialog")
        assertTrue("方案按钮点击要真的填入成员", dialog.contains("members = applied.memberModelIds"))
        assertTrue("方案按钮点击要真的填入批次", dialog.contains("turns = applied.turnsPerRun"))
        assertTrue("方案按钮点击要真的填入续写次数", dialog.contains("resumeMax = applied.resumeMax"))
        // 复审位 v316 抓的 P2：清洗口径必须与「添加成员」选择器一致（enabled + CHAT），
        // 放宽成「全部模型」会把停用供应商 / 画图模型重新塞进成员
        assertTrue("套用前要按可用口径清洗", dialog.contains("applySavedPreset(preset, applyableModelIds)"))
        assertTrue(
            "可用口径必须是 enabled 供应商 + CHAT 类型",
            panel.contains("provider.enabled") && panel.contains("model.type == ModelType.CHAT"),
        )
        assertTrue("套用结果要说人话", dialog.contains("presetApplyMessage(preset.name, applied)"))
        // 🔴 复审位 v316 抓的 P0：管理入口（改名/覆盖/删除）原本靠「长按」手势，
        // 挂在 Material3 按钮自己的 modifier 外层 —— 按钮内置点击先消费按下事件，
        // 外层长按收不到，管理框永远打不开。所以必须用**看得见的具体按钮**，
        // 并且反向钉住「不许再把那套手势挂在按钮上」。
        assertTrue("管理入口必须是可见按钮（⋯）", dialog.contains("Text(\"⋯\""))
        assertTrue("点 ⋯ 要真的打开管理框", dialog.contains("managingPreset = preset"))
        assertFalse(
            "不许再把手势修饰符加在方案按钮的 modifier 上（会与按钮内置点击抢事件）",
            dialog.contains("combinedClickable"),
        )

        // VM 侧必须真的写 DataStore，否则是骗人的开关（v313 教训）
        assertTrue("存方案要落到 VM", chatPage.contains("vm.requestSaveGroupPreset(name, config)"))
        assertTrue("覆盖方案要落到 VM", chatPage.contains("vm.requestOverwriteGroupPreset(id, config)"))
        assertTrue("改名要落到 VM", chatPage.contains("vm.requestRenameGroupPreset(id, name)"))
        assertTrue("删方案要落到 VM", chatPage.contains("vm.requestDeleteGroupPreset(id)"))
        assertTrue("VM 存方案要写 groupChatPresets", chatVM.contains("groupChatPresets = settings.groupChatPresets + preset"))
        assertTrue("删方案按 id 剔除", chatVM.contains("filterNot { it.id == presetId }"))
        // 复审位 v316 抓的 P2：写盘是异步的，返回值不能代表成败 —— 必须有回执通道，
        // 界面据回执报成功/失败；且取消异常必须原样上抛
        assertTrue("要有方案写操作回执流", chatVM.contains("MutableStateFlow<GroupPresetOpResult?>(null)"))
        assertTrue("回执要能被界面消费", chatVM.contains("fun consumeGroupPresetOp()"))
        assertTrue("写盘要统一走 persistPresetOp", chatVM.contains("private fun persistPresetOp("))
        assertTrue("取消异常必须原样上抛", chatVM.contains("catch (e: CancellationException) {") && chatVM.contains("throw e"))
        assertTrue("界面要监听回执而不是返回值", chatPage.contains("vm.groupPresetOp.collectAsStateWithLifecycle()"))
        assertTrue("回执文案走纯函数", mainModel.contains("fun presetOpMessage(result: GroupPresetOpResult)"))
        // 复审位抓的 P2：存满绝不许静默挤掉最旧一条（那是悄悄丢用户数据）
        assertFalse(
            "不许再用 takeLast 静默丢掉用户已存的方案",
            chatVM.contains("takeLast(GroupChatPreset.MAX_PRESETS)"),
        )
        assertTrue("存满必须当场拒绝并说明", chatVM.contains("validateNewPresetName(name, presets)"))

        // 存储五环节缺一不可
        assertTrue("要有 DataStore 键", store.contains("val GROUP_CHAT_PRESETS = stringPreferencesKey(\"group_chat_presets\")"))
        assertTrue("要真的写入", store.contains("preferences[GROUP_CHAT_PRESETS] = JsonInstant.encodeToString(settings.groupChatPresets)"))
        assertTrue("要真的读取", store.contains("decodeGroupPresetsTolerant(raw)"))
        // 复审位抓的 P2：一条坏数据不能把 8 个方案全丢掉 → 必须逐条容错
        assertTrue("读取必须逐条容错", store.contains("JsonInstant.decodeFromJsonElement<GroupChatPreset>(element)"))
        assertFalse(
            "不许整表 decodeFromString（一条坏数据会清空全部方案）",
            store.contains("decodeFromString<List<GroupChatPreset>>"),
        )
        assertTrue("Settings 要有字段", store.contains("val groupChatPresets: List<GroupChatPreset> = emptyList(),"))
    }

    @Test
    fun `方案不许牵连主对话与圆桌`() {
        // 禁区 #13 / 三向隔离：方案只服务群设置，普通聊天、圆桌、子代理都不读它
        assertFalse(
            "主对话服务层不许出现群方案",
            chatService.contains("groupChatPresets") || chatService.contains("GroupChatPreset"),
        )
        val interjection = sliceSection(
            chatService,
            "private fun tryGroupInterjection(",
            "fun startGroupDiscussion(",
        )
        assertFalse("插话通道不许被方案牵连", interjection.contains("Preset"))
    }

    @Test
    fun `撤掉开始弹框后两个入口都要在`() {
        // v316 撤掉弹框（加不了附件），但必须留下「写题目（可加附件）」与「直接开跑」两个入口
        assertFalse("面板不许再引用被撤的弹框", panel.contains("GroupStartDialog"))
        assertFalse("聊天页不许再引用被撤的弹框", chatPage.contains("GroupStartDialog"))
        assertTrue("要有写题目入口", panel.contains("onClick = onStartWithTopic"))
        assertTrue("要有直接开跑入口", panel.contains("onClick = onContinue"))
        assertTrue("写题目入口要落到输入框焦点", chatPage.contains("groupInterjectSignal += 1"))
    }

    // ===== v317：面板折叠 —— 界面开关必须与底层行为同一次落地 =====

    @Test
    fun `折叠开关必须真的被面板消费并真的落盘`() {
        val panelFn = sliceSection(panel, "internal fun GroupChatPanel(", "internal fun GroupChatSettingsDialog(")
        assertTrue("面板要收 collapsed 参数", panelFn.contains("collapsed: Boolean,"))
        assertTrue("面板要收切换回调", panelFn.contains("onToggleCollapsed: () -> Unit,"))
        assertTrue("要有收起/展开按钮并真的调回调", panelFn.contains("TextButton(onClick = onToggleCollapsed)"))
        // 用户拍板原话是「展开箭头」，所以文案必须带箭头；箭头不单独表意，一律配文字
        assertTrue(
            "按钮文案要跟着状态变、且带箭头",
            panelFn.contains("if (collapsed) \"▼ 展开\" else \"▲ 收起\""),
        )
        assertTrue("收起时要真的提前结束这一列", panelFn.contains("if (collapsed) {") && panelFn.contains("return@Column"))
        // 折叠判断必须排在暂停分支之前，否则收起时「重新启用讨论群」大按钮还在，白折
        val collapseAt = panelFn.indexOf("if (collapsed) {")
        val pausedAt = panelFn.indexOf("if (paused) {")
        assertTrue("折叠判断必须在暂停分支之前", collapseAt in 0 until pausedAt)
        // 审查位 v317 抓的守卫假绿：只钉「开关存在」不够 —— 万一把它挪进 if (collapsed) 块里，
        // 收起后面板永久焊死（唯一出口消失），上面那些断言却全都还是绿的。
        assertTrue(
            "收起/展开开关必须在提前返回**之前**（否则收起后没有出口）",
            panelFn.indexOf("TextButton(onClick = onToggleCollapsed)") in 0 until collapseAt,
        )
        // 报错不许被折进收起态里藏起来（手册续跑铁律 ③）
        val collapseBlock = panelFn.substring(collapseAt).substringBefore("if (paused) {")
        assertTrue("收起时仍要显示报错", collapseBlock.contains("state.error") && collapseBlock.contains("colorScheme.error"))
        // v317 采纳审查位 P2-1：收起只折「可操作项」，坏状态不许藏 ——
        // 成员不足两个时那行红字也必须在收起态可见，否则用户看着「💬 讨论群已就绪」以为正常
        assertTrue(
            "收起时也要显示「成员不足两个」警告",
            collapseBlock.contains("if (!membersEnough) {"),
        )
        // v317 采纳审查位 P2-5：只有收起态才用省略号，展开态保持 v316 的裁剪行为
        assertTrue(
            "展开态不许被改成省略号截断",
            panelFn.contains("overflow = if (collapsed) TextOverflow.Ellipsis else TextOverflow.Clip,"),
        )
        // ChatPage 必须把偏好读进来、把切换写回去（缺一个就是骗人的开关）
        assertTrue("页面要读折叠偏好", chatPage.contains("collapsed = setting.displaySetting.groupPanelCollapsed"))
        assertTrue("页面要写回折叠偏好", chatPage.contains("groupPanelCollapsed = !setting.displaySetting.groupPanelCollapsed"))
        // 偏好必须走 DisplaySetting（DataStore JSON），绝不允许动数据库版本
        assertTrue("偏好字段要有默认值（旧数据兼容）", store.contains("val groupPanelCollapsed: Boolean = false,"))
        // 折叠只是显示偏好，绝不允许动数据库版本。
        // ⚠️ 判据不能写成「ChatPage 里不许出现 AppDatabase 这个词」—— 我自己写的注释里
        // 就有它，而反向断言连注释一起算（手册 §7.3 记过两次同类事故，本轮第三次差点再踩）。
        // 要看就看数据库定义文件本身。
        val appDbSource = source(base + "data/db/AppDatabase.kt")
        assertTrue("AppDatabase 必须仍是 32（v1017 用户授权31升32）", appDbSource.contains("version = 32,"))
        // 审查位 v317 抓的 P2-3：原先这条「面板文件里不许出现 @Entity/Migration」近似恒真
        // （界面文件本来就不会有 Room 注解），只会给人"已经防住了"的错觉。
        // 换成直接钉两个数据库文件的版本号 —— 这才是「没动数据库」的权威判据。
        val agentDbSource = source(base + "agent/db/AgentDatabase.kt")
        assertTrue("AgentDatabase 必须仍是 3", agentDbSource.contains("version = 3,"))
    }

    // ---- v318：成员提问的用户回答要进上文 + 面板状态要实时刷新 ----

    @Test
    fun `用户的回答必须被翻译进其他成员的上文`() {
        val prompts = source(base + "data/ai/prompts/GroupChat.kt")
        // 真机 bug：提问消息常常只有 ask_user 卡片、没有正文，旧实现只看正文 → 整条丢弃，
        // 下一位成员既不知道有人问过、也不知道用户答过（GroupChatTest 有真跑用例钉逻辑）。
        assertTrue("必须有问答摘要函数", prompts.contains("private fun groupAskUserDigest("))
        assertTrue(
            "他人发言分支必须调用它",
            prompts.contains("val askDigest = groupAskUserDigest(message)"),
        )
        // v319：判据扩了一支。用户要求「开群带全部上文」之后，他人发言还会额外带上
        // groupToolDigest（之前干活查到的工具产出），所以「不许丢」的条件从
        // 「正文与问答都空」变成「正文、问答、工具产出三者都空」。
        // 语义没变：仍然是「不许出现一条只有署名的空消息」，只是字面串跟着实现走。
        // 真正跑数据的用例在 GroupChatTest：「开群之前的历史，工具产出要带进上文」
        // 与「群讨论期间别人调的工具仍然不进共享上文」两条，一正一反都钉住了。
        assertTrue(
            "空正文但有问答或工具产出时不许丢消息",
            prompts.contains(
                "if (text.isNullOrBlank() && askDigest == null && toolDigest == null) {",
            ),
        )
        // 只认 ask_user 这一个工具名，其它工具输出照旧不进上文
        assertTrue("必须只认 ask_user", prompts.contains("toolName == ASK_USER_TOOL_NAME"))
        // 复用界面同一个解析器，避免两处口径漂移
        assertTrue("必须复用 parseAskUserQuestions", prompts.contains("parseAskUserQuestions("))
        // 用户还没答时不许写进上文
        assertTrue("未作答要跳过", prompts.contains("?: continue"))
    }

    @Test
    fun `两处 ask_user 工具名必须一致`() {
        // 提示词层刻意不 import service 层的常量（不让 prompts 反向依赖 service），
        // 所以两边各写一份 —— 一旦漂移，摘要就会静默失效（永远匹配不上工具名）。
        val prompts = source(base + "data/ai/prompts/GroupChat.kt")
        val promptName = Regex("""ASK_USER_TOOL_NAME = "([^"]+)"""").find(prompts)?.groupValues?.get(1)
        val serviceName = Regex("""GROUP_ASK_USER_TOOL_NAME = "([^"]+)"""").find(run)?.groupValues?.get(1)
        assertNotNull("提示词层找不到 ask_user 常量", promptName)
        assertNotNull("service 层找不到 ask_user 常量", serviceName)
        assertEquals("两处工具名必须一致", serviceName, promptName)
        // ⚠️ 必须与**注册处**比，不能与硬编码字面量比 —— 后者只证明我抄对了抄本，
        // 工具改名时三处都会安静地跟着错（审查位 v318 抓的假绿）。
        val toolSource = source(base + "data/ai/tools/local/AskUserTool.kt")
        // v1017：官方 3.1.1 把注册名提成了常量（`const val ASK_USER_TOOL_NAME = "ask_user"`
        // + `name = ASK_USER_TOOL_NAME`），旧字面 `name = "…"` 在这份文件里已不存在。
        // 这里**不放宽判据**，而是跟着改成两步核：
        //   ① 常量的值必须就是 ask_user（等价于原来读的"注册处真名"）；
        //   ② 注册处必须真的用这个常量，不许偷偷退回硬编码字面量（退回就会两边漂移）。
        val registeredName = Regex("""ASK_USER_TOOL_NAME = "([^"]+)"""").find(toolSource)?.groupValues?.get(1)
        assertNotNull("找不到 ask_user 的注册名常量（注册处必须给出真实名字）", registeredName)
        assertTrue("注册处必须使用该常量，不许退回硬编码 name = \"ask_user\"",
            toolSource.contains("name = ASK_USER_TOOL_NAME"))
        assertEquals("必须与工具注册时的名字一致", registeredName, promptName)
    }

    @Test
    fun `面板状态必须由订阅驱动，不能只靠手工推送`() {
        // 真机 bug：GroupChatRun 有 12 处状态变更点，旧代码只在若干固定点手工 publishGroupState，
        // 引擎「成员开始说话 / 说完换人 / 答完提问继续」全都没推 → 用户答完提问后面板还挂着
        // 「有成员在等你回答」，而成员早就在说话了。
        val subscribeFn = sliceSection(
            chatService,
            "private fun subscribeGroupRun(run: GroupChatRun) {",
            "private fun groupEngine(conversationId: Uuid)",
        )
        assertTrue("必须有一个转发订阅", chatService.contains("private fun subscribeGroupRun("))
        // ⚠️ 判据必须限定在订阅函数体内：圆桌早就有 run.state.first{…}（ChatService 三处），
        // 对整份文件 contains("run.state") 近似恒真 —— 审查位 v318 抓的假绿。
        assertTrue("必须订阅 run 自己的状态流", subscribeFn.contains("run.state"))
        assertTrue("必须去重后转发", subscribeFn.contains("distinctUntilChanged()"))
        assertTrue("转发必须写进面板 flow", subscribeFn.contains("collect { publishGroupState(run) }"))
        assertTrue("建 run 时必须挂上订阅", chatService.contains("subscribeGroupRun(run)"))
        // 漏取消 = 协程泄漏，且下次建群会被 isActive 判成还活着而不再订阅
        val clearFn = sliceSection(
            chatService,
            "internal fun clearGroupRunState(conversationId: Uuid) {",
            "// ---- 处理工具调用审批 ----",
        )
        assertTrue("解散时必须取消订阅", clearFn.contains("groupRunSubs.remove(conversationId)?.cancel()"))
    }

    @Test
    fun `字数直通不许把面板变成高频重组`() {
        // chars 每个流式分片都在变。若参与去重判定，整页会跟着高频重组。
        val subscribeFn = sliceSection(
            chatService,
            "private fun subscribeGroupRun(run: GroupChatRun) {",
            "private fun groupEngine(conversationId: Uuid)",
        )
        assertTrue(
            "去重前必须先把 chars 归零（面板不显示字数）",
            subscribeFn.contains("map { it.copy(chars = 0) }"),
        )
    }

    @Test
    fun `回答提问续写时提问消息必须回到上文末位`() {
        // 审查位 v318 抓的 P1：用户「先插话、再点卡片回答」时，上文末条变成那条插话，
        // 而生成层只认「末条消息里有没有待续跑的工具」→ 认不出提问那条：
        // ① 提问者自己拿不到用户答案；② 走全新生成后再按 overwriteMessageId 原地覆盖
        //    那条提问消息，卡片连同用户答案一起被抹掉（丢用户数据）。
        // ⚠️ 锚点说明：resumeAfterUserAnswer 是 GroupChatEngine 里**最后一个函数**，
        // 它后面没有别的顶层符号可当终点（private enum class 在它之前），所以切片取到文件末尾。
        // 审查位 v318 点出这点后，改成用「调用次数」钉住语义：将来若有人在它后面追加代码、
        // 或把挪位调用复制到别处，这条会红，而不是静默变宽变假绿。
        val resumeFn = engine.substringAfter("suspend fun resumeAfterUserAnswer() {")
        assertTrue("续写前必须把提问消息挪到末位", resumeFn.contains("moveGroupMessageToEnd("))
        assertTrue(
            "整个引擎里只允许这一处挪位（多了就说明有第二路径漏改或误改）",
            Regex("moveGroupMessageToEnd\\(").findAll(engine).count() == 1,
        )
        assertTrue(
            "挪的就是那条要续写的提问消息",
            resumeFn.contains("messageId = continuingId,"),
        )
        assertTrue(
            "喂给模型的必须是挪位后的那份上文，不能再直接调 transcriptFor",
            resumeFn.contains("messages = transcript,"),
        )
        assertTrue(
            "引擎必须 import 这个纯函数（跨文件顶层函数漏 import 直接编译失败）",
            engine.contains("import me.rerere.rikkahub.data.ai.prompts.moveGroupMessageToEnd"),
        )
        val prompts = source(base + "data/ai/prompts/GroupChat.kt")
        assertTrue("纯函数必须存在", prompts.contains("fun moveGroupMessageToEnd("))
    }

    @Test
    fun `跳过指令不许被当成用户原话`() {
        // 跳过时写进答案的是「写给提问者的指令」（含「不要重复问同样的问题」），
        // 原样摊给其他成员，它们会以为用户真说过这句话。
        val prompts = source(base + "data/ai/prompts/GroupChat.kt")
        assertTrue("必须识别跳过哨兵", prompts.contains("GROUP_ASK_SKIP_MARKER"))
        assertTrue("必须读 note 字段", prompts.contains("""root?.get("note")"""))
        assertTrue(
            "跳过时输出如实说明而不是指令原文",
            prompts.contains("用户跳过了这次提问，没有作答"),
        )
    }

    @Test
    fun `创建分支必须把对话级设置带到新对话`() {
        // 用户真机报告：「打开讨论群组创建分支后，新分支出来的对话讨论群组直接消失/解散掉了」。
        // 根因是群配置与对话级模型记忆都按对话 id 存，分支领了新 id 就查不到条目。
        val forkFn = sliceSection(
            chatService,
            "suspend fun forkConversationAtMessage(",
            "private suspend fun carryOverForkSettings(",
        )
        assertTrue("分支后必须搬运设置", forkFn.contains("carryOverForkSettings(conversationId, forkConversation.id)"))
        val carryFn = sliceSection(
            chatService,
            "private suspend fun carryOverForkSettings(",
            "    suspend fun selectMessageNode(",
        )
        assertTrue(
            "搬运必须走可单测的纯函数",
            // v1007：写法从「先 first() 拿快照、再 update(整份)」改成 update(transform)，
            // 由 DataStore 内部读改写 —— 消掉两步之间被别的设置写入插队的窗口。
            // 纯函数本身没变，只是变量名从 current 变 latest。
            carryFn.contains("settingsAfterFork(latest, sourceConversationId, newConversationId)"),
        )
        assertTrue(
            "搬运必须走 update(transform)，不许拿旧快照整份回写",
            carryFn.contains("settingsStore.update { latest ->"),
        )
        assertTrue(
            // v1007：原先这条断言钉的是调用方自己写的 `if (updated === current) return`。
            // 现在「没变化就不写盘」由 PreferencesStore.update/persist 负责：
            // transform 返回同一实例时 `latest === persisted`，persist 直接跳过。
            // 所以判据改成「必须走那个带引用判断的 update」，不是把这条需求删掉。
            "没东西可搬时不许落盘（改由 PreferencesStore.update 的引用判断保证）",
            source(base + "data/datastore/PreferencesStore.kt").let {
                it.contains("if (latest !== base)") && it.contains("suspend fun update(fn: (Settings) -> Settings)")
            },
        )
        // 手册 §7.1：包裹挂起调用的兜底不许用 runCatching —— 它会吞掉取消异常，
        // 用户点停止就不能立刻生效。这里必须显式 catch 并原样上抛。
        assertTrue("取消异常必须原样上抛", carryFn.contains("catch (e: CancellationException) {\n            throw e"))
        assertFalse("搬运函数里不许用 runCatching 兜底", carryFn.contains("runCatching"))
        val forkSettings = source(base + "data/datastore/ForkConversationSettings.kt")
        assertTrue("纯函数必须存在", forkSettings.contains("fun settingsAfterFork("))
        assertTrue(
            "模型记忆必须跟开关走（关着时不写看不见的记录）",
            forkSettings.contains("takeIf { settings.rememberModelPerConversation }"),
        )
    }

    @Test
    fun `删除对话必须释放讨论群与状态订阅`() {
        // v317 之前删对话只是留个无用条目；v318 加了「状态 → 面板」的转发订阅后，
        // 不取消就是一个永远挂着的协程（run.state 是无限流）。审查位 v318 抓的 P2，
        // 因本轮改动升级为必修。三个删除入口都要接，漏一个就漏一条路。
        val chatVmFn = sliceSection(
            chatVM,
            "fun deleteConversation(conversation: Conversation): Job =",
            "fun updatePinnedStatus(conversation: Conversation) {",
        )
        assertTrue(
            "聊天页删除要释放",
            chatVmFn.contains("chatService.releaseGroupStateOnDelete(conversation.id)"),
        )
        val historyVm = source(base + "ui/pages/history/HistoryVM.kt")
        assertTrue(
            "历史页删除要释放",
            historyVm.contains("chatService.releaseGroupStateOnDelete(conversation.id)"),
        )
        val routes = source(base + "web/routes/ConversationRoutes.kt")
        assertTrue(
            "Web 接口删除要释放（与 App 内同口径）",
            routes.contains("chatService.releaseGroupStateOnDelete(uuid)"),
        )
        // 释放必须先停再清：反过来会留下「按钮还在、点了没反应」的空面板（v314 同一条教训）
        val releaseFn = sliceSection(
            chatService,
            "suspend fun releaseGroupStateOnDelete(conversationId: Uuid) {",
            "    // ---- 处理工具调用审批 ----",
        )
        val stopAt = releaseFn.indexOf("stopGroupDiscussion(conversationId)")
        val clearAt = releaseFn.indexOf("clearGroupRunState(conversationId)")
        assertTrue("释放里必须既停又清", stopAt >= 0 && clearAt >= 0)
        assertTrue("顺序必须先是停后是清", stopAt < clearAt)
    }

    // ---- v320：点名当场换人 + 群通知接线 ----

    @Test
    fun `点名可以当场撤掉还没出声的发言`() {
        val pick = sliceSection(
            chatService,
            "fun requestGroupSpeakerNow(",
            "private fun notifyGroupAwaitingUser(",
        )
        assertTrue("必须先有「正在开口那位」的把手表", chatService.contains("private val groupSpeaking = ConcurrentHashMap<Uuid, GroupSpeakingHandle>()"))
        assertTrue(
            "撤之前先判「这位出声了没」——只有思考/只挂着没执行的工具才允许撤",
            pick.contains("!groupUtteranceHasKeptContent(handle.lastUtterance)"),
        )
        // v320：判据字面量跟着代码改过 —— 采纳审查位意见后，「布尔旗」换成了「我撤的是哪一个 job」的
        // 引用比对（两次瞬时读凑结论不可靠）。按手册口径：更新判据并写明理由，不把代码改回去迎合断言。
        val namedAt = pick.indexOf("run.requestSpeaker(modelId)")
        val flaggedAt = pick.indexOf("handle.abortRequestedFor = speakerJob")
        assertTrue("两个锚点都必须存在，否则顺序比较会静默失效", namedAt >= 0 && flaggedAt >= 0)
        assertTrue("顺序：先记名，再记旗（反了会被当成用户点停止，整批讨论直接结束）", namedAt < flaggedAt)
        assertTrue("必须只 cancel 这一位的发言协程", pick.contains("speakerJob.cancel()"))
        assertTrue("必须给界面一种「当场换人」的结果，不许静默", pick.contains("GroupPickOutcome.INTERRUPTED"))

        val speak = sliceSection(chatService, "private suspend fun speakGroupTurn(", "// 收尾：无论正常")
        assertTrue("发言必须跑在可单独取消的子协程里", speak.contains("val speakerJob = async {"))
        assertTrue("只给「新发言、要写进对话」的这一路交把手", speak.contains("val abortable = persist && overwriteMessageId == null"))
        assertTrue(
            "取消要分得开：点名换人 vs 用户点停止（引用比对 + 本协程仍活着，两个条件都在）",
            speak.contains("handle?.abortRequestedFor === speakerJob && coroutineContext[Job]?.isActive == true"),
        )
        assertTrue("撤场要删掉什么都没留下的那条发言", speak.contains("removeEmptyGroupUtterance(current, utteranceId)"))
        assertTrue("把手用完必须摘掉，不许泄漏", speak.contains("groupSpeaking.remove(conversationId, handle)"))
        // 以下三条 = v320 审查位采纳项，防止被人改回旧写法
        assertTrue("撤之前必须确认那个子协程还活着（拿旧把手乱 cancel = 给用户假提示）", pick.contains("speakerJob.isActive"))
        assertTrue("撤之前必须重新核一次当前发言者还是不是同一位", pick.contains("now.currentSpeakerModelId == handle.modelId"))
        assertTrue(
            "旗子必须是「我撤的是哪一个 job」的引用，不是布尔值（两次瞬时读凑结论不可靠）",
            pick.contains("handle.abortRequestedFor = speakerJob") && speak.contains("handle?.abortRequestedFor === speakerJob"),
        )
        assertTrue("撤场清理必须走带锁的 mutateAndSaveConversation（v229 铁律）", speak.contains("mutateAndSaveConversation(conversationId) { current ->"))
        assertFalse("撤场清理不许再退回「读快照→改→整份写回」", speak.contains("val cleaned = removeEmptyGroupUtterance(current, utteranceId)"))
        assertFalse("不许退回布尔旗（要靠两次瞬时读凑结论）", chatService.contains("var abortRequested: Boolean"))
        assertFalse("撤场清理不许再退回「读快照→改→整份写回」", speak.contains("updateConversation(conversationId, cleaned)"))

        // 判据本体（GroupChatRun）：待批准的卡片必须算「已出声」
        assertTrue(
            "Tool 的判据必须同时认「已执行」「已被用户答过」「等批准」三种，少一种都会删错东西",
            run.contains("part.isExecuted ||") && run.contains("is ToolApprovalState.Answered") && run.contains("part.isPending"),
        )

        val abortAt = engine.indexOf("catch (e: GroupSpeakerAbortedException)")
        val failedAt = engine.indexOf("} catch (e: Exception) {")
        assertTrue("两处 catch 都必须存在，否则下面的顺序比较会静默失效", abortAt >= 0 && failedAt >= 0)
        assertTrue(
            "引擎必须把「被撤」排在「成员失败」之前，否则换人会被记成一次失败并推进游标",
            abortAt < failedAt,
        )
        assertEquals("两处推进循环都要接住被撤这一种", 2, Regex("catch \\(e: GroupSpeakerAbortedException\\)").findAll(engine).count())
        assertEquals("界面两种新提示都要有", 2, Regex("GroupPickOutcome\\.INTERRUPTED").findAll(chatPage).count())
    }

    @Test
    fun `群停下来等用户必须真的发出通知信号`() {
        val manager = source(base + "service/ChatNotificationManager.kt")
        assertTrue("事件必须被消费，否则只是发个没人听的信号", manager.contains("is AppEvent.GroupChatWaitingForUser -> handleGroupChatWaiting(event)"))
        // v320 审查位抓到这条是**假绿**：旧代码里本来就有两处同样的判断（handleGenerationUpdate /
        // handleGenerationEnded），整份文件 contains 它永远为真，等于没断言。改成先把新处理函数切出来再判。
        val handler = sliceSection(manager, "private fun handleGroupChatWaiting(", "private fun sendGenerationDoneNotification(")
        assertTrue("新处理函数自己必须挡前台（不许蹭别的函数的判断）", handler.contains("if (isForeground.value) return"))
        assertTrue("新处理函数自己必须读那个开关", handler.contains("displaySetting.notifyGroupChatWaiting"))
        assertTrue(
            "整场结束的文案不许糊成「等你点继续讨论」（那种场合点了也不会跑）",
            handler.contains("AppEvent.GroupWaitReason.ENDED"),
        )

        val notify = sliceSection(chatService, "private fun notifyGroupAwaitingUser(", "fun skipGroupQuestion(")
        assertTrue("要不要通知收在纯函数里（可单测），不在这里各写一套", notify.contains("groupWaitReasonOf(snapshot.status, snapshot.error) ?: return"))
        assertTrue("走事件总线，与主对话同一套设施", notify.contains("appEventBus.tryEmit("))
        assertEquals("两个批次收尾处都必须调用（继续讨论 / 回答提问后续写）", 2, Regex("notifyGroupAwaitingUser\\(run\\.snapshot\\(\\)\\)").findAll(chatService).count())

        // 手册 v313 教训：界面开关必须与底层行为**同一次落地**，否则就是骗人的开关
        val prefs = source(base + "data/datastore/PreferencesStore.kt") +
            "\n" + source(base + "data/datastore/Settings.kt") // v323：官方 3.1 把 Settings 字段拆到 Settings.kt，判据改读两个文件（断言内容未改）
        assertTrue("开关默认必须是开（默认关会让这次修复在多数装机上看不见效果）", prefs.contains("val notifyGroupChatWaiting: Boolean = true"))
        val settingPage = source(base + "ui/pages/setting/SettingPreferencesNotificationPage.kt")
        assertTrue("界面必须写回同一个字段", settingPage.contains("updateDisplaySetting(displaySetting.copy(notifyGroupChatWaiting = it))"))
    }
}
