// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §4 判据 K1/K2/K8 与 §5 负例 2/3 的靶子。
// 纯函数 + java.io.File 临时目录，JVM 直测（SkillRules / PersonaRules / BaopuSql 全零 Android）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SkillRulesTest {

    private fun tmpDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "skill-test-$name-${System.nanoTime()}")

    // ── K1 两层解析：项目优先、全局兜底、同名项目赢 ────────────────────────

    @Test
    fun `K1_两层查找_项目优先_全局兜底_同名项目赢`() {
        val project = tmpDir("proj")
        val global = tmpDir("glob")
        try {
            // 都没有 ⇒ null
            assertNull(SkillRules.resolveSkillFile(project, global, "整理书稿"))
            // 只有全局 ⇒ 全局兜底
            val g = File(File(SkillRules.globalSkillDir(global), "整理书稿"), SkillRules.SKILL_FILE)
            g.parentFile.mkdirs(); g.writeText("全局版")
            assertEquals(MemoryScope.GLOBAL, SkillRules.resolveSkillFile(project, global, "整理书稿")?.scope)
            // 项目也有 ⇒ 项目赢（同名覆盖）
            val p = File(File(SkillRules.projectSkillDir(project), "整理书稿"), SkillRules.SKILL_FILE)
            p.parentFile.mkdirs(); p.writeText("项目版")
            val hit = SkillRules.resolveSkillFile(project, global, "整理书稿")
            assertEquals(MemoryScope.PROJECT, hit?.scope)
            assertEquals(p.absolutePath, hit?.file?.absolutePath)
        } finally {
            project.deleteRecursively(); global.deleteRecursively()
        }
    }

    // ── 索引渲染 ⇄ 解析 ───────────────────────────────────────────────────

    @Test
    fun `索引渲染解析往返_场景原样_路径提示剥掉`() {
        val line = SkillRules.IndexLine(MemoryScope.PROJECT, "整理书稿", "遇到拆章与转编码")
        val rendered = SkillRules.renderIndexLine(line)
        assertTrue(rendered.startsWith(SkillRules.INDEX_MARK))
        assertTrue(rendered.contains("read_file `技能/整理书稿/SKILL.md`"))
        val parsed = SkillRules.parseIndexLines(rendered, MemoryScope.PROJECT).single()
        assertEquals("整理书稿", parsed.name)
        assertEquals("遇到拆章与转编码", parsed.scenario) // 渲染拼的路径提示不进 scenario
    }

    @Test
    fun `解析忽略非索引行与残行`() {
        val text = "正文第一行\n【技能】好的技能：场景描述\n【技能】缺场景\n随手一提【技能】不在行首\n"
        val lines = SkillRules.parseIndexLines(text, MemoryScope.PROJECT)
        assertEquals(1, lines.size)
        assertEquals("好的技能", lines[0].name)
    }

    // ── K2 索引上限 20：拒绝并提示，不是截断 ───────────────────────────────

    @Test
    fun `K2_两层合计超20_加第21条被拒且文本原样`() {
        val existing = (1..20).joinToString("\n") {
            SkillRules.renderIndexLine(
                SkillRules.IndexLine(MemoryScope.PROJECT, "技能$it", "场景$it"),
            )
        }
        val cap = SkillRules.capCheck(21)
        assertTrue("第 21 条必须判 Over", cap is SkillRules.CapResult.Over)
        assertEquals(21, (cap as SkillRules.CapResult.Over).total)
        val add = SkillRules.addIndexLine(
            existing,
            SkillRules.IndexLine(MemoryScope.PROJECT, "新技能", "新场景"),
            otherLayerCount = 0,
        )
        assertTrue("addIndexLine 也要拒（OverCap，不是截断）", add is SkillRules.EditResult.OverCap)
        // 文本原样：不许悄悄塞半条进去
        assertEquals(existing, (add as SkillRules.EditResult.OverCap).let { existing })
        assertEquals(20, SkillRules.parseIndexLines(existing, MemoryScope.PROJECT).size)
    }

    @Test
    fun `K2对偶_20条以内正常追加_两层合计计入`() {
        val global = SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.GLOBAL, "全局技", "场景"))
        val project = SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "项目技", "场景"))
        assertEquals(2, SkillRules.totalIndexCount(global, project))
        // 本层 0 条、另一层 19 条 ⇒ 本层加 1 条后合计 20，仍 Ok
        val add = SkillRules.addIndexLine(
            null, SkillRules.IndexLine(MemoryScope.PROJECT, "项目技", "场景"),
            otherLayerCount = 19,
        )
        assertTrue(add is SkillRules.EditResult.Ok)
        // 本层 0 条、另一层 20 条 ⇒ 合计 21，拒
        val reject = SkillRules.addIndexLine(
            null, SkillRules.IndexLine(MemoryScope.PROJECT, "项目技", "场景"),
            otherLayerCount = 20,
        )
        assertTrue(reject is SkillRules.EditResult.OverCap)
    }

    // ── 增删：同名替换 ＋ 近似替换 ＋ 删 ───────────────────────────────────

    @Test
    fun `同名索引_替换原行不新增`() {
        val current = SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "整理书稿", "旧场景"))
        val add = SkillRules.addIndexLine(
            current, SkillRules.IndexLine(MemoryScope.PROJECT, "整理书稿", "新场景"),
        )
        val newText = (add as SkillRules.EditResult.Ok).newText
        val lines = SkillRules.parseIndexLines(newText, MemoryScope.PROJECT)
        assertEquals(1, lines.size)
        assertEquals("新场景", lines[0].scenario)
    }

    @Test
    fun `场景近似去重_Jaccard阈值同口径`() {
        val current = SkillRules.renderIndexLine(
            SkillRules.IndexLine(MemoryScope.PROJECT, "整理书稿", "遇到拆章转编码导出书稿类任务先读文档"),
        )
        val near = SkillRules.addIndexLine(
            current, SkillRules.IndexLine(MemoryScope.PROJECT, "另一名字", "遇到拆章转编码导出书稿类任务先读文档"),
        )
        val newText = (near as SkillRules.EditResult.Ok).newText
        assertEquals(1, SkillRules.parseIndexLines(newText, MemoryScope.PROJECT).size)
    }

    @Test
    fun `K8_删索引_只删行_文件原样在`() {
        val project = tmpDir("proj")
        try {
            // 技能正文文件先落好（文件是真相；删索引不删文件）
            val skill = File(File(SkillRules.projectSkillDir(project), "整理书稿"), SkillRules.SKILL_FILE)
            skill.parentFile.mkdirs(); skill.writeText("技能正文")
            val current = SkillRules.renderIndexLine(
                SkillRules.IndexLine(MemoryScope.PROJECT, "整理书稿", "场景"),
            ) + "\n其他正文\n"
            val result = SkillRules.removeIndexLine(current, "整理书稿")
            val newText = (result as SkillRules.EditResult.Ok).newText
            assertFalse(newText.contains(SkillRules.INDEX_MARK))
            assertTrue(newText.contains("其他正文"))
            // 文件还在（K8：删索引不删文件）
            assertTrue(skill.isFile)
            assertEquals("技能正文", skill.readText())
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `删不存在的索引_NotFound`() {
        val r = SkillRules.removeIndexLine("【技能】甲：场景\n", "乙")
        assertTrue(r is SkillRules.EditResult.NotFound)
    }
}

/**
 * K3/K4/K5（人设既有机制断言）＋ v7 迁移语句（封卷两列）。
 * K3/K4/K5 派工单点名「现有机制断言」——本仓此前没有 PersonaFiles 直测，这里补上。
 */
class PersonaAndCloseStmtsTest {

    private fun tmpDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "persona-test-$name-${System.nanoTime()}")

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    // ── K3 超限可见截断（现有机制） ───────────────────────────────────────

    @Test
    fun `K3_C层超8000tokens_截断且标记可见`() {
        // 中文按字符 ×1.0 ⇒ 9000 个汉字 ≈ 9000 × 1.15 > 8000
        val huge = "长".repeat(9000)
        val segments = PersonaRules.assemble("内核", null, huge)
        val c = segments.last { it.layer == PersonaLayer.C_PROJECT }
        assertTrue("超限必须置 truncated（不许静默）", c.truncated)
        assertTrue("截断标记要进文本", c.text.contains(PersonaRules.TRUNCATION_MARK))
        val ok = PersonaRules.assemble("内核", null, "短的")
        assertFalse(ok.last { it.layer == PersonaLayer.C_PROJECT }.truncated)
    }

    // ── K4 未确认不落盘（sha256 前后一致） ─────────────────────────────────

    @Test
    fun `K4_非PENDING提案写不进人设_文件字节不变`() {
        val global = tmpDir("glob")
        try {
            val pf = PersonaFiles(globalRoot = global, projectRoot = tmpDir("proj"))
            val initial = "人设原文"
            // 先合法落一版（PENDING 提案 = 确认卡要应用的那份）
            val first = PersonaRules.propose(PersonaLayer.B_GLOBAL, "", initial, "初版")!!
            assertTrue(pf.applyProposal(first))
            val file = pf.globalFile()
            val before = sha256(file)
            // REJECTED 提案 ⇒ canApply false ⇒ applyProposal false ⇒ 字节不变
            val rejected = first.copy(status = PersonaProposalStatus.REJECTED)
            assertFalse(PersonaRules.canApply(rejected))
            assertFalse(pf.applyProposal(rejected))
            assertEquals(before, sha256(file))
            // 已 APPLIED 的旧提案再 apply 也不行（状态不是 PENDING）
            val applied = first.copy(status = PersonaProposalStatus.APPLIED)
            assertFalse(pf.applyProposal(applied))
            assertEquals(before, sha256(file))
        } finally {
            global.deleteRecursively()
        }
    }

    // ── K5 A 内核不可提案 ─────────────────────────────────────────────────

    @Test
    fun `K5_A内核提案恒被拒`() {
        assertNull(PersonaRules.propose(PersonaLayer.A_KERNEL, "内核", "改成别的", "理由"))
        assertFalse(PersonaRules.canWrite(PersonaLayer.A_KERNEL))
    }

    // ── v7 迁移语句：封卷两列（幂等） ─────────────────────────────────────

    @Test
    fun `v7封卷两列_缺哪列给哪条_重复给空`() {
        val stmts = BaopuSql.sessionCloseColumnsStmt(emptyList())
        assertEquals(2, stmts.size)
        assertTrue(stmts[0].sql.contains("closed_at"))
        assertTrue(stmts[1].sql.contains("refined_at"))
        // 列已在 ⇒ 空列表（幂等，重复跑不炸）
        val again = BaopuSql.sessionCloseColumnsStmt(listOf("closed_at", "refined_at", "id"))
        assertTrue(again.isEmpty())
    }

    @Test
    fun `封卷SQL形状_自检只认closed有refined无`() {
        val closed = BaopuSql.markSessionClosed(3L, 1_700L)
        assertTrue(closed.sql.contains("closed_at = ?"))
        assertTrue(closed.sql.contains("closed_at IS NULL"))
        val refined = BaopuSql.markSessionRefined(3L, 1_701L)
        assertTrue(refined.sql.contains("refined_at = ?"))
        assertTrue(refined.sql.contains("refined_at IS NULL"))
        val list = BaopuSql.listUnclosedRefinements()
        assertTrue(list.sql.contains("closed_at IS NOT NULL"))
        assertTrue(list.sql.contains("refined_at IS NULL"))
    }
}
