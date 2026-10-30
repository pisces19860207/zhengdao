// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 四条件判据的 JVM 钉子（负例也钉：删了判据只剩"感觉该评" ⇒ 红）。
package com.example.zhengdao.baopu.evolution.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTriggerRulesTest {

    // ── ① 终态 FAILED 或回滚 ≥1 ──────────────────────────────────────────

    @Test
    fun `① 最后一行 FAILED ⇒ 触发`() {
        val run = run("r1", listOf("OK", "FAILED"), startedAt = 100, at = 200)
        assertEquals(run, EvalTriggerRules.failedRun(listOf(run), emptyList()))
    }

    @Test
    fun `① TIMEOUT 与 CANCELLED 同算没兜住的失败`() {
        // 与 281 RunVerdict 同一把尺：FAILED/TIMEOUT/CANCELLED 都是不终局。
        for (last in listOf("TIMEOUT", "CANCELLED")) {
            val run = run("r1", listOf("OK", last), startedAt = 100, at = 200)
            assertEquals("last=$last", run, EvalTriggerRules.failedRun(listOf(run), emptyList()))
        }
    }

    @Test
    fun `① FAILED 后又有 OK ＝ 被救回来 ⇒ 不触发`() {
        val run = run("r1", listOf("FAILED", "OK"), startedAt = 100, at = 200)
        assertNull(EvalTriggerRules.failedRun(listOf(run), emptyList()))
    }

    @Test
    fun `① 回滚 ≥1 次（终态成功也触发）`() {
        val run = run("r1", listOf("OK"), startedAt = 100, at = 200)
        assertEquals(run, EvalTriggerRules.failedRun(listOf(run), listOf(150L)))
    }

    @Test
    fun `① 回滚在 run 窗之外（别的会话／别的轮）⇒ 不触发`() {
        val run = run("r1", listOf("OK"), startedAt = 100, at = 200)
        assertNull(EvalTriggerRules.failedRun(listOf(run), listOf(99L, 201L)))
    }

    @Test
    fun `① 没有任何 run ⇒ 判不了，不猜`() {
        assertNull(EvalTriggerRules.failedRun(emptyList(), listOf(1L)))
    }

    // ── ② 用户连续两次纠同一件事 ─────────────────────────────────────────

    @Test
    fun `② 同一技能最后两条都是 USER_CORRECTED ⇒ 触发并给出两条证据 id`() {
        val rows = listOf(
            row(1, "查文献", "SUCCESS"),
            row(2, "查文献", "USER_CORRECTED"),
            row(3, "查文献", "USER_CORRECTED"),
        )
        val hit = EvalTriggerRules.correctedSkill(rows)
        assertEquals("查文献" to listOf(2L, 3L), hit)
    }

    @Test
    fun `② 中间夹一次成功 ＝ 打断 ⇒ 不触发`() {
        val rows = listOf(
            row(1, "查文献", "USER_CORRECTED"),
            row(2, "查文献", "USER_CORRECTED"),
            row(3, "查文献", "SUCCESS"),
            row(4, "查文献", "USER_CORRECTED"),
        )
        assertNull(EvalTriggerRules.correctedSkill(rows))
    }

    @Test
    fun `② 只纠过一次 ⇒ 不触发`() {
        assertNull(
            EvalTriggerRules.correctedSkill(listOf(row(1, "查文献", "USER_CORRECTED"))),
        )
    }

    @Test
    fun `② 两个技能都连续被纠 ⇒ 取最近被纠的`() {
        val rows = listOf(
            row(1, "旧技能", "USER_CORRECTED"),
            row(2, "旧技能", "USER_CORRECTED"),
            row(3, "新技能", "USER_CORRECTED"),
            row(4, "新技能", "USER_CORRECTED"),
        )
        assertEquals("新技能" to listOf(3L, 4L), EvalTriggerRules.correctedSkill(rows))
    }

    @Test
    fun `② 认不出的 outcome 丢弃（不冒充也不打断真实序列）`() {
        val rows = listOf(
            row(1, "查文献", "WEIRD"),
            row(2, "查文献", "USER_CORRECTED"),
            row(3, "查文献", "USER_CORRECTED"),
        )
        assertEquals("查文献" to listOf(2L, 3L), EvalTriggerRules.correctedSkill(rows))
    }

    // ── ③ 成功率跌破阈值 ─────────────────────────────────────────────────

    @Test
    fun `③ 样本不足分母线 ⇒ 判不了`() {
        assertNull(EvalTriggerRules.successRate(0, 9, 0))
        assertEquals(EvalTriggerRules.RATE_MIN_SAMPLES, 10)
    }

    @Test
    fun `③ 4 成 6 挂 ⇒ 跌破（触发）`() {
        assertEquals(0.4, EvalTriggerRules.successRate(4, 6, 0)!!, 1e-9)
    }

    @Test
    fun `③ 恰好一半 ＝ 等号不动（不算跌破）`() {
        assertEquals(0.5, EvalTriggerRules.successRate(5, 5, 0)!!, 1e-9)
    }

    @Test
    fun `③ RETRIED 不进分母（它标记重来，不是这次的结果）`() {
        // 10 SUCCESS + 5 RETRIED ⇒ 率 1.0，不是 10/15。
        assertEquals(1.0, EvalTriggerRules.successRate(10, 0, 0)!!, 1e-9)
    }

    @Test
    fun `③ 窗内有跌破的技能 ⇒ 出信号带事实数字`() {
        val rows = (1..4).map { row(it.toLong(), "查文献", "SUCCESS") } +
            (5..10).map { row(it.toLong(), "查文献", "FAILURE") }
        val sig = EvalTriggerRules.skillBelowRate(rows)!!
        assertEquals(TriggerType.SKILL_RATE, sig.type)
        assertEquals("查文献", sig.target)
        assertEquals(4, sig.successes)
        assertEquals(6, sig.failures)
        assertTrue(sig.ref.startsWith("skill_event:"))
    }

    @Test
    fun `③ 率过线 ⇒ 不出信号`() {
        val rows = (1..8).map { row(it.toLong(), "查文献", "SUCCESS") } +
            (9..10).map { row(it.toLong(), "查文献", "FAILURE") }
        assertNull(EvalTriggerRules.skillBelowRate(rows))
    }

    // ── 组合 ─────────────────────────────────────────────────────────────

    @Test
    fun `组合 一次取数里三类同时满足 ⇒ 三条信号按编号顺序`() {
        val failed = run("r9", listOf("FAILED"), startedAt = 10, at = 20)
        val session = listOf(
            row(1, "查文献", "USER_CORRECTED"),
            row(2, "查文献", "USER_CORRECTED"),
        )
        val window = session + (3..12).map { row(it.toLong(), "旧技能", "FAILURE") }
        val inputs = EvalTriggerInputs(
            nowMs = 1_000L,
            sessionId = 7L,
            runs = listOf(failed),
            rollbackAtMs = emptyList(),
            sessionSkillRows = session,
            windowSkillRows = window,
            intent = "整理笔记",
        )
        val signals = EvalTriggerRules.signalsOf(inputs)
        assertEquals(
            listOf(TriggerType.RUN_FAILED, TriggerType.USER_CORRECTION, TriggerType.SKILL_RATE),
            signals.map { it.type },
        )
        assertEquals("task:r9", signals[0].ref)
        assertEquals("skill_event:2", signals[1].ref)
        assertEquals("旧技能", signals[2].target)
    }

    @Test
    fun `组合 全都不满足 ⇒ 空表（大多数收尾走这里，零动作）`() {
        val inputs = EvalTriggerInputs(
            nowMs = 1_000L,
            sessionId = 7L,
            runs = listOf(run("r1", listOf("OK"), startedAt = 10, at = 20)),
            rollbackAtMs = emptyList(),
            sessionSkillRows = listOf(row(1, "查文献", "SUCCESS")),
            windowSkillRows = listOf(row(1, "查文献", "SUCCESS")),
            intent = "整理笔记",
        )
        assertTrue(EvalTriggerRules.signalsOf(inputs).isEmpty())
    }

    // ── 假件 ─────────────────────────────────────────────────────────────

    private fun run(id: String, statuses: List<String>, startedAt: Long, at: Long) =
        TriggerRunRow(runId = id, sessionId = 7L, startedAtMs = startedAt, atMs = at, statuses = statuses)

    private fun row(id: Long, name: String, outcome: String) =
        TriggerSkillRow(id = id, name = name, outcome = outcome, atMs = id)
}
