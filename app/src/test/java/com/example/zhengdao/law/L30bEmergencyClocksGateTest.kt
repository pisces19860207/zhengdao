// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典门禁 **L30b 三时钟**（法典卷十 L30b；271 台账此前没有这一项，本批新建）。
//
// 判据落进生产 `baopu/law/EmergencyClocks.kt`；「最严重」的判定属 L29 灾异四类，未接线 ⇒
// 生产只收 `critical: Boolean`，L29 接线那天换判据、用例名不动（缺陷账：271-L29）。
//
// ## 判据（可执行，判的是生产 EmergencyClocks）
// 止损 **0 秒**（白名单动作不等判定，立即自执行）／告知 **3 秒、最严重 2 秒**
// （超过 ⇒ 旁路；旁路只影响"谁出卡"，不影响处置权）／追认 **24 小时**（处置时刻 + 24h，不多不少）。
//
// ## 负例（造违规即红，红在生产常量与算式上）
// 串档（止损≠0／最严重档与普通档同速／追认没加 24h）与边界差一毫秒（2999/3000、1999/2000）都不行。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.EmergencyClocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L30bEmergencyClocksGateTest {

    private val t0 = 1_800_000_000_000L
    private val hour = 3600_000L

    // ── 正例：三只钟各走各的 ─────────────────────────────────────────────

    @Test
    fun `L30_正例_三时钟各走各的_止损不等判定_告知最严重更快_追认满 24 小时`() {
        // 止损：0 秒，白名单动作立即动手，不等判定
        assertEquals("止损必须是 0 秒：白名单动作不等判定", 0L, EmergencyClocks.STOP_LOSS_MS)
        // 告知：普通 3 秒、最严重 2 秒，且最严重必须更快
        assertEquals("告知普通档 3 秒", 3_000L, EmergencyClocks.INFORM_MS)
        assertEquals("告知最严重档 2 秒", 2_000L, EmergencyClocks.INFORM_CRITICAL_MS)
        assertTrue("最严重必须更快（2 < 3）", EmergencyClocks.INFORM_CRITICAL_MS < EmergencyClocks.INFORM_MS)
        // 旁路边界：2999 没到点、3000 到点（最严重 1999/2000 同理）
        assertFalse("2999 毫秒还没到 3 秒", EmergencyClocks.informBypass(2_999L, critical = false))
        assertTrue("3000 毫秒整即旁路", EmergencyClocks.informBypass(3_000L, critical = false))
        assertFalse("1999 毫秒还没到 2 秒", EmergencyClocks.informBypass(1_999L, critical = true))
        assertTrue("2000 毫秒整即旁路", EmergencyClocks.informBypass(2_000L, critical = true))
        // 追认：处置时刻 + 24 小时，不多不少
        assertEquals("追认截止＝处置 + 24h", t0 + 24 * hour, EmergencyClocks.ratifyDueAtMs(t0))
    }

    // ── 负例：串档或边界差一毫秒都不行 ─────────────────────────────────────

    @Test
    fun `L30_负例_时钟串档或边界差一毫秒都不行`() {
        // 串档一：止损被并进告知钟（止损 0 与告知 3 秒必须是两个数）
        assertTrue("止损不许等于告知钟", EmergencyClocks.STOP_LOSS_MS != EmergencyClocks.INFORM_MS)
        // 串档二：最严重档与普通档同速（两档各自读数必须一个 2 秒一个 3 秒）
        assertEquals("最严重档必须还是 2 秒", 2_000L, EmergencyClocks.informDeadlineMs(critical = true))
        assertEquals("普通档必须还是 3 秒", 3_000L, EmergencyClocks.informDeadlineMs(critical = false))
        // 串档三：追认被抹成 0（截止必须晚于处置时刻）
        assertTrue("追认截止必须晚于处置时刻", EmergencyClocks.ratifyDueAtMs(t0) > t0)
        // 边界差一毫秒就是两样结局（旁路必须在整点翻面，不许提前也不许延后）
        assertTrue(
            "2999/3000 差一毫秒就是两样结局",
            EmergencyClocks.informBypass(EmergencyClocks.INFORM_MS - 1, critical = false) !=
                EmergencyClocks.informBypass(EmergencyClocks.INFORM_MS, critical = false),
        )
        assertTrue(
            "1999/2000 差一毫秒就是两样结局",
            EmergencyClocks.informBypass(EmergencyClocks.INFORM_CRITICAL_MS - 1, critical = true) !=
                EmergencyClocks.informBypass(EmergencyClocks.INFORM_CRITICAL_MS, critical = true),
        )
    }
}
