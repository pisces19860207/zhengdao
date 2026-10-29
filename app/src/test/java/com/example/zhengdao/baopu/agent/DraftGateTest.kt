// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十八批 §2（③b 核验闸：逐条判 真/假/无法判定；假 ⇒ 打回重写；
// 无法判定 ⇒ 卡面显示未核实；暗卫未合前用桩）＋ §负例（未核验就上御案、
// "无法判定"被写成"真"）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftGateTest {

    private fun claims(n: Int = 2) = (1..n).map { DraftClaim("C$it", "主张$it", "ledger:op:$it") }

    @Test
    fun `负例_桩一律回无法判定_绝不许替没人核过的事说真`() {
        val checks = StubVerifier.verify(claims(3))
        assertEquals(3, checks.size)
        assertTrue(
            "桩要是回「真」，卡面就会显示「已核·真」而实际没人核过——这正是本批负例本体",
            checks.all { it.verdict == ClaimVerdict.UNVERIFIABLE },
        )
    }

    @Test
    fun `判据_桩永远放行但全部标未核实`() {
        val result = DraftGate.check(claims(2))
        assertTrue(result is DraftGateResult.Passed)
        val checks = (result as DraftGateResult.Passed).checks
        assertTrue("有无法判定的条目", DraftGate.hasUnverified(checks))
        assertFalse("没核过就不许说全核过", DraftGate.allVerified(checks))
    }

    @Test
    fun `负例_有一条假就打回重写`() {
        val verifier = ClaimVerifier { list ->
            list.map {
                if (it.id == "C2") ClaimCheck(it.id, ClaimVerdict.FALSE, "日志第 41 行对不上")
                else ClaimCheck(it.id, ClaimVerdict.TRUE, "账本行一致")
            }
        }
        val result = DraftGate.check(claims(2), verifier)
        assertTrue("有假必须打回", result is DraftGateResult.Rejected)
        val rejected = result as DraftGateResult.Rejected
        assertEquals(DraftReject.FALSE_CLAIM, rejected.reason)
        assertEquals(listOf("C2"), rejected.bad.map { it.claimId })
        assertTrue("假必须指得到证据", rejected.bad.first().evidence.isNotBlank())
    }

    @Test
    fun `判据_全真才放行且标注为真`() {
        val verifier = ClaimVerifier { list -> list.map { ClaimCheck(it.id, ClaimVerdict.TRUE, "对上账") } }
        val result = DraftGate.check(claims(2), verifier)
        val checks = (result as DraftGateResult.Passed).checks
        assertTrue(DraftGate.allVerified(checks))
        assertFalse(DraftGate.hasUnverified(checks))
    }

    @Test
    fun `负例_核验方漏回的主张按无法判定补齐_不许当通过`() {
        // 只回第一条，剩下那条没回（核验方出错/漏了）
        val verifier = ClaimVerifier { list -> list.take(1).map { ClaimCheck(it.id, ClaimVerdict.TRUE) } }
        val checks = (DraftGate.check(claims(2), verifier) as DraftGateResult.Passed).checks
        assertEquals(2, checks.size)
        assertEquals(ClaimVerdict.UNVERIFIABLE, checks[1].verdict)
        assertFalse("有一条没核过就不算全核过", DraftGate.allVerified(checks))
    }

    @Test
    fun `判据_主张超过二十条要拆单`() {
        val result = DraftGate.check(claims(DraftGate.MAX_CLAIMS + 1))
        assertTrue(result is DraftGateResult.Rejected)
        assertEquals(DraftReject.TOO_MANY_CLAIMS, (result as DraftGateResult.Rejected).reason)
    }

    @Test
    fun `判据_核验方抛异常时按无法判定处理_不许崩也不许当通过`() {
        val boom = ClaimVerifier { error("暗卫挂了") }
        val checks = (DraftGate.check(claims(2), boom) as DraftGateResult.Passed).checks
        assertTrue(checks.all { it.verdict == ClaimVerdict.UNVERIFIABLE })
    }

    @Test
    fun `判据_日志那行把打回原因与条目都写出来`() {
        val verifier = ClaimVerifier { list -> list.map { ClaimCheck(it.id, ClaimVerdict.FALSE, "对不上") } }
        val line = DraftGate.logLine(DraftGate.check(claims(2), verifier))
        assertTrue(line.contains("打回"))
        assertTrue(line.contains("C1"))
    }
}
