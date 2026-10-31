// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十批 · 开机自检的判据与负例。
//
// 为什么全部能纯 JVM 跑：开检是**判定层**，四个部位的读数由测试手搓交进来，落账走假出口。
// 真机那一段（开机那两秒到底量到什么）等接线批在机上补，本文件钉的是"给什么读数出什么结论"。
//
// 五条判据（派工单原文编号）：
//   ① 开机 ≤2 秒产出简报且不弹窗
//   ② 四部位各一条结论（不可用要原因码）
//   ③ 三类处置各一测
//   ④ 必须人的事**弹卡次数＝0**（断言）
//   ⑤ 阻断性**弹且只弹一次**（造"账本不可写"）
// 四条负例：开机弹一片卡／不可用能力仍"假装能做"／自修失败静默／阻断性也静默。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.agent.QuietMode
import com.example.zhengdao.baopu.agent.QuietState
import com.example.zhengdao.baopu.agent.QuietVerdict
import com.example.zhengdao.baopu.agent.Rollback
import com.example.zhengdao.baopu.scout.Capability
import com.example.zhengdao.baopu.scout.Scout
import com.example.zhengdao.baopu.scout.ScoutDevice
import com.example.zhengdao.baopu.scout.ScoutRecord
import com.example.zhengdao.baopu.scout.ScoutSheet
import com.example.zhengdao.baopu.scout.ScoutState
import com.example.zhengdao.baopu.scout.ScoutTrigger
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假出口：①修／②自决单／③未办成单／④弹一次 各记一笔，测试按笔数断言。 */
private class FakeSinks(var repairOk: Boolean = true) : BootSinks {
    val repaired = ArrayList<BootFinding>()
    val ruled = ArrayList<String>()
    val items = ArrayList<UnfinishedItem>()
    val emitted = ArrayList<BootInterrupt>()
    var shown = HashSet<String>()

    override fun repair(finding: BootFinding): Boolean {
        repaired += finding
        return repairOk
    }

    override fun noteSelfRuling(finding: BootFinding, reason: String): Boolean {
        assertTrue("自决单必须带那句事实：${finding.wire}", reason.isNotBlank())
        ruled += finding.wire
        return true
    }

    override fun recordUnfinished(item: UnfinishedItem): Boolean {
        items += item
        return true
    }

    override fun interruptShown(key: String): Boolean = key in shown

    override fun emitInterrupt(key: String, text: String): Boolean {
        emitted += BootInterrupt(key, text)
        shown += key
        return true
    }
}

class BootCheckTest {

    // ── 判据①：≤2 秒出简报、不弹窗 ──────────────────────────────────────────

