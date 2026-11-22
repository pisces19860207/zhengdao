// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第八百一十九批 · 表层推导判据：五维齐 + 由状态推出 + 同快照同表层。
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceDeriverTest {

    private fun tier(st: SurfaceState, axis: SurfaceAxis): Int =
        st.levels.first { it.axis == axis }.tier

    @Test
    fun `判据_三层齐之表层五维齐且各带来源`() {
        val st = SurfaceDeriver.derive(PersonaFixture.snapshot())
        assertEquals(SurfaceAxis.entries.toList(), st.levels.map { it.axis })
        assertEquals(5, st.levels.size)
        assertTrue("每维都要有 source，不许只有笼统性格", st.levels.all { it.source.isNotBlank() })
    }

    @Test
    fun `判据_表层由状态推出而非性格开朗标签`() {
        // 酒意/疲惫由外部喂入信号直接决定；不是"他性格如此"。
        val s = PersonaFixture.snapshot().copy(intoxication = 2, fatigue = 3)
        val st = SurfaceDeriver.derive(s)
        assertEquals(2, tier(st, SurfaceAxis.INTOXICATION))
        assertEquals(3, tier(st, SurfaceAxis.FATIGUE))
        // 同一份快照只改酒意，其他维不动 ⇒ 证明是状态驱动而非性格标签驱动。
        val st2 = SurfaceDeriver.derive(s.copy(intoxication = 0))
        assertEquals(0, tier(st2, SurfaceAxis.INTOXICATION))
        assertEquals(tier(st, SurfaceAxis.PATIENCE), tier(st2, SurfaceAxis.PATIENCE))
    }

    @Test
    fun `判据_耐心随轮数与显性度递减且封底`() {
        val calm = SurfaceDeriver.derive(PersonaFixture.snapshot())
        val worn = SurfaceDeriver.derive(
            PersonaFixture.snapshot().copy(turns = 9, salience = 4, vetoActive = true),
        )
        assertEquals(3, tier(calm, SurfaceAxis.PATIENCE))
        assertEquals(0, tier(worn, SurfaceAxis.PATIENCE))
    }

    @Test
    fun `判据_羞耻只在S3之后起且排斥加档`() {
        val early = SurfaceDeriver.derive(PersonaFixture.snapshot().copy(dStage = 2))
        val s3 = SurfaceDeriver.derive(PersonaFixture.snapshot().copy(dStage = 3))
        val s3Reject = SurfaceDeriver.derive(
            PersonaFixture.snapshot().copy(dStage = 3, stanceLabel = "排斥"),
        )
        assertEquals(0, tier(early, SurfaceAxis.SHAME))
        assertEquals(1, tier(s3, SurfaceAxis.SHAME))
        assertEquals(2, tier(s3Reject, SurfaceAxis.SHAME))
    }

    @Test
    fun `判据_紧张随显性度升且硬否决顶格`() {
        val lv1 = SurfaceDeriver.derive(PersonaFixture.snapshot().copy(salience = 1))
        val lv4 = SurfaceDeriver.derive(PersonaFixture.snapshot().copy(salience = 4))
        val veto = SurfaceDeriver.derive(PersonaFixture.snapshot().copy(salience = 1, vetoActive = true))
        assertEquals(0, tier(lv1, SurfaceAxis.TENSION))
        assertEquals(2, tier(lv4, SurfaceAxis.TENSION))
        assertEquals(3, tier(veto, SurfaceAxis.TENSION))
    }

    @Test
    fun `判据_同快照同表层逐维一致`() {
        val s = PersonaFixture.snapshot().copy(turns = 5, salience = 3, dStage = 4, fatigue = 2)
        val a = SurfaceDeriver.derive(s)
        val b = SurfaceDeriver.derive(s)
        assertEquals(a, b)
        assertEquals(a.levels.map { it.line() }, b.levels.map { it.line() })
        assertTrue(a.levels.all { it.tier in 0..3 })
    }

    @Test
    fun `判据_非法快照被拒`() {
        val bad = listOf(
            PersonaFixture.snapshot().copy(turns = -1),
            PersonaFixture.snapshot().copy(salience = 5),
            PersonaFixture.snapshot().copy(dStage = 9),
            PersonaFixture.snapshot().copy(intoxication = 4),
        )
        assertEquals(4, bad.size)
        bad.forEach { s ->
            val threw = runCatching { SurfaceDeriver.derive(s) }.exceptionOrNull()
            assertTrue("非法快照须抛异常：$s", threw is IllegalArgumentException)
        }
    }

    private fun SurfaceLevel.line(): String = "$axis=$tier($source)"
}
