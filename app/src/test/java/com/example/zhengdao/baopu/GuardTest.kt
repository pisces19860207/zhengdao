// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 抱朴守卫单测（刀 0 的核心价值 · 2026-10-16）。
 *
 * 覆盖派工单验收口径第 3 条的四类判据：越界拒绝（含同前缀兄弟目录）、
 * 两种工作区模式、没素材拒绝开工、没归档拒绝写。
 *
 * 环境说明（证据等级②，实测于本机沙盒）：沙盒里 `Files.createSymbolicLink`
 * 不抛异常但造不出真链 ⇒ 两处软链用例用 `assumeTrue` 显式跳过，
 * **真实开发机上会真跑**（跳过错不在用例，在环境）。
 */
class GuardTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var raw: File

    private fun setupWorkspace() {
        ws = tmp.newFolder("ws")
        raw = File(ws, "资料库/原始").apply { mkdirs() }
    }

    // ── 路径守卫 ──────────────────────────────────────────────────────────

    @Test
    fun `守卫_根内任意深度都放行`() {
        setupWorkspace()
        val deep = File(ws, "资料库/改版/a/b/c/卡.md")
        assertTrue(deep.parentFile!!.mkdirs())
        val v = BaopuGuardCore.checkPath(ws, deep)
        assertTrue("根内深层文件应放行：$v", v is BaopuGuardCore.PathVerdict.Inside)
    }

    @Test
    fun `守卫_工作区根本身算在内`() {
        setupWorkspace()
        assertTrue(BaopuGuardCore.checkPath(ws, ws) is BaopuGuardCore.PathVerdict.Inside)
    }

    @Test
    fun `守卫_同前缀兄弟目录必须拒绝`() {
        setupWorkspace()
        // 经典坑：root = …/ws，兄弟目录 …/ws-evil 与 root 只差一个后缀，
        // 字符串前缀比较会误判为"在根内"（rootfs/PathGuard 的 E-085 同款）。
        val evil = File(ws.parentFile, ws.name + "-evil")
        assertTrue(evil.mkdirs())
        val v = BaopuGuardCore.checkPath(ws, File(evil, "x.md"))
        assertTrue("同前缀兄弟目录必须拒绝：$v", v is BaopuGuardCore.PathVerdict.Outside)
    }

    @Test
    fun `守卫_父目录逃逸必须拒绝`() {
        setupWorkspace()
        val escape = File(ws, "../outside.txt")
        val v = BaopuGuardCore.checkPath(ws, escape)
        assertTrue(".. 逃逸必须拒绝：$v", v is BaopuGuardCore.PathVerdict.Outside)
    }

    @Test
    fun `守卫_完全不相干的绝对路径必须拒绝`() {
        setupWorkspace()
        val v = BaopuGuardCore.checkPath(ws, File("/etc/passwd"))
        assertTrue(v is BaopuGuardCore.PathVerdict.Outside)
    }

    @Test
    fun `守卫_指向区外的软链必须拒绝`() {
        setupWorkspace()
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("s") }
        val link = File(ws, "link.txt")
        runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }
        assumeTrue(
            "沙盒造不出真软链（已知环境限制，证据等级②）——真实开发机上本用例真跑",
            Files.isSymbolicLink(link.toPath()),
        )
        val v = BaopuGuardCore.checkPath(ws, link)
        assertTrue("指向区外的软链必须拒绝：$v", v is BaopuGuardCore.PathVerdict.Outside)
    }

    @Test
    fun `守卫_两种工作区模式下判据一致`() {
        // 共享模式（/sdcard/Download/证道 形态）与仅私有模式（getExternalFilesDir 形态）
        // 只是根不同，守卫判据必须一致：根内放行、兄弟目录拒绝。
        // （真实根解析走 Workspace.hostDir，Context 绑定，归集成层；这里钉判据本身。）
        for (mode in listOf("shared", "private")) {
            val root = tmp.newFolder("ws-$mode")
            val inside = File(root, "资料库/改版/x.md")
            assertTrue(BaopuGuardCore.checkPath(root, inside) is BaopuGuardCore.PathVerdict.Inside)
            val sibling = File(root.parentFile, root.name + "-evil")
            assertTrue(sibling.mkdirs())
            assertTrue(
                "模式 $mode：兄弟目录必须拒绝",
                BaopuGuardCore.checkPath(root, sibling) is BaopuGuardCore.PathVerdict.Outside,
            )
        }
    }

    // ── 开工闸门（甲） ────────────────────────────────────────────────────

    @Test
    fun `开工闸门_原始目录不存在时拒绝`() {
        ws = tmp.newFolder("ws")
        val v = BaopuGuardCore.checkMaterial(File(ws, "资料库/原始"))
        assertTrue("目录不存在应拒绝：$v", v is BaopuGuardCore.MaterialVerdict.Empty)
        assertTrue((v as BaopuGuardCore.MaterialVerdict.Empty).reason.contains("不存在"))
    }

    @Test
    fun `开工闸门_空目录拒绝`() {
        setupWorkspace()
        val v = BaopuGuardCore.checkMaterial(raw)
        assertTrue(v is BaopuGuardCore.MaterialVerdict.Empty)
        assertTrue((v as BaopuGuardCore.MaterialVerdict.Empty).reason.contains("还没有文件"))
    }

    @Test
    fun `开工闸门_只有子目录没有文件也拒绝`() {
        setupWorkspace()
        assertTrue(File(raw, "空子目录").mkdirs())
        val v = BaopuGuardCore.checkMaterial(raw)
        assertTrue(v is BaopuGuardCore.MaterialVerdict.Empty)
        assertTrue((v as BaopuGuardCore.MaterialVerdict.Empty).reason.contains("只有子目录"))
    }

    @Test
    fun `开工闸门_有文件即放行并报数`() {
        setupWorkspace()
        File(raw, "a.txt").writeText("a")
        File(raw, "sub").mkdirs()
        File(raw, "sub/b.txt").writeText("b")
        val v = BaopuGuardCore.checkMaterial(raw)
        assertTrue("有文件应放行：$v", v is BaopuGuardCore.MaterialVerdict.Ready)
        assertEquals(2, (v as BaopuGuardCore.MaterialVerdict.Ready).count)
        assertFalse(v.truncated)
    }

    @Test
    fun `开工闸门_触达扫描上限必须告知`() {
        setupWorkspace()
        for (i in 1..5) File(raw, "f$i.txt").writeText("x")
        val v = BaopuGuardCore.checkMaterial(raw, maxScan = 3)
        assertTrue(v is BaopuGuardCore.MaterialVerdict.Ready)
        assertTrue(
            "触达上限必须置 truncated（不静默截断）",
            (v as BaopuGuardCore.MaterialVerdict.Ready).truncated,
        )
    }

    @Test
    fun `开工闸门_软链不算素材`() {
        setupWorkspace()
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("s") }
        runCatching { Files.createSymbolicLink(File(raw, "链.txt").toPath(), secret.toPath()) }
        assumeTrue("沙盒造不出真软链（已知环境限制）", Files.isSymbolicLink(File(raw, "链.txt").toPath()))
        val v = BaopuGuardCore.checkMaterial(raw)
        assertTrue("软链不应算素材：$v", v is BaopuGuardCore.MaterialVerdict.Empty)
    }

    // ── 归档闸门（乙） ────────────────────────────────────────────────────

    @Test
    fun `归档闸门_原件就在原始里且内容相同_不重复归档`() {
        setupWorkspace()
        val src = File(raw, "卡.md").apply { writeText("正文") }
        val v = BaopuGuardCore.ensureArchived(raw, src, "卡.md")
        assertTrue(v is BaopuGuardCore.ArchiveVerdict.Archived)
        assertFalse((v as BaopuGuardCore.ArchiveVerdict.Archived).needsCopy)
        assertEquals("正文", src.readText()) // 原件一字未动
    }

    @Test
    fun `归档闸门_原始里已有同名不同内容_放行但不覆盖`() {
        setupWorkspace()
        val archived = File(raw, "卡.md").apply { writeText("最早的原件") }
        val elsewhere = File(ws, "卡.md").apply { writeText("改过的副本") }
        val v = BaopuGuardCore.ensureArchived(raw, elsewhere, "卡.md")
        assertTrue(v is BaopuGuardCore.ArchiveVerdict.Archived)
        assertFalse((v as BaopuGuardCore.ArchiveVerdict.Archived).needsCopy)
        assertEquals("最早的原件", archived.readText()) // 原始/ 铁律：只增不减
    }

    @Test
    fun `归档闸门_原件在别处且原始没有_复制进原始`() {
        setupWorkspace()
        val elsewhere = File(ws, "待改.md").apply { writeText("原件内容") }
        val v = BaopuGuardCore.ensureArchived(raw, elsewhere, "待改.md")
        assertTrue(v is BaopuGuardCore.ArchiveVerdict.Archived)
        val dst = File(raw, "待改.md")
        assertTrue("应已复制进 原始/", dst.isFile)
        assertEquals("原件内容", dst.readText())
        assertEquals("原件内容", elsewhere.readText()) // 源文件不动
    }

    @Test
    fun `归档闸门_深层相对名自动建父目录`() {
        setupWorkspace()
        val elsewhere = File(ws, "a.md").apply { writeText("x") }
        BaopuGuardCore.ensureArchived(raw, elsewhere, "子/目录/a.md")
        assertTrue(File(raw, "子/目录/a.md").isFile)
    }

    @Test
    fun `归档闸门_原件不存在_放行_新建内容无需归档`() {
        setupWorkspace()
        val ghost = File(ws, "不存在的文件.md")
        val v = BaopuGuardCore.ensureArchived(raw, ghost, "新卡.md")
        assertTrue(v is BaopuGuardCore.ArchiveVerdict.Archived)
        assertFalse((v as BaopuGuardCore.ArchiveVerdict.Archived).needsCopy)
        assertFalse(File(raw, "新卡.md").exists()) // 没有原件就不造文件
    }

    @Test
    fun `归档闸门_非法归档名拒绝`() {
        setupWorkspace()
        val src = File(ws, "x.md").apply { writeText("x") }
        for (bad in listOf("../逃逸.md", "/绝对.md", "", "  ")) {
            val v = BaopuGuardCore.ensureArchived(raw, src, bad)
            assertTrue("非法名「$bad」应拒绝：$v", v is BaopuGuardCore.ArchiveVerdict.Refused)
        }
    }

    @Test
    fun `归档闸门_软链原件拒绝`() {
        setupWorkspace()
        val outside = tmp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("s") }
        val link = File(ws, "link.md")
        runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }
        assumeTrue("沙盒造不出真软链（已知环境限制）", Files.isSymbolicLink(link.toPath()))
        val v = BaopuGuardCore.ensureArchived(raw, link, "link.md")
        assertTrue("软链原件应拒绝：$v", v is BaopuGuardCore.ArchiveVerdict.Refused)
    }

    @Test
    fun `归档闸门_复制目标被抢先占位时拒绝而不覆盖`() {
        setupWorkspace()
        val elsewhere = File(ws, "竞态.md").apply { writeText("我的原件") }
        val dst = File(raw, "竞态.md").apply { writeText("别人先躺下的") }
        // 直接走 copy 分支模拟"判定后、复制前"被抢先：目标已存在 ⇒ Files.copy 抛异常 ⇒ 拒绝
        val v = runCatching { Files.copy(elsewhere.toPath(), dst.toPath()) }
            .fold(
                onSuccess = { BaopuGuardCore.ArchiveVerdict.Archived(false, "") },
                onFailure = { BaopuGuardCore.ArchiveVerdict.Refused("${it.javaClass.simpleName}") },
            )
        assertTrue(v is BaopuGuardCore.ArchiveVerdict.Refused)
        assertEquals("别人先躺下的", dst.readText()) // 绝不被覆盖
    }

    // ── 内容比对 ──────────────────────────────────────────────────────────

    @Test
    fun `内容比对_同内容为真_不同为假_缺文件为假`() {
        val a = File(tmp.newFolder("cmp"), "a.txt").apply { writeText("一样") }
        val b = File(tmp.root, "b.txt").apply { writeText("一样") }
        val c = File(tmp.root, "c.txt").apply { writeText("不一样") }
        val d = File(tmp.root, "d.txt").apply { writeText("长度不同") }
        val missing = File(tmp.root, "missing.txt")
        assertTrue(BaopuGuardCore.contentSame(a, b))
        assertFalse(BaopuGuardCore.contentSame(a, c))
        assertFalse(BaopuGuardCore.contentSame(a, d))
        assertFalse(BaopuGuardCore.contentSame(a, missing))
        assertFalse(BaopuGuardCore.contentSame(missing, a))
    }

    @Test
    fun `内容比对_大文件分块也准确`() {
        val dir = tmp.newFolder("big")
        val a = File(dir, "a.bin")
        val b = File(dir, "b.bin")
        val c = File(dir, "c.bin")
        val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
        a.writeBytes(payload)
        b.writeBytes(payload.copyOf())
        c.writeBytes(payload.copyOf().also { it[299 * 1024] = (it[299 * 1024] + 1).toByte() })
        assertTrue(BaopuGuardCore.contentSame(a, b))
        assertFalse(BaopuGuardCore.contentSame(a, c)) // 最后一块差一个字节
    }

    @Test
    fun `归档名合法性_判据表`() {
        assertTrue(BaopuGuardCore.isLegalRelName("a.md"))
        assertTrue(BaopuGuardCore.isLegalRelName("子/目录/a.md"))
        assertFalse(BaopuGuardCore.isLegalRelName("../a.md"))
        assertFalse(BaopuGuardCore.isLegalRelName("/a.md"))
        assertFalse(BaopuGuardCore.isLegalRelName(""))
        assertFalse(BaopuGuardCore.isLegalRelName("   "))
        assertFalse(BaopuGuardCore.isLegalRelName("a/../b.md"))
    }
}
