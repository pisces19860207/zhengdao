// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 纹状体单测：四格差读数表＋域封死＋不改授权档＋确定性。
// 第七百五十六批 · 追加 corrected 语义收口判据：每次 true 必带 cause/from/to，凭空 true 构造不出来。
// "不投票、不改档"钉法：全四格连打两轮之后，AskGovernance.currentTier 与调用前
// 逐字相同——纹状体收的是布尔，结构上碰不到档位（BioSourceAssertTest 另钉禁引擎符号）。
package com.example.zhengdao.baopu.bio

import com.example.zhengdao.baopu.agent.AskGovernance
import com.example.zhengdao.baopu.agent.AskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StriatumTest {

    /** corrected=false 的构造：没有修正事件。 */
    private fun plain(ok: Boolean): Striatum.Rpe =
        Striatum.rpe(Striatum.Outcome(ok = ok, evidence = null))

    /** corrected=true 的构造：必带一张显式修正事件（cause/from/to）。 */
    private fun amended(
        ok: Boolean,
        cause: String = "USER_NEGATED@skill:查文献",
        from: Int,
        to: Int,
    ): Striatum.Rpe = Striatum.rpe(Striatum.Outcome(ok = ok, evidence = Striatum.Correction(cause, from, to)))

    private fun rpe(ok: Boolean, corrected: Boolean): Striatum.Rpe =
        if (corrected) {
            // 四格表 fixtures：ok+纠（预测 +1 被改成 0）；err+纠（预测落空，from +1 → -1）
            amended(ok, from = 1, to = if (ok) 0 else -1)
        } else {
            plain(ok)
        }

    // ── 四格差读数表（口径见 Striatum 文件头）：err=-1；ok+纠=0；ok+未纠=+1 ──

    @Test
    fun fourCellTableMapsOutcomeToThreeBands() {
        assertEquals(-1, rpe(ok = false, corrected = false).value)
        assertEquals(-1, rpe(ok = false, corrected = true).value) // 失败不因纠正翻案
        assertEquals(0, rpe(ok = true, corrected = true).value) // 成了但被打回：正负相抵
        assertEquals(1, rpe(ok = true, corrected = false).value) // 一次过：正向兑现
    }

    @Test
    fun valueStaysInsideDeclaredDomain() {
        for (ok in listOf(true, false)) {
            for (corrected in listOf(true, false)) {
                assertTrue(rpe(ok, corrected).value in setOf(-1, 0, 1))
            }
        }
    }

    // ── note 只作机械说明＋把"不投票不改档"说在明处 ──

    @Test
    fun noteExplainsBandAndDisclaimsAuthority() {
        val hit = rpe(ok = false, corrected = true)
        assertTrue(hit.note.contains("预测落空"))
        assertTrue(hit.note.contains("不改变失败这个事实"))
        val okPlain = rpe(ok = true, corrected = false)
        assertTrue(okPlain.note.contains("正向兑现"))
        for (ok in listOf(true, false)) {
            for (corrected in listOf(true, false)) {
                val r = rpe(ok, corrected)
                assertTrue(r.note.contains("不投票、不改档"))
            }
        }
    }

    // ── 授权档不变（本批判据）：全四格打两轮，档位与调用前逐字相同 ──

    @Test
    fun askTierIsUntouchedByRpeCalls() {
        val before = AskGovernance.currentTier
        // 探针档位必须与当前档不同——否则"档没被动过"证明不了任何事。
        val probe = if (before == AskTier.DELEGATED) AskTier.STRICT else AskTier.DELEGATED
        AskGovernance.restore(probe)
        for (round in 1..2) {
            for (ok in listOf(true, false)) {
                for (corrected in listOf(true, false)) {
                    rpe(ok, corrected)
                }
            }
        }
        assertEquals(probe, AskGovernance.currentTier)
        AskGovernance.restore(before)
    }

    // ── 确定性（判据④）：同输入两次差读数逐字一致 ──

    @Test
    fun sameInputSameRpeTwice() {
        val outcome = Striatum.Outcome(
            ok = true,
            evidence = Striatum.Correction("USER_NEGATED@skill:查文献", 1, 0),
        )
        assertEquals(Striatum.rpe(outcome), Striatum.rpe(outcome))
    }

    // ── 523 三件套①：二值成败说不出的形态，差读数说得——"成了但被打回"＝0 ──

    @Test
    fun triple1CorrectedSuccessDistinguishableFromPlainSuccess() {
        // 没它（成败二值）：这格只能算"成功"；有它：差读数 0（正负相抵）——同一输入，两种读数。
        assertEquals(0, rpe(ok = true, corrected = true).value)
        assertEquals(1, rpe(ok = true, corrected = false).value)
    }

    // ── 756 判据③：corrected=true 必带 cause/from/to；凭空 true 构造不出来 ──

    @Test
    fun correctionCarriesCauseFromTo() {
        val e = Striatum.Correction("USER_REWROTE@turn:7", 1, 0)
        assertEquals("USER_REWROTE@turn:7", e.cause)
        assertEquals(1, e.from)
        assertEquals(0, e.to)
        val outcome = Striatum.Outcome(ok = true, evidence = e)
        assertTrue(outcome.corrected) // true 只能由 evidence 派生
        assertEquals(0, Striatum.rpe(outcome).value)
    }

    @Test
    fun blankCauseIsRejected() {
        try {
            Striatum.Correction("   ", 1, 0)
            fail("空白 cause 必须被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cause"))
        }
    }

    @Test
    fun outOfDomainFromToIsRejected() {
        for ((from, to) in listOf(2 to 0, -2 to 0, 1 to 2, 1 to -2)) {
            try {
                Striatum.Correction("USER_NEGATED@skill:x", from, to)
                fail("越界的 from/to ($from,$to) 必须被拒")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("{-1,0,+1}"))
            }
        }
    }

    @Test
    fun unchangedFromToIsRejected() {
        for (v in listOf(-1, 0, 1)) {
            try {
                Striatum.Correction("USER_NEGATED@skill:x", v, v)
                fail("from==to ($v) 等于没有修正动作，必须被拒")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("没有发生修正动作"))
            }
        }
    }

    @Test
    fun noEvidenceMeansNotCorrected() {
        val outcome = Striatum.Outcome(ok = true, evidence = null)
        assertTrue(!outcome.corrected)
        assertEquals(1, Striatum.rpe(outcome).value)
    }
}
