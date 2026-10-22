// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十四批 · 纯函数边界测试第二批（第 5 组）。
//
// 派工单点名的 `SkillRules` 只有索引渲染/解析/增删与上限，没有草稿/转正 API；
// 生命周期（草稿头、uses 累加、转正线）在 `SkillStore` 的纯函数里。本组两块都测：
// ① SkillRules：上限两侧、空场景与重复同名的手改边界；
// ② SkillStore：草稿/转正/晋级边界（用满 2/3/4 次、无头、大小写、负 uses）。
// 只读判据，不写盘。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRulesEdgeTest {

    // ── ① SkillRules：索引上限 ────────────────────────────────────────────

    @Test
    fun `上限判定_恰好20通过_21拒绝_自定义上限0`() {
        assertTrue(SkillRules.capCheck(SkillRules.INDEX_CAP) is SkillRules.CapResult.Ok)
        val over = SkillRules.capCheck(SkillRules.INDEX_CAP + 1)
        assertTrue(over is SkillRules.CapResult.Over)
        assertEquals(SkillRules.INDEX_CAP + 1, (over as SkillRules.CapResult.Over).total)
        assertEquals(SkillRules.INDEX_CAP, over.cap)

        assertTrue("cap=0 且总数为 0 仍通过", SkillRules.capCheck(0, cap = 0) is SkillRules.CapResult.Ok)
        assertTrue(SkillRules.capCheck(1, cap = 0) is SkillRules.CapResult.Over)
        assertTrue("负数总数按通过（防御性下限）", SkillRules.capCheck(-1, cap = 0) is SkillRules.CapResult.Ok)
    }

    @Test
    fun `增索引_另一层恰好占满前一位仍可加_占满就拒`() {
        val line = SkillRules.IndexLine(MemoryScope.PROJECT, "新技能", "新场景")
        val ok = SkillRules.addIndexLine(null, line, otherLayerCount = SkillRules.INDEX_CAP - 1)
        assertTrue("19 + 本层 1 = 20 ⇒ Ok", ok is SkillRules.EditResult.Ok)
        val over = SkillRules.addIndexLine(null, line, otherLayerCount = SkillRules.INDEX_CAP)
        assertTrue("20 + 本层 1 = 21 ⇒ OverCap", over is SkillRules.EditResult.OverCap)
    }

    // ── ① SkillRules：解析/删除的手改边界 ────────────────────────────────

    @Test
    fun `解析_空场景渲染后尾部提示被当场景回读`() {
        val rendered = SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "空场景", ""))
        val parsed = SkillRules.parseIndexLines(rendered, MemoryScope.PROJECT).single()
        assertEquals("空场景", parsed.name)
        assertEquals(
            "当前实现：tailIdx == 0 不剥，尾部提示原样当成场景",
            "，先 read_file `技能/空场景/SKILL.md` 按它做。",
            parsed.scenario,
        )
    }

    @Test
    fun `解析_首个冒号前才是名字`() {
        val parsed = SkillRules.parseIndexLines("【技能】甲：场景：带冒号", MemoryScope.PROJECT).single()
        assertEquals("甲", parsed.name)
        assertEquals("场景：带冒号", parsed.scenario)
    }

    @Test
    fun `解析_空名字残行忽略_CRLF不误判`() {
        assertTrue(SkillRules.parseIndexLines("【技能】：场景", MemoryScope.PROJECT).isEmpty())
        assertTrue(SkillRules.parseIndexLines("【技能】缺冒号", MemoryScope.PROJECT).isEmpty())
        val crlf = SkillRules.parseIndexLines("【技能】甲：场景\r\n正文", MemoryScope.PROJECT).single()
        assertEquals("场景", crlf.scenario)
    }

    @Test
    fun `删索引_同名重复只删第一条`() {
        val one = SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "甲", "场景"))
        val text = "$one\n$one\n"
        val result = SkillRules.removeIndexLine(text, "甲")
        assertTrue(result is SkillRules.EditResult.Ok)
        assertEquals(
            "当前实现按 removed 标记只删第一条",
            1,
            SkillRules.parseIndexLines((result as SkillRules.EditResult.Ok).newText, MemoryScope.PROJECT).size,
        )
    }

    @Test
    fun `删索引_名字与冒号之间有空格不认`() {
        val r = SkillRules.removeIndexLine("【技能】甲 ：场景\n", "甲")
        assertTrue("名字必须紧贴冒号，空格版按 NotFound", r is SkillRules.EditResult.NotFound)
    }

    // ── ② SkillStore：草稿 / 转正 / 晋级 ─────────────────────────────────

    @Test
    fun `草稿判定_用满3次才可转正_正式技能不可`() {
        assertEquals("active", SkillStore.statusOf("纯正文"))
        assertEquals(0, SkillStore.usesOf("纯正文"))
        assertFalse(SkillStore.canPromote("纯正文"))

        fun draft(uses: Int) = SkillStore.draftHeader(uses = uses) + "\n正文"
        assertFalse("用满前一次不行", SkillStore.canPromote(draft(2)))
        assertTrue("恰好 3 次可转正", SkillStore.canPromote(draft(3)))
        assertTrue("超过 3 次仍可转正", SkillStore.canPromote(draft(4)))
        assertFalse(
            "已转正（active）不再走转正口",
            SkillStore.canPromote(SkillStore.draftHeader(status = "active", uses = 3) + "\n正文"),
        )
    }

    @Test
    fun `累加一次_无头正文补active头_有头原地加`() {
        val bare = SkillStore.bumpUses("正文")
        assertTrue("无头按 active 补", bare.startsWith("<!-- status=active uses=1 -->\n正文"))
        assertEquals(1, SkillStore.usesOf(bare))

        val draft = SkillStore.bumpUses(SkillStore.draftHeader(uses = 2) + "\n正文")
        assertEquals(3, SkillStore.usesOf(draft))
        assertEquals("draft", SkillStore.statusOf(draft))
        assertTrue("累加到线即可转正", SkillStore.canPromote(draft))
    }

    @Test
    fun `草稿头形制_只有草稿写正文时带头`() {
        assertEquals("<!-- status=draft uses=0 -->", SkillStore.draftHeader())
        assertEquals("<!-- status=promoted uses=5 -->", SkillStore.draftHeader(status = "promoted", uses = 5))

        val body = "正文"
        val draft = SkillProposal(SkillScope.PROJECT, "甲", body, "理由", draft = true)
        val formal = draft.copy(draft = false)
        assertEquals("草稿头在第一行", "<!-- status=draft uses=0 -->\n$body", SkillStore.withDraftHeader(draft))
        assertEquals("正式技能不加头", body, SkillStore.withDraftHeader(formal))
    }

    @Test
    fun `状态大小写与负uses按当前实现`() {
        val upper = "<!-- status=Draft uses=3 -->\n正文"
        assertEquals("status 原样返回，不做大小写归一", "Draft", SkillStore.statusOf(upper))
        assertFalse("大写 Draft 不等于 draft ⇒ 不能转正", SkillStore.canPromote(upper))

        val negative = "<!-- status=draft uses=-1 -->\n正文"
        assertEquals("负数不被 \\d+ 匹配 ⇒ 读成 0", 0, SkillStore.usesOf(negative))
        assertFalse(SkillStore.canPromote(negative))
    }
}
