// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #10 的第⑤处：`GlobalMemory.load` 的「没有」与「坏了」必须可分。
//
// 改前表现（`全仓隐患审计-2026-10-28.md` A-P2-21）：一句
// `runCatching { fileIn(filesDir).takeIf { it.isFile }?.readText(UTF_8) }.getOrNull()?.takeIf { isNotBlank() }`
// 把**四种**情况全折成 null：① 没写过 ② 写过但全是空白 ③ 文件在但读不动（权限/磁盘/被占）④ 有内容。
// ③ 与 ① 同形最要命：下一条 [GlobalMemory.save] 是**整份覆盖**——把"读坏了"当成"本来就没有"，
// 用户的全局记忆就在下一次保存时被空内容替掉（数据丢失放大点）。
//
// 本批口径：157 §0 说这一份**还没有任何调用方**（只做存管、不接注入）⇒ 允许降级，
// 但不许悄悄降级：`load` 的返回值一位没动（①②③ 仍 null、④ 仍原文），③ 额外说一句。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GlobalMemoryReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files: File by lazy { tmp.newFolder("files") }

    private fun src(rel: String): String {
        val f = listOf(rel, "../$rel").map { File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到源码 $rel（门禁不许空转，cwd=${File("").absolutePath}）", f != null)
        return f!!.readText()
    }

    /** 造一个「文件确实在、但读不动」的场景：JVM 里唯一稳的办法是把读这一步换掉（本机造不出真故障）。 */
    private class BrokenDisk(msg: String) : IOException(msg)

    // ── 四态分得开 ──────────────────────────────────────────────────────

    @Test
    fun `没写过与读不动分得开`() {
        // 改前：两种都只回 null，日志也没有。
        val absent = GlobalMemory.loadState(files) { throw BrokenDisk("读不了") }
        assertTrue("没文件必须是 Absent，不是 Unreadable：$absent",
            absent is GlobalMemory.GlobalMemoryRead.Absent)
    }

    @Test
    fun `空白与读不动分得开`() {
        val f = GlobalMemory.fileIn(files)
        f.parentFile!!.mkdirs()
        f.writeText("   \n\t ", Charsets.UTF_8)
        assertTrue("全空白是 Blank（原口径：按没有）",
            GlobalMemory.loadState(files) is GlobalMemory.GlobalMemoryRead.Blank)
        val broken = GlobalMemory.loadState(files) { throw BrokenDisk("读不了") }
        assertTrue("同一份盘，读不动必须报 Unreadable：$broken",
            broken is GlobalMemory.GlobalMemoryRead.Unreadable)
        assertEquals("要带异常类别", "BrokenDisk", (broken as GlobalMemory.GlobalMemoryRead.Unreadable).why)
        assertEquals("还要报得出文件多大（能对账）", f.length(), broken.bytes)
    }

    @Test
    fun `读到内容就是 ok`() {
        assertTrue(GlobalMemory.save(files, "偏好：中文\n"))
        val ok = GlobalMemory.loadState(files)
        assertTrue(ok is GlobalMemory.GlobalMemoryRead.Ok)
        assertEquals("偏好：中文\n", (ok as GlobalMemory.GlobalMemoryRead.Ok).text)
    }

    // ── load 的行为一位没动，只是多说了两句 ─────────────────────────────

    @Test
    fun `load 的返回值口径没变`() {
        val logs = mutableListOf<String>()
        assertNull("没写过 ⇒ null（原样）", GlobalMemory.load(files, { logs.add(it) }))
        assertTrue("没写过不许报警：" + logs, logs.isEmpty())
        val f = GlobalMemory.fileIn(files)
        f.parentFile!!.mkdirs()
        f.writeText("   ", Charsets.UTF_8)
        assertNull("全空白 ⇒ null（原样）", GlobalMemory.load(files, { logs.add(it) }))
        assertTrue("空白也不算坏，不许报警", logs.isEmpty())
        assertTrue(GlobalMemory.save(files, "偏好：简洁"))
        assertEquals("有内容 ⇒ 原文（原样）", "偏好：简洁", GlobalMemory.load(files, { logs.add(it) }))
        assertTrue("正常读不许报警", logs.isEmpty())
    }

    @Test
    fun `读不动时将事情说清楚`() {
        assertTrue(GlobalMemory.save(files, "偏好：中文、简洁"))
        val logs = mutableListOf<String>()
        val got = GlobalMemory.load(files, { logs.add(it) }, { throw BrokenDisk("读不了") })
        assertNull("仍然返回 null（157 无调用方 ⇒ 允许降级）", got)
        assertEquals("只许说一句", 1, logs.size)
        val line = logs.single()
        assertTrue("要说清是读不动：$line", line.contains("读不动"))
        assertTrue("要带异常类别：$line", line.contains("BrokenDisk"))
        assertTrue("要报得出字节数：$line", line.contains("${GlobalMemory.fileIn(files).length()}"))
        assertTrue("要提醒 save 是整份覆盖：$line", line.contains("整份覆盖"))
        assertTrue("要说本轮按没有跑（不拦）：$line", line.contains("本轮按「没有」跑"))
    }

    @Test
    fun `读坏了不许自动建文件也不许覆盖`() {
        val f = GlobalMemory.fileIn(files)
        f.parentFile!!.mkdirs()
        f.writeText("原稿", Charsets.UTF_8)
        GlobalMemory.load(files, { }, { throw BrokenDisk("读不了") })
        assertEquals("读一次不许动盘上的字节", "原稿", f.readText(Charsets.UTF_8))
    }

    // ── 负例 ────────────────────────────────────────────────────────────

    @Test
    fun `负例_改前的一折到底写法不许回来`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/GlobalMemory.kt")
        assertFalse("不许再用「isFile 筛一层 + getOrNull」把四态折成 null",
            s.contains("fileIn(filesDir).takeIf { it.isFile }?.readText(Charsets.UTF_8)"))
        assertFalse("旧的一行式签名不许回来（改前它把四种情况全折成 null）",
            s.contains("fun load(filesDir: File): String? ="))
        assertFalse("不许留那句「不报错，静默当没有」的口径注释", s.contains("不报错，静默当没有"))
        assertTrue("四态类型要在位", s.contains("sealed interface GlobalMemoryRead"))
    }

    @Test
    fun `负例_静默改成崩溃`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/GlobalMemory.kt")
        assertFalse("load/loadState 不许 throw（§3 负例）", s.contains("throw "))
        assertFalse("不许让异常穿透到调用方", s.contains("catch ("))
        assertTrue("runCatching 兜底仍在", s.contains("runCatching { read(f) }"))
    }

    @Test
    fun `负例_只加日志不交状态`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/GlobalMemory.kt")
        // 「坏了」必须能被调用方**拿到**（状态），不只是打在日志里。
        assertTrue("要有把四态交出去的读入口", s.contains("fun loadState("))
        assertTrue("Unreadable 带原因与字节数", s.contains("data class Unreadable(val why: String, val bytes: Long)"))
        assertTrue("日志是**说明降级**不是唯一出口", s.contains("log: (String) -> Unit = { RunLog.log(it) }"))
    }

    @Test
    fun `负例_save 的原子写口径一位没动`() {
        // 本条只改读侧；写侧（.tmp + rename + 返回布尔）是 157 的原设计，不许顺手改。
        val s = src("src/main/java/com/example/zhengdao/baopu/GlobalMemory.kt")
        assertTrue(s.contains("fun save(filesDir: File, text: String): Boolean"))
        assertTrue(s.contains("tmp.renameTo(f)"))
        assertTrue(s.contains("f.isFile"))
    }
}
