// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：org.json 官方文档、OpenCode serve 模式 SSE 事件类型。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-054：Agent 运行失败（`session.step.failed` / `session.execution.failed`）的**解析与文案**。
 *
 * 为什么值得一组单测：这两个事件在 2026-10-09 之前被 App 当成"未知 SSE 事件"记一行日志了事，
 * 真机现象是"发完消息界面永远空着、等 3 分钟一无所有"——正是最贵的静默失败形态。
 * 解析层是纯函数，必须能在**没有设备**的情况下把这些口径钉住。
 */
class RunFailureTest {

    @Test
    fun `三种失败事件都识别为失败`() {
        assertTrue(RunFailure.isFailure("session.step.failed"))
        assertTrue(RunFailure.isFailure("session.execution.failed"))
        assertTrue(RunFailure.isFailure("session.error"))
        // 正常事件绝不能被当成失败（否则用户每跑一步都看到红条）
        assertFalse(RunFailure.isFailure("session.step.started"))
        assertFalse(RunFailure.isFailure("session.execution.started"))
        assertFalse(RunFailure.isFailure("session.idle"))
        assertFalse(RunFailure.isFailure("project.updated"))
    }

    @Test
    fun `从 error_message 取原因`() {
        val raw = """{"type":"session.step.failed","error":{"message":"模型不可用（429）"}}"""
        assertEquals("模型不可用（429）", RunFailure.reasonOf("session.step.failed", raw))
    }

    @Test
    fun `error 为字符串时直接当原因`() {
        val raw = """{"type":"session.execution.failed","error":"provider rejected"}"""
        assertEquals("provider rejected", RunFailure.reasonOf("session.execution.failed", raw))
    }

    @Test
    fun `兼容 data 与 properties 两种信封`() {
        val a = """{"data":{"error":{"message":"信封里的原因"}}}"""
        assertEquals("信封里的原因", RunFailure.reasonOf("session.step.failed", a))
        val b = """{"properties":{"message":"properties 里的原因"}}"""
        assertEquals("properties 里的原因", RunFailure.reasonOf("session.step.failed", b))
        val c = """{"data":{"properties":{"detail":"两层信封"}}}"""
        assertEquals("两层信封", RunFailure.reasonOf("session.step.failed", c))
    }

    @Test
    fun `没有原因时兜底文案非空且含事件类型`() {
        // 空对象 / 只有 type / 完全无关的字段，都必须给出可读输出——空串在界面上等于没提示
        val r1 = RunFailure.reasonOf("session.step.failed", "{}")
        assertTrue(r1.contains("session.step.failed"))
        assertTrue(r1.isNotBlank())
        val r2 = RunFailure.reasonOf("session.execution.failed", """{"type":"session.execution.failed"}""")
        assertTrue(r2.contains("session.execution.failed"))
    }

    @Test
    fun `非法 JSON 不抛异常且有兜底`() {
        val r = RunFailure.reasonOf("session.step.failed", "这不是 JSON")
        assertTrue(r.isNotBlank())
        assertTrue(r.contains("session.step.failed"))
    }

    @Test
    fun `超长原因截断到 160 字加省略号`() {
        val long = "字".repeat(400)
        val raw = """{"error":{"message":"$long"}}"""
        val r = RunFailure.reasonOf("session.step.failed", raw)
        assertEquals(161, r.length) // 160 + "…"
        assertTrue(r.endsWith("…"))
    }

    // ── 未知事件日志聚合（同一处改动的另一半）────────────────────────────

    @Test
    fun `未知事件同类只在首次与第 N 次记录`() {
        val log = UnknownSseLog(every = 100)
        // 首次：记一行，且说明"后续只汇总"
        val first = log.next("project.updated")
        assertNotNull(first)
        assertTrue(first!!.contains("project.updated"))
        // 第 2..99 次：不落盘（这正是真机刷屏的那 98 行）
        for (i in 2 until 100) {
            assertNull("第 $i 次不该落盘", log.next("project.updated"))
        }
        // 第 100 次：一条汇总
        val hundredth = log.next("project.updated")
        assertNotNull(hundredth)
        assertTrue(hundredth!!.contains("100"))
        // 不同事件类型各自计数，互不影响
        assertNotNull(log.next("model.updated"))
    }

    @Test
    fun `未知事件汇总覆盖类型数与总条数`() {
        val log = UnknownSseLog(every = 100)
        assertNull("没有任何未知事件时不该有汇总行", log.summary())
        repeat(3) { log.next("a.updated") }
        repeat(2) { log.next("b.updated") }
        val s = log.summary()
        assertNotNull(s)
        assertTrue(s!!.contains("5"))  // 总条数
        assertTrue(s.contains("2"))    // 类型数
    }
}
