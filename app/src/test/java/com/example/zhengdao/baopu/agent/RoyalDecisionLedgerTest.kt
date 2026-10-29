// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十八批 §3（御案页签：批/驳/搁置＋批后落账）。
// 三条语义各有用例：批/驳永不再列；搁置只压到次日。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoyalDecisionLedgerTest {

    private lateinit var root: File

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        root = Files.createTempDirectory("royal-decisions").toFile()
        RoyalDecisionLedger.resetForTest()
        DecisionLedger.install(root)
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        RoyalDecisionLedger.resetForTest()
    }

    @Test
    fun `判据_批了就不再列`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.APPROVE, now)
        assertTrue(RoyalDecisionLedger.suppressedIds(now).contains("op:1"))
    }

    @Test
    fun `判据_驳了就不再列`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.REJECT, now)
        assertTrue(RoyalDecisionLedger.suppressedIds(now).contains("op:1"))
    }

    @Test
    fun `判据_搁置只压到明天_次日自己回来`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.HOLD, now)
        assertTrue("当天不该再烦用户", RoyalDecisionLedger.suppressedIds(now).contains("op:1"))
        assertFalse(
            "第二天它要自己回来（搁置不是删除）",
            RoyalDecisionLedger.suppressedIds(now + dayMs).contains("op:1"),
        )
    }

    @Test
    fun `判据_批驳永不过期_一年后仍不列`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.APPROVE, now)
        assertTrue(RoyalDecisionLedger.suppressedIds(now + 365 * dayMs).contains("op:1"))
    }

    @Test
    fun `判据_落账可回放_重启后仍读得到`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.APPROVE, now)
        RoyalDecisionLedger.resetForTest()
        val rows = RoyalDecisionLedger.replay()
        assertEquals(1, rows.size)
        assertEquals(RoyalVerdict.APPROVE, rows[0].verdict)
        assertEquals("op:1", rows[0].opinionId)
    }

    @Test
    fun `判据_落在装配目录的御批文件里`() {
        RoyalDecisionLedger.record("op:1", RoyalVerdict.HOLD, now)
        val f = RoyalDecisionLedger.fileIn(root)
        assertTrue(f.isFile)
        assertEquals(root, f.parentFile)
        assertEquals("御批.jsonl", f.name)
    }

    @Test
    fun `负例_意见id为空不许落账`() {
        assertNull(RoyalDecisionLedger.record("  ", RoyalVerdict.APPROVE, now))
        assertTrue(RoyalDecisionLedger.replay().isEmpty())
    }

    @Test
    fun `判据_选项id与裁定同一套字面量`() {
        assertEquals(RoyalVerdict.APPROVE, RoyalVerdict.ofOptionId("approve"))
        assertEquals(RoyalVerdict.REJECT, RoyalVerdict.ofOptionId("reject"))
        assertEquals(RoyalVerdict.HOLD, RoyalVerdict.ofOptionId("hold"))
        assertNull(RoyalVerdict.ofOptionId("whatever"))
    }
}
