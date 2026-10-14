// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetWatch] 的验收（2026-10-14，用户点名「网络不通横幅有用」之后改活体指示）。
 *
 * 测的是"报警与恢复"这两个用户能感知的行为，不是实现：
 * 1. 第一次探测就定状态（不让用户等两轮）；
 * 2. 单次抖动**不**报警（横幅不许闪）；
 * 3. 连续失败才报警；
 * 4. 一恢复立刻撤销（用户抱怨过"网络好了横幅还挂着"）；
 * 5. 同状态重复探测**不**返回"翻转"（否则 RunLog 会被同一句刷屏）；
 * 6. [NetWatch.resetFailures] 只清计数、不动判定。
 */
class NetWatchTest {

    @Test
    fun `第一次探测就定状态`() {
        val down = NetWatch()
        assertTrue("首探不通应当立刻翻转（否则用户要等 40 秒）", down.observe(false))
        assertFalse(down.online)

        val up = NetWatch()
        assertTrue(up.observe(true))
        assertTrue(up.online)
    }

    @Test
    fun `单次抖动不报警`() {
        val w = NetWatch()
        assertTrue(w.observe(true))
        assertFalse("已判通时一次失败不该翻转", w.observe(false))
        assertTrue(w.online)
    }

    @Test
    fun `连续两次失败才报警`() {
        val w = NetWatch()
        assertTrue(w.observe(true))
        assertFalse(w.observe(false))
        assertTrue("第一次失败仍视为连通", w.online)
        assertTrue("第二次失败才翻转", w.observe(false))
        assertFalse(w.online)
    }

    @Test
    fun `一恢复立刻撤销报警`() {
        val w = NetWatch()
        w.observe(true)
        w.observe(false)
        w.observe(false)
        assertFalse(w.online)
        assertTrue("成功一次就翻回连通", w.observe(true))
        assertTrue(w.online)
    }

    @Test
    fun `成功后累计失败清零`() {
        val w = NetWatch()
        w.observe(true)
        w.observe(false)          // 累计 1
        w.observe(true)           // 清零
        assertFalse("清零后再来一次失败不该直接报警", w.observe(false))
        assertTrue(w.online)
        assertTrue("第二次失败才翻", w.observe(false))
    }

    @Test
    fun `同状态重复探测不返回翻转`() {
        val w = NetWatch()
        assertTrue(w.observe(true))     // 首探：定状态
        assertFalse(w.observe(true))    // 同状态：不该写日志
        assertFalse(w.observe(true))
        assertEquals(3, w.observations)
    }

    @Test
    fun `resetFailures 只清计数不改判定`() {
        val w = NetWatch()
        w.observe(true)
        w.observe(false)
        w.observe(false)
        assertFalse(w.online)
        w.resetFailures()
        assertFalse("清计数不该偷偷判定为连通", w.online)
        assertFalse("清完计数后第一次失败仍不够阈值", w.observe(false))
    }

    @Test
    fun `阈值可配`() {
        val strict = NetWatch(failThreshold = 1)
        strict.observe(true)
        assertTrue("阈值 1 时一次失败立刻报警", strict.observe(false))
        assertFalse(strict.online)
    }
}
