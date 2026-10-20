// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 A1–A5：**采纳事件的字段口径**。钉子都在「基线三态」和「名字闸门」上——
// 这两处错了，后面所有结论都会拿错尺子，而且错得看不出来。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PostAdoptionEventTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(
        eventId: String = "adopt-e1-1000",
        name: String = "我的技能",
        version: String = "abc123456789",
        baseline: Map<SkillOutcome, Int> = emptyMap(),
        source: PostAdoptionBaseline = PostAdoptionBaseline.NEW,
        rollback: Boolean = false,
        backups: List<String> = emptyList(),
        appliedAt: Long = 1_000L,
    ) = PostAdoptionEvent(
        eventId = eventId,
        channel = PostAdoptionChannel.LEARN_CARD,
        candidateId = "c1",
        skillName = name,
        skillVersion = version,
        appliedAtMs = appliedAt,
        baseline = baseline,
        baselineSource = source,
        rollbackAvailable = rollback,
        backupIds = backups,
    )

    @Test
    fun `A1_新技能基线全零且标NEW`() {
        val e = event()
        assertEquals(PostAdoptionBaseline.NEW, e.baselineSource)
        SkillOutcome.entries.forEach { assertEquals(0, e.baselineOf(it)) }
        // 「新」的语义：之前一次都没用过 ⇒ 现读几次就是增量几次
        val delta = postAdoptionDelta(e, mapOf(SkillOutcome.SUCCESS to 2))
        assertEquals(2, delta.successes)
        assertEquals(0, delta.failures)
    }

    @Test
    fun `A2_老技能基线取现读数并标MEASURED`() {
        val e = event(
            baseline = mapOf(SkillOutcome.SUCCESS to 5, SkillOutcome.FAILURE to 1),
            source = PostAdoptionBaseline.MEASURED,
        )
        assertEquals(PostAdoptionBaseline.MEASURED, e.baselineSource)
        assertEquals(5, e.baselineOf(SkillOutcome.SUCCESS))
        val delta = postAdoptionDelta(
            e,
            mapOf(SkillOutcome.SUCCESS to 8, SkillOutcome.FAILURE to 2),
        )
        assertEquals(3, delta.successes)
        assertEquals(1, delta.failures)
        // 卡面要能并排显示「采纳前」——基线快照不许丢
        assertEquals(5, baselineSnapshot(e).successes)
    }

    @Test
    fun `A3_名字过不了闸门就不许进账本`() {
        // 与 85 的 SkillEffects.toStmt 同一把尺：名字必须原样等于 sanitizeName 的输出
        assertNull(SkillEffects.nameToken("我的 技能"))
        assertNull(SkillEffects.nameToken(""))
        assertNotNull(SkillEffects.nameToken("我的技能"))
        assertNotNull(SkillEffects.nameToken("skill_a-1"))
    }

    @Test
    fun `A4_备份现读不到则rollbackAvailable为false`() {
        val no = event(rollback = false)
        assertFalse(no.rollbackAvailable)
        assertTrue(no.backupIds.isEmpty())
        val yes = event(rollback = true, backups = listOf("20261021-010203"))
        assertTrue(yes.rollbackAvailable)
        assertEquals(listOf("20261021-010203"), yes.backupIds)
    }

    @Test
    fun `A5_同技能再采纳先把旧事件结案为SUPERSEDED`() {
        val old = event(eventId = "adopt-old", appliedAt = 1_000L)
        val ledger = PostAdoptionLedger(listOf(old), emptyList())
        val fresh = event(eventId = "adopt-new", appliedAt = 2_000L)
        val settle = ledger.supersedeOnNewAdoption(fresh, atMs = 2_000L)
        assertEquals(1, settle.size)
        assertEquals("adopt-old", settle[0].eventId)
        assertEquals(PostAdoptionAction.SUPERSEDED, settle[0].action)
        assertNull(settle[0].verdict)
        // 新事件自己不能被顶掉
        assertTrue(ledger.supersedeOnNewAdoption(old, atMs = 2_000L).none { it.eventId == "adopt-new" })
    }

    @Test
    fun `A6_账本round_trip保住三态与备份名单`() {
        val log = PostAdoptionLog(java.io.File(tmp.root, PostAdoptionLog.FILE_NAME))
        val a = event(eventId = "adopt-a", source = PostAdoptionBaseline.UNKNOWN)
        val b = event(
            eventId = "adopt-b",
            rollback = true,
            backups = listOf("20261021-010203", "20261021-010204"),
            baseline = mapOf(SkillOutcome.SUCCESS to 2),
            source = PostAdoptionBaseline.MEASURED,
        )
        log.append(a)
        log.append(b)
        val loaded = log.load()
        assertNotNull(loaded)
        assertEquals(2, loaded!!.events.size)
        assertEquals(PostAdoptionBaseline.UNKNOWN, loaded.events[0].baselineSource)
        assertEquals(2, loaded.events[1].baselineOf(SkillOutcome.SUCCESS))
        assertEquals(2, loaded.events[1].backupIds.size)
        assertTrue(loaded.events[1].rollbackAvailable)
    }
}
