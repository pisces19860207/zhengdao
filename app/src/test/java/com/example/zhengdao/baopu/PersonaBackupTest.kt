// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第三十七批 D 段（人设回滚入口）——判据 L6 与负例 5。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 人设备份与回滚的单测（第三十七批 D 段）。
 *
 * 钉六件事：
 * 1. 备份名沿用 `.bak-<stamp>` 口径（与 `SkillStore` 同款，用户一眼认得）；
 * 2. 写入前备份的内容**逐字节等于改前**；首次写入**不造空备份**；
 * 3. 恢复后与改前**逐字节一致**（L6 的核心）；
 * 4. 恢复本身**先备份当前版**（可反悔）——负例 5 就是拆掉这一步；
 * 5. 没有备份 ⇒ 拒绝，**源文件一个字节都不动**；
 * 6. 设置页那一行**灰掉但不消失**（`enabled = latest != null`，不是 `if (latest != null)` 包起来）。
 */
class PersonaBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = tmp.newFolder()

    private fun fileWith(text: String): java.io.File {
        val f = java.io.File(dir(), PersonaFiles.FILE_PERSONA_PROJECT)
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun bytes(f: java.io.File) = f.readBytes()

    // ── ① 命名 ──────────────────────────────────────────────────────────

    @Test
    fun `备份名_沿用bak口径且能取回时间戳`() {
        val n = PersonaBackup.backupNameFor("人设-本项目.md", "20261019-101500")
        assertEquals("人设-本项目.md.bak-20261019-101500", n)
        assertEquals("20261019-101500", PersonaBackup.stampOf(n, "人设-本项目.md"))
        assertNull("别的文件不能算人设的备份", PersonaBackup.stampOf("人设.md.bak-1", "人设-本项目.md"))
    }

    @Test
    fun `时间戳显示_认得出来就不原样吐`() {
        assertEquals("2026-10-19 10:15:00", PersonaBackup.displayStamp("20261019-101500"))
        // 同秒去重后缀要去掉
        assertEquals("2026-10-19 10:15:00", PersonaBackup.displayStamp("20261019-101500-2"))
        // 认不出来原样返回，不许崩
        assertEquals("怪东西", PersonaBackup.displayStamp("怪东西"))
    }

    // ── ② 写前备份 ──────────────────────────────────────────────────────

    @Test
    fun `写入前备份_内容与改前逐字节一致`() {
        val f = fileWith("第一版：说话前的铺垫\n含中文与换行")
        val before = bytes(f)
        val bak = PersonaBackup.backupBefore(f, "20261019-101500")
        assertNotNull("文件存在就必须留下备份", bak)
        assertTrue("备份内容要与改前逐字节一致", bytes(bak!!).contentEquals(before))
        assertTrue("原件不许被备份动作改动", bytes(f).contentEquals(before))
    }

    @Test
    fun `首次写入_不造空备份`() {
        // 人设文件还不存在（全新工作区）：没有任何内容可备份，不许造个空文件骗「可回滚」
        val f = java.io.File(dir(), PersonaFiles.FILE_PERSONA_PROJECT)
        assertFalse(f.exists())
        assertNull(PersonaBackup.backupBefore(f, "20261019-101500"))
        assertTrue("不许凭空造出人设文件", !f.exists())
    }

    @Test
    fun `同一秒两次写_不覆盖已有备份`() {
        val f = fileWith("v1")
        val b1 = PersonaBackup.backupBefore(f, "20261019-101500")!!
        f.writeText("v2", Charsets.UTF_8)
        val b2 = PersonaBackup.backupBefore(f, "20261019-101500")!!
        assertEquals("v1", b1.readText())
        assertEquals("v2", b2.readText())
        assertEquals("两份都得留着（绝不覆盖）", 2, PersonaBackup.backupsOf(f).size)
    }

    @Test
    fun `多份备份_取最新`() {
        val f = fileWith("v1")
        PersonaBackup.backupBefore(f, "20261019-100000")
        f.writeText("v2", Charsets.UTF_8)
        PersonaBackup.backupBefore(f, "20261019-110000")
        assertEquals("v2", PersonaBackup.latestOf(f)!!.readText())
    }

    // ── ③ 恢复（L6 的核心）─────────────────────────────────────────────

    @Test
    fun `恢复_与改前逐字节一致`() {
        val original = "原始人设\n- 别客套\n- 先给结论"
        val f = fileWith(original)
        val before = bytes(f)
        PersonaBackup.backupBefore(f, "20261019-100000")
        f.writeText("被改坏的人设", Charsets.UTF_8)
        val r = PersonaBackup.restore(f, "20261019-110000")
        assertTrue("恢复应当成功：$r", r is PersonaBackupResult.Done)
        assertTrue("恢复后必须与改前逐字节一致", bytes(f).contentEquals(before))
    }

    @Test
    fun `恢复本身也先备份当前版_可以反悔`() {
        val f = fileWith("第一版")
        PersonaBackup.backupBefore(f, "20261019-100000")
        f.writeText("第二版", Charsets.UTF_8)
        PersonaBackup.restore(f, "20261019-110000")
        assertEquals("第一版", f.readText())
        // 恢复前那份「第二版」必须还在盘上（否则滚错了没法回来）
        val texts = PersonaBackup.backupsOf(f).map { it.readText() }
        assertTrue("第二版要作为备份留下：$texts", texts.contains("第二版"))
    }

    @Test
    fun `没有备份_恢复被拒且源文件一个字节不动`() {
        val f = fileWith("只有这一版")
        val before = bytes(f)
        val r = PersonaBackup.restore(f, "20261019-110000")
        assertTrue("没有备份必须拒绝：$r", r is PersonaBackupResult.Rejected)
        assertEquals(PersonaBackup.NO_BACKUP, (r as PersonaBackupResult.Rejected).reason)
        assertTrue("拒绝时不许动原文件", bytes(f).contentEquals(before))
    }

    // ── ④ 接线：人设唯一写盘口真的在写前备份 ──────────────────────────

    @Test
    fun `applyProposal_写前自动备份且两次写留两份`() {
        val pf = PersonaFiles(tmp.newFolder(), tmp.newFolder())
        val f = pf.projectFile()
        f.parentFile?.mkdirs()
        f.writeText("原始人设", Charsets.UTF_8)

        val p1 = PersonaRules.propose(PersonaLayer.C_PROJECT, "原始人设", "第二版人设", "测试")!!
        assertTrue("第一次改应当成功", pf.applyProposal(p1))
        val p2 = PersonaRules.propose(PersonaLayer.C_PROJECT, "第二版人设", "第三版人设", "测试")!!
        assertTrue("第二次改应当成功", pf.applyProposal(p2))

        assertEquals("第三版人设", pf.read(PersonaLayer.C_PROJECT))
        val baks = PersonaBackup.backupsOf(f)
        assertEquals("改两次就该有两份备份", 2, baks.size)
        assertEquals("最旧那份是原始内容", "原始人设", baks.last().readText())
        assertEquals("最新那份是第二版", "第二版人设", baks.first().readText())
    }

    @Test
    fun `applyProposal_首次写入不产生备份`() {
        val pf = PersonaFiles(tmp.newFolder(), tmp.newFolder())
        val f = pf.projectFile()
        val p = PersonaRules.propose(PersonaLayer.C_PROJECT, "", "初次人设", "测试")!!
        assertTrue(pf.applyProposal(p))
        assertEquals("初次人设", pf.read(PersonaLayer.C_PROJECT))
        assertTrue("首次写入没有「上一版」可备份", PersonaBackup.backupsOf(f).isEmpty())
        // 但第二次改就有了
        val p2 = PersonaRules.propose(PersonaLayer.C_PROJECT, "初次人设", "改一次", "测试")!!
        assertTrue(pf.applyProposal(p2))
        assertEquals(1, PersonaBackup.backupsOf(f).size)
    }

    @Test
    fun `applyProposal_A内核永远写不进去也永远没有备份`() {
        val pf = PersonaFiles(tmp.newFolder(), tmp.newFolder())
        val p = PersonaProposal(
            layer = PersonaLayer.A_KERNEL,
            current = "",
            proposed = "想改内核",
            reason = "测试",
        )
        assertFalse("A 内核不可提案改写（判据 4）", pf.applyProposal(p))
    }

    // ── ⑤ 设置页那一行：灰掉但不消失 ──────────────────────────────────

    @Test
    fun `设置页_无备份时按钮灰掉但这一行不消失`() {
        val src = java.io.File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
            .let { if (it.isFile) it else java.io.File("app/$it") }
            .readText().replace("\r\n", "\n")
        assertTrue("设置页要有人设回滚入口", src.contains("PersonaBackup.restore("))
        assertTrue(
            "没有备份只能是灰掉（enabled=false），不能把整行藏起来",
            src.contains("enabled = latest != null"),
        )
        assertFalse(
            "不许用 if (latest != null) 把按钮包起来——那样没备份时这一行就消失了",
            src.contains("if (latest != null)"),
        )
        assertTrue("要显示最近备份的时间戳", src.contains("PersonaBackup.displayStamp("))
        assertTrue("两层人设都要能回滚", src.contains("PersonaLayer.B_GLOBAL to R.string.persona_rollback_global"))
    }
}
