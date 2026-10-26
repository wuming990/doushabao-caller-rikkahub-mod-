package me.rerere.rikkahub.v1015

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 给「守卫的裁判」本身写测试。
 *
 * 为什么必须有这一份：v1015 的事故形状是「功能没了、注释与文案键还在」。如果判据用裸 contains
 * 读整个文件，注释里提一嘴功能名就能长期假装通过 —— 守卫看着在保护，其实永远不执行。
 * 这几条就是在证明：注释里的文字**不算数**，代码与字符串字面量里的才算数。
 */
class V1015SourceGuardTest {

    private val sample = """
        package demo
        // vm.importFrom(it, importKind) 是旧写法说明，不该被当成接线存在
        /* 块注释里写 rememberCoroutineScope 也不算 */
        fun a() {
            val s = "字符串里写 vm.exportTo(it) 要算数"
            println(s)
        }
        val b = 'c' // 行尾注释 vm.localTransfer
    """.trimIndent()

    @Test
    fun `行注释里的标识符不算代码`() {
        val code = V1015SourceGuard.codeOnly(sample)
        assertFalse("行注释没被剥掉", code.contains("旧写法说明"))
    }

    @Test
    fun `块注释里的标识符不算代码`() {
        val code = V1015SourceGuard.codeOnly(sample)
        assertFalse("块注释没被剥掉", code.contains("块注释里写"))
        assertFalse(code.contains("rememberCoroutineScope"))
    }

    @Test
    fun `字符串字面量里的内容必须保留`() {
        // 剥注释器如果把字符串里冒号后的内容当注释吞掉，界面文案的判据就会莫名变红。
        val code = V1015SourceGuard.codeOnly(sample)
        assertTrue("字符串内容被误剥", code.contains("字符串里写 vm.exportTo(it) 要算数"))
    }

    @Test
    fun `行尾注释被剥但同一行的代码留着`() {
        val code = V1015SourceGuard.codeOnly(sample)
        assertTrue(code.contains("val b = 'c'"))
        assertFalse(code.contains("行尾注释"))
    }

    @Test
    fun `模板里再套字符串不会让状态机失步`() {
        // 不构造样本（上一版我在这儿连栽两次：转义写歪反而把模板触发了，测的还是错的字符串）。
        // 直接用本仓真实文件 WorkspaceDetailVM.kt 第 79 行当样本 —— 那行是真的在模板里套了字符串：
        //     failures += "${'$'}{entry.name}：... ?: "导出失败"}"
        // 第 81 行紧跟一条中文注释，第 82 行是注释之后的真代码。失步时这两处会一起判错。
        val real = V1015SourceGuard.source(
            "app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailVM.kt"
        )
        val realCode = V1015SourceGuard.codeOnly(real)
        assertTrue("真实文件里模板嵌套字符串被误剥", realCode.contains("导出失败"))
        assertTrue("真实文件里失败分支的真代码被抹掉", realCode.contains("failures += "))
        assertFalse("真实文件第 81 行的注释没被剥掉", realCode.contains("只清理本次创建但未完整写入的文件"))
        assertTrue(
            "注释之后的真代码被抹掉（这就是状态机失步的典型表现）",
            realCode.contains("DocumentsContract.deleteDocument(resolver, it)")
        )
    }

    @Test
    fun `raw 三引号字符串内部的注释符号不算注释`() {
        // 这条样本上一版我直接用 Kotlin 字面三引号写：raw 串里再出现三引号会提前收尾，
        // 样本静默缩成一小截字符串，断言测的是根本不存在的内容（编译器不报，跑起来才红）。
        // 被测对象是喂给解析器的文本，不该受 Kotlin 字面量限制，所以这里用拼接凑出三个引号。
        val q = "\""
        val triple = q + q + q
        val s = "val prompt = " + triple + "\n// 正文标记 rememberCoroutineScope\n/* 这也不算注释 */\n" +
            triple + "\n// 这才是真注释 vm.exportTo(it)"
        val code = V1015SourceGuard.codeOnly(s)
        assertTrue("raw 字符串正文被误剥", code.contains("正文标记 rememberCoroutineScope"))
        assertTrue("raw 字符串内的斜杠星被当成块注释开始", code.contains("这也不算注释"))
        assertFalse("raw 字符串之后的真注释没被剥掉", code.contains("vm.exportTo(it)"))
    }

    @Test
    fun `块注释可以嵌套`() {
        val s = "/* 外层 /* 内层 vm.exportTo(it) */ 外层继续 */ vm.importFrom(it, importKind)"
        val code = V1015SourceGuard.codeOnly(s)
        assertFalse("嵌套块注释没剥干净", code.contains("vm.exportTo(it)"))
        assertTrue("嵌套块注释后的真代码被抹掉", code.contains("vm.importFrom(it, importKind)"))
    }

    @Test
    fun `字符字面量里的引号不当成字符串结束`() {
        // 样本要表达的是：val q = '"'  // 这条注释要消失 vm.localTransfer
        // 用拼接写，避免一个反斜杠就把整条断言测到错的东西上。
        val s = "val q = '" + '"' + "' // 这条注释要消失 vm.localTransfer"
        val code = V1015SourceGuard.codeOnly(s)
        assertFalse("字符字面量后的行注释没被剥掉", code.contains("vm.localTransfer"))
        assertTrue("字符字面量与它后面的代码被误抹", code.contains("val q = "))
    }

    @Test
    fun `XML 注释里的注册声明不算存在`() {
        val xml = """
            <!-- android:name=".service.OfficialTransferService" 这是说明文字 -->
            <service android:name=".service.ChatGenerationForegroundService" />
        """.trimIndent()
        assertFalse(V1015SourceGuard.xmlCodeOnly(xml).contains("OfficialTransferService"))
        assertTrue(V1015SourceGuard.xmlHasAttr(xml, "android:name", ".service.ChatGenerationForegroundService"))
    }
}
