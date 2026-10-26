package me.rerere.rikkahub.v1015

import java.io.File

/**
 * v1015 守卫测试共用工具。
 *
 * 为什么要单独写一个「剥注释」的读法：v1015 体检发现，上一轮合并把整套「复制官方工作区」界面
 * 覆盖掉了，但数据层、页面控制层、52 条文案全都在 —— 只要有人在注释里提一句功能名，
 * 用裸 contains 写的守卫就会照样变绿。手册 §10 第 5 条记过同一族教训
 * （TokenSpeedDisplayGuardTest 曾「删注释才红、删功能反而绿」）。
 * 所以这里的判据一律先在**代码部分**上匹配，注释与块注释里的文字不算数。
 */
internal object V1015SourceGuard {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    fun path(relative: String): File = File(repoRoot, relative)

    fun exists(relative: String): Boolean = path(relative).exists()

    /** 读整个文件（相对仓库根）。找不到就直接判失败，不许静默跳过。 */
    fun source(relative: String): String {
        val file = path(relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    /**
     * 只保留代码，去掉 // 行注释与块注释。
     * 字符串与字符字面量内部的内容原样保留（包括 """raw""" 多行串），
     * 因此不会因为注释里出现某个标识符而误判「功能还在」。
     */
    fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        // 状态用显式小解析器走一遍：只有这样才能既剥掉注释、又不误伤字符串与 ${"模板"}。
        // v1015 二审计出的洞：初版只处理普通字符串，遇到 "${error.message ?: "导出失败"}"
        // 这种模板里再套字符串会失步 —— 失步后可能把注释当代码留着（假绿），也可能把同行的
        // 真代码抹掉（assertFalse 类判据假绿）。本仓确实有这种写法（WorkspaceDetailVM.kt:79），
        // 所以这里必须处理，不能只当理论风险。
        while (i < n) {
            val before = i
            val c = src[i]
            val d2 = if (i + 1 < n) src[i + 1] else ' '
            when {
                c == '/' && d2 == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && d2 == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') { depth--; i += 2 }
                        else if (src[i] == '/' && i + 1 < n && src[i + 1] == '*') { depth++; i += 2 }
                        else { if (src[i] == '\n') out.append('\n'); i++ }
                    }
                }
                c == '"' && d2 == '"' && i + 2 < n && src[i + 2] == '"' -> { i = copyRaw(src, i, out) }
                c == '"' -> i = copyQuoted(src, i, out)
                c == '\'' -> i = copyChar(src, i, out)
                else -> { out.append(c); i++ }
            }
            // 保险：v1015 真的踩过一次——raw 分支忘了接收返回的游标，游标不前进，
            // 一路往输出堆内容直到把测试 JVM 撑爆（OutOfMemoryError）。任何分支都必须推进游标，
            // 不推进就是解析器失步，直接明确报错，别让它变成内存炸掉连累整个测试进程。
            if (i == before) throw IllegalStateException("codeOnly 在偏移 $before 处没有推进游标：解析器失步")
        }
        return out.toString()
    }

    /** 从 `"` 起拷贝一个普通字符串字面量（含 ${"模板"} 里的嵌套字符串/字符），返回新位置。 */
    private fun copyQuoted(src: String, from: Int, out: StringBuilder): Int {
        var i = from + 1
        out.append('"')
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\\' -> { out.append(c); if (i + 1 < n) out.append(src[i + 1]); i += 2 }
                c == '$' && i + 1 < n && src[i + 1] == '{' -> { out.append("\${"); i = copyTemplate(src, i + 2, out) }
                c == '"' -> { out.append('"'); return i + 1 }
                else -> { out.append(c); i++ }
            }
        }
        return i
    }

    // 注意这条注释本身原先写过字面的双斜杠与斜杠星号 —— Kotlin 块注释可嵌套，
    // 那句把自己套娃住、没闭合，把后面整个 object 都吞进注释里，编译器只报一堆 unresolved。
    /** 从三引号起拷贝 raw 多行字符串；它内部的注释符号不算注释，原样保留。返回新位置。 */
    private fun copyRaw(src: String, from: Int, out: StringBuilder): Int {
        var i = from + 3
        out.append("\"\"\"")
        val n = src.length
        while (i < n) {
            if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                out.append("\"\"\""); return i + 3
            }
            if (src[i] == '$' && i + 1 < n && src[i + 1] == '{') { out.append("\${"); i = copyTemplate(src, i + 2, out); continue }
            out.append(src[i]); i++
        }
        return i
    }

    /** 拷贝 '${' 到配对的 '}'（深度计数，内部可再嵌字符串）。入参指向 '{' 之后的第一个字符。 */
    private fun copyTemplate(src: String, from: Int, out: StringBuilder): Int {
        var i = from
        var depth = 1
        val n = src.length
        while (i < n && depth > 0) {
            val c = src[i]
            when {
                c == '{' -> { depth++; out.append(c); i++ }
                c == '}' -> { depth--; out.append(c); i++ }
                c == '"' && i + 1 < n && src[i + 1] == '"' && i + 2 < n && src[i + 2] == '"' -> copyRaw(src, i, out).let { i = it }
                c == '"' -> { i = copyQuoted(src, i, out) }
                c == '\'' -> { i = copyChar(src, i, out) }
                c == '\\' -> { out.append(c); if (i + 1 < n) out.append(src[i + 1]); i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return i
    }

    /** 拷贝一个字符字面量（'\"' 这种不能当成字符串结束）。 */
    private fun copyChar(src: String, from: Int, out: StringBuilder): Int {
        var i = from + 1
        out.append('\'')
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\\' -> { out.append(c); if (i + 1 < n) out.append(src[i + 1]); i += 2 }
                c == '\'' -> { out.append('\''); return i + 1 }
                else -> { out.append(c); i++ }
            }
        }
        return i
    }

    /** 代码部分（已剥注释）里是否出现某段写法。 */
    fun codeHas(relative: String, needle: String): Boolean =
        codeOnly(source(relative)).contains(needle)

    /**
     * 界面文案键是否真的被代码引用（`R.string.<key>` 出现在非注释代码里）。
     * 这条是 v1015 体检的直接产物：61 条二改文案键在语言文件里躺着、没有任何代码引用，
     * 功能入口就这么静默没了。
     */
    /** XML：剥掉 <!-- --> 注释后的正文（Manifest 的注释会提到功能名，不能让它替代码作证）。 */
    fun xmlCodeOnly(src: String): String = src.replace(Regex("<!--[\\s\\S]*?-->"), "")

    /** XML 属性等值匹配；值里的引号在这里集中转义，测试里就不必写转义地狱。 */
    fun xmlHasAttr(src: String, name: String, value: String): Boolean =
        xmlCodeOnly(src).contains("$name=\"$value\"")

    /** 取某个顶层函数体的文本（从定义处到文件末尾，够用且不怕缩进）。 */
    fun funBody(relative: String, signature: String): String {
        val src = codeOnly(source(relative))
        val i = src.indexOf(signature)
        if (i < 0) throw AssertionError("$relative 里找不到 $signature —— 函数没了就是整覆盖")
        return src.substring(i)
    }


    fun stringKeyUsed(vararg files: String, key: String): Boolean = files.any { f ->
        codeOnly(source(f)).contains("R.string.$key")
    }
}
