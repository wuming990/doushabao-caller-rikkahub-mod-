package me.rerere.rikkahub.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1026：路由注册完整性门禁。
 *
 * 真机崩溃（用户实测报告）：设置 → MCP → 点某个服务器详情，App 直接挂掉，堆栈是
 * `IllegalStateException: Unknown screen SettingMcpDetail(serverId=...)`，抛在
 * Navigation3 的 EntryProviderScope 里。
 *
 * 根因不是这次联网修复：官方 3.1.1 本来就注册了 `entry<Screen.SettingMcpDetail>`
 * （官方 RouteActivity.kt:524），我们 v1024 快照里这条已经没了 —— 更早某次合并上游时
 * 漏掉的官方增量。Screen 定义、页面函数、连那行 import 全都还在，唯独少了 entry 注册，
 * 所以静态看代码一切正常，只有真点进去才炸。
 *
 * 为什么必须机器扫：少一条 entry 不违反任何类型约束，BUILD 照样 SUCCESSFUL；
 * 而全 App 任何一个页面丢了注册都是「点进去就崩」，人工合并时没人会逐个点一遍。
 *
 * 核心不变式：`全项目 Screen 子类型定义集合 == entryProvider 实际注册集合`。
 * 两侧差集都必须为空。选它而不是「扫 navigate(Screen.X) 调用点」，因为
 * NavContext 的 navigate(screen: Screen) 与 clearAndNavigate 都接受变量，扫字面调用必然漏；
 * 只要每个定义出来的 Screen 都有注册，变量传进来的是哪一个都不可能崩。
 *
 * 定义扫描覆盖**整个 app/src/main/java**，不是只读 RouteActivity：
 * 第四轮审查建议不要把「只能定义在某个文件里」当永久架构限制，这个意见是对的 ——
 * 扫全项目既解除了架构限制，覆盖又比单文件更完整，所以这里不禁止将来拆分文件。
 *
 * 解析器必须防住四条已证实的假绿路径（前三条由独立审查逐轮抓出，第四条由主模型自查抓出）：
 * 1. 不剥注释 → 把注册语句注释掉，正则照样匹配注释文字，测试绿着放过去；
 * 2. 不抹字符串 → 源码里一句 "entry<Screen.Fake>" 字面量也会被当成注册；
 * 3. 定义正则 `\([^)]*\)` 遇到 `Chat(... = emptyList(), ...) : Screen` 的嵌套括号会截断，
 *    整个定义被漏，集合变小 → 假绿；
 * 4. 全文匹配 entry → 把注册挪进 `if (false)` 分支或没被调用的辅助函数，照样算已注册。
 * 所以：注册只在真正的 entryProvider 块内统计；定义解析用括号配平；
 * 剥注释时连字符串内容一起抹（保留引号与换行，行号不漂）。
 *
 * 末尾的元测试直接拿合成源码喂解析器，逐条验证它不上当。门禁自己会骗人比没有门禁更危险。
 *
 * 已知边界（刻意不追求，写清楚免得后人误信）：这不是完整 Kotlin 词法分析器。
 * 未覆盖的形状包括自定义注解改写声明、以及把 Screen 子类型藏在生成代码里的情况。
 * 真出现那些形态时本门禁可能漏判 —— 但它已覆盖本项目实际使用的全部声明写法。
 *
 * 刻意不把「有注册但没有任何导航入口」判成失败：FileManager 是我们自己按别的入口进的，
 * ShareHandler 由外部 Intent 走，都不是缺陷；判失败只会逼人删掉正常代码。
 */
class RouteNavigationRegistryTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private val mainRoot = File(repoRoot, "app/src/main/java")
    private val routeFile = File(mainRoot, "me/rerere/rikkahub/RouteActivity.kt")

    private val routeSourceText: String by lazy {
        assertTrue("找不到 RouteActivity.kt", routeFile.exists())
        routeFile.readText()
    }

    private val strippedRouteSource: String by lazy {
        KotlinCommentStripper.strip(routeSourceText)
    }

    /** 全项目剥注释后的源码，按「粗筛含 Screen 字样」过滤后再解析，避免整棵树白扫。 */
    private val projectSources: List<Pair<String, String>> by lazy {
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            // 先按原文粗筛，只对候选文件做剥注释 —— 整棵树 strip 一遍要几秒，没必要。
            .filter { (_, raw) -> raw.contains("Screen") }
            .map { (file, raw) -> file.relativeTo(repoRoot).path to KotlinCommentStripper.strip(raw) }
            .toList()
    }

    private val definedAll: Set<String> by lazy {
        projectSources.flatMap { RouteSourceParser.definedScreens(it.second) }.toSet()
    }

    private val registeredAll: Set<String> by lazy {
        projectSources.flatMap { RouteSourceParser.registeredScreens(it.second) }.toSet()
    }

    // ---- 核心不变式 ----

    @Test
    fun `必须至少找到一个 entryProvider 注册块`() {
        // 这条破了后面所有断言都没有意义（找不到块会表现为「谁都没注册」，
        // 那是一种会大声报错的失败，但把原因说清楚比让人猜要省时间）。
        val blocks = RouteSourceParser.entryProviderBodies(strippedRouteSource)
        assertTrue("RouteActivity 里找不到 entryProvider { } 块，解析前提已失效", blocks.isNotEmpty())
        assertTrue(
            "entryProvider 块规模异常缩水（解析到 ${blocks.joinToString("").length} 字符）",
            blocks.joinToString("").length > 2000,
        )
    }

    @Test
    fun `entryProvider块内解析结果必须自洽`() {
        // 第五轮审查指出：只设 >2000 的下限，证明不了块被完整取到。
        // 这里不写死具体数字（那会变成每加一个页面都要改测试的固定税，版本号那条刚坑过我们），
        // 改成三条互相印证的自洽关系。
        val hits = RouteSourceParser.entryProviderHits(strippedRouteSource)
        // 上一版这里写的是「topLevel.size == registeredScreens(...).size」，两边取自同一批
        // hits、同一个 filter，永远相等 —— 一条恒真断言，正是我前面批评过的毛病（第六轮
        // 两路审查都点了它）。换成真正独立的交叉验证：用与深度机制无关的全文正则再数一遍，
        // 两套算法结果必须一致。花括号配平若提前结束、漏掉尾部注册，这里就会露出来。
        val textScan = Regex("""entry<Screen\.(\w+)>""")
            .findAll(strippedRouteSource).map { it.groupValues[1] }.toSet()
        assertEquals(
            "深度机制解析出的注册，必须与全文正则扫到的注册一致；" +
                "不一致说明块配平提前结束（漏了尾部注册），或者路由表里出现了块外 entry（死代码）",
            textScan,
            RouteSourceParser.registeredScreens(strippedRouteSource),
        )
        assertTrue(
            "真实路由表里不该有写在控制流里的注册；出现了必须看见，不能静默：" +
                hits.filter { it.depth != 1 }.map { it.name },
            hits.filter { it.depth != 1 }.isEmpty(),
        )
        assertTrue(
            "块内 entry 总数异常缩水（解析到 ${hits.size} 条，历史上不低于 50）—— " +
                "花括号配平可能没取到完整块",
            hits.size >= 50,
        )
    }

    @Test
    fun `每个定义出来的 Screen 都必须注册`() {
        val unregistered = definedAll - registeredAll
        assertTrue(
            "这些 Screen 定义了却没在 entryProvider 里注册 —— 合并上游时丢注册的典型形态，" +
                "点进去会抛 Unknown screen 直接崩：\n  " + unregistered.joinToString("\n  "),
            unregistered.isEmpty(),
        )
    }

    @Test
    fun `注册表里没有指向不存在 Screen 的条目`() {
        val ghosts = registeredAll - definedAll
        assertTrue(
            "注册了却没找到定义：${ghosts}",
            ghosts.isEmpty(),
        )
    }

    @Test
    fun `定义集合与注册集合必须完全相等且规模不倒退`() {
        // 数量下限是防「解析器漏扫导致集合变小」这一类假绿：漏扫时集合仍然相等，
        // 但规模会掉。第四轮审查点名的就是这个组合风险，所以精确相等 + 规模下限一起用。
        assertTrue(
            "Screen 定义数 ${definedAll.size} 异常偏少（历史上不低于 50）—— 解析器可能漏扫了",
            definedAll.size >= 50,
        )
        assertTrue(
            "注册数 ${registeredAll.size} 异常偏少（历史上不低于 50）—— 解析器可能漏扫了",
            registeredAll.size >= 50,
        )
        assertEquals(definedAll, registeredAll)
    }

    // ---- 本轮崩溃的具体修复 ----

    @Test
    fun `MCP 详情页注册必须存在（本轮崩溃的具体修复）`() {
        // 用「实际注册集合」判断，不用文本 contains —— 文本断言会被注释和字符串骗过去。
        assertTrue(
            "设置 → MCP → 点服务器详情 要求 SettingMcpDetail 已在 entryProvider 块里注册",
            RouteSourceParser.registeredScreens(strippedRouteSource).contains("SettingMcpDetail"),
        )
        val blocks = RouteSourceParser.entryProviderBodies(strippedRouteSource).joinToString("\n")
        assertTrue(
            "注册必须真的把 serverId 传给页面，不能是个空壳",
            blocks.contains("SettingMcpDetailPage(id = Uuid.parse(key.serverId))"),
        )
        assertTrue(
            "页面函数的 import 不能丢",
            routeSourceText.contains(
                "import me.rerere.rikkahub.ui.pages.setting.SettingMcpDetailPage",
            ),
        )
    }

    @Test
    fun `赞助页不许被顺手加回来（用户明确要求删除，为开源做准备）`() {
        val source = strippedRouteSource
        assertFalse(
            "SettingDonate 是有意删除的二改，不是丢失；后续合并官方时不能把它补回来",
            Regex("""entry<Screen\.SettingDonate>""").containsMatchIn(
                RouteSourceParser.entryProviderBodies(source).joinToString("\n"),
            ),
        )
        assertFalse(
            "连 Screen 定义也不该回来",
            Regex("""\b(?:data class|data object|object|class)\s+SettingDonate\s""")
                .containsMatchIn(source),
        )
    }

    // ---- 元测试：验证门禁自己不会骗人 ----

    @Test
    fun `元测试-注释与字符串里的注册写法都不算数`() {
        val sample = """
            fun x() {
                // entry<Screen.InLineComment> { }
                /* 块注释里的 entry<Screen.InBlockComment> 跨
                   多行也一样 */
                val s = "entry<Screen.InString>"
                entryProvider {
                    entry<Screen.RealOne> { }
                }
            }
        """.trimIndent()
        val stripped = KotlinCommentStripper.strip(sample)
        assertEquals(
            "只有 entryProvider 块里的真代码算注册",
            setOf("RealOne"), RouteSourceParser.registeredScreens(stripped),
        )
    }

    @Test
    fun `元测试-块外与不可达分支里的注册都不算数`() {
        // 第五轮审查抓的两条，上一版这条断言我自己写松了（写成 Live 或 InUnreachableBranch
        // 命中其一即可），那等于把漏洞写进测试里默认下来。现在收紧成精确集合。
        val sample = """
            fun deadHelper() {
                entry<Screen.InDeadCode> { }
            }

            fun real() {
                entryProvider {
                    entry<Screen.Live> { }
                    if (false) {
                        entry<Screen.InUnreachableBranch> { }
                    }
                }
            }
        """.trimIndent()
        val stripped = KotlinCommentStripper.strip(sample)
        assertEquals(
            "只有直接列在 entryProvider 块顶层的那条算注册",
            setOf("Live"),
            RouteSourceParser.registeredScreens(stripped),
        )
        assertEquals(
            "深层 entry 必须被单独报出来，不能静默忽略 —— 静默是最坏的结果",
            listOf("InUnreachableBranch"),
            RouteSourceParser.deepEntriesInBlocks(stripped).map { it.name },
        )
    }

    @Test
    fun `元测试-泛型与多父类型这类合法声明不能漏扫`() {
        // 上一版漏两类：`class X<T> : Screen`（p 停在 '<' 上）与 `class X : Marker, Screen`
        // （只看冒号后第一项）。漏扫会让定义集合变小，集合相等反而通过 —— 典型假绿。
        val sample = """
            sealed interface Screen : NavKey {
                class PlainOne(val a: String) : Screen
                data class Two(val b: List<String> = emptyList()) : Screen
                data object Three: Screen
                object Four : Screen
                class Generic<T>(val x: T) : Screen
                class Child : Base(), Screen
                class Hidden private constructor(val a: String) : Screen
                class NotAScreen : Marker
            }
        """.trimIndent()
        assertEquals(
            "七种合法声明都要抓到，且不相关的父类型不能误抓",
            setOf("PlainOne", "Two", "Three", "Four", "Generic", "Child", "Hidden"),
            RouteSourceParser.definedScreens(KotlinCommentStripper.strip(sample)),
        )
    }

    @Test
    fun `元测试-普通的Screen引用不该被误判成定义或注册`() {
        // 反向保护：全项目扫描如果把这些形态误当成定义，门禁会假红并逼人改正常代码。
        val sample = """
            fun use(screen: Screen) {
                val isScreen = screen is Screen
                val k = Screen::class
                val list: List<Screen> = emptyList()
                val name = "class Fake : Screen"
            }
        """.trimIndent()
        val stripped = KotlinCommentStripper.strip(sample)
        assertEquals("不该抓到任何定义", emptySet<String>(), RouteSourceParser.definedScreens(stripped))
        assertEquals("不该抓到任何注册", emptySet<String>(), RouteSourceParser.registeredScreens(stripped))
    }

    @Test
    fun `元测试-嵌套括号的构造参数必须能解析出完整定义集合`() {
        val sample = """
            sealed interface Screen : NavKey {
                data class Chat(
                    val id: String,
                    val files: List<String> = emptyList(),
                    val nested: Map<String, List<Int>> = mapOf("a" to listOf(1, 2)),
                ) : Screen
                data object Simple : Screen
            }
        """.trimIndent()
        assertEquals(
            "带 emptyList() / mapOf(... listOf(...)) 嵌套括号的定义不能漏（上一版正则在这里就截断）",
            setOf("Chat", "Simple"),
            RouteSourceParser.definedScreens(KotlinCommentStripper.strip(sample)),
        )
    }

    @Test
    fun `元测试-三引号raw字符串里的反斜杠不是转义符`() {
        // Kotlin raw string 不处理转义：反斜杠紧邻结束三引号时，
        // 若按转义规则跳字符，就会错过结束符、把后面的真源码一起吞掉。
        val sample = "val t = \"\"\"a\\\"\"\"\nentryProvider {\n  entry<Screen.AfterRaw> { }\n}"
        val stripped = KotlinCommentStripper.strip(sample)
        assertTrue(
            "raw string 后面的 entryProvider 块必须还在",
            RouteSourceParser.registeredScreens(stripped).contains("AfterRaw"),
        )
        assertEquals(
            "吞掉内容时不能吞换行，否则行号漂、报告指错位置",
            sample.lines().size, stripped.lines().size,
        )
    }

    @Test
    fun `元测试-三引号字符串里的注册写法不算数`() {
        val sample = "a = \"\"\"\nentry<Screen.InRaw>\nmore\n\"\"\"\nentryProvider {\n entry<Screen.RealTwo> { }\n}"
        val stripped = KotlinCommentStripper.strip(sample)
        assertEquals(setOf("RealTwo"), RouteSourceParser.registeredScreens(stripped))
        assertEquals(
            "抹字符串内容时不能把换行一起抹掉",
            sample.lines().size, stripped.lines().size,
        )
    }

    @Test
    fun `元测试-字符串里的双斜杠不能被当成注释起点`() {
        val sample = """
            val url = "https://example.com/a"
            entryProvider {
                entry<Screen.AfterUrl> { }
            }
        """.trimIndent()
        val registered = RouteSourceParser.registeredScreens(KotlinCommentStripper.strip(sample))
        assertTrue(
            "URL 里的 // 若被当成行注释，后面的真代码会被整段吞掉，注册就查不到了",
            registered.contains("AfterUrl"),
        )
    }

    @Test
    fun `元测试-剥注释必须保持行号，便于报告定位`() {
        val sample = "line1\n// comment\nline3 /* block\nspanning */ line4\nline5"
        val stripped = KotlinCommentStripper.strip(sample)
        assertEquals(5, sample.lines().size)
        assertEquals("行数不能因为剥注释而改变", 5, stripped.lines().size)
    }

    @Test
    fun `元测试-真实源码里的MCP注册确实来自entryProvider块`() {
        // 把「合成源码能骗不过」与「真实文件当前状态」连起来：
        // 只验合成样例是不够的，还得证明真实文件在同一个解析器下结论正确。
        val real = strippedRouteSource
        assertTrue(
            "真实 RouteActivity 必须能解析出 entryProvider 块",
            RouteSourceParser.entryProviderBodies(real).isNotEmpty(),
        )
        assertTrue(
            "SettingMcpDetail 必须在块内注册（不是只存在于注释或字符串里）",
            RouteSourceParser.registeredScreens(real).contains("SettingMcpDetail"),
        )
    }
}

