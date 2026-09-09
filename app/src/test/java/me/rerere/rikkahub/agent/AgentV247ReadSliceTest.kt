package me.rerere.rikkahub.agent

import me.rerere.rikkahub.agent.tools.agentReadBudgetChars
import me.rerere.rikkahub.agent.tools.agentReadSafeChars
import me.rerere.rikkahub.agent.tools.buildReadFilePayload
import me.rerere.rikkahub.agent.tools.sliceForToolPayload
import me.rerere.rikkahub.agent.tools.sliceTextByLines
import me.rerere.rikkahub.data.datastore.TOOL_OUTPUT_LIMIT_DEFAULT_KB
import me.rerere.rikkahub.data.datastore.TOOL_OUTPUT_LIMIT_MAX_KB
import me.rerere.rikkahub.data.datastore.TOOL_OUTPUT_LIMIT_MIN_KB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v247 门禁：两件真机反馈出来的事。
 *
 * ## 一、子代理读大文件读不全（本轮实测抓到）
 *
 * 旧实现只做 `take(defaultBudget)`，**没有任何续读办法**。实测：一条子代理审查
 * GenerationAgentBackend.kt（71614 字节）时只看到前 45%，它自己在报告里如实写了
 * 「函数体我未能直接读到原文，只能依据搜索返回的行内片段推断」。
 * 现在按行分段 + 回传 next_start_line，子代理能接着读完。
 *
 * 本测试的核心是**分段拼回来必须和原文一字不差**（含真实源码文件的端到端用例），
 * 因为这类改动最容易在换行符上出错 —— 空行会让「是不是第一行」的判断失效，
 * 用 builder.isEmpty() 当判据就会把空行处的换行吞掉。
 *
 * ## 二、详情页「工具执行中」旁边仍写「没动静」（用户真机反馈）
 *
 * 用户原话：「有显示工具执行中，但是工具执行中没动静的计时还在涨，我不知道是特意设置
 * 还是漏洞」。真相是一半设计一半漏洞：
 * - 数字继续涨是**刻意的**（如实反映模型确实这么久没吐新内容），不能清零；
 * - 但 v246 只在面板把标签换成「工具已跑」，**详情页漏了、只改了颜色** ——
 *   同一件事两个界面两种说法，正是 v246 想消掉的「界面说法漂移」。
 *
 * 所以这里加一条防漂移断言：两个界面文件都必须同时具备「工具已跑」与「没动静」两套说法。
 *
 * ## 三、v248 补：分段本身被外层再截一刀（真机抓到，v247 的回归）
 *
 * v247 把单段预算设成 32KB，正好等于外层对**单个工具返回**的硬上限
 * （`GenerationLoop.MAX_TOOL_OUTPUT_CHARS`）。但工具返回是 JSON，换行与引号要转义，
 * 实测 32721 字符的段序列化成 **33955** 字符 → 每段都被外层截掉，子代理没有 shell、
 * 只能拿到 4KB 预览。而元数据排在 content 前面，模型照样看到 `end_line=879` /
 * `next_start_line=880`，于是**「以为读过了」直接跳段，中间约 800 行静默丢失、毫无报错**。
 *
 * v248 双保险：默认预算降到 24KB，再按**实际序列化长度**回缩到安全线以内
 * （`sliceForToolPayload`）。下面用真实源码 + 三种病态文件（全换行、全引号反斜杠、单行 20 万字符）
 * 钉住「任何输入都不会超上限，且不跳行」。
 */
class AgentV247ReadSliceTest {

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

    private val readOnlyPath =
        "app/src/main/java/me/rerere/rikkahub/agent/tools/AgentReadOnlyTools.kt"
    private val pagePath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentThreadPage.kt"
    private val panelPath =
        "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentActivityPanel.kt"
    private val backendPath =
        "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"

    // v248：换算与 Settings.toolOutputCharLimit 保持一致（测试里不便构造 Settings，故本地复算）
    private fun limitChars(kb: Int): Int =
        kb.coerceIn(TOOL_OUTPUT_LIMIT_MIN_KB, TOOL_OUTPUT_LIMIT_MAX_KB) * 1024

