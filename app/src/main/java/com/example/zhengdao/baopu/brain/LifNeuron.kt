// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B（类脑仿真影子层）①：电流型 LIF ＋ 自适应阈值。
// 唯一读法＝私有侧《788-段A-设计定稿-2026-11-22》
//   §一 选 LIF 不选 Izhikevich（缺的是"累积→适应→回落"，不是放电模式的花样）
//   §二 步长 5 ms、每轮 ≤1200 步、每物类 64 神经元（48 兴奋 / 16 抑制）
//   §三 rate coding，窗口 100 ms
//
// 参数一律 `source=engineering`：取值来自「一次接触—反应—回落」的最短闭环与工作量估算，
// 不来自任何诊疗资料，也**不来自对代理档的拟合**（定稿 §八④：调参调到像为止 ⇒ 作废）。
//
// 本文件零随机、零时钟、零 IO、零 android 依赖：同一串 step 输入必然给出同一串放电。
package com.example.zhengdao.baopu.brain

/**
 * LIF 与网络规模的全部常量（唯一真源）。
 *
 * 为什么全部集中在一个 object：定稿把步长/规模/窗口钉死了，段 C 要按这些数复现对照；
 * 数字散落在各处 ⇒ 改一处忘一处，复现就漂。测试逐条读这里，不另抄一份。
 */
internal object LifParams {

    /** 参数来源标记（判据⑦：纯 Kotlin 常量，无第三方 SNN 框架）。 */
    const val SOURCE = "engineering"

    /** 仿真步长 5 ms（定稿 §二：1 ms 太贵、10 ms 会掩盖 200 ms 级现象）。 */
    const val DT_MS = 5.0

    /** 膜时间常数 20 ms ⇒ 每步泄漏系数 dt/τ = 0.25。 */
    const val TAU_M_MS = 20.0

    /** 静息电位（归一化单位：基线阈值 1.0 ＝恰好放电）。 */
    const val V_REST = 0.0

    /** 复位电位（放电后回到静息）。 */
    const val V_RESET = 0.0

    /** 基线阈值 θ0。 */
    const val THETA_BASE = 1.0

    /**
     * 自适应增量 a：每次放电阈值抬这么多，再按 [TAU_ADAPT_MS] 回落（定稿 §一）。
     * 0.05 的取法：一个 6 s 轮里阈值最多抬到约 2.3（＝峰值输入电流），
     * 于是"强刺激也会被自己的阈值压回"——这就是要断言的饱和，不是调出来的像。
     */
    const val ADAPT_INC = 0.05

    /**
     * 阈值回落时间常数 1000 ms ⇒ 每步回落系数 dt/τ_a = 0.005。
     * 定稿只写"按时间常数回落"，没钉这个数；取 1 s 的理由：一轮 6 s 要能看见
     * "抬起—压住—回落"三个阶段的形状。200 ms 的实测结果是一窗之内就落平，
     * 适应档拉不开（见交付报告 §实测）。
     */
    const val TAU_ADAPT_MS = 1000.0

    /** 不应期 2 步 = 10 ms（放电后 10 ms 内不再放电）。 */
    const val REFRACTORY_STEPS = 2

    /** 每轮步数上限 1200 步 = 6 s 仿真时长（定稿 §二）。 */
    const val MAX_STEPS = 1200

    /** 计数窗口 100 ms = 20 步（定稿 §三 rate coding）。 */
    const val WINDOW_MS = 100.0

    /** 一窗的步数。 */
    const val STEPS_PER_WINDOW = 20

    /** 独立 seed 0x5eed（定稿 §五①）；不共享主链随机源。十进制 24301 写进账本。 */
    const val SEED = 0x5eedL

    /** 每物类神经元总数（定稿 §二）。 */
    const val N_TOTAL = 64

    /** 其中兴奋性。 */
    const val N_EXC = 48

    /** 其中抑制性。 */
    const val N_INH = N_TOTAL - N_EXC

    /**
     * 每个神经元的入边数。定稿没钉这个数；取 8 的理由：64 个里连 8 条 ≈ 12.5% 连通率，
     * 稀疏（不会一放电就带全群）且不孤立（每个神经元都有人喂）。
     */
    const val FAN_IN = 8
}

/**
 * 一个电流型 LIF 神经元（带自适应阈值）。
 *
 * 动力学（Euler，dt = [LifParams.DT_MS]）：
 * ```
 * τ_m dV/dt = -V + R·I        ⇒ V ← V·(1-dt/τ_m) + (dt/τ_m)·I     （R=1，与阈值同单位）
 * θ ← θ - (dt/τ_a)·(θ-θ0)     （不低于 θ0）
 * 放电：V ≥ θ ⇒ V := V_reset, θ += a, 不应期 = 2 步
 * ```
 * [bias] 是每神经元的固定小偏置（确定性，由拓扑采样给出），用来打破 64 个神经元完全同构；
 * 幅度被压在 ±0.02 以内 ⇒ 无输入时永远到不了阈值（不会自发点火）。
 */
internal class LifNeuron(val excitatory: Boolean, val bias: Double) {

    /** 膜电位（归一化单位）。 */
    var potential: Double = LifParams.V_REST
        private set

    /** 当前阈值（基线 + 未回落的适应量）。 */
    var threshold: Double = LifParams.THETA_BASE
        private set

    /** 累计放电数（只给调试与断言用，不参与任何映射）。 */
    var spikes: Int = 0
        private set

    /** 剩余不应期步数。 */
    private var refractory = 0

    /**
     * 推进一个步长，返回这一步是否放电。
     * [current] ＝ 外部输入电流 ＋ 突触电流 ＋ [bias]，三者同单位（阈值 1.0 为界）。
     * [dtMs] 默认就是定稿的 5 ms；给参数是为了让"步长"只有一处真源，也便于测试反证。
     */
    fun step(current: Double, dtMs: Double = LifParams.DT_MS): Boolean {
        relaxThreshold(dtMs)
        if (refractory > 0) {
            refractory--
            potential = LifParams.V_RESET
            return false
        }
        val leak = dtMs / LifParams.TAU_M_MS
        potential += leak * (-potential + current)
        if (potential < threshold) return false
        fire()
        return true
    }

    /** 阈值向基线回落（先回落再判放电：这一步的阈值是回落之后的阈值）。 */
    private fun relaxThreshold(dtMs: Double) {
        val rate = dtMs / LifParams.TAU_ADAPT_MS
        threshold -= rate * (threshold - LifParams.THETA_BASE)
        if (threshold < LifParams.THETA_BASE) threshold = LifParams.THETA_BASE
    }

    /** 放电：复位、阈值抬 a、进不应期。 */
    private fun fire() {
        potential = LifParams.V_RESET
        threshold += LifParams.ADAPT_INC
        refractory = LifParams.REFRACTORY_STEPS
        spikes++
    }
}
