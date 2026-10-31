// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典门禁 **L31 事后监督三件套**（法典卷十 L31；316 对账时是五条真缺口之一，本批落地）。
//
// 判据本体是 315 已接线的生产件（复用，不复制一套）：止损＝`EmergencyWiring.executeStopLoss`（0 秒），
// 告知＝`EmergencyClocks.reportInTime`（3 秒上报线），追认＝`EmergencyWiring.ratify`（24 小时窗）。
// 本门禁钉的是 L31 的账面要求：**三件套缺任一 ⇒ 红；逾期未追认 ⇒ 降级并记账**。
//
// ## 判据（可执行，判的是生产 EmergencyWiring／EmergencyClocks）
//   ① 三列齐：每条处置账行必须带止损 0／上报线 3s／追认截止 24h 三列，且列值出自 law 的钟
//      （缺列或手抄 ⇒ parse 出来的行对不上 EmergencyClocks，当场红）；
//   ② 三件套齐 ⇒ 处置合法存续：执行即入账（止损）、3 秒内账上查得到（告知）、
//      24h 内署名追认生效（追认），且追认过的不再降级；
//   ③ 逾期未追认 ⇒ 降级并记账：授权收回（处置不在身上）＋账上追加降级行＋未办成单落一行
//      （能点回处置账行）；到线才来的追认收不下（窗口关死）。
//
// ## 负例（造违规即红，红在生产行为上）
// 迟一毫秒的告知 ⇒ reportInTime 假；过期追认 ⇒ null；不追认到线 ⇒ 必须降级＋未办成单。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.EmergencyStatus
import com.example.zhengdao.baopu.inspect.EmergencyWiring
import com.example.zhengdao.baopu.inspect.StopLossAction
import com.example.zhengdao.baopu.law.EmergencyClocks
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class L31OversightTrioGateTest {

    private val t0 = 1_800_000_000_000L
    private lateinit var root: java.io.File
    private lateinit var unfinishedDir: java.io.File

    @Before
    fun setUp() {
        EmergencyWiring.resetForTest()
        root = Files.createTempDirectory("zd323-root").toFile()
        unfinishedDir = Files.createTempDirectory("zd323-unfinished").toFile()
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

    // ── 正例：三件套齐，处置合法存续 ─────────────────────────────────────────────

    @Test
    fun `L31_正例_止损告知追认三件套齐_处置合法存续`() {
        // ①止损：0 秒执行，执行即入账（回放查得到）
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.ROLLBACK, nowMs = t0)
        assertEquals("执行即待追认", EmergencyStatus.PENDING_RATIFY, receipt.status)
        assertEquals("止损列必须出自 law 的钟（0 秒）", EmergencyClocks.STOP_LOSS_MS, receipt.stopLossMs)
        // ②告知：3 秒内账上查得到——执行与落账同一次同步调用，上报线由 EmergencyClocks 出
        val booked = EmergencyWiring.replay().first { it.id == receipt.id }
        assertTrue(
            "执行即入账 ⇒ 账上可见时刻不晚于上报线（3 秒）",
            EmergencyClocks.reportInTime(booked.executedAtMs, booked.executedAtMs),
        )
        assertEquals("上报线列必须出自 law 的钟（3 秒）", EmergencyClocks.INFORM_MS, booked.informMs)
        // ③追认：24h 内署名追认生效
        val ratified = EmergencyWiring.ratify(receipt.id, nowMs = t0 + 3_600_000L, by = "御前")
        assertNotNull("窗口内署名追认收得下", ratified)
        assertEquals(EmergencyStatus.RATIFIED, ratified!!.status)
        // 三件套齐 ⇒ 不再降级（处置合法存续）
        assertTrue(
            "追认过的处置不降级",
            EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS + 1).isEmpty(),
        )
        assertTrue("追认过的处置在身上", StopLossAction.ROLLBACK in EmergencyWiring.stopLossActiveActions())
    }

    @Test
    fun `L31_正例_三时钟列逐列出自law的钟_缺任一列的账对不上`() {
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.FREEZE_WRITES, nowMs = t0)
        // 三列齐且互不相等（三只钟各走各的——串档即违规）：
        assertEquals("止损列＝0 秒", EmergencyClocks.STOP_LOSS_MS, receipt.stopLossMs)
        assertEquals("上报线列＝3 秒", EmergencyClocks.INFORM_MS, receipt.informMs)
        assertEquals("追认截止列＝处置 + 24h", EmergencyClocks.ratifyDueAtMs(t0), receipt.ratifyDueAtMs)
        assertTrue("止损钟与告知钟不许是同一只", receipt.stopLossMs != receipt.informMs)
        assertTrue("追认截止必须晚于上报线", receipt.ratifyDueAtMs > t0 + receipt.informMs)
        // 追认截止必须正好是 EmergencyClocks 算的那一个数——手抄一个"差不多 24h"的数对不上
        assertEquals(
            "追认截止必须出自 EmergencyClocks.ratifyDueAtMs",
            EmergencyClocks.ratifyDueAtMs(receipt.executedAtMs),
            receipt.ratifyDueAtMs,
        )
    }

    // ── 负例：缺任一 ⇒ 红；逾期未追认 ⇒ 降级并记账 ───────────────────────────────

    @Test
    fun `L31_负例_告知迟一毫秒就是没上报_过期追认窗口关死`() {
        // 告知缺：迟一毫秒 ⇒ reportInTime 必须翻假（差一毫秒都算迟）
        assertFalse(
            "超过上报线（3s+1ms）必须算没上报",
            EmergencyClocks.reportInTime(t0, t0 + EmergencyClocks.INFORM_MS + 1),
        )
        assertTrue(
            "到线（含）正好算上报",
            EmergencyClocks.reportInTime(t0, t0 + EmergencyClocks.INFORM_MS),
        )
        // 追认缺：过期才来的追认收不下（窗口关死——逾期属于降级那一步，不属于追认）
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.QUARANTINE, nowMs = t0)
        assertNull(
            "到线（含）追认必须拒",
            EmergencyWiring.ratify(receipt.id, nowMs = t0 + EmergencyClocks.RATIFY_MS, by = "御前"),
        )
        assertEquals(
            "被拒的追认不改状态",
            EmergencyStatus.PENDING_RATIFY,
            EmergencyWiring.replay().first { it.id == receipt.id }.status,
        )
    }

    @Test
    fun `L31_负例_逾期未追认必须降级并记账`() {
        val receipt = EmergencyWiring.executeStopLoss(StopLossAction.STOP_MACHINE, nowMs = t0)
        // 差一毫秒不许降（到线才算逾期——与 315 的边界同尺）
        assertTrue(
            "24h 差一毫秒不许降级",
            EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS - 1).isEmpty(),
        )
        assertTrue(StopLossAction.STOP_MACHINE in EmergencyWiring.stopLossActiveActions())
        // 到线（含）：降级并记账——授权收回＋账上追加降级行＋未办成单落一行
        val downgraded = EmergencyWiring.sweepOverdue(t0 + EmergencyClocks.RATIFY_MS)
        assertEquals("只降这一条", listOf(receipt.id), downgraded.map { it.id })
        assertEquals(EmergencyStatus.OVERDUE_DOWNGRADED, downgraded.single().status)
        assertFalse("降级＝授权收回：处置不在身上", StopLossAction.STOP_MACHINE in EmergencyWiring.stopLossActiveActions())
        // 记账两本：紧急处置账上的降级行（回放可查）＋未办成单的一行（能点回处置账行）
        assertEquals(
            "降级行必须入账（append-only，回放查得到）",
            EmergencyStatus.OVERDUE_DOWNGRADED,
            EmergencyWiring.replay().first { it.id == receipt.id }.status,
        )
        val row = UnfinishedLedger(listOf(unfinishedDir)).load().single()
        assertTrue("未办成单要点名动作", row.intent.contains("停机"))
        assertEquals("能点回处置账行", "emergency:${receipt.id}", row.reasonRef)
        assertTrue("追认这件事还能补", row.fixable)
    }
}