    // v248：一切以「设置里的 KB 档位」为准算出来，不再写死常量
    private val defaultLimitChars = limitChars(TOOL_OUTPUT_LIMIT_DEFAULT_KB)
    private val defaultBudget = agentReadBudgetChars(defaultLimitChars)
    private val defaultSafe = agentReadSafeChars(defaultLimitChars)

    // ------------------------------------------------------------ 分段读（真跑逻辑）

    @Test
    fun `装得下就一次读完，不该给下一段`() {
        val text = "第一行\n第二行\n第三行"
        val slice = sliceTextByLines(text, 1, 1000)
        assertEquals("内容必须一字不差", text, slice.content)
        assertEquals(1, slice.startLine)
        assertEquals(3, slice.endLine)
        assertEquals(3, slice.totalLines)
        assertFalse("没有后续就不该说还有", slice.hasMore)
        assertNull("没有后续就不该给续读行号", slice.nextStartLine)
        assertFalse(slice.lineTruncated)
    }

    @Test
    fun `长文本分段读完，拼起来必须和原文一字不差`() {
        val text = (1..400).joinToString("\n") { "第 $it 行，这里补一些字撑长度让它必须分段。" }
        val budget = 300
        val rebuilt = StringBuilder()
        var start = 1
        var rounds = 0
        while (true) {
            val slice = sliceTextByLines(text, start, budget)
            assertFalse("正常文本不该出现单行超长截断", slice.lineTruncated)
            if (rounds > 0) rebuilt.append('\n')
            rebuilt.append(slice.content)
            rounds++
            val next = slice.nextStartLine ?: break
            assertTrue("续读行号必须往前走，否则会死循环", next > start)
            start = next
            if (rounds > 1000) throw AssertionError("分段没有收敛，可能死循环")
        }
        assertTrue("这个预算下必须真的分成多段，否则测的不是分段", rounds > 1)
        assertEquals("分段拼回来必须等于原文", text, rebuilt.toString())
    }

    @Test
    fun `空行不许把换行吞掉`() {
        // 这是实现里最容易踩的坑：用 builder.isEmpty() 判断「是不是第一行」，
        // 遇到空行时 builder 仍为空，下一行又被当成第一行，中间那个换行就没了。
        val text = "\n\n正文\n\n结尾"
        val slice = sliceTextByLines(text, 1, 1000)
        assertEquals("空行必须原样保留", text, slice.content)
        assertEquals(5, slice.totalLines)
    }

    @Test
    fun `全是空行也要原样返回`() {
        val text = "\n\n\n"
        val slice = sliceTextByLines(text, 1, 1000)
        assertEquals(text, slice.content)
        assertEquals(4, slice.totalLines)
        assertFalse(slice.hasMore)
    }

    @Test
    fun `单行超长必须截断但仍要往下走，不能卡在同一行`() {
        val text = "x".repeat(50) + "\n后面还有"
        val first = sliceTextByLines(text, 1, 10)
        assertEquals("x".repeat(10), first.content)
        assertTrue("这一行没读全，必须如实说出来", first.lineTruncated)
        assertEquals(1, first.endLine)
        assertEquals("必须指向下一行，否则会反复读同一行", 2, first.nextStartLine)

        val second = sliceTextByLines(text, first.nextStartLine!!, 10)
        assertEquals("后面还有", second.content)
        assertFalse(second.hasMore)
    }

    @Test
    fun `起始行越界或小于一都不许崩`() {
        val text = "甲\n乙"
        val tooBig = sliceTextByLines(text, 99, 100)
        assertEquals("越界要夹到最后一行", 2, tooBig.startLine)
        assertEquals("乙", tooBig.content)
        assertFalse(tooBig.hasMore)

        listOf(0, -1, -999).forEach { bad ->
            val slice = sliceTextByLines(text, bad, 100)
            assertEquals("小于 1 一律当 1（传 $bad）", 1, slice.startLine)
            assertEquals(text, slice.content)
        }
    }

    @Test
    fun `空文件也要给出结构化结果`() {
        val slice = sliceTextByLines("", 1, 100)
        assertEquals("", slice.content)
        assertEquals(1, slice.totalLines)
        assertEquals(1, slice.startLine)
        assertFalse(slice.hasMore)
        assertNull(slice.nextStartLine)
    }

