// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 杏仁核·快探（有状态半）主件。
//
// 定位（派工单原文口径）：快、粗、**先于判定**的威胁/显著性探测——只"举手"，不"拍板"。
// 输入：474 轨迹的事件流（[ProbeInput] 里直接装 [TraceRow]，不另造事件总线）；
//       正文是调用方手头暂有的瞬态文本（现喂现用，快探不存正文——状态与账里只有
//       类键、计数、乘子与时刻，这与 474 "不存正文"同一物理红线）。
// 输出：salience（0..1）＋六类威胁标签（不可逆/外发/敏感路径/批量/异常速度/语气突变）＋举手。
//
// 四条仿生特性（缺一不算）：
//   ① 快：全机械（工具名分类表/路径段匹配/计数/乘子），毫秒级，**不调模型**；
//      威胁强度读数**只消费 675 的 [Amygdala.salience]**——自造第二套文本判断在这里不存在
//      （源码断言：本包无 DangerWords 引用，salience 公式的文本分量全部来自 675 读数）。
//   ② 粗：宁误报——阈值取低、标签取宽，交慢层（RiskMatrix/左右相）复核；
//   ③ 习惯化与 ④ 敏化在 [AmygdalaState]，本件只把它的乘子乘进 salience。
// 红线：只标记（mark），不拦（block）、不改档、不写权威账；输出不产生任何判定值。
// 失败兜底（判据⑥）：本件未接主链路；账挂掉只吞异常照常出读数，行为与"没有 520"逐字一致。
package com.example.zhengdao.baopu.amygdala

import com.example.zhengdao.baopu.agent.TracePhase
import com.example.zhengdao.baopu.agent.TraceRow
import com.example.zhengdao.baopu.agent.TraceStatus
import com.example.zhengdao.baopu.bio.Amygdala
import com.example.zhengdao.baopu.bio.BioAnalog
import com.example.zhengdao.baopu.bio.BioEvent
import com.example.zhengdao.baopu.bio.BioLog

/** 六类威胁标签（机械判定，宁宽勿漏）。标签只是"举手时带的话"，不是判定。 */
internal enum class ThreatLabel { IRREVERSIBLE, OUTBOUND, SENSITIVE_PATH, BATCH, ABNORMAL_SPEED, TONE_SHIFT }

/** 事件流的一条输入：474 轨迹行（必给）＋调用方手头暂有的瞬态正文（可空）。 */
internal data class ProbeInput(val row: TraceRow, val text: String = "")

/** 快探的输出：读数＋标签＋举没举手。没有任何裁量字段——复核与判定权在慢层。 */
internal data class ProbeMark(
    val salience: Double,
    val labels: Set<ThreatLabel>,
    val handRaised: Boolean,
    val classKey: String,
    val note: String,
)

