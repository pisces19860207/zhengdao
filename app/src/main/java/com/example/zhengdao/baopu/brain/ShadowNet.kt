// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ②：64 神经元固定拓扑的类脑影子网络。
// 依据：定稿 §二（每物类 64＝48 兴奋/16 抑制，步长 5 ms，每轮 ≤1200 步）
//      §三（rate coding，100 ms 窗口）§五①（独立 seed 0x5eed）。
//
// 三条硬边界：
//   1. **确定性**：拓扑与偏置全部由 `Random(seed xor fnv1a(zoneId))` 一次采样得到；
//      同一 (seed, zoneId) 两次构造必然是同一张图，同一串输入必然给出同一串放电帧。
//   2. **有界**：步数上限 1200、突触权重压在 ±0.07、每神经元固定 8 条入边 ⇒ 不会自激发散。
//   3. **零依赖**：只用 kotlin 标准库（Random 来自 stdlib，无第三方 SNN 框架，判据⑦）。
package com.example.zhengdao.baopu.brain

import java.util.Locale
import kotlin.random.Random

/**
 * 一轮影子的读数。全部是**中间量**，不对外：
 * 对外的只有 [MapToTier] 映射出的 0–3 档（判据④）。
 */
internal class ShadowRound(
    /** 每 100 ms 窗口的群体放电率（放电数 ÷ (神经元数 × 窗口步数)）。 */
    val windowRates: DoubleArray,
    /** 末窗放电率——turgor 档的输入（"现在"的强度，不是全程均值）。 */
    val spikeRate: Double,
    /** 末窗群体平均膜电位 ÷ 基线阈值——兴奋度档的输入。 */
    val meanPotential: Double,
    /** 窗率随窗口的线性斜率（每窗）——适应档的输入（负值＝衰减）。 */
    val adaptSlope: Double,
) {
    init {
        require(windowRates.isNotEmpty()) { "窗口率不能为空" }
    }

    /** 一帧机读快照：同输入两跑必须逐字相同（判据③的比对单位）。 */
    fun frame(): String = buildString {
        append(windowRates.joinToString(",") { fmt(it) })
        append("|rate=").append(fmt(spikeRate))
        append("|v=").append(fmt(meanPotential))
        append("|slope=").append(fmt(adaptSlope))
    }

    private fun fmt(x: Double): String = String.format(Locale.ROOT, "%.6f", x)
}

/**
 * 一个物类的 64 神经元影子网络。
 *
 * 拓扑：每个神经元固定 [FAN_IN] 条入边，入边源从全群 64 个里无放回抽样（不许自连）。
 * 权重按前后是否兴奋性定号：E→E / E→I 为正，I→E / I→I 为负；量级压在 0.05–0.07，
 * 目的是让 8 条入边全开的驱动（≈0.5）小于一个刺激档的跨度（≈0.5–0.9），
 * 于是"外部刺激定档、循环只做微调"，不会把编码表冲掉。
 */
internal class ShadowNet(val zoneId: String, seed: Long = LifParams.SEED) {

    private val rng = Random(seed xor fnv1a(zoneId))

    /** 64 个神经元（前 48 兴奋、后 16 抑制）。 */
    val neurons: List<LifNeuron> = List(LifParams.N_TOTAL) { i ->
        LifNeuron(excitatory = i < LifParams.N_EXC, bias = (rng.nextDouble() * 2.0 - 1.0) * BIAS_MAX)
    }

    private val presynaptic: Array<IntArray> = Array(LifParams.N_TOTAL) { IntArray(LifParams.FAN_IN) }

    private val weights: Array<DoubleArray> = Array(LifParams.N_TOTAL) {
        DoubleArray(LifParams.FAN_IN)
    }

    /** 上一步谁放了电（一步突触延迟）。 */
    private var lastSpikes = BooleanArray(LifParams.N_TOTAL)

    /** 这一步的输入电流暂存（避免每步开数组）。 */
    private val current = DoubleArray(LifParams.N_TOTAL)

    init {
        buildTopology()
    }

    /** 入边源（测试用：钉拓扑确定性）。下标 i 的第 k 条入边来自 [presynaptic][i][k]。 */
    fun presynapticOf(post: Int): IntArray = presynaptic[post].copyOf()

    /** 权重（测试用）。 */
    fun weightsOf(post: Int): DoubleArray = weights[post].copyOf()