/**
 * 从 Kotlin 源码里取「Screen 子类型定义」与「entryProvider 实际注册项」。
 *
 * 输入必须是**已剥注释并抹掉字符串内容**的源码，否则注释与字面量都能冒充代码。
 */
/** 块内一条 entry 及其相对深度（深度 1 = 直接列在 entryProvider 块顶层）。 */
private data class EntryHit(val name: String, val depth: Int)

private object RouteSourceParser {

    /** 只在 entryProvider 块内匹配；块外的 entry 是死代码，不构成注册。 */

    /** 块内一条 entry 及其相对深度。 */
    private val REGISTERED = Regex("""entry<Screen\.(\w+)>""")


    /** `: Screen` 之外还要认 `: Marker, Screen`、`: Base(), Screen` 这类多父类型写法。 */
    private val COLON_HEAD = Regex("""\s*:\s*""")
    private val SCREEN_IN_LIST = Regex("""(?:^|,)\s*Screen\b""")

    /**
     * 声明头：data class / data object / object / class 四种都算。
     * `class` 单独列出来是因为 sealed interface 的实现允许写成普通 class（审查抓到的漏扫）。
     */
    private val DEFINITION_HEAD = Regex("""\b(?:data class|data object|object|class)\s+(\w+)""")

    /**
     * 名字与构造参数之间可能夹着 `private constructor`、`internal constructor` 这类修饰。
     * 不跳过的话游标停在 "private" 上，既不是 '<' 也不是 '('，整个定义被判定为「不是 Screen
     * 子类型」而漏掉 —— 漏扫的方向是假绿（第六轮审查点名）。
     */
    // 注意 constructor 后面用 \s* 而不是 \s+：真实写法是 `private constructor(...)`，
    // constructor 紧跟左括号，要求必须有空白的话游标会停在 'c' 上，定义照样漏扫
    // （预演阶段实测漏掉过一次，别改回去）。
    private val MODIFIER = Regex("""(?:private|protected|internal|public)\s+|constructor\s*""")
    private val ENTRY_PROVIDER_HEAD = Regex("""entryProvider\s*\{""")
    private val SUPER_SCREEN = Regex("""\s*:\s*Screen\b""")

