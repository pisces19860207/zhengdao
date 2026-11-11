// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零一批：GateBodies 切片尺子的「嵌套块注释（BLOCK 深度计数）」夹具。
//
// 现场：593 报告 §七 明留一条遗留——CodeText 不处理 Kotlin 的可嵌套块注释，
// 遇到「外层开符 + 内层开符 + 内层闭符 + 外层闭符」时，旧尺子在第一个闭符就回 CODE，
// 外半段（含伪闭括号／伪函数头）漏进代码空间 ⇒ 函数体提前闭合、切片按错行号走。
// Kotlin 的块注释本来就可嵌套，本批把 BLOCK 态改成深度计数并补齐夹具。
//
// 本文件刻意不在注释里写出任何注释定界符字面量（Kotlin 里会开一个永不关闭的嵌套注释）；
// 需要写定界符时一律放进字符串字面量或 listOf 元素里。
//
// 判据只钉可量化的四件事：
//   ①单行／跨行嵌套各一，断言切片首行／末行／行数／整段 List<String> 逐行等值；
//   ②字符串与行注释里的开符不误开深度（误开就会到文件尾不归零或被吞后切片找不到）；
//   ③未闭合到文件尾必须明确抛错，且不许静默返回错误切片；
//   ④code 空间行数＝原文行数（嵌套层里的换行照样回填，占位但不出内容）。
// 夹具纯字符串、不落盘、不建临时目录 ⇒ 不产生测试残留。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBodiesNestedBlockCommentTest {

    /** 单行嵌套：一行里两层开符、两层闭符；外半段塞伪函数头与伪闭括号。 */
    private fun singleLineNestedHead(): String = listOf(
        "/* 外 /* 内 */ 外 fun decoy( } */",
        "fun guarded(): Int {",
        "    if (ok) {",
        "        note(\"x\")",
        "    }",
        "    return 7",
        "}",
        "fun next() { }",
    ).joinToString("\n")

    /** 跨行嵌套且长在被保护函数体内部：内层含换行，外半段含伪闭括号。 */
    private fun multiLineNestedInside(): String = listOf(
        "fun one(): Int {",
        "    /* 外层开始",
        "     *   /* 内层开始",
        "     *     fun decoy( {     // 伪函数头、伪开括号",
        "     *     alpha }          // 内层伪闭括号",
        "     *   内层结束 */",
        "     * 外层 beta }          // 外半段伪闭括号：首个闭符结束时会漏进代码空间",
        "     */",
        "    return 7",
        "}",
        "fun two() { }",
    ).joinToString("\n")

    @Test
    fun `单行嵌套块注释_切片与真实函数体逐行等值_外半段不许漏进代码空间`() {
        val src = singleLineNestedHead()
        val lines = src.lines()
        // 夹具自校验：锚点行漂了，下面的判据就失效（不许拿改过的夹具骗绿）
        assertEquals("夹具：被保护函数在原文第 1 行", 1, lines.indexOfFirst { it.contains("fun guarded(") })
        assertEquals("夹具：函数体闭括号在原文第 6 行", "}", lines[6])
        assertEquals("夹具：首行确有两层开符", 2, lines[0].windowed(2).count { it == "/*" })
        assertEquals("夹具：首行确有两层闭符", 2, lines[0].windowed(2).count { it == "*/" })

        val body = GateBodies.functionBody(src, "fun guarded(")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行须是函数签名行", lines[1], got.first())
        assertEquals("切片末行须是函数体闭括号行", lines[6], got.last())
        assertEquals("切片行数＝函数体 6 行", 6, got.size)
        assertEquals("整段逐行等值（起止范围一致）", lines.subList(1, 7), got)
        assertFalse("块外函数不许吞进来", body.contains("fun next"))

        val code = CodeText.codeOnly(src)
        assertEquals("code 空间行数＝原文行数", lines.size, code.lines().size)
        assertEquals(
            "被保护函数在 code 空间仍在第 1 行（嵌套注释不占多行）",
            1,
            code.lines().indexOfFirst { it.contains("fun guarded(") },
        )
        assertFalse("外半段伪函数头不许漏进代码空间", code.contains("fun decoy"))
        assertFalse("嵌套注释里的伪内容不许漏进代码空间", code.contains("内"))
        assertTrue("闭符之后 CODE 态恢复：块外函数须在代码空间", code.contains("fun next() { }"))
    }

    @Test
    fun `跨行嵌套块注释_内层换行回填_伪闭括号不提前闭合_切片逐行等值`() {
        val src = multiLineNestedInside()
        val lines = src.lines()
        assertEquals("夹具：函数体闭括号在原文第 9 行", "}", lines[9])
        assertEquals("夹具：外层开符在第 1 行", 1, lines.indexOfFirst { it.contains("/*") })
        assertEquals("夹具：内层开符在第 2 行", 2, lines.indexOfLast { it.contains("/*") })
        assertEquals("夹具：外闭符独占第 7 行", "*/", lines[7].trim())

        val body = GateBodies.functionBody(src, "fun one()")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行", lines[0], got.first())
        assertEquals("切片末行不许被外半段的 beta } 顶歪", lines[9], got.last())
        assertEquals("切片行数＝函数体 10 行", 10, got.size)
        assertEquals("整段逐行等值", lines.subList(0, 10), got)
        assertTrue("真实 return 还在切片里", body.contains("return 7"))
        assertFalse("不许吞进下一个函数", body.contains("fun two"))

        val code = CodeText.codeOnly(src)
        assertEquals("code 空间行数＝原文行数（嵌套层里的换行照样回填）", lines.size, code.lines().size)
        assertEquals(
            "真闭括号在 code 空间仍在第 9 行",
            9,
            code.lines().indexOfFirst { it.trim() == "}" },
        )
        assertFalse("内层伪函数头不许漏进代码空间", code.contains("fun decoy"))
        assertFalse("外半段伪闭括号不许漏进代码空间", code.contains("beta"))
        assertTrue("闭符之后 CODE 态恢复：块外函数须在代码空间", code.contains("fun two() { }"))
    }

    @Test
    fun `词法_嵌套层内换行必须回填_code空间与原文逐行对齐`() {
        val src = multiLineNestedInside()
        val code = CodeText.codeOnly(src)
        val lines = src.lines()
        assertEquals("code 空间行数＝原文行数", lines.size, code.lines().size)
        assertEquals("函数签名行不许前移", "fun one(): Int {", code.lines()[0])
        assertEquals("真 return 仍在原文第 8 行", 8, code.lines().indexOfFirst { it.contains("return 7") })
        assertEquals("真闭括号仍在原文第 9 行", 9, code.lines().indexOfFirst { it.trim() == "}" })
        assertFalse("注释内容一个字不许漏进代码空间", code.contains("外层开始"))
        assertFalse("内层内容一个字不许漏进代码空间", code.contains("内层开始"))
    }

    @Test
    fun `字符串里出现开符与闭符_不误开深度_切片不偏`() {
        val src = listOf(
            "fun s(): String {",
            "    val a = \"/* 不是注释 }\"",
            "    val b = \"*/ 也不是闭符\"",
            "    return a + b",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("code 空间行数＝原文行数", src.lines().size, code.lines().size)
        assertEquals("字符串整体换成空串占位", "    val a = \"\"", code.lines()[1])
        assertFalse("串内伪开符不许漏进代码空间", code.contains("不是注释"))
        assertFalse("串内伪闭符不许漏进代码空间", code.contains("也不是闭符"))
        val body = GateBodies.functionBody(src, "fun s()")
        assertNotNull("误开深度会把后面吞成注释，切片就找不到了", body)
        assertEquals("切片逐行等值", src.lines().subList(0, 5), body!!.lines())
        assertTrue("后面的真函数仍在代码空间", code.contains("fun tail() { }"))
    }

    @Test
    fun `行注释里出现开符与闭符_不误开深度_切片不偏`() {
        val src = listOf(
            "fun l(): Int { // /* 行注释里的开符，不许开深度 }",
            "    // */ 行注释里的闭符，也不许关深度",
            "    return 1",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("LINE 态换行保留 ⇒ 行数对齐", src.lines().size, code.lines().size)
        assertFalse("行注释内容不许漏进代码空间", code.contains("行注释里的开符"))
        assertFalse("行注释内容不许漏进代码空间", code.contains("行注释里的闭符"))
        assertTrue("真 return 仍在代码空间", code.contains("return 1"))
        val body = GateBodies.functionBody(src, "fun l()")
        assertNotNull("误开深度会把后面吞成注释，切片就找不到了", body)
        assertEquals("切片逐行等值", src.lines().subList(0, 4), body!!.lines())
        assertTrue("后面的真函数仍在代码空间", code.contains("fun tail() { }"))
    }

    @Test
    fun `原始串里出现开符_不误开深度_切片不偏`() {
        val tq = "\"\"\""
        val src = listOf(
            "fun r(): String {",
            "    val doc = $tq",
            "        /* 原始串里的开符不许开深度 }",
            "        */ 原始串里的闭符也不许关深度",
            "    $tq.trimIndent()",
            "    return doc",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("RAW 态换行保留 ⇒ 行数对齐", src.lines().size, code.lines().size)
        assertFalse("原始串内容不许漏进代码空间", code.contains("原始串里的开符"))
        val body = GateBodies.functionBody(src, "fun r()")
        assertNotNull("误开深度会把后面吞成注释，切片就找不到了", body)
        assertEquals("切片逐行等值", src.lines().subList(0, 7), body!!.lines())
        assertTrue("后面的真函数仍在代码空间", code.contains("fun tail() { }"))
    }

    @Test
    fun `未闭合嵌套块注释_到文件尾必须明确抛错_不许静默切错`() {
        val src = listOf(
            "fun f(): Int {",
            "    /* 外 /* 内 */ 外",
            "    return 7",
            "}",
        ).joinToString("\n")
        val ex = assertThrows(IllegalStateException::class.java) { CodeText.codeOnly(src) }
        assertTrue("报错须点名未闭合：${ex.message}", ex.message!!.contains("块注释未闭合"))
        assertTrue("报错须给出起始行：${ex.message}", ex.message!!.contains("起始行 2"))
        assertTrue("报错须给出剩余深度：${ex.message}", ex.message!!.contains("剩余深度 1"))
        // 切片入口同样不许静默返回一个按错行号切出来的片段
        assertThrows(IllegalStateException::class.java) { GateBodies.functionBody(src, "fun f(") }
    }

    @Test
    fun `单层未闭合块注释_同样明确抛错_已闭合的正例不抛`() {
        val unclosed = "fun f(): Int {\n    /* 忘了闭符\n    return 7\n}\n"
        val ex = assertThrows(IllegalStateException::class.java) { CodeText.codeOnly(unclosed) }
        assertTrue("报错须给出剩余深度 1：${ex.message}", ex.message!!.contains("剩余深度 1"))
        // 正例：闭合的嵌套不许被误判成未闭合（防「一律抛错」的假实现）
        val closed = "fun f(): Int {\n    /* 外 /* 内 */ 外 */\n    return 7\n}\n"
        val closedCode = CodeText.codeOnly(closed)
        assertEquals("闭合嵌套的 code 空间行数＝原文行数", closed.lines().size, closedCode.lines().size)
        assertFalse("闭合嵌套的内容仍不许漏进代码空间", closedCode.contains("外"))
        assertNotNull("闭合嵌套必须能正常切片", GateBodies.functionBody(closed, "fun f("))
    }

    @Test
    fun `三层嵌套_深度逐层归零_切片仍逐行等值`() {
        val src = listOf(
            "/* L1 /* L2 /* L3 */ L2尾 */ L1尾 */",
            "fun deep(): Int {",
            "    return 3",
            "}",
            "fun tail() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("code 空间行数＝原文行数", src.lines().size, code.lines().size)
        assertFalse("三层注释内容不许漏进代码空间", code.contains("L1") || code.contains("L3"))
        assertTrue("闭符之后 CODE 态恢复", code.contains("fun deep(): Int {"))
        val body = GateBodies.functionBody(src, "fun deep(")
        assertNotNull(body)
        assertEquals("切片逐行等值", src.lines().subList(1, 4), body!!.lines())
    }
}
