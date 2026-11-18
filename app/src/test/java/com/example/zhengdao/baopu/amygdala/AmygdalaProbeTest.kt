// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 快探行为测试。全部走注入时钟与内存账，零真钟、零落盘（607 水位口径）。
// 判据对照：①p95≤5ms　②习惯化降敏＋账有降敏行＋负例　③敏化上升＋到期自动衰减
// ④一键回默认读回　⑥失败兜底逐字一致；另钉六类标签与"消费 675 读数"。
// 夹具口径：标准刺激行 durationMs=50（真干活的工具），免得误触异常速度标签搅动底数。
package com.example.zhengdao.baopu.amygdala

import com.example.zhengdao.baopu.agent.TracePhase
import com.example.zhengdao.baopu.agent.TraceRow
import com.example.zhengdao.baopu.agent.TraceStatus
import com.example.zhengdao.baopu.bio.Amygdala
import com.example.zhengdao.baopu.bio.BioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmygdalaProbeTest {

    /** 习惯化/敏化夹具的标准刺激：工具名不可逆类＋文本带敏感路径与一个危险词（底数 0.55）。 */
    private val stimulus = ProbeInput(row(tool = STIMULUS_TOOL), text = STIMULUS_TEXT)

    // ── 消费 675：salience 公式的文本分量必须全部来自 675 读数（红线"不许自造第二套判断"）──

    @Test
    fun salienceIsExactlyTheFormulaOver675Reading() {
        val probe = AmygdalaProbe(clock = { T0 })
        val mark = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = "删除"))
        val reading = Amygdala.salience(BioEvent(text = "删除", source = "card"))
        val expected = BASE_THREAT * reading.threat / THREAT_MAX +
            BASE_NOVELTY * reading.novelty / NOVELTY_MAX
        assertEquals(expected, mark.salience, 1e-9)
        assertTrue("无标签不举手", mark.labels.isEmpty() && !mark.handRaised)
    }

    @Test
    fun louder675ReadingMovesSalienceUp() {
        val probe = AmygdalaProbe(clock = { T0 })
        val calm = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = "今天天气不错"))
        val loud = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = "删除 清空 覆写"))
        assertTrue("675 读数抬升必须抬高 salience", loud.salience > calm.salience)
    }

    // ── 六类标签（机械、宁宽勿漏）──

    @Test
    fun irreversibleOutboundAndSensitivePathLabelsFire() {
        val probe = AmygdalaProbe(clock = { T0 })
        val del = probe.probe(ProbeInput(row(tool = "delete_path", durationMs = 50L), text = "/system/app.db"))
        assertTrue(ThreatLabel.IRREVERSIBLE in del.labels)
        assertTrue(ThreatLabel.SENSITIVE_PATH in del.labels)
        val http = probe.probe(ProbeInput(row(tool = "http", durationMs = 50L), text = ""))
        assertTrue(ThreatLabel.OUTBOUND in http.labels)
        assertFalse(ThreatLabel.IRREVERSIBLE in http.labels)
        val plain = probe.probe(ProbeInput(row(tool = "card_build", durationMs = 50L), text = "普通正文"))
        assertTrue("无害输入零标签", plain.labels.isEmpty())
    }

    @Test
    fun batchLabelFiresOnEighthSameToolEventInWindow() {
        val probe = AmygdalaProbe(clock = { T0 })
        repeat(7) { i ->
            val mark = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = ""))
            assertFalse("第 ${i + 1} 个不该批量", ThreatLabel.BATCH in mark.labels)
        }
        val eighth = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = ""))
        assertTrue("同窗口第 8 个同类事件 ⇒ 批量", ThreatLabel.BATCH in eighth.labels)
    }

    @Test
    fun abnormalSpeedLabelFiresOnlyOnSuspiciouslyFastToolWork() {
        val probe = AmygdalaProbe(clock = { T0 })
        val fast = probe.probe(ProbeInput(row(tool = "write_file", durationMs = 3L), text = ""))
        assertTrue(ThreatLabel.ABNORMAL_SPEED in fast.labels)
        val slow = probe.probe(ProbeInput(row(tool = "write_file", durationMs = 100L), text = ""))
        assertFalse(ThreatLabel.ABNORMAL_SPEED in slow.labels)
        val unknown = probe.probe(ProbeInput(row(tool = "write_file", durationMs = -1L), text = ""))
        assertFalse("没测到耗时（-1）不算异常速度", ThreatLabel.ABNORMAL_SPEED in unknown.labels)
        val notOk = probe.probe(ProbeInput(row(tool = "write_file", status = TraceStatus.CONFIRM, durationMs = 3L), text = ""))
        assertFalse("没真干活（停在等批准）不算", ThreatLabel.ABNORMAL_SPEED in notOk.labels)
    }

    @Test
    fun toneShiftLabelFiresOnSuddenThreatJump() {
        val probe = AmygdalaProbe(clock = { T0 })
        repeat(3) {
            val mark = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = "今天天气不错"))
            assertFalse(ThreatLabel.TONE_SHIFT in mark.labels)
        }
        val jump = probe.probe(ProbeInput(row(tool = "card", durationMs = 50L), text = "删除 清空 覆写"))
        assertTrue("平静流里威胁档骤升 ⇒ 语气突变", ThreatLabel.TONE_SHIFT in jump.labels)
    }

    // ── 判据② 习惯化：同类无害刺激 ×N ⇒ 标记率下降且账里有降敏行 ──

    @Test
    fun habituationLowersMarkRateAndLedgersDesensitize() {
        val probe = AmygdalaProbe(clock = { T0 })
        val marks = (1..12).map { probe.probe(stimulus) }
        assertTrue("前 6 次全举手（底数 0.55 ≥ 0.5）", marks.take(6).all { it.handRaised })
        assertTrue("降敏后不再举手", marks.drop(6).none { it.handRaised })
        val rate = marks.count { it.handRaised } / marks.size.toDouble()
        assertTrue("标记率必须下降（实测 $rate）", rate < 1.0)
        val kinds = probe.accountEntries().map { it.kind }
        assertTrue("账里必须有降敏行", AccountKind.DESSENSITIZE in kinds)
        assertEquals("降敏一次记一行", 1, kinds.count { it == AccountKind.DESSENSITIZE })
        assertEquals(0.5, probe.viewOf(STIMULUS_TOOL).habituationFactor, 0.0)
    }

    /** 负例（判据②"不降 ⇒ 红"）：机制的记忆不在场（每事件换新探针）⇒ 标记率不降 ⇒ 证明下降来自习惯化。 */
    @Test
    fun freshProbeKeepsMarkingProvesHabituationCausesTheDrop() {
        val probes = (1..12).map { AmygdalaProbe(clock = { T0 }) }
        val marks = probes.map { it.probe(stimulus) }
        assertTrue("没有积累就没有降敏：全部举手", marks.all { it.handRaised })
        assertTrue("新探针的账里永远没有降敏行", probes.all { p -> p.accountEntries().none { it.kind == AccountKind.DESSENSITIZE } })
    }

    // ── 判据③ 敏化：一次事故后标记率上升；N 天后按时间夹具自动衰减 ──

    @Test
    fun accidentSensitizesThenDecaysByClockFixture() {
        var now = T0
        val probe = AmygdalaProbe(clock = { now })
        repeat(6) { probe.probe(stimulus) } // 6 连举手 ⇒ 习惯化（乘子 0.5）
        val quiet = probe.probe(stimulus)
        assertFalse("已降敏：同类刺激不再举手", quiet.handRaised)
        probe.probe(ProbeInput(row(tool = STIMULUS_TOOL, status = TraceStatus.FAILED), text = ""))
        assertTrue(AccountKind.SENSITIZE in probe.accountEntries().map { it.kind })
        assertEquals("事故作废『无害』证据：习惯化乘子回 1.0", 1.0, probe.viewOf(STIMULUS_TOOL).habituationFactor, 0.0)
        val afterAccident = probe.probe(stimulus)
        assertTrue("敏化后同类刺激标记率上升（乘子 ×2 ⇒ 必举手）", afterAccident.handRaised)
        assertEquals(1.0, afterAccident.salience, 1e-9)
        now = T0 + SENSITIZE_DAYS_MS + 1
        val decayed = probe.probe(stimulus)
        assertTrue("到期衰减必须自动落账", AccountKind.DECAY in probe.accountEntries().map { it.kind })
        assertEquals("衰减后乘子回 1.0：salience 回底数（全程没调过 reset）", EXPECTED_BASE, decayed.salience, 1e-9)
    }

    // ── 判据④ 一键回默认：调用后读回原值 ──

    @Test
    fun resetToDefaultReadsBackFactoryValues() {
        var now = T0
        val probe = AmygdalaProbe(clock = { now })
        repeat(6) { probe.probe(stimulus) }
        probe.probe(ProbeInput(row(tool = STIMULUS_TOOL, status = TraceStatus.REJECTED), text = ""))
        assertFalse("敏化在场 ⇒ 状态非默认", probe.viewOf(STIMULUS_TOOL) == ClassState())
        probe.resetToDefault()
        assertTrue("一键回默认：读回出厂值", probe.viewOf(STIMULUS_TOOL) == ClassState())
        assertTrue(AccountKind.RESET in probe.accountEntries().map { it.kind })
        now += 1
        val after = probe.probe(stimulus)
        assertEquals("回默认后行为也是出厂行为", EXPECTED_BASE, after.salience, 1e-9)
    }

    // ── 判据⑥ 失败兜底：账挂掉，输出逐字不变、异常不外漏 ──

    @Test
    fun throwingAccountChangesNotOneByteOfOutput() {
        val good = runScript(InMemoryProbeAccount())
        val broken = runScript(ThrowingAccount())
        assertEquals("账坏了：输出一个字节都不许变", good, broken)
    }

    @Test
    fun sameScriptSameMarksAcrossFreshProbes() {
        assertEquals(runScript(InMemoryProbeAccount()), runScript(InMemoryProbeAccount()))
    }

    // ── 判据① 毫秒级：单次判定 p95 ≤ 5ms（本机实测，读数进报告）──

    @Test
    fun singleJudgmentP95Under5Millis() {
        val stream = mixedStream()
        repeat(WARMUP) { AmygdalaProbe(clock = { T0 }).probe(stream[it % stream.size]) }
        val probe = AmygdalaProbe(clock = { T0 })
        val nanos = LongArray(SAMPLES)
        repeat(SAMPLES) { i ->
            val started = System.nanoTime()
            probe.probe(stream[i % stream.size])
            nanos[i] = System.nanoTime() - started
        }
        nanos.sort()
        val p95 = nanos[(SAMPLES * 95) / 100]
        println("[520] 快探单次判定 p95=${p95 / 1000.0}μs，max=${nanos[SAMPLES - 1] / 1000.0}μs（n=$SAMPLES）")
        assertTrue("p95=${p95}ns 超过 5ms 红线", p95 <= 5_000_000L)
    }

    // ── 三问入口（523 本地 B 计划）：答案非空且引用的测试真实存在 ──

    @Test
    fun gateAnswersCiteRealEvidence() {
        val gate = AmygdalaProbe(clock = { T0 }).tripleGate()
        val a1 = gate.triple1MeasurableDifference()
        val a2 = gate.triple2NoOverreach()
        val a3 = gate.triple3Fallback()
        assertTrue(a1.evidence.isNotBlank() && a2.evidence.isNotBlank() && a3.evidence.isNotBlank())
        for (needle in TRIPLE1_EVIDENCE) assertTrue("三问①证据须引用真实测试：$needle", needle in a1.evidence)
        assertTrue("三问②证据须引用源码断言", "noJudgementNoLedgerNoModelInCode" in a2.evidence)
        assertTrue("三问③证据须引用兜底测试", "throwingAccountChangesNotOneByteOfOutput" in a3.evidence)
    }

    // ── 夹具 ──

    private fun row(
        tool: String,
        status: TraceStatus = TraceStatus.OK,
        phase: TracePhase = TracePhase.ACT,
        durationMs: Long = 50L,
    ): TraceRow = TraceRow(
        runId = "r1@$T0", sessionId = 1L, step = 1, seq = 0,
        phase = phase, status = status, tool = tool, durationMs = durationMs,
    )

    /**
     * 兜底/确定性对照脚本（探针在函数内建，时钟闭包接本函数的 now）：
     * 刺激×6（习惯化）→ 刺激（静）→ 事故行 → 刺激（敏化举手）→ 推进三天 → 刺激（衰减）。
     */
    private fun runScript(account: ProbeAccount): List<ProbeMark> {
        var now = T0
        val probe = AmygdalaProbe(account = account, clock = { now })
        val marks = mutableListOf<ProbeMark>()
        fun fire(input: ProbeInput) {
            marks.add(probe.probe(input))
        }
        repeat(6) { fire(stimulus) }
        fire(stimulus)
        now += 1
        fire(ProbeInput(row(tool = STIMULUS_TOOL, status = TraceStatus.FAILED), text = ""))
        fire(stimulus)
        now += SENSITIZE_DAYS_MS + 1
        fire(stimulus)
        return marks
    }

    /** 性能夹具：文本/工具/事故/快耗时四类轮转（含 675 调用与状态变化的真实最重路径）。 */
    private fun mixedStream(): List<ProbeInput> = List(4) { i ->
        when (i) {
            0 -> ProbeInput(row(tool = STIMULUS_TOOL), text = STIMULUS_TEXT)
            1 -> ProbeInput(row(tool = "card"), text = "今天天气不错")
            2 -> ProbeInput(row(tool = "http", status = TraceStatus.FAILED), text = "")
            else -> ProbeInput(row(tool = "write_file", durationMs = 3L), text = "/data/bin")
        }
    }

    /** 账坏夹具：append 即抛，entries 永空——快探必须照常出读数（判据⑥）。 */
    private class ThrowingAccount : ProbeAccount {
        override fun append(entry: AccountEntry) {
            error("探针账坏了")
        }

        override fun entries(): List<AccountEntry> = emptyList()
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val STIMULUS_TOOL = "delete_path"
        const val STIMULUS_TEXT = "/system/app.db 删除全部数据"

        /** 刺激底数：threat=1、novelty=2、标签 2 个（与主件公式同式同序，衰减/回默认断言用）。 */
        const val EXPECTED_BASE = 0.40 * 1 / 4.0 + 0.15 * 2 / 2.0 + 0.15 * 2

        /** 主件常量的测试镜像（消费钉用；改主件常数必须同批改这里）。 */
        const val BASE_THREAT = 0.40
        const val BASE_NOVELTY = 0.15
        const val THREAT_MAX = 4.0
        const val NOVELTY_MAX = 2.0

        /** 与主件同口径：敏化 3 天（毫秒）。 */
        const val SENSITIZE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

        const val WARMUP = 300
        const val SAMPLES = 1000
        val TRIPLE1_EVIDENCE = listOf(
            "habituationLowersMarkRateAndLedgersDesensitize",
            "freshProbeKeepsMarkingProvesHabituationCausesTheDrop",
            "accidentSensitizesThenDecaysByClockFixture",
        )
    }
}
