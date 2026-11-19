package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v1033 守卫：经典压缩「用户发言保护」的口径升级。
 *
 * 用户原话：「经典压缩做不到每次压缩都保留用户的原话么？」随后选定
 * 「A 滚动原话账本」+「不设上限，全部原话永久逐字留着」。
 *
 * v1032 的实现只抄本次新压掉那一段，下一次压缩会把上一段账本当普通历史再摘要一遍，
 * 于是最早的原话退化成转述 —— 这正是用户追问的那个缺口。
 *
 * v1033 的实现路径不是「搬运上一次的账本」，而是**每次从完整历史逐字重取**：
 * 被压缩的原始消息从未被删除（[Conversation.insertContextCheckpoint] 只插入卡片、不删节点），
 * 所以不需要链式继承，也就不会因用户手动编辑卡片而断链。
 *
 * 必须同时成立的四件事：
 * ① 连续多次压缩后，最早的原话仍然逐字在场；
 * ② 旧卡片里那份账本不再重复喂给摘要模型（否则逐字段随压缩次数按平方级膨胀）；
 * ③ 关闭开关时，喂给模型的输入与摘要有无一字不差；
 * ④ 真实用户发言里恰好出现标记行时，绝不会被剥掉。
 */
class V1033ClassicVerbatimLedgerTest {

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

    private suspend fun ConversationSession.compress(
        protect: Boolean,
        summary: String,
        fed: MutableList<UIMessage> = mutableListOf(),
        keepRecent: Int = 0,
    ): MutableList<UIMessage> {
        compressClassicHistory(keepRecent, protectUserMessages = protect) { _, messages ->
            fed += messages
            summary
        }
        return fed
    }

    private fun ledgerOf(checkpointText: String): String {
        // 审查位 P2：必须有前置断言。标记缺失时 substringAfter 会返回**整串**，
        // 后面所有「不许包含 X」的负向断言就静默通过了 —— 比没测更糟。
        assertTrue("账本标记必须存在", checkpointText.contains(CLASSIC_VERBATIM_USER_MARKER))
        return checkpointText.substringAfter(CLASSIC_VERBATIM_USER_MARKER)
    }

    // ---- ① 永久逐字：压三次之后最早那句还在 ----

    @Test
    fun `连续三次压缩后 最早的原话仍然逐字在场`() = runBlocking {
        fixture(listOf(UIMessage.user("第一句需求"), UIMessage.assistant("回答一"))) { session, store ->
            session.compress(true, "摘要一")
            session.update {
                it.copy(messageNodes = it.messageNodes + UIMessage.user("第二句要求").toMessageNode())
            }
            session.compress(true, "摘要二")
            session.update {
                it.copy(
                    messageNodes = it.messageNodes +
                        UIMessage.user("第三句补充").toMessageNode() +
                        UIMessage.assistant("回答三").toMessageNode(),
                )
            }
            session.compress(true, "摘要三")

            val text = checkpointTextOf(store.persisted)
            assertTrue("最早那句必须还逐字在场", text.contains("1. 第一句需求"))
            assertTrue(text.contains("2. 第二句要求"))
            assertTrue(text.contains("3. 第三句补充"))
            assertEquals(
                "原话段落标题在一张卡片里只能出现一次",
                1,
                text.split(CLASSIC_VERBATIM_USER_MARKER).size - 1,
            )
            assertFalse(
                "旧摘要正文是 user 角色消息，绝不能混进原话清单",
                text.substringAfter(CLASSIC_VERBATIM_USER_MARKER).let { it.contains("摘要一") || it.contains("摘要二") },
            )
        }
    }

    @Test
    fun `保留尾巴里的发言不进账本 放开范围也不许越过压缩终点`() = runBlocking {
        fixture(
            listOf(
                UIMessage.user("要被压掉的发言"),
                UIMessage.assistant("回答一"),
                UIMessage.user("留在尾巴的发言"),
                UIMessage.assistant("回答二"),
            ),
        ) { session, store ->
            // v1033 把范围放开到「从第一条开始」，但终点仍是 endIndex：
            // 保留最近 2 条 → 尾巴那两句不许被抄进账本（它们本来就以原消息上传，抄了就是重复计费）。
            session.compress(true, "摘要", keepRecent = 2)
            val ledger = ledgerOf(checkpointTextOf(store.persisted))
            assertTrue("范围内的发言必须逐字在场", ledger.contains("1. 要被压掉的发言"))
            assertFalse("尾巴里的发言不许被重复抄进账本", ledger.contains("留在尾巴的发言"))
        }
    }

    // ---- ② 旧账本不再重复喂给摘要模型 ----

