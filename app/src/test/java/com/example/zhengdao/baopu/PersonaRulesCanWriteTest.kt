// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十四批 · 纯函数边界测试第二批（第 4 组）。
//
// `PersonaRules.canWrite` 的 A 恒禁老用例（SkillRulesTest.K5）只覆盖了 A；
// 本组把 B/C 作用域、提案的空值与去空白、appendOnly 的空原文/不增长/尾部空白、
// 组装时的 8k 恰好边界一起钉住，不改实现。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaRulesCanWriteTest {

    @Test
    fun `canWrite_A恒禁_BC可写`() {
        PersonaLayer.values().forEach { layer ->
            when (layer) {
                PersonaLayer.A_KERNEL -> assertFalse("A 内核恒禁：$layer", PersonaRules.canWrite(layer))
                PersonaLayer.B_GLOBAL -> assertTrue("B 全局可提案：$layer", PersonaRules.canWrite(layer))
                PersonaLayer.C_PROJECT -> assertTrue("C 项目可提案：$layer", PersonaRules.canWrite(layer))
            }
        }
    }

    @Test
    fun `propose_A内核_空正文与合法正文都拒`() {
        assertNull(PersonaRules.propose(PersonaLayer.A_KERNEL, "内核", "改成别的", "理由"))
        assertNull(PersonaRules.propose(PersonaLayer.A_KERNEL, "内核", "", "理由"))
        assertNull(PersonaRules.propose(PersonaLayer.A_KERNEL, "", "  ", "  "))
    }

    @Test
    fun `propose_BC空正文拒_正常提案去首尾空白`() {
        assertNull(PersonaRules.propose(PersonaLayer.B_GLOBAL, "旧", "   ", "理由"))
        assertNull(PersonaRules.propose(PersonaLayer.C_PROJECT, "旧", "\n\t", "理由"))
        val p = PersonaRules.propose(PersonaLayer.C_PROJECT, "旧正文", "  新正文  ", "  理由  ")!!
        assertEquals(PersonaLayer.C_PROJECT, p.layer)
        assertEquals("原文不动", "旧正文", p.current)
        assertEquals("新正文去首尾空白", "新正文", p.proposed)
        assertEquals("理由去首尾空白", "理由", p.reason)
        assertEquals(PersonaProposalStatus.PENDING, p.status)
    }

    @Test
    fun `appendOnly_空原文只收非空追加`() {
        assertFalse("空对空不算追加", PersonaRules.appendOnly("", ""))
        assertFalse("只有空白也不算", PersonaRules.appendOnly("", "  \n"))
        assertTrue("空白原文 + 有内容 ⇒ 算首次写入", PersonaRules.appendOnly("   ", "x"))
        assertTrue(PersonaRules.appendOnly("", "x"))
    }

    @Test
    fun `appendOnly_必须严格变长且前缀不变`() {
        assertFalse("原样重写不算", PersonaRules.appendOnly("abc", "abc"))
        assertFalse("改短不算", PersonaRules.appendOnly("abc", "ab"))
        assertFalse("前缀被改不算", PersonaRules.appendOnly("abc", "xabc"))
        assertTrue(PersonaRules.appendOnly("abc", "abcd"))
        assertFalse("同长度但前缀不同也不行", PersonaRules.appendOnly("abc", "abd"))
    }

    @Test
    fun `appendOnly_尾部空白不算变长`() {
        assertFalse("只多空白不算追加", PersonaRules.appendOnly("abc", "abc \n"))
        assertTrue("去尾空白后仍更长 ⇒ 放行", PersonaRules.appendOnly("abc\n", "abc\nx"))
        assertTrue(PersonaRules.appendOnly("abc  ", "abc  x"))
    }

    @Test
    fun `assemble_空层跳过_顺序恒ABC`() {
        val onlyKernel = PersonaRules.assemble("内核", null, "   ")
        assertEquals(listOf(PersonaLayer.A_KERNEL), onlyKernel.map { it.layer })

        val all = PersonaRules.assemble("内核", " B 段 ", " C 段 ")
        assertEquals(
            listOf(PersonaLayer.A_KERNEL, PersonaLayer.B_GLOBAL, PersonaLayer.C_PROJECT),
            all.map { it.layer },
        )
        assertEquals(
            "空白 B 层跳过，C 层仍在",
            listOf(PersonaLayer.A_KERNEL, PersonaLayer.C_PROJECT),
            PersonaRules.assemble("内核", "   ", " C ").map { it.layer },
        )
    }

    @Test
    fun `assemble_恰好8000不截断_8001截断可见`() {
        val text = "abcdefgh"
        val atLimit = PersonaRules.assemble("k", null, text, tokensOf = { 8_000 })
        val exact = atLimit.last { it.layer == PersonaLayer.C_PROJECT }
        assertFalse("恰好到上限不算超", exact.truncated)
        assertEquals(text, exact.text)

        val over = PersonaRules.assemble("k", null, text, tokensOf = { 8_001 })
        val clipped = over.last { it.layer == PersonaLayer.C_PROJECT }
        assertTrue("超一 token 就要标截断", clipped.truncated)
        assertEquals("按比例留 10% 缓冲后保留 7 个字符", "abcdefg", clipped.text.removeSuffix(PersonaRules.TRUNCATION_MARK))
        assertTrue(clipped.text.endsWith(PersonaRules.TRUNCATION_MARK))
    }
}
