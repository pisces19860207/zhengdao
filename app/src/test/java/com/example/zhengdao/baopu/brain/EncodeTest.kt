// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ③：刺激档 → 电流的写死表断言。
package com.example.zhengdao.baopu.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodeTest {

    @Test
    fun `表长等于档数加一`() {
        assertEquals("档数 4 ⇒ 表长 4", 4, Encode.tableSize())
        assertEquals(Encode.MAX_TIER + 1, Encode.tableSize())
    }

    @Test
    fun `四档电流逐值写死`() {
        assertEquals("档 0 ＝ 无刺激", 0.0, Encode.currentOf(0), 0.0)
        assertEquals(1.30, Encode.currentOf(1), 0.0)
        assertEquals(1.70, Encode.currentOf(2), 0.0)
        assertEquals(2.40, Encode.currentOf(3), 0.0)
    }

    @Test
    fun `电流必须单调递增`() {
        for (tier in 1..Encode.MAX_TIER) {
            assertTrue("档 $tier 必须比上一档强", Encode.currentOf(tier) > Encode.currentOf(tier - 1))
        }
    }

    @Test
    fun `档 0 恰为零_否则静息会有自发活动`() {
        assertEquals("档 0 必须恰是 0.0（差一点点都会让静息自己放电）", 0.0, Encode.currentOf(0), 0.0)
    }

    @Test
    fun `越界不收不外推不夹取`() {
        for (bad in listOf(-1, 4, 99)) {
            val error = runCatching { Encode.currentOf(bad) }.exceptionOrNull()
            assertTrue("档 $bad 必须抛 IllegalArgumentException：$error", error is IllegalArgumentException)
        }
    }
}