    @Test
    fun `判据①_两秒内出简报且不弹卡`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(allGreen(), sinks, nowMs = T0, elapsedMs = 1_500L)
        assertTrue("简报一行都没有，回灌什么？", report.brief.isNotEmpty())
        assertTrue("超了 2 秒预算：${report.elapsedMs}", report.withinBudget)
        assertEquals("常态一轮弹卡数不为 0", 0, report.cardCount)
        assertEquals(0, sinks.emitted.size)
        assertEquals("四部位全绿时不该落自决单", emptyList<String>(), sinks.ruled)
    }

    @Test
    fun `判据①_超预算不是失败_简报照出`() {
        val report = BootCheck.run(allGreen(), FakeSinks(), nowMs = T0, elapsedMs = 2_500L)
        assertFalse(report.withinBudget)
        assertTrue("超预算就不出简报＝把事实藏了", report.brief.isNotEmpty())
        assertTrue("简报要如实说超预算", report.brief.first().contains("超预算"))
    }

    @Test
    fun `判据①_简报不超十行`() {
        // 最坏一轮＝四类处置同轮都有（①修成两件、②两条自决、③一条未办成、④一枚阻断）：
        // 表头 1 ＋ 四部位 4 ＋ ①②③④各 1 ＝ 9 行。这是**结构上界**，不是"截到 10 行"的结果。
        val sinks = FakeSinks(repairOk = true)
        val readings = BootReadings(
            scout = report(
                listOf(record(Capability.CAMERA, ScoutState.DENIED, denials = 1)),
                sheetSaved = false,
            ),
            inspector = InspectorReading(workspaceReadable = false, ledger = LedgerProbe.DIR_MISSING),
            physician = listOf(organ("学习器", OrganState.NOT_WIRED, Disposition.DEFECT)),
            audit = audit(InspectVerdict.HIT, 2),
        )
        val out = BootCheck.run(readings, sinks, T0, 900L)
        assertEquals("四类处置都在这一轮", listOf(2, 2, 1, 1), listOf(sinks.repaired.size, sinks.ruled.size, sinks.items.size, out.cardCount))
        assertEquals(9, out.brief.size)
        assertTrue("上界要贴着派工单那条线留余量", out.brief.size <= BootLimits.MAX_BRIEF_LINES)
        assertTrue(out.brief.all { it.isNotBlank() })
    }

    // ── 判据②：四部位各一条结论，不可用要原因码 ─────────────────────────────

    @Test
    fun `判据②_四部位各一条结论且顺序稳定`() {
        val report = BootCheck.run(allGreen(), FakeSinks(), T0, 800L)
        assertEquals(4, report.verdicts.size)
        assertEquals(BootPart.entries.toList(), report.verdicts.map { it.part })
        assertTrue("每条结论都要有一句事实", report.verdicts.all { it.fact.isNotBlank() })
        assertEquals("原因码不许为空", 4, report.verdicts.count { it.code.isNotBlank() })
    }

    @Test
    fun `判据②_四个部位全缺数时原因码都在闭集里`() {
        val report = BootCheck.run(BootReadings(), FakeSinks(), T0, 500L)
        val codes = report.verdicts.map { it.code }
        assertEquals(listOf("scout_not_probed", "workspace_unreadable", "physician_not_run", "audit_no_data"), codes)
        assertTrue("缺数的部位一律判不可用", report.verdicts.none { it.usable })
        val wires = BootFinding.entries.map { it.wire }
        assertTrue("原因码必须是闭集里的键", codes.all { it == "ok" || it in wires })
    }

    @Test
    fun `判据②_结论不许挂到别的部位上`() {
        val bad = runCatching { PartVerdict(BootPart.SCOUT, BootFinding.AUDIT_GAP, "挂错了") }
        assertTrue("部位与结论的界得在构造期拦住：${bad.exceptionOrNull()}", bad.isFailure)
    }

    // ── 判据③：三类处置各一测 ───────────────────────────────────────────────

    @Test
    fun `判据③_可自恢复_自己修完不打扰`() {
        val sinks = FakeSinks(repairOk = true)
        // 只有①那一格坏，别的部位都给绿读数：这一轮除了简报里那一行事实，哪儿都不许留痕。
        val report = BootCheck.run(
            allGreen().copy(inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.DIR_MISSING)),
            sinks, T0, 600L
        )
        assertEquals(listOf(BootFinding.LEDGER_DIR_MISSING), sinks.repaired)
        assertEquals(listOf(BootFinding.LEDGER_DIR_MISSING), report.healed)
        assertTrue("修成了就不该有没修成的", report.healFailures.isEmpty())
        assertEquals("①修成了就不该留结论", null, report.verdicts.first { it.part == BootPart.INSPECTOR }.finding)
        assertEquals(0, report.cardCount)
        assertEquals("①不是②，不许落自决单", emptyList<String>(), sinks.ruled)
        assertTrue("①不是③，不许占未办成单", sinks.items.isEmpty())
        assertTrue("但简报要有一行事实说修过", report.brief.any { it.startsWith("①自修 1 项") })
    }

    @Test
    fun `判据③_可自决_出自决单不弹卡`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                scout = report(allGranted()),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.WRITABLE),
                physician = allActive(),
                audit = audit(InspectVerdict.HIT, 3),
            ),
            sinks, T0, 700L
        )
        assertEquals(listOf("audit_gap"), sinks.ruled)
        assertEquals(listOf(BootFinding.AUDIT_GAP), report.selfRulings)
        assertEquals("②可自决那一档弹卡了", 0, report.cardCount)
        assertTrue("②不进未办成单（那是③的出口）", sinks.items.isEmpty())
    }

    @Test
    fun `判据③_必须人_只留一条未办成单不弹卡`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                scout = report(
                    listOf(
                        record(Capability.CAMERA, ScoutState.DENIED, denials = 1),
                        record(Capability.STORAGE, ScoutState.REVOKED),
                    )
                ),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.WRITABLE),
                physician = allActive(),
                audit = audit(InspectVerdict.CLEAR),
            ),
            sinks, T0, 700L
        )
        assertEquals("必须人的事只留一条", 1, sinks.items.size)
        assertEquals(0, report.cardCount)
        val item = sinks.items.first()
        assertEquals("开机自检：打开「相机」权限", item.intent)
        assertEquals(UnfinishedReason.MISSING_PERMISSION, item.reason)
        assertEquals("boot:scout_permission_missing", item.reasonRef)
        assertTrue("缺权限是能补的，得给入口", item.fixable && item.fixHint.isNotBlank())
    }

    // ── 判据④：必须人的事弹卡次数＝0（断言） ────────────────────────────────

    @Test
    fun `判据④_除阻断那一档以外全表都不许弹卡`() {
        val interrupting = BootDisposition.entries.filter { it.interrupts }
        assertEquals("全表只许一格能弹卡（派工单④那个唯一例外）", 1, interrupting.size)
        assertEquals(BootDisposition.BLOCKING, interrupting.first())
        BootFinding.entries.forEach { f ->
            assertEquals("${f.wire} 不许被判成可弹卡", BootDisposition.BLOCKING == f.disposition, f.disposition.interrupts)
        }
    }

    @Test
    fun `判据④_必须人与自决同轮也不弹卡`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                scout = report(listOf(record(Capability.NOTIFICATIONS, ScoutState.DENIED, denials = 2))),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.WRITABLE),
                physician = listOf(organ("读数层", OrganState.NOT_WIRED, Disposition.DEFECT)),
                audit = audit(InspectVerdict.HIT, 1),
            ),
            sinks, T0, 900L
        )
        assertEquals(0, report.cardCount)
        assertEquals(1, sinks.items.size)
        assertEquals(2, sinks.ruled.size)
    }

    // ── 判据⑤：阻断性弹、且只弹一次（造"账本不可写"） ───────────────────────

    @Test
    fun `判据⑤_账本写不了弹一次并说清为什么`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                scout = report(allGranted()),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.NOT_WRITABLE),
                physician = allActive(),
                audit = audit(InspectVerdict.CLEAR),
            ),
            sinks, T0, 700L
        )
        assertEquals(1, report.cardCount)
        assertEquals("boot:ledger_unwritable", report.interrupts.first().key)
        val text = report.interrupts.first().text
        assertTrue("那一句要说清『我干不了活，因为 X』：$text", text.startsWith("我干不了活，因为"))
        assertTrue(text.contains("账本写不了"))
        assertTrue("要说清在哪个部位：$text", text.contains("勘验"))
        assertTrue("要带原因码：$text", text.contains("ledger_unwritable"))
    }

    @Test
    fun `判据⑤_同一次阻断不弹第二遍_但简报照写`() {
        val sinks = FakeSinks()
        val readings = BootReadings(
            scout = report(allGranted()),
            inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.NOT_WRITABLE),
            physician = allActive(),
            audit = audit(InspectVerdict.CLEAR),
        )
        assertEquals(1, BootCheck.run(readings, sinks, T0, 700L).cardCount)
        val second = BootCheck.run(readings, sinks, T0 + 60_000L, 700L)
        assertEquals("弹过的不弹第二遍", 0, second.cardCount)
        assertTrue(second.interrupts.isEmpty())
        assertEquals("一次开检一个事件键最多弹一枚", listOf("boot:ledger_unwritable"), sinks.emitted.map { it.key })
        assertTrue("事实不许因为不弹卡就从简报里消失", second.brief.any { it.contains("④阻断 1 项") })
        assertTrue("简报要说清这一遍没弹：${second.brief}", second.brief.any { it.contains("本轮弹 0 次") })
        assertNotNull(second.verdicts.first { it.part == BootPart.INSPECTOR }.finding)
    }

    @Test
    fun `判据⑤_同一部位两条阻断也只弹一枚`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                inspector = InspectorReading(workspaceReadable = false, ledger = LedgerProbe.NOT_WRITABLE)
            ),
            sinks, T0, 700L
        )
        assertEquals("读不了＋写不进同轮也只说一次", 1, report.cardCount)
        assertEquals("工作区读不了排在写不进之前（闭集顺序即稳定序）", "boot:workspace_unreadable", report.interrupts.first().key)
        assertEquals("结论位一条一个：第二条不许冒充第二个部位", 1, report.verdicts.count { it.disposition == BootDisposition.BLOCKING })
        assertTrue(report.brief.any { it.contains("④阻断 1 项") && it.contains("本轮弹 1 次") })
        // 没被选成结论的那条不许人间蒸发：部位那句事实把两件事都写上了
        assertTrue(report.brief.any { it.startsWith("勘验：") && it.contains("写不进") })
        assertEquals(listOf("boot:workspace_unreadable"), sinks.emitted.map { it.key })
    }

    // ── 负例①：开机弹一片卡 ─────────────────────────────────────────────────

    @Test
    fun `负例①_四部位全坏那一轮也只弹一枚`() {
        val sinks = FakeSinks(repairOk = false)
        val report = BootCheck.run(
            BootReadings(
                scout = report(
                    listOf(
                        record(Capability.STORAGE, ScoutState.DENIED, denials = 1),
                        record(Capability.ROOT, ScoutState.UNAVAILABLE, "本机没 root"),
                    )
                ),
                inspector = InspectorReading(workspaceReadable = false, ledger = LedgerProbe.NOT_WRITABLE),
                physician = listOf(organ("金吾卫", OrganState.NOT_WIRED, Disposition.DEFECT)),
                audit = audit(InspectVerdict.HIT, 5),
            ),
            sinks, T0, 1_200L
        )
        assertTrue("不可用的部位要如实标出来", report.verdicts.count { !it.usable } >= 1)
        assertEquals("开机弹一片卡＝本批要治的病", 1, report.cardCount)
        assertEquals(1, sinks.emitted.size)
        assertEquals("未办成单一轮只留一条", 1, sinks.items.size)
    }

    // ── 负例②：不可用的能力仍"假装能做" ─────────────────────────────────────

    @Test
    fun `负例②_没探过就不许写出能力数`() {
        val report = BootCheck.run(BootReadings(), FakeSinks(), T0, 400L)
        val scout = report.verdicts.first { it.part == BootPart.SCOUT }
        assertEquals(BootFinding.SCOUT_NOT_PROBED, scout.finding)
        assertFalse("没探过＝不可用", scout.usable)
        assertTrue("简报里要写明还没探过", scout.fact.contains("还没探过"))
        assertFalse("不许把没探过写成能做", scout.fact.contains("能用"))
    }

    @Test
    fun `负例②_本机做不了的那几项要在事实里点到数`() {
        val report = BootCheck.run(
            BootReadings(
                scout = report(
                    listOf(
                        record(Capability.CAMERA, ScoutState.GRANTED),
                        record(Capability.VIRTUALIZATION, ScoutState.UNAVAILABLE, "系统不支持虚拟化"),
                        record(Capability.ROOT, ScoutState.UNAVAILABLE, "本机没 root"),
                    )
                )
            ),
            FakeSinks(), T0, 400L
        )
        val line = report.verdicts.first { it.part == BootPart.SCOUT }.fact
        assertTrue("四态要各数各的：$line", line.contains("本机做不了 2"))
        assertTrue(line.contains("能用 1"))
    }

    @Test
    fun `负例②_不可用与没开权限是两件事不许混`() {
        val both = BootCheck.run(
            BootReadings(
                scout = report(
                    listOf(
                        record(Capability.ROOT, ScoutState.UNAVAILABLE, "本机没 root"),
                        record(Capability.CLIPBOARD, ScoutState.DENIED, denials = 1),
                    )
                )
            ),
            FakeSinks(), T0, 400L
        )
        val scout = both.verdicts.first { it.part == BootPart.SCOUT }
        assertEquals("能补的那条优先（做不了的不占人的手）", BootFinding.SCOUT_PERMISSION_MISSING, scout.finding)
        assertEquals(1, both.unfinished.size)
        assertTrue("做不了的那几项仍在事实句里", scout.fact.contains("本机做不了 1"))
    }

    // ── 负例③：自修失败静默 ─────────────────────────────────────────────────

    @Test
    fun `负例③_自修失败升格成必须人不许静默`() {
        val sinks = FakeSinks(repairOk = false)
        val report = BootCheck.run(
            BootReadings(inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.DIR_MISSING)),
            sinks, T0, 600L
        )
        val inspector = report.verdicts.first { it.part == BootPart.INSPECTOR }
        assertEquals(BootFinding.LEDGER_DIR_MISSING, inspector.finding)
        assertTrue("修不成必须升格", inspector.escalated)
        assertEquals(BootDisposition.NEEDS_HUMAN, inspector.disposition)
        assertEquals("静默＝一条都没留", 1, sinks.items.size)
        assertEquals(0, report.cardCount)
        assertEquals(UnfinishedReason.OTHER, sinks.items.first().reason)
        assertTrue("未办成单要说的是没修成，不是缺权限", sinks.items.first().reasonDetail.contains("台账目录"))
    }

    @Test
    fun `负例③_自修失败被更重的结论压住时也留得住`() {
        val sinks = FakeSinks(repairOk = false)
        val report = BootCheck.run(
            BootReadings(inspector = InspectorReading(workspaceReadable = false, ledger = LedgerProbe.DIR_MISSING)),
            sinks, T0, 600L
        )
        val inspector = report.verdicts.first { it.part == BootPart.INSPECTOR }
        assertEquals("结论给最重那一条", BootFinding.WORKSPACE_UNREADABLE, inspector.finding)
        assertEquals("没修成的那一条不许人间蒸发", listOf(BootFinding.LEDGER_DIR_MISSING), report.healFailures)
        assertEquals(1, report.cardCount)
        assertTrue(report.brief.any { it.contains("没修成 1") })
    }

    // ── 负例④：阻断性也静默 ─────────────────────────────────────────────────

    @Test
    fun `负例④_落点出问题的那三种读数都不许一声不响`() {
        // 别的部位一律给绿读数，这样"卡／未办成单／自决单"三个计数只可能来自勘验那一格。
        // `DIR_MISSING` 用修不成的出口（修成了归判据③，那一档本来就该静默）。
        listOf(
            LedgerProbe.NOT_WRITABLE to true,
            LedgerProbe.DIR_MISSING to false,
            LedgerProbe.UNTESTED to true,
        ).forEach { (probe, repairOk) ->
            val sinks = FakeSinks(repairOk = repairOk)
            val report = BootCheck.run(
                allGreen().copy(inspector = InspectorReading(workspaceReadable = true, ledger = probe)),
                sinks, T0, 600L
            )
            assertNotNull("${probe.wire} 不给结论就是静默", report.verdicts.first { it.part == BootPart.INSPECTOR }.finding)
            val left = report.cardCount + sinks.items.size + sinks.ruled.size
            assertTrue("${probe.wire} 既没弹卡也没落任何一笔账＝静默", left >= 1)
        }
        val blocking = BootCheck.run(
            allGreen().copy(inspector = InspectorReading(workspaceReadable = false, ledger = LedgerProbe.WRITABLE)),
            FakeSinks(), T0, 600L
        )
        assertEquals(1, blocking.cardCount)
        assertTrue(blocking.interrupts.first().text.contains("工作区读不了"))
    }

    // ── 其余三部位的具体形状 ────────────────────────────────────────────────

    @Test
    fun `御医轻量_该接没接要点名到部位`() {
        val report = BootCheck.run(
            BootReadings(
                scout = report(allGranted()),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.WRITABLE),
                physician = listOf(
                    organ("学习器", OrganState.NOT_WIRED, Disposition.DEFECT),
                    organ("读数层", OrganState.NOT_WIRED, Disposition.DEFECT),
                    organ("分类器", OrganState.ACTIVE, Disposition.NONE),
                    organ("不良人", OrganState.NOT_WIRED, Disposition.BY_DESIGN),
                ),
                audit = audit(InspectVerdict.CLEAR),
            ),
            FakeSinks(), T0, 600L
        )
        val physician = report.verdicts.first { it.part == BootPart.PHYSICIAN }
        assertEquals(BootFinding.ORGAN_NOT_WIRED, physician.finding)
        assertTrue("点名到部位：${physician.fact}", physician.fact.contains("学习器、读数层"))
        assertFalse("裁定关着的不是缺陷，不许算进该接没接", physician.fact.contains("不良人"))
        assertTrue(physician.usable)
    }

    @Test
    fun `御医没给结论与体检跑过没缺陷是两件事`() {
        val never = BootCheck.run(BootReadings(physician = emptyList()), FakeSinks(), T0, 600L)
        assertEquals(BootFinding.PHYSICIAN_NOT_RUN, never.verdicts.first { it.part == BootPart.PHYSICIAN }.finding)
        assertFalse(never.verdicts.first { it.part == BootPart.PHYSICIAN }.usable)
        val clean = BootCheck.run(BootReadings(physician = allActive()), FakeSinks(), T0, 600L)
        assertNull(clean.verdicts.first { it.part == BootPart.PHYSICIAN }.finding)
    }

    @Test
    fun `审计_无从比对不许写成没漏行`() {
        val noData = BootCheck.run(BootReadings(audit = audit(InspectVerdict.NO_DATA)), FakeSinks(), T0, 600L)
        val auditor = noData.verdicts.first { it.part == BootPart.AUDITOR }
        assertEquals(BootFinding.AUDIT_NO_DATA, auditor.finding)
        assertFalse("没数据＝这个部位这次不可用", auditor.usable)
        val clear = BootCheck.run(BootReadings(audit = audit(InspectVerdict.CLEAR)), FakeSinks(), T0, 600L)
        assertNull(clear.verdicts.first { it.part == BootPart.AUDITOR }.finding)
    }

    @Test
    fun `落点没测过不许冒充写得了`() {
        val sinks = FakeSinks()
        val report = BootCheck.run(
            BootReadings(
                scout = report(allGranted()),
                inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.UNTESTED),
                physician = allActive(),
                audit = audit(InspectVerdict.CLEAR),
            ),
            sinks, T0, 600L
        )
        val inspector = report.verdicts.first { it.part == BootPart.INSPECTOR }
        assertEquals(BootFinding.LEDGER_UNTESTED, inspector.finding)
        assertFalse(inspector.usable)
        assertEquals(0, report.cardCount)
        assertEquals(listOf("ledger_untested"), sinks.ruled)
    }

    // ── 与免打扰的接缝：④那一格不属于"打扰" ────────────────────────────────

    @Test
    fun `免打扰中的阻断仍出结论_但条数不变`() {
        // 开检**不读授权**（派工单④"唯一例外"归我判读为 Q1，待用户拍板）：
        // 所以这里钉的是两件事——阻断那一格的结论与卡不受免打扰影响，而卡的数量仍只由去重决定。
        val report = BootCheck.run(
            BootReadings(inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.NOT_WRITABLE)),
            FakeSinks(), T0, 600L
        )
        assertEquals(1, report.cardCount)
        val grant = QuietMode.grant("t330", nowMs = T0)
        assertTrue("阻断不是打扰，是一次失败陈述", QuietMode.cardsAllowed(grant, T0, blocking = true))
        assertFalse("非阻断的卡在免打扰期内一枚都不许弹", QuietMode.cardsAllowed(grant, T0, blocking = false))
    }

    // ── 手搓读数的四个把手 ──────────────────────────────────────────────────

    private fun allGreen(): BootReadings = BootReadings(
        scout = report(allGranted()),
        inspector = InspectorReading(workspaceReadable = true, ledger = LedgerProbe.WRITABLE),
        physician = allActive(),
        audit = audit(InspectVerdict.CLEAR),
    )

    private fun allGranted(): List<ScoutRecord> = Capability.entries.map { record(it, ScoutState.GRANTED) }

    private fun allActive(): List<OrganVerdict> = listOf(
        organ("分类器", OrganState.ACTIVE, Disposition.NONE),
        organ("勘验", OrganState.ACTIVE, Disposition.NONE),
    )

    private fun record(
        cap: Capability,
        state: ScoutState,
        reason: String = "",
        denials: Int = 0,
    ): ScoutRecord = ScoutRecord(
        capability = cap,
        state = state,
        denials = denials,
        lastAtMs = T0,
        reason = reason,
        device = "PGT-AN10/16",
    )

    private fun report(
        records: List<ScoutRecord>,
        sheetSaved: Boolean = true,
        withinBudget: Boolean = true,
        privateFallback: Boolean = false,
    ): Scout.Report = Scout.Report(
        trigger = ScoutTrigger.FIRST_RUN,
        reasons = listOf(ScoutTrigger.FIRST_RUN),
        atMs = T0,
        elapsedMs = 800L,
        withinBudget = withinBudget,
        device = ScoutDevice("PGT-AN10", "16", 36, "2.2.2", 222L),
        records = records,
        opinions = emptyList(),
        changed = emptyList(),
        sheet = ScoutSheet.Sheet("能力清单", listOf("存储：能用"), emptyList()),
        sheetSaved = sheetSaved,
        ledgerPaths = listOf("/记忆/斥候账.jsonl"),
        privateFallback = privateFallback,
    )

    private fun organ(name: String, state: OrganState, disposition: Disposition): OrganVerdict = OrganVerdict(
        name = name,
        state = state,
        cause = if (state == OrganState.NOT_WIRED) NoOutputCause.NEVER_DISPATCHED else null,
        disposition = disposition,
        reason = "$name：测试用结论",
        evidence = listOf("BootCheckTest 手搓"),
    )

    private fun audit(verdict: InspectVerdict, count: Int = 0): InspectFinding = InspectFinding(
        check = InspectCheck.AUDIT_GAP,
        verdict = verdict,
        oneLine = InspectText.line("审计漏 $count 条：写动作有动作无账"),
        suggestion = "无需处理",
        count = count,
        atMs = T0,
    )

    companion object {
        private const val T0 = 1_700_000_000_000L
    }
}

