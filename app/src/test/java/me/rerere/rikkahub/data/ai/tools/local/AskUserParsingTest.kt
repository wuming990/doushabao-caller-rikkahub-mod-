package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v297：ask_user 参数解析的真单测。
 *
 * 起因是真机事故：主模型把 options 写成了对象数组（每个选项 `{"text": "..."}`），
 * 旧实现「整批 runCatching + 对每个选项硬取 jsonPrimitive」直接抛异常，
 * 结果**整张卡片所有题目一起消失**，只剩一个省略号，而提交键照样能点，
 * 模型只收到 `{"answers":{}}` —— 用户答不了话、双方都以为是对方的问题。
 *
 * 这些用例全部是真跑函数（不是读源码做字符串断言），因为解析逻辑已经抽成顶层纯函数。
 */
class AskUserParsingTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `正常形态 纯字符串选项与 selection_type`() {
        val questions = parseAskUserQuestions(
            json(
                """{"questions":[{"id":"a","question":"选哪个","options":["甲","乙"],"selection_type":"single"}]}"""
            )
        )
        assertEquals(1, questions.size)
        assertEquals("a", questions[0].id)
        assertEquals("选哪个", questions[0].question)
        assertEquals(listOf("甲", "乙"), questions[0].options)
        assertEquals(SELECTION_SINGLE, questions[0].selectionType)
    }

    @Test
    fun `真机事故形态 选项写成对象数组时整张卡片不许消失`() {
        // 这就是把用户堵死的那次调用：选项是对象，且每个对象里还塞了 selection_type
        val questions = parseAskUserQuestions(
            json(
                """{"questions":[
                  {"id":"cost","question":"要不要算钱","options":[{"text":"先只统计token","selection_type":"single"},{"text":"加单价表","selection_type":"single"}]},
                  {"id":"where","question":"显示在哪","options":[{"text":"顶部常驻"},{"text":"藏菜单里"}]},
                  {"id":"bg","question":"后台消耗算不算","options":[{"text":"算"},{"text":"不算"}]}
                ]}"""
            )
        )
        assertEquals("三题必须全在（旧实现这里返回 0 题）", 3, questions.size)
        assertEquals(listOf("先只统计token", "加单价表"), questions[0].options)
        assertEquals(listOf("顶部常驻", "藏菜单里"), questions[1].options)
        assertEquals("要不要算钱", questions[0].question)
    }

    @Test
    fun `单个选项形状坏只丢这一个 不牵连整题`() {
        val questions = parseAskUserQuestions(
            json(
                """{"questions":[{"id":"a","question":"题","options":["好","坏",{"no_text_field":1},"也好"]}]}"""
            )
        )
        assertEquals(1, questions.size)
        // 只有「取不到文字的对象」被丢掉，两个正常字符串选项必须留着
        assertEquals(listOf("好", "坏", "也好"), questions[0].options)
    }

    @Test
    fun `选项对象按常见字段名取文字`() {
        val questions = parseAskUserQuestions(
            json(
                """{"questions":[{"id":"a","question":"题","options":[{"label":"用 label"},{"value":"用 value"},{"title":"用 title"},{"weird":"取不到"}]}]}"""
            )
        )
        assertEquals(listOf("用 label", "用 value", "用 title"), questions[0].options)
    }

    @Test
    fun `questions 被整个塞成 JSON 字符串时也能读`() {
        // 原始字符串里的 \" 就是 JSON 需要的转义引号（不要再写成 \\"，那会变成两个反斜杠）
        val questions = parseAskUserQuestions(
            json("""{"questions":"[{\"id\":\"a\",\"question\":\"字符串里的题\",\"options\":[\"甲\"]}]"}""")
        )
        assertEquals(1, questions.size)
        assertEquals("字符串里的题", questions[0].question)
        assertEquals(listOf("甲"), questions[0].options)
    }

    @Test
    fun `一题坏掉不影响其他题`() {
        val questions = parseAskUserQuestions(
            json("""{"questions":[{"id":"a","question":"好题"},{"id":"b"},{"question":"没id但有题"},{"id":"c","options":["只有选项没有题干"]}]}""")
        )
        // 第 2、4 题没有题干：第 2 题连选项都没有 → 丢；第 4 题有选项 → 留并给占位题干
        assertEquals(3, questions.size)
        assertEquals("好题", questions[0].question)
        assertEquals("没id但有题", questions[1].question)
        // 占位题干按「原始下标」算（这一题在参数数组里是第 4 个），不是按过滤后的序号
        assertEquals("第 4 个问题", questions[2].question)
    }

    @Test
    fun `缺 id 时用序号兜底 不再让所有答案挤进同一个空键`() {
        val questions = parseAskUserQuestions(
            json("""{"questions":[{"question":"甲题","options":["1"]},{"question":"乙题","options":["2"]}]}""")
        )
        assertEquals(listOf("q0", "q1"), questions.map { it.id })
    }

    @Test
    fun `选择类型容错 认不出来一律按最宽松的自由文本`() {
        fun typeOf(raw: String?): String {
            val tail = if (raw != null) ",\"selection_type\":\"$raw\"" else ""
            return parseAskUserQuestions(
                json("""{"questions":[{"id":"a","question":"题","options":["甲"]$tail}]}""")
            ).first().selectionType
        }
        assertEquals(SELECTION_MULTI, typeOf("multi"))
        assertEquals(SELECTION_MULTI, typeOf("MULTIPLE"))
        assertEquals(SELECTION_SINGLE, typeOf("single"))
        assertEquals(SELECTION_SINGLE, typeOf("radio"))
        assertEquals(SELECTION_TEXT, typeOf("text"))
        // 模型根本没写这个字段时，给最宽松的档位：既能点选项也能自己打字
        assertEquals(SELECTION_TEXT, typeOf(null))
        assertEquals(SELECTION_TEXT, typeOf("whatever"))
    }

    @Test
    fun `字符串数组形态也能读`() {
        val questions = parseAskUserQuestions(json("""{"questions":["第一个问题","第二个问题"]}"""))
        assertEquals(listOf("第一个问题", "第二个问题"), questions.map { it.question })
    }

    @Test
    fun `参数形状完全不符时判定为读不出来 供界面显示自由文本框`() {
        assertTrue(askUserArgumentsUnreadable(json("""{"questions":[{"no_question_field":1}]}""")))
        assertTrue(askUserArgumentsUnreadable(json("""{"wrong_key":[]}""")))
        // 空参数不算「读不出来」（那是模型压根没给内容，界面不必贴原始参数）
        assertFalse(askUserArgumentsUnreadable(json("{}")))
        assertFalse(askUserArgumentsUnreadable(null))
        // 正常参数不算
        assertFalse(askUserArgumentsUnreadable(json("""{"questions":[{"id":"a","question":"题"}]}""")))
    }

    @Test
    fun `空列表时解析结果为空而不是抛异常`() {
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(json("""{"questions":[]}""")))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(json("""{"questions":null}""")))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(null))
    }

    // ---- v299：商汤渠道 + kimi-k3 真机实遇「题干显示正常、选项一个都看不到」后补的形状 ----

    @Test
    fun `选项被塞成 JSON 字符串时也能读出来`() {
        // 网关/模型把数组序列化成字符串：options 的值是 "[\"甲\",\"乙\"]" 这个字符串
        val questions = parseAskUserQuestions(
            json("""{"questions":[{"id":"a","question":"题","options":"[\"甲\",\"乙\"]"}]}""")
        )
        assertEquals(listOf("甲", "乙"), questions[0].options)
    }

    @Test
    fun `选项写成用分隔符隔开的一整串时也能读出来`() {
        fun opts(raw: String) = parseAskUserQuestions(
            json("""{"questions":[{"id":"a","question":"题","options":"$raw"}]}""")
        ).first().options
        assertEquals(listOf("甲", "乙", "丙"), opts("甲|乙|丙"))
        assertEquals(listOf("甲", "乙"), opts("甲、乙"))
        // 换行必须写成「反斜杠+n」进 JSON 文本（Kotlin 源里写 \\n），否则真换行会直接
        // 插进 JSON 字符串里变成非法 JSON；编号前缀由生产代码剥掉（v299 审查位抓到）
        assertEquals(listOf("甲", "乙"), opts("1. 甲\\n2. 乙"))
        assertEquals(listOf("甲", "乙"), opts("1) 甲\\n2) 乙"))
        // 序号后面没有空白时不许剥 —— 否则「3.5 倍」会被削成「5 倍」
        assertEquals(listOf("3.5 倍以内", "5 倍以上"), opts("3.5 倍以内|5 倍以上"))
        // 已知取舍（审查位要求钉住，避免以后无声变化）：单个选项里自带逗号时会被切碎。
        // 之所以仍然收逗号：模型把选项写成整串时，逗号分隔是最常见的形态，
        // 放弃逗号会让「可以,不可以」变成一整条选项，代价更大。
        assertEquals(listOf("是的", "我需要"), opts("是的，我需要"))
        // 整串本来就只有一个选项时，不能切碎
        assertEquals(listOf("只有一个选项"), opts("只有一个选项"))
    }

    @Test
    fun `选项字段被换名成 choices 时也能读出来`() {
        val questions = parseAskUserQuestions(
            json("""{"questions":[{"id":"a","question":"题","choices":["甲","乙"]}]}""")
        )
        assertEquals(listOf("甲", "乙"), questions[0].options)
    }

    @Test
    fun `参数本身就是数组时也能读出来`() {
        val questions = parseAskUserQuestions(
            json("""[{"id":"a","question":"题","options":["甲"]}]""")
        )
        assertEquals(1, questions.size)
        assertEquals(listOf("甲"), questions[0].options)
    }

    @Test
    fun `题干换名成 title 时也能读出来`() {
        val questions = parseAskUserQuestions(
            json("""{"questions":[{"id":"a","title":"用 title 当题干","options":["甲"]}]}""")
        )
        assertEquals("用 title 当题干", questions[0].question)
    }
}
