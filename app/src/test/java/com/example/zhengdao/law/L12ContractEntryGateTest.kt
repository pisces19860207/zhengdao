// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L12 无契约的技能不得入库**（派工单 271）
// ＋ 同一号在法典卷三的另一半：**学不完要存档**（半成品只能进草稿区，不许进正式区）。
//
// ## 判据（可执行）
// ① 文字技能没有契约（或契约读不成、缺 `success`）⇒ **拒收**，一个字节都不写；
// ② 只有 1 个站点验过 ⇒ 只能 `DraftOnly`（草稿区），**不许**算正式；
// ③ 两站点验过 + 契约齐 ⇒ `Pass`。
//
// ## 负例（本文件自证会红）
// ① 无契约正文若被放行入库 ⇒ 红；
// ② 单站点若被算正式 ⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.EntrySubject
import com.example.zhengdao.baopu.EntryVerdict
import com.example.zhengdao.baopu.Rehearsal
import com.example.zhengdao.baopu.SkillAcceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L12ContractEntryGateTest {

    private val contracted = """
        # 整理台账
        <!-- contract appliesWhen url_domain=a.com -->
        <!-- contract success element_appears=结果 -->
        <!-- contract protected path:/admin -->
        - 按日期排
    """.trimIndent()

    private val twoSites = Rehearsal(sites = listOf("a.com", "b.com"))

    // ── 正例：契约齐 + 两站点 ⇒ 正式 ────────────────────────────────────────

    @Test
    fun `L12_正例_契约齐且两站点验过才进正式区`() {
        val verdict = SkillAcceptance.entryGate(EntrySubject.Prose(contracted), twoSites)
        assertTrue("契约齐 + 两站点应进正式区：$verdict", verdict is EntryVerdict.Pass)
        verdict as EntryVerdict.Pass
        assertFalse("两站点不算单站点", verdict.singleSite)
    }

    // ── 负例①：无契约 ⇒ 拒收 ───────────────────────────────────────────────

    @Test
    fun `L12_负例1_无契约的技能必须拒收`() {
        val bare = "# 整理台账\n- 按日期排，看起来成了就行"
        val verdict = SkillAcceptance.entryGate(EntrySubject.Prose(bare), twoSites)
        assertTrue("无契约的技能被放行入库了（$verdict）", verdict is EntryVerdict.Reject)
        verdict as EntryVerdict.Reject
        assertTrue("拒收理由必须点名 G2（success 不可观察）：${verdict.reason}", verdict.reason.contains("G2"))
        assertTrue("拒收理由要说怎么办", verdict.reason.contains("success"))

        // 契约读不成也算拒（不许兜默认值）
        val broken = "# 整理台账\n<!-- contract appliesWhen -->\n- 步骤"
        assertTrue(
            "契约读不成必须拒收：${SkillAcceptance.entryGate(EntrySubject.Prose(broken), twoSites)}",
            SkillAcceptance.entryGate(EntrySubject.Prose(broken), twoSites) is EntryVerdict.Reject,
        )
    }

    // ── 负例②：单站点 ⇒ 只能草稿 ───────────────────────────────────────────

    @Test
    fun `L12_负例2_单站点只能落草稿_不许进正式区`() {
        val oneSite = Rehearsal(sites = listOf("a.com"))
        val verdict = SkillAcceptance.entryGate(EntrySubject.Prose(contracted), oneSite)
        assertTrue("单站点被算成正式了（$verdict）", verdict is EntryVerdict.DraftOnly)
        verdict as EntryVerdict.DraftOnly
        assertTrue("理由必须点名 G1：${verdict.reason}", verdict.reason.contains("G1"))

        val none = SkillAcceptance.entryGate(EntrySubject.Prose(contracted), Rehearsal())
        assertTrue("一个站点都没验过更不许进正式区（$none）", none is EntryVerdict.DraftOnly)
        assertEquals(
            "两站点与单站点的结论必须不同（否则 G1 没在起作用）",
            false,
            verdict is EntryVerdict.Pass,
        )
    }
}