    @Test
    fun `开启保护时 旧卡片的账本不再喂给摘要模型`() = runBlocking {
        fixture(listOf(UIMessage.user("第一段原话"), UIMessage.assistant("回答"))) { session, store ->
            session.compress(true, "第一次摘要")
            session.update {
                it.copy(
                    messageNodes = it.messageNodes +
                        UIMessage.user("第二段原话").toMessageNode() +
                        UIMessage.assistant("再回答").toMessageNode(),
                )
            }
            val fed = session.compress(true, "第二次摘要")

            val checkpoints = fed.filter { it.isContextCheckpoint }
            assertEquals("第二次压缩应看到一张旧卡片", 1, checkpoints.size)
            assertEquals(
                "卡片正文必须只剩摘要本体",
                "第一次摘要",
                checkpoints.first().toText(),
            )

            // 本次卡片里的账本仍是全量。
            val ledger = checkpointTextOf(store.persisted).substringAfter(CLASSIC_VERBATIM_USER_MARKER)
            assertTrue(ledger.contains("第一段原话"))
            assertTrue(ledger.contains("第二段原话"))
        }
    }

    // ---- ③ 关闭开关时不许有任何连带变化 ----

    @Test
    fun `关闭保护时 喂给模型的旧卡片一字不变`() = runBlocking {
        fixture(listOf(UIMessage.user("第一段原话"), UIMessage.assistant("回答"))) { session, store ->
            // 先开保护造出一张带账本的旧卡片。
            session.compress(true, "第一次摘要")
            val checkpointWithLedger = store.persisted.currentMessages.last { it.isContextCheckpoint }.toText()
            assertTrue("前置条件：旧卡片确实带着账本", checkpointWithLedger.contains(CLASSIC_VERBATIM_USER_MARKER))

            session.update {
                it.copy(
                    messageNodes = it.messageNodes +
                        UIMessage.user("第二段原话").toMessageNode() +
                        UIMessage.assistant("再回答").toMessageNode(),
                )
            }
            val fed = session.compress(false, "第二次摘要")

            val oldCheckpoint = fed.filter { it.isContextCheckpoint }.map { it.toText() }
            assertTrue(
                "关闭保护时不许剥任何东西 —— 摘要输入必须与 v1032 完全一致",
                oldCheckpoint.any { it.contains(CLASSIC_VERBATIM_USER_MARKER) },
            )
            assertFalse(
                "关闭时账本一个字都不许多",
                checkpointTextOf(store.persisted).contains(CLASSIC_VERBATIM_USER_MARKER),
            )
        }
    }

    @Test
    fun `完全不传保护参数时走的仍是关闭分支 默认必须是关`() = runBlocking {
        fixture(listOf(UIMessage.user("唯一发言"), UIMessage.assistant("回答"))) { session, store ->
            // v1019 起的全部调用形态：具名参数都不给 + trailing lambda。
            session.compressClassicHistory { _, _ -> "只有一句摘要" }
            assertEquals("只有一句摘要", checkpointTextOf(store.persisted))
        }
    }

    // ---- ④ 不许误伤真实发言 ----

    @Test
    fun `真实用户发言里恰好出现标记行 不许被剥掉`() {
        val pasted = UIMessage.user("请把这段照抄：\n$CLASSIC_VERBATIM_USER_MARKER\n1. 示例")
        val kept = listOf(pasted).strippingClassicVerbatimLedger().first()
        assertSame("非检查点消息必须原对象返回", pasted, kept)
        assertTrue("用户粘贴的文字一个字都不许少", kept.toText().contains(CLASSIC_VERBATIM_USER_MARKER))
    }

    @Test
    fun `旧卡片没有账本时返回同一个对象 不制造假差异`() {
        val plain = UIMessage.user("旧摘要正文").copy(isContextCheckpoint = true)
        assertSame("没得剥就不该新建实例", plain, listOf(plain).strippingClassicVerbatimLedger().first())
    }

    @Test
    fun `剥账本只动正文 用量水位元数据必须原样保留`() {
        val part = UIMessagePart.Text(
            "摘要本体\n\n$CLASSIC_VERBATIM_USER_MARKER\n1. 原话",
            metadata = buildJsonObject { put("probe_key", "probe_value") },
        )
        val checkpoint = UIMessage.user("").copy(isContextCheckpoint = true, parts = listOf(part))
        val stripped = listOf(checkpoint).strippingClassicVerbatimLedger().first()

        assertEquals("摘要本体一个字都不许多或少", "摘要本体", stripped.toText())
        val metadata = (stripped.parts.first() as? UIMessagePart.Text)?.metadata
        assertNotNull("剥账本不许丢元数据", metadata)
        // 审查位 P2：按键取回原值，不用 toString 蒙混 —— 后者只要字串里出现过就算过，判据太松。
        assertEquals(
            "元数据必须按 key 取回原值",
            "probe_value",
            (metadata!!["probe_key"] as? JsonPrimitive)?.content,
        )
    }

