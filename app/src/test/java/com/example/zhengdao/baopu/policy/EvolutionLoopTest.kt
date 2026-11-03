// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十六批 · 进化闭环闸——行为层测试。
//
// 判据逐条对位（派工单-376）：
//   ① 三触发各有正例/负例（没据不许触发）；
//   ② 四条验证任一不过 ⇒ 状态回滚 ＋ 账行记"失败＋原因"（四条各一个负例，缺一即失败不是口号）；
//   ③ 不可进化条被尝试修改 ⇒ 拒（闸面明拒＋310 构造期结构拦，两层各有测试）；
//   ④ 不碰 RunEngine 主循环（源级钉）＋回滚走 310 的写口不另起第二套（源级钉）。
//
// fixture 口径照 EpochTest：锚点全用 HARMLESS（不在清单上），周一基准 2026-10-26，不读系统时钟。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.constitution.GateIo
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapItem
import com.example.zhengdao.baopu.verify.GapKind
import com.example.zhengdao.baopu.verify.GapSource
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DAY_MS = 24L * 3_600_000L
private const val HOUR_MS = 3_600_000L

/** 2026-10-26 是本单的周一；测试拿它当纪元基准，不读系统时钟（照 310/364 口径）。 */
private val BASE_MONDAY: LocalDate = LocalDate.of(2026, 10, 26)

private fun weekStart(weeks: Long): Long = BASE_MONDAY.plusWeeks(weeks).toEpochDay() * DAY_MS

/** fixture 锚点：不在不可进化清单上，改它合法。 */
private const val HARMLESS = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor#floorOf"

private const val RULING = "御批 376-1"

class EvolutionLoopTest {

    @Before
    fun resetAll() {
        EpochRuntime.reset()
        EpochRegistry.reset()
        EvolutionLoop.reset()
    }

    // ── 造数据的小工具 ────────────────────────────────────────────────

    private fun entry(id: String, target: String = HARMLESS): CriterionEntry =
        CriterionEntry(id = id, description = "判据 $id（fixture，非御案数据）", target = target, admittedInEpoch = 0L)

    private fun snap(atMs: Long, vararg ids: String): CriteriaSnapshot =
        CriteriaSnapshot.of(epoch = EpochId.of(atMs), seq = 1L, criteria = ids.map { entry(it) })

    private fun misRow(
        atMs: Long,
        key: String = "over_steps",
        source: MisjudgmentSource = MisjudgmentSource.DEVIATION_LEDGER,
        ref: String = "dev:run-1",
    ): MisjudgmentRow = MisjudgmentRow(source = source, criterionKey = key, atMs = atMs, ref = ref)

    /** 触发 a 的正例现场：同一判据 24h 内 3 行，出处各不相同。 */
    private fun misTrigger(nowMs: Long, key: String = "over_steps"): MisjudgmentTrigger = MisjudgmentTrigger(
        listOf(
            misRow(nowMs, key, ref = "dev:r1"),
            misRow(nowMs - HOUR_MS, key, ref = "dev:r2"),
            misRow(nowMs - 2 * HOUR_MS, key, ref = "dev:r3"),
        ),
    )

    private fun gap(attempts: Int): GapTrigger = GapTrigger(
        listOf(
            GapItem(
                source = GapSource.RIGHT,
                kind = GapKind.TOOL_MISUSE,
                target = "plan:1",
                evidence = listOf("账行:run-1"),
                minimalFix = "紧急卡走人批入口",
                attempts = attempts,
            ),
        ),
    )

    private fun order(ref: String = "人令 376-9"): HumanOrderTrigger =
        HumanOrderTrigger(orderRef = ref, instruction = "把这类误判的判据收紧")

    private val allGreen = EvolutionProof(
        unitTests = GateRun(ran = true, total = 8445, failed = 0),
        regressionSet = GateRun(ran = true, total = 50, failed = 0),
        anchorSet = AnchorHold(total = 50, mismatched = 0),
        detekt = DetektDebt(before = 357, after = 357),
    )

    /** 让第 0 纪的表生效（起点现场，不走进化通道——它不是进化，是出厂状态）。 */
    private fun adoptWeek0() {
        val swapped = EpochRuntime.swap(snap(weekStart(0), "K1", "K2"), weekStart(0))
        assertTrue("现场没造对：310 的 swap 该成，实测「${swapped.reason}」", swapped.swapped)
    }

