// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 起居注「未办成单」的数据与判据（只做数据层；页面等 278 的决策中心定稿）。
//
// 钉住派工单的四条硬约束：
// ① 只列事实与入口（没有"替你决定弹不弹"的开关）；
// ② 同一件事只列一行（按"想做的事"归一，次数累计）；
// ③ 原因必须可点开证据（`reasonRef` 形如 `<source>:<id>`，空引用只在"其他"里允许）；
// ④ 改判不是撤销历史（账本只增不改）。
package com.example.zhengdao.baopu.scout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnfinishedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(
        cap: Capability = Capability.CAMERA,
        state: ScoutState = ScoutState.DENIED,
        denials: Int = 1,
        reason: String = "",
    ) = ScoutRecord(cap, state, denials, 77L, "", reason, "", false, "PGT-AN10/16")

    @Test fun `a rejection points back at the card that can be overturned`() {
        val item = UnfinishedRules.fromRejected(record(), "扫一下这页纸", 99L, "ask:card-17")
        assertEquals(UnfinishedOutcome.NOT_DONE, item.outcome)
        assertEquals(UnfinishedReason.REJECTED, item.reason)
        assertEquals("ask:card-17", item.reasonRef)
        assertTrue(item.fixable)
        assertTrue(item.fixHint, item.fixHint.contains("相机"))
        assertTrue(item.reasonDetail, item.reasonDetail.contains("相机"))
    }

    @Test fun `missing permission points at the capability not at a card`() {
        val item = UnfinishedRules.fromMissingPermission(record(state = ScoutState.REVOKED), "整理相册", 99L)
        assertEquals(UnfinishedReason.MISSING_PERMISSION, item.reason)
        assertEquals("permission:camera", item.reasonRef)
        assertTrue(item.fixable)
    }

    @Test fun `unavailable is honestly not fixable and cites the ledger row`() {
        val rec = record(cap = Capability.ROOT, state = ScoutState.UNAVAILABLE, reason = "这台手机没有 root")
        val item = UnfinishedRules.fromUnavailable(rec, "改系统字体", 99L)
        assertEquals(UnfinishedReason.TOOL_UNAVAILABLE, item.reason)
        assertEquals(rec.ref(), item.reasonRef)
        assertFalse("别骗人说再试一次就行", item.fixable)
        assertEquals("这台手机没有 root", item.reasonDetail)
    }

    @Test fun `a reason without a source cannot be constructed`() {
        assertThrows(IllegalArgumentException::class.java) {
            UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.MISSING_PERMISSION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.OTHER, reasonRef = "没有冒号")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.OTHER, fixable = true)
        }
        // 其他原因允许没有出处（"不知道为什么"也配占一行，总比装懂强）。
        UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.OTHER)
    }

    @Test fun `one intent is one line no matter how often it fails`() {
        val first = UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.MISSING_PERMISSION, reasonRef = "permission:storage")
        val second = UnfinishedItem(" 整理相册 ", UnfinishedOutcome.NOT_DONE, UnfinishedReason.REJECTED, reasonRef = "ask:1")
        val merged = UnfinishedRules.merge(listOf(first), second)
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().count)
        assertEquals(UnfinishedReason.REJECTED, merged.first().reason)
        assertEquals(UnfinishedRules.keyOf(first), UnfinishedRules.keyOf(merged.first()))
    }

    @Test fun `a different intent stays a different line`() {
        val a = UnfinishedItem("整理相册", UnfinishedOutcome.FAILED, UnfinishedReason.OTHER)
        val b = UnfinishedItem("导出便签", UnfinishedOutcome.FAILED, UnfinishedReason.OTHER)
        assertEquals(2, UnfinishedRules.merge(listOf(a), b).size)
    }

    @Test fun `the ledger appends and folds by intent`() {
        val dir = tmp.newFolder("记忆")
        val ledger = UnfinishedLedger(listOf(dir))
        val one = UnfinishedRules.fromMissingPermission(record(), "拍证件", 100L)
        val two = UnfinishedRules.fromRejected(record(denials = 2), "拍证件", 200L, "ask:9")

        assertTrue(ledger.record(one))
        assertTrue(ledger.record(two))
        assertEquals(File(dir, ScoutLimits.UNFINISHED_FILE), ledger.file())

        val list = ledger.load()
        assertEquals(1, list.size)
        assertEquals(2, list.first().count)
        assertEquals(UnfinishedReason.REJECTED, list.first().reason)
        assertEquals("ask:9", list.first().reasonRef)
        // 只增不改：两行都在文件里（改判不撤销历史）。
        assertEquals(2, ledger.file().readLines().count { it.isNotBlank() })
    }

    @Test fun `an empty ledger means nothing was left undone`() {
        assertTrue(UnfinishedLedger(listOf(tmp.newFolder("空"))).load().isEmpty())
    }
}
