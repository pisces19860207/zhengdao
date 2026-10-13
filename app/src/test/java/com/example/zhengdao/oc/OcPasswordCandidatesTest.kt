// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：JUnit 4、Kotlin 标准库。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C 步（2026-10-13，E-093 另一半）：候选口令的收集与挑选 —— 纯函数用例。
 *
 * 不碰 Context、不碰网络：探测这一步由 [OcManager.pickServePassword] 的参数注入，
 * 所以这些用例在任何机器上都是确定性的（真连端口的用例在 OcServePasswordParseTest 里走接缝）。
 */
class OcPasswordCandidatesTest {

    private fun cands(vararg lines: String) = OcManager.passwordCandidates(lines.asSequence())

    @Test
    fun `按新到旧收集`() {
        assertEquals(
            listOf("pw3", "pw2", "pw1"),
            cands(
                "server password pw1",
                "server listening on http://127.0.0.1:14000",
                "server password pw2",
                "server password pw3"
            )
        )
    }

    @Test
    fun `重复口令只留一次（保留它最新那次的位置）`() {
        // pw1 出现过两次：倒序后 pw1（最新那次）排在最前，distinct 保留的正是它 ⇒ pw1 优先于 pw2
        assertEquals(
            listOf("pw1", "pw2"),
            cands("server password pw1", "server password pw2", "server password pw1")
        )
    }

    @Test
    fun `封顶三个候选（每个候选要打一次探针）`() {
        assertEquals(
            listOf("p5", "p4", "p3"),
            cands(
                "server password p1",
                "server password p2",
                "server password p3",
                "server password p4",
                "server password p5"
            )
        )
    }

    @Test
    fun `忽略没有口令的行与空口令`() {
        assertEquals(
            listOf("pw1"),
            cands(
                "server listening on http://127.0.0.1:14000",
                "server password pw1",
                "server password   "
            )
        )
    }

    @Test
    fun `日志里没有口令就是空候选`() {
        assertEquals(emptyList<String>(), cands("something else\n", "server listening on http://127.0.0.1:14000"))
    }

    @Test
    fun `口令里的空格原样保留`() {
        assertEquals(listOf("a b c"), cands("server password a b c"))
    }

    @Test
    fun `挑第一个能过鉴权的候选（新到旧）`() {
        assertEquals("pw2", OcManager.pickServePassword(listOf("pw2", "pw1")) { it == "pw1" || it == "pw2" })
        assertEquals("pw1", OcManager.pickServePassword(listOf("pw2", "pw1")) { it == "pw1" })
    }

    @Test
    fun `候选都过不了就返回 null`() {
        assertNull(OcManager.pickServePassword(listOf("pw1", "pw2")) { false })
    }

    @Test
    fun `探针抛异常按不通过处理`() {
        assertNull(OcManager.pickServePassword(listOf("pw1")) { throw IllegalStateException("boom") })
    }

    @Test
    fun `没有候选时不调用探针`() {
        var called = false
        assertNull(OcManager.pickServePassword(emptyList()) { called = true; true })
        assertEquals(false, called)
    }
}
