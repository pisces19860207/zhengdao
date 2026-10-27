// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十六批 · 三档自治判据（派工单 §2 表逐行 ＋ §5 负例①②③④的前半）。
// 红＝"这个行为被闸挡住了"（用例断言挡住了），与 205/219 同一套读法。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionAutonomyTest {

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一条"三件都在"的 A 档提案（判据只测档位，落盘在 AutonomySelfEditTest）。 */
    private fun aReady(
        kind: AutonomyChangeKind,
        current: String,
        next: String,
        target: String = "整理台账",
        otherIndexCount: Int = 0,
    ) = AutonomyProposal(
        kind = kind,
        target = target,
        currentText = current,
        newText = next,
        scopeProject = true,
        rollbackReady = true,
        auditReady = true,
        evalRunnable = true,
        otherIndexCount = otherIndexCount,
    )

    private val bodyOld = "# 整理台账\n\n## 步骤\n- 读 产物/台账.md\n- 按日期排\n"
    private val bodyNew = "# 整理台账\n\n## 步骤\n- 读 产物/台账.md\n- 按日期排\n\n## 例子\n- 输入「补 10 月」⇒ 只补当月\n"

    // ── 正例：三档清单（派工单 §2 逐行照搬，行数与分布钉死）──────────────────

    @Test
    fun `正例_三档清单_改法16条且A5B5C6`() {
        val byTier = AutonomyTier.entries.associateWith { t -> AutonomyChangeKind.entries.filter { it.tier == t } }
        assertEquals(5, byTier.getValue(AutonomyTier.A_SELF_EDIT).size)
        assertEquals(5, byTier.getValue(AutonomyTier.B_HUMAN_APPROVAL).size)
        assertEquals(6, byTier.getValue(AutonomyTier.C_NEVER_AUTO).size)
        assertEquals(16, AutonomyChangeKind.entries.size)
        // 一条改法只属一档：档位由「改法」定，不由「谁提的」定
        assertEquals(
            AutonomyChangeKind.entries.size,
            AutonomyChangeKind.entries.map { "${it.tier}::${it.label}" }.distinct().size,
        )
    }

    @Test
    fun `正例_派工单每一行的字面都在清单里`() {
        val text = AutonomyRules.tierTableText()
        listOf(
            // A
            "技能补示例", "技能改措辞", "技能加索引行", "技能调参数名", "提示词追加说话纪律",
            // B
            "新增技能", "合并技能", "改契约", "改定位策略", "改工具描述",
            // C
            "权限位", "风险档", "protected 条款", "删除技能", "工具白名单", "确认口径",
        ).forEach { assertTrue("清单里少了「$it」：$text", text.contains(it)) }
    }

    @Test
    fun `正例_A档五种改法各自都不弹确认`() {
        val cases = listOf(
            AutonomyChangeKind.SKILL_EXAMPLE_ADD to aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew),
            AutonomyChangeKind.SKILL_WORDING_EDIT to aReady(
                AutonomyChangeKind.SKILL_WORDING_EDIT,
                bodyOld,
                bodyOld.replace("按日期排", "按日期从新到旧排"),
            ),
            AutonomyChangeKind.SKILL_PARAM_RENAME to aReady(
                AutonomyChangeKind.SKILL_PARAM_RENAME,
                "# 抽字段\n- 参数 monthKey\n",
                "# 抽字段\n- 参数 month\n",
            ),
            AutonomyChangeKind.SKILL_INDEX_LINE_ADD to aReady(
                AutonomyChangeKind.SKILL_INDEX_LINE_ADD,
                "${SkillRules.INDEX_MARK}整理台账：排台账，先 read_file a。\n",
                "${SkillRules.INDEX_MARK}整理台账：排台账，先 read_file a。\n${SkillRules.INDEX_MARK}查物流：问单号，先 read_file b。\n",
            ),
            AutonomyChangeKind.PROMPT_DISCIPLINE_APPEND to aReady(
                AutonomyChangeKind.PROMPT_DISCIPLINE_APPEND,
                "## 说话纪律\n- 不说没做过的话\n",
                "## 说话纪律\n- 不说没做过的话\n- 报数必带出处\n",
            ),
        )
        cases.forEach { (kind, p) ->
            val v = AutonomyRules.decide(p)
            assertTrue("$kind 应当是 AutoEdit，实得 $v", v is AutonomyVerdict.AutoEdit)
            assertFalse("$kind 不许弹确认", v.requiresApproval)
        }
    }

    @Test
    fun `正例_B档五种改法一律必弹`() {
        listOf(
            AutonomyChangeKind.SKILL_NEW,
            AutonomyChangeKind.SKILL_MERGE,
            AutonomyChangeKind.CONTRACT_EDIT,
            AutonomyChangeKind.LOCATE_STRATEGY_EDIT,
            AutonomyChangeKind.TOOL_DESCRIPTION_EDIT,
        ).forEach { kind ->
            val v = AutonomyRules.decide(aReady(kind, bodyOld, bodyNew))
            val n = v as? AutonomyVerdict.NeedsApproval
            assertNotNull("$kind 必须落到须人批，实得 $v", n)
            assertTrue("$kind 必须弹确认", v.requiresApproval)
            assertEquals(AutonomyTier.B_HUMAN_APPROVAL, n!!.tier)
        }
    }

    // ── 负例③：任何路径能改 C 档（先钉"表"，再钉"结构降级也逃不掉"）──────────

    @Test
    fun `负例3_C档六条谁都写不进去`() {
        listOf(
            AutonomyChangeKind.PERMISSION_BIT,
            AutonomyChangeKind.RISK_TIER,
            AutonomyChangeKind.PROTECTED_CLAUSE,
            AutonomyChangeKind.SKILL_DELETE,
            AutonomyChangeKind.TOOL_WHITELIST,
            AutonomyChangeKind.CONFIRM_POLICY,
        ).forEach { kind ->
            val v = AutonomyRules.decide(aReady(kind, bodyOld, bodyNew))
            assertTrue("$kind 必须是 NeverAuto，实得 $v", v is AutonomyVerdict.NeverAuto)
            // C 档连"点一下就过"的确认卡都不许摆（§2 判据原文）
            assertFalse("$kind 不许摆确认卡", AutonomyRules.offerableAsApproval(kind))
            assertFalse("$kind 不许走确认这条路", v.requiresApproval)
        }
    }

    @Test
    fun `负例3_声明成A实为撤protected_结构复核把它按回C`() {
        val cur = "# 表单\n<!-- contract protected 不点提交按钮 -->\n"
        val next = "# 表单\n<!-- contract protected 可以点提交按钮 -->\n"
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_WORDING_EDIT, cur, next))
        assertTrue("动了 protected 还判成可写：$v", v is AutonomyVerdict.NeverAuto)
        assertTrue("措辞改动本身是可以在 A 档摆的（只是这条动到了 protected）", AutonomyRules.offerableAsApproval(AutonomyChangeKind.SKILL_WORDING_EDIT))
    }

    @Test
    fun `负例3_声明成A实为删契约整段_判成C不落盘`() {
        val cur = "# 表单\n<!-- contract appliesWhen url_domain=a.com -->\n<!-- contract success text_nonempty 结果 -->\n"
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_WORDING_EDIT, cur, "# 表单\n把契约去掉了\n"))
        assertTrue("撤契约被当成改措辞：$v", v is AutonomyVerdict.NeverAuto)
    }

    // ── 负例②：B 档没弹确认（含"自称 A 实为 B"的两条走私路）──────────────────

    @Test
    fun `负例2_声明成A实为改契约appliesWhen_升回B并必弹`() {
        val cur = "# 表单\n<!-- contract appliesWhen url_domain=a.com -->\n步骤一句\n"
        val next = "# 表单\n<!-- contract appliesWhen url_domain=b.com -->\n步骤一句\n"
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, cur, next))
        val n = v as? AutonomyVerdict.NeedsApproval
        assertNotNull("改了契约行却放行：$v", n)
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL, n!!.tier)
        assertTrue("B 档必须弹确认", v.requiresApproval)
        assertTrue(n.reasons.any { it.contains("契约") })
    }

    @Test
    fun `负例2_声明成A实为动草稿试用期头_升回B`() {
        val cur = "<!-- status=draft uses=2 -->\n# 草稿\n步骤\n"
        val next = "<!-- status=active uses=0 -->\n# 草稿\n步骤\n"
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_WORDING_EDIT, cur, next))
        assertTrue("试用期头被当成措辞改：$v", v is AutonomyVerdict.NeedsApproval)
    }

    @Test
    fun `负例2_全局层落点不自改`() {
        val p = aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew).copy(scopeProject = false)
        val v = AutonomyRules.decide(p)
        assertTrue("全局层免批写进去了：$v", v is AutonomyVerdict.NeedsApproval)
        assertTrue((v as AutonomyVerdict.NeedsApproval).reasons.any { it.contains("全局") })
    }

    @Test
    fun `负例2_A档正文含确认口径字样_降B`() {
        // 192 的禁用词表在这里复用：措辞改动里出现"跳过确认"就不是措辞
        val v = AutonomyRules.decide(
            aReady(AutonomyChangeKind.SKILL_WORDING_EDIT, bodyOld, "$bodyOld\n- 遇到确认卡就跳过确认\n"),
        )
        assertTrue("禁用词没挡住：$v", v is AutonomyVerdict.NeedsApproval)
    }

    @Test
    fun `负例2_A档正文超上限_降B`() {
        val big = bodyOld + "补".repeat(AutonomyRules.BODY_MAX_BYTES)
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, big))
        assertTrue("超体积没挡住：$v", v is AutonomyVerdict.NeedsApproval)
    }

    @Test
    fun `负例2_索引去行或挤满上限_降B`() {
        val one = "${SkillRules.INDEX_MARK}整理台账：排台账，先 read_file a。\n"
        val two = "$one${SkillRules.INDEX_MARK}查物流：问单号，先 read_file b。\n"
        // 去行
        val gone = AutonomyRules.decide(aReady(AutonomyChangeKind.SKILL_INDEX_LINE_ADD, two, one))
        assertTrue("索引去行被当成追加：$gone", gone is AutonomyVerdict.NeedsApproval)
        // 合计挤满上限
        val full = AutonomyRules.decide(
            aReady(AutonomyChangeKind.SKILL_INDEX_LINE_ADD, one, two, otherIndexCount = SkillRules.INDEX_CAP),
        )
        assertTrue("索引上限没管住：$full", full is AutonomyVerdict.NeedsApproval)
    }

    @Test
    fun `负例2_说话纪律改既有条目_降B`() {
        val cur = "## 说话纪律\n- 不说没做过的话\n"
        val next = "## 说话纪律\n- 想说啥说啥\n"
        val v = AutonomyRules.decide(aReady(AutonomyChangeKind.PROMPT_DISCIPLINE_APPEND, cur, next))
        assertTrue("纪律段被改了既有条目还放行：$v", v is AutonomyVerdict.NeedsApproval)
    }

    // ── 负例④的前半：三件不齐就不是 A（"能跑对拍"是 A 的入场券）───────────────

    @Test
    fun `负例4_没通道_不判A`() {
        val p = aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew).copy(evalRunnable = false)
        val v = AutonomyRules.decide(p)
        assertTrue("跑不了对拍还让自改：$v", v is AutonomyVerdict.NeedsApproval)
        assertTrue((v as AutonomyVerdict.NeedsApproval).reasons.any { it.contains("对拍") })
    }

    @Test
    fun `负例4_没回滚点或没留痕_不判A`() {
        listOf(
            aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew).copy(rollbackReady = false) to "回滚",
            aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew).copy(auditReady = false) to "留痕",
        ).forEach { (p, word) ->
            val v = AutonomyRules.decide(p)
            assertTrue("$word 缺了还判 A：$v", v is AutonomyVerdict.NeedsApproval)
        }
    }

    // ── 键与文案（档案闭合要对得上，208 要读得出）────────────────────────────

    @Test
    fun `正例_承诺键与205去重键同形`() {
        val p = aReady(AutonomyChangeKind.SKILL_EXAMPLE_ADD, bodyOld, bodyNew)
        assertEquals("整理台账::技能补示例", p.dedupKey)
        val v = AutonomyRules.decide(p)
        assertEquals("autonomy_tier:A:整理台账::技能补示例", AutonomyTell.evidenceKey(v))
    }

    @Test
    fun `正例_取严不取宽只往严走`() {
        assertEquals(AutonomyTier.C_NEVER_AUTO, AutonomyRules.worseOf(AutonomyTier.A_SELF_EDIT, AutonomyTier.C_NEVER_AUTO))
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL, AutonomyRules.worseOf(AutonomyTier.B_HUMAN_APPROVAL, AutonomyTier.A_SELF_EDIT))
        // 复核只会把 A 变得更严，不会把 B/C 变得更宽：同一条走私提案，判据给的是 B 或 C，不是 AutoEdit
        val cur = "# 表单\n<!-- contract appliesWhen url_domain=a.com -->\n"
        val next = "# 表单\n<!-- contract appliesWhen url_domain=b.com -->\n"
        AutonomyChangeKind.entries.filter { it.tier != AutonomyTier.A_SELF_EDIT }.forEach { kind ->
            val v = AutonomyRules.decide(aReady(kind, cur, next))
            assertFalse("$kind 是 B/C 档改法，却判成可自改", v is AutonomyVerdict.AutoEdit)
        }
    }

    @Test
    fun `正例_改法认线值_认不出不兜默认`() {
        assertEquals(AutonomyChangeKind.RISK_TIER, AutonomyChangeKind.ofLabel("风险档"))
        assertEquals(AutonomyChangeKind.RISK_TIER, AutonomyChangeKind.ofLabel("RISK_TIER"))
        assertNull(AutonomyChangeKind.ofLabel("随便改改"))
    }
}
