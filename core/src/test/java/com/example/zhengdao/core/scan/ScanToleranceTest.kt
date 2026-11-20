// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core.scan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * 765 · 单条目容错列举的判定面（[ScanTolerance]）。
 *
 * 两层夹具（IO 全注入，与 494「Windows chmod 不作数」同口径）：
 * 1. **真文件系统夹具**：临时沙盒里 20 个正常文件 + 建得出来的坏名
 *    （U+FFFD 解码失败、300 字节超长）——验「扫描完成、不崩、跳过进账、正常零影响」；
 * 2. **注入夹具**：假流/假属性读——把五种原因码（含真文件系统造不出的
 *    reserved/attr-fail/denied）逐类造出来，目录级失败与迭代中断也在此验证。
 *
 * 坏名文件的构造方式（复现用，不贴真实名）：`adb shell` 里 `: > "$(printf '\344\272')"`
 * 造截半字 UTF-8 名（716 真机事故的原样）；其余四类同法（printf 造保留名/尾点/超长、
 * chmod 造权限）。测试里用 U+FFFD 字符串（NIO 替换解码的过境形态）代表第一类。
 */
class ScanToleranceTest {

    private var sandbox: File? = null

    @After
    fun tearDown() {
        sandbox?.let { dir -> dir.walkBottomUp().forEach { it.delete() } }
        sandbox = null
    }

    private fun newSandbox(): File {
        val dir = Files.createTempDirectory("zd765-scan").toFile()
        sandbox = dir
        return dir
    }

    /** 300 个 UTF-8 字节的 CJK 名（字符数 100，两平台都建得出来）。 */
    private val tooLongName = "设".repeat(100)
    private val decodeFailName = "谱\uFFFD记.md"

