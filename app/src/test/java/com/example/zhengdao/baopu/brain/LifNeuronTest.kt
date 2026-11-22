// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ①：LIF ＋ 自适应阈值的逐条行为断言。
// 口径：这里只钉"方程写对了没有"——首脉冲步数、不应期、阈值抬起与回落、无输入不点火。
package com.example.zhengdao.baopu.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifNeuronTest {

    private fun neuron(bias: Double = 0.0) = LifNeuron(excitatory = true, bias = bias)

    /** 跑 [steps] 步恒定输入，返回首次放电的步号（1 起；没放电返回 -1）。 */
    private fun firstSpikeStep(current: Double, steps: Int = 40): Int {
        val n = neuron()
        for (s in 1..steps) if (n.step(current)) return s
        return -1
    }

    @Test
    fun `无输入不放电且电位停在静息`() {
        val n = neuron()
        repeat(200) { assertFalse("零输入下不许放电", n.step(0.0)) }
        assertEquals(LifParams.V_REST, n.potential, 1e-12)
        assertEquals("阈值不许自己漂", LifParams.THETA_BASE, n.threshold, 1e-12)
    }

    @Test
    fun `偏置在零附近也不许自发点火`() {
        // ±0.02 的偏置是拓扑给的，稳态 0.02 远低于阈值 1.0 ⇒ 200 步一步都不许放。
        for (bias in doubleArrayOf(-0.02, 0.0, 0.02)) {
            val n = neuron(bias)
            repeat(200) { assertFalse("偏置 $bias 下不许自发点火", n.step(0.0)) }
        }
    }

    @Test
    fun `首脉冲步数与方程解一致`() {
        // V_n = I(1-0.75^n)，首次 ≥1 的 n：I=2.4⇒2，I=1.7⇒4，I=1.3⇒6
        assertEquals("I=2.4 应第 2 步放电", 2, firstSpikeStep(2.40))
        assertEquals("I=1.7 应第 4 步放电", 4, firstSpikeStep(1.70))
        assertEquals("I=1.3 应第 6 步放电", 6, firstSpikeStep(1.30))
        // 负例：稳态低于阈值 ⇒ 永远不放（阈下不是"慢"，是不放）
        assertEquals("I=0.9 稳态 0.9 < 1.0 ⇒ 不放", -1, firstSpikeStep(0.90, 400))
    }

    @Test
    fun `放电后两步不应期`() {
        val n = neuron()
        var guard = 0
        while (!n.step(2.40) && guard < 10) guard++
        assertTrue("2.4 的输入应在 10 步内放电", n.spikes == 1)
        assertFalse("放电后第 1 步不许再放", n.step(2.40))
        assertFalse("放电后第 2 步不许再放", n.step(2.40))
        var again = false
        repeat(4) { if (n.step(2.40)) again = true }
        assertTrue("不应期过后 4 步内必须能再放（否则不是 LIF 是不动了）", again)
    }

    @Test
    fun `每次放电阈值抬 a 并逐渐回落`() {
        val n = neuron()
        // 打到 3 次放电
        var fired = 0
        var guard = 0
        while (fired < 3 && guard < 200) {
            if (n.step(2.40)) fired++
            guard++
        }
        assertEquals(3, n.spikes)
        assertTrue("放 3 次后阈值必须高于基线：${n.threshold}", n.threshold > LifParams.THETA_BASE)
        // 停刺激后回落：跑 20 s 空转（4000 步 ≈ 10 个 τ_a），阈值应回到基线
        repeat(4000) { n.step(0.0) }
        assertEquals("久无输入后阈值应回落到基线", LifParams.THETA_BASE, n.threshold, 1e-6)
    }

    @Test
    fun `持续刺激后放电变慢即适应`() {
        // 前半轮与后半轮的放电数：同一输入，后半必须更少（阈值被自己压住）
        val n = neuron()
        var early = 0
        var late = 0
        repeat(600) { if (n.step(1.70)) early++ }
        repeat(600) { if (n.step(1.70)) late++ }
        assertTrue("前 3 s 应放电（拿到 $early 次）", early > 0)
        assertTrue("后 3 s 应变慢：前 $early / 后 $late", late < early)
    }

    @Test
    fun `同一串输入给出同一串放电`() {
        val a = neuron(0.01)
        val b = neuron(0.01)
        val seqA = (0 until 300).map { a.step(if (it % 7 < 3) 1.7 else 0.4) }
        val seqB = (0 until 300).map { b.step(if (it % 7 < 3) 1.7 else 0.4) }
        assertEquals("同参数同输入必须逐帧一致", seqA, seqB)
    }

    @Test
    fun `步长改大一倍则同一步走得更多`() {
        // 反证"步长只有一处真源"：dt=10 ms 时第 1 步泄漏系数 0.5，V 一次就到 1.05
        val fast = LifNeuron(excitatory = true, bias = 0.0)
        assertTrue("dt=10 ms 时 2.4 的输入第 1 步就过阈", fast.step(2.40, dtMs = 10.0))
        val slow = LifNeuron(excitatory = true, bias = 0.0)
        assertFalse("dt=5 ms 时同样输入第 1 步只到 0.6", slow.step(2.40, dtMs = 5.0))
    }
}