/**
 * 免打扰授权的判据（派工单后半页四条 ＋ 三条负例）。
 *
 * 与开检同一句实话：本文件钉的是授权怎么算，落账与真机那一段归接线批。
 */
class QuietModeTest {

    private val t0 = 1_700_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `判据①_用户说不弹卡_本任务零卡`() {
        val grant = QuietMode.grant("task-330", nowMs = t0)
        assertFalse("免打扰期内还允许弹卡＝授权白给", QuietMode.cardsAllowed(grant, t0 + hour, blocking = false))
        assertTrue(QuietMode.cardsAllowed(null, t0, blocking = false))
    }

    @Test
    fun `判据④_授权最长四小时`() {
        val max = QuietMode.grant("task-330", minutes = 240L, nowMs = t0)
        assertEquals(t0 + 4 * hour, max.expiresAtMs)
        assertTrue("想开五小时＝绕过派工单的自动退出", runCatching { QuietMode.grant("t", 241L, t0) }.isFailure)
        assertTrue(runCatching { QuietMode.grant("t", 0L, t0) }.isFailure)
    }

    @Test
    fun `判据④_满四小时自动退出_到期就恢复常规问法`() {
        val grant = QuietMode.grant("task-330", minutes = 240L, nowMs = t0)
        assertEquals(QuietState.ACTIVE, QuietMode.stateOf(grant, t0 + 4 * hour - 1))
        assertEquals(QuietState.EXPIRED, QuietMode.stateOf(grant, t0 + 4 * hour))
        assertFalse(QuietMode.active(grant, t0 + 4 * hour))
        assertEquals(0L, QuietMode.remainingMs(grant, t0 + 4 * hour))
        assertTrue("到期了还拦着弹卡＝把用户永久关在门外", QuietMode.cardsAllowed(grant, t0 + 4 * hour, false))
    }