    @Test
    fun `正文前面已有一处标记时 仍以我们追加的那份为准`() {
        // 说明：这条**区分不了**新旧实现 —— 形状相符那处恰好就是最后一处，
        // 旧写法（只看一次 lastIndexOf）也能通过。真正区分新旧的是下面那条
        // 「账本条目内出现标记行」的用例，别把这条当成回退循环的守卫。
        val pasted = "摘要里提到\n\n$CLASSIC_VERBATIM_USER_MARKER\n（模型抄写的说明）\n继续摘要"
        val card = pasted + "\n\n" + CLASSIC_VERBATIM_USER_MARKER + "\n1. 我的原话"
        assertEquals(
            "我们追加的那份必须剥掉，正文里那处标记一个字都不许动",
            pasted,
            stripClassicVerbatimLedger(card),
        )
    }

    @Test
    fun `账本条目内出现标记行时 必须回退到我们追加的那一处`() {
        // 第三轮双审查一致命中的唯一实质问题（不带全文记 P2、带全文记 P1）：
        // 本轮生产改动只有「从后往前回退找形状相符标记位」这一件事，
        // 而原有用例里形状相符那处正好就是最后一处 —— 退回旧实现照样全绿，守卫等于没写。
        // 本用例用**生产函数** classicVerbatimUserAppendix 造真实卡片：
        // 用户原话里含标记行时，条目内换行被 replace 成三空格缩进，
        // 于是条目里那处标记成为**最后一处**且形状不符 → 新实现回退剥掉；
        // 退回第二轮单次 lastIndexOf 判据时整张卡片不剥，本用例必红（即「退回预演必须变红」）。
        val withMarker = "我的原话\n\n$CLASSIC_VERBATIM_USER_MARKER\n1. 示例"
        val card = "摘要" + classicVerbatimUserAppendix(listOf(UIMessage.user(withMarker)))
        assertEquals(
            "前置条件：必须有两处标记（我们追加的 + 条目内被缩进的那处）",
            2,
            card.countOf(CLASSIC_VERBATIM_USER_MARKER),
        )
        assertTrue(
            "前置条件：最后一处必须落在条目内（前面是三空格缩进，形状不符）",
            card.lastIndexOf(CLASSIC_VERBATIM_USER_MARKER) > card.indexOf(CLASSIC_VERBATIM_USER_MARKER),
        )
        assertEquals(
            "必须回退到形状相符的那一处，只留下摘要本体",
            "摘要",
            stripClassicVerbatimLedger(card),
        )
    }

    @Test
    fun `标记行出现在文本最开头时原样返回 不许把正文清空`() {
        // 钉住循环的 -1 终止边界：index=0 时 lastIndexOf(marker, -1) 必须结束循环并返回原串。
        val onlyMarker = CLASSIC_VERBATIM_USER_MARKER + "\n1. 没有摘要本体"
        assertSame(
            "开头那处的 before 是空串，不满足「前面有空行」，一个字都不许剥",
            onlyMarker,
            stripClassicVerbatimLedger(onlyMarker),
        )
    }

    @Test
    fun `剥账本后检查点身份与卡片条数都不变`() {
        val checkpoint = UIMessage.user("摘要\n\n$CLASSIC_VERBATIM_USER_MARKER\n1. 原话")
            .copy(isContextCheckpoint = true)
        val user = UIMessage.user("我的原话")
        val result = listOf(user, checkpoint).strippingClassicVerbatimLedger()
        assertEquals(2, result.size)
        assertNotSame("卡片应被换成剥过的新实例", checkpoint, result[1])
        assertTrue("剥完仍然是检查点身份", result[1].isContextCheckpoint)
        assertEquals("真实发言必须排在原位", "我的原话", result[0].toText())
    }

    // ---- 不设上限 ----

    @Test
    fun `多条超长原话全部逐字保留 不再有约两万token上限`() {
        val first = "甲".repeat(40_000)
        val second = "乙".repeat(40_000)
        val appendix = classicVerbatimUserAppendix(listOf(UIMessage.user(first), UIMessage.user(second)))
        assertFalse("不许出现截断标记", appendix.contains("tokens truncated"))
        assertTrue("第一条必须整条在场", appendix.contains(first))
        assertTrue("第二条必须整条在场", appendix.contains(second))
        assertEquals(
            "两条各占一个编号",
            listOf("1. ", "2. "),
            appendix.lines().filter { it.startsWith("1. ") || it.startsWith("2. ") }.map { it.substring(0, 3) },
        )
    }

