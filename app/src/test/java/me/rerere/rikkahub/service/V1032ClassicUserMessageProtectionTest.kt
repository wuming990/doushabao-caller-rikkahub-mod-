package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v1032 守卫：经典压缩的「用户发言保护」。
 *
 * 用户提出这个开关的原话：「给经典压缩模式也增加个用户发言保护选项，
 * 可自主选择是否压缩时连带着用户发言一起压缩掉」。智能压缩那条 Codex 路线本来就
 * 把最近的用户消息原文留着，经典压缩（官方检查点流程）以前会把用户发言一起写进摘要。
 *
 * 三件事必须同时成立，缺一条这功能就是假的：
 * ① 关闭时摘要正文一字节都不许多（默认必须是关）；
 * ② 打开时逐字附上用户发言；
 * ③ 绝不把上一个检查点（旧摘要）当成用户发言再抄一遍 —— 否则摘要逐次套娃膨胀。
 *
 * v1033 口径变更（用户追问「做不到每次压缩都保留用户的原话么」之后拍板）：
 * ② 从「只附本次新压掉那一段」升级为「附从对话第一条到本次压缩终点的全部原话」，
 * 并按用户选定**不设长度上限**（代价：卡片与每次上传体积随发言量增长）。
 * ③ 仍然成立 —— 检查点消息被 filterNot 排除，所以放开范围不会自抄摘要。
 * 新增配套承诺：旧卡片里的账本不再重复喂给摘要模型（stripClassicVerbatimLedger）。
 */
class V1032ClassicUserMessageProtectionTest {