    private val baseSnapshot: CriteriaSnapshot get() = snap(weekStart(0), "K1", "K2")

    // ── 判据①：触发 a 误判（正例＋五个负例） ────────────────────────────

    @Test
    fun `触发a正例_同一判据24h内3次误判_许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(misTrigger(now), now)
        assertTrue("3 次同判据误判该成立：${gate.reason}", gate.allowed)
        assertTrue(gate.basis.contains("over_steps"))
    }

    @Test
    fun `触发a负例_只有2次_不许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(listOf(misRow(now, ref = "dev:r1"), misRow(now - HOUR_MS, ref = "dev:r2"))),
            now,
        )
        assertFalse(gate.allowed)
        assertTrue(gate.reason.contains("不足 3 次"))
    }

    @Test
    fun `触发a负例_3行但跨出24h窗_不许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(
                listOf(
                    misRow(now, ref = "dev:r1"),
                    misRow(now - 25 * HOUR_MS, ref = "dev:r2"),
                    misRow(now - 26 * HOUR_MS, ref = "dev:r3"),
                ),
            ),
            now,
        )
        assertFalse("窗外那行不该数进来：${gate.reason}", gate.allowed)
    }

    @Test
    fun `触发a负例_不同判据2加1_不许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(
                listOf(
                    misRow(now, key = "timeout", ref = "dev:r1"),
                    misRow(now - HOUR_MS, key = "timeout", ref = "dev:r2"),
                    misRow(now - 2 * HOUR_MS, key = "over_steps", ref = "dev:r3"),
                ),
            ),
            now,
        )
        assertFalse(gate.allowed)
    }

    @Test
    fun `触发a负例_同一条账行读两遍只数一次_不许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(
                listOf(misRow(now, ref = "dev:r1"), misRow(now, ref = "dev:r1"), misRow(now - HOUR_MS, ref = "dev:r2")),
            ),
            now,
        )
        assertFalse("同一 ref 数两遍＝刷证据：${gate.reason}", gate.allowed)
    }

    @Test
    fun `触发a负例_有行落在时刻之后_账钟不对不许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(
                listOf(
                    misRow(now, ref = "dev:r1"),
                    misRow(now - HOUR_MS, ref = "dev:r2"),
                    misRow(now + HOUR_MS, ref = "dev:r3"),
                ),
            ),
            now,
        )
        assertFalse(gate.allowed)
        assertTrue(gate.reason.contains("账钟不对"))
    }

    @Test
    fun `触发a两本账合数_偏差账与退回记录同键3行_许提`() {
        val now = weekStart(1)
        val gate = EvolutionLoop.canPropose(
            MisjudgmentTrigger(
                listOf(
                    misRow(now, source = MisjudgmentSource.DEVIATION_LEDGER, ref = "dev:r1"),
                    misRow(now - HOUR_MS, source = MisjudgmentSource.RETURN_RECORD, ref = "cand:9"),
                    misRow(now - 2 * HOUR_MS, source = MisjudgmentSource.DEVIATION_LEDGER, ref = "dev:r3"),
                ),
            ),
            now,
        )
        assertTrue(gate.allowed)
    }

    @Test
    fun `触发a的行缺出处_根本建不出来`() {
        assertThrows(IllegalArgumentException::class.java) {
            MisjudgmentRow(source = MisjudgmentSource.DEVIATION_LEDGER, criterionKey = "timeout", atMs = 1L, ref = " ")
        }
    }

    // ── 判据①：触发 b 人令 ───────────────────────────────────────────

    @Test
    fun `触发b正例_人令编号与原话俱全_许提`() {
        val gate = EvolutionLoop.canPropose(order(), weekStart(1))
        assertTrue(gate.allowed)
        assertTrue(gate.basis.contains("人令 376-9"))
    }

    @Test
    fun `触发b负例_没编号或没原话_根本建不出来`() {
        assertThrows(IllegalArgumentException::class.java) {
            HumanOrderTrigger(orderRef = " ", instruction = "收紧判据")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HumanOrderTrigger(orderRef = "人令 376-9", instruction = "")
        }
    }

    // ── 判据①：触发 c 缺口（N 用现值交人线） ───────────────────────────

    @Test
    fun `触发c正例_缺口到现值交人线_许提`() {
        val gate = EvolutionLoop.canPropose(gap(GapFeedback.HAND_OVER_ATTEMPTS), weekStart(1))
        assertTrue("到线 ${GapFeedback.HAND_OVER_ATTEMPTS} 该成立：${gate.reason}", gate.allowed)
    }

    @Test
    fun `触发c负例_差一次不到线_不许提`() {
        val gate = EvolutionLoop.canPropose(gap(GapFeedback.HAND_OVER_ATTEMPTS - 1), weekStart(1))
        assertFalse("N 必须就是现值交人线：${gate.reason}", gate.allowed)
        assertTrue(gate.reason.contains("交人线"))
    }

    @Test
    fun `触发c负例_缺口板是空的_不许提`() {
        val gate = EvolutionLoop.canPropose(GapTrigger(emptyList()), weekStart(1))
        assertFalse(gate.allowed)
    }

    // ── 硬规矩：没据不许提进化（apply 面再拦一道） ──────────────────────

    @Test
    fun `没据不许提进化_apply拒且一字未动`() {
        adoptWeek0()
        val now = weekStart(1)
        val weak = MisjudgmentTrigger(listOf(misRow(now, ref = "dev:r1"), misRow(now - HOUR_MS, ref = "dev:r2")))
        val r = EvolutionLoop.apply(weak, snap(now, "K1", "K3"), RULING, now)
        assertFalse("没据不许换表：${r.reason}", r.accepted)
        assertEquals("拒了就不许动 310", baseSnapshot, EpochRuntime.currentSnapshot())
        assertNull(EvolutionLoop.pendingReadout())
        assertTrue(EvolutionLoop.attemptLedger().isEmpty())
    }

    @Test
    fun `apply没御批编号_拒`() {
        adoptWeek0()
        val now = weekStart(1)
        val r = EvolutionLoop.apply(order(), snap(now, "K1", "K3"), " ", now)
        assertFalse(r.accepted)
        assertTrue(r.reason.contains("编号"))
    }

    // ── 判据③：不可进化清单永不动 ────────────────────────────────────

    @Test
    fun `判据③_不可进化条被尝试修改_闸面明拒`() {
        val hardAnchor = "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection#StillAsk.DESTRUCTIVE"
        val reason = EvolutionLoop.immutableReject(listOf(entry("K1"), entry("K9", target = hardAnchor)))
        assertTrue("借进化通道改硬闸必须拒", reason != null && reason.contains("不可进化"))
        assertTrue(reason!!.contains("只有用户亲手改"))
        assertNull("不撞清单要放行", EvolutionLoop.immutableReject(listOf(entry("K1"))))
    }

    @Test
    fun `判据③_310构造期结构拦_第二道仍在`() {
        val hardAnchor = "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection#StillAsk.DESTRUCTIVE"
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaSnapshot.of(
                epoch = EpochId.of(weekStart(1)),
                seq = 1L,
                criteria = listOf(entry("K1"), entry("K9", target = hardAnchor)),
            )
        }
    }

    @Test
    fun `空判据表不算进化_apply拒`() {
        adoptWeek0()
        val now = weekStart(1)
        val empty = CriteriaSnapshot.of(epoch = EpochId.of(now), seq = 1L, criteria = emptyList())
        val r = EvolutionLoop.apply(order(), empty, RULING, now)
        assertFalse(r.accepted)
        assertTrue(r.reason.contains("空判据表"))
    }

    // ── 判据②：四条全绿才算成 ────────────────────────────────────────

    @Test
    fun `四条全绿_进化成立_纪元册落账`() {
        adoptWeek0()
        val now = weekStart(1)
        val applied = EvolutionLoop.apply(misTrigger(now), snap(now, "K1", "K3"), RULING, now)
        assertTrue("现场没造对：${applied.reason}", applied.accepted)
        val outcome = EvolutionLoop.verify(allGreen, now)
        assertTrue("全绿该成：${outcome.reason}", outcome.passed)
        assertFalse(outcome.rolledBack)
        assertEquals("新表此刻生效", snap(now, "K1", "K3"), EpochRuntime.currentSnapshot())
        assertEquals(
            "验证通过的纪要进册（纪元号与 310 周历同源，不许硬编码）",
            Epoch.idOf(EpochId.of(weekStart(1))),
            EpochRegistry.epochOf(Epoch.idOf(EpochId.of(weekStart(1))))?.id,
        )
        val rows = EvolutionLoop.attemptLedger()
        assertEquals(1, rows.size)
        assertEquals(EvolutionAttemptRow.VERDICT_PASS, rows[0].verdict)
        assertFalse(rows[0].rolledBack)
        assertEquals(EvolutionAttemptRow.TRIGGER_MISJUDGMENT, rows[0].triggerKind)
        assertTrue(rows[0].wire().contains("通过"))
    }

    @Test
    fun `判据②负例_全量单测红_回滚`() = rollbackOnRed(
        proof = allGreen.copy(unitTests = GateRun(ran = true, total = 8445, failed = 1)),
        marker = "全量单测",
    )

    @Test
    fun `判据②负例_回归集没跑_不算绿_回滚`() = rollbackOnRed(
        proof = allGreen.copy(regressionSet = GateRun(ran = false, total = 0, failed = 0)),
        marker = "问题回归集",
    )

    @Test
    fun `判据②负例_锚定集掉一条_回滚`() = rollbackOnRed(
        proof = allGreen.copy(anchorSet = AnchorHold(total = 50, mismatched = 1)),
        marker = "锚定集",
    )

    @Test
    fun `判据②负例_detekt新增债_回滚`() = rollbackOnRed(
        proof = allGreen.copy(detekt = DetektDebt(before = 357, after = 358)),
        marker = "detekt",
    )

    /** 判据②的公共负例骨架：换表成功 → 验证红 ⇒ 回滚＋账行"失败·已回滚＋原因"＋册不收。 */
    private fun rollbackOnRed(proof: EvolutionProof, marker: String) {
        adoptWeek0()
        val now = weekStart(1)
        val applied = EvolutionLoop.apply(misTrigger(now), snap(now, "K1", "K3"), RULING, now)
        assertTrue("现场没造对：${applied.reason}", applied.accepted)
        val outcome = EvolutionLoop.verify(proof, now)
        assertFalse("缺一即失败：${outcome.reason}", outcome.passed)
        assertTrue(outcome.rolledBack)
        assertTrue(outcome.reason.contains(marker))
        assertEquals("状态退回上一快照，不许半进化", baseSnapshot, EpochRuntime.currentSnapshot())
        assertEquals("310 的三态账记已回滚", EpochState.ROLLED_BACK, EpochRuntime.ledgerState())
        assertNull("失败的纪不进册", EpochRegistry.epochOf(Epoch.idOf(EpochId.of(weekStart(1)))))
        val rows = EvolutionLoop.attemptLedger()
        assertEquals(1, rows.size)
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, rows[0].verdict)
        assertTrue(rows[0].rolledBack)
        assertTrue("原因要能指认：${rows[0].reason}", rows[0].reason.contains(marker))
        // 收场干净：没有待验的了，再 verify 也不许有第二个结论
        val again = EvolutionLoop.verify(allGreen, now)
        assertFalse(again.passed)
        assertTrue(again.reason.contains("没有待验"))
        assertEquals("重复 verify 不许再落账", 1, EvolutionLoop.attemptLedger().size)
    }

    @Test
    fun `首张表验证失败_reset回无表_账行仍记已回滚`() {
        val now = weekStart(0)
        val applied = EvolutionLoop.apply(order(), snap(now, "K1", "K2"), RULING, now)
        assertTrue("现场没造对：${applied.reason}", applied.accepted)
        val outcome = EvolutionLoop.verify(
            allGreen.copy(anchorSet = AnchorHold(total = 50, mismatched = 2)),
            now,
        )
        assertFalse(outcome.passed)
        assertTrue(outcome.rolledBack)
        assertNull("首张表失败＝回到无表", EpochRuntime.currentSnapshot())
        val rows = EvolutionLoop.attemptLedger()
        assertEquals(1, rows.size)
        assertTrue(rows[0].rolledBack)
        assertTrue(rows[0].reason.contains("锚定集"))
    }

    @Test
    fun `空集的全绿是假绿_ran为真却0例_建不出来`() {
        assertThrows(IllegalArgumentException::class.java) { GateRun(ran = true, total = 0, failed = 0) }
        assertThrows(IllegalArgumentException::class.java) { GateRun(ran = true, total = 10, failed = 11) }
    }

    // ── 中间态纪律：不许叠加、不许无中生有地 verify ──────────────────────

    @Test
    fun `上一场还没验完_不许叠加第二场`() {
        adoptWeek0()
        val now = weekStart(1)
        assertTrue(EvolutionLoop.apply(misTrigger(now), snap(now, "K1", "K3"), RULING, now).accepted)
        val second = EvolutionLoop.apply(order(), snap(weekStart(2), "K1", "K4"), "御批 376-2", weekStart(2))
        assertFalse("叠加＝半进化的缝：${second.reason}", second.accepted)
        assertTrue(second.reason.contains("还没验完"))
        assertTrue("挂起的还是第一场", EvolutionLoop.pendingReadout()!!.contains("MISJUDGMENT"))
    }

    @Test
    fun `没有待验的进化_verify拒且不落账`() {
        val outcome = EvolutionLoop.verify(allGreen, weekStart(1))
        assertFalse(outcome.passed)
        assertTrue(EvolutionLoop.attemptLedger().isEmpty())
    }

    @Test
    fun `失败之后还能再提_账只增不改`() {
        adoptWeek0()
        val w1 = weekStart(1)
        assertTrue(EvolutionLoop.apply(misTrigger(w1), snap(w1, "K1", "K3"), RULING, w1).accepted)
        val failed = EvolutionLoop.verify(allGreen.copy(unitTests = GateRun(ran = true, total = 8445, failed = 1)), w1)
        assertFalse(failed.passed)
        val firstRow: String = EvolutionLoop.attemptLedger().single().wire()
        val w2 = weekStart(2)
        val second = EvolutionLoop.apply(order(), snap(w2, "K1", "K4"), "御批 376-2", w2)
        assertTrue("回滚完要能重新走闸：${second.reason}", second.accepted)
        val passed = EvolutionLoop.verify(allGreen, w2)
        assertTrue(passed.passed)
        val rows = EvolutionLoop.attemptLedger()
        assertEquals(2, rows.size)
        assertEquals("旧账行一字不改", firstRow, rows[0].wire())
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, rows[0].verdict)
        assertEquals(EvolutionAttemptRow.VERDICT_PASS, rows[1].verdict)
    }

    // ── 账行形制：闭集、七列、竖线不许撑破列 ────────────────────────────

    @Test
    fun `账行_触发名与结论是闭集_不许第四态`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionAttemptRow("HUNCH", "依据", RULING, "通过", false, "原因", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionAttemptRow("GAP", "依据", RULING, "再讨论", false, "原因", 1L)
        }
    }

    @Test
    fun `账行_七列_内容里的竖线不撑破列`() {
        val row = EvolutionAttemptRow("GAP", "依据|带竖线", RULING, "失败", true, "原因|也带", 7L)
        val cols = row.wire().split("|")
        assertEquals(7, cols.size)
        assertFalse(cols.any { it.isEmpty() })
        assertTrue(row.wire().contains("已回滚"))
    }

    // ── 判据④：源级钉——不碰 RunEngine、回滚走 310 的写口 ───────────────

    @Test
    fun `判据④_不碰RunEngine主循环_源级`() {
        // 剥注释再查（照 EpochTest 口径）：话术里提到 RunEngine 不算碰，代码里出现才算。
        assertFalse(GateIo.stripComments(evolutionFileText()).contains("RunEngine"))
    }

    @Test
    fun `判据④_回滚与reset只走310的写口_不另起第二套`() {
        val code = GateIo.stripComments(evolutionFileText())
        assertTrue("失败回滚必须走 310 的 rollbackToPrevious", code.contains("EpochRuntime.rollbackToPrevious()"))
        assertTrue("首张表失败走 310 的 reset", code.contains("EpochRuntime.reset()"))
        assertTrue("通过必须经 364 的纪元册落账", code.contains("EpochRegistry.append("))
    }

    private fun evolutionFileText(): String {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        return File(root, "java/com/example/zhengdao/baopu/policy/EvolutionLoop.kt").readText(Charsets.UTF_8)
    }
}
