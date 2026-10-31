// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十九批 · 双盲比对核（BlindReview）的门禁——工单口径逐条：
// 指纹＝sha256 十六进制 64 位；盲的形状（影子的输入没有自我报告字段）；
// 两枚指纹缺位不许比（缺位＝没做，不是「一致」）；不一致 ⇒ 按 L32 认定口径出场
// （双盲提出位自动真、御批确认位留给人——复核说了不算）。
//
// ## 正例
// 一致 ⇒ CONCUR；不一致 ⇒ DISAGREEMENT 且两枚指纹并列保留；
// DISAGREEMENT 出场成认定后，decide 判「无效」（御批没到）——御批置真那天才收紧。
//
// ## 负例（造违规即红，红在生产比对核上）
// 指纹不合形状（原文当指纹／空串）⇒ 拒比；影子输入的事实为空 ⇒ 拒造；
// CONCUR 拿去造认定 ⇒ 抛。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.law.AccountabilityGate
import com.example.zhengdao.baopu.law.AccountabilityKind
import com.example.zhengdao.baopu.law.AccountabilityStanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindReviewTest {

    private val t0 = 1_800_000_000_000L

    private val input = BlindReviewInput(
        stepId = "step:edit-config:42",
        stepFacts = listOf("file:config.json", "op:write", "bytes:512"),
    )

    // ── 正例：指纹、比对、出场 ──────────────────────────────────────────────────

    @Test
    fun `指纹与比对_一致CONCUR_不一致DISAGREEMENT且两枚指纹并列`() {
        val executor = BlindReview.digestOf(listOf("artifact:a", "conclusion:done"))
        val shadowSame = BlindReview.digestOf(listOf("artifact:a", "conclusion:done"))
        val shadowDiff = BlindReview.digestOf(listOf("artifact:a", "conclusion:differs"))
        // 指纹口径：64 位小写十六进制
        assertTrue(BlindReview.isDigest(executor))
        assertEquals(64, executor.length)
        assertEquals("同材料同指纹（影子重做得到同样的结果）", executor, shadowSame)
        assertNotEquals("不同材料不同指纹", executor, shadowDiff)
        // 一致 ⇒ CONCUR；不一致 ⇒ DISAGREEMENT，两枚指纹谁也不许覆盖谁
        val concur = BlindReview.compare(input, executor, shadowSame)
        assertEquals(BlindReviewOutcome.CONCUR, concur.outcome)
        val disagree = BlindReview.compare(input, executor, shadowDiff)
        assertEquals(BlindReviewOutcome.DISAGREEMENT, disagree.outcome)
        assertEquals(executor, disagree.executorDigest)
        assertEquals(shadowDiff, disagree.shadowDigest)
    }

    @Test
    fun `出场_不一致进L32认定_御批没到判无效_御批置真才收紧`() {
        val executor = BlindReview.digestOf(listOf("a:1"))
        val shadow = BlindReview.digestOf(listOf("a:2"))
        val verdict = BlindReview.compare(input, executor, shadow)
        assertEquals(BlindReviewOutcome.DISAGREEMENT, verdict.outcome)
        // 出场：双盲提出位自动真、御批确认位留给人
        val finding = BlindReview.asFinding(verdict, AccountabilityKind.MISJUDGED_ANOMALY, category = "隔离", atMs = t0)
        assertTrue("影子就是双盲任一方：提出位自动真", finding.raisedByBlindReview)
        assertFalse("御批确认位留给人（复核说了不算）", finding.confirmedByThrone)
        // 御批没到 ⇒ decide 判无效：不收紧、不记账、不攒次数
        val pending = AccountabilityGate.decide(finding, emptyList(), nowMs = t0)
        assertFalse(pending.findingValid)
        assertEquals(AccountabilityStanding.OPEN, pending.standing)
        assertFalse(pending.tightened)
        assertFalse(pending.violationRecorded)
        // 御批置真那天：同一条认定才按累进走（首条 ⇒ 记违规、放行）
        val confirmed = finding.copy(confirmedByThrone = true)
        val valid = AccountabilityGate.decide(confirmed, emptyList(), nowMs = t0)
        assertTrue(valid.findingValid)
        assertTrue(valid.violationRecorded)
        assertEquals(AccountabilityStanding.OPEN, valid.standing)
    }

    // ── 负例：形状关与出场关 ─────────────────────────────────────────────────────

    @Test
    fun `负例_指纹不合形状不许比_影子没事实不许造_CONCUR不许进追责`() {
        val real = BlindReview.digestOf(listOf("a:1"))
        // 原文当指纹／空串 ⇒ 拒比（缺位＝没做，不是「一致」）
        assertThrows(IllegalArgumentException::class.java) {
            BlindReview.compare(input, executorDigest = "结论：一切正常", shadowDigest = real)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlindReview.compare(input, executorDigest = real, shadowDigest = "")
        }
        // 影子没拿到事实 ⇒ 拒造（空事实比出来的「一致」是假的）
        assertThrows(IllegalArgumentException::class.java) {
            BlindReviewInput(stepId = "step:1", stepFacts = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlindReviewInput(stepId = "  ", stepFacts = listOf("a:1"))
        }
        // digestOf 空材料 ⇒ 拒（恒同的假指纹）
        assertThrows(IllegalArgumentException::class.java) { BlindReview.digestOf(emptyList()) }
        // CONCUR 没有认定可提
        val concur = BlindReview.compare(input, real, real)
        assertThrows(IllegalArgumentException::class.java) {
            BlindReview.asFinding(concur, AccountabilityKind.OVER_WHITELIST, category = "隔离", atMs = t0)
        }
    }
}
