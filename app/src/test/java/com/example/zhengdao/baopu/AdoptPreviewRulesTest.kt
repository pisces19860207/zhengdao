// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十八批夹具：**高风险采纳确认层的 old/new 必须与真实改动逐字对应**。
//
// 缺陷 539-(5) 的现象是「前＝人设全文、后＝一段工具约束」，前后文对不上。所以这几条断言
// 全是**逐字相等**（不用 contains／不用长度近似）：
//   A1 单处改动：added() 去掉的那一段必须逐字等于 candidates 里那一句；
//   A2 多字段同时改：每一段各就各位（旧文里 0 次、新文里 1 次、顺序不挪）；
//   A3 首写（原正文为空）：后就是那段，不是空；
//   A4 原正文尾部空白：正文一字不改，只在后面接一段；
//   A5 技能正文：后是**真会写进去的包装后正文**，不是候选 payload（payload 只是「场景」一节）；
//   A6 技能落点：预览读的「前」与 `SkillStore.plan` 要写的是**同一个文件**。
package com.example.zhengdao.baopu

import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdoptPreviewRulesTest {

    /** 一条典型的人设正文（C 层：本项目人设）。 */
    private val persona = "① 说话简短，先给结论。\n② 不替用户做不可逆的决定。"

    /** 一条典型的工具约束候选（payload 本来只是「要追加的一段」）。 */
    private val toolRule = "工具使用约束：同一工具连续失败两次就停下，把失败原样报给我。"

    @Test
    fun `A1_单处改动_前后逐字对应且变化段就是那一句`() {
        val p = AdoptPreviewRules.personaAppend(persona, toolRule)

        assertEquals("「采纳前」必须是盘上那份正文（逐字）", persona, p.before)
        assertEquals(
            "「采纳后」必须是真会写进去的正文（原正文 ＋ 空行 ＋ 新增段）",
            persona + AdoptPreviewRules.APPEND_SEPARATOR + toolRule,
            p.after,
        )
        assertEquals("变化的那一段必须逐字等于候选正文", toolRule, p.added())

        // 互换/取增量都会在这两条上当场红：
        assertEquals("旧正文里不该出现新加的那句", 0, countOf(persona, toolRule))
        assertEquals("新正文里那句只出现一次", 1, countOf(p.after, toolRule))
        assertTrue("「采纳后」必须以「采纳前」为前缀（追加式改动）", p.after.startsWith(p.before.trimEnd()))
    }

    @Test
    fun `A2_多字段同时改_每一段各就各位不错位`() {
        val payload = listOf(
            "工具使用约束：同一工具连续失败两次就停下。",
            "引用要求：只引正文里出现的原句，不许改写。",
            "落盘要求：写工作区之前先说清写哪个文件。",
        ).joinToString("\n")

        val p = AdoptPreviewRules.personaAppend(persona, payload)

        assertEquals(persona + AdoptPreviewRules.APPEND_SEPARATOR + payload, p.after)
        assertEquals("三段一起改，变化段仍是这三段原文（逐字、顺序不变）", payload, p.added())

        // 逐段对位：旧文 0 次、新文 1 次、三段在 after 里出现的先后与 payload 一致
        var last = -1
        payload.split("\n").forEach { line ->
            assertEquals("旧正文里不该有这一段：$line", 0, countOf(persona, line))
            assertEquals("新正文里这一段只该出现一次：$line", 1, countOf(p.after, line))
            val at = p.after.indexOf(line)
            assertTrue("三段顺序被挪了：$line", at > last)
            last = at
        }
    }

    @Test
    fun `A3_首写_原正文为空时后就是新增段`() {
        val p = AdoptPreviewRules.personaAppend("", toolRule)
        assertEquals("首写没有「前」", "", p.before)
        assertEquals("首写的「后」就是这段正文（不是空、不是空行开头）", toolRule, p.after)
        assertEquals(toolRule, p.added())
    }

    @Test
    fun `A4_原正文尾部有空行_正文一字不改只接一段`() {
        val withBlankTail = persona + "\n\n\n"
        val p = AdoptPreviewRules.personaAppend(withBlankTail, toolRule)

        assertEquals("「前」原样呈现（尾部空白不吞）", withBlankTail, p.before)
        assertEquals(persona + AdoptPreviewRules.APPEND_SEPARATOR + toolRule, p.after)
        assertEquals(toolRule, p.added())
        assertTrue("旧正文那两行一字不改地留在「后」里", p.after.startsWith(persona))
    }

    @Test
    fun `A5_技能正文_后是包装后的正文而不是候选payload`() {
        val previous = "# 技能：旧技能\n\n## 场景\n旧场景\n"
        val learn = SkillRefine.LearnProposal(
            id = "suggestion-新约束",
            content = toolRule,
            source = "进化建议卡",
            suggest = SkillRefine.Suggest.DRAFT,
            fromUser = false,
        )
        val body = SkillRefine.skillBodyFor(learn)

        val p = AdoptPreviewRules.skillBody(previous, body)
        assertEquals("「前」= 会被覆盖的那个文件的现读正文（逐字）", previous, p.before)
        assertEquals("「后」= 真会写进去的正文（逐字）", body, p.after)
        assertNull("技能正文是整篇替换，不是追加式改动 ⇒ 变化段无定义", p.added())

        // 反向：拿候选 payload 冒充「后」的解（缺陷原样）在这条上必红
        assertTrue("包装后的正文必须先有技能标题", p.after.startsWith("# 技能："))
        assertTrue("包装后的正文里带着『## 场景』一节", p.after.contains("## 场景"))
        assertTrue("候选正文是被包进「场景」一节的，不是整篇", p.after != toolRule)
    }

    @Test
    fun `A6_技能落点与plan同口径_预览读的就是会被覆盖的文件`() {
        TempSandbox.use("zd618-adopt-preview") { root ->
            val name = "测试 技能"
            val proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = name,
                body = "# 技能：$name\n\n## 场景\nx\n",
                reason = "夹具",
                draft = true,
            )
            val plan = SkillStore.plan(proposal, root, File(root, "全局"), "20261112-220000")
            assertEquals(
                "预览读「前」的文件必须就是 plan 要写的那个（否则前后文不是同一个文件的前后）",
                plan.file.absolutePath,
                AdoptPreviewRules.skillTargetFile(root, name)?.absolutePath,
            )
            assertNull(
                "名字全是符号 ⇒ 没有落点（与 plan 的『未命名』拒绝同向）",
                AdoptPreviewRules.skillTargetFile(root, "***"),
            )
        }
    }

    private fun countOf(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var at = haystack.indexOf(needle)
        var n = 0
        while (at >= 0) {
            n++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return n
    }
}
