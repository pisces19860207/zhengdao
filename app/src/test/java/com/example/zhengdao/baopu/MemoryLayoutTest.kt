// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：java.io.File / JUnit 4（TemporaryFolder）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 157 记忆目录约定 ＋ 目录收口的路径/边界/迁移 JVM 直测（临时目录，不碰 Context）。
 *
 * 覆盖：
 * - 四目录＋记忆层路径单一真源（[MemoryLayout]）；
 * - 写入边界 [MemoryLayout.allowedWrite] 正负例（组件级，防 `记忆X/` 蹭过）；
 * - [MemoryLayout.ensureDirs] 只建空 `记忆/日记/`（不弹卡不写内容）；
 * - 迁移六条各一例＋幂等（二遍全零）＋冲突不覆盖＋只删空目录；
 * - 人设红线：`抱朴记忆/`（C 层人设）与工作区其他文件一个字节不碰。
 */
class MemoryLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ws(): File = tmp.newFolder("工作区")
    private fun files(): File = tmp.newFolder("files")

    private fun f(parent: File, rel: String, text: String = "内容"): File {
        val out = File(parent, rel)
        out.parentFile?.mkdirs()
        out.writeText(text)
        return out
    }

    // ── 路径单一真源 ────────────────────────────────────────────────────────

    @Test
    fun `路径口径_四目录与记忆层都在工作区根一层`() {
        val w = File("/ws")
        assertEquals("/ws/产物", MemoryLayout.productsDir(w).path.replace('\\', '/'))
        assertEquals("/ws/投递", MemoryLayout.deliverDir(w).path.replace('\\', '/'))
        assertEquals("/ws/备份", MemoryLayout.backupDir(w).path.replace('\\', '/'))
        assertEquals("/ws/记忆", MemoryLayout.memoryDir(w).path.replace('\\', '/'))
        assertEquals("/ws/记忆/日记", MemoryLayout.diaryDir(w).path.replace('\\', '/'))
        assertEquals("/ws/记忆/归档", MemoryLayout.archiveDir(w).path.replace('\\', '/'))
        assertEquals("/ws/记忆/项目记忆.md", MemoryLayout.projectMemoryFile(w).path.replace('\\', '/'))
        // App 私有两份（全局记忆与写前备份）都在 filesDir 下，不进工作区
        assertEquals(
            "/files/抱朴记忆/全局记忆.md",
            MemoryLayout.globalMemoryFile(File("/files")).path.replace('\\', '/'),
        )
        assertEquals(
            "/files/抱朴记忆/备份",
            MemoryLayout.memoryBackupDir(File("/files")).path.replace('\\', '/'),
        )
    }

    // ── 写入边界（组件级） ──────────────────────────────────────────────────

    @Test
    fun `allowedWrite_只放行记忆目录内_组件级不蹭同名兄弟`() {
        val w = File("/ws")
        assertTrue(MemoryLayout.allowedWrite(w, MemoryLayout.diaryDir(w)))
        assertTrue(MemoryLayout.allowedWrite(w, File(MemoryLayout.memoryDir(w), "日记/2026-10-23.md")))
        assertTrue(MemoryLayout.allowedWrite(w, MemoryLayout.projectMemoryFile(w)))
        // 负例：同前缀兄弟（记忆X/）不许蹭过
        assertFalse(MemoryLayout.allowedWrite(w, File(w, "记忆X/a.md")))
        // 负例：四目录其余三个不是记忆写入目标
        assertFalse(MemoryLayout.allowedWrite(w, MemoryLayout.productsDir(w)))
        assertFalse(MemoryLayout.allowedWrite(w, MemoryLayout.backupDir(w)))
        assertFalse(MemoryLayout.allowedWrite(w, MemoryLayout.deliverDir(w)))
        // 负例：工作区外
        assertFalse(MemoryLayout.allowedWrite(w, File("/ elsewhere/x.md")))
    }

    @Test
    fun `ensureDirs_只建空日记目录_不写任何内容`() {
        val w = ws()
        MemoryLayout.ensureDirs(w)
        assertTrue(MemoryLayout.diaryDir(w).isDirectory)
        // 不弹卡不写内容：日记目录是空的；别的目录一个都不建
        assertEquals(0, MemoryLayout.diaryDir(w).listFiles()?.size ?: 0)
        assertFalse(MemoryLayout.archiveDir(w).exists())
        assertFalse(MemoryLayout.productsDir(w).exists())
        assertFalse(MemoryLayout.backupDir(w).exists())
    }

    // ── 迁移六条 ───────────────────────────────────────────────────────────

    @Test
    fun `迁移_整理进产物_投递退回根`() {
        val w = ws()
        f(File(w, "资料库/整理"), "a.md", "甲")
        f(File(w, "资料库/整理"), "sub/b.md", "乙")
        f(File(w, "资料库/投递"), "c.txt", "丙")

        val r = MemoryLayout.migrate(w, files())

        assertEquals(3, r.movedProducts + r.movedDeliver)
        assertEquals("甲", File(MemoryLayout.productsDir(w), "a.md").readText())
        assertEquals("乙", File(MemoryLayout.productsDir(w), "sub/b.md").readText())
        assertEquals("丙", File(MemoryLayout.deliverDir(w), "c.txt").readText())
        assertTrue("搬空的旧目录要删（只删空的）", !File(w, "资料库").exists())
    }

    @Test
    fun `迁移_原件进私有_改版进迁移存档`() {
        val w = ws()
        val fd = files()
        f(File(w, "资料库/原始"), "原书.txt", "原文")
        f(File(w, "资料库/改版"), "旧改版.md", "旧稿")

        MemoryLayout.migrate(w, fd)

        assertEquals("原文", File(MemoryLayout.rawPrivateDir(fd), "原书.txt").readText())
        assertEquals("旧稿", File(MemoryLayout.productsDir(w), "_迁移存档/改版/旧改版.md").readText())
    }

    @Test
    fun `迁移_baopu-bak进备份_根项目记忆进记忆目录`() {
        val w = ws()
        f(File(w, ".baopu-bak"), "x.md.bak", "退路")
        f(w, "项目记忆.md", "# 旧根项目记忆")

        MemoryLayout.migrate(w, files())

        assertEquals("退路", File(MemoryLayout.backupDir(w), "x.md.bak").readText())
        assertEquals("# 旧根项目记忆", MemoryLayout.projectMemoryFile(w).readText())
        assertTrue("旧备份目录搬空即删", !File(w, ".baopu-bak").exists())
    }

    @Test
    fun `迁移_幂等_二遍全零`() {
        val w = ws()
        val fd = files()
        f(File(w, "资料库/整理"), "a.md", "甲")
        f(File(w, "资料库/投递"), "b.txt", "乙")

        val first = MemoryLayout.migrate(w, fd)
        assertTrue("第一遍要有搬动", !first.isEmpty)
        val second = MemoryLayout.migrate(w, fd)
        assertTrue("第二遍必须全零：${second.summary()}", second.isEmpty)
        assertEquals("甲", File(MemoryLayout.productsDir(w), "a.md").readText())
        assertEquals("乙", File(MemoryLayout.deliverDir(w), "b.txt").readText())
    }

    @Test
    fun `迁移_冲突不覆盖_跳过并记账_旧文件原样留着`() {
        val w = ws()
        f(File(w, "资料库/整理"), "a.md", "旧内容")
        f(MemoryLayout.productsDir(w), "a.md", "新内容") // 目标已有同名

        val r = MemoryLayout.migrate(w, files())

        assertEquals(0, r.movedProducts)
        assertEquals(1, r.skipped.size)
        assertEquals("新内容", File(MemoryLayout.productsDir(w), "a.md").readText())
        assertEquals("旧内容", File(File(w, "资料库/整理"), "a.md").readText())
    }

    @Test
    fun `迁移_只删空目录_非空的资料库留着下遍接着搬`() {
        val w = ws()
        f(File(w, "资料库/整理"), "留.txt", "还在") // 无冲突 ⇒ 搬得走
        f(File(w, "资料库/投递"), "冲.txt", "撞")
        f(MemoryLayout.deliverDir(w), "冲.txt", "已在") // 制造冲突 ⇒ 投递那份搬不走

        MemoryLayout.migrate(w, files())

        // 搬得走的搬走：整理/整个被清掉（含空目录自身）
        assertTrue("无冲突的留.txt 应搬进产物", File(MemoryLayout.productsDir(w), "留.txt").isFile)
        assertTrue("搬空的 资料库/整理 要删", !File(w, "资料库/整理").exists())
        // 搬不走的留下：投递那份有冲突 ⇒ 资料库/整个不许删
        assertTrue("投递还有冲突文件 ⇒ 资料库/整个不许删", File(w, "资料库").isDirectory)
        assertTrue("冲突跳过的冲.txt 原样留着", File(File(w, "资料库/投递"), "冲.txt").isFile)
    }

    @Test
    fun `迁移_人设红线_抱朴记忆目录与其他人设文件一个字节不碰`() {
        val w = ws()
        f(File(w, "抱朴记忆"), "人设-本项目.md", "# 项目人设")
        f(w, "_索引.md", "# 工作区索引")
        f(File(w, "资料库/整理"), "a.md", "甲")

        val before = File(File(w, "抱朴记忆"), "人设-本项目.md").readText()
        val beforeIdx = File(w, "_索引.md").readText()
        MemoryLayout.migrate(w, files())

        assertEquals(before, File(File(w, "抱朴记忆"), "人设-本项目.md").readText())
        assertEquals(beforeIdx, File(w, "_索引.md").readText())
        assertEquals("甲", File(MemoryLayout.productsDir(w), "a.md").readText())
    }
}
