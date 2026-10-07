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
import java.io.File

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
    fun 真实归档_解压与Java安装版对拍() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = findArchive() ?: return
        val installed = File(ctx.filesDir, "rootfs")
        if (!installed.isDirectory) return   // 尚无 Java 安装版可对拍

        val out = File(ctx.cacheDir, "extract_rust_poc").apply { deleteRecursively(); mkdirs() }
        val shaFile = File("/sdcard/Download/zhengdao/cache/debian-13.7-base-arm64.tar.zst.sha256")
        val expectedSha = if (shaFile.isFile) shaFile.readText().trim().split(" ").firstOrNull() else null

        val t0 = System.nanoTime()
        val (entries, bytes, rustSha) = ExtractNative.extract(
            archive.canonicalPath, out.canonicalPath, expectedSha
        )
        val ms = (System.nanoTime() - t0) / 1_000_000

        log("BENCH_EXTRACT Rust: ${ms}ms / ${archive.length() / 1048576}MB 归档 / $entries 条目 / ${bytes / 1048576}MB 解出")
        if (expectedSha != null) assertEquals(expectedSha.lowercase(), rustSha)

        // 关键文件对拍：Rust 解出版 vs Java 安装版，逐一存在且大小一致
        var compared = 0
        keyFiles(installed).forEach { ref ->
            val mine = File(out, ref.relativeTo(installed).path)
            assertTrue("缺失: ${mine.path}", mine.isFile)
            assertEquals(
                "大小不一致: ${ref.path}",
                ref.length(), mine.length()
            )
            compared++
        }
        log("BENCH_EXTRACT 对拍通过: $compared 个关键文件大小一致")

        out.deleteRecursively()
    }

    @Test
    fun sha不匹配_拒绝并保留主流程() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = findArchive() ?: return
        val out = File(ctx.cacheDir, "extract_rust_bad").apply { deleteRecursively(); mkdirs() }
        val err = runCatching {
            ExtractNative.extract(archive.canonicalPath, out.canonicalPath, "0".repeat(64))
        }.exceptionOrNull()
        assertTrue("SHA 不匹配必须报错", err is IllegalStateException)
        out.deleteRecursively()
    }
}
