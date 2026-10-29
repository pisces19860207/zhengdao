// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典门禁 **L22 事后追认与撤销建议**（法典卷七 L22）。
//
// 由 271 批的规范（law/spec/L22RevocationSlipSpec.kt，随本批删除）升格：
// 判据已搬进生产 `baopu/law/RevocationSlip.kt`，用例名照 spec 承诺逐字不变（#271-L22 销账）。
//
// ## 判据（可执行，判的是生产 Revocation.judge）
// 任何 L21 处置，**24 小时内**必须产出「撤销单」，五栏缺一不可：
//   发生了什么／当时授权了什么／风险／**建议撤销还是保留**／回滚点与影响面。
// 逾期未出 ⇒ 记一次违规；时间倒挂 ⇒ 视为没出；缺哪栏点名哪栏。
//
// ## 负例（造违规即红，红在生产 judge 上）
// ① 逾期 24 小时以上 ⇒ 必须记违规；② 五栏缺一 ⇒ 必须拒收；③ 时间倒挂 ⇒ 必须拒收。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.Revocation
import com.example.zhengdao.baopu.law.RevocationSlip
import com.example.zhengdao.baopu.law.SlipAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L22RevocationSlipGateTest {

    private val t0 = 1_800_000_000_000L
    private val hour = 3600_000L

    // ── 正例：24 小时内、五栏齐 ────────────────────────────────────────────

    @Test
    fun `L22_正例_24小时内五栏齐的撤销单合规`() {
        assertNull("刚好 24 小时也算及时", Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + 24 * hour)))
        assertNull("提前出更好", Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + hour)))
        // 三种建议都是合法取值（撤销/保留/保留但收紧）
        SlipAdvice.entries.forEach { advice ->
            assertNull(
                "建议「${advice.label}」应当合法",
                Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + hour, advice = advice)),
            )
        }
    }

    // ── 负例：逾期与缺栏 ───────────────────────────────────────────────────

    @Test
    fun `L22_负例_逾期或五栏缺项必须记违规`() {
        val late = Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + 24 * hour + 1))
        assertTrue("逾期一分钟也算逾期", late != null && late.contains("逾期"))

        val noRollback = Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + hour, rollbackPoint = " "))
        assertTrue("缺回滚点必须拒收：$noRollback", noRollback != null && noRollback.contains("回滚点"))

        val noRisk = Revocation.judge(RevocationSlip(handledAtMs = t0, producedAtMs = t0 + hour, risk = ""))
        assertTrue("缺风险栏必须拒收：$noRisk", noRisk != null && noRisk.contains("风险"))

        val backwards = Revocation.judge(RevocationSlip(handledAtMs = t0 + hour, producedAtMs = t0))
        assertTrue("时间倒挂视为没出", backwards != null)

        assertEquals("三种残缺必须给三种不同理由", 3, setOf(late, noRollback, noRisk).size)
    }
}