    /**
     * 推进一个仿真步，返回这一步全群放电数。
     * [externalCurrent] 是这一步所有神经元共享的外部刺激电流（[Encode] 给出的那个）。
     */
    fun step(externalCurrent: Double, dtMs: Double = LifParams.DT_MS): Int {
        for (post in 0 until LifParams.N_TOTAL) {
            current[post] = externalCurrent + neurons[post].bias + synapticOf(post)
        }
        var fired = 0
        val next = BooleanArray(LifParams.N_TOTAL)
        for (i in 0 until LifParams.N_TOTAL) {
            val spiked = neurons[i].step(current[i], dtMs)
            next[i] = spiked
            if (spiked) fired++
        }
        lastSpikes = next
        return fired
    }

    /**
     * 跑一整轮（≤[LifParams.MAX_STEPS] 步），按 100 ms 窗口汇总。
     * [steps] 必须落在 [STEPS_PER_WINDOW]..[MAX_STEPS]：少于一个窗口就没有"率"可言，
     * 多于上限就不是定稿的 6 秒仿真时长——两者都当场红，不静默夹取。
     */
    fun runRound(
        externalCurrent: Double,
        steps: Int = LifParams.MAX_STEPS,
        dtMs: Double = LifParams.DT_MS,
    ): ShadowRound {
        require(steps in LifParams.STEPS_PER_WINDOW..LifParams.MAX_STEPS) {
            "步数必须落在 ${LifParams.STEPS_PER_WINDOW}..${LifParams.MAX_STEPS}，收到 $steps"
        }
        val perStep = IntArray(steps) { step(externalCurrent, dtMs) }
        val windows = perStep.toList().chunked(LifParams.STEPS_PER_WINDOW)
            .map { window ->
                val spikes = window.sumOf { it }
                spikes.toDouble() / (LifParams.N_TOTAL * LifParams.STEPS_PER_WINDOW)
            }
        val rates = windows.toDoubleArray()
        return ShadowRound(
            windowRates = rates,
            spikeRate = rates.last(),
            meanPotential = neurons.sumOf { it.potential } / (LifParams.N_TOTAL * LifParams.THETA_BASE),
            adaptSlope = slopeOf(rates),
        )
    }

    /** 入边电流：上一步放过电的入边源按权重求和。 */
    private fun synapticOf(post: Int): Double {
        var sum = 0.0
        val pre = presynaptic[post]
        val w = weights[post]
        for (k in pre.indices) {
            if (lastSpikes[pre[k]]) sum += w[k]
        }
        return sum
    }

    /** 确定性拓扑：无放回抽样（不许自连），权重按前后类型定号。 */
    private fun buildTopology() {
        val seen = BooleanArray(LifParams.N_TOTAL)
        for (post in 0 until LifParams.N_TOTAL) {
            java.util.Arrays.fill(seen, false)
            seen[post] = true
            var k = 0
            while (k < LifParams.FAN_IN) {
                val source = rng.nextInt(LifParams.N_TOTAL)
                if (seen[source]) continue
                seen[source] = true
                presynaptic[post][k] = source
                weights[post][k] = weightOf(source, post)
                k++
            }
        }
    }

    /** 权重矩阵：兴奋入边为正、抑制入边为负；E→I 比 E→E 略强（前馈抑制）。 */
    private fun weightOf(source: Int, post: Int): Double {
        val sourceExc = source < LifParams.N_EXC
        val postExc = post < LifParams.N_EXC
        return when {
            sourceExc && postExc -> W_EE
            sourceExc -> W_EI
            postExc -> W_IE
            else -> W_II
        }
    }

    /** 窗率序列的线性斜率（最小二乘；窗口数 1 ⇒ 0.0，没有斜率可言）。 */
    private fun slopeOf(rates: DoubleArray): Double {
        if (rates.size < 2) return 0.0
        val n = rates.size
        val meanX = (n - 1) / 2.0
        val meanY = rates.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            num += dx * (rates[i] - meanY)
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private companion object {
        /** 偏置上限（±0.02 ≪ 阈值 1.0 ⇒ 无刺激时不会自发点火）。 */
        const val BIAS_MAX = 0.02

        const val W_EE = 0.05
        const val W_EI = 0.06
        const val W_IE = -0.07
        const val W_II = -0.05

        /** FNV-1a 32 位：把物类 id 混进 seed，且不依赖任何平台的字符串哈希实现。 */
        fun fnv1a(text: String): Long {
            var hash = 0x811c9dc5L
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash = (hash * 0x01000193L) and 0xffffffffL
            }
            return hash
        }
    }
}
