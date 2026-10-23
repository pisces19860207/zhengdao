// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：java.io.File / JUnit 4（TemporaryFolder）/ TimeFormats。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.util.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 157 记忆**写前快照** [MemoryBackup] 的 JVM 直测（临时目录，不碰 Context）。
 *
 * 覆盖派工单判据（用户 2026-10-23 原话「修正前备份到 `filesDir/抱朴记忆/备份/`」）：
 * - 快照落 **App 私有**（`filesDir/抱朴记忆/备份/`），工作区一个字节不碰；
 * - 目录名＝[TimeFormats.fileStamp] 秒级戳；同秒第二/三份追 `-2`/`-3`，**不覆盖**旧份；
 * - `.tmp` + rename 原子口径：成功不留半成品；`记忆/` 不是目录 ⇒ null 且不建备份根；
 * - [MemoryBackup.snapshots] 新→旧；[MemoryBackup.rotate] 只留最近 keep 份；
 * - keep<1 构造即抛（安全网报废不许静默上线）。
 */
class MemoryBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun f(parent: File, rel: String, text: String = "内容"): File {
        val out = File(parent, rel)
        out.parentFile?.mkdirs()
        out.writeText(text)
        return out
    }

    private val NOW = 1_767_000_000_000L // 固定时刻（测试不读真时钟）

    // ── 落点与命名 ────────────────────────────────────────────────────────

    @Test
    fun `快照落App私有备份目录_命名fileStamp_整树复制不碰工作区`() {
        val files = tmp.newFolder("files")
        val ws = tmp.newFolder("工作区")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        val memoryRoot = MemoryLayout.memoryDir(ws)
        f(memoryRoot, "日记/2026-10-23.md", "日记正文")
        f(memoryRoot, "项目记忆.md", "项目口径")
        f(memoryRoot, "归档/旧日记.md", "归档正文")

        val snap = MemoryBackup(backupRoot).snapshot(memoryRoot, NOW)

        assertNotNull("快照应成功", snap)
        assertEquals(TimeFormats.fileStamp(NOW), snap!!.name)
        assertEquals(backupRoot, snap.parentFile)
        assertEquals("日记正文", File(snap, "日记/2026-10-23.md").readText())
        assertEquals("项目口径", File(snap, "项目记忆.md").readText())
        assertEquals("归档正文", File(snap, "归档/旧日记.md").readText())
        // 原子口径：成功后不留 .tmp 半成品
        assertTrue(backupRoot.listFiles()!!.none { it.name.endsWith(".tmp") })
        // 绝不写进工作区：工作区里仍然只有「记忆/」一层原样
        assertEquals(listOf("记忆"), ws.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `同秒第二三份追2追3_旧快照一个字节不被覆盖`() {
        val files = tmp.newFolder("files")
        val ws = tmp.newFolder("工作区")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        val memoryRoot = MemoryLayout.memoryDir(ws)
        val mb = MemoryBackup(backupRoot)

        f(memoryRoot, "日记/a.md", "第一份")
        val s1 = mb.snapshot(memoryRoot, NOW)!!
        f(memoryRoot, "日记/a.md", "第二份")
        val s2 = mb.snapshot(memoryRoot, NOW)!!
        f(memoryRoot, "日记/a.md", "第三份")
        val s3 = mb.snapshot(memoryRoot, NOW)!!

        assertEquals(TimeFormats.fileStamp(NOW), s1.name)
        assertEquals(TimeFormats.fileStamp(NOW) + "-2", s2.name)
        assertEquals(TimeFormats.fileStamp(NOW) + "-3", s3.name)
        assertEquals("第一份", File(s1, "日记/a.md").readText())
        assertEquals("第二份", File(s2, "日记/a.md").readText())
        assertEquals("第三份", File(s3, "日记/a.md").readText())
    }

    @Test
    fun `memoryRoot不是目录_返回null且不建备份根`() {
        val files = tmp.newFolder("files")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        val mb = MemoryBackup(backupRoot)
        assertNull(mb.snapshot(File(files, "不存在的记忆"), NOW))
        assertFalse("失败时不该创建备份根", backupRoot.exists())
    }

    // ── 列表与轮转 ────────────────────────────────────────────────────────

    @Test
    fun `snapshots新到旧_rotate只留最近keep份`() {
        val files = tmp.newFolder("files")
        val ws = tmp.newFolder("工作区")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        val memoryRoot = MemoryLayout.memoryDir(ws)
        f(memoryRoot, "日记/a.md", "x")
        val mb = MemoryBackup(backupRoot, keep = 2)
        var t = NOW
        repeat(4) {
            mb.snapshot(memoryRoot, t)
            t += 1000 // 每秒一份
        }

        val snaps = mb.snapshots()
        assertEquals(4, snaps.size)
        val names = snaps.map { it.name }
        assertEquals("列表须新→旧", names.sortedDescending(), names)

        val deleted = mb.rotate()
        assertEquals(2, deleted)
        val left = mb.snapshots()
        assertEquals(2, left.size)
        // 留下的正是最新两份（最早的被轮转删掉）
        assertEquals(TimeFormats.fileStamp(NOW + 3000), left[0].name)
        assertEquals(TimeFormats.fileStamp(NOW + 2000), left[1].name)
    }

    @Test
    fun `份数不超keep_rotate返回0一份不删`() {
        val files = tmp.newFolder("files")
        val ws = tmp.newFolder("工作区")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        val memoryRoot = MemoryLayout.memoryDir(ws)
        f(memoryRoot, "日记/a.md", "x")
        val mb = MemoryBackup(backupRoot, keep = 5)
        mb.snapshot(memoryRoot, NOW)
        mb.snapshot(memoryRoot, NOW + 1000)

        assertEquals(0, mb.rotate())
        assertEquals(2, mb.snapshots().size)
    }

    @Test
    fun `备份根不存在时snapshots为空列表`() {
        val files = tmp.newFolder("files")
        val backupRoot = MemoryLayout.memoryBackupDir(files)
        assertTrue(MemoryBackup(backupRoot).snapshots().isEmpty())
        assertEquals(0, MemoryBackup(backupRoot).rotate())
    }

    @Test
    fun `keep小于1构造即抛`() {
        val backupRoot = MemoryLayout.memoryBackupDir(tmp.newFolder("files"))
        val e0 = runCatching { MemoryBackup(backupRoot, keep = 0) }.exceptionOrNull()
        assertTrue("keep=0 应抛 IllegalArgumentException，实际=$e0", e0 is IllegalArgumentException)
        val eNeg = runCatching { MemoryBackup(backupRoot, keep = -1) }.exceptionOrNull()
        assertTrue("keep=-1 应抛 IllegalArgumentException，实际=$eNeg", eNeg is IllegalArgumentException)
    }

    @Test
    fun `默认keep就是内核值`() {
        val backupRoot = MemoryLayout.memoryBackupDir(tmp.newFolder("files"))
        // 默认值走内核常量（改数必须动内核 ⇒ KernelGuardTest 会拦）
        val ws = tmp.newFolder("工作区")
        val memoryRoot = MemoryLayout.memoryDir(ws)
        f(memoryRoot, "日记/a.md", "x")
        val mb = MemoryBackup(backupRoot)
        var t = NOW
        repeat(6) { mb.snapshot(memoryRoot, t); t += 1000 }
        mb.rotate()
        assertEquals(BaopuKernel.Data.MEMORY_BACKUP_KEEP, mb.snapshots().size)
    }
}
