// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：产生器侧降采样探针。
// 口径（派工单已拍，本件照做）：产生器自己降采样，记录层不懂业务语义；
//   · 只记进入细化/评估的那一批，不记原始 8192 全量；每条带 sampledFrom=N + strategy；
//   · 单产生器占主池 ≤20%（ConsiderLog 配额）；超配额只记截断计数，不许静默丢；
//   · 诊断模式（diagnostic=true）才记诊断圈内候选，写独立文件，不混主池；开关关 ⇒ 无 diag 文件；
//   · 8192 全量进主池 ⇒ 红：单次调用写入主池 ≤ MAX_WRITE_PER_CALL（2000）。
// 本件零 IO 决定：只决定"记哪条"，落盘由调用方 export* 给目标文件。
package com.example.zhengdao.baopu.state.consider

import java.io.File

/**
 * 产生器侧留痕探针（确定性，零随机零时钟）。
 *
 * 业务侧只需在候选进入评估时调用 [record]，并把该候选的"去向"映射成 [ConsiderVerdict] 闭集码；
 * 本件不判断业务，只按 [strategy] 决定这条是否进主池、以及是否进诊断池。
 *
 * @param sampledFrom 原始候选总数 N（790 = BearingGeometry.SAMPLES = 8192），原样写进每条记录
 * @param strategy 降采样策略，wire 值 ∈ {firstK, stride, reservoir, diagnosticOnly}
 * @param maxRecords 单次评估允许写入主池的上限（确定性硬顶，≤ [ConsiderLog.MAX_WRITE_PER_CALL]）
 * @param diagnostic 诊断模式开关；关 ⇒ 诊断池为 null ⇒ 不产生 diag 文件
 * @param baseAtMs 逻辑时刻基准（本件不读系统时钟；同输入同输出）
 */
internal class ConsiderProbe(
    val generatorId: String,
    val sampledFrom: Int,
    val strategy: ConsiderStrategy,
    val main: ConsiderLog = ConsiderLog.shared,
    val maxRecords: Int = DEFAULT_MAX_RECORDS,
    val diagnostic: Boolean = false,
    val baseAtMs: Long = 0L,
) {
    init {
        require(sampledFrom > 0) { "sampledFrom 必须为正：$sampledFrom" }
        require(maxRecords in 1..ConsiderLog.MAX_WRITE_PER_CALL) {
            "maxRecords 必须落在 1..${ConsiderLog.MAX_WRITE_PER_CALL}：$maxRecords"
        }
    }

    /** 诊断池：只在诊断模式开启时创建；与主池是两个独立实例，绝不共用。 */
    val diag: ConsiderLog? = if (diagnostic) ConsiderLog(capacity = ConsiderLog.DEFAULT_CAPACITY) else null

    private var seq = 0
    private var writtenMain = 0

    /** 确定性 reservoir 索引集（固定 LCG + Fisher-Yates，零 kotlin.random）。 */
    private val reservoirSet: Set<Int> by lazy {
        if (strategy != ConsiderStrategy.RESERVOIR) emptySet()
        else reservoirIndices(sampledFrom, maxRecords)
    }

    private val stride: Int get() = maxOf(1, sampledFrom / maxRecords)

    /**
     * 记一个候选。摘要按需计算（只在真的入池/diag 时才 []digest]，8192 次里绝大多数不付字符串成本）。
     *
     * @param digest 输入摘要的惰性供给（产生器给，通常是 `{ digestOf(angles) }`）
     * @param verdict 该候选在这一层的去向（闭集码，业务侧映射）
     * @param inDiagCircle 是否落在诊断圈内（业务侧给布尔，本件不懂 err/band）
     */
    @Synchronized
    fun record(digest: () -> String, verdict: ConsiderVerdict, inDiagCircle: Boolean) {
        val i = seq++
        val at = baseAtMs + i
        val mainTaken = acceptMain(i)
        val diagLog = diag
        val diagTaken = diagnostic && inDiagCircle && diagLog != null
        if (!mainTaken && !diagTaken) return
        val inputDigest = digest()
        if (mainTaken) {
            writtenMain++
            main.record(ConsiderRecord(generatorId, inputDigest, verdict, at, sampledFrom, strategy))
        }
        if (diagTaken) {
            diagLog?.record(
                ConsiderRecord(generatorId, inputDigest, verdict, at, sampledFrom, ConsiderStrategy.DIAGNOSTIC_ONLY),
            )
        }
    }

    /** 主池是否收这条：策略判定 + 单次调用硬顶（>2000 即红，这里在到达上限前就停）。 */
    private fun acceptMain(index: Int): Boolean {
        if (writtenMain >= maxRecords) return false
        return when (strategy) {
            ConsiderStrategy.FIRST_K -> index < maxRecords
            ConsiderStrategy.STRIDE -> index % stride == 0
            ConsiderStrategy.RESERVOIR -> index in reservoirSet
            ConsiderStrategy.DIAGNOSTIC_ONLY -> false
        }
    }

    /** 已写入主池条数（判据⑤取证：一次评估 ≤2000）。 */
    @Synchronized fun writtenMain(): Int = writtenMain

    /** 复位序号与硬顶计数（池本身另由 ConsiderLog.reset 复位）。 */
    @Synchronized fun resetCounters() {
        seq = 0
        writtenMain = 0
    }

    /** 导出主池 JSONL，返回行数。 */
    fun exportMain(target: File): Int = main.exportJsonl(target)

    /**
     * 导出诊断池 JSONL。诊断模式关（diag=null）⇒ 返回 0 且**不碰文件**（不产生 diag 文件）。
     */
    fun exportDiag(target: File): Int {
        val diagLog = diag ?: return 0
        return diagLog.exportJsonl(target)
    }

    /** 已记入主池/诊断池的条数（导出前可查）。 */
    @Synchronized fun diagSize(): Int = diag?.size() ?: 0

    companion object {
        /** 单次评估默认最多记 64 条（8192 → stride 128），远低于 2000 红线。 */
        const val DEFAULT_MAX_RECORDS = 64

        private const val LCG_MUL = 6_364_136_223_846_793_005L
        private const val LCG_ADD = 1_442_695_040_888_963_407L
        private const val LCG_SEED = 0x2545F4914F6CDD1DL

        private fun nextRand(seed: Long): Long = (seed * LCG_MUL + LCG_ADD) and Long.MAX_VALUE

        /** 固定种子 Fisher-Yates：从 0..n-1 里确定性地选 k 个互不相同的索引。 */
        private fun reservoirIndices(n: Int, k: Int): Set<Int> {
            val idx = IntArray(n) { it }
            var s = LCG_SEED
            val take = minOf(k, n)
            for (i in 0 until take) {
                s = nextRand(s)
                val j = i + (s % (n - i)).toInt()
                val t = idx[i]
                idx[i] = idx[j]
                idx[j] = t
            }
            return idx.take(take).toSet()
        }
    }
}
