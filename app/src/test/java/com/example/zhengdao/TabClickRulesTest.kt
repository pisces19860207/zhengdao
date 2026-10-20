// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TabClickRules] 的判据用例（第七十六批 · §B 80 MainActivity 纯逻辑抽取）。
 *
 * 窗口是**严格小于**：正好 300ms 不算双击（与改前 `t - last < 300L` 一致）。
 * 时间由用例注入，不依赖 `SystemClock`，所以能钉住毫秒边界而不是"差不多"。
 */
class TabClickRulesTest {

    @Test
    fun `双击_同一Tab窗口内命中`() {
        assertTrue(
            TabClickRules.isDoubleTap(
                currentTab = TabIndex.BAOPU,
                targetTab = TabIndex.BAOPU,
                nowMs = 1000L,
                lastClickAtMs = 800L,
            )
        )
    }

    @Test
    fun `双击_窗口是严格小于正好300毫秒不算`() {
        assertFalse(
            TabClickRules.isDoubleTap(
                currentTab = TabIndex.AGENTS,
                targetTab = TabIndex.AGENTS,
                nowMs = 1300L,
                lastClickAtMs = 1000L,
            )
        )
        // 299ms 仍然算
        assertTrue(
            TabClickRules.isDoubleTap(
                currentTab = TabIndex.AGENTS,
                targetTab = TabIndex.AGENTS,
                nowMs = 1299L,
                lastClickAtMs = 1000L,
            )
        )
    }

    @Test
    fun `双击_不同Tab只切换不回顶`() {
        // 哪怕 1ms 内连点两个不同 Tab，也只是切换，不能触发"回顶"
        assertFalse(
            TabClickRules.isDoubleTap(
                currentTab = TabIndex.BAOPU,
                targetTab = TabIndex.AGENTS,
                nowMs = 1000L,
                lastClickAtMs = 999L,
            )
        )
    }

    @Test
    fun `双击_首次点击不算`() {
        // 冷启动后 lastClickAtMs = 0，now 是开机以来的毫秒数，必然远大于窗口
        assertFalse(
            TabClickRules.isDoubleTap(
                currentTab = TabIndex.BROWSER,
                targetTab = TabIndex.BROWSER,
                nowMs = 5_000_000L,
                lastClickAtMs = 0L,
            )
        )
    }

    @Test
    fun `时间戳_双击清零单击取本次时刻`() {
        assertEquals(0L, TabClickRules.nextStampMs(isDoubleTap = true, nowMs = 1234L))
        assertEquals(1234L, TabClickRules.nextStampMs(isDoubleTap = false, nowMs = 1234L))
    }

    @Test
    fun `窗口常量是300毫秒`() {
        // 改这个值等于改手感，必须有派工单；钉住数字让"顺手调大"先撞红
        assertEquals(300L, TabClickRules.DOUBLE_TAP_WINDOW_MS)
    }
}
