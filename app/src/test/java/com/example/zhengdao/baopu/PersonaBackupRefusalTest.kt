// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 · A 段第 2 条（257 审计 A-P1-3）复现用例。
//
// **改前的表现**（`PersonaFiles.applyProposal` 里 `PersonaBackup.backupBefore(f, …)`
// 的返回值被丢弃、随后无条件 `f.writeText(...)` 再 `true`）：
// - 备份没成功（备份区被占、盘满、权限）时，人设文件照样被整份覆盖，
//   而调用方拿到 `true`——上层据此认为「写了、而且留了可回滚的备份」，
//   于是设置页的「恢复到上一版」会去指一份根本不存在的备份；
// - 同仓的 `PersonaPatchApply.reset` 早就做对了（备份拿不到 ⇒ Rejected、原文件不动），
//   同一个不变式两个道口两种纪律。
// 改后：`f.isFile && backupBefore(...) == null` ⇒ 不写、返回 false，并走现有错误通道
// `PersonaAudit.rejected` 留一行；首次写入（文件还没有）仍然放行，「没有」≠「坏了」。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PersonaBackupRefusalTest {

    private fun tmp(): File = Files.createTempDirectory("persona-backup").toFile()

    /** `<工作区>/抱朴记忆/` 当作 C 层根；备份区 = `<工作区>/备份/人设/`（199 起）。 */
    private class Harness(val workspace: File, val files: PersonaFiles) {
        val personaFile: File get() = files.projectFile()

        /** 把备份区的目录名占成一个普通文件 ⇒ mkdirs 失败、copyTo 抛错 ⇒ 备份拿不到。 */
        fun breakBackupArea() {
            val backupRoot = File(workspace, BaopuKernel.Data.DIR_BACKUP)
            backupRoot.mkdirs()
            File(backupRoot, "人设").writeText("我占住了这个名字")
        }
    }

    private fun harness(): Harness {
        val ws = tmp()
        val projectRoot = File(ws, PersonaPackRules.DIR_ROOT).apply { mkdirs() }
        return Harness(ws, PersonaFiles(globalRoot = tmp(), projectRoot = projectRoot))
    }

    private fun pendingProposal(current: String, proposed: String) = PersonaProposal(
        layer = PersonaLayer.C_PROJECT,
        current = current,
        proposed = proposed,
        reason = "第二百五十九批探针",
        status = PersonaProposalStatus.PENDING,
    )

    @Test
    fun `备份拿不到_不写原文件_返回 false`() {
        val h = harness()
        h.personaFile.writeText("旧人设\n", Charsets.UTF_8)
        h.breakBackupArea()
        assertTrue("前提：备份区确实坏了", PersonaBackup.backupBefore(h.personaFile, "20261028-000000") == null)

        val written = h.files.applyProposal(pendingProposal("旧人设", "旧人设\n新增条款\n"))

        assertTrue("备份失败必须返回 false（改前这里是 true）", !written)
        assertEquals("原文件一个字节都不许动（改前这里已经被整份覆盖）", "旧人设\n", h.personaFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `备份拿不到_要往现有错误通道报一行`() {
        val h = harness()
        h.personaFile.writeText("旧人设\n", Charsets.UTF_8)
        h.breakBackupArea()
        val lines = mutableListOf<String>()
        val oldSink = PersonaAudit.sink
        PersonaAudit.sink = { lines += it }
        try {
            h.files.applyProposal(pendingProposal("旧人设", "旧人设\n新增条款\n"))
        } finally {
            PersonaAudit.sink = oldSink
        }
        assertTrue("拒绝必须有回声（改前静默覆盖，一行都没有）：$lines",
            lines.any { it.contains("备份") && it.contains("没有动原文件") })
    }

    @Test
    fun `首次写入没有旧文件_仍然放行_不许误伤成备份失败`() {
        val h = harness()
        h.breakBackupArea()
        assertTrue("前提：C 层文件还不存在", !h.personaFile.isFile)
        val written = h.files.applyProposal(pendingProposal("", "第一条人设\n"))
        assertTrue("文件本来就没有 ⇒ 没有可备份的东西，不等于备份坏了（改后仍须放行）", written)
        assertEquals("第一条人设\n", h.personaFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `备份区正常_照常写并且留下可回滚的备份`() {
        val h = harness()
        h.personaFile.writeText("旧人设\n", Charsets.UTF_8)
        val written = h.files.applyProposal(pendingProposal("旧人设", "旧人设\n新增条款\n"))
        assertTrue(written)
        assertEquals("旧人设\n新增条款\n", h.personaFile.readText(Charsets.UTF_8))
        val latest = PersonaBackup.latestOf(h.personaFile)
        assertTrue("成功路径必须真的留下一份旧版", latest != null && latest.isFile)
        assertEquals("旧人设\n", latest!!.readText(Charsets.UTF_8))
    }
}