    @Test
    fun `真实大文件必须能分段读完 —— 正是本轮实测读不全的那个文件`() {
        val backendPath = "app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt"
        val text = source(backendPath)
        assertTrue(
            "这个文件必须确实超过单次上限，否则这条用例没有意义（当前 ${text.length} 字符）",
            text.length > defaultBudget,
        )
        val rebuilt = StringBuilder()
        var start = 1
        var rounds = 0
        while (true) {
            val slice = sliceTextByLines(text, start, defaultBudget)
            assertTrue("每段都不许超预算", slice.content.length <= defaultBudget)
            if (rounds > 0) rebuilt.append('\n')
            rebuilt.append(slice.content)
            rounds++
            val next = slice.nextStartLine ?: break
            start = next
            if (rounds > 1000) throw AssertionError("分段没有收敛")
        }
        assertTrue("必须真的分了多段", rounds >= 2)
        assertEquals("整份源码分段读回来必须一字不差", text, rebuilt.toString())
    }

    // ------------------------------------------------------------ 门禁：工具真的把续读接上了

    @Test
    fun `读文件工具必须暴露续读参数并回传续读行号`() {
        val readOnly = source(readOnlyPath)
        assertTrue(
            "必须有 start_line 入参，否则子代理没法接着读",
            readOnly.contains("put(\"start_line\", buildJsonObject {"),
        )
        assertTrue(
            "必须回传 next_start_line，否则子代理不知道从哪接",
            readOnly.contains("put(\"next_start_line\", next)"),
        )
        assertTrue(
            "必须走「切一段 + 按序列化长度回缩」的入口，不能又退回一刀切",
            readOnly.contains("startLine = params.intOrNull(\"start_line\") ?: 1,") &&
                readOnly.contains("limitChars = limitChars,"),
        )
        assertFalse(
            "旧的一刀切写法必须已经删掉",
            readOnly.contains("val body = if (truncated) text.take(defaultBudget) else text"),
        )
        assertTrue(
            "工具说明里要写清怎么续读，否则模型不会用",
            readOnly.contains("next_start_line") &&
                readOnly.contains("call again with the same path and start_line set to that number"),
        )
        assertTrue(
            "行数信息要给全，模型才知道自己读到哪了",
            readOnly.contains("put(\"total_lines\", slice.totalLines)") &&
                readOnly.contains("put(\"end_line\", slice.endLine)"),
        )
    }

    @Test
    fun `加了续读也不许顺手放开写或执行能力`() {
        val readOnly = source(readOnlyPath)
        listOf(
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_shell",
            "workspace_publish_file",
        ).forEach { forbidden ->
            assertFalse("只读工具集不得出现 $forbidden", readOnly.contains("name = \"$forbidden\""))
        }
        assertFalse("不得出现执行命令的调用", readOnly.contains("executeCommand"))
        assertEquals(
            "只读工具仍然只有 4 把",
            4,
            Regex("""    name = "workspace_""").findAll(readOnly).count(),
        )
    }

    // ------------------------------------------------------------ 门禁：两个界面不许各说一套

    @Test
    fun `详情页在工具执行期间必须把标签换成「工具已跑」`() {
        val page = source(pagePath)
        assertTrue(
            "这是用户真机反馈的漏点：显示「工具执行中」旁边却仍写「没动静」",
            page.contains("if (livePendingTools > 0) \"工具已跑 \${formatAgentDuration(it)}\""),
        )
        assertTrue(
            "工具没在跑时仍然要说「没动静」，这个语义不能丢",
            page.contains("else \"没动静 \${formatAgentDuration(it)}\""),
        )
        assertTrue(
            "工具在跑不许标红（v246 已定的行为，不许回退）",
            page.contains("livePendingTools > 0 -> MaterialTheme.colorScheme.primary"),
        )
    }

    @Test
    fun `面板与详情页必须都具备两套说法，防止只改一处`() {
        val page = source(pagePath)
        val panel = source(panelPath)
        listOf("page" to page, "panel" to panel).forEach { (name, srcText) ->
            assertTrue("$name 必须有「工具已跑」这套说法", srcText.contains("\"工具已跑 \$"))
            assertTrue("$name 必须保留「没动静」这套说法", srcText.contains("\"没动静 \$"))
        }
        assertTrue(
            "两处都必须由「有没有工具在飞」决定，不能各用一套判据",
            page.contains("agentPendingToolCount(events)") &&
                panel.contains("pendingTools = signal?.pendingTools ?: 0"),
        )
    }

