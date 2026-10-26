// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §3（分类/去重/摘要/建议目录；只出副本计划；
// 一张清单；原件不参与任何移动/改名/删除）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.knowledge.KnowledgeOrganizeRules.Action
import com.example.zhengdao.baopu.knowledge.KnowledgeOrganizeRules.Candidate
import com.example.zhengdao.baopu.knowledge.KnowledgeOrganizeRules.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeOrganizeRulesTest {

    private fun c(
        rel: String,
        sha: String,
        readable: Boolean = true,
        category: Category = KnowledgeOrganizeRules.categoryOf(rel),
        excerpt: String = "正文",
        reason: String = "",
    ) = Candidate(rel, 10, 1, sha, category, readable, excerpt, reason)

    @Test
    fun `分类_按扩展名`() {
        assertEquals(Category.DOC, KnowledgeOrganizeRules.categoryOf("书.md"))
        assertEquals(Category.DOC, KnowledgeOrganizeRules.categoryOf("合同.docx"))
        assertEquals(Category.CODE, KnowledgeOrganizeRules.categoryOf("a.kt"))
        assertEquals(Category.SHEET, KnowledgeOrganizeRules.categoryOf("表.csv"))
        assertEquals(Category.WEB, KnowledgeOrganizeRules.categoryOf("页.html"))
        assertEquals(Category.OTHER, KnowledgeOrganizeRules.categoryOf("图.png"))
    }

    @Test
    fun `去重_同sha后来者跳过_原件都留着`() {
        val plan = KnowledgeOrganizeRules.plan(
            listOf(c("a.md", "sha1"), c("b.md", "sha1"), c("c.md", "sha2")), 1,
        )
        assertEquals(2, plan.adds.size)
        assertEquals(1, plan.duplicates)
        val skip = plan.items.first { it.action == Action.SKIP_DUPLICATE }
        assertEquals("b.md", skip.sourceRel)
        assertTrue(skip.reason.contains("a.md"))
    }

    @Test
    fun `读不出_只记账不产出`() {
        val plan = KnowledgeOrganizeRules.plan(
            listOf(c("图.png", "sha3", readable = false, reason = "图片不认领")), 1,
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unreadable)
        assertTrue(plan.items.single().reason.contains("图片"))
    }

    @Test
    fun `目标_落在整理类目录_名字清洗`() {
        val plan = KnowledgeOrganizeRules.plan(listOf(c("目录/我的 书?.md", "sha4")), 1)
        val item = plan.adds.single()
        assertEquals("整理/doc/我的 书_.md", item.targetRel)
        assertTrue(item.summary.isNotBlank())
        assertTrue(KnowledgeOrganizeRules.targetsSafe(plan))
    }

    @Test
    fun `目标撞名_同sha加后缀仍在整理里`() {
        val plan = KnowledgeOrganizeRules.plan(
            listOf(
                c("a/同.md", "sha5"),
                c("b/同.md", "sha6"),
            ),
            1,
        )
        assertEquals(2, plan.adds.size)
        val targets = plan.adds.map { it.targetRel }.toSet()
        assertEquals("两个不同内容都要活", 2, targets.size)
        assertTrue(plan.adds.all { it.targetRel.startsWith("整理/") })
    }

    @Test
    fun `摘要_收敛空白并截断`() {
        val s = KnowledgeOrganizeRules.summaryOf("  第一行\n\n第二行  " + "x".repeat(300))
        assertFalse(s.contains("\n"))
        assertTrue(s.endsWith("…"))
        assertTrue(s.length <= KnowledgeOrganizeRules.SUMMARY_CHARS + 1)
    }

    @Test
    fun `计划_永不生成原始区目标`() {
        val plan = KnowledgeOrganizeRules.plan(listOf(c("a.md", "sha7")), 1)
        assertTrue(plan.adds.none { it.targetRel.startsWith("原始/") })
        assertTrue(KnowledgeOrganizeRules.targetsSafe(plan))
        assertFalse(KnowledgeOrganizeRules.targetsSafe(plan.copy(items = plan.items.map { it.copy(targetRel = "原始/x.md") })))
    }
}