    /**
     * 找出所有 `entryProvider { ... }` 字面量块的内容（花括号配平）。
     *
     * 为什么要限定块内：全文匹配会把挪进 `if (false)` 分支、或留在没被调用的辅助函数里的
     * entry 也算成注册 —— 那是第四轮审查点名的假绿路径。
     */
    fun entryProviderBodies(src: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val m = ENTRY_PROVIDER_HEAD.find(src, from) ?: break
            val open = m.value.lastIndexOf('{').let { m.range.first + it }
            var depth = 0
            var p = open
            while (p < src.length) {
                when (src[p]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { p++; break }
                    }
                }
                p++
            }
            out += src.substring(open, minOf(p, src.length))
            from = p
        }
        return out
    }

    /**
     * 只统计**直接列在 entryProvider 块顶层**的 entry（相对深度 1）。
     *
     * 为什么要管深度：第五轮审查指出，把注册挪进 `if (false) { entry<...> }` 这种不可达分支，
     * 纯文本匹配照样算它「已注册」，而实际路由表里根本没有。真实的 Navigation3 写法里
     * entry 一定是块的第一层语句，所以「只认深度 1」正好把控制流里的假注册排除掉。
     *
     * 不静默忽略：深层 entry 会由 deepEntriesInBlocks() 单独报出来。将来真有人用循环生成
     * entry，门禁会红并说清原因，而不是悄悄放行 —— 静默是最坏的结果。
     */
    fun registeredScreens(strippedSource: String): Set<String> =
        entryProviderHits(strippedSource).filter { it.depth == 1 }.map { it.name }.toSet()

    /** 块内非顶层的 entry —— 正常应为空；不为空说明有人在控制流里写注册，需要人看一眼。 */
    fun deepEntriesInBlocks(strippedSource: String): List<EntryHit> =
        entryProviderHits(strippedSource).filter { it.depth != 1 }

    fun entryProviderHits(strippedSource: String): List<EntryHit> {
        val hits = mutableListOf<EntryHit>()
        for (block in entryProviderBodies(strippedSource)) {
            var depth = 0
            var i = 0
            while (i < block.length) {
                when (block[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                val m = REGISTERED.find(block, i)
                if (m != null && m.range.first == i) {
                    hits += EntryHit(m.groupValues[1], depth)
                    i = m.range.last + 1
                    continue
                }
                i++
            }
        }
        return hits
    }

    /**
     * 找 `class X(...) : Screen` / `data class X(...) : Screen` /
     * `data object X : Screen` / `object X : Screen`。
     *
     * 构造参数用括号配平跳过，不用 `\([^)]*\)` —— 后者遇到 `emptyList()` 这类嵌套括号会在
     * 第一个右括号处截断，把整个定义漏掉。
     * 父接口匹配用正则而不是 `startsWith(": Screen")`，好让 `:Screen`（无空格）
     * 与 `:\n    Screen`（换行缩进）这些合法写法也能被抓到。
     */
    fun definedScreens(strippedSource: String): Set<String> {
        val out = linkedSetOf<String>()
        for (m in DEFINITION_HEAD.findAll(strippedSource)) {
            var p = m.range.last + 1
            val src = strippedSource
            while (p < src.length && src[p].isWhitespace()) p++
            // 泛型参数先配平跳过：`class X<T> : Screen` 不跳的话 p 会停在 '<' 上，
            // 于是这个合法定义从集合里消失，未注册时也不会报错（第五轮审查抓到的漏扫）。
            if (p < src.length && src[p] == '<') {
                var depth = 0
                while (p < src.length) {
                    when (src[p]) {
                        '<' -> depth++
                        '>' -> {
                            depth--
                            if (depth == 0) { p++; break }
                        }
                    }
                    p++
                }
            }
            while (p < src.length && src[p].isWhitespace()) p++
            // 修饰词必须在构造参数**之前**吃掉：`class X private constructor(...) : Screen`
            // 里 constructor 紧跟左括号，若先处理括号，游标会停在 "private" 上、
            // 之后再也回不来 —— 实跑就是在这里漏掉了 Hidden（顺序错过一次，别再改回去）。
            while (true) {
                val mod = MODIFIER.matchAt(src, p) ?: break
                p = mod.range.last + 1
                while (p < src.length && src[p].isWhitespace()) p++
            }
            while (p < src.length && src[p].isWhitespace()) p++
            if (p < src.length && src[p] == '(') {
                var depth = 0
                while (p < src.length) {
                    when (src[p]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) { p++; break }
                        }
                    }
                    p++
                }
            }
            while (p < src.length && src[p].isWhitespace()) p++
            if (SUPER_SCREEN.matchAt(src, p) != null) {
                out += m.groupValues[1]
            } else if (COLON_HEAD.matchAt(src, p) != null) {
                // 多父类型：`class X : Marker, Screen`、`class X : Base(), Screen`。
                // 取冒号后到第一个 { 之前的父类型列表，在里面找独立出现的 Screen。
                val listEnd = src.indexOf('{', p).let { if (it < 0) minOf(p + 200, src.length) else it }
                // `(?:^|,)\s*Screen\b` 只认列表里独立一项的 Screen，不会把 MyScreen 之类算进来。
                if (SCREEN_IN_LIST.containsMatchIn(src.substring(p, listEnd))) {
                    out += m.groupValues[1]
                }
            }
        }
        return out
    }
}

