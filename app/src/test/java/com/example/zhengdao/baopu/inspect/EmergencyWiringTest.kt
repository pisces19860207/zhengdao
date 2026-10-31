// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十五批 · 紧急闸接线的**行为门禁**（派工单判据①②③④逐条；源级门禁另见 EmergencyWiringGateTest）。
//
// ## 判据（可执行，判的是生产 EmergencyWiring／EmergencyGate／EmergencyClocks）
//   ① 紧急卡必须人批：requiresApproval 恒 true；自决路径撞上它一律拒——
//      **不许"特殊情况"**：3 严重度 × 2 法条 × 有无标记共 12 种组合，能自决放行的只有"非紧急"。
//   ② 止损四件不必等人：executeStopLoss 签名里没有审批参数，调了即执行（0 秒时钟）；
//      执行完立刻 replay 查得到（判据④的 3 秒上报线：可见时刻＝执行时刻＋调用耗时）。
//   ③ 超 24h ⇒ 报逾期并降级：到线（含）才降、差一毫秒不算到点；降级＝授权收回＋未办成单一行。
//   ④ 三时钟落账：账行带止损 0／上报 3s／追认 24h 三列，写入时从 EmergencyClocks 算死。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.law.EmergencyClocks
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedReason
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencyWiringTest {

    private lateinit var root: File
    private lateinit var unfinishedDir: File

    @Before
    fun setUp() {
        EmergencyWiring.resetForTest()
        root = Files.createTempDirectory("zd315-root").toFile()
        unfinishedDir = Files.createTempDirectory("zd315-unfinished").toFile()
        DecisionLedger.install(root)
        EmergencyWiring.installUnfinishedDirs { listOf(unfinishedDir) }
    }

    @After
    fun tearDown() {
        EmergencyWiring.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
        unfinishedDir.deleteRecursively()
    }

    private fun opinion(marker: Boolean, critical: Boolean, l21: Boolean) = Opinion(
        source = Source.CLASSIFIER,
        severity = if (critical) Severity.CRITICAL else Severity.MAJOR,
        gist = "紧急处置",
        action = ActionCode.SUGGEST_PAUSE,
        lawRef = if (l21) LawRef.L21 else LawRef.L8,
        evidence = if (marker) listOf(EMERGENCY_MARKER, "risk:r1") else listOf("risk:r1"),
    )

    // ── 判据①：紧急卡必须人批，自决一律拒（不许"特殊情况"） ──────────────────────

    @Test
    fun 判据一_紧急卡必须人批且自决一律拒() {
        val emergency = opinion(marker = true, critical = true, l21 = true)
        assertTrue("三样齐了就是紧急卡", emergency.isEmergencyCard)
        assertTrue("紧急卡必须人批", emergency.requiresApproval)
        val rejection = emergency.selfRulingRejection()
        assertNotNull("自决路径撞上紧急卡必须拒", rejection)
        assertTrue("拒因要点名『必须人批』", rejection!!.contains("必须人批"))
    }

    @Test
    fun 判据一_十二种组合里能自决放行的只有非紧急() {
        // 不许"特殊情况"：除（标记＋致命＋L21）外的每一种，都必须放行且不需要人批。
        for (marker in listOf(true, false)) {
            for (critical in listOf(true, false)) {
                for (l21 in listOf(true, false)) {
                    val o = opinion(marker, critical, l21)
                    val isEmergency = marker && critical && l21
                    val msg = "marker=$marker critical=$critical l21=$l21"
                    assertEquals("requiresApproval 判错（$msg）", isEmergency, o.requiresApproval)
                    assertEquals(
                        "自决拒因判错（$msg）",
                        isEmergency,
                        o.selfRulingRejection() != null,
                    )
                }
            }
        }
    }

    // ── 判据②＋④：止损四件 0 秒执行，3 秒内账上可查 ────────────────────────────

    @Test
    fun 判据二_止损四件零等待即执行且不必等人() {
        val t0 = 1_700_000_000_000L
        for (action in StopLossAction.entries) {
            val start = System.nanoTime()
            val receipt = EmergencyWiring.executeStopLoss(action, nowMs = t0)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                "止损必须同步返回（$action）：多了 ${elapsedMs}ms 等待",
                EmergencyClocks.stopLossElapsedOk(0L) && elapsedMs < EmergencyClocks.INFORM_MS,
            )
            assertEquals("执行即待追认（$action）", EmergencyStatus.PENDING_RATIFY, receipt.status)
            assertTrue(
                "执行完账上必须立刻查得到（$action）",
                EmergencyWiring.replay().any { it.id == receipt.id },
            )
            assertTrue("处置必须在身上（$action）", action in EmergencyWiring.stopLossActiveActions())
        }
        assertEquals("四件都执行了", StopLossAction.entries.size, EmergencyWiring.replay().size)
    }

    @Test
    fun 判据四_三秒上报线与三时钟落账() {
        val t0 = 1_700_000_000_000L
        val start = System.nanoTime()
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.QUARANTINE, nowMs = t0)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 可见时刻＝执行时刻＋调用耗时：必须在 3 秒上报线内（差一毫秒都算迟，与告知钟同尺）。
        assertTrue(
            "执行后 ${elapsedMs}ms 才查到，越过上报线",
            EmergencyClocks.reportInTime(t0, t0 + elapsedMs),
        )
        // 三时钟落账：三列写入时算死在行上（回放不重算）。
        assertEquals("止损列＝0 秒", EmergencyClocks.STOP_LOSS_MS, receipt.stopLossMs)
        assertEquals("上报线列＝3 秒", EmergencyClocks.INFORM_MS, receipt.informMs)
        assertEquals("追认截止列＝处置+24h", EmergencyClocks.ratifyDueAtMs(t0), receipt.ratifyDueAtMs)
        assertFalse("止损钟与告知钟不是一只", receipt.stopLossMs == receipt.informMs)
    }

    // ── 追认：24h 内有人确认；到线即拒、必须署名 ────────────────────────────────

    @Test
    fun 追认到场生效且此后不再降级() {
        val t0 = 1_700_000_000_000L
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.ROLLBACK, nowMs = t0)
        val ratified = EmergencyWiring.ratify(receipt.id, nowMs = t0 + 1_000, by = "蓝总管")
        assertNotNull("到期前追认收得下", ratified)
        assertEquals(EmergencyStatus.RATIFIED, ratified!!.status)
        assertEquals("蓝总管", ratified.ratifiedBy)
        assertTrue("追认过的处置在身上", StopLossAction.ROLLBACK in EmergencyWiring.stopLossActiveActions())
        assertTrue("追认过的不再降级", EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS + 1).isEmpty())
    }

    @Test
    fun 追认到线即拒且必须署名() {
        val t0 = 1_700_000_000_000L
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.STOP_MACHINE, nowMs = t0)
        assertNull("没署名不算人批", EmergencyWiring.ratify(receipt.id, nowMs = t0 + 1, by = "  "))
        assertNull("到线（含）已是逾期，追认窗口关了", EmergencyWiring.ratify(receipt.id, nowMs = t0 + EmergencyClocks.RATIFY_MS, by = "蓝总管"))
        assertEquals("被拒的追认不改状态", EmergencyStatus.PENDING_RATIFY, EmergencyWiring.replay().first { it.id == receipt.id }.status)
    }

    // ── 判据③：超 24h ⇒ 报逾期并降级，进未办成单 ───────────────────────────────

    @Test
    fun 判据三_超二十四小时报逾期并降级进未办成单() {
        val t0 = 1_700_000_000_000L
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.FREEZE_WRITES, nowMs = t0)
        // 边界：差一毫秒都不算到点。
        assertTrue(
            "24h 差一毫秒不许降级",
            EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS - 1).isEmpty(),
        )
        assertTrue(StopLossAction.FREEZE_WRITES in EmergencyWiring.stopLossActiveActions())
        // 到线（含）：降级＝授权收回＋未办成单一行。
        val downgraded = EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS)
        assertEquals("只降这一条", listOf(receipt.id), downgraded.map { it.id })
        assertEquals(EmergencyStatus.OVERDUE_DOWNGRADED, downgraded.single().status)
        assertFalse("降级后处置不在身上", StopLossAction.FREEZE_WRITES in EmergencyWiring.stopLossActiveActions())
        val row = UnfinishedLedger(listOf(unfinishedDir)).load().single()
        assertTrue("未办成单要点名动作", row.intent.contains("冻结写入"))
        assertEquals("能点回处置账行", "emergency:${receipt.id}", row.reasonRef)
        assertTrue("追认这件事还能补", row.fixable)
        assertEquals("原因＝其他（五类闭集之外）", UnfinishedReason.OTHER, row.reason)
    }

    @Test
    fun 账落盘且回放跨内存() {
        val t0 = 1_700_000_000_000L
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.QUARANTINE, nowMs = t0)
        EmergencyWiring.resetForTest() // 内存清空：盘是权威
        val fromDisk = EmergencyWiring.replay()
        assertEquals("重启后账还在", 1, fromDisk.size)
        assertEquals(receipt.id, fromDisk.single().id)
        assertEquals(EmergencyStatus.PENDING_RATIFY, fromDisk.single().status)
        // 降级追加新行，旧行留在账上（append-only：文件里两行都在）。
        EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS)
        assertEquals(2, EmergencyWiring.fileIn(root).readLines(Charsets.UTF_8).size)
        assertEquals(
            EmergencyStatus.OVERDUE_DOWNGRADED,
            EmergencyWiring.replay().single().status,
        )
    }

    @Test
    fun 未装配未办成单落点时降级照常单不落() {
        val t0 = 1_700_000_000_000L
        EmergencyWiring.resetForTest() // 清掉 setUp 里注入的落点：只留账，不拦降级
        DecisionLedger.install(root)
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.ROLLBACK, nowMs = t0)
        val downgraded = EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS)
        assertEquals("没装配落点不许挡降级", listOf(receipt.id), downgraded.map { it.id })
        assertEquals(EmergencyStatus.OVERDUE_DOWNGRADED, EmergencyWiring.replay().single().status)
    }
}
