// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [downloadPercentBucket] 的验收（第七十四批 · 结构拆分第三阶段；2026-10-22 改 1% 一档）。
 *
 * 三件会被用户看见的事：
 * 1. 进度**1% 一档**（用户原话：「百分比应该是个位数跳动的话，会心里更有数」）——
 *    慢网下每推进约 1% 就能看到一个数字变化，最多 100 次更新；
 * 2. 没走完不显示 100%（整数向下取整，99% 报 99）；
 * 3. 总长度异常（镜像报错、done > total）时不出现负数或 120% 这种值。
 *
 * 返回类型是 **Long**（调用方还要跟 `lastPercent: Long` 比去重），
 * 断言一律带 `L`——别写成 Int 字面量，那会变成装箱比较而假红。
 */
class TerminalProgressRulesTest {

    @Test
    fun `个位数就跳_1% 一档`() {
        assertEquals(0L, downloadPercentBucket(0, 100))
        assertEquals(1L, downloadPercentBucket(1, 100))
        assertEquals(7L, downloadPercentBucket(7, 100))
        assertEquals("向下取整：19.9% 报 19", 19L, downloadPercentBucket(199, 1000))
    }

    @Test
    fun `每一档都进位`() {
        assertEquals(20L, downloadPercentBucket(20, 100))
        assertEquals(39L, downloadPercentBucket(39, 100))
        assertEquals(40L, downloadPercentBucket(40, 100))
    }

    @Test
    fun `没下完不报 100`() {
        assertEquals("99% 就报 99 —— 用户不能看到「100% 但还没完」", 99L, downloadPercentBucket(99, 100))
        assertEquals(100L, downloadPercentBucket(100, 100))
    }

    @Test
    fun `总长异常时夹在 0 到 100`() {
        assertEquals("镜像返回错误的总长度也不能出 120%", 100L, downloadPercentBucket(150, 100))
        assertEquals("done 比 total 大同样夹住", 100L, downloadPercentBucket(999, 100))
    }

    @Test
    fun `真实首装量级`() {
        val total = 192L * 1024 * 1024
        assertEquals(0L, downloadPercentBucket(0, total))
        assertEquals("一半 ⇒ 50%", 50L, downloadPercentBucket(total / 2, total))
        assertEquals(100L, downloadPercentBucket(total, total))
    }

    // ── 源码门禁 ──────────────────────────────────────────────────────────

    @Test
    fun `下载回调只调纯函数旧表达式不得残留`() {
        val src = File("src/main/java/com/example/zhengdao/TerminalActivity.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/TerminalActivity.kt") }
            .readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

        assertTrue("下载回调必须调用 downloadPercentBucket", src.contains("downloadPercentBucket(done, total)"))
        assertFalse(
            "旧的分档算式不得留在 Activity（两份实现必然漂移）",
            src.contains("coerceIn(0, 100) / 20"),
        )
    }
}
