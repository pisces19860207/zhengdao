// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：commons-compress 官方文档、Android instrumented test 官方文档。
package com.example.zhengdao.rootfs

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File

/**
 * 解压基线（候选 1 步骤 0）：用与 RootfsInstaller 完全相同的 commons-compress
 * 路径解真实 rootfs 归档，产出 Rust 版的对照基线。
 *
 * 找不到归档时自动跳过（test assumption 语义：基线依赖设备上有真包）。
 * Log.i 而非 println：logcat 是设备级缓冲，测试包卸载后仍可读取。
 */
@RunWith(AndroidJUnit4::class)
class ExtractBaselineTest {

    private fun log(s: String) = Log.i("BENCH", s)

    private fun findArchive(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val candidates = listOf(
            // 2026-10-08 起 App 的公共缓存挪到 Download/证道/rootfs（与 opencode 包同处），
            // 原来 Download/zhengdao/cache 里的包由 migration 搬走 —— 三者都列上，谁在就用谁。
            File("/sdcard/Download/证道/rootfs/debian-13.7-base-arm64.tar.zst"),
            File("/sdcard/Download/zhengdao/cache/debian-13.7-base-arm64.tar.zst"),
            File("/sdcard/Download/证道/debian-13.7-base-arm64.tar.zst"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 100_000_000L }
            ?: ctx.filesDir.parentFile?.let { parent ->
                // 兜底：内部存储 cache 里的缓存包（RootfsCache.dir 私有兜底位）
                File(parent, "cache/rootfs-cache").takeIf { it.isDirectory }
                    ?.listFiles()?.firstOrNull { it.length() > 100_000_000L }
            }
    }

    /** 用 RootfsInstaller 同款流式路径解到临时目录，只统计字节与条目数（不落盘业务文件，
     *  避免污染设备——解压耗时主体在解压+读流，写 /dev/null 等价于最小化 IO 干扰）。 */
    @Test
    fun 基线_commonsCompress解压_真实包() {
        val archive = findArchive() ?: return  // 无真包：静默跳过（下次有包再采）
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sinkDir = File(ctx.cacheDir, "extract_baseline").apply {
            deleteRecursively(); mkdirs()
        }

        var entries = 0
        var bytesOut = 0L
        val mem0 = runtimeUsedMem()

        val t0 = System.nanoTime()
        BufferedInputStream(archive.inputStream(), 512 * 1024).use { buffered ->
            ZstdCompressorInputStream(buffered).use { decompressed ->
                TarArchiveInputStream(decompressed, "UTF-8").use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        entries++
                        var read = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            bytesOut += n
                            read += n
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val memDelta = runtimeUsedMem() - mem0

        log("BENCH_BASELINE commons-compress: ${ms}ms / $archive.name(${archive.length() / 1048576}MB) " +
            "entries=$entries out=${bytesOut / 1048576}MB memΔ≈${memDelta / 1048576}MB")
        sinkDir.deleteRecursively()
    }

    private fun runtimeUsedMem(): Long {
        val am = InstrumentationRegistry.getInstrumentation().context
            .getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem - mi.availMem
    }
}
