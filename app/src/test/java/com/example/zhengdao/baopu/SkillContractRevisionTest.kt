// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十三批 C 段判据 · 修订边界（派工单 §3 两条门槛 ＋ §4 正例「删掉 protected 一条 ⇒ 拒收」与负例⑤⑥）。
//
// 这一段的要害：**「改契约」不等于「改做法」**。步骤/脚本一个字都不许在修订里被动；
// 而且「不回归」这四个字必须有 206 的对拍结论撑着——**没跑就是没证据，没证据就不许采纳**。
// 本批不调模型、不落盘：对拍结论由调用方算好喂进来（206 的口子在那儿，这里不新造机器，见 R5）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillContractRevisionTest {

    private val skillName = "装车核对"

    private fun body(vararg lines: String): String =
        (listOf("# 装车核对") + lines.toList() + listOf("", "## 步骤", "1. 打开装车单列表", "2. 核对装车状态")).joinToString("\n")

    private val current = body(
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract appliesWhen page_has=文案「装车单」 -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
        "<!-- contract protected 不改原件区文件 -->",
    )

    /** 只把域名边界从 example.com 挪到 dispatch.example.com，protected 与步骤都不动。 */
    private val movedBoundary = body(
        "<!-- contract appliesWhen url_domain=dispatch.example.com -->",
        "<!-- contract appliesWhen page_has=文案「装车单」 -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
        "<!-- contract protected 不改原件区文件 -->",
    )

    private fun contractOf(text: String): SkillContract.Contract =
        (SkillContract.parse(text) as SkillContract.Parsed.Contracted).contract

    // ── 206 的口子（结论由调用方算；这里只验「门槛认不认这份结论」）──────────

    private fun fixture(name: String = skillName) = EvalFixture(
        id = "fx-233",
        userText = "把这张装车单核一遍",
        skillName = name,
        skillBodyOld = "旧正文",
        skillBodyNew = "新正文",
        workspaceSnapshotSha = "abcd1234abcd1234",
        preconditions = listOf("网络可用"),
        model = "deepseek/deepseek-flash@t=0.0",
        budget = EvalBudget(maxSteps = 12, timeoutMs = 60_000L),
        seedFiles = emptyList(),
        createdAt = 1_760_000_000_000L,
    )

    private fun attempt(
        ok: Boolean,
        steps: Int = 5,
        products: List<String> = listOf("装车台账.csv:deadbeef"),
    ) = EvalAttempt(
        ok = ok,
        steps = steps,
        toolCalls = listOf("read_file", "write_file"),
        failurePoint = if (ok) null else "第 3 步｜click｜找不到「装车单」",
        elapsedMs = 1_200L,
        tokensIn = 800,
        tokensOut = 120,
        products = products,
    )

    private fun compare(
        verdict: EvalVerdict = EvalVerdict.DEFER,
        name: String = skillName,
        result: List<String> = emptyList(),
        reason: String = "两轮无可判差异",
    ) = EvalCompare(
        fixtureId = "fx-233",
        skillName = name,
        old = attempt(true),
        new = attempt(true, steps = 4),
        behaviorDiff = listOf("步数 5 → 4"),
        resultDiff = result,
        verdict = verdict,
        reason = reason,
    )

    private fun review(proposed: String, cmp: EvalCompare? = compare(), from: String = current) =
        SkillContractRevision.review(skillName, from, proposed, cmp)

    private fun reasons(v: SkillContractRevision.Verdict): String = v.reasons.joinToString("；")

    // ── 门槛本身（两个纯判据）──────────────────────────────────────────────

    @Test
    fun `R1_正文差异只数契约行之外的行`() {
        assertEquals(0, SkillContractRevision.proseDiffLines(current, movedBoundary))
        // 多一条契约行 ⇒ 正文仍是 0 行差异（这正是「改边界」该有的形状）
        assertEquals(0, SkillContractRevision.proseDiffLines(
            current,
            body(
                "<!-- contract appliesWhen url_domain=example.com -->",
                "<!-- contract appliesWhen page_has=文案「装车单」 -->",
                "<!-- contract appliesWhen workspace_has=配货单.xlsx -->",
                "<!-- contract success element_appears=文案「装车状态」 -->",
                "<!-- contract protected 不点提交按钮 -->",
                "<!-- contract protected 不改原件区文件 -->",
            ),
        ))
        // 多一步，就算动了脚本
        assertEquals(1, SkillContractRevision.proseDiffLines(current, "$current\n3. 顺手点个提交"))
        assertTrue(SkillContractRevision.proseDiffLines(current, "# 装车核对\n\n1. 只留一步") > 0)
    }

    @Test
    fun `R2_protected一条没动才算过`() {
        val a = listOf("不点提交按钮", "不改原件区文件")
        assertTrue(SkillContractRevision.protectedUntouched(a, a))
        // 顺序变了不算动（同一条约束不该因为换行位就被判成改语义）
        assertTrue(SkillContractRevision.protectedUntouched(a, a.reversed()))
        assertFalse(SkillContractRevision.protectedUntouched(a, listOf("不点提交按钮")))
        assertFalse(SkillContractRevision.protectedUntouched(a, a + "多点几下无所谓"))
        assertFalse(SkillContractRevision.protectedUntouched(a, listOf("不点提交按钮", "原件区可以改")))
    }

    // ── 四道门槛都过 ───────────────────────────────────────────────────────

    @Test
    fun `R3_改边界加206结论ADOPT_采纳`() {
        val why = "结果变好：旧跑失败、新跑通过"
        val v = review(movedBoundary, compare(EvalVerdict.ADOPT, result = listOf(why), reason = why))
        assertTrue("应采纳：${reasons(v)}", v.accepted)
        assertEquals("ADOPT｜$why", v.compareLine)
        assertNotNull(v.contract)
        assertEquals("dispatch.example.com", v.contract!!.appliesWhen.first().value)
        assertEquals(listOf("不点提交按钮", "不改原件区文件"), v.contract!!.protectedClauses)
        assertTrue(SkillContractRevision.brief(v, skillName), SkillContractRevision.brief(v, skillName).contains("四道门槛都过"))
    }

    @Test
    fun `R4_持平DEFER算不回归_采纳`() {
        val v = review(movedBoundary, compare(EvalVerdict.DEFER))
        assertTrue("持平＝没回归，可以进决策中心：${reasons(v)}", v.accepted)
        assertEquals("DEFER｜两轮无可判差异", v.compareLine)
    }

    @Test
    fun `R5_真调206的对拍口_结论能一路传到门槛`() {
        // 这一条钉「调既有口，不新造对拍机」：结论由 EvalCompareRules 算，本批只负责认它
        val adopt = EvalCompareRules.compare(fixture(), old = attempt(false), new = attempt(true))
        assertEquals(EvalVerdict.ADOPT, adopt.verdict)
        assertTrue(reasons(review(movedBoundary, adopt)), review(movedBoundary, adopt).accepted)

        val reject = EvalCompareRules.compare(fixture(), old = attempt(true), new = attempt(false))
        assertEquals(EvalVerdict.REJECT, reject.verdict)
        val v = review(movedBoundary, reject)
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("206 对拍判 REJECT"))
        assertEquals("只有对拍这一条理由：${reasons(v)}", 1, v.reasons.size)
    }

    // ── 负例⑤：修订动了 protected ──────────────────────────────────────────

    @Test
    fun `R6_删掉protected一条_拒收`() {
        val proposed = body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract appliesWhen page_has=文案「装车单」 -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不改原件区文件 -->",
        )
        val v = review(proposed)
        assertFalse("受保护语义约束被删了一条，必须拒收", v.accepted)
        assertTrue(reasons(v), reasons(v).contains("protected 被动了：原 2 条 → 新 1 条"))
        assertNull(v.contract)
        assertTrue(SkillContractRevision.brief(v, skillName).startsWith("契约边界修订「$skillName」拒收"))
    }

    @Test
    fun `R7_protected换个字也拒收_只换行位不动`() {
        val reword = body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract appliesWhen page_has=文案「装车单」 -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不点提交按钮 -->",
            "<!-- contract protected 原件区可以改 -->",
        )
        assertFalse(review(reword).accepted)
        val reordered = body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract appliesWhen page_has=文案「装车单」 -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不改原件区文件 -->",
            "<!-- contract protected 不点提交按钮 -->",
        )
        val v = review(reordered)
        assertTrue("只是两条的先后换了：${reasons(v)}", v.accepted)
        assertEquals(listOf("不改原件区文件", "不点提交按钮"), v.contract!!.protectedClauses)
    }

    // ── 负例⑥：没跑历史回放就采纳 ──────────────────────────────────────────

    @Test
    fun `R8_没给对拍结论_一律拒收`() {
        val v = review(movedBoundary, cmp = null)
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("没跑 206 历史回放"))
        assertNull(v.compareLine)
        assertNull(v.contract)
    }

    @Test
    fun `R9_对拍结论挂在别的技能上_拒收`() {
        val v = review(movedBoundary, compare(EvalVerdict.ADOPT, name = "另一条技能", result = listOf("结果变好")))
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("对拍的不是这条技能：结论挂在「另一条技能」上"))
    }

    @Test
    fun `R10_结论写着没退化但结果列有产物少了_照样拒收`() {
        // DEFER≠持平：结果列里出现「产物少了」就是退化，光看结论名字会被骗过去
        val v = review(movedBoundary, compare(EvalVerdict.DEFER, result = listOf("产物少了：装车台账.csv:deadbeef")))
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("结果列有退化：产物少了：装车台账.csv:deadbeef"))
    }

    // ── 「改做法」不许借契约这条路进来 ─────────────────────────────────────

    @Test
    fun `R11_正文被动了一行_拒收并报出行数`() {
        val v = review("$movedBoundary\n3. 顺手点提交")
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("修订只许改契约边界：正文（步骤/脚本）动了 1 行"))
        assertEquals(reasons(v), 1, v.reasons.size)
    }

    @Test
    fun `R12_新契约自身立不住_三种都拒收`() {
        val missing = body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
        )
        assertFalse(review(missing).accepted)
        assertTrue(reasons(review(missing)), reasons(review(missing)).contains("新契约还缺 protected"))

        val unknownKind = body(
            "<!-- contract appliesWhen browser=chrome -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不点提交按钮 -->",
        )
        assertFalse(review(unknownKind).accepted)
        assertTrue(reasons(review(unknownKind)), reasons(review(unknownKind)).contains("新契约读不成"))

        val noContract = body()
        assertFalse(review(noContract).accepted)
        assertTrue(reasons(review(noContract)), reasons(review(noContract)).contains("这是撤契约"))
    }

    @Test
    fun `R13_原契约本来就读不成_没有可比基线_拒收`() {
        val broken = body(
            "<!-- contract appliesWhen browser=chrome -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不点提交按钮 -->",
        )
        val v = review(movedBoundary, from = broken)
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("原契约读不成"))
        assertEquals("只有基线这一条理由：${reasons(v)}", 1, v.reasons.size)
    }

    // ── §4 负例⑧的修订侧：老技能补第一条契约，不许被门槛挡死 ────────────────

    @Test
    fun `R14_给没契约的老技能补第一条_四道门槛照样过`() {
        val v = review(current, from = body()) // 老技能一条契约都没有（种子技能就这形状）
        assertTrue("补契约是降级路径的正门：${reasons(v)}", v.accepted)
        assertEquals(2, v.contract!!.protectedClauses.size)
    }

    @Test
    fun `R15_补契约也算行为改变_没跑对拍一样不许采纳`() {
        val v = review(current, cmp = null, from = body())
        assertFalse(v.accepted)
        assertTrue(reasons(v), reasons(v).contains("没跑 206 历史回放"))
    }

    // ── 「改边界」那句话的形状（§3：出提议，不是直接改脚本）─────────────────

    @Test
    fun `R16_判据没兑现时出的是改边界提议`() {
        val unmet = SkillApplicability.verifySuccess(contractOf(current), SkillContext(pageTexts = listOf("合计"))).second
        val line = SkillContractRevision.boundaryProposal(skillName, unmet)
        assertTrue(line, line.contains("判据没兑现"))
        assertTrue(line, line.contains("契约边界"))
        assertTrue(line, line.contains("不是直接改脚本"))
        assertTrue(line, line.contains("装车状态"))
        // 提议里不许夹带「改脚本」的指令（改做法走示教，不走契约）
        assertFalse(line, line.contains("重新录制"))
    }

    @Test
    fun `R17_几处毛病一次说完_不让人来回猜`() {
        // 正文也动了、protected 也动了、对拍也没跑 ⇒ 三条理由一起给
        val proposed = body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success element_appears=文案「装车状态」 -->",
            "<!-- contract protected 不点提交按钮 -->",
        ) + "\n3. 顺手点提交"
        val v = review(proposed, cmp = null)
        assertEquals("三条理由：${reasons(v)}", 3, v.reasons.size)
        assertTrue(reasons(v), reasons(v).contains("修订只许改契约边界"))
        assertTrue(reasons(v), reasons(v).contains("protected 被动了"))
        assertTrue(reasons(v), reasons(v).contains("没跑 206 历史回放"))
        assertFalse(v.accepted)
        assertTrue(SkillContractRevision.brief(v, skillName).contains("拒收"))
    }
}