    @Test
    fun `判据②_不可逆动作不问但必须可回退`() {
        val grant = QuietMode.grant("task-330", nowMs = t0)
        listOf(Rollback.RECYCLE_BIN, Rollback.BACKUP_FILE, Rollback.UNDO_LIST).forEach { r ->
            assertEquals("${r.cn}是可回退的，该静默做", QuietVerdict.RUN_SILENTLY, QuietMode.decide(grant, t0, r))
        }
        assertEquals(
            "没有任何回退办法就不许静默干",
            QuietVerdict.REFUSE_AND_RECORD,
            QuietMode.decide(grant, t0, Rollback.NONE)
        )
    }

    @Test
    fun `判据②_三样回退办法都是闭集里的键`() {
        assertEquals(3, Rollback.entries.count { it.reversible })
        assertFalse(Rollback.NONE.reversible)
        assertEquals(Rollback.BACKUP_FILE, Rollback.ofWire("backup_file"))
        assertNull("认不出的自造值不许静默当可回退", Rollback.ofWire("already_gone"))
    }

    @Test
    fun `判据③_收工交三样`() {
        val grant = QuietMode.grant("task-330", nowMs = t0)
        val hand = QuietMode.closeOut(
            grant, t0 + minute,
            artifacts = listOf("开机自检.kt"),
            acceptance = "四部位各一条结论，弹卡 0 枚",
            undone = listOf("真机两秒预算未测"),
        )
        assertEquals(QuietState.ACTIVE, hand.state)
        assertEquals(3, hand.lines().size)
        assertTrue(hand.lines()[0].contains("产物 1 件"))
        assertTrue(hand.lines()[1].contains("四部位"))
        assertTrue(hand.lines()[2].contains("真机两秒预算未测"))
    }

