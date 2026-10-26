// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §3/§4（整理一次出一张清单；原件零丢失；
// 产物落 整理/；撞名不覆盖；审计可回滚）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.library.FaceIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeOrganizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun workspace(): File = tmp.newFolder("ws").also { KnowledgeLayout.ensureDirs(it) }

    @Test
    fun `整理_只写副本_原件sha不变_二遍跳过`() {
        val w = workspace()
        val a = write(KnowledgeLayout.rawDir(w), "书.md", "# 第一章\n正文甲")
        val d = write(KnowledgeLayout.rawDir(w), "笔记.txt", "正文乙")
        write(KnowledgeLayout.rawDir(w), "图.png", "不是图")
        val beforeA = FaceIndex.sha256(a)
        val beforeD = FaceIndex.sha256(d)

        val plan = KnowledgeOrganize.propose(w, now = 1_000L)
        assertEquals(2, plan.adds.size)
        assertEquals("png 读不出只记账", 1, plan.unreadable)
        val out = KnowledgeOrganize.execute(w, plan, now = 1_000L)
        assertEquals(2, out.created)

        assertEquals("原件必须逐字节不变", beforeA, FaceIndex.sha256(a))
        assertEquals(beforeD, FaceIndex.sha256(d))
        val product = File(KnowledgeLayout.knowledgeDir(w), plan.adds.first { it.sourceRel == "书.md" }.targetRel)
        assertTrue(product.isFile)
        assertTrue(product.readText().contains("原件未动"))

        val again = KnowledgeOrganize.execute(w, KnowledgeOrganize.propose(w, now = 2_000L), now = 2_000L)
        assertEquals("同内容再跑 ⇒ 跳过，不重写", 0, again.created)
        assertEquals(2, again.skipped)
    }

    @Test
    fun `撞名不同内容_加sha后缀_不覆盖已有产物`() {
        val w = workspace()
        write(KnowledgeLayout.rawDir(w), "a.md", "新正文")
        val target = File(KnowledgeLayout.organizedDir(w), "doc/a.md")
        target.parentFile?.mkdirs()
        target.writeText("旧产物", Charsets.UTF_8)

        val out = KnowledgeOrganize.execute(w, KnowledgeOrganize.propose(w, now = 3_000L), 3_000L)
        assertEquals(1, out.created)
        assertEquals("旧产物", target.readText())
        val siblings = target.parentFile.listFiles()!!.filter { it.name.startsWith("a.") && it.name.endsWith(".md") }
        assertEquals(2, siblings.size)
    }

    @Test
    fun `台账可回滚_只删副本_原件还在`() {
        val w = workspace()
        val a = write(KnowledgeLayout.rawDir(w), "书.md", "# 标题\n正文")
        val plan = KnowledgeOrganize.propose(w, now = 4_000L)
        val out = KnowledgeOrganize.execute(w, plan, 4_000L)
        assertEquals(1, out.created)
        val product = File(KnowledgeLayout.knowledgeDir(w), plan.adds.single().targetRel)
        assertTrue(product.isFile)
        assertTrue(KnowledgeLayout.organizeAuditFile(w).isFile)

        val removed = KnowledgeOrganize.rollback(w, plan.runId)
        assertEquals(1, removed)
        assertFalse(product.exists())
        assertTrue("原件永不参与回滚删除", a.isFile)
    }

    @Test
    fun `越界计划整批拒绝`() {
        val w = workspace()
        val bad = KnowledgeOrganizeRules.Plan(
            "run",
            listOf(
                KnowledgeOrganizeRules.Item(
                    KnowledgeOrganizeRules.Action.ADD, "a.md", "原始/a.md",
                    KnowledgeOrganizeRules.Category.DOC, "sha", "",
                ),
            ),
        )
        val out = KnowledgeOrganize.execute(w, bad, 5_000L)
        assertEquals(0, out.created)
        assertEquals(1, out.failed)
    }

    @Test
    fun `清单只有一张_不是每文件一张`() {
        val w = workspace()
        repeat(5) { i -> write(KnowledgeLayout.rawDir(w), "f$i.md", "内容$i") }
        val plan = KnowledgeOrganize.propose(w, now = 6_000L)
        assertEquals(5, plan.adds.size)
        assertTrue(plan.summaryLine.contains("整理清单"))
    }
}
