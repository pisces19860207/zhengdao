package com.termux.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `TerminalView.rowForY` 纵向换算的单测（台账 §B-补-49 🟡4）。
 *
 * 缺陷链路：长按路径（getColumnAndRow）用 mFontLineSpacingAndAscent 换算，
 * 选词手柄路径（getCursorY）却硬编码 -40（上游常量，2026-10-11 行高 1.15×
 * 改动没同步）——两者差约 0.5–0.7 个行盒 ⇒ 同一 y 判成不同行。修复后两处
 * 收敛到同一纯函数，本测试钉住其数学定义。JVM 可跑。
 */
class TerminalRowMappingTest {

    @Test
    fun `常规换算`() {
        // ls=40、LSA=30、y=150 ⇒ (150-30)/40 = 3
        assertEquals(3, TerminalView.rowForY(150f, 30, 40, 0))
    }

    @Test
    fun `真实 LSA 场景（ascent 为负 ⇒ LSA 小于行距）`() {
        // ls=40、ascent=-12 ⇒ LSA=28；y=100 ⇒ (100-28)/40 = 1.8 ⇒ 截断 1
        // （旧硬编码 -40 会算成 (100-40)/40 = 1——此例相同，但 y=60 时两者分歧：
        //  LSA 式 (60-28)/40=0，硬编码式 (60-40)/40=0；y=50 时 LSA 式 0、硬编码 0。
        //  分歧点在 LSA 与 40 的差累积到跨行时，见下一例的 topRow 组合。）
        assertEquals(1, TerminalView.rowForY(100f, 28, 40, 0))
    }

    @Test
    fun `topRow 回滚时行号整体偏移`() {
        assertEquals(13, TerminalView.rowForY(150f, 30, 40, 10))
    }

    @Test
    fun `y 小于 LSA 时为负行 由调用方按语义处理`() {
        // (10-50)/10 = -4.0 ⇒ -4（与上游一致：本函数不夹取）
        assertEquals(-4, TerminalView.rowForY(10f, 50, 10, 0))
    }
}
