// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档。
package com.example.zhengdao.rust

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * 解压流水线真机对拍（v2.0 R2）：同一 311MB 真实 rootfs 归档，
 * Rust 路径（ExtractNative）解压 → 关键文件与 Java 安装版逐一对拍。
 *
 * 归档缺失时静默跳过（基线依赖设备上有真包，同 ExtractBaselineTest 语义）。
 */
@RunWith(AndroidJUnit4::class)
class ExtractNativeInstrumentedTest {

    private fun log(s: String) = Log.i("BENCH", s)

    private fun findArchive(): File? =
        listOf(
            "/sdcard/Download/zhengdao/cache/debian-13.7-base-arm64.tar.zst",
            "/sdcard/Download/证道/debian-13.7-base-arm64.tar.zst",
        ).map { File(it) }.firstOrNull { it.isFile && it.length() > 100_000_000L }

    /** Java 安装版 rootfs 里的关键文件（已由先前 Java 路径装好，作为对拍基准）。 */
    private fun keyFiles(rootfs: File): List<File> = listOf(
        File(rootfs, "bin/sh"),
        File(rootfs, "usr/bin/env"),
        File(rootfs, "etc/hosts"),
    )

    @Test
    fun rust链路可用() {
        assertTrue(
            "libextract.so 加载失败——检查 jniLibs/arm64-v8a 是否打包",
            ExtractNative.isRustAvailable()
        )
    }

    @Test
    fun 真实归档_Rust树与Java安装树全量对拍() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = findArchive() ?: return
        val outRust = File(ctx.cacheDir, "extract_rust_poc").apply { deleteRecursively(); mkdirs() }
        val shaFile = File(archive.parentFile, archive.name + ".sha256")
        val expectedSha = if (shaFile.isFile) shaFile.readText().trim().split(" ").firstOrNull() else null

        // ── Rust 路径：真跑（数据常驻 native，边界只跨一次）──
        val t0 = System.nanoTime()
        val (entries, bytes, rustSha) = ExtractNative.extract(
            archive.canonicalPath, outRust.canonicalPath, expectedSha
        )
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("BENCH_EXTRACT Rust: ${ms}ms / ${archive.length() / 1048576}MB 归档 / $entries 条目 / ${bytes / 1048576}MB 解出")
        if (expectedSha != null) assertEquals(expectedSha.lowercase(), rustSha)
        assertTrue("条目数异常低: $entries", entries > 10_000)

        // ── Java 路径：RootfsInstaller.install 真装（tmpDir→rootfs，含标记文件）──
        val t1 = System.nanoTime()
        com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, archive) { }
        val msJava = (System.nanoTime() - t1) / 1_000_000
        log("BENCH_EXTRACT Java: ${msJava}ms（含安装收尾）")

        // ── 两树全量比对（NOFOLLOW：bind symlink 指向整个共享存储，跟进去会数出几万用户文件）──
        val opts = arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        fun treeOf(root: File): MutableMap<String, Long> {
            val m = mutableMapOf<String, Long>()
            java.nio.file.Files.walk(root.toPath()).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it, *opts) }.forEach { p ->
                    m[root.toPath().relativize(p).toString().replace(java.io.File.separatorChar, '/')] =
                        java.nio.file.Files.size(p)
                }
            }
            return m
        }
        val rustTree = treeOf(outRust)
        val javaTree = treeOf(File(ctx.filesDir, "rootfs")).apply { remove(".zhengdao-rootfs-ok") }

        log("BENCH_EXTRACT 对拍规模: Rust=${rustTree.size} Java=${javaTree.size}")
        assertEquals("两树普通文件数不一致", javaTree.size, rustTree.size)
        var mismatches = 0
        for ((name, size) in javaTree) {
            val got = rustTree[name]
            if (got == null || got != size) {
                if (mismatches < 8) log("BENCH_EXTRACT 不一致: $name Java=$size Rust=$got")
                mismatches++
            }
        }
        assertEquals("存在路径/大小不一致的文件", 0, mismatches)
        log("BENCH_EXTRACT 对拍通过: ${javaTree.size} 个普通文件（路径+大小）全量一致")

        outRust.deleteRecursively()
    }

    @Test
    fun sha不匹配_拒绝并保留主流程() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = findArchive() ?: return
        val out = File(ctx.cacheDir, "extract_rust_bad").apply { deleteRecursively(); mkdirs() }
        val err = runCatching {
            ExtractNative.extract(archive.canonicalPath, out.canonicalPath, "0".repeat(64))
        }.exceptionOrNull()
        Log.i("BENCH", "SHA 拒绝用例: err=${err?.javaClass?.name}: ${err?.message?.take(200) ?: "（无异常——调用成功）"}")
        assertTrue("SHA 不匹配必须报错", err is IllegalStateException)
        out.deleteRecursively()
    }
}
