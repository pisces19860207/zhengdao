// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十七批：GateBodies 切片尺子的「Kotlin 原始字符串」复现／回归夹具。
//
// 现场（仓库内已有旁证：LowRiskEvoGateTest.kt:47-50）：557 的 GateBodies 在
// CodeText.codeOnly 空间做括号配对，再用 code 空间数出的行号切回原文。
// CodeText 的 RAW 态当时**不回填串内换行**，其余五个态都回填 ⇒ 含跨行 """ 的文件，
// code 空间比原文少 N 行，sliceByLines 于是拿偏小的起止行去原文切，切片整体前移 N 行。
// 568 发现这个盲区后只能把 SkillTools 的顺序断言改钉全文；本单把尺子修回来。
//
// 判据只钉可量化的两件事，不用「片段 contains 某字面量」当主判据（那类断言在夹具上恒真）：
//   ①切片与真实函数体**逐行等值**（首行、末行、行数、整段 List<String> 全比）；
//   ②code 空间行数与原文行数相等，原始串内容一个字不许漏进代码空间。
// 夹具不落盘、不建临时目录 ⇒ 不产生测试残留。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBodiesRawStringTest {

    /** 模拟 SkillTools.kt：文件头部一段跨行原始串，串里塞伪函数头／伪括号／伪引号。 */
    private fun rawHeadFixture(): String {
        val tq = "\"\"\""
        return """
            // 文件头：DESC / inputSchema 那类跨行原始串
            const val PROMPT = $tq
                fun decoy( {   // 伪函数头、伪开括号
                "quoted" }     // 伪闭括号、伪引号
            $tq.trimIndent()

            fun guarded(): Int {
                if (ok) {
                    note("x")
                }
                return 7
            }
            fun next() { }
        """.trimIndent()
    }

    /** 原始串长在被保护函数体内部。 */
    private fun rawInsideFixture(): String {
        val tq = "\"\"\""
        return """
            fun one(): String {
                val doc = $tq
                    alpha {
                    beta }
                $tq.trimIndent()
                return "done"
            }
            fun two() { }
        """.trimIndent()
    }

    @Test
    fun `文件头跨行原始串_切片与真实函数体逐行等值_不许前移`() {
        val src = rawHeadFixture()
        val lines = src.lines()
        // 夹具自校验：锚点行漂了，下面的判据就失效（不许拿改过的夹具骗绿）
        assertEquals("夹具：被保护函数在原文第 6 行", 6, lines.indexOfFirst { it.contains("fun guarded(") })
        assertEquals("夹具：函数体闭括号在原文第 11 行", "}", lines[11])

        val body = GateBodies.functionBody(src, "fun guarded(")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行须是函数签名行", lines[6], got.first())
        assertEquals("切片末行须是函数体闭括号行", lines[11], got.last())
        assertEquals("切片行数＝函数体 6 行", 6, got.size)
        assertEquals("整段逐行等值（起止范围一致）", lines.subList(6, 12), got)
        assertFalse("原始串里的伪函数头不许被当成代码", body.contains("fun decoy"))
        assertFalse("块外函数不许吞进来", body.contains("fun next"))
    }

    @Test
    fun `函数体内跨行原始串_伪括号不提前闭合_末行仍是真闭括号`() {
        val src = rawInsideFixture()
        val lines = src.lines()
        assertEquals("夹具：函数体闭括号在原文第 6 行", "}", lines[6])

        val body = GateBodies.functionBody(src, "fun one()")
        assertNotNull("尺子没找到函数体", body)
        val got = body!!.lines()
        assertEquals("切片首行", lines[0], got.first())
        assertEquals("切片末行不许被串里的 beta } 顶歪", lines[6], got.last())
        assertEquals("切片行数＝函数体 7 行", 7, got.size)
        assertEquals("整段逐行等值", lines.subList(0, 7), got)
        assertTrue("真实 return 还在切片里", body.contains("return \"done\""))
        assertFalse("不许吞进下一个函数", body.contains("fun two"))
    }

    @Test
    fun `词法_原始串内换行必须回填_code空间与原文逐行对齐`() {
        val src = rawHeadFixture()
        val code = CodeText.codeOnly(src)
        assertEquals(
            "code 空间行数＝原文行数（按行切回原文的前提）",
            src.lines().size,
            code.lines().size,
        )
        assertEquals(
            "被保护函数在 code 空间的行号不许变",
            6,
            code.lines().indexOfFirst { it.contains("fun guarded(") },
        )
        assertFalse("原始串内容不许漏进代码空间：伪函数头", code.contains("fun decoy"))
        assertFalse("原始串内容不许漏进代码空间：伪引号", code.contains("quoted"))
        assertFalse("原始串内容不许漏进代码空间：伪注释", code.contains("伪闭括号"))
    }

    @Test
    fun `词法_三引号整段是原始串_串内单双引号与伪括号都不切态`() {
        val tq = "\"\"\""
        val src = listOf(
            "fun f() {",
            "    val s = $tq",
            "        \"quoted\" \"\" } fun fake( {",
            "        // 伪注释 /* 伪块注释 */",
            "    $tq.trimIndent()",
            "    return 1",
            "}",
            "fun g() { }",
        ).joinToString("\n")
        val code = CodeText.codeOnly(src)
        assertEquals("行数对齐", src.lines().size, code.lines().size)
        assertFalse("单个／连续两个引号不许把 RAW 态切回 CODE：伪函数头", code.contains("fun fake"))
        assertFalse("单个／连续两个引号不许把 RAW 态切回 CODE：伪引号", code.contains("quoted"))
        assertFalse("原始串里的注释符号不许漏进代码空间", code.contains("伪注释"))
        val body = GateBodies.functionBody(src, "fun f()")
        assertNotNull(body)
        assertEquals("切片逐行等值", src.lines().subList(0, 7), body!!.lines())
    }
}
