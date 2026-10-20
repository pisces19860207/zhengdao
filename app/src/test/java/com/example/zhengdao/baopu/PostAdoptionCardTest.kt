// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 D1–D4：**弹卡纪律**（用真账本文件跑，但读数与时钟都是注入的）。
// 四条规矩：变好不弹、变差只弹一次、先留着不再提、稍后处理不结案。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PostAdoptionCardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val appliedAt = 1_000L
    private val now = 2_000L

    private fun event(rollback: Boolean = true) = PostAdoptionEvent(
        eventId = "adopt-d",
        channel = PostAdoptionChannel.LEARN_CARD,
        candidateId = "c1",
        skillName = "我的技能",
        skillVersion = "abc123456789",
        appliedAtMs = appliedAt,
        baseline = emptyMap(),
        baselineSource = PostAdoptionBaseline.NEW,
        rollbackAvailable = rollback,
        backupIds = if (rollback) listOf("20261021-010203") else emptyList(),
    )

    private fun port(outcomes: Map<SkillOutcome, Int>, e: PostAdoptionEvent = event()): PostAdoptionPort {
        val log = PostAdoptionLog(File(tmp.root, PostAdoptionLog.FILE_NAME))
        log.append(e)
        return PostAdoptionPort(
            log = log,
            readOutcomes = { outcomes },
            versionOf = { e.skillVersion },
            clock = { now },
        )
    }

    @Test
    fun `D1_变好不弹卡只写审计`() {
        val p = port(mapOf(SkillOutcome.SUCCESS to 3))
        assertTrue("KEEP 不该打扰用户", p.recheck().isEmpty())
        val ledger = PostAdoptionLog(File(tmp.root, PostAdoptionLog.FILE_NAME)).load()
        assertNotNull(ledger)
        assertEquals(1, ledger!!.settlements.size)
        assertEquals(PostAdoptionAction.KEPT, ledger.settlements[0].action)
        assertTrue("KEEP 结案后不再复测", ledger.open().isEmpty())
    }

    @Test
    fun `D2_变差只弹一次`() {
        val p = port(mapOf(SkillOutcome.FAILURE to 3))
        val first = p.recheck()
        assertEquals(1, first.size)
        assertEquals(PostAdoptionVerdict.ROLLBACK, first[0].verdict)
        assertEquals(3, first[0].delta.failures)
        // 同一事件再复测：**不再弹**
        assertTrue(p.recheck().isEmpty())
    }

    @Test
    fun `D3_点先留着记DISMISSED并结案`() {
        val p = port(mapOf(SkillOutcome.FAILURE to 3))
        val notice = p.recheck().first()
        assertTrue(
            p.recordUserAction(
                eventId = notice.eventId,
                action = PostAdoptionAction.DISMISSED,
                verdict = notice.verdict,
                reason = notice.reason,
                delta = notice.delta,
            ),
        )
        assertTrue("接受风险后不再提", p.recheck().isEmpty())
        val ledger = PostAdoptionLog(File(tmp.root, PostAdoptionLog.FILE_NAME)).load()!!
        assertTrue(ledger.open().isEmpty())
    }

    @Test
    fun `D4_点稍后处理不结案`() {
        val p = port(mapOf(SkillOutcome.FAILURE to 3))
        val notice = p.recheck().first()
        p.recordUserAction(
            eventId = notice.eventId,
            action = PostAdoptionAction.LATER,
            verdict = notice.verdict,
        )
        val ledger = PostAdoptionLog(File(tmp.root, PostAdoptionLog.FILE_NAME)).load()!!
        assertEquals("LATER 不是终态", 1, ledger.open().size)
        // 但它已经弹过一次 ⇒ 不再弹第二次
        assertTrue(p.recheck().isEmpty())
    }

    @Test
    fun `D5_新采纳把旧窗口作废_避免双计`() {
        val e1 = event().copy(eventId = "adopt-d1", appliedAtMs = appliedAt)
        val p = port(mapOf(SkillOutcome.FAILURE to 3), e1)
        // 同一技能又采纳一次 ⇒ onAdopted 先把旧事件结案为 SUPERSEDED
        assertTrue(p.onAdopted(e1.copy(eventId = "adopt-d2", appliedAtMs = now)))
        val ledger = PostAdoptionLog(File(tmp.root, PostAdoptionLog.FILE_NAME)).load()!!
        assertTrue(ledger.open().any { it.eventId == "adopt-d2" })
        assertFalse(ledger.open().any { it.eventId == "adopt-d1" })
    }
}
