// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.3（提炼口径）与 §4 判据 K6/K7。
// 纯函数直测（SkillRefine 零 Android / 零 IO）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRefineTest {

    private fun facts(vararg texts: String) = MemoryRefine.SessionFacts(
        sessionId = 1L,
        userTexts = texts.toList(),
        now = 1_700_000_000_000L,
    )

    private fun existing(vararg names: String) = names.map {
        SkillRules.IndexLine(MemoryScope.PROJECT, it, "旧场景")
    }

    // ── K7 一次封卷 ≤3 条 ─────────────────────────────────────────────────

    @Test
    fun `K7_命中再多_一次封卷最多3条_其余计dropped`() {
        val texts = (1..6).map { "请记住这个流程：第${it}套做法，照做就行。" }
        val r = SkillRefine.refine(facts(*texts.toTypedArray()), existing())
        assertEquals(3, r.candidates.size)
        assertTrue("超限的要计数，不静默", r.dropped > 0)
    }

    @Test
    fun `K6_触发点判据_Settled不出技能提炼_封卷与自检可以`() {
        assertNull("每轮结束留给记忆候选（派工单 §2.4-4）", SkillRefine.skillTriggerFor("settled"))
        assertEquals(
            SkillRefine.Trigger.CLOSE_AND_REFINE,
            SkillRefine.skillTriggerFor("new_session"),
        )
        assertEquals(SkillRefine.Trigger.SELF_CHECK, SkillRefine.skillTriggerFor("startup"))
        assertNull(SkillRefine.skillTriggerFor("别的"))
    }

    @Test
    fun `用户明说才出候选_普通句子不出`() {
        val r = SkillRefine.refine(
            facts("今天天气不错", "帮我整理一下书稿"),
            existing(),
        )
        assertEquals(0, r.candidates.size)
        assertEquals(0, r.dropped)
        val r2 = SkillRefine.refine(
            facts("帮我整理一下书稿，记住这个流程：先切章再转编码"),
            existing(),
        )
        assertEquals(1, r2.candidates.size)
        assertTrue("标记词命中 ⇒ 高置信（用户明说）", r2.candidates[0].fromUser)
    }

    @Test
    fun `句含全局_进全局层_否则项目层`() {
        val r = SkillRefine.refine(
            facts("把这个流程沉淀成技能：全局通用，每次先备份再动手"),
            existing(),
        )
        assertEquals(MemoryScope.GLOBAL, r.candidates.single().scope)
        val r2 = SkillRefine.refine(
            facts("把这个流程做成技能：先切章再转编码"),
            existing(),
        )
        assertEquals(MemoryScope.PROJECT, r2.candidates.single().scope)
    }

    @Test
    fun `已有同名技能索引_跳过不重复提`() {
        // 候选名 = 句子前 18 字的 slug（确定性）——existing 用同一个派生名
        val sentence = "记住这个流程：先切章再转编码"
        val derivedName = MemoryRules.slugOf(sentence.take(18))
        val r = SkillRefine.refine(
            facts(sentence),
            existing(derivedName),
        )
        assertEquals(0, r.candidates.size)
    }

    @Test
    fun `确定性_同一份事实两次提炼结果完全一致`() {
        val f = facts("记住这个流程：先备份再动手", "记住流程：先看目录再读文")
        val a = SkillRefine.refine(f, existing())
        val b = SkillRefine.refine(f, existing())
        assertEquals(a.candidates, b.candidates)
        assertEquals(a.dropped, b.dropped)
    }
}