    private class Store(var persisted: Conversation) : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) {
            persisted = current
        }
    }

    private suspend fun fixture(
        messages: List<UIMessage>,
        block: suspend (ConversationSession, Store) -> Unit,
    ) {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })
        val store = Store(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(source.id, source, scope, store, {})
        try {
            session.initialize(load = { source })
            block(session, store)
        } finally {
            scope.cancel()
        }
    }

    private fun checkpointTextOf(conversation: Conversation): String =
        conversation.currentMessages.last { it.isContextCheckpoint }.toText()

    private fun appendixOf(conversation: Conversation): String =
        checkpointTextOf(conversation).substringAfter(CLASSIC_VERBATIM_USER_MARKER)

    // ---- ① 关闭时必须一个字节都不许多 ----

    @Test
    fun `关闭保护时摘要正文与开关引入前完全一致`() = runBlocking {
        fixture(listOf(UIMessage.user("我的要求"), UIMessage.assistant("好的"))) { session, store ->
            session.compressClassicHistory { _, _ -> "只有一句摘要" }
            assertEquals("只有一句摘要", checkpointTextOf(store.persisted))
        }
    }

    @Test
    fun `不传参数与显式传 false 必须得到同一个摘要`() = runBlocking {
        val nodes = listOf(UIMessage.user("原话一"), UIMessage.assistant("回答一"))
        var off = ""
        var omitted = ""
        fixture(nodes) { session, store ->
            session.compressClassicHistory(protectUserMessages = false) { _, _ -> "摘要A" }
            off = checkpointTextOf(store.persisted)
        }
        fixture(nodes) { session, store ->
            session.compressClassicHistory { _, _ -> "摘要A" }
            omitted = checkpointTextOf(store.persisted)
        }
        assertEquals(off, omitted)
    }

    // ---- ② 打开时逐字附上本次范围内的用户发言 ----

    @Test
    fun `打开保护时用户原话逐字附在摘要末尾`() = runBlocking {
        fixture(listOf(UIMessage.user("把标题改成四个字"), UIMessage.assistant("明白"))) { session, store ->
            session.compressClassicHistory(protectUserMessages = true) { _, _ -> "摘要正文" }
            val text = checkpointTextOf(store.persisted)
            assertTrue("摘要本体的位置不能被破坏: $text", text.startsWith("摘要正文"))
            assertTrue("必须带原话段落标题", text.contains(CLASSIC_VERBATIM_USER_MARKER))
            assertTrue("必须逐字出现用户原话", text.contains("1. 把标题改成四个字"))
        }
    }

    @Test
    fun `保留最近消息尾巴里的用户发言不重复进原话清单`() = runBlocking {
        fixture(List(4) { UIMessage.user("message $it") }) { session, store ->
            session.compressClassicHistory(keepRecentMessages = 2, protectUserMessages = true) { _, _ -> "摘要" }
            val text = checkpointTextOf(store.persisted)
            assertTrue("压缩范围内的 message 0 必须逐字保留", text.contains("message 0"))
            assertTrue("压缩范围内的 message 1 必须逐字保留", text.contains("message 1"))
            // message 2 / 3 仍作为真实消息跟在摘要之后，不该在原话清单里再抄一遍
            val appendix = appendixOf(store.persisted)
            assertFalse("尾巴里的消息不许重复出现在原话清单", appendix.contains("message 2"))
            assertFalse("尾巴里的消息不许重复出现在原话清单", appendix.contains("message 3"))
        }
    }

    @Test
    fun `范围内没有用户发言时不追加任何文字`() = runBlocking {
        fixture(listOf(UIMessage.assistant("只有助手说话"))) { session, store ->
            session.compressClassicHistory(protectUserMessages = true) { _, _ -> "摘要正文" }
            assertEquals("没有原话可留时不许多出空标题", "摘要正文", checkpointTextOf(store.persisted))
        }
    }

    @Test
    fun `纯附件的用户消息不会挤进原话清单`() = runBlocking {
        val imageOnly = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///a.png")))
        fixture(listOf(imageOnly, UIMessage.assistant("看到了"))) { session, store ->
            session.compressClassicHistory(protectUserMessages = true) { _, _ -> "摘要" }
            assertEquals("附件没有文字，逐字保留只能是空条目", "摘要", checkpointTextOf(store.persisted))
        }
    }

    @Test
    fun `合成消息与旧摘要不会被当成用户原话`() {
        val synthetic = UIMessage.user("这是系统注入的提示").copy(isSynthetic = true)
        val checkpoint = UIMessage.user("上一轮的摘要").copy(isContextCheckpoint = true)
        assertEquals("两类都不是用户亲口说的话，必须全部过滤", "", classicVerbatimUserAppendix(listOf(synthetic, checkpoint)))
        val mixed = classicVerbatimUserAppendix(listOf(synthetic, checkpoint, UIMessage.user("这条才是我说的")))
        assertTrue(mixed.contains("这条才是我说的"))
        assertFalse(mixed.contains("这是系统注入的提示"))
        assertFalse(mixed.contains("上一轮的摘要"))
    }

    @Test
    fun `超长用户发言也整条逐字保留 不再按预算截断`() {
        // v1033：用户选定「不设上限，全部原话永久逐字留着」，旧的约 2 万 token 中间截断必须消失。
        // 60_000 个汉字 ≈ 18 万字节 ≈ 4.5 万 token，远超旧预算 —— 照 v1032 的写法这里必然被截断。
        val long = "长".repeat(60_000)
        val appendix = classicVerbatimUserAppendix(listOf(UIMessage.user(long)))
        assertFalse("不许出现任何截断标记", appendix.contains("tokens truncated"))
        assertTrue("整条原话必须一字不差全留下", appendix.contains(long))
        // 可数判据，不用 lines().last()：审查位 P2 指出「取最后一行」在以后任何末尾追加下都会悄悄失效。
        assertEquals("只能有一条编号行", 1, appendix.lines().count { it.startsWith("1. ") })
        assertTrue("编号必须从 1 开始", appendix.lines().single { it.startsWith("1. ") }.startsWith("1. 长长长"))
    }

    // ---- ③ 绝对不许套娃膨胀 ----

    @Test
    fun `第二次压缩带上全部原话 但绝不把旧摘要正文当成原话`() = runBlocking {
        // v1033 反转了本用例的旧判据：用户要求「每次压缩都保留用户原话」，
        // 而原始消息从来没有被删除（检查点只是往历史里插一张摘要卡片），
        // 所以第二次的卡片必须把第一次的原话也逐字带上，而不是只剩第二段。
        fixture(listOf(UIMessage.user("第一段原话"), UIMessage.assistant("回答"))) { session, store ->
            session.compressClassicHistory(protectUserMessages = true) { _, _ -> "第一次摘要" }
            assertTrue(checkpointTextOf(store.persisted).contains("第一段原话"))

            session.update {
                it.copy(
                    messageNodes = it.messageNodes +
                        UIMessage.user("第二段原话").toMessageNode() +
                        UIMessage.assistant("再回答").toMessageNode(),
                )
            }
            var fedToModel: List<UIMessage> = emptyList()
            session.compressClassicHistory(protectUserMessages = true) { _, messages ->
                fedToModel = messages
                "第二次摘要"
            }

            val text = checkpointTextOf(store.persisted)
            assertTrue("第二段原话必须逐字保留", text.contains("第二段原话"))
            assertEquals("原话段落标题只能出现一次", 1, text.split(CLASSIC_VERBATIM_USER_MARKER).size - 1)
            val appendix = text.substringAfter(CLASSIC_VERBATIM_USER_MARKER)
            assertTrue("v1033：第一次的原话也必须逐字带着，不许退化成转述", appendix.contains("第一段原话"))
            assertTrue(
                "两段原话都要各自成条，条目编号必须连续",
                appendix.contains("1. 第一段原话") && appendix.contains("2. 第二段原话"),
            )
            assertFalse("旧摘要本身是 user 角色消息，绝不能被当成用户原话", appendix.contains("第一次摘要"))

            // v1033 配套判据：旧卡片里那份已归档的账本不许再喂给摘要模型转述一遍，
            // 否则逐字段随压缩次数按平方级膨胀，而且模型会把逐字清单改写成转述。
            val fedCheckpoint = fedToModel.first { it.isContextCheckpoint }
            assertEquals(
                "剥掉账本后，旧卡片的摘要本体一个字都不许多一个字都不许少",
                "第一次摘要",
                fedCheckpoint.toText(),
            )
            assertFalse("喂给模型的旧卡片必须已剥掉逐字段", fedCheckpoint.toText().contains(CLASSIC_VERBATIM_USER_MARKER))
        }
    }

    @Test
    fun `保护开关不改变压缩边界与检查点数量`() = runBlocking {
        fixture(listOf(UIMessage.user("q1"), UIMessage.assistant("a1"))) { session, store ->
            val originalIds = store.persisted.messageNodes.map { it.id }
            session.compressClassicHistory(protectUserMessages = true) { _, _ -> "摘要" }
            val conversation = store.persisted
            // keepRecentMessages=0 时检查点插在最后一个节点之后，这是 v1019 以来的既有边界，开关不许改它
            assertEquals(3, conversation.messageNodes.size)
            assertEquals(1, conversation.messageNodes.count { it.currentMessage.isContextCheckpoint })
            assertEquals("原节点必须原样保留（检查点机制的既有承诺）", originalIds, conversation.messageNodes.take(2).map { it.id })
            assertEquals(listOf("q1", "a1"), conversation.currentMessages.take(2).map { it.toText() })
            assertTrue(conversation.currentMessages[2].isContextCheckpoint)
        }
    }

    // ---- 审查位补钉（v1032 首轮双审提出，逐条采纳）----

    @Test
    fun `模型没产出时必须仍然报错 不许拿用户原话冒充摘要`() = runBlocking {
        fixture(listOf(UIMessage.user("我的原话很多"), UIMessage.assistant("回答"))) { session, store ->
            val before = session.state.value.messageNodes.size
            try {
                session.compressClassicHistory(protectUserMessages = true) { _, _ -> "   " }
                throw AssertionError("空摘要必须抛 EMPTY_SUMMARY")
            } catch (expected: ClassicCompressionException) {
                assertEquals(ClassicCompressionIssue.EMPTY_SUMMARY, expected.issue)
            }
            assertEquals("失败时不许留下半个检查点", before, store.persisted.messageNodes.size)
        }
    }

    @Test
    fun `保护打开时用量水位仍然挂在同一条摘要正文上`() = runBlocking {
        // baselines 是「保留区消息的旧用量不计入新账」的唯一依据；把原话追加成第二个 part
        // 或改写成别的载体，都会让这条判据失效，这里钉死它。
        val answered = UIMessage.assistant("a1").copy(
            usage = me.rerere.ai.core.TokenUsage(),
        )
        fixture(listOf(UIMessage.user("q1"), answered)) { session, store ->
            session.compressClassicHistory(keepRecentMessages = 1, protectUserMessages = true) { _, _ -> "摘要" }
            val checkpoint = store.persisted.currentMessages.last { it.isContextCheckpoint }
            assertNotNull("开保护后摘要正文必须仍带 baselines 元数据", checkpoint.checkpointBaselines())
            assertTrue("逐字原话必须在同一条正文里", checkpoint.toText().contains("1. q1"))
        }
    }

    @Test
    fun `关闭保护时已有检查点的第二次压缩也逐字节不变`() = runBlocking {
        var withFlag = ""
        var baseline = ""
        fixture(listOf(UIMessage.user("q1"), UIMessage.assistant("a1"))) { session, store ->
            session.compressClassicHistory(protectUserMessages = false) { _, _ -> "第一段" }
            session.update {
                it.copy(messageNodes = it.messageNodes + UIMessage.user("q2").toMessageNode())
            }
            session.compressClassicHistory(protectUserMessages = false) { _, _ -> "第二段" }
            withFlag = checkpointTextOf(store.persisted)
        }
        fixture(listOf(UIMessage.user("q1"), UIMessage.assistant("a1"))) { session, store ->
            session.compressClassicHistory { _, _ -> "第一段" }
            session.update {
                it.copy(messageNodes = it.messageNodes + UIMessage.user("q2").toMessageNode())
            }
            session.compressClassicHistory { _, _ -> "第二段" }
            baseline = checkpointTextOf(store.persisted)
        }
        assertEquals(baseline, withFlag)
    }

    // ---- 编辑摘要这条真实路径（第一轮审查指出：此前零覆盖）----

    @Test
    fun `编辑摘要把原话段删掉后 检查点身份与用量水位都不许丢`() = runBlocking {
        // 用户在检查点卡片上点「编辑」，把贴进去的原话段整段删掉 —— 这是最容易被改坏的一条路：
        // 若编辑实现把摘要当成普通消息新建分支，检查点身份会丢，limitContext 找不回它，
        // 已归档的整段历史被重新发给模型（用户以为压好了，实际恢复全量计费）。
        // 保留区必须带一条**有用量**的消息：用量水位只给保留区里报得出用量的消息记账。
        val retained = UIMessage.assistant("a1").copy(
            usage = me.rerere.ai.core.TokenUsage(promptTokens = 10, completionTokens = 2),
        )
        fixture(listOf(UIMessage.user("q1"), retained)) { session, store ->
            session.compressClassicHistory(keepRecentMessages = 1, protectUserMessages = true) { _, _ -> "摘要正文" }
            val nodes = store.persisted.messageNodes
            val checkpointNode = nodes.first { it.currentMessage.isContextCheckpoint }
            assertTrue(
                "编辑前正文里确实带着原话段",
                checkpointNode.currentMessage.toText().contains(CLASSIC_VERBATIM_USER_MARKER),
            )
            assertNotNull("压缩后保留区就该有水位记录", checkpointNode.currentMessage.checkpointBaselines())

            val edited = editMessageNodes(
                nodes,
                checkpointNode.currentMessage.id,
                listOf(UIMessagePart.Text("用户自己重写的摘要，没有原话段")),
            )
            assertNotNull(edited)
            val after = edited!!.first { it.id == checkpointNode.id }
            assertTrue("编辑后必须仍是检查点（原地改写，不许新建分支）", after.currentMessage.isContextCheckpoint)
            assertEquals("编辑后正文就是用户写的内容", "用户自己重写的摘要，没有原话段", after.currentMessage.toText())
            assertFalse(
                "用户删掉的原话段不许被自动补回来",
                after.currentMessage.toText().contains(CLASSIC_VERBATIM_USER_MARKER),
            )
            // 水位元数据必须原地带过去：丢了它，保留区那条的旧账会被当成新账重复计入
            val baselines = after.currentMessage.checkpointBaselines()
            assertNotNull("编辑后仍须带用量水位", baselines)
            assertNotNull("水位里必须还认得保留区那条消息", baselines!![retained.id.toString()])
            // 判据不许退化成「比创建时间」：导入、改时区、重新生成旧消息都会让时间先后失真
            val retainedMessage = edited.last { !it.currentMessage.isContextCheckpoint }.currentMessage
            assertTrue(
                "保留区消息仍按用量水位判旧账，而不是退回时间比较",
                isPreCheckpointUsage(retainedMessage, after.currentMessage),
            )
        }
    }

    // ---- 接线守卫：开关必须真的从界面传到会话 ----

    private fun moduleFile(relative: String): String {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("找不到工程根目录", root)
        val file = File(root, "app/src/main/$relative")
        // 路径写错时必须让测试立刻红：返回空串会让下面所有「不许出现」的负向断言静默通过，
        // 那是比漏测更糟的情况 —— 守卫看着绿，其实什么都没看。
        assertTrue("守卫测试读不到文件：${file.path}", file.exists())
        return file.readText()
    }

    @Test
    fun `接线 界面到会话每一环都带着这个开关`() {
        val dialog = moduleFile("java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt")
        assertTrue("对话框缺保护开关控件", dialog.contains("checked = protectUserMessages"))
        assertTrue("对话框缺上次选择的初值", dialog.contains("initialClassicProtectUserMessages"))
        assertTrue("确认按钮必须把开关带出去", dialog.contains("onConfirmClassic(additionalPrompt, keepRecentMessages, protectUserMessages)"))

        val picker = moduleFile("java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("FilesPicker 缺三参数签名", picker.contains("onCompressContextClassic: (additionalPrompt: String, keepRecentMessages: Int, protectUserMessages: Boolean) -> Job"))
        assertTrue("FilesPicker 缺初值接线", picker.contains("initialClassicProtectUserMessages = settings.compressClassicProtectUserMessages"))

        val page = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue("ChatPage 缺三参数 lambda", page.contains("onCompressContextClassic = { additionalPrompt, keepRecentMessages, protectUserMessages ->"))

        val vm = moduleFile("java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue("ChatVM 没把开关交给服务", vm.contains("_conversationId, additionalPrompt, keepRecentMessages, protectUserMessages,"))

        val service = moduleFile("java/me/rerere/rikkahub/service/ChatService.kt")
        // v1034 审查位 P1-1：原来直接用 substringAfter/substringBefore —— 锚点一旦不存在，
        // substringAfter 会**返回整份文件**（本项目记过的典型假判据形态），判据就悄悄变成
        // "整页纸里随便含个词都算过"。先钉住两个锚点存在且顺序正确，再按索引切片。
        val classicStart = service.indexOf("suspend fun compressConversationClassic(")
        val classicEnd = service.indexOf("suspend fun autoCompressForkConversation(", classicStart.coerceAtLeast(0))
        assertTrue("找不到 compressConversationClassic 起点", classicStart >= 0)
        assertTrue("compressConversationClassic 与 autoCompressForkConversation 的顺序漂了（切片会读到别的方法）", classicEnd > classicStart)
        val classic = service.substring(classicStart, classicEnd)
        // v1034 同步：这一处调用改成了具名参数的多行写法（因为要新增 allowWhileGenerating 形参），
        // 旧的单行字面量随之过期。判据改成**更强**的形式：必须能看到 protectUserMessages 以具名
        // 实参传进会话层，同时必须仍然把 allowWhileGenerating 交出去（默认 false 由函数签名钉）。
        // 不是把判据放宽成"这页纸里随便含个词"，切片边界仍然只在 compressConversationClassic 内部。
        assertTrue(
            "服务层没把开关交给会话（必须看到具名传参 protectUserMessages = protectUserMessages）",
            Regex("compressClassicHistory\\(\\s*[^)]*protectUserMessages\\s*=\\s*protectUserMessages", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(classic),
        )
        // v1034 第三轮审查位 P1：原来这条是"字符串包含"，在注释里写一遍就算过（空判据）。
        // 改成可数判据：必须是**独占一行的具名实参**，且只许出现一次。
        assertEquals(
            "服务层必须把 allowWhileGenerating 作为独占一行的具名实参交给会话（注释不算）",
            1,
            classic.lines().count { it.trim() == "allowWhileGenerating = allowWhileGenerating," },
        )
        // v1034 第三轮审查位 P1：这条切片原来同样没校验锚点（substringAfter 找不到锚点会返回
        // 整份文件，判据就悄悄变成"随便哪页含个词都算过"）。与上面 classic 同一套写法。
        val taskStart = service.indexOf("fun startManualClassicCompression(")
        val taskEnd = service.indexOf("// 生成完成流", taskStart.coerceAtLeast(0))
        assertTrue("找不到 startManualClassicCompression 起点", taskStart >= 0)
        assertTrue("startManualClassicCompression 与「// 生成完成流」的顺序漂了（切片会读到别的方法）", taskEnd > taskStart)
        val classicTask = service.substring(taskStart, taskEnd)
        assertTrue("后台任务没落盘这个选择", classicTask.contains("compressClassicProtectUserMessages = protectUserMessages,"))

        assertTrue("设置默认必须是关", moduleFile("java/me/rerere/rikkahub/data/datastore/Settings.kt")
            .contains("val compressClassicProtectUserMessages: Boolean = false,"))
        val prefs = moduleFile("java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue("读取必须用 == true（缺 key 要落到 false）", prefs.contains("compressClassicProtectUserMessages = preferences[COMPRESS_CLASSIC_PROTECT_USER_MESSAGES] == true,"))
        assertFalse("禁止写成 != false（会把老存档判成开启）", prefs.contains("preferences[COMPRESS_CLASSIC_PROTECT_USER_MESSAGES] != false"))
        assertTrue("必须真的落盘", prefs.contains("preferences[COMPRESS_CLASSIC_PROTECT_USER_MESSAGES] = settings.compressClassicProtectUserMessages"))
        assertTrue("必须在 SETTINGS_FIELDS 注册一行", moduleFile("java/me/rerere/rikkahub/data/datastore/SettingsFields.kt")
            .contains("plain(COMPRESS_CLASSIC_PROTECT_USER_MESSAGES, Settings::compressClassicProtectUserMessages)"))

        // v1033：这条守卫原本钉的是「只取本次新压掉那一段」的旧写法
        // nodes.subList((checkpointIndex + 1).coerceAtLeast(0), endIndex)，该写法已被全量取法取代。
        // 守卫的**语义**没有变 —— 「账本范围必须与摘要输入同源」——所以改成钉新的同源表达式，
        // 而不是把守卫删掉。首轮双审查（一带全文 + 不带全文）都判 fail 在这一条上。
        val compression = moduleFile("java/me/rerere/rikkahub/service/ClassicCompression.kt")
        assertTrue(
            "v1033：账本必须与摘要输入同源（共用同一个 summaryInput），否则「压掉的」和「逐字保留的」会错开",
            compression.contains("val summaryInput = conversation.currentMessages.take(endIndex)")
                && compression.contains("classicVerbatimUserAppendix(summaryInput)"),
        )
        assertFalse(
            "v1033：不许退回「只取本次那一段」的旧范围（那正是用户追问的缺口）",
            compression.contains("nodes.subList((checkpointIndex + 1).coerceAtLeast(0), endIndex).map"),
        )
        assertTrue(
            "v1033：开启保护时，旧卡片账本必须先剥掉再喂摘要模型",
            compression.contains("summaryInput.strippingClassicVerbatimLedger()"),
        )
    }

    @Test
    fun `接线 开关只出现在经典分支 智能分支一个控件都不许多加`() {
        val dialog = moduleFile("java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt")
        val classicStart = dialog.indexOf("if (classicMode) {")
        val branchSplit = dialog.indexOf("} else {", classicStart.coerceAtLeast(0))
        val classicEnd = dialog.indexOf("// 结束智能模式分支", branchSplit.coerceAtLeast(0))
        assertTrue("对话框经典分支起点缺失", classicStart >= 0)
        assertTrue("对话框智能分支分界缺失", branchSplit > classicStart)
        assertTrue("对话框智能分支结束标记缺失", classicEnd > branchSplit)
        val classicBranch = dialog.substring(classicStart, branchSplit)
        val smartBranch = dialog.substring(branchSplit, classicEnd)
        assertTrue("保护开关必须落在经典分支里", classicBranch.contains("checked = protectUserMessages"))
        assertTrue("保护说明必须落在经典分支里", classicBranch.contains("chat_page_compress_classic_protect_user_desc"))
        assertFalse("智能分支不许出现保护开关", smartBranch.contains("protectUserMessages"))
        assertFalse("智能分支不许出现保护文案", smartBranch.contains("chat_page_compress_classic_protect_user"))
        // 智能压缩自己的既有控件必须还在，不能被新增行挤掉
        assertTrue("智能分支缺压缩模型来源选择", smartBranch.contains("CompressionModelOption"))
    }

    @Test
    fun `接线 中英文文案都在`() {
        for (locale in listOf("values", "values-zh")) {
            var root: File? = File(System.getProperty("user.dir")).absoluteFile
            while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
            val xml = File(root, "app/src/main/res/$locale/strings.xml").readText()
            assertTrue("$locale 缺标题", xml.contains("name=\"chat_page_compress_classic_protect_user\""))
            assertTrue("$locale 缺说明", xml.contains("name=\"chat_page_compress_classic_protect_user_desc\""))
        }
    }

    @Test
    fun `红线 这个开关不许牵连智能压缩与主对话`() {
        val service = moduleFile("java/me/rerere/rikkahub/service/ChatService.kt")
        // 先钉住函数顺序，再看切片内容 —— 否则以后挪动函数位置会让这条负向断言悄悄变成永远绿。
        val smartStart = service.indexOf("suspend fun compressConversation(")
        val smartEnd = service.indexOf("fun shouldAutoCompressConversation(", smartStart.coerceAtLeast(0))
        assertTrue("智能压缩函数起点缺失", smartStart >= 0)
        assertTrue("智能压缩与自动压缩判定的顺序漂了（切片会读到别的方法）", smartEnd > smartStart)
        val smart = service.substring(smartStart, smartEnd)
        assertFalse("智能压缩有自己的用户消息保留口径，不能被这个开关改动", smart.contains("protectUserMessages"))
        val auto = service.substringAfter("suspend fun autoCompressForkConversation(")
            .substringBefore("return conversationRepo.getConversationById(forkConversation.id)")
        assertFalse("自动压缩走的是智能压缩，不许被牵连", auto.contains("protectUserMessages"))

        assertFalse("主对话生成循环不许看到这个开关", moduleFile("java/me/rerere/rikkahub/data/ai/GenerationLoop.kt").contains("protectUserMessages"))
        assertFalse("请求工厂不许看到这个开关", moduleFile("java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt").contains("protectUserMessages"))
    }
}
