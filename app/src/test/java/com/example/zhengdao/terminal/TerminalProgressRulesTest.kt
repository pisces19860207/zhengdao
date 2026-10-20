// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [downloadPercentBucket] 的验收（第七十四批 · 结构拆分第三阶段）。
 *
 * 三件会被用户看见的事：
 * 1. 进度只按 20% 一档刷（192MB 首装最多 6 次更新，不刷屏）；
 * 2. 没走完不显示 100%（整数向下取整，99% 仍报 80 档）；
 * 3. 总长度异常（镜像报错、done > total）时不出现负数或 120% 这种值。
 *
 * 返回类型是 **Long**（与搬移前的 `percent: Long` 一致，调用方还要跟 `lastPercent: Long`
 * 比去重），断言一律带 `L`——别写成 Int 字面量，那会变成装箱比较而假红。
 */
class TerminalProgressRulesTest {

    @Test
    fun `0 到 19 百分比都算 0 档`() {
        assertEquals(0L, downloadPercentBucket(0, 100))
        assertEquals(0L, downloadPercentBucket(19, 100))
        assertEquals("浮点前的向下取整：19.9% 还不是 20 档", 0L, downloadPercentBucket(199, 1000))
    }

    @Test
    fun `走满一档才进位`() {
        assertEquals(20L, downloadPercentBucket(20, 100))
        assertEquals(20L, downloadPercentBucket(39, 100))
        assertEquals(40L, downloadPercentBucket(40, 100))
    }

    @Test
    fun `没下完不报 100`() {
        assertEquals("99% 仍属 80 档 —— 用户不能看到「100% 但还没完」", 80L, downloadPercentBucket(99, 100))
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
        assertEquals("一半 ⇒ 50% 落到 40 档", 40L, downloadPercentBucket(total / 2, total))
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
