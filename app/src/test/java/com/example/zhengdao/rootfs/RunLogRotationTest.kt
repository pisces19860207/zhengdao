package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-13（issue #17）：`RunLog.appendRotated` 三步无锁的回归。
 *
 * 改动前的问题（台账 §B 顺序 7 / issue #17 P2-13 原文）：
 * `rootfs/RunLog.kt:233 appendRotated` 的「量长度 → readText/writeText 轮换 → appendText」
 * 三步不在同一临界区。RunLog 的调用方跨多个线程（保活 / proot 输出 / UI / 下载），
 * 并发写会两类丢日志：同时超限各写一份（后者覆盖前者）、追加与轮换交叉（append 被抹掉）。
 *
 * 本测试钉两件事：
 * ① 轮换算法本身（纯函数 [RunLog.rotateContent]）——行为不改；
 * ② 临界区真的存在（文本门禁）——防后人把 synchronized 删回去。
 */
class RunLogRotationTest {

    @Test
    fun `轮换保留后半段并带截断标记`() {
        // 1000 个 0-999 的行，max=100 ⇒ 只保留最后 50 个字符（max/2）
        val current = (0 until 1000).joinToString("\n") { "line-$it" }
        val rotated = RunLog.rotateContent(current, 100)
        assertTrue("必须有截断标记", rotated.startsWith("…（旧日志已截断）…\n"))
        val kept = rotated.removePrefix("…（旧日志已截断）…\n")
        assertEquals("保留 max/2 = 50 个字符", 50, kept.length)
        assertTrue("保留的是后半段", kept.endsWith("line-999"))
        assertFalse("前半段必须被丢掉", kept.contains("line-0\n"))
    }

    @Test
    fun `轮换后内容恰好是原内容尾部`() {
        val current = "abcdefghij"
        // max=4 ⇒ 保留 max/2 = 2 个字符
        val rotated = RunLog.rotateContent(current, 4)
        assertEquals("…（旧日志已截断）…\n" + "ij", rotated)
    }

    @Test
    fun `空内容轮换只剩标记`() {
        assertEquals("…（旧日志已截断）…\n", RunLog.rotateContent("", 100))
    }

    @Test
    fun `短内容轮换也按口径取后半段`() {
        // 纯函数不判超限（判定在 appendRotated 里用文件字节数）——短内容调用时
        // 语义就是「取后半段 + 标记」，调用方负责只在超限时调。
        val rotated = RunLog.rotateContent("短内容", 1_000_000)
        assertEquals("…（旧日志已截断）…\n短内容", rotated)
    }

    @Test
    fun `门禁_appendRotated 必须在临界区内`() {
        val src = File("src/main/java/com/example/zhengdao/rootfs/RunLog.kt").readText()
        val fn = src.substringAfter("private fun appendRotated(")
            .substringBefore("\n    }\n") // 函数体到下一个顶层方法的收尾
        assertTrue(
            "appendRotated 必须持 lock（P2-13：三步非原子，并发写互裁）",
            fn.contains("synchronized(lock)"),
        )
        assertTrue(
            "判定用便宜的 length()，不把 readText 放进每行日志的热路径",
            fn.contains("f.length() > max"),
        )
        assertTrue(
            "轮换内容走纯函数 rotateContent（单测可覆盖）",
            fn.contains("f.writeText(rotateContent(current, max))"),
        )
    }
}
