package com.example.zhengdao.baopu.evolution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝总管验收补测（2026-11-09，567-C）：「证据足」这一格是**两本取合**，缺一本就不足。
 *
 * 为什么补：这条判据原来只写在 [EvolutionWiring.adjudicateDepth] 里，而那条链
 * （`sweepOne → adjudicateDepth`）全是 private，单测进不去。我做了两刀变异——
 * ① 去掉 `proofReady`、② 把 `DepthEvalFeed.evidenceEnough()` 放宽成 `ran > 0`——
 * **两次都跑绿**，说明这一格当时没有任何用例钉住。抽成 `depthEvidenceEnough` 后由本类钉死。
 */
class DepthEvidenceJoinTest {

    private fun reading(ran: Int, total: Int, passRate: Double?) =
        DepthEvalReading(
            atMs = 1L,
            type = "eval",
            ran = ran,
            passed = ran,
            total = total,
            passRate = passRate,
            ref = "goal:1",
        )

    @Test
    fun `两本都在_证据足`() {
        assertTrue(EvolutionWiring.depthEvidenceEnough(proofReady = true, eval = reading(3, 3, 1.0)))
    }

    @Test
    fun `四条读数不在_有评估账也不算足`() {
        assertFalse(
            "只收紧不放松：proofReady 缺 ⇒ 证据不足（505 判据②「不知道就不升档」）",
            EvolutionWiring.depthEvidenceEnough(proofReady = false, eval = reading(3, 3, 1.0)),
        )
    }

    @Test
    fun `没有评估账_读数齐也不算足`() {
        assertFalse(EvolutionWiring.depthEvidenceEnough(proofReady = true, eval = null))
    }

    @Test
    fun `评估账在但没真出读数_不算足`() {
        assertFalse(
            "ran=0 ⇒ 没真评过；passRate=null ⇒ 没读数",
            EvolutionWiring.depthEvidenceEnough(proofReady = true, eval = reading(0, 0, null)),
        )
    }
}
