// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 缓存清理单测（[CacheCleaner]）。
 *
 * 背景（2026-10-07 真机 PGT-AN10 实测，用户报"手机设置里数据有 10G"）：
 * 一、`CacheCleaner.measure()` 的两个探测路径是错的——uv 测的是 `~/.cache/uv`（实测
 * 1 MB），而 hermes 早已把 uv 缓存重定位到 `~/.hermes/cache/uv`（实测 254 MB）；
 * apt 只测 `var/cache/apt/archives`（apt clean 后实测 1 MB），真正占地的
 * `var/lib/apt/lists`（88 MB）没算。四项合计只报 91 MB，离 500 MB 自动清理阈值
 * 差得远，那条通知等于永远不响。
 * 二、`rootfs/tmp` 里堆着 277 MB 残留（32 个同族逐字节相同的 `.so` + 0 字节
 * `mat-debug-*.log`），而这类清理原先"未纳入"。二档现在落地，但删除依据必须**很保守**
 * ——只认固定命名指纹 + 年龄，所以指纹判定与保留名单全部锁进这里。
 */
class CacheCleanerTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun tmpDir(): File =
        File.createTempFile("cc-test", "").let {
            it.delete(); it.mkdirs(); it
        }

    private fun File.aged(fileName: String, sizeBytes: Int, ageMs: Long): File {
        val f = File(this, fileName)
        f.writeBytes(ByteArray(sizeBytes))
        f.setLastModified(now - ageMs)
        return f
    }

    // ── 命名指纹 ────────────────────────────────────────────────────────────

    @Test
    fun `认得出真机上那两族残留`() {
        // 族一：18 份 4.7 MB，族二：14 份 13.3 MB（文件名取自真机 ls）
        assertTrue(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9df-00000001.so"))
        assertTrue(CacheCleaner.isStaleTempName(".3cdffefebdfcf5df-00000008.so"))
        assertTrue(CacheCleaner.isStaleTempName(".dcdfddd7efb17519-00000000.so"))
        assertTrue(CacheCleaner.isStaleTempName(".dcdf5dd4faff13ff-00000000.so"))
    }

    @Test
    fun `认得出空日志`() {
        assertTrue(CacheCleaner.isStaleTempName("mat-debug-6300.log"))
        assertTrue(CacheCleaner.isStaleTempName("mat-debug-28235.log"))
    }

    @Test
    fun `临时目录里必须保留的东西一个都不能认成残留`() {
        // 真机 files/rootfs/tmp 的实际条目：删错任何一个都会伤到会话或系统
        listOf(
            "tmux-0",                     // tmux socket 目录：删了 session 直接断
            ".ses",                       // 会话标记（真机上是被 tmux 用来记 session 的文件）
            "node-compile-cache",         // V8 官方编译缓存目录（仅慢几秒，但不属于本档）
            "opencode",                   // 运行时目录
            "pkg_all.txt", "pkg_skills.txt",
            "uv-ebbe680e99bf0e44.lock",   // uv 锁文件
            ".zhengdao-banner-pending",   // 启动横幅
            "mat-debug.log",              // 没有 pid 的，不认
        ).forEach {
            assertFalse("$it 不该被认成可清残留", CacheCleaner.isStaleTempName(it))
        }
    }

    @Test
    fun `指纹判定是逐位严格的`() {
        assertFalse(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9df-0000000.so"))   // 序号只有 7 位
        assertFalse(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9df-0000000a.so"))  // 序号含非数字
        assertFalse(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9d-00000001.so"))   // 十六进制只有 15 位
        assertFalse(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9dg-00000001.so"))  // 含非十六进制字符
        assertFalse(CacheCleaner.isStaleTempName("3cdf7a7e9f7ee9df-00000001.so"))   // 缺前导点
        assertFalse(CacheCleaner.isStaleTempName(".3cdf7a7e9f7ee9df-00000001.so.bak"))
    }

    @Test
    fun `大写十六进制不认——真机上是小写`() {
        assertFalse(CacheCleaner.isStaleTempName(".3CDF7A7E9F7EE9DF-00000001.so"))
    }

    // ── 年龄门槛 ────────────────────────────────────────────────────────────

    @Test
    fun `刚出现的文件不动——可能正被进程持有`() {
        val dir = tmpDir()
        dir.aged(".3cdf7a7e9f7ee9df-00000001.so", 1024, ageMs = 60_000) // 1 分钟前
        assertEquals(0, CacheCleaner.staleTempFilesIn(dir, now).size)
    }

    @Test
    fun `超过一天才清，且只清命中的那些`() {
        val dir = tmpDir()
        dir.aged(".3cdf7a7e9f7ee9df-00000001.so", 2048, ageMs = dayMs + 1000)
        dir.aged("mat-debug-6300.log", 0, ageMs = dayMs + 1000)
        dir.aged("tmux-0", 512, ageMs = dayMs * 10)          // 保留项，再老也不动
        dir.aged("node-compile-cache", 4096, ageMs = dayMs * 10)
        dir.aged(".dcdfddd7efb17519-00000000.so", 1024, ageMs = 1000) // 太新

        val hit = CacheCleaner.staleTempFilesIn(dir, now).map { it.name }.sorted()
        assertEquals(
            listOf(".3cdf7a7e9f7ee9df-00000001.so", "mat-debug-6300.log"),
            hit,
        )
        assertEquals(2048L, CacheCleaner.staleTempBytesIn(dir, now))
    }

    @Test
    fun `目录即使名字命中也不算——isFile 是硬条件`() {
        val dir = tmpDir()
        File(dir, ".3cdf7a7e9f7ee9df-00000001.so").apply {
            mkdirs()
            setLastModified(now - dayMs * 5)
        }
        assertEquals(0, CacheCleaner.staleTempFilesIn(dir, now).size)
    }

    @Test
    fun `目录不存在返回空而不是抛异常`() {
        val gone = File(tmpDir(), "not-there")
        assertTrue(CacheCleaner.staleTempFilesIn(gone, now).isEmpty())
    }

    // ── 探测路径（本次修的 bug，锁住不许回退）───────────────────────────────

    @Test
    fun `uv 缓存必须同时覆盖 hermes 重定位后的路径`() {
        val files = File("/data/data/com.example.zhengdao/files")
        val paths = CacheCleaner.probePaths(files, File("/cache")).toMap().getValue("uv 缓存")
        val names = paths.map { it.invariantSeparatorsPath }
        assertTrue(
            "hermes 把 uv 缓存放在了 home/.hermes/cache/uv，漏掉它等于漏掉 254MB",
            names.any { it.endsWith("home/.hermes/cache/uv") },
        )
        assertTrue(names.any { it.endsWith("home/.cache/uv") })
    }

    @Test
    fun `apt 缓存必须同时覆盖 lists 索引`() {
        val files = File("/data/data/com.example.zhengdao/files")
        val paths = CacheCleaner.probePaths(files, File("/cache")).toMap().getValue("apt 缓存")
        val names = paths.map { it.invariantSeparatorsPath }
        assertTrue(
            "apt clean 后 archives 是空的，占地的是 var/lib/apt/lists（实测 88MB）",
            names.any { it.endsWith("rootfs/var/lib/apt/lists") },
        )
        assertTrue(names.any { it.endsWith("rootfs/var/cache/apt/archives") })
    }

    @Test
    fun `安装包缓存这个键名被卸载对话框读着，不许改名`() {
        val files = File("/data/data/com.example.zhengdao/files")
        val keys = CacheCleaner.probePaths(files, File("/cache")).map { it.first }
        assertTrue(
            "HomeScreen 用 measure()[安装包缓存] 取数，改名会静默显示 0",
            keys.contains("安装包缓存"),
        )
    }

    @Test
    fun `每一项都指向 files 或 cache 之下，不会误指系统路径`() {
        val files = File("/data/data/com.example.zhengdao/files")
        val cache = File("/data/data/com.example.zhengdao/cache")
        CacheCleaner.probePaths(files, cache).forEach { (name, dirs) ->
            assertFalse("$name 没有候选目录", dirs.isEmpty())
            dirs.forEach { d ->
                assertTrue(
                    "$name 指向了应用私有目录之外：${d.path}",
                    d.path.startsWith(files.path) || d.path.startsWith(cache.path),
                )
            }
        }
    }

    // ── 目录求和：必须不跟进符号链接 ────────────────────────────────────────

    @Test
    fun `嵌套目录按真实文件长度求和`() {
        val dir = tmpDir()
        dir.aged("a.bin", 1000, ageMs = 0)
        File(dir, "sub").mkdirs()
        dir.aged("sub/b.bin", 2000, ageMs = 0)
        File(dir, "sub/deep").mkdirs()
        dir.aged("sub/deep/c.bin", 3000, ageMs = 0)
        assertEquals(6000L, CacheCleaner.fileLengths(dir))
        assertEquals(0L, CacheCleaner.bytesToMb(6000L))
    }

    @Test
    fun `指向文件的软链不计入`() {
        val dir = tmpDir()
        val real = dir.aged("real.bin", 500_000, ageMs = 0)
        val link = File(dir, "link.bin")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(), real.toPath(),
            )
        }.isSuccess
        // Windows 上建软链要特权/开发者模式，建不出来就跳过这条（Linux CI 上会跑）
        if (!created) return
        assertEquals(
            "软链被跟进就会把同一份数据数两遍",
            500_000L,
            CacheCleaner.fileLengths(dir),
        )
    }

    @Test
    fun `指向目录的软链不计入——uv 的 archive-v0 就是这个形状`() {
        // 真机现场：~/.hermes/cache/uv 真实长度和 232.8MB，跟软链后数成 476MB（≈233×2）
        val root = tmpDir()
        val wheels = File(root, "wheels-v6").apply { mkdirs() }
        wheels.aged("wheel.bin", 900_000, ageMs = 0)
        val archive = File(root, "archive-v0").apply { mkdirs() }
        // uv 把 archive-v0/<hash> 做成指向 wheels 内容的软链
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(archive, "deadbeef").toPath(),
                File(wheels, "wheel.bin").toPath(),
            )
        }.isSuccess
        if (!created) return
        assertEquals(900_000L, CacheCleaner.fileLengths(root))
    }

    @Test
    fun `不存在的目录返回 0 而不是抛异常`() {
        assertEquals(0L, CacheCleaner.fileLengths(File(tmpDir(), "nope")))
    }

    // ── 单位换算 ────────────────────────────────────────────────────────────

    @Test
    fun `字节换算取整不四舍五入——宁可少报也不虚报`() {
        assertEquals(0L, CacheCleaner.bytesToMb(1048575))
        assertEquals(1L, CacheCleaner.bytesToMb(1048576))
        // 真机 files/rootfs/tmp 总计 277MB，其中 271MB 是这 32 个 .so（另 6MB 是
        // node-compile-cache 等保留项，不计入）
        assertEquals(271L, CacheCleaner.bytesToMb(4_919_152L * 18 + 13_995_736L * 14))
    }
}

/** 与 [CacheCleaner.staleTempBytes] 同义，只是作用在给定目录上（便于单测）。 */
private fun CacheCleaner.staleTempBytesIn(dir: File, now: Long): Long =
    CacheCleaner.staleTempFilesIn(dir, now).sumOf { it.length() }
