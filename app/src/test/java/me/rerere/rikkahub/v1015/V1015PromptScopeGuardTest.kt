package me.rerere.rikkahub.v1015

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1015 门禁：提示词「指定目标模型」防走丢。
 *
 * 合并官方 3.1.0 时 PromptPage.kt 被官方版本逐字节整覆盖，9 条 prompt_page_* 文案键全成了孤儿；
 * 更麻烦的是官方 3.1 还把 `providers` 这条传参链从整页删干净了 —— 也就是说只把界面搬回来、
 * 不把 providers 一路传到位，界面照样是死的。所以这里既查签名也查调用点。
 */
class V1015PromptScopeGuardTest {

    private val page = "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt"

    @Test
    fun `两个页签都拿到模型清单`() {
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(page))
        val n = Regex("providers = settings\\.providers").findAll(code).count()
        assertTrue("ModeInjectionTab 与 LorebookTab 的调用点必须各传一次 providers，现在只找到 $n 处", n >= 2)
    }

    @Test
    fun `目标模型选择器组件还在`() {
        listOf(
            "private fun InjectionMultiModelScopePicker(",
            "private fun InjectionModelScopePicker(",
        ).forEach { sig ->
            assertTrue("缺选择器 $sig", V1015SourceGuard.codeHas(page, sig))
        }
        val code = V1015SourceGuard.codeOnly(V1015SourceGuard.source(page))
        val hits = Regex("providers").findAll(code).count()
        assertTrue("providers 相关写法只剩 $hits 处（整覆盖会退回个位数）", hits >= 12)
    }

    @Test
    fun `每一层卡片与编辑面板的签名都带着 providers`() {
        listOf(
            "private fun ModeInjectionTab(",
            "private fun ModeInjectionCard(",
            "private fun ModeInjectionEditSheet(",
            "private fun LorebookTab(",
            "private fun LorebookEditSheet(",
            "private fun RegexInjectionEntryCard(",
            "private fun RegexInjectionEditDialog(",
        ).forEach { sig ->
            val body = V1015SourceGuard.funBody(page, sig)
            // 只看参数表：从函数签名到第一个 ") {" 之前
            val head = body.substringBefore(") {")
            assertTrue("$sig 的参数表缺 providers —— 传参链在这一层断了", head.contains("providers: List<ProviderSetting>"))
        }
    }

    @Test
    fun `关键文案键不是孤儿`() {
        listOf(
            "prompt_page_target_models",
            "prompt_page_target_model",
            "prompt_page_all_enabled_models",
            "prompt_page_select_models",
        ).forEach { key ->
            assertTrue("文案键 $key 已无人引用", V1015SourceGuard.stringKeyUsed(page, key = key))
        }
    }

    @Test
    fun `目标模型标签必须待在卡片内部而不是甩在外面`() {
        // v322 快照里这块本身就是坏的：标签掉到卡片大括号之后（Kotlin 不看缩进，编译照过，
        // 但会被画到卡片下方）。搬代码时照抄就会把这个坏形状一起搬回来，所以这里钉住顺序。
        // 取「从这张卡的签名起，到下一个函数定义止」——substringBefore 会撞上签名自己、截成空串。
        val src = V1015SourceGuard.codeOnly(V1015SourceGuard.source(page))
        val start = src.indexOf("private fun ModeInjectionCard(")
        assertTrue("找不到 ModeInjectionCard 定义", start >= 0)
        val rest = src.substring(start)
        val nextFn = Regex("\\n(private|internal) fun ").find(rest, 1)
        val card = if (nextFn != null) rest.substring(0, nextFn.range.first) else rest
        val tag = card.indexOf("targetModelName,")
        val menu = card.indexOf("ItemActionMenu(")
        assertTrue("卡片里找不到目标模型标签", tag > 0)
        assertTrue("标签又跑到操作菜单之后了（= 甩在卡片外）", menu > 0 && tag in 1 until menu)
    }
}
