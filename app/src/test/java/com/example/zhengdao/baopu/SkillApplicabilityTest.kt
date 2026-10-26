// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十三批 B 段判据 · 适用性过滤（派工单 §2 四条规则 ＋ §4 正例与负例①③④⑧）。
//
// 这一段的要害是**fail-closed**：「没有读数」永远不算满足，「缺契约」永远轮不到自动选中。
// 所以下面每一条都在问同一件事——**它有没有替没验过的东西背书**。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillApplicabilityTest {

    private fun applies(vararg lines: String): String =
        (listOf("# 装车核对") + lines.toList() + listOf("", "## 步骤", "1. 打开装车单列表")).joinToString("\n")

    /** 一条三段齐、只卡域名的技能（派工单 §4 正例那条）。 */
    private val domainOnly = applies(
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
    )

    private val twoConditions = applies(
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract appliesWhen page_has=文案「装车单」 -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
    )

    /** 三段齐、但域名与现场相反的一条（拿它验「不满足的不参与排序、只带原因」）。 */
    private val otherDomain = applies(
        "<!-- contract appliesWhen url_domain=example.net -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
    )

    private val noContract = applies()

    private fun cand(name: String, body: String, uses: Int = 0) = SkillCandidate(name, body, uses)

    private fun fit(name: String, body: String, ctx: SkillContext): SkillFitResult =
        SkillApplicability.assess(name, body, 0, ctx)

    private fun contractOf(body: String): SkillContract.Contract =
        (SkillContract.parse(body) as SkillContract.Parsed.Contracted).contract

    // ── §2 规则 1＋§4 正例：该用才用，不该用时说清为什么不该用 ────────────────

    @Test
    fun `D1_正例_在iana_org上不被自动选中并说明原因`() {
        val ctx = SkillContext(url = "https://www.iana.org/domains/reserved")
        val r = fit("装车核对", domainOnly, ctx)
        assertEquals(SkillFit.UNMATCHED, r.fit)
        assertFalse("不满足前提不许自动选中", r.autoSelectable)
        assertNull(SkillApplicability.autoPick(SkillApplicability.rank(listOf(cand("装车核对", domainOnly)), ctx)))
        // 原因要指着读数说，不许一句「不适用」打发
        assertTrue(r.note, r.note.contains("www.iana.org"))
        assertTrue(r.note, r.note.contains("example.com"))
        val check = r.checks.single()
        assertEquals(SkillContract.SECTION_APPLIES, check.section)
        assertEquals(SkillReading.NO, check.reading)
    }

    @Test
    fun `D2_正例_在example_com上正常选中`() {
        val ctx = SkillContext(url = "https://example.com/orders")
        val r = fit("装车核对", domainOnly, ctx)
        assertEquals(SkillFit.MATCHED, r.fit)
        assertTrue(r.autoSelectable)
        assertEquals(1, r.matchedCount)
        assertEquals("装车核对", SkillApplicability.autoPick(SkillApplicability.rank(listOf(cand("装车核对", domainOnly)), ctx))?.name)
    }

    @Test
    fun `D3_子域算满足_长得像的不算`() {
        assertEquals(SkillFit.MATCHED, fit("a", domainOnly, SkillContext(url = "https://tel.example.com/")).fit)
        // 只有「相等或以 .值 结尾」才是这一站；撞名的另一站不算
        assertEquals(SkillFit.UNMATCHED, fit("b", domainOnly, SkillContext(url = "https://notmyexample.com/")).fit)
        assertEquals(SkillFit.UNMATCHED, fit("c", domainOnly, SkillContext(url = "https://example.com.evil.net/")).fit)
    }

    @Test
    fun `D4_域名读数怎么从地址里取出来`() {
        assertEquals("www.example.com", SkillApplicability.hostOf("HTTPS://www.Example.com:8443/path?x=1#f"))
        assertEquals("example.com", SkillApplicability.hostOf("example.com."))
        assertEquals("example.com", SkillApplicability.hostOf("user:pw@example.com/a"))
        assertEquals("example.com", SkillApplicability.hostOf("http://example.com"))
        assertNull(SkillApplicability.hostOf(null))
        assertNull(SkillApplicability.hostOf("   "))
        assertNull(SkillApplicability.hostOf(":::"))
        assertNull(SkillApplicability.hostOf("x"))
    }

    @Test
    fun `D5_页面文案条件_有没有读数要分得清`() {
        val full = fit("a", twoConditions, SkillContext(url = "https://example.com", pageTexts = listOf("本月装车单据", "合计")))
        assertEquals(SkillFit.MATCHED, full.fit)
        assertEquals(SkillReading.YES, full.checks[1].reading)
        // 页面上没有那段文案 ⇒ NO（不是「判不了」）
        val no = fit("b", twoConditions, SkillContext(url = "https://example.com", pageTexts = listOf("合计")))
        assertEquals(SkillReading.NO, no.checks[1].reading)
        assertEquals(SkillFit.PARTIAL, no.fit)
        // 压根没给页面快照 ⇒ NO_DATA，绝不能当满足
        val nodata = fit("c", twoConditions, SkillContext(url = "https://example.com"))
        assertEquals(SkillReading.NO_DATA, nodata.checks[1].reading)
        assertEquals(SkillFit.PARTIAL, nodata.fit)
        assertTrue(nodata.note, nodata.note.contains("没有页面文案的读数"))
    }

    @Test
    fun `D6_工作区文件条件支持通配与反斜杠`() {
        val body = applies(
            "<!-- contract appliesWhen workspace_has=配货单-*.xlsx -->",
            "<!-- contract success text_nonempty=结果区 -->",
            "<!-- contract protected 不改原件 -->",
        )
        assertEquals(SkillFit.MATCHED, fit("a", body, SkillContext(workspaceFiles = listOf("C:\\工作区\\资料库\\整理\\配货单-10月.xlsx"))).fit)
        // 目录里换个名字 ⇒ 不算
        assertEquals(SkillFit.UNMATCHED, fit("b", body, SkillContext(workspaceFiles = listOf("配货单模板.xlsx"))).fit)
        // 没有文件清单读数 ⇒ NO_DATA
        val nodata = fit("c", body, SkillContext())
        assertEquals(SkillReading.NO_DATA, nodata.checks.single().reading)
        assertFalse(nodata.autoSelectable)
    }

    @Test
    fun `D7_文案取值几种写法`() {
        assertEquals("装车单", SkillApplicability.needleOf("文案「装车单」"))
        assertEquals("装车单", SkillApplicability.needleOf("「装车单」"))
        assertEquals("装车单", SkillApplicability.needleOf("\"装车单\""))
        assertEquals("装车单", SkillApplicability.needleOf("文案装车单"))
        assertEquals("结果区", SkillApplicability.needleOf("结果区"))
    }

    // ── §2 规则 2＋3：部分满足只是候选，排序按满足条数 ───────────────────────

    @Test
    fun `D8_部分满足标前提不全_且不参与自动选中`() {
        val ranking = SkillApplicability.rank(
            listOf(cand("装车核对", twoConditions, uses = 7)),
            SkillContext(url = "https://example.com"),
        )
        assertTrue(ranking.selectable.isEmpty())
        val weak = ranking.weakCandidates.single()
        assertEquals(SkillFit.PARTIAL, weak.fit)
        assertEquals(1, weak.matchedCount)
        assertFalse(weak.autoSelectable)
        assertTrue(weak.note, weak.note.startsWith("前提不全"))
        assertTrue(weak.note, weak.note.contains("装车单"))
        assertEquals(7, weak.uses)
        assertNull(SkillApplicability.autoPick(ranking))
    }

    @Test
    fun `D9_多候选按满足条数排_再按uses排`() {
        val oneCond = applies(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success element_appears=文案「状态」 -->",
            "<!-- contract protected 不点提交 -->",
        )
        val threeCond = applies(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract appliesWhen page_has=文案「装车单」 -->",
            "<!-- contract appliesWhen workspace_has=配货单.xlsx -->",
            "<!-- contract success element_appears=文案「状态」 -->",
            "<!-- contract protected 不点提交 -->",
        )
        val ctx = SkillContext(
            url = "https://example.com",
            pageTexts = listOf("装车单据"),
            workspaceFiles = listOf("配货单.xlsx"),
        )
        val ranking = SkillApplicability.rank(
            listOf(
                cand("少条件", oneCond, uses = 99),
                cand("三条件", threeCond, uses = 1),
                cand("同三条", threeCond, uses = 5),
            ),
            ctx,
        )
        assertEquals(listOf(3, 3, 1), ranking.selectable.map { it.matchedCount })
        assertEquals(listOf("同三条", "三条件", "少条件"), ranking.selectable.map { it.name })
        // uses 只在条数相同的组里决定先后（用得再多也不许越过更对口的）
        assertEquals(99, ranking.selectable.last().uses)
        assertEquals("少条件", ranking.selectable.last().name)
    }

    @Test
    fun `D10_不满足的不参与排序_只带原因`() {
        val ranking = SkillApplicability.rank(
            listOf(
                cand("乙不匹配", otherDomain, uses = 50),
                cand("甲没契约", noContract),
                cand("丙匹配", domainOnly),
            ),
            SkillContext(url = "https://example.com"),
        )
        assertEquals(listOf("丙匹配"), ranking.selectable.map { it.name })
        assertTrue(ranking.weakCandidates.isEmpty())
        // excluded 不参与排序：按名字列，读数多的那条不许靠前排
        assertEquals(listOf("乙不匹配", "甲没契约"), ranking.excluded.map { it.name })
        assertTrue(ranking.excluded.all { !it.autoSelectable })
        val missing = ranking.excluded.first { it.name == "甲没契约" }
        assertEquals(SkillFit.UNKNOWN_CONTRACT, missing.fit)
        assertTrue(missing.note, missing.note.contains("contract_missing"))
        assertTrue(missing.checks.isEmpty())
        val unmatched = ranking.excluded.first { it.name == "乙不匹配" }
        assertEquals(SkillFit.UNMATCHED, unmatched.fit)
        assertEquals(50, unmatched.uses)
    }

    // ── §2 规则 4：缺契约按「未知」处理（负例①的闸）─────────────────────────

    @Test
    fun `D11_缺契约读不成不全_三种都按未知处理`() {
        val absent = fit("甲", noContract, SkillContext(url = "https://example.com"))
        assertEquals(SkillFit.UNKNOWN_CONTRACT, absent.fit)
        assertTrue(absent.note, absent.note.contains("contract_missing"))

        val broken = fit("乙", applies("<!-- contract appliesWhen browser=chrome -->"), SkillContext())
        assertEquals(SkillFit.UNKNOWN_CONTRACT, broken.fit)
        assertTrue(broken.note, broken.note.contains("契约读不成"))

        val partialContract = fit("丙", applies("<!-- contract appliesWhen url_domain=example.com -->"), SkillContext())
        assertEquals(SkillFit.UNKNOWN_CONTRACT, partialContract.fit)
        assertTrue(partialContract.note, partialContract.note.contains("契约不全"))
        // 契约有但缺 success ⇒ 仍带得出已读到的那部分（人看得见还缺什么）
        assertNotNull(partialContract.contract)
        assertTrue(partialContract.contract!!.appliesWhen.isNotEmpty())

        listOf(absent, broken, partialContract).forEach {
            assertFalse("${it.name} 不许被自动选中", it.autoSelectable)
        }
        // 哪怕这条老技能用得最多、域名也对得上，缺契约照样轮不到自动选中
        assertNull(SkillApplicability.autoPick(
            SkillApplicability.rank(listOf(cand("甲", noContract, 99)), SkillContext(url = "https://example.com")),
        ))
    }

    @Test
    fun `D12_负例⑧_缺契约只挡自动_人点名照样能跑`() {
        listOf(SkillFit.MATCHED, SkillFit.PARTIAL, SkillFit.UNMATCHED, SkillFit.UNKNOWN_CONTRACT).forEach {
            assertTrue("$it 必须允许人手动点名", SkillApplicability.manualPickAllowed(it))
        }
        // 现有种子技能一条契约都没有：加了契约这件事之后，它们仍能被点出来跑
        val seeded = fit("微头条流程", "# 微头条流程\n\n1. 打开后台", SkillContext())
        assertEquals(SkillFit.UNKNOWN_CONTRACT, seeded.fit)
        assertTrue(SkillApplicability.manualPickAllowed(seeded.fit))
        assertFalse(seeded.autoSelectable)
    }

    // ── §1 规则 1：回放前提示先补契约 ───────────────────────────────────────

    @Test
    fun `D13_回放前的提示语`() {
        val note = SkillApplicability.preReplayNote(noContract)
        assertNotNull(note)
        assertTrue(note!!, note.contains("先补契约"))
        assertTrue(note.contains("contract_missing"))
        assertTrue(note.contains("照你的意思跑"))
        assertNull(SkillApplicability.preReplayNote(domainOnly))
        assertNull(SkillApplicability.preReplayNote(twoConditions))
        // 读不成也要提醒（而且把错处一起说）
        assertTrue(SkillApplicability.preReplayNote(applies("<!-- contract success finished=ok -->"))!!
            .contains("未知的成功判据类型"))
    }

    // ── §1「必须可观察」＋§4 负例④：判据不满足就是失败 ──────────────────────

    @Test
    fun `D14_正例_success满足判成功_不满足判失败`() {
        val c = contractOf(domainOnly)
        val ok = SkillApplicability.verifySuccess(c, SkillContext(pageTexts = listOf("装车状态：已完成", "合计")))
        assertTrue(ok.first)

        val bad = SkillApplicability.verifySuccess(c, SkillContext(pageTexts = listOf("合计")))
        assertFalse("页面上没有那个元素 ⇒ 判失败（模型说什么不算）", bad.first)
        val why = bad.second.single().why
        assertTrue(why, why.contains("跑完页面上没有"))
        assertTrue(why.contains("装车状态"))
    }

    @Test
    fun `D15_负例④_没有读数绝不当成成功`() {
        val noData = SkillApplicability.verifySuccess(contractOf(domainOnly), SkillContext())
        assertFalse("没有页面读数 ⇒ 判不了 ⇒ 不许判成功", noData.first)
        assertEquals(SkillReading.NO_DATA, noData.second.single().reading)

        val region = contractOf(applies(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success text_nonempty=结果区 -->",
            "<!-- contract protected 不点提交 -->",
        ))
        assertFalse(SkillApplicability.verifySuccess(region, SkillContext()).first)
        assertFalse(SkillApplicability.verifySuccess(region, SkillContext(regions = mapOf("结果区" to "   "))).first)
        assertTrue(SkillApplicability.verifySuccess(region, SkillContext(regions = mapOf("结果区" to "共 12 车"))).first)
        // 一条 success 都没有的契约判不了成功（不齐的契约本来就轮不到这里）
        assertFalse(SkillApplicability.verifySuccess(
            SkillContract.Contract(
                listOf(SkillContract.Condition(SkillContract.ConditionKind.URL_DOMAIN, "example.com")),
                emptyList(),
                listOf("不点提交"),
            ),
            SkillContext(pageTexts = listOf("anything")),
        ).first)
    }

    @Test
    fun `D16_NO与NO_DATA分开记账`() {
        val r = fit("甲", twoConditions, SkillContext())
        assertEquals(SkillFit.UNMATCHED, r.fit)
        assertTrue(r.note, r.note.startsWith("判不了前提"))
        assertTrue(r.checks.all { it.reading == SkillReading.NO_DATA })
        // 一条有读数（不符）＋一条没读数 ⇒ 一条 YES 都拿不到，仍是「不满足」不是「部分满足」
        val mixed = fit("乙", twoConditions, SkillContext(url = "https://other.com"))
        assertEquals(listOf(SkillReading.NO, SkillReading.NO_DATA), mixed.checks.map { it.reading })
        assertEquals(SkillFit.UNMATCHED, mixed.fit)
    }

    @Test
    fun `D17_同一份正文同一组读数_两次评估一字不差`() {
        val ctx = SkillContext(url = "https://example.com", pageTexts = listOf("装车单据"))
        val candidates = listOf(cand("装车核对", twoConditions, 3), cand("没契约", noContract, 8))
        val a = SkillApplicability.rank(candidates, ctx)
        val b = SkillApplicability.rank(candidates, ctx)
        assertEquals(a, b)
        // 评估只读候选，不改候选：名字与条数都不动
        assertEquals(listOf("装车核对", "没契约"), candidates.map { it.name })
        assertEquals(listOf("装车核对"), a.selectable.map { it.name })
        // 评估结果里带得出契约原件（调用方不必自己再读一遍盘）
        assertEquals(contractOf(twoConditions), a.selectable.single().contract)
        assertTrue(a.excluded.single().note.contains("contract_missing"))
    }
}