    @Test
    fun `不设上限只影响经典压缩 不许改动智能压缩那份预算常量`() {
        // 固定税：预算常量仍归智能压缩（Codex）使用，经典压缩不再读它。
        // 谁哪天想给经典压缩重新加上限，必须先拿到用户明确同意（用户口径：不设上限）。
        val prompts = readFile("app/src/main/java/me/rerere/rikkahub/data/ai/prompts/CompressPrompt.kt")
        assertTrue("智能压缩的 2 万 token 预算不许被动", prompts.contains("COMPRESS_RETAINED_USER_MESSAGE_MAX_TOKENS = 20_000"))
        val classic = readFile("app/src/main/java/me/rerere/rikkahub/service/ClassicCompression.kt")
        // 审查位 P2：切片类判据必须先确认锚点存在。substringAfter 找不到锚点会返回整份文件，
        // 那种负向断言就变成"永远通过"的假判据 —— 比没有测试更糟。
        assertTrue("锚点函数必须还在", classic.contains("internal fun classicVerbatimUserAppendix"))
        assertTrue(
            "必须显式走「不设上限」具名哨兵",
            classic.contains("maxTokens = CLASSIC_VERBATIM_NO_LIMIT")
                && classic.contains("internal const val CLASSIC_VERBATIM_NO_LIMIT = Int.MAX_VALUE"),
        )
        assertEquals(
            "经典压缩文件里那个智能压缩预算常量一次都不许出现",
            0,
            classic.countOf("COMPRESS_RETAINED_USER_MESSAGE_MAX_TOKENS"),
        )
    }

    // ---- 审查位 P1：剥离的形状判据 ----

    @Test
    fun `摘要正文里自己出现标记行时 不许把摘要本体截掉`() {
        // 模型可能把上一版账本原样抄进摘要；或者用户在卡片里粘过这段标题。
        // indexOf 版本会命中内容里那一处并把摘要本体拦腰截断 —— 审查位 P1。
        val quoted = "前段摘要\n\n$CLASSIC_VERBATIM_USER_MARKER\n（模型自己抄写的一行说明）\n后段摘要"
        assertSame(
            "形状对不上就不许剥，宁可不剥也不许动正文",
            quoted,
            stripClassicVerbatimLedger(quoted),
        )
        val ours = "摘要本体\n\n$CLASSIC_VERBATIM_USER_MARKER\n1. 原话"
        assertEquals("我们自己追加的那种形状必须能剥干净", "摘要本体", stripClassicVerbatimLedger(ours))
    }

    @Test
    fun `标记行夹在正文中间时 检查点不被换成新实例`() {
        val odd = UIMessage.user("上文\n$CLASSIC_VERBATIM_USER_MARKER\n下文").copy(isContextCheckpoint = true)
        assertSame("剥不掉就返回原对象，不许制造假差异", odd, listOf(odd).strippingClassicVerbatimLedger().first())
    }

    @Test
    fun `第三次压缩时前两张旧卡片都已被剥过账本`() = runBlocking {
        fixture(listOf(UIMessage.user("原话一"), UIMessage.assistant("答一"))) { session, _ ->
            session.compress(true, "摘要一")
            session.update { it.copy(messageNodes = it.messageNodes + UIMessage.user("原话二").toMessageNode()) }
            session.compress(true, "摘要二")
            session.update { it.copy(messageNodes = it.messageNodes + UIMessage.user("原话三").toMessageNode()) }
            val fed = session.compress(true, "摘要三")

            val checkpoints = fed.filter { it.isContextCheckpoint }
            assertEquals("前置条件：第三次压缩时该看到两张旧卡片", 2, checkpoints.size)
            for (card in checkpoints) {
                assertFalse(
                    "每张旧卡片喂给模型时都必须已经剥掉账本",
                    card.toText().contains(CLASSIC_VERBATIM_USER_MARKER),
                )
            }
            assertEquals(listOf("摘要一", "摘要二"), checkpoints.map { it.toText() })
        }
    }

    private fun String.countOf(needle: String): Int {
        // 不用正则：Kotlin 字符串里的反斜杠转义在测试里极易写错（本项目踩过）。
        var count = 0
        var i = indexOf(needle)
        while (i >= 0) {
            count++
            i = indexOf(needle, i + needle.length)
        }
        return count
    }

    private fun readFile(relative: String): String {
        var root = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !java.io.File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("找不到工程根目录", root)
        val file = java.io.File(root, relative)
        // 读不到文件必须立刻红：返回空串会让所有「不许出现」的负向断言静默通过。
        assertTrue("守卫测试读不到文件：${file.path}", file.exists())
        return file.readText()
    }
}