/**
 * 剥掉 Kotlin 注释、并抹掉字符串字面量的内容；块注释支持嵌套；**保持行号不变**。
 *
 * 两件事都要做，因为注册项与类型定义只可能出现在真代码里：
 * - 不剥注释 → 把 entry 注释掉，正则照样匹配注释文字，测试绿着放过去；
 * - 不抹字符串 → 源码里一句 "entry<Screen.Fake>" 字面量（日志、提示词、测试样例都算）
 *   同样会被当成注册，这条路比注释更容易出现。
 * 抹内容时保留引号本身与其中的换行，这样行号不漂、报告里的「第几行」仍然可信。
 *
 * 为什么不用简单正则：`//` 会出现在字符串里（"https://..."），正则剥注释会把真代码一起吞；
 * Kotlin 的块注释还可以嵌套，`/*` `*/` 必须计数。
 *
 * ⚠️ 三引号 raw string 里**反斜杠不是转义符**（Kotlin raw string 不支持转义），
 * 所以那个分支里绝不能跳字符 —— 否则反斜杠紧邻结束三引号时会错过结尾，
 * 把后面的真源码一路吞到下一个三引号（第四轮审查抓到的）。
 */
private object KotlinCommentStripper {

    fun strip(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            if (blockDepth > 0) {
                when {
                    src.startsWith("/*", i) -> { blockDepth++; i += 2 }
                    src.startsWith("*/", i) -> { blockDepth--; i += 2 }
                    src[i] == '\n' -> { out.append('\n'); i++ }
                    else -> i++
                }
                continue
            }
            when {
                src.startsWith("/*", i) -> { blockDepth++; i += 2; out.append(' ') }
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                    out.append('\n')
                    // 吃掉结尾这个换行：不消费的话下一轮 else 分支会再 append 一次，
                    // 行数就漂了 —— 测试报告里的「第几行」会指错位置。
                    if (i < src.length) i++
                }
                // raw string：反斜杠按普通字符处理，不做转义跳过。
                src.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\""); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("\"\"\""); i += 3 }
                }
                src[i] == '"' -> {
                    out.append('"'); i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) i += 2 else i++
                    }
                    out.append('"')
                    if (i < src.length && src[i] == '"') i++
                }
                src[i] == '\'' -> {
                    out.append('\''); i++
                    while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) i += 2 else i++
                    }
                    out.append('\'')
                    if (i < src.length && src[i] == '\'') i++
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }
}