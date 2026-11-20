// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十四批 · 行为判据：
//   ① 分泌双相封顶 ② 节律频率有上限+停归零 ③ 饱和方向反转 ④ 恢复必然（时间夹具）
//   ⑤ 六维齐+顺滑引用湿档 ⑥ 长期适应最多降一档/慢可逆 ⑦ 确定性。
// 本测试零写盘（纯函数 + 只读源码），水位不受影响。
package com.example.zhengdao.baopu.state.stimulus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StimulusLoopTest {

    @Test
    fun `判据一_分泌先升后缓且封顶`() {
        val seq = (0..SecretionCurve.MAX_ACCUMULATION).map { SecretionCurve.at(it) }
        // 先升：前段严格递增到峰值
        assertTrue("分泌应先升：$seq", seq[0] < seq[1] && seq[1] < seq[2] && seq[2] < seq[3])
        // 后缓：越过峰值不再上升
        assertTrue("越过峰值后须回落或持平：$seq", seq[4] <= seq[3] && seq[5] <= seq[4])
        // 封顶：整表峰值=上限，且处处不越顶
        assertEquals(SecretionCurve.CAP, SecretionCurve.peak())
        assertTrue("不得有档超上限：$seq", seq.all { it <= SecretionCurve.CAP })
        // 负例把关：超大输入也不得越上限（单调无限上升 ⇒ 红；允许回落到趋缓档）
        assertTrue("超大输入不得越上限", SecretionCurve.at(10_000) <= SecretionCurve.CAP)
    }

    @Test
    fun `判据二_频率单调不减且连续N拍后停最高档`() {
        val seq = (0..Rhythm.CAP_BEATS + 50).map { Rhythm.frequencyAt(it) }
        for (i in 1 until seq.size) assertTrue("频率不该回落：$seq", seq[i] >= seq[i - 1])
        assertEquals(FrequencyGrade.MAX, Rhythm.frequencyAt(Rhythm.CAP_BEATS))
        assertEquals(FrequencyGrade.MAX, Rhythm.frequencyAt(10_000))
        assertTrue("最高档及以上恒为顶档", seq.drop(Rhythm.CAP_BEATS).all { it == FrequencyGrade.MAX })
    }

    @Test
    fun `判据二_回撤与深度封顶且停后全归零`() {
        val r = Rhythm.of(999, 99, 99)
        assertEquals(DepthGrade.MAX, r.depth)
        assertEquals(WithdrawGrade.MAX, r.withdraw)
        assertEquals(FrequencyGrade.MAX, r.frequency)
        val stopped = Rhythm.afterStop()
        assertEquals(0, stopped.frequency)
        assertEquals(0, stopped.depth)
        assertEquals(0, stopped.withdraw)
    }

    @Test
    fun `判据三_饱和方向反转_阈值后刺激升敏感反降`() {
        // 未饱和区：刺激↑ 敏感单调不减
        val pre = (0..SensitivityCurve.SATURATION_THRESHOLD).map { SensitivityCurve.at(it) }
        for (i in 1 until pre.size) assertTrue("未饱和区不该降：$pre", pre[i] >= pre[i - 1])
        // 饱和区（阈值+1 起）：刺激↑ 敏感严格下降——方向反转的核心断言
        val post = (SensitivityCurve.SATURATION_THRESHOLD..SensitivityCurve.MAX_INTENSITY)
            .map { SensitivityCurve.at(it) }
        for (i in 1 until post.size) assertTrue("饱和区必须反转下降：$post", post[i] < post[i - 1])
        // 全曲线峰值恰在阈值档
        val peakIntensity = (0..SensitivityCurve.MAX_INTENSITY).maxByOrNull { SensitivityCurve.at(it) }!!
        assertEquals(SensitivityCurve.SATURATION_THRESHOLD, peakIntensity)
    }

    @Test
    fun `判据三_饱和不适档随越阈上升且未饱和为零`() {
        for (i in 0..SensitivityCurve.SATURATION_THRESHOLD) {
            assertEquals("未饱和不适须为 0", 0, SensitivityCurve.discomfortAt(i))
        }
        assertTrue(SensitivityCurve.discomfortAt(4) > 0)
        assertTrue("越阈越不适", SensitivityCurve.discomfortAt(5) >= SensitivityCurve.discomfortAt(4))
        assertFalse(SensitivityCurve.isSaturated(SensitivityCurve.SATURATION_THRESHOLD))
        assertTrue(SensitivityCurve.isSaturated(SensitivityCurve.SATURATION_THRESHOLD + 1))
    }

    @Test
    fun `判据四_恢复超过最慢档必然回到目标_时间夹具`() {
        for (level in 0..2) {
            for (reduced in 0..SensitivityCurve.MAX_LEVEL) {
                val target = SensitivityCurve.MAX_LEVEL
                assertEquals(
                    "level=$level reduced=$reduced 超时必须回升到目标",
                    target,
                    Recovery.at(reduced, target, Recovery.LONGEST_BEATS + 10, level),
                )
            }
        }
    }

    @Test
    fun `判据四_恢复随拍数单调不降且封顶`() {
        for (level in 0..2) {
            val seq = (0..Recovery.LONGEST_BEATS + 2).map { Recovery.at(1, 3, it, level) }
            for (i in 1 until seq.size) assertTrue("恢复不该回落：$seq", seq[i] >= seq[i - 1])
            assertTrue("恢复不得超目标", seq.all { it <= 3 })
        }
    }

    @Test
    fun `判据五_六维齐且各封顶_顺滑只读湿档源`() {
        // 六维字段齐（逐个构造读取，避免依赖反射字段数）
        val r = Sensation.of(99, 99, 99, 99, 99)
        assertEquals(6, listOf(r.resistance, r.smoothness, r.temperature, r.envelopment, r.pressure, r.dullPain).size)
        assertEquals(SensationDimension.MAX, r.resistance)
        assertEquals(SensationDimension.MAX, r.temperature)
        assertEquals(SensationDimension.MAX, r.envelopment)
        assertEquals(SensationDimension.MAX, r.pressure)
        assertEquals(SensationDimension.MAX, r.dullPain)
        // 未接源恒 0 档
        assertEquals(0, r.smoothness)
        // 注入湿档 2 ⇒ 顺滑=2（顺滑由湿档源决定，不是自造、不是从别的维度推出）
        val wet = object : WetnessSource { override fun wetnessGrade(): Int = 2 }
        val r2 = Sensation.of(1, 1, 1, 1, 1, wet)
        assertEquals(2, r2.smoothness)
        // 湿档越界也被钳位
        val wet99 = object : WetnessSource { override fun wetnessGrade(): Int = 99 }
        assertEquals(SensationDimension.MAX, Sensation.of(0, 0, 0, 0, 0, wet99).smoothness)
    }

    @Test
    fun `判据六_短时适应每N拍降一档且封底`() {
        assertEquals(3, Adaptation.after(3, 0))
        assertEquals(2, Adaptation.after(3, Adaptation.ADAPT_BEATS))
        assertEquals(1, Adaptation.after(3, 2 * Adaptation.ADAPT_BEATS))
        // 降到底不再降（不许降到无感）
        assertEquals(Adaptation.FLOOR, Adaptation.after(3, 100))
        assertTrue("适应下限须 ≥1", Adaptation.FLOOR >= 1)
    }

    @Test
    fun `判据六_长期适应最多降一档且慢可逆不即时回弹`() {
        // 暴露 0 档不动基线；1..3 档都只降最多一档
        assertEquals(3, LongTermAdaptation.baselineAfter(3, 0))
        for (exposure in 1..LongTermAdaptation.MAX_EXPOSURE) {
            val b = LongTermAdaptation.baselineAfter(3, exposure)
            assertEquals("最多降一档", 2, b)
            assertTrue("不许降到无感", b >= LongTermAdaptation.BASE_FLOOR)
        }
        // 不即时回弹：未满 SLOW_WEEKS 保持降低
        val reduced = LongTermAdaptation.baselineAfter(3, 2)
        assertEquals(reduced, LongTermAdaptation.recoveringBaseline(reduced, 3, 0))
        assertEquals(reduced, LongTermAdaptation.recoveringBaseline(reduced, 3, LongTermAdaptation.SLOW_WEEKS - 1))
        // 慢可逆：满 SLOW_WEEKS 回一档，且不越过原基线
        assertEquals(3, LongTermAdaptation.recoveringBaseline(reduced, 3, LongTermAdaptation.SLOW_WEEKS))
        assertEquals(3, LongTermAdaptation.recoveringBaseline(reduced, 3, 99))
    }

    @Test
    fun `判据七_同输入两次结果逐字节一致`() {
        for (i in 0..10) {
            assertEquals(SecretionCurve.at(i), SecretionCurve.at(i))
            assertEquals(SensitivityCurve.at(i), SensitivityCurve.at(i))
            assertEquals(Rhythm.frequencyAt(i), Rhythm.frequencyAt(i))
            assertEquals(Adaptation.after(3, i), Adaptation.after(3, i))
            assertEquals(Recovery.at(1, 3, i, 1), Recovery.at(1, 3, i, 1))
        }
    }
}
