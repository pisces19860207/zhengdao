// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 472 赔付四件的判据与负例（派工单 §一 ＋ §五 判据①②）。
//
// 判据①：不可恢复损坏 ⇒ 出「永久损失清单」＋ ≤5 行说明；
// 判据②：同型错误第二次 ⇒ 必须有一条**机检**的门禁/规矩（ERRATA 或制度总账，光有文字不算）。
// 负例逐条：
//   P1 判了「赔不了」却没清单
//   P2 说明写超 6 行却被悄悄截回 5 行（＝把"写超了"这件事一起截掉）
//   P3 有还原路没试过就喊赔不了
//   P4 留证四段少一段却报"已留证"
//   P5 同型第二次仍只有文字规矩（没升级成机检）
//   P6 四件缺一仍去落账（半条赔付没有写路径）
//   P7 清单里混进半条（缺大小或缺最后修改）
package com.example.zhengdao.baopu.consequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequenceCompensationGateTest {

    private val now = 1_760_000_000_000L
    private val trail = ReplayTrail(
        TrailStage.entries.mapIndexed { i, s -> TrailStep(s, now + i, "${s.cn}留痕") },
    )
    private val rule = Prevention(PreventionTarget.RULES_LEDGER, "T-99", "tools/check-rules-ledger.py")
    private val proseOnly = Prevention(PreventionTarget.ERRATA, "E-900", "以后注意先核对台账")

    private fun loss(n: Int): List<LossItem> =
        (0 until n).map { LossItem("/sdcard/文档/$it.md", 1024L * (it + 1), now - it * 60_000L) }

    private fun facts(
        restored: List<String> = emptyList(),
        lost: List<LossItem> = emptyList(),
        routesKnown: Boolean = true,
        routesTried: Boolean = true,
        occurrence: Int = 1,
        prevention: Prevention? = rule,
        trailSteps: ReplayTrail = trail,
    ) = DamageFacts(
        opId = "op-506",
        tool = "file_delete",
        whatHappened = "删掉了 3 份文档",
        why = "路径判定漏了一层目录",
        nextStepForUser = "去备份目录捞，捞不到我出清单",
        sameTypeOccurrence = occurrence,
        restoreRoutesKnown = routesKnown,
        restoreRoutesTried = routesTried,
        restoredPaths = restored,
        lostItems = lost,
        trail = trailSteps,
        prevention = prevention,
    )

    // ── 判据① 三态与清单 ──────────────────────────────────────────────────

    @Test
    fun `判据1a_能还原就还原_部分还原如实说部分`() {
        assertEquals(RecoveryVerdict.RESTORED, CompensationPlanner.verdictOf(facts(restored = listOf("/备份/a.md"))))
        val part = facts(restored = listOf("/备份/a.md"), lost = loss(2))
        assertEquals(RecoveryVerdict.PARTIAL, CompensationPlanner.verdictOf(part))
        // 部分还原不配「赔不了」那一行，但清单也不该出现（还原回来的是 a，丢的是那 2 项——仍要报）
        val c = CompensationPlanner.plan(part)!!
        assertEquals(2, c.lossList.size)
        assertTrue("部分还原但还剩两项 ⇒ §四 仍要报人（点的是「赔不了／永久损失」两件事）", c.mustReportToHuman)
        assertFalse("全还原才不许报人", CompensationPlanner.plan(facts(restored = listOf("/a")))!!.mustReportToHuman)
    }

    @Test
    fun `判据1b_赔不了必须直说且带清单`() {
        val c = CompensationPlanner.plan(facts(lost = loss(3)))!!
        assertEquals(RecoveryVerdict.UNRECOVERABLE, c.verdict)
        assertEquals("赔不了", RecoveryVerdict.CANNOT_PAY_MARK)
        assertTrue(c.note.lines.first().startsWith("赔不了"))
        assertEquals(3, c.lossList.size)
        assertTrue(c.complete)
        assertTrue("§四：只有赔不了／永久损失才报人", c.mustReportToHuman)
    }

    @Test
    fun `负例P1_判了赔不了却没清单就报缺陷`() {
        val c = CompensationPlanner.plan(facts(restored = emptyList(), lost = emptyList()))
        assertNull("事实不齐（既无原本也无清单）根本开不出赔付", c)
        val half = Compensation("op", "file_delete", RecoveryVerdict.UNRECOVERABLE, PlainNote(listOf("赔不了：删了")), emptyList(), rule, trail)
        assertTrue(half.defects().any { it.contains("永久损失清单") })
    }

    @Test
    fun `负例P7_清单里混进半条`() {
        val bad = listOf(LossItem("", 10L, now), LossItem("/a.md", -1L, now), LossItem("/b.md", 10L, 0L))
        bad.forEach { assertFalse("三列缺一即半条：$it", it.isWellFormed()) }
        val c = Compensation("op", "file_delete", RecoveryVerdict.UNRECOVERABLE, PlainNote(listOf("赔不了：删了")), bad, rule, trail)
        assertTrue(c.defects().any { it.contains("半条") })
    }

    @Test
    fun `判据1c_清单每行三列齐`() {
        val lines = loss(2).map { it.line("2026-11-07 00:00") }
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("1024 B") && lines.first().contains("最后修改"))
    }

    // ── 判据① 的说明：≤5 行 ───────────────────────────────────────────────

    @Test
    fun `判据1d_说明三栏齐且不超五行`() {
        val n = CompensationPlanner.noteOf(RecoveryVerdict.UNRECOVERABLE, facts(lost = loss(3)))
        assertTrue(n.lines.size <= PlainNote.MAX_LINES)
        assertFalse(n.tooLong)
        assertFalse(n.blank)
        assertTrue(n.lines.any { it.startsWith("为什么：") })
        assertTrue(n.lines.any { it.startsWith("你现在该做的：") })
    }

    @Test
    fun `负例P2_写超了要被报出来_不许悄悄截断`() {
        val long = PlainNote((1..6).map { "第 $it 行" })
        assertTrue(long.tooLong)
        val c = Compensation("op", "file_delete", RecoveryVerdict.PARTIAL, long, emptyList(), rule, trail)
        assertTrue(c.defects().any { it.contains("② 说明超了") })
        assertEquals("说明本体一个字不许被砍", 6, c.note.lines.size)
    }

    @Test
    fun `负例_判了赔不了但第一行没直说`() {
        val soft = PlainNote(listOf("可能无法完全恢复：删了 3 份", "为什么：漏判", "你现在该做的：等我一小时"))
        val c = Compensation("op", "file_delete", RecoveryVerdict.UNRECOVERABLE, soft, loss(3), rule, trail)
        assertTrue(c.defects().any { it.contains("却没在第一行直说") })
    }

    // ── ③ 防再发 ＋ 判据② 第二次要机检 ────────────────────────────────────

    @Test
    fun `判据2_同型第二次必须有可机检的条目`() {
        assertTrue(rule.isMachineChecked)
        assertFalse("纯文字不算机检", proseOnly.isMachineChecked)
        assertTrue(PreventionRules.sufficientAt(1, rule))
        assertTrue(PreventionRules.sufficientAt(2, rule))
        assertFalse(PreventionRules.sufficientAt(2, proseOnly))
        assertFalse(PreventionRules.sufficientAt(2, null))
        val second = CompensationPlanner.plan(facts(restored = listOf("/备份/a.md"), occurrence = 2, prevention = proseOnly))!!
        assertFalse("第二次仍只有文字 ⇒ 这条赔付不完整", second.complete)
        assertTrue(second.defects().any { it.contains("机检") })
    }

    @Test
    fun `判据2b_坑账与总账都认_自造落点不认`() {
        assertEquals(2, PreventionTarget.entries.size)
        assertNull(PreventionTarget.ofWire("my_notes"))
        assertNull(RecoveryVerdict.ofWire("kinda_restored"))
        val noId = Compensation("op", "file_delete", RecoveryVerdict.PARTIAL, PlainNote(listOf("部分还原：删了")), emptyList(), Prevention(PreventionTarget.ERRATA, ""), trail)
        assertTrue(noId.defects().any { it.contains("没编号") })
    }

    @Test
    fun `负例P4_留证四段少一段就点名少哪段`() {
        val three = ReplayTrail(trail.steps.filterNot { it.stage == TrailStage.VERIFY })
        assertEquals(listOf(TrailStage.VERIFY.cn), three.missingStages())
        assertFalse(three.replayable)
        val c = CompensationPlanner.plan(facts(restored = listOf("/a"), trailSteps = three))!!
        assertTrue(c.defects().any { it.contains("④ 留证缺阶段") })
        assertEquals(4, trail.replayLines().size)
        // 倒着塞进去也要按时刻正着排出来——装配层直接打这张表靠的就是这一条。
        val reversed = ReplayTrail(trail.steps.reversed())
        assertEquals(
            "回放按时刻排，不按塞进来的序",
            TrailStage.entries.map { "[${it.cn}] ${now + it.ordinal} ${it.cn}留痕" },
            reversed.replayLines(),
        )
    }

    @Test
    fun `负例P3_有路没试就想喊赔不了被拒`() {
        val r = CompensationPlanner.refusalOf(facts(routesKnown = true, routesTried = false, lost = loss(3)))
        assertNotNull(r)
        assertTrue(r!!.contains("先试再判"))
        assertNull(CompensationPlanner.plan(facts(routesKnown = true, routesTried = false, lost = loss(3))))
    }

    @Test
    fun `判据_没伤到东西不许开赔付`() {
        assertNotNull("既没还原本也没损失清单 ⇒ 事实不齐", CompensationPlanner.refusalOf(facts()))
        assertNull(CompensationPlanner.plan(facts()))
        val noId = facts(restored = listOf("/a")).copy(opId = "")
        assertTrue(CompensationPlanner.refusalOf(noId)!!.contains("opId"))
    }

    // ── 负例P6 半条赔付没有落盘路径 ────────────────────────────────────────

    @Test
    fun `负例P6_四件缺一不落账`() {
        val good = CompensationPlanner.plan(facts(lost = loss(3)))!!
        assertNotNull(ConsequenceLedger.draftOf(good, now))
        val broken = good.copy(prevention = null)
        assertTrue(broken.defects().isNotEmpty())
        assertNull("半条赔付在代码里没有写路径", ConsequenceLedger.draftOf(broken, now))
        val d = ConsequenceLedger.draftOf(good, now)!!
        assertEquals("compensation", d.kind.wire)
        assertTrue(d.evidence.startsWith("${ConsequenceEvidence.COMPENSATION}op-506:unrecoverable"))
        assertTrue("机检过的那一条要看得见", d.evidence.endsWith("·机检"))
        assertEquals(false, d.reversible)
        val lines = ConsequenceLedger.lossLinesOf(good.copy(lossList = loss(2))) { "2026-11-07 00:00" }
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("/sdcard/文档/0.md"))
    }
}
