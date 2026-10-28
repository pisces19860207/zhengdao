// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #10 的第④处：`ExampleBank.loadArchive` 的「没有」与「坏了」必须可分。
//
// 改前表现（`全仓隐患审计-2026-10-28.md` A-P2-21 点名"最值得看"的那一条）：
// `runCatching { f.readText(UTF_8) }.getOrNull() ?: return TasteArchive()` —— 档案文件**读不动**
// 时按空档案返回，而设置页/注入侧拿到空档案后下一次 `saveArchive` 会把整份覆盖回去：
// 「读坏了」被放大成"用户手改的口味档案没了"。日志里一个字都没有。
//
// 本批做法：状态交给调用方（[ExampleBank.loadArchiveState]），`loadArchive` 保持原返回值
// （空档案，不拦本轮），但坏读必须说一句，并且**不许**在读取路径上建/覆盖文件。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TasteArchiveReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val product: File by lazy { tmp.newFolder("产物") }

    private fun src(rel: String): String {
        val f = listOf(rel, "../$rel").map { File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到源码 $rel（门禁不许空转，cwd=${File("").absolutePath}）", f != null)
        return f!!.readText()
    }

    private class BadDisk(msg: String) : IOException(msg)

    // ── 三态 ────────────────────────────────────────────────────────────

    @Test
    fun `没写过与读不动分得开`() {
        // 改前：两种都只回一个空档案，谁也不吱声。
        val absent = ExampleBank.loadArchiveState(product) { throw BadDisk("读不了") }
        assertTrue("文件不存在必须是 Absent：$absent", absent is ExampleBank.ArchiveRead.Absent)
    }

    @Test
    fun `读不动报得出异常类别与字节数`() {
        val f = ExampleBank.archiveFile(product)
        f.parentFile!!.mkdirs()
        f.writeText(TasteProfile.template("2026-10-28 09:00"), Charsets.UTF_8)
        val r = ExampleBank.loadArchiveState(product) { throw BadDisk("读不了") }
        assertTrue("文件在却读不动 ⇒ Unreadable：$r", r is ExampleBank.ArchiveRead.Unreadable)
        assertEquals("只带异常类别，不带路径正文", "BadDisk", (r as ExampleBank.ArchiveRead.Unreadable).why)
        assertEquals("字节数要报得出（能对账）", f.length(), r.bytes)
        assertTrue("字节数不是 0（文件真在盘上）", r.bytes > 0L)
    }

    @Test
    fun `读到内容才是 ok`() {
        val f = ExampleBank.archiveFile(product)
        f.parentFile!!.mkdirs()
        f.writeText(TasteProfile.template("2026-10-28 09:00"), Charsets.UTF_8)
        val r = ExampleBank.loadArchiveState(product)
        assertTrue(r is ExampleBank.ArchiveRead.Ok)
        assertEquals("档案版本号读得出来", "v1", (r as ExampleBank.ArchiveRead.Ok).archive.version)
    }

    // ── loadArchive 的返回值口径没动，只是坏读会说话 ─────────────────────

    @Test
    fun `load_archive 返回值口径不变`() {
        val logs = mutableListOf<String>()
        assertEquals("没写过 ⇒ 空档案（原样）", TasteArchive(), ExampleBank.loadArchive(product, { logs.add(it) }))
        assertTrue("没写过不许报警", logs.isEmpty())
    }

    @Test
    fun `坏读时将事情说清楚仍回空档案`() {
        val f = ExampleBank.archiveFile(product)
        f.parentFile!!.mkdirs()
        f.writeText(TasteProfile.template("2026-10-28 09:00") + "\n- 不写失业。证据：台账 ✔\n", Charsets.UTF_8)
        val logs = mutableListOf<String>()
        val got = ExampleBank.loadArchive(product, { logs.add(it) }, { throw BadDisk("读不了") })
        assertEquals("仍回空档案（不拦本轮）", 0, got.entries.size)
        assertEquals("只许说一句", 1, logs.size)
        val line = logs.single()
        assertTrue("要说清是读不动：$line", line.contains("读不动"))
        assertTrue("要带异常类别：$line", line.contains("BadDisk"))
        assertTrue("要报得出字节数：$line", line.contains("${f.length()}"))
        assertTrue("要提醒别在这上面写回：$line", line.contains("别在这上面写回"))
        assertTrue("要指出 .bak 这条退路：$line", line.contains(".bak"))
    }

    @Test
    fun `读取路径一个字节都不动盘`() {
        val f = ExampleBank.archiveFile(product)
        f.parentFile!!.mkdirs()
        f.writeText("原稿", Charsets.UTF_8)
        ExampleBank.loadArchive(product, { }, { throw BadDisk("读不了") })
        assertEquals("读一次不许动正文", "原稿", f.readText(Charsets.UTF_8))
        assertFalse("不许悄悄建 .bak", File(f.parentFile, f.name + ".bak").exists())
        // 也没人给一个空目录建档案
        val fresh = tmp.newFolder("没写过")
        ExampleBank.loadArchive(fresh, { }, { throw BadDisk("读不了") })
        assertFalse("读取不许自动建模板（那是 ensureArchive 的活）",
            File(fresh, TasteProfile.ARCHIVE_NAME).exists())
    }

    // ── 负例 ────────────────────────────────────────────────────────────

    @Test
    fun `负例_改前的一折到底写法不许回来`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/ExampleBank.kt")
        assertFalse("旧写法（坏读折成空档案且不说话）不许回来",
            s.contains("runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return TasteArchive()"))
        assertFalse("旧签名不许回来", s.contains("fun loadArchive(productDir: File): TasteArchive {"))
        assertTrue("三态类型在位", s.contains("internal sealed interface ArchiveRead"))
    }

    @Test
    fun `负例_只加日志不交状态`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/ExampleBank.kt")
        assertTrue("状态得能被调用方拿到", s.contains("fun loadArchiveState("))
        assertTrue("Unreadable 带原因与字节数", s.contains("data class Unreadable(val why: String, val bytes: Long)"))
        assertTrue("日志可注入（说明降级是**说给人听**，不是唯一出口）",
            s.contains("log: (String) -> Unit = { RunLog.log(it) }"))
    }

    @Test
    fun `负例_不许把静默改成崩溃`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/ExampleBank.kt")
        assertFalse("读侧不许 throw（§3 负例）", s.contains("throw "))
        assertTrue("runCatching 兜底仍在", s.contains("runCatching { read(f) }"))
    }

    @Test
    fun `负例_写侧与不联网红线一位没动`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/ExampleBank.kt")
        // 202 批红线：本批只改读侧，写侧（临时文件 + rename + .bak）与「不联网」不许动。
        assertTrue(s.contains("tmp.renameTo(f)"))
        assertTrue(s.contains("f.copyTo(File(dir, f.name + \".bak\"), overwrite = true)"))
        listOf("java.net.", "HttpURLConnection", "okhttp", "URL(", "Socket").forEach {
            assertFalse("不该出现 $it（202 批红线：不联网）", s.contains(it))
        }
    }
}
