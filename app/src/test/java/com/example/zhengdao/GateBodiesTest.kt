// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十七批：[GateBodies] 这把尺自己的牙（哨兵自己的 bug 不算树的事——尺先钉住，
// 各处门禁才谈得上用它）。合成 Kotlin 片段，不读生产源码。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBodiesTest {

    @Test
    fun `块体_切到配对闭括号_不吃下一个函数`() {
        val src = """
            class A {
                private fun one(): Int {
                    if (true) { return 1 }
                    return 0
                }
                private fun two() { }
            }
        """.trimIndent()
        val body = GateBodies.functionBody(src, "fun one()")!!
        assertTrue(body.contains("return 0"))
        assertFalse("下一个函数不许吞进来", body.contains("fun two"))
    }

    @Test
    fun `中间函数改可见性_切片不变_547那一格`() {
        val before = """
            private fun view(): Int { return 1 }
            private fun messages() = "x"
            private suspend fun scan() { }
        """.trimIndent()
        val after = before.replace("private fun messages", "internal fun messages")
        assertEquals(
            GateBodies.functionBody(before, "fun view()"),
            GateBodies.functionBody(after, "fun view()"),
        )
        assertFalse(GateBodies.functionBody(after, "fun view()")!!.contains("fun scan"))
    }

    @Test
    fun `表达式体_带尾巴lambda_取到深度零换行`() {
        val src = """
            private suspend fun scan(): Int = withContext(io) {
                val x = compute(1, 2)
                x
            }
            private fun next() { }
        """.trimIndent()
        val body = GateBodies.functionBody(src, "fun scan()")!!
        assertTrue(body.contains("compute(1, 2)"))
        assertFalse(body.contains("fun next"))
    }

    @Test
    fun `字符串与注释里的花括号顶不歪配对`() {
        val tq = "\"\"\""
        val src = """
            fun one(): String {
                val s = "一个 { 在字符串里"
                val t = ${tq} raw { 也是 ${tq}
                // 注释里 } 也不算
                /* 块注释 { } 也不算 */
                return s + t
            }
            fun two() { }
        """.trimIndent()
        val body = GateBodies.functionBody(src, "fun one()")!!
        assertTrue(body.contains("return s + t"))
        assertFalse(body.contains("fun two"))
    }

    @Test
    fun `参数默认值里的等号不当表达式体`() {
        val src = """
            fun foo(x: Int = 3, y: String = "a"): Int {
                return x
            }
            fun bar() { }
        """.trimIndent()
        val body = GateBodies.functionBody(src, "fun foo(")!!
        assertTrue(body.contains("return x"))
        assertFalse(body.contains("fun bar"))
    }

    @Test
    fun `调用实参段_跨行配平_字符串内容看得见`() {
        val src = """
            fun caller() {
                note(
                    gate = GATE_A,
                    redline = "policy",
                )
                note(gate = GATE_B, redline = "raw")
            }
        """.trimIndent()
        val calls = GateBodies.callArgs(src, "note(")
        assertEquals(2, calls.size)
        assertTrue(calls[0].contains("GATE_A"))
        assertTrue("按行切回原文 ⇒ 字符串内容还在", calls[0].contains("\"policy\""))
        assertTrue(calls[1].contains("\"raw\""))
    }

    @Test
    fun `语句块体_条件改局部名照样切_不吃块外`() {
        val src = """
            fun f() {
                if (verdict.rejected) {
                    note("x {")
                    return 1
                }
                other()
            }
        """.trimIndent()
        val anchor = Regex("""if\s*\(\s*\w+\.rejected\s*\)""")
        val body = GateBodies.blockBody(src, anchor)!!
        assertTrue(body.contains("note("))
        assertFalse("块外不许吞进来", body.contains("other()"))
        val renamed = src.replace("verdict", "v2")
        assertEquals(body.replace("verdict", "v2"), GateBodies.blockBody(renamed, anchor))
    }

    @Test
    fun `找不到返回null_不硬切`() {
        assertNull(GateBodies.functionBody("class A", "fun missing("))
        assertEquals(emptyList<String>(), GateBodies.callArgs("class A", "missing("))
        assertNull(GateBodies.blockBody("class A", Regex("""if\s*\(\s*\w+\.rejected\s*\)""")))
    }
}