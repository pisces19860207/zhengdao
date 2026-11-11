// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十三批：GateBodies 切片尺子的「跨行块注释（BLOCK 态）换行回填」补钉夹具。
//
// 现场（台账 173re／587 验收记录）：验收变异用 str.replace(..., 1) 摘
// "c == '\n' -> out.append(c)" 时，这行文本在 CodeText.kt 的 BLOCK 分支（第 62 行）
// 与 RAW 分支（第 89 行）一字不差，首次命中落在 BLOCK ⇒ 摘掉后**没有用例变红**
// ⇒ 暴露同族盲区：跨行块注释的换行回填没有夹具钉着。
//
// 为什么危险：GateBodies 在 codeOnly 空间数换行定位行号，再按行切回原文。
// BLOCK 态不回填注释内换行 ⇒ code 空间比原文少 N 行（N＝注释内换行数），
// 其后所有函数切片整体前移 N 行；注释里的伪 } 漏进代码空间还会提前闭合函数体。
//
// 判据只钉可量化的三件事：①切片与真实函数体逐行等值（首行／末行／行数／整段）；
// ②code 空间行数＝原文行数；③注释内伪代码一个字不许漏进代码空间。
// 夹具纯字符串、不落盘、不建临时目录 ⇒ 不产生测试残留。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBodiesBlockCommentTest {

    /** 文件头一段跨 6 行的块注释，里面塞伪函数头／伪括号／伪引号／伪行注释／伪三引号。 */
    private fun blockHeadFixture(): String {
        val tq = "\"\"\""
        return listOf(
            "/* 头部跨行块注释：下面的伪代码不许进代码空间",
            " *   fun decoy( {          // 伪函数头、伪开括号",
            " *   \"quoted\" }          // 伪闭括号、伪引号",
            " *   // 伪行注释 }         // 伪行注释",
            " *   val fake = $tq        // 伪三引号：BLOCK 态不许切成 RAW",
            " */",
            "fun guarded(): Int {",
            "    if (ok) {",
            "        note(\"x\")",
            "    }",
            "    return 7",
            "}",
            "fun next() { }",
        ).joinToString("\n")
    }

    /** 块注释长在被保护函数体内部，注释里有伪 } 与伪函数头。 */
    private fun blockInsideFixture(): String = listOf(
        "fun one(): String {",
        "    /*",
        "     * alpha {",
        "     * beta }",
        "     * fun fake( {     // 伪函数头",
        "     */",
        "    return \"done\"",
        "}",
        "fun two() { }",
    ).joinToString("\n")

    @Test
    fun `文件头跨行块注释_切片与真实函数体逐行等值_不许前移`() {
        val src = blockHeadFixture()
        val lines = src.lines()
        // 夹具自校验：锚点行漂了，下面的判据就失效（不许拿改过的夹具骗绿）
        assertEquals("夹具：被保护函数在原文第 6 行", 6, lines.indexOfFirst { it.contains("fun guarded(") })
        assertEquals("夹具：函数体闭括号在原文第 11 行", "}", lines[11])
        assertEquals("夹具：块注释确实跨 6 行", 6, lines.indexOfFirst { it.trim() == "*/" } + 1)

        val body = GateBodies.functionBody(src, "fun guarded(")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行须是函数签名行", lines[6], got.first())
        assertEquals("切片末行须是函数体闭括号行", lines[11], got.last())
        assertEquals("切片行数＝函数体 6 行", 6, got.size)
        assertEquals("整段逐行等值（起止范围一致）", lines.subList(6, 12), got)
        assertFalse("块注释里的伪函数头不许被当成代码", body.contains("fun decoy"))
        assertFalse("块外函数不许吞进来", body.contains("fun next"))
    }

    @Test
    fun `函数体内跨行块注释_伪括号不提前闭合_末行仍是真闭括号`() {
        val src = blockInsideFixture()
        val lines = src.lines()
        assertEquals("夹具：函数体闭括号在原文第 7 行", "}", lines[7])

        val body = GateBodies.functionBody(src, "fun one()")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行", lines[0], got.first())
        assertEquals("切片末行不许被注释里的 beta } 顶歪", lines[7], got.last())
        assertEquals("切片行数＝函数体 8 行", 8, got.size)
        assertEquals("整段逐行等值", lines.subList(0, 8), got)
        assertTrue("真实 return 还在切片里", body.contains("return \"done\""))
        assertFalse("不许吞进下一个函数", body.contains("fun two"))
        assertFalse("注释里的伪函数头不许进代码空间", CodeText.codeOnly(src).contains("fun fake"))
    }

    @Test
    fun `词法_块注释内换行必须回填_code空间与原文逐行对齐`() {
        val src = blockHeadFixture()
        val code = CodeText.codeOnly(src)
        assertEquals(
            "code 空间行数＝原文行数（按行切回原文的前提）",
            src.lines().size,
            code.lines().size,
        )
        assertEquals(
            "被保护函数在 code 空间仍在第 6 行（BLOCK 换行回填的直接证据）",
            6,
            code.lines().indexOfFirst { it.contains("fun guarded(") },
        )
        assertEquals("第 6 行就是函数签名（不是注释尾巴）", "fun guarded(): Int {", code.lines()[6])
        assertFalse("注释内容不许漏进代码空间：伪函数头", code.contains("fun decoy"))
        assertFalse("注释内容不许漏进代码空间：伪引号", code.contains("quoted"))
        assertFalse("注释内容不许漏进代码空间：伪行注释", code.contains("伪行注释"))
        assertFalse("注释内容不许漏进代码空间：伪三引号赋值", code.contains("val fake"))
        assertTrue("*/ 之后 CODE 态要恢复：块外函数须在代码空间", code.contains("fun next() { }"))
    }
}