/** 杏仁核·快探（有状态半）。@BioAnalog("杏仁核")：登记表第一行，与 675 读数源同一件器官。 */
@BioAnalog("杏仁核")
internal class AmygdalaProbe(
    private val account: ProbeAccount = InMemoryProbeAccount(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val state = AmygdalaState(account)
    private val recentThreats = ArrayDeque<Int>()

    /** 三问入口（523 本地 B 计划缝，523 合入后回填）。 */
    fun tripleGate(): BioAnalogGate = AmygdalaProbeGate

    /**
     * 快探主入口。同一（状态，输入）喂两遍输出逐字一致；毫秒级、无模型调用（判据①）。
     * 顺序固定：读乘子 → 消费 675 读数 → 机械标签 → 出 salience/举手 → 状态记账。
     */
    @Synchronized
    fun probe(input: ProbeInput): ProbeMark {
        val nowMs = clock()
        val classKey = classKeyOf(input.row)
        val factors = state.factorsFor(classKey, nowMs)
        val reading = readingOf(input, classKey)
        val labels = labelsOf(input, reading, state.batchTripped(classKey, nowMs))
        val base = baseOf(reading, labels)
        // 语气突变＝新异升级，习惯化对它豁免（生物同款：习惯化对新刺激恢复反应）。
        val habitFactor = if (ThreatLabel.TONE_SHIFT in labels) DEFAULT_FACTOR else factors.first
        val salience = (base * habitFactor * factors.second).coerceIn(0.0, 1.0)
        val raised = salience >= MARK_THRESHOLD
        state.recordEvent(
            classKey = classKey,
            marked = raised,
            accident = input.row.status in ACCIDENTS,
            nowMs = nowMs,
        )
        if (reading != null) pushThreat(reading.threat)
        if (raised) {
            BioLog.line(TAG, "举手 class=$classKey salience=${"%.2f".format(salience)} labels=$labels")
        }
        return ProbeMark(
            salience = salience,
            labels = labels,
            handRaised = raised,
            classKey = classKey,
            note = noteOf(labels, habitFactor, factors.second),
        )
    }

    /** 一键回默认（判据④）：习惯化/敏化全部清零，读回即出厂值。 */
    @Synchronized
    fun resetToDefault() = state.resetToDefault(clock())

    /** 测试/诊断读回：某类刺激当前状态。 */
    fun viewOf(classKey: String): ClassState = state.classView(classKey)

    /** 测试/诊断读回：探针账流水（判据②"账里有降敏行"的读账口）。 */
    fun accountEntries(): List<AccountEntry> = account.entries()

    // ── 机械件：每个都便宜、每个都可能误报（②粗：宁误报，交慢层复核）──

    /** 文本读数只此一路：675 的杏仁核。无正文就不读（空文本读零没有信息量，还多一行日志）。 */
    private fun readingOf(input: ProbeInput, classKey: String): Amygdala.Reading? =
        if (input.text.isBlank()) {
            null
        } else {
            Amygdala.salience(BioEvent(text = input.text, source = classKey))
        }

    private fun labelsOf(input: ProbeInput, reading: Amygdala.Reading?, batch: Boolean): Set<ThreatLabel> {
        val labels = LinkedHashSet<ThreatLabel>()
        if (isIrreversible(input.row.tool)) labels.add(ThreatLabel.IRREVERSIBLE)
        if (isOutbound(input.row.tool)) labels.add(ThreatLabel.OUTBOUND)
        if (hasSensitivePath(input.text)) labels.add(ThreatLabel.SENSITIVE_PATH)
        if (batch) labels.add(ThreatLabel.BATCH)
        if (isAbnormallyFast(input.row)) labels.add(ThreatLabel.ABNORMAL_SPEED)
        if (isToneShift(reading)) labels.add(ThreatLabel.TONE_SHIFT)
        return labels
    }

    /** 语气突变：675 读数的威胁档突然抬升（与近期读数比跳 ≥2 档）。只比档位，不读正文。 */
    private fun isToneShift(reading: Amygdala.Reading?): Boolean {
        if (reading == null || reading.threat < TONE_MIN || recentThreats.isEmpty()) return false
        return reading.threat - recentThreats.max() >= TONE_JUMP
    }

    private fun pushThreat(threat: Int) {
        recentThreats.addLast(threat)
        if (recentThreats.size > TONE_WINDOW) recentThreats.removeFirst()
    }

    /** salience 的底数：文本分量全部来自 675 读数（threat/novelty），标签按个加码。 */
    private fun baseOf(reading: Amygdala.Reading?, labels: Set<ThreatLabel>): Double {
        val threat = (reading?.threat ?: 0).coerceIn(0, THREAT_MAX)
        val novelty = (reading?.novelty ?: 0).coerceIn(0, NOVELTY_MAX)
        return BASE_THREAT * threat / THREAT_MAX +
            BASE_NOVELTY * novelty / NOVELTY_MAX +
            LABEL_WEIGHT * minOf(labels.size, LABEL_CAP)
    }

    private fun noteOf(labels: Set<ThreatLabel>, habitFactor: Double, sensFactor: Double): String {
        val named = if (labels.isEmpty()) "无标签" else labels.joinToString("、") { zhName(it) }
        return "$named；习惯化×$habitFactor 敏化×$sensFactor；快探只举手，复核与判定权在慢层"
    }

    private fun zhName(label: ThreatLabel): String = when (label) {
        ThreatLabel.IRREVERSIBLE -> "不可逆"
        ThreatLabel.OUTBOUND -> "外发"
        ThreatLabel.SENSITIVE_PATH -> "敏感路径"
        ThreatLabel.BATCH -> "批量"
        ThreatLabel.ABNORMAL_SPEED -> "异常速度"
        ThreatLabel.TONE_SHIFT -> "语气突变"
    }

    private fun classKeyOf(row: TraceRow): String = row.tool.ifBlank { "phase:${row.phase.name}" }

    private companion object {
        const val TAG = "杏仁核·快探"

        /** 举手线：salience 到 0.5 才举（降敏乘子把它压下去，标记率就掉了——判据②）。 */
        const val MARK_THRESHOLD = 0.5
        const val DEFAULT_FACTOR = 1.0

        /** salience 底数权重：威胁 0.40 ＋ 新异 0.15 ＋ 每标签 0.15（封 4 个）。 */
        const val BASE_THREAT = 0.40
        const val BASE_NOVELTY = 0.15
        const val LABEL_WEIGHT = 0.15
        const val LABEL_CAP = 4
        const val THREAT_MAX = 4
        const val NOVELTY_MAX = 2

        /** 异常速度：真干活的工具批（OK 收场）耗时不足 10ms——要么空转要么吞了错。 */
        const val SPEED_FLOOR_MS = 10L

        /** 语气突变：跳 ≥2 档且当前 ≥2 档；回看窗口 8 条读数。 */
        const val TONE_JUMP = 2
        const val TONE_MIN = 2
        const val TONE_WINDOW = 8

        /**
         * 事故＝轨迹行的 FAILED/REJECTED/TIMEOUT 三种收场。CONFIRM 不是事故
         * （停在等人批准是慢层在干活）；CANCELLED/SKIPPED 是主动动作，也不算。
         */
        val ACCIDENTS = setOf(TraceStatus.FAILED, TraceStatus.REJECTED, TraceStatus.TIMEOUT)

        /** 不可逆类工具名标记（机械 contains，宁宽勿漏：delete_path/重置类都落网）。 */
        val IRREVERSIBLE_MARKS = listOf("delete", "remove", "reset", "clear", "wipe", "format", "uninstall", "truncate")

        /** 外发类工具名标记。 */
        val OUTBOUND_MARKS = listOf("http", "upload", "send", "share", "push", "post", "export", "submit", "publish", "sync")

        /** 敏感路径段：路径形状（带 / 或 \）且含其中一段 ⇒ 标签（宁误报）。 */
        val SENSITIVE_SEGMENTS = listOf("system", "proc", "data", "db", "secret", "token", "credential", "passwd", "key")

        fun isIrreversible(tool: String): Boolean {
            val t = tool.lowercase()
            return IRREVERSIBLE_MARKS.any { t.contains(it) }
        }

        fun isOutbound(tool: String): Boolean {
            val t = tool.lowercase()
            return OUTBOUND_MARKS.any { t.contains(it) }
        }

        fun hasSensitivePath(text: String): Boolean {
            if (!text.contains('/') && !text.contains('\\')) return false
            val t = text.lowercase()
            return SENSITIVE_SEGMENTS.any { t.contains(it) }
        }

        fun isAbnormallyFast(row: TraceRow): Boolean =
            row.phase == TracePhase.ACT && row.status == TraceStatus.OK &&
                row.durationMs >= 0 && row.durationMs < SPEED_FLOOR_MS
    }
}
