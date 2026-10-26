// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十六批 G6 单测：技能退役再回滚，**整份人设字节不变**。
//
// 222 真机实测：三条索引行文本逐字还原了，但整份 `人设.md` 569 B → 567 B——
// 只少首行空行与文末换行（`PersonaRules.propose` 对 proposed 做了 `.trim()`）。
// 这里用同构样本（首行空行＋三条索引＋文末换行）走一次真盘：
// 删除 → 归档 → restoreArchived ⇒ 字节与 sha256 必须与删除前**逐位一致**。
//
// 第三条索引行由 `SkillStore.plan` 现算——盘上那一行当初就是这么写出来的
// （场景＝正文首句），手抄一遍只会测到"我抄得像不像"，测不到 G6 本身。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SkillRestoreBytesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ws: File by lazy { tmp.newFolder("ws") }
    private val global: File by lazy { tmp.newFolder("app") }

    private val archivedBody = "# 周报汇总\n数字只从台账里取，不编造。"

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun line(name: String, scenario: String): String = SkillRules.renderIndexLine(
        SkillRules.IndexLine(com.example.zhengdao.baopu.MemoryScope.PROJECT, name, scenario),
    )

    /** 回滚会补的那一行：与 `restoreArchived` 内部用同一份输入现算（生产里就是这么生成的）。 */
    private fun restoreLine(): String {
        val planned = SkillStore.plan(
            SkillProposal(
                scope = SkillScope.PROJECT,
                name = "周报汇总",
                body = stripStatusHeader(archivedBody),
                reason = "归档恢复",
                draft = SkillStore.statusOf(archivedBody) == "draft",
            ),
            ws,
            global,
            backupStamp = "",
        )
        return SkillRules.renderIndexLine(planned.indexEntry)
    }

    /** 同构样本：首行空行＋三条索引＋文末换行（222 的 569 B 就是这个形状）。 */
    private fun personaText(): String = buildString {
        append('\n')
        append(line("产物清理", "清理工作区里过期的产物文件与临时目录，保留最近三十天，并回报清掉多少"))
        append('\n')
        append(line("日志归档", "把本周日志按天归档到资料库，缺天补空，最后给出缺哪几天"))
        append('\n')
        append(restoreLine())
        append('\n')
    }

    private fun archiveRow(): ArchivedSkillRow {
        val dir = File(SkillRules.projectSkillDir(ws), "周报汇总").apply { mkdirs() }
        val archived = File(dir, "SKILL.md.deleted-20261026-091331")
        archived.writeText(archivedBody, Charsets.UTF_8)
        return ArchivedSkillRow(
            name = "周报汇总",
            scope = SkillScope.PROJECT,
            file = archived,
            target = File(dir, SkillRules.SKILL_FILE),
            relPath = "技能/周报汇总/${archived.name}",
            targetRelPath = "技能/周报汇总/${SkillRules.SKILL_FILE}",
            archivedAt = "20261026-091331",
        )
    }

    @Test
    fun `退役后回滚_人设字节与sha256逐位还原_首尾空行不丢`() {
        val pf = PersonaFiles(globalRoot = global, projectRoot = ws)
        val index = pf.projectFile()
        index.parentFile?.mkdirs()
        val before = personaText().toByteArray(Charsets.UTF_8)
        index.writeBytes(before)
        val shaBefore = sha256(before)
        assertTrue("样本要真的带首行空行", before[0] == '\n'.code.toByte())

        // 退役那一半：索引去行（SkillStore.archive 的同款判据）＋正文改名归档
        val removed = SkillRules.removeIndexLine(personaText(), "周报汇总") as SkillRules.EditResult.Ok
        index.writeText(removed.newText, Charsets.UTF_8)
        assertTrue("退役后确实少了这一行", !index.readText(Charsets.UTF_8).contains("周报汇总"))

        val row = archiveRow()
        val out = restoreArchived(row, ws, global, pf)

        assertEquals(ArchivedOutcome.Restored("周报汇总", RestoredIndex.Added), out)
        assertTrue("正文搬回原位", row.target.isFile)
        val after = index.readBytes()
        assertEquals(
            "回滚前后必须逐位一致（222 事故：${before.size} B → ${after.size} B）",
            before.size,
            after.size,
        )
        assertEquals("sha256 必须一致", shaBefore, sha256(after))
        // 两句人话的判据：首行空行与文末换行都在
        assertTrue("首行空行不许丢", index.readText(Charsets.UTF_8).startsWith("\n"))
        assertTrue("文末换行不许丢", index.readText(Charsets.UTF_8).endsWith("\n"))
    }

    @Test
    fun `回滚仍走唯一写口_且先备份原文件`() {
        val pf = PersonaFiles(globalRoot = global, projectRoot = ws)
        val index = pf.projectFile()
        index.parentFile?.mkdirs()
        val retired = (SkillRules.removeIndexLine(personaText(), "周报汇总") as SkillRules.EditResult.Ok).newText
        index.writeText(retired, Charsets.UTF_8)

        restoreArchived(archiveRow(), ws, global, pf)

        // applyProposal 的唯一道口会自动留 .bak；199 起 C 层备份落 `<工作区>/备份/人设/`
        val bakDir = File(ws, "备份/人设")
        val baks = bakDir.listFiles { f -> f.name.startsWith("人设-本项目.md.bak-") }.orEmpty()
        assertEquals("回滚写人设前必须先备份", 1, baks.size)
        assertEquals("备份里是回滚前那一刻的字节（已去掉索引行）", retired, baks.single().readText(Charsets.UTF_8))
    }
}
