// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批（设计修订）· 判据「定位：给一个"最近下载的 zip"，模型能自己找到」。
//
// 两半都要钉：① 默认导入目录里挑哪个（按 mtime，不按名字）；② 模型那句话落到哪条工具链
// （find_files order=recent 定位 → import_data(path) 定点导入；没给路径时走收件箱）。
package com.example.zhengdao.backup

import com.example.zhengdao.baopu.agent.FindFilesTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.chooseSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupDiscoveryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zd491-disco").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, mtime: Long, bytes: String = "x"): File =
        File(dir, name).apply {
            writeText(bytes, Charsets.UTF_8)
            setLastModified(mtime)
        }

    // ── ① 默认导入目录里挑哪个 ───────────────────────────────────────────────

    @Test
    fun 按修改时间挑最近的而不是按名字() {
        // 名字故意反着来：老的那个名字"更靠前"，但它是三天前的
        file("A-旧备份.zip", 1_000_000_000_000L)
        file("Z-刚下载.zip", 1_762_400_000_000L)
        val picked = BackupDiscoveryRules.pick(listOf(File(dir, "A-旧备份.zip"), File(dir, "Z-刚下载.zip")))
        assertEquals("最近放进去的必须排第一", "Z-刚下载.zip", picked[0].name)
        assertEquals(2, picked.size)
    }

    @Test
    fun 只认zip且不认隐藏文件() {
        val ok = file("证道备份.zip", 1_762_400_000_000L)
        val note = file("说明.txt", 1_762_400_000_000L)
        val hidden = file(".证道备份.zip.part", 1_762_400_000_000L)
        val picked = BackupDiscoveryRules.pick(listOf(ok, note, hidden))
        assertEquals(1, picked.size)
        assertEquals(ok.name, picked[0].name)
    }

    @Test
    fun 候选数量有上限且空目录不炸() {
        val files = (1..8).map { file("b$it.zip", 1_000_000_000_000L + it) }
        assertEquals(BackupDiscoveryRules.MAX_CANDIDATES, BackupDiscoveryRules.pick(files).size)
        assertTrue(BackupDiscoveryRules.pick(emptyList()).isEmpty())
    }

    @Test
    fun 提示文案把后果说清() {
        val note = BackupDiscoveryRules.noteOf("证道备份.zip", 12, "2026-11-06 14:20:10")
        assertTrue(note, note.contains("12 项"))
        assertTrue(note, note.contains("不会自己动"))
    }

    // ── ② 模型那句话落到哪条工具链 ───────────────────────────────────────────

    @Test
    fun 点了名就用点名的包() {
        val named = File(dir, "用户说的那个.zip")
        val inbox = File(dir, "收件箱里那个.zip")
        assertEquals(named.absolutePath, chooseSource(named.absolutePath, inbox)?.absolutePath)
    }

    @Test
    fun 没点名就用收件箱里最近那个() {
        val inbox = File(dir, "收件箱里那个.zip")
        assertEquals(inbox.absolutePath, chooseSource("   ", inbox)?.absolutePath)
    }

    @Test
    fun 既没点名收件箱也空就返回空让调用方去扫目录() {
        assertNull(chooseSource("", null))
    }

    /** 「刚下载那个 zip」：find_files order=recent 必须把最新的排第一、并且带回时间。 */
    @Test
    fun find_files按最近排序并把时间带出来() {
        val old = file("旧包.zip", 1_000_000_000_000L)
        val fresh = file("刚下载.zip", 1_762_400_000_000L)
        val out = FindFilesTool.find(dir, dir, ".zip", order = FindFilesTool.ORDER_RECENT)
        val text = (out as ToolOutcome.Ok).text
        val iFresh = text.indexOf(fresh.name)
        val iOld = text.indexOf(old.name)
        assertTrue("两个都要命中：$text", iFresh >= 0 && iOld >= 0)
        assertTrue("最近的必须排前面：$text", iFresh < iOld)
        assertTrue("按时间排序时要带时间（不然模型只能靠顺序猜）：$text", text.contains(":"))
        assertTrue("要说清这是按时间排的：$text", text.contains("按修改时间倒序"))
    }

    /** 不传 order 时行为与以前逐字一致（默认按路径；不带时间列）。 */
    @Test
    fun 默认仍然按路径排序() {
        val a = file("aaa.zip", 1_762_400_000_000L)
        val b = file("zzz.zip", 1_000_000_000_000L)
        val text = (FindFilesTool.find(dir, dir, ".zip") as ToolOutcome.Ok).text
        assertTrue("默认顺序是路径：$text", text.indexOf(a.name) < text.indexOf(b.name))
        assertTrue("默认不该多出时间列：$text", !text.contains("按修改时间倒序"))
    }
}
