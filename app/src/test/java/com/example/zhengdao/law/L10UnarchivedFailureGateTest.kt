// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L10 失败不得静默（行为面）**——本文件是**门禁**，会真红。
//
// ## 为什么这份是门禁
// 它断言的是**生产对象** `UnarchivedFailureCheck`：同一能力近 7 天失败 ≥3 次、而 208 里既没有
// 退役行、正文也没软删 ⇒ 勘验必须给 HIT。生产把阈值改宽、把归档判定改松，这条**当场红**。
// （271-补：原来 L10 整份是"规范"，因为它一半判据没接线；现在按"能红的就该进门禁账"拆开，
//   这一半留在门禁账上，另一半见 `spec/L10SilentCatchSpec.kt`。）
//
// ## 判据（可执行）
// ① 已收拾（退役）⇒ CLEAR、count=0；
// ② 3 次失败未归档 ⇒ HIT、count=1、结论行与证据都指名道姓；
// ③ 2 次不够（阈值是 3）——防"一次就报"。
//
// ## 负例（本文件自证会红）
// 阈值 `>=` 改 `>`（3 次不再算命中）⇒ `L10_负例1_三次失败未归档必须报` 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.SkillFailure
import com.example.zhengdao.baopu.inspect.UnarchivedFailureCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L10UnarchivedFailureGateTest {

    private val now = 1_000_000_000L

    private fun failures(times: Int) = (1..times).map { SkillFailure("日报整理", now - it * 60_000L) }

    // ── 正例：失败被收拾了 ⇒ 不报 ───────────────────────────────────────────

    @Test
    fun `L10_正例_失败已归档则勘验不报`() {
        val finding = UnarchivedFailureCheck.check(
            failures = failures(3),
            archivedNames = setOf("日报整理"),
            nowMs = now,
        )
        assertEquals("已退役的技能不该再被报「未归档」", InspectVerdict.CLEAR, finding.verdict)
        assertEquals("命中数必须是 0", 0, finding.count)
        assertTrue("CLEAR 也要给一行结论", finding.oneLine.isNotBlank())
    }

    // ── 负例：三次失败没人收拾 ⇒ 必须报 ────────────────────────────────────

    @Test
    fun `L10_负例1_三次失败未归档必须报`() {
        val finding = UnarchivedFailureCheck.check(
            failures = failures(3),
            archivedNames = emptySet(),
            nowMs = now,
        )
        assertEquals("反复失败没人收拾必须出结论（HIT）", InspectVerdict.HIT, finding.verdict)
        assertEquals("命中条数", 1, finding.count)
        assertTrue("结论行要指名道姓", finding.oneLine.contains("日报整理"))
        assertTrue("证据要指到那条技能", finding.evidence.any { it.contains("日报整理") })

        // 两次不够（阈值是 3）——防「一次就报」的假门禁
        val twice = UnarchivedFailureCheck.check(
            failures = failures(2),
            archivedNames = emptySet(),
            nowMs = now,
        )
        assertFalse("两次不该报", twice.verdict == InspectVerdict.HIT)
    }
}
