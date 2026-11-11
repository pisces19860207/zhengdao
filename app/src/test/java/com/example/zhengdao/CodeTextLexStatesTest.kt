// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十三批：CodeText.codeOnly 六态逐态夹具。
// 六态＝CODE / STRING(STR) / RAW / BLOCK / LINE / CHAR，完整表（逐态「是否回填换行」）
// 见交付报告「六态表」一节；本文件负责「每态至少一条夹具，缺的补齐」。
// 587 只给 RAW 补了换行回填并配了 4 例；此前 LINE / CHAR / STRING / CODE 没有逐态点名的夹具，
// BLOCK 只有单行注释的顺带覆盖（单行注释内 0 个换行 ⇒ 摘掉换行回填也不会红）。
//
// 每条夹具都钉两件事：
//   ①该态的内容不许漏进代码空间（伪括号／伪函数头／转义符）；
//   ②code 空间行数与原文对齐（换行回填是 GateBodies 按行切回原文的前提）。
// 夹具纯字符串、不落盘、不建临时目录 ⇒ 不产生测试残留。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class CodeTextLexStatesTest {

    @Test
    fun `词法_CODE态_纯代码逐行保留_切片起止行不偏`() {
        val src = listOf(
            "fun plain(): Int {",
            "    return 1",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("CODE 态行数对齐", src.lines().size, code.lines().size)
        assertEquals("CODE 态原样保留签名", "fun plain(): Int {", code.lines()[0])
        val body = GateBodies.functionBody(src, "fun plain()")
        assertNotNull(body)
        assertEquals("切片逐行等值", src.lines().subList(0, 3), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }

    @Test
    fun `词法_STRING态_串内伪括号不进代码空间_转义引号不提前闭合`() {
        val src = """
            fun s(): String {
                val a = "{ } fun fake( \"quoted\""
                return a
            }
            fun tail() { }
        """.trimIndent()
        val code = CodeText.codeOnly(src)
        assertEquals("STRING 态行数对齐", src.lines().size, code.lines().size)
        assertFalse("串内伪函数头不许漏进代码空间", code.contains("fun fake"))
        assertFalse("串内伪引号内容不许漏进代码空间", code.contains("quoted"))
        assertEquals("字符串整体换成空串占位（转义引号不许提前闭合）", "val a = \"\"", code.lines()[1].trim())
        val body = GateBodies.functionBody(src, "fun s()")
        assertNotNull(body)
        assertEquals("串内 { } 不许顶歪配对", src.lines().subList(0, 4), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }

    @Test
    fun `词法_RAW态_跨行原始串换行回填_串内伪括号不漏`() {
        val tq = "\"\"\""
        val src = listOf(
            "fun r(): String {",
            "    val doc = $tq",
            "        alpha {",
            "        beta }",
            "    $tq.trimIndent()",
            "    return doc",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("RAW 态行数对齐（587 补的那一行）", src.lines().size, code.lines().size)
        assertFalse("串内伪开括号不许漏进代码空间", code.contains("alpha"))
        assertFalse("串内伪闭括号不许漏进代码空间", code.contains("beta"))
        val body = GateBodies.functionBody(src, "fun r()")
        assertNotNull(body)
        assertEquals("切片逐行等值", src.lines().subList(0, 7), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }

    @Test
    fun `词法_BLOCK态_跨行块注释换行回填_伪括号与伪函数头不漏`() {
        val src = listOf(
            "/* 跨行块注释",
            " * fun fake( {",
            " * beta }",
            " */",
            "fun b(): Int {",
            "    return 2",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("BLOCK 态行数对齐（本单补钉）", src.lines().size, code.lines().size)
        assertEquals("函数签名仍在原文第 4 行", 4, code.lines().indexOfFirst { it.contains("fun b(") })
        assertFalse("注释内伪函数头不许漏进代码空间", code.contains("fun fake"))
        assertFalse("注释内伪闭括号不许漏进代码空间", code.contains("beta"))
        val body = GateBodies.functionBody(src, "fun b()")
        assertNotNull(body)
        assertEquals("切片逐行等值", src.lines().subList(4, 7), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }

    @Test
    fun `词法_LINE态_行注释到行尾闭合_换行保留_伪括号不漏`() {
        val src = listOf(
            "fun l(): Int { // 伪闭括号 } 与伪函数头 fun fake(",
            "    // 第二行注释 { }",
            "    return 1",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("LINE 态换行保留 ⇒ 行数对齐", src.lines().size, code.lines().size)
        assertFalse("行注释内伪函数头不许漏进代码空间", code.contains("fun fake"))
        assertFalse("行注释内伪闭括号不许漏进代码空间", code.contains("伪闭括号"))
        val body = GateBodies.functionBody(src, "fun l()")
        assertNotNull(body)
        assertEquals("切片逐行等值（注释内 } 不提前闭合）", src.lines().subList(0, 4), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }

    @Test
    fun `词法_CHAR态_字符字面量里的花括号不顶歪配对_转义单引号不提前闭合`() {
        val src = """
            fun c(): Char {
                val open = '{'
                val close = '}'
                val quote = '\''
                return open
            }
            fun tail() { }
        """.trimIndent()
        val code = CodeText.codeOnly(src)
        assertEquals("CHAR 态行数对齐", src.lines().size, code.lines().size)
        assertEquals("字符字面量换成单引号占位：开括号行", "val open = '", code.lines()[1].trim())
        assertEquals("转义单引号不提前闭合：仍是单引号占位", "val quote = '", code.lines()[3].trim())
        val body = GateBodies.functionBody(src, "fun c()")
        assertNotNull(body)
        assertEquals("字符字面量里的 { } 不许顶歪配对", src.lines().subList(0, 6), body!!.lines())
        assertFalse("不吃下一个函数", body.contains("fun tail"))
    }
}