    // ------------------------------------------------------------ v248：序列化后不许被外层截断

    /**
     * 外层（GenerationLoop）对**单个工具返回**有 32KB 硬上限，超了就只给模型 4KB 预览。
     * 而元数据（start_line / end_line / next_start_line）排在 content 之前，所以一旦被外层截断，
     * 模型照样能读到 next_start_line，于是「以为读过了」直接跳段 —— 中间几百行静默丢失。
     * 这一组用例就是钉死这条路。
     */
    private val outerToolOutputLimit = 32 * 1024

    @Test
    fun `v247 的 32KB 预算确实会超出外层上限 —— 这就是本版要修的`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        val old = sliceTextByLines(text, 1, 32 * 1024)
        val oldPayload = buildReadFilePayload("/workspace/x.kt", old)
        assertTrue(
            "旧做法只算 content 字符数、没算 JSON 转义，序列化后必然超（实测约 33955）",
            oldPayload.length > outerToolOutputLimit,
        )
        assertTrue(
            "任何档位下，预算都必须给转义留出余量",
            listOf(32, 64, 96, 128).all { kb ->
                val limit = limitChars(kb)
                agentReadBudgetChars(limit) < limit && agentReadSafeChars(limit) < limit
            },
        )
    }

    @Test
    fun `真实源码每一段序列化后都不许超安全线，且整份拼回一字不差`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        val rebuilt = StringBuilder()
        var start = 1
        var rounds = 0
        while (true) {
            val (slice, payload) = sliceForToolPayload("/workspace/x.kt", text, start)
            assertTrue(
                "第 $rounds 段序列化后 ${payload.length} 字符，超过安全线 $defaultSafe",
                payload.length <= defaultSafe,
            )
            assertTrue("也必须小于子代理这一档的上限", payload.length <= defaultLimitChars)
            if (rounds > 0) rebuilt.append('\n')
            rebuilt.append(slice.content)
            rounds++
            val next = slice.nextStartLine ?: break
            assertTrue("必须往前走", next > start)
            start = next
            if (rounds > 2000) throw AssertionError("没有收敛")
        }
        assertTrue("必须分成多段", rounds >= 2)
        assertEquals("分段拼回来必须等于原文", text, rebuilt.toString())
    }

    @Test
    fun `全是换行的病态文件也不许超安全线`() {
        val text = "\n".repeat(60_000)
        var start = 1
        var rounds = 0
        var covered = 0
        while (true) {
            val (slice, payload) = sliceForToolPayload("/workspace/x.txt", text, start)
            assertTrue("换行会被转义成两个字符，最容易撑爆", payload.length <= defaultSafe)
            covered += slice.endLine - slice.startLine + 1
            rounds++
            val next = slice.nextStartLine ?: break
            assertTrue("必须往前走，否则死循环", next > start)
            start = next
            if (rounds > 2000) throw AssertionError("没有收敛")
        }
        assertEquals("每一行都必须被覆盖到，不许跳行", 60_001, covered)
    }

    @Test
    fun `全是引号反斜杠的病态文件也不许超安全线`() {
        // 转义膨胀最坏的情况：每个字符都要变成两个
        val text = (1..400).joinToString("\n") { "\"\\".repeat(200) }
        var start = 1
        var rounds = 0
        while (true) {
            val (slice, payload) = sliceForToolPayload("/workspace/x.json", text, start)
            assertTrue(
                "这种内容序列化后接近翻倍，固定预算一定会超（第 $rounds 段 ${payload.length}）",
                payload.length <= defaultSafe,
            )
            rounds++
            val next = slice.nextStartLine ?: break
            assertTrue(next > start)
            start = next
            if (rounds > 2000) throw AssertionError("没有收敛")
        }
        assertTrue("必须分成多段", rounds >= 2)
    }

    @Test
    fun `单行极长时预算会自动缩小，仍然不许超安全线`() {
        val text = "\"".repeat(200_000)
        val (slice, payload) = sliceForToolPayload("/workspace/x.txt", text, 1)
        assertTrue("必须回缩到安全线内", payload.length <= defaultSafe)
        assertTrue("这一行没读全，必须如实标注", slice.lineTruncated)
        assertTrue(
            "回缩后的段长必须真的小于默认预算，说明回缩生效了",
            slice.content.length < defaultBudget,
        )
    }

    @Test
    fun `截断超长行不许把一个字符切成两半`() {
        // 每个 emoji 在 Kotlin 里占两个 char（UTF-16 代理对），切在中间会留下半个字符
        val emoji = "😀"
        val text = emoji.repeat(30_000)
        val slice = sliceTextByLines(text, 1, 1001)   // 奇数预算，必然落在代理对中间
        assertTrue(slice.lineTruncated)
        val last = slice.content.lastOrNull()
        assertTrue(
            "最后一个字符不许是「高代理项」，否则界面上会出现乱码方块",
            last == null || !last.isHighSurrogate(),
        )
        assertEquals("应当退让一个位置，取偶数长度", 1000, slice.content.length)
    }

    @Test
    fun `安全线本身必须小于外层硬上限`() {
        assertTrue(
            "留余量的意义就在这里：$defaultSafe 必须 < $defaultLimitChars",
            defaultSafe < defaultLimitChars,
        )
    }

    // ------------------------------------------------------------ v248：档位设置

    @Test
    fun `档位换算必须准确，越界要夹住`() {
        assertEquals(32 * 1024, limitChars(32))
        assertEquals(64 * 1024, limitChars(64))
        assertEquals(128 * 1024, limitChars(128))
        assertEquals(256 * 1024, limitChars(256))
        assertEquals("低于下限一律按下限", TOOL_OUTPUT_LIMIT_MIN_KB * 1024, limitChars(1))
        assertEquals("低于下限一律按下限", TOOL_OUTPUT_LIMIT_MIN_KB * 1024, limitChars(-99))
        assertEquals("高于上限一律按上限", TOOL_OUTPUT_LIMIT_MAX_KB * 1024, limitChars(9999))
        assertEquals("默认档位就是设置里的默认值", 64, TOOL_OUTPUT_LIMIT_DEFAULT_KB)
        // 32 档位必须刚好退回本版早先那套数（24KB 预算 / 31KB 安全线），保证行为可预期
        assertEquals(24 * 1024, agentReadBudgetChars(limitChars(32)))
        assertEquals(31 * 1024, agentReadSafeChars(limitChars(32)))
        assertEquals("默认 64 档位下预算 48KB", 48 * 1024, defaultBudget)
        assertEquals("默认 64 档位下安全线 63KB", 63 * 1024, defaultSafe)
    }

    @Test
    fun `每个档位下读同一个大文件都不许超各自的安全线，也不许丢内容`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        listOf(32, 64, 96, 128).forEach { kb ->
            val limit = limitChars(kb)
            val safe = agentReadSafeChars(limit)
            val rebuilt = StringBuilder()
            var start = 1
            var rounds = 0
            while (true) {
                val (slice, payload) = sliceForToolPayload("/workspace/x.kt", text, start, limit)
                assertTrue(
                    "${kb}KB 档位第 $rounds 段序列化 ${payload.length} 字符，超过安全线 $safe",
                    payload.length <= safe,
                )
                if (rounds > 0) rebuilt.append('\n')
                rebuilt.append(slice.content)
                rounds++
                val next = slice.nextStartLine ?: break
                start = next
                if (rounds > 2000) throw AssertionError("${kb}KB 档位没有收敛")
            }
            assertEquals("${kb}KB 档位下拼回来必须等于原文", text, rebuilt.toString())
        }
    }

    @Test
    fun `档位调大必须真的减少读取次数`() {
        val text = source("app/src/main/java/me/rerere/rikkahub/agent/runtime/GenerationAgentBackend.kt")
        fun roundsFor(kb: Int): Int {
            var start = 1
            var rounds = 0
            while (true) {
                val (slice, _) = sliceForToolPayload("/workspace/x.kt", text, start, limitChars(kb))
                rounds++
                start = slice.nextStartLine ?: break
                if (rounds > 2000) throw AssertionError("没有收敛")
            }
            return rounds
        }
        val small = roundsFor(32)
        val big = roundsFor(128)
        assertTrue(
            "调大档位的意义就是少跑几步：32KB 用了 $small 次，128KB 用了 $big 次",
            big < small,
        )
    }

    @Test
    fun `子代理读取上限必须走完设置五环节`() {
        val prefs = source("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue(
            "1 定义 key",
            prefs.contains("val TOOL_OUTPUT_LIMIT_KB = intPreferencesKey(\"tool_output_limit_kb\")"),
        )
        assertTrue("2 读取", prefs.contains("toolOutputLimitKb = (preferences[TOOL_OUTPUT_LIMIT_KB]"))
        assertTrue(
            "3 写入 —— 少了这一环，用户调完下一次发射就恢复默认（历史上真踩过）",
            prefs.contains("preferences[TOOL_OUTPUT_LIMIT_KB] = settings.toolOutputLimitKb"),
        )
        assertTrue("4 字段带默认值", prefs.contains("val toolOutputLimitKb: Int = TOOL_OUTPUT_LIMIT_DEFAULT_KB,"))

        val picker = source("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
        assertTrue("5 设置界面有调节入口", picker.contains("agent_tools_read_limit_title"))
        assertTrue(
            "5 加减按钮必须真的改这个字段",
            picker.contains("settings.copy(toolOutputLimitKb = (settings.toolOutputLimitKb - 32)") &&
                picker.contains("settings.copy(toolOutputLimitKb = (settings.toolOutputLimitKb + 32)"),
        )

        val backend = source(backendPath)
        assertTrue(
            "6 工具装配必须按设置值给预算",
            backend.contains("settings.toolOutputCharLimit,"),
        )
        assertTrue(
            "6 生成层的截断上限也必须用同一个值，否则工具给多了照样被截",
            backend.contains("toolOutputCharLimit = settings.toolOutputCharLimit,"),
        )

        val handler = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(
            "生成层要接这个参数，但默认值必须仍是上游那个 32KB",
            handler.contains("toolOutputCharLimit: Int = MAX_TOOL_OUTPUT_CHARS,"),
        )
        assertTrue(
            "截断判定必须用传进来的值，不能继续用写死的常量",
            handler.contains("if (totalChars <= charLimit.coerceAtLeast(4 * 1024)) return output"),
        )
        assertFalse(
            "生成层绝对不许自己去读这个设置 —— 一读主对话就被牵连（用户红线：主模型不能做任何限制）",
            handler.contains("settings.toolOutputCharLimit"),
        )
    }

    @Test
    fun `主对话与圆桌绝对不许被这个设置牵连`() {
        // 用户原话：「你子代理可以随便动，不好用大不了以后不用，但是主模型绝对不能乱动，
        // 不能做出任何限制」。所以这一条是硬红线，不是风格问题。
        val handler = source("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
        assertTrue(
            "工具返回上限的默认值必须保持上游的 32KB",
            handler.contains("toolOutputCharLimit: Int = MAX_TOOL_OUTPUT_CHARS,"),
        )
        assertTrue(
            "上游那个常量本身不许改动",
            handler.contains("internal const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024"),
        )
        val chatService = source("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
        assertFalse(
            "主对话发起生成时绝对不许传这个参数（不传才等于行为不变）",
            chatService.contains("toolOutputCharLimit"),
        )

        val fs = source("workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt")
        assertTrue(
            "检索的剪枝与预算必须默认关闭，只有明确开启的调用方才生效",
            fs.contains("budgeted: Boolean = false,"),
        )
        val roundTable = source("app/src/main/java/me/rerere/rikkahub/data/ai/tools/RoundTableReadOnlyTools.kt")
        assertFalse("圆桌的检索工具本轮不动", roundTable.contains("budgeted"))
        val readOnly = source(readOnlyPath)
        assertTrue("只有子代理这条路开启刹车", readOnly.contains("budgeted = true"))
    }

    @Test
    fun `提示词必须教子代理先搜再跳读，别从第一行一段段翻`() {
        val backend = source(backendPath)
        assertTrue(
            "光有续读能力不够，得告诉它可以直接跳到搜索命中的行号",
            backend.contains("先搜再跳读，别从头翻"),
        )
        assertTrue(
            "要说清楚怎么跳",
            backend.contains("再把 `start_line` 直接填成那个行号"),
        )
    }
}
