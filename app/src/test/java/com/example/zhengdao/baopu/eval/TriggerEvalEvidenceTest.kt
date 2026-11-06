// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 证据喂数（评测→决策）的判据用例：
//   ① 造一条 FAILED 触发 ⇒ 决策口输入 eval 能看到读数（判 REJECT，不是过线）；
//   ② 账本空／全是没有读数的行 ⇒ eval=null ⇒ UNVERIFIABLE（DEFER），不得当通过；
//   ③ 来源标 TRIGGER、时间窗（ranAt=最新一条）与 selectEvalEvidence 的第三优先级。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEvidence
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.ProposalCandidate
import com.example.zhengdao.baopu.ProposalEvidence
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SelfCheckEvidence
import com.example.zhengdao.baopu.evolution.trigger.TriggerRow
import com.example.zhengdao.baopu.inputFor
import com.example.zhengdao.baopu.triggerEvalOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEvalEvidenceTest {

    /** 一条真触发、带读数的账行（304 字段：run/passed/passRate/失败场景/时刻）。 */
    private fun reading(
        atMs: Long,
        ran: Int = 13,
        passed: Int = 13,
        passRate: Double? = passed.toDouble() / ran.toDouble(),
        failedCases: List<String> = emptyList(),
    ) = TriggerRow(
        atMs = atMs,
        type = com.example.zhengdao.baopu.evolution.trigger.TriggerType.MANUAL,
        triggered = true,
        ref = "manual",
        intent = "测试读数",
        evalRan = ran,
        evalPassed = passed,
        evalTotal = ran,
        evalPassRate = passRate,
        evalFailedCases = failedCases,
    )

    /** 一条被限频挡下的行（没有读数；不该进汇总）。 */
    private fun suppressed(atMs: Long) = TriggerRow(
        atMs = atMs,
        type = com.example.zhengdao.baopu.evolution.trigger.TriggerType.RUN_FAILED,
        triggered = false,
        ref = "task:r1",
        intent = "被抑制",
        suppressReason = "同类「任务失败」24h 内已评过",
    )

    /** 一条真触发但没跑出读数的行（带 error；同样不进汇总）。 */
    private fun errored(atMs: Long) = TriggerRow(
        atMs = atMs,
        type = com.example.zhengdao.baopu.evolution.trigger.TriggerType.RUN_FAILED,
        triggered = true,
        ref = "task:r2",
        intent = "评了但崩了",
        evalError = "场景文件读不动",
    )

    private fun candidate() = ProposalCandidate(
        id = "c-trig",
        kind = ProposalKind.SKILL_BODY,
        target = "做卡流程",
        payload = "# 做卡流程\n\n正文。",
        evidence = ProposalEvidence(atMs = 1L),
        createdAt = 1L,
    )

    /** 自检过＋回滚可用 ⇒ 建议链走到 eval 那一格（507 喂的读数在这里说话）。 */
    private val selfCheckOk = SelfCheckEvidence(passed = true, rollbackAvailable = true, caseIds = listOf("selfcheck:structure"))

    // ── ① FAILED 触发的读数进决策口 ──────────────────────────────────────

    @Test
    fun `FAILED触发_读数可见_score是账本汇总的passRate`() {
        val rows = listOf(
            reading(atMs = 1_000L, ran = 13, passed = 13),
            reading(atMs = 2_000L, ran = 13, passed = 5, failedCases = listOf("s3", "s7")),
        )
        val eval = triggerEvalOf(rows)!!
        // run=26 / passed=18 ⇒ passRate≈0.6923（两行同尺合计，不是各行均值的近似）
        assertEquals(EvalEvidenceSource.TRIGGER, eval.source)
        assertEquals(2_000L, eval.ranAt) // 时间窗右沿＝最新一条
        assertEquals(18.0 / 26.0, eval.score, 1e-9)
        // 失败场景 id 进 caseIds（审计引用得回具体场景）
        assertTrue(eval.caseIds.containsAll(listOf("s3", "s7")))
    }

    @Test
    fun `FAILED触发_judge判REJECT_原因链点名EVAL_BELOW_FLOOR`() {
        val rows = listOf(reading(atMs = 1_000L, ran = 13, passed = 5))
        val evidence = EvolutionEvidence(selfCheck = selfCheckOk, eval = triggerEvalOf(rows))
        val judgement = EvolutionPolicy().judge(inputFor(candidate(), evidence))
        assertEquals(EvolutionRecommendation.REJECT, judgement.recommendation)
        assertTrue(
            "原因链要点名 eval 未过线",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_BELOW_FLOOR },
        )
    }

    // ── ② 账本空／无读数 ⇒ UNVERIFIABLE（不得当通过） ─────────────────────

    @Test
    fun `账本空_triggerEvalOf返回null`() = assertNull(triggerEvalOf(emptyList()))

    @Test
    fun `只有抑制行与error行_等于没有读数_返回null`() {
        assertNull(triggerEvalOf(listOf(suppressed(1_000L), errored(2_000L))))
    }

    @Test
    fun `账本空_eval=null_judge判DEFER_不是通过`() {
        // 507 派工单②：无读数 ⇒ UNVERIFIABLE——落到 policy 就是 DEFER（不许默认过线）。
        val judgement = EvolutionPolicy().judge(
            inputFor(candidate(), EvolutionEvidence(selfCheck = selfCheckOk, eval = triggerEvalOf(emptyList()))),
        )
        assertEquals(EvolutionRecommendation.DEFER, judgement.recommendation)
        assertTrue(
            "原因链要点名 eval 没跑",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_NOT_RUN },
        )
    }

    // ── ③ 汇总窗口与去重 ──────────────────────────────────────────────────

    @Test
    fun `只取最近N条有读数的行_N=20`() {
        // 前 5 条全挂、后 20 条全过：取**尾** 20 ⇒ 全过；取头 20 或全取 ⇒ 掺着挂的。
        val rows = (1..25L).map {
            if (it <= 5L) reading(atMs = it * 1_000L, ran = 13, passed = 0)
            else reading(atMs = it * 1_000L, ran = 13, passed = 13)
        }
        val eval = triggerEvalOf(rows)!!
        assertEquals(1.0, eval.score, 1e-9) // 尾 20 条全过 ⇒ run=260、passed=260（头 5 条挂的被窗口丢掉）
        assertEquals(25_000L, eval.ranAt) // 时间窗右沿是最新一条
    }

    @Test
    fun `失败场景去重且有上限`() {
        val rows = (1..10L).map {
            reading(atMs = it * 1_000L, failedCases = List(13) { j -> "s$j" })
        }
        val eval = triggerEvalOf(rows)!!
        assertEquals(13, eval.caseIds.size) // 10 行同样的 13 个场景 ⇒ 去重后仍是 13
    }

    // ── ④ 第三来源的选择（prod ＞ trigger ＞ release） ─────────────────────

    @Test
    fun `selectEvalEvidence_trigger排在prod后release前`() {
        val now = 10_000_000L
        val prod = com.example.zhengdao.baopu.EvolutionEval(0.9, source = EvalEvidenceSource.PERSONAL_PROD, ranAt = now - 1_000)
        val trigger = com.example.zhengdao.baopu.EvolutionEval(0.5, source = EvalEvidenceSource.TRIGGER, ranAt = now - 1_000)
        val release = com.example.zhengdao.baopu.EvolutionEval(0.99, source = EvalEvidenceSource.RELEASE, ranAt = null)
        // 新鲜 prod 在 ⇒ trigger 不抢
        assertEquals(prod, selectEvalEvidence(prod = prod, release = release, now = now, trigger = trigger))
        // prod 缺 ⇒ trigger 顶上（哪怕 release 分数更高——不许混用，按新鲜与就近选）
        assertEquals(trigger, selectEvalEvidence(prod = null, release = release, now = now, trigger = trigger))
        // trigger 过期 ⇒ 回退 release（旧读数不许冒充）
        val staleTrigger = com.example.zhengdao.baopu.EvolutionEval(0.5, source = EvalEvidenceSource.TRIGGER, ranAt = now - 8L * 24 * 60 * 60 * 1000)
        assertEquals(release, selectEvalEvidence(prod = null, release = release, now = now, trigger = staleTrigger))
        // 全缺 ⇒ null（DEFER，不是通过）
        assertNull(selectEvalEvidence(prod = null, release = null, now = now, trigger = null))
    }
}
