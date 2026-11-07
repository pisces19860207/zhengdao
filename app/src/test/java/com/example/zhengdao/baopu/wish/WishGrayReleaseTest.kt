// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第五百一十批 · 判据⑥：灰度达标自动转正、不达标自动回滚（各一条）。
package com.example.zhengdao.baopu.wish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishGrayReleaseTest {

    @Test
    fun 判据6a_达标自动转正() {
        val record = GrayRecord("w1", WishProduct.SKILL, uses = 3)
        val result = WishGrayRelease.evaluate(record, selfCheckPassed = true)
        assertEquals(GrayState.PROMOTED, result.state)
    }

    @Test
    fun 判据6b_不达标自动回滚() {
        val record = GrayRecord("w2", WishProduct.SKILL, uses = 1)
        val result = WishGrayRelease.evaluate(record, selfCheckPassed = false)
        assertEquals(GrayState.ROLLED_BACK, result.state)
        assertTrue(result.rollbackReason!!.contains("自检不达标"))
    }

    @Test
    fun 未达阈值留在灰度() {
        val record = GrayRecord("w3", WishProduct.CARD, uses = 1)
        val result = WishGrayRelease.evaluate(record, selfCheckPassed = true)
        assertEquals(GrayState.GRAY, result.state)
    }

    @Test
    fun 红线命中回滚() {
        val record = GrayRecord("w4", WishProduct.CONTENT, uses = 5)
        val result = WishGrayRelease.evaluate(record, selfCheckPassed = true, redLineHit = true)
        assertEquals(GrayState.ROLLED_BACK, result.state)
    }

    @Test
    fun 真文件被改回滚() {
        val record = GrayRecord("w5", WishProduct.NOVEL, uses = 5)
        val result = WishGrayRelease.evaluate(record, selfCheckPassed = true, realFileChanged = true)
        assertEquals(GrayState.ROLLED_BACK, result.state)
    }

    @Test
    fun 记一次使用加一() {
        val record = GrayRecord("w6", WishProduct.SKILL, uses = 0)
        assertEquals(1, WishGrayRelease.recordUse(record).uses)
    }

    @Test
    fun 阈值与SkillStore同口径() {
        assertEquals(3, WishGrayRelease.PROMOTE_THRESHOLD)
    }

    @Test
    fun 回滚条件判定() {
        assertTrue(WishGrayRelease.shouldRollback(false, false, false))
        assertTrue(WishGrayRelease.shouldRollback(true, true, false))
        assertTrue(WishGrayRelease.shouldRollback(true, false, true))
        assertFalse(WishGrayRelease.shouldRollback(true, false, false))
    }
}