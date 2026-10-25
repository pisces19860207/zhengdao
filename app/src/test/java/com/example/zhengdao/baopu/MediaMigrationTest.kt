// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第一百九十九批 · 两笔存量迁移（`MediaMigration`，纯 File IO）。
 *
 * 判据（派工单 §2 / 设计 §7）：
 * - 复制**前后逐文件 sha256 一致**；
 * - **旧目录不消失、旧文件不删**（只落 `.待删.txt` 交给人）；
 * - 同名不同内容 ⇒ 改名不覆盖；
 * - **二遍全零**（幂等）；
 * - 人设存量**只登记**（文件一个都不动、不删）；
 * - 人设新备份落 `备份/人设/`，而「恢复上一版」两处都要能找到（升级当天不许说「没有备份」）。
 */
class MediaMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(f: File, text: String): File = f.apply {
        parentFile?.mkdirs()
        writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun sha(f: File): String = MediaOwnership.sha256(f)

    // ── ① 截屏目录 ───────────────────────────────────────────────────────────

    @Test
    fun `截屏迁移_复制不删_逐文件哈希一致`() {
        val ws = tmp.newFolder("ws")
        val legacy = File(tmp.root, "screenshots").apply { mkdirs() }
        val target = File(tmp.root, "截屏")
        val a = write(File(legacy, "a.png"), "A图")
        val b = write(File(legacy, "b.png"), "B图")

        val r = MediaMigration.migrateShots(ws, legacy, target)

        assertEquals(2, r.copied)
        assertTrue(r.failedVerify.isEmpty())
        assertTrue("旧目录不许消失", legacy.isDirectory)
        assertTrue("旧文件不许删", a.isFile && b.isFile)
        assertEquals("复制品要与原件逐字节一致", sha(a), sha(File(target, "a.png")))
        assertEquals(sha(b), sha(File(target, "b.png")))
        assertTrue("要落待删清单", File(legacy, MediaMigration.PENDING_DELETE).isFile)
    }

    @Test
    fun `截屏迁移_同名不同内容_改名不覆盖`() {
        val ws = tmp.newFolder("ws")
        val legacy = File(tmp.root, "screenshots").apply { mkdirs() }
        val target = File(tmp.root, "截屏").apply { mkdirs() }
        write(File(legacy, "a.png"), "新的")
        val old = write(File(target, "a.png"), "旧的（用户已有的那张）")

        val r = MediaMigration.migrateShots(ws, legacy, target)

        assertEquals(1, r.copied)
        assertEquals(1, r.renamed.size)
        assertEquals("目标里原有的那张一个字节都不许动", "旧的（用户已有的那张）", old.readText())
        assertTrue("新内容要落在 -2 上", File(target, "a-2.png").isFile)
    }

    @Test
    fun `截屏迁移_二遍全零`() {
        val ws = tmp.newFolder("ws")
        val legacy = File(tmp.root, "screenshots").apply { mkdirs() }
        val target = File(tmp.root, "截屏").apply { mkdirs() }
        write(File(legacy, "a.png"), "A")
        write(File(target, "a.png"), "别的")

        val first = MediaMigration.migrateShots(ws, legacy, target)
        assertEquals(1, first.copied)
        val second = MediaMigration.migrateShots(ws, legacy, target)
        assertEquals("二遍不许再复制", 0, second.copied)
        assertTrue("二遍要判成已在位", second.isEmpty)
        // 第三遍也不许长出新名字
        val third = MediaMigration.migrateShots(ws, legacy, target)
        assertEquals(0, third.copied)
        assertFalse("不许长出 a-3", File(target, "a-3.png").exists())
    }

    @Test
    fun `截屏迁移_没有旧目录就是空报告`() {
        val ws = tmp.newFolder("ws")
        val r = MediaMigration.migrateShots(ws, File(tmp.root, "不存在"), File(tmp.root, "截屏"))
        assertTrue(r.isEmpty)
        assertTrue(r.copied == 0 && r.identical == 0 && r.skipped.isEmpty())
    }

    @Test
    fun `截屏迁移_待删清单不进第二批`() {
        val ws = tmp.newFolder("ws")
        val legacy = File(tmp.root, "screenshots").apply { mkdirs() }
        val target = File(tmp.root, "截屏")
        write(File(legacy, "a.png"), "A")
        MediaMigration.migrateShots(ws, legacy, target)
        // `.待删.txt` 是隐藏文件 ⇒ 第二遍不许被当成一张截图搬进 截屏/
        assertFalse(File(target, MediaMigration.PENDING_DELETE).exists())
    }

    @Test
    fun `截屏迁移_对账报告落在产物迁移存档`() {
        val ws = tmp.newFolder("ws")
        val legacy = File(tmp.root, "screenshots").apply { mkdirs() }
        write(File(legacy, "a.png"), "A")
        MediaMigration.migrateShots(ws, legacy, File(tmp.root, "截屏"))
        val dir = File(File(ws, "产物"), "_迁移存档")
        assertTrue("对账报告要落 产物/_迁移存档/", dir.isDirectory)
        assertTrue(dir.listFiles()!!.any { it.name.contains("对账") })
    }

    // ── ② 人设存量 ───────────────────────────────────────────────────────────

    @Test
    fun `人设存量_只登记不搬不删`() {
        val ws = tmp.newFolder("ws")
        val body = write(File(ws, "人设-本项目.md"), "旧人设正文")
        val bak = write(File(ws, "人设-本项目.md.bak-20261024-002952"), "更旧的一版")

        val r = MediaMigration.registerPersonaResidue(ws)

        assertEquals(2, r.registered.size)
        assertFalse(r.designFileExists)
        assertTrue("本体不许被搬走/删掉", body.isFile)
        assertTrue(".bak 存量不许被搬走/删掉", bak.isFile)
        val reg = File(r.registerFile)
        assertTrue("登记清单要落 备份/人设/", reg.isFile)
        val text = reg.readText()
        assertTrue(text.contains("人设-本项目.md"))
        assertTrue(text.contains(sha(body)))
        assertFalse("设计位置不该被凭空建出来", File(File(ws, "抱朴记忆"), "人设-本项目.md").exists())
    }

    @Test
    fun `人设存量_二遍零_内容没变就不重写`() {
        val ws = tmp.newFolder("ws")
        write(File(ws, "人设-本项目.md"), "正文")
        val first = MediaMigration.registerPersonaResidue(ws)
        assertEquals(1, first.registered.size)
        val second = MediaMigration.registerPersonaResidue(ws)
        assertTrue("内容没变 ⇒ 第二遍什么都不做", second.isEmpty)
    }

    @Test
    fun `人设存量_设计位置已有时要写明`() {
        val ws = tmp.newFolder("ws")
        write(File(ws, "抱朴记忆/人设-本项目.md"), "新人设")
        write(File(ws, "人设-本项目.md"), "旧人设")
        val r = MediaMigration.registerPersonaResidue(ws)
        assertTrue(r.designFileExists)
        assertTrue(File(r.registerFile).readText().contains("已存在"))
    }

    @Test
    fun `人设存量_没有存量时不建登记文件`() {
        val ws = tmp.newFolder("ws")
        val r = MediaMigration.registerPersonaResidue(ws)
        assertTrue(r.registered.isEmpty())
        assertFalse("没有存量就别在 备份/ 里造文件", File(r.registerFile).exists())
    }

    // ── ③ 人设 .bak 的新家 ───────────────────────────────────────────────────

    @Test
    fun `人设备份_新备份落备份目录_旧备份仍能恢复`() {
        val ws = tmp.newFolder("ws")
        val persona = write(File(ws, "抱朴记忆/人设-本项目.md"), "第一版")
        // 存量：旧位置（人设文件旁边）的那一份
        val legacyBak = write(
            File(ws, "抱朴记忆/人设-本项目.md.bak-20261020-000000"),
            "第零版（旧位置）",
        )

        val fresh = PersonaBackup.backupBefore(persona, "20261025-080000")
        assertNotNull("写前必须留备份", fresh)
        assertEquals(
            "新备份要落 <工作区>/备份/人设/",
            File(ws, "备份/人设").absolutePath,
            fresh!!.parentFile!!.absolutePath,
        )
        // 两处都要能看见（只扫一处 ⇒ 升级当天「恢复上一版」会突然说没有备份）
        val all = PersonaBackup.backupsOf(persona)
        assertEquals(2, all.size)
        assertTrue(all.any { it.absolutePath == legacyBak.absolutePath })
        assertEquals("最新的那份是新写的", fresh.absolutePath, all.first().absolutePath)
    }

    @Test
    fun `人设备份_根直放的旧人设仍可读`() {
        val ws = tmp.newFolder("ws")
        write(File(ws, "人设-本项目.md"), "根上那份旧人设")
        val pf = PersonaFiles(
            globalRoot = File(ws, "全局"),
            projectRoot = File(ws, "抱朴记忆"),
            legacyProjectRoot = ws,
        )
        assertEquals("新位置没有 ⇒ 回落到根上的旧人设（升级不能像丢人设）", "根上那份旧人设", pf.read(PersonaLayer.C_PROJECT))
        // 负例：新位置写了东西，就读新的
        write(File(ws, "抱朴记忆/人设-本项目.md"), "新人设")
        assertEquals("新人设", pf.read(PersonaLayer.C_PROJECT))
        assertNull("B 层不许沾 C 层的回落逻辑", PersonaFiles(File(ws, "全局"), File(ws, "抱朴记忆"), ws).read(PersonaLayer.B_GLOBAL))
    }
}