    @Test
    fun `判据③_到期那一轮交差多一行自动退出说明`() {
        val grant = QuietMode.grant("task-330", minutes = 1L, nowMs = t0)
        val hand = QuietMode.closeOut(grant, t0 + 2 * minute, listOf("a"), "跑完一轮", emptyList())
        assertEquals(QuietState.EXPIRED, hand.state)
        assertEquals(4, hand.lines().size)
        assertTrue("要说清是自动退的不是人叫停的", hand.lines()[3].contains("免打扰已自动退出"))
        assertTrue("未做项为空时也要照实写无", hand.lines()[2].contains("未做 0 项"))
    }

    @Test
    fun `负例_空手交差与只说一句做完了都不许`() {
        val grant = QuietMode.grant("task-330", nowMs = t0)
        assertTrue(
            "验收结论为空＝只说做完了",
            runCatching { QuietMode.closeOut(grant, t0, listOf("a"), "  ", emptyList()) }.isFailure
        )
        assertTrue(
            "既没产物也没未做项＝没干过",
            runCatching { QuietMode.closeOut(grant, t0, emptyList(), "干完了", emptyList()) }.isFailure
        )
    }

    @Test
    fun `负例_没授权时不许冒充授权静默执行`() {
        // 动作明明是可回退的，没授权也照样不许静默干——"不问"只能来自授权，不能来自方便。
        assertEquals(QuietVerdict.ASK_AS_USUAL, QuietMode.decide(null, t0, Rollback.RECYCLE_BIN))
        val expired = QuietMode.grant("task-330", minutes = 1L, nowMs = t0)
        assertEquals(
            "已过期的授权不是授权",
            QuietVerdict.ASK_AS_USUAL,
            QuietMode.decide(expired, t0 + 2 * minute, Rollback.BACKUP_FILE)
        )
    }

    @Test
    fun `到期边沿只在跨过那一次报`() {
        val grant = QuietMode.grant("task-330", minutes = 240L, nowMs = t0)
        assertTrue(QuietMode.justExpired(grant, t0 + 4 * hour - minute, t0 + 4 * hour))
        assertFalse("同一次到期不报第二遍（装配层按边沿落账）", QuietMode.justExpired(grant, t0 + 4 * hour, t0 + 5 * hour))
        assertFalse(QuietMode.justExpired(null, t0, t0 + hour))
    }

    @Test
    fun `免打扰不许覆盖别的任务`() {
        val grant = QuietMode.grant("task-330", nowMs = t0)
        assertEquals("task-330", grant.taskId)
        assertTrue("授权必须写明是哪件事", runCatching { QuietMode.grant("  ", nowMs = t0) }.isFailure)
    }

    companion object {
        private const val minute = 60_000L
    }
}
