// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L22 事后追认与撤销建议**（法典卷七 L22）。
//
// ## 判据（可执行）
// 任何 L21 处置，**24 小时内**必须产出「撤销单」，五栏缺一不可：
//   发生了什么／当时授权了什么／风险／**建议撤销还是保留**／回滚点与影响面。
// 逾期未出 ⇒ **记一次违规**（不是"补一份就算"）。
//
// ## 生产状态（诚实标注）
// 生产码里还没有撤销单（`grep 撤销单 app/src/main` 零命中）⇒ 判据形状门禁，
// 缺陷 #271-L22 在账。
//
// ## 负例（本文件自证会红）
// ① 逾期 24 小时以上 ⇒ 必须记违规；② 五栏缺一 ⇒ 必须拒收；③ 建议栏写"看情况" ⇒ 必须拒收。
package com.example.zhengdao.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L22RevocationSlipGateTest {

    internal enum class Advice(val label: String) { REVOKE("撤销这次授权"), KEEP("保留"), KEEP_TIGHTEN("保留但收紧") }

    internal data class Slip(
        val eventId: String = "emg-1",
        val handledAtMs: Long,
        val producedAtMs: Long,
        val what: String = "冻结了写入",
        val authorized: String = "L25 窄门自执行（白名单：冻结写入）",
        val risk: String = "若判断错，写入延迟 10 分钟",
        val advice: Advice = Advice.KEEP,
        val rollbackPoint: String = "快照 snap-2026-10-28-01",
    )

    internal object Revocation {
        const val WINDOW_MS = 24L * 3600 * 1000

        /** null = 合规；否则是违规理由（**缺哪一栏就点名哪一栏**，不许一句话糊弄）。 */
        fun judge(slip: Slip): String? {
            if (slip.producedAtMs - slip.handledAtMs > WINDOW_MS) {
                return "撤销单逾期（超过 24 小时）⇒ 记一次违规：紧急处置必须当天被追问"
            }
            if (slip.producedAtMs < slip.handledAtMs) {
                return "撤销单的时间早于处置本身（时间倒挂）⇒ 视为没出"
            }
            val missing = listOf(
                "发生了什么" to slip.what,
                "当时授权了什么" to slip.authorized,
                "风险" to slip.risk,
                "回滚点与影响面" to slip.rollbackPoint,
            ).filter { it.second.isBlank() }.map { it.first }
            if (missing.isNotEmpty()) {
                return "撤销单缺项（缺：${missing.joinToString("、")}）⇒ 拒收：五栏缺一不可"
            }
            return null
        }
    }

    private val t0 = 1_800_000_000_000L
    private val hour = 3600_000L

    // ── 正例：24 小时内、五栏齐 ────────────────────────────────────────────

    @Test
    fun `L22_正例_24小时内五栏齐的撤销单合规`() {
        assertNull("刚好 24 小时也算及时", Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + 24 * hour)))
        assertNull("提前出更好", Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + hour)))
        // 三种建议都是合法取值（撤销/保留/保留但收紧）
        Advice.entries.forEach { advice ->
            assertNull("建议「${advice.label}」应当合法", Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + hour, advice = advice)))
        }
    }

    // ── 负例：逾期与缺栏 ───────────────────────────────────────────────────

    @Test
    fun `L22_负例_逾期或五栏缺项必须记违规`() {
        val late = Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + 24 * hour + 1))
        assertTrue("逾期一分钟也算逾期", late != null && late.contains("逾期"))

        val noRollback = Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + hour, rollbackPoint = " "))
        assertTrue("缺回滚点必须拒收：$noRollback", noRollback != null && noRollback.contains("回滚点"))

        val noAdviceField = Revocation.judge(Slip(handledAtMs = t0, producedAtMs = t0 + hour, risk = ""))
        assertTrue("缺风险栏必须拒收：$noAdviceField", noAdviceField != null && noAdviceField.contains("风险"))

        val backwards = Revocation.judge(Slip(handledAtMs = t0 + hour, producedAtMs = t0))
        assertTrue("时间倒挂视为没出", backwards != null)

        assertEquals("三种残缺必须给三种不同理由", 3, setOf(late, noRollback, noAdviceField).size)
    }
}
