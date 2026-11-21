// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.scan.ScanTolerance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 772 五项语义夹具对拍（E-197 ①②）：scanTree／countTopLevel 换了列举原语
 * （java.io → [ScanTolerance.listTolerant]），**除「坏名」这一条正增量外，
 * 一切产出必须与旧原语逐项一致**。
 *
 * 对拍的「另一边」＝765 之前实现的**逐行复刻**（java.io 原语，只活在测试里）：
 * 同一棵夹具树两边各扫一遍，relPath／名字／大小／时间／分类／截断旗逐项比。
 *
 * 五项语义（派工单①）：
 * - ① HARD_CAP：>8000 件照旧停住、truncated 旗同、取前 2000 件同；未到 HARD_CAP
 *   但超 MAX_SCAN 也照旧亮旗；
 * - ② 软链不跟随：树内文件链与**树外目录链**都不进结果、目标不被扫；
 * - ③ 垃圾名过滤：`.DS_Store`／`Thumbs.db`／`._*` 两边一致不进；
 * - ④ PathGuard：isInside 双保险原样（该行未动，对拍确认换原语不使它失守）；
 * - ⑤ 返回顺序：显式按 relPath 排序，两次连跑一致（不赌 readdir 顺序）。
 *
 * 坏名（772 的正增量）单独一组：U+FFFD 真落盘（765 口径的过境形态，NTFS 建得出来）
 * ⇒ 不进清单、不炸、其余全列、countTopLevel 与「无坏名时」逐字一致（坏名不计入、不中断）。
 * 跳过记账的行形状 core 层已钉死（ScanToleranceTest），这里只验「确实记了账」。
 */
class LibraryPathsScanSemanticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 参照实现：765 前 scanTree／countTopLevel 的逐行复刻（对拍基准） ────────

    private fun junkName(name: String): Boolean =
        name == ".DS_Store" || name == "Thumbs.db" || name.startsWith("._")

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun relOf(dir: File, f: File): String = f.absolutePath
        .removePrefix(dir.absolutePath)
        .trimStart('/', '\\')
        .replace('\\', '/')

    private fun legacyScanTree(dir: File): LibraryPaths.ScanResult {
        if (!dir.isDirectory) return LibraryPaths.ScanResult(emptyList(), false)
        val cap = LibraryPaths.MAX_SCAN * 4
        val collected = ArrayList<LibraryPaths.ScannedFile>()
        var hardTruncated = false
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && collected.size < cap) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (f in children) {
                if (isSymlink(f)) continue
                when {
                    f.isDirectory -> stack.addLast(f)
                    f.isFile -> {
                        if (junkName(f.name)) continue
                        if (!PathGuard.isInside(dir, f)) continue
                        val (kind, reason) = LibraryPaths.classify(f.name)
                        collected += LibraryPaths.ScannedFile(
                            relOf(dir, f), f.name, f.length(), f.lastModified(), kind, reason,
                        )
                        if (collected.size >= cap) {
                            hardTruncated = true
                            break
                        }
                    }
                }
            }
        }
        collected.sortBy { it.relPath }
        return LibraryPaths.ScanResult(
            collected.take(LibraryPaths.MAX_SCAN),
            hardTruncated || collected.size > LibraryPaths.MAX_SCAN,
        )
    }

    private fun legacyCountTopLevel(dir: File): Int {
        if (!dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        return children.count { !isSymlink(it) }
    }

    // ── 对拍夹具 ─────────────────────────────────────────────────────────────

    @Test
    fun `对拍_递归夹具_产出与旧原语逐项一致`() {
        val root = tmp.newFolder("夹具")
        // 顶层：中英混合名、多扩展名、垃圾名三件、空目录
        listOf("b.txt", "a.md", "c.json", "读我.kt", ".DS_Store", "Thumbs.db", "._残件.txt")
            .forEach { File(root, it).writeText("内容-$it") }
        File(root, "子目录").mkdirs()
        File(root, "子目录/deep.txt").writeText("s")
        File(root, "子目录/更深").mkdirs()
        File(root, "子目录/更深/n.md").writeText("n")
        File(root, "空目录").mkdirs()

        val fresh = LibraryPaths.scanTree(root)
        val legacy = legacyScanTree(root)
        assertEquals("产出逐项一致（含 size/mtime/kind/reason）", legacy, fresh)
        assertFalse(fresh.truncated)
        // ③ 垃圾名两边一致不进
        assertTrue(fresh.files.none { junkName(it.name) })
        // ⑤ 顺序：显式排序独立断言（不赌 readdir 顺序）
        assertEquals(fresh.files.map { it.relPath }, fresh.files.map { it.relPath }.sorted())
        assertTrue(fresh.files.any { it.relPath == "子目录/更深/n.md" })
        // 两次连跑逐项一致
        assertEquals(fresh, LibraryPaths.scanTree(root))
    }

    @Test
    fun `对拍_软链不跟随_树内文件链与树外目录链都不进结果`() {
        val root = tmp.newFolder("夹具")
        val outside = tmp.newFolder("树外")
        File(outside, "outside.txt").writeText("outside")
        File(root, "in.txt").writeText("in")
        val fileLink = File(root, "链.txt")
        val dirLink = File(root, "外链")
        runCatching { java.nio.file.Files.createSymbolicLink(fileLink.toPath(), File(root, "in.txt").toPath()) }
        runCatching { java.nio.file.Files.createSymbolicLink(dirLink.toPath(), outside.toPath()) }
        // 沙盒可能造不出真链（既有约定）⇒ 造不出时本条跳过，不误判
        if (!fileLink.exists() || !dirLink.exists()) return

        val fresh = LibraryPaths.scanTree(root)
        val legacy = legacyScanTree(root)
        assertEquals(legacy.files.map { it.relPath }, fresh.files.map { it.relPath })
        assertEquals(listOf("in.txt"), fresh.files.map { it.relPath })
        // 树外目标没被目录链带进来（跟随了就会有 outside.txt）
        assertTrue(fresh.files.none { it.name == "outside.txt" })
        // countTopLevel 同尺：链不计入
        assertEquals(1, LibraryPaths.countTopLevel(root))
    }

    @Test
    fun `对拍_HARD_CAP_超8000件截断旗与取件一致`() {
        val root = tmp.newFolder("大量夹具")
        repeat(8001) { File(root, "f-%05d.txt".format(it)).createNewFile() }

        val fresh = LibraryPaths.scanTree(root)
        val legacy = legacyScanTree(root)
        assertTrue(">HARD_CAP 必须亮截断旗", fresh.truncated)
        assertEquals(legacy.truncated, fresh.truncated)
        assertEquals("取件＝MAX_SCAN", LibraryPaths.MAX_SCAN, fresh.files.size)
        assertEquals("取哪 2000 件与旧原语一致", legacy.files, fresh.files)
    }

    @Test
    fun `对拍_超MAX_SCAN未到HARD_CAP_截断旗照亮`() {
        val root = tmp.newFolder("中量夹具")
        repeat(LibraryPaths.MAX_SCAN + 1) { File(root, "g-%05d.txt".format(it)).createNewFile() }

        val fresh = LibraryPaths.scanTree(root)
        assertTrue(fresh.truncated)
        assertEquals(LibraryPaths.MAX_SCAN, fresh.files.size)
        assertEquals(legacyScanTree(root), fresh)
    }

    // ── 坏名（772 正增量）：不进清单、不炸、其余全列、计数不变 ────────────────

    @Test
    fun `坏名_UFFFD真落盘_不进清单其余全列_记账在案`() {
        val root = tmp.newFolder("坏名夹具")
        val good = listOf("甲.txt", "乙.md", "丙.json")
        good.forEach { File(root, it).writeText("内容") }
        File(root, "谱\uFFFD记.md").writeText("坏名件") // decode-fail 过境形态（765 口径）

        val scan = LibraryPaths.scanTree(root)
        assertEquals("其余全列、坏名不进", good.sorted(), scan.files.map { it.relPath })
        assertFalse(scan.truncated)

        // 跳过确实记了账（LibraryPaths 默认 sink＝RunLog；行形状 core 已钉，这里验条数与原因码）
        val sink = mutableListOf<ScanTolerance.NameSkip>()
        val listing = ScanTolerance.listTolerant(root, skipSink = { sink += it })
        assertEquals("坏名恰好一条账", 1, listing.skips.size)
        assertEquals("decode-fail", listing.skips[0].reason)
        assertEquals(1, sink.size)
        assertTrue(sink[0].ledgerLine().startsWith("[scan-skip] decode-fail "))
    }

    @Test
    fun `countTopLevel_坏名不计入不中断_与无坏名夹具逐字一致`() {
        val good = listOf("甲.txt", "乙.md", "丙.json")
        val plain = tmp.newFolder("无坏名")
        val dirty = tmp.newFolder("有坏名")
        for (dir in listOf(plain, dirty)) {
            listOf("甲.txt", "乙.md", "丙.json").forEach { File(dir, it).writeText("内容") }
            File(dir, ".DS_Store").writeText("垃圾") // 垃圾名照旧计入（原口径）
            File(dir, "子").mkdirs()
        }
        File(dirty, "谱\uFFFD记.md").writeText("坏名件")

        assertEquals("坏名不计入：两夹具计数一致", LibraryPaths.countTopLevel(plain), LibraryPaths.countTopLevel(dirty))
        assertEquals("与旧原语对拍（好夹具）", legacyCountTopLevel(plain), LibraryPaths.countTopLevel(plain))
        // 旧原语会多算 1（坏名它照列）——这正是 772 要修的差，负面印证
        assertEquals(legacyCountTopLevel(plain) + 1, legacyCountTopLevel(dirty))
        // scanTree 同场不炸：坏名夹具照常出全好件（relPath 按码点排序：丙<乙<甲）
        assertEquals(good.sorted(), LibraryPaths.scanTree(dirty).files.map { it.relPath })
    }

    @Test
    fun `countTopLevel_口径不变_目录缺失零_软链不计入`() {
        assertEquals(0, LibraryPaths.countTopLevel(File(tmp.newFolder(), "没有")))
        val root = tmp.newFolder("夹具")
        File(root, "a.txt").writeText("x")
        File(root, ".DS_Store").writeText("x")
        File(root, "子").mkdirs()
        val link = File(root, "链")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), File(root, "子").toPath()) }
        val expected = if (link.exists()) 2 else 3 // 链不计入；造不出链的环境三件都在
        assertEquals(expected, LibraryPaths.countTopLevel(root))
        assertEquals(expected, legacyCountTopLevel(root))
    }
}