    private fun fakeStream(items: List<Path>): DirectoryStream<Path> = object : DirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = items.toMutableList().iterator()
        override fun close() {}
    }

    // ── 夹具一：真文件系统（20 正常 + 2 类建得出来的坏名） ──────────────────

    @Test
    fun `真夹具_20正常加两类坏名_扫描完成不崩_跳过进账_正常零影响`() {
        val dir = newSandbox()
        val normalNames = (1..20).map { "正常-%02d.md".format(it) }
        normalNames.forEach { Files.createFile(dir.toPath().resolve(it)) }
        Files.createFile(dir.toPath().resolve(decodeFailName))
        Files.createFile(dir.toPath().resolve(tooLongName))

        val sink = mutableListOf<ScanTolerance.NameSkip>()
        val listing = ScanTolerance.listTolerant(dir, skipSink = { sink += it })

        // 判据⑤/②：正常文件全部扫到、一个不少
        assertEquals("20 个正常文件全部扫到", normalNames.sorted(), listing.entries.map { it.name }.sorted())
        // 判据①：坏名两类各记一条，扫描没有中断（能走到这里的断言本身就是"没崩"）
        assertEquals(2, listing.skips.size)
        assertEquals(
            listOf("decode-fail", "too-long"),
            listing.skips.map { it.reason }.sorted(),
        )
        // 判据④：跳过数 == 记账行数（不许静默）
        assertEquals("每条跳过恰好记一行", listing.skips.size, sink.size)
        // 判据③：账行带原因码与转义路径，且不含替换符原字
        sink.forEach { row ->
            assertTrue("账行要带 [scan-skip] 前缀：${row.ledgerLine()}", row.ledgerLine().startsWith("[scan-skip] "))
            assertTrue("账行不允许含替换符原字", !row.ledgerLine().contains('\uFFFD'))
        }
        // 判据⑤：顺序与 java.io listFiles 同源一致（同 readdir 序）
        val viaListFiles = dir.listFiles()!!.map { it.name }
        assertEquals(viaListFiles, listing.entries.map { it.name } + listing.skips.map { it.name })
        // 连跑两次顺序稳定
        val again = ScanTolerance.listTolerant(dir, skipSink = {})
        assertEquals(listing.entries.map { it.name }, again.entries.map { it.name })
        assertNull("目录正常 ⇒ dirError 为 null", listing.dirError)
    }

    // ── 夹具二：注入 IO（五类原因码逐类，含真文件系统造不出的三类） ────────────

    /** 注入夹具：一个真正常文件 + 五类坏名各一，属性读对 lock.md 抛拒绝、对 ghost.md 抛不存在。 */
    private fun injectedDir(): File {
        val dir = newSandbox()
        Files.createFile(dir.toPath().resolve("ok.md"))
        return dir
    }

    private fun pathsOf(dir: File, names: List<String>): List<Path> = names.map { dir.toPath().resolve(it) }

    @Test
    fun `注入夹具_五类原因码逐条进账_正常条目照常`() {
        val dir = injectedDir()
        val names = listOf(
            "ok.md",           // 正常
            decodeFailName,    // decode-fail
            tooLongName,       // too-long
            "CON.md",          // reserved（Windows 保留设备名）
            "尾点. ",          // reserved（尾空格）
            "ghost.md",        // attr-fail（列出后属性读失败：文件已不存在）
            "lock.md",         // denied（属性读被拒）
        )
        val io = ScanTolerance.Io(
            open = { fakeStream(pathsOf(dir, names)) },
            attrs = { path ->
                when (path.fileName.toString()) {
                    "ghost.md" -> throw NoSuchFileException(path.toString())
                    "lock.md" -> throw AccessDeniedException(path.toString())
                    else -> Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                }
            },
        )
        val sink = mutableListOf<ScanTolerance.NameSkip>()
        val listing = ScanTolerance.listTolerant(dir, skipSink = { sink += it }, io = io)

        // 正常条目照常
        assertEquals(listOf("ok.md"), listing.entries.map { it.name })
        // 五类原因码逐条进账（顺序＝流序：decode-fail / too-long / reserved / reserved / attr-fail / denied）
        assertEquals(
            listOf(
                "decode-fail", "too-long", "reserved", "reserved", "attr-fail", "denied",
            ),
            listing.skips.map { it.reason },
        )
        // 判据④：跳过数 == 记账行数
        assertEquals(listing.skips.size, sink.size)
        assertNull(listing.dirError)
    }

    @Test
    fun `目录级拒绝_记一行_denied_交回dirError`() {
        val dir = newSandbox()
        val io = ScanTolerance.Io(open = { throw AccessDeniedException(dir.absolutePath) })
        val sink = mutableListOf<ScanTolerance.NameSkip>()
        val listing = ScanTolerance.listTolerant(dir, skipSink = { sink += it }, io = io)

        assertTrue(listing.entries.isEmpty())
        assertEquals(1, listing.skips.size)
        assertEquals(ScanNameRules.REASON_DENIED, listing.skips.single().reason)
        assertEquals("denied", listing.dirError)
        assertEquals(1, sink.size)
    }

    @Test
    fun `目录级IO失败_记一行_attr_fail_交回dirError`() {
        val dir = newSandbox()
        val io = ScanTolerance.Io(open = { throw IOException("EIO") })
        val sink = mutableListOf<ScanTolerance.NameSkip>()
        val listing = ScanTolerance.listTolerant(dir, skipSink = { sink += it }, io = io)

        assertTrue(listing.entries.isEmpty())
        assertEquals(ScanNameRules.REASON_ATTR_FAIL, listing.skips.single().reason)
        assertEquals("attr-fail", listing.dirError)
        assertEquals(1, sink.size)
    }

    @Test
    fun `迭代中断_丢的是异常不是进程_已扫到的条目与账都保留`() {
        val dir = newSandbox()
        Files.createFile(dir.toPath().resolve("ok.md"))
        val items = pathsOf(dir, listOf("ok.md", "第二个.md"))
        val io = ScanTolerance.Io(
            open = {
                object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
                        private var n = 0
                        override fun hasNext(): Boolean = n < items.size
                        override fun next(): Path {
                            val p = items[n]
                            n += 1
                            if (n == 2) throw DirectoryIteratorException(IOException("readdir 中断"))
                            return p
                        }
                        override fun remove() = throw UnsupportedOperationException()
                    }
                    override fun close() {}
                }
            },
        )
        val listing = ScanTolerance.listTolerant(dir, skipSink = {}, io = io)

        // 第一个正常条目已到手；中断记了目录级行（attr-fail），不再前进
        assertEquals(listOf("ok.md"), listing.entries.map { it.name })
        assertEquals("attr-fail", listing.dirError)
        assertEquals(1, listing.skips.size)
    }

    @Test
    fun `负例_sink写失败不静默_行仍在返回值里`() {
        val dir = injectedDir()
        val names = listOf("ok.md", "ghost.md")
        val io = ScanTolerance.Io(
            open = { fakeStream(pathsOf(dir, names)) },
            attrs = { path ->
                if (path.fileName.toString() == "ghost.md") throw NoSuchFileException(path.toString())
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            },
        )
        val listing = ScanTolerance.listTolerant(
            dir,
            skipSink = { throw IllegalStateException("账本写坏了") },
            io = io,
        )
        // 扫描照常完成：正常条目在、跳过行仍在返回值里（记账失败的兜底层）
        assertEquals(listOf("ok.md"), listing.entries.map { it.name })
        assertEquals(1, listing.skips.size)
        assertEquals(ScanNameRules.REASON_ATTR_FAIL, listing.skips.single().reason)
    }

    @Test
    fun `真目录_空目录_零跳过`() {
        val dir = newSandbox()
        val listing = ScanTolerance.listTolerant(dir, skipSink = {})
        assertTrue(listing.entries.isEmpty())
        assertTrue(listing.skips.isEmpty())
        assertNull(listing.dirError)
    }

    @Test
    fun `账行形状_原因码加转义路径_逐字核对`() {
        val dir = newSandbox()
        val skip = ScanTolerance.NameSkip(
            dirPath = dir.absolutePath,
            name = decodeFailName,
            reason = ScanNameRules.REASON_DECODE_FAIL,
            detail = "名字含替换符（字节不可解码）",
        )
        val line = skip.ledgerLine()
        assertTrue(line.startsWith("[scan-skip] decode-fail "))
        assertTrue("目录路径要在账行里", line.contains(ScanNameRules.escapeName(dir.absolutePath)))
        assertTrue("坏名要转义后出现", line.contains(ScanNameRules.escapeName(decodeFailName)))
        assertTrue("细节要在账行里", line.contains("名字含替换符"))
        assertNotNull(line)
    }

    @Test
    fun `目录不存在_按空表处理_不记行不报错_语义与listFiles的null一致`() {
        val missing = File(newSandbox(), "根本没有这个目录")
        val listing = ScanTolerance.listTolerant(missing, skipSink = {})
        assertTrue(listing.entries.isEmpty())
        assertTrue("不存在的目录不该进跳过账（旧语义是静默 continue）", listing.skips.isEmpty())
        assertNull(listing.dirError)
    }
}
