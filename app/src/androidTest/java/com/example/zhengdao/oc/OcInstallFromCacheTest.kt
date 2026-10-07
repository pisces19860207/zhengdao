// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档。
package com.example.zhengdao.oc

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 缓存安装真机用例（2026-10-07「opencode 启动失败」事故的直接复现与验收）：
 *
 * 事故链：解压写到一半被中断 → 截断文件 250MB（真值 289,207,968）→
 * `installed()` 只查 >100MB 误判"已安装" → chmod 在写完后才执行、从未轮到
 * → mode 600 → serve exec 报 error=13 EACCES。
 *
 * 本用例：缓存包已在位（PC 端 SHA 校验后推送）→ 走 App 的 downloadAndInstall
 * （缓存命中 → PINNED_SHA 校验 → 释放 → 补执行位）→ 断言最终二进制与真包一致。
 */
@RunWith(AndroidJUnit4::class)
class OcInstallFromCacheTest {

    @Test
    fun 缓存安装_完整性尺寸与可执行位() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = File(OcManager.cacheDir(ctx), "opencode-${OcManager.VERSION}-1-aarch64.pkg.tar.xz")
        assumeTrue("缓存包缺失（需先推送真包），跳过", pkg.isFile)

        val result = OcManager.downloadAndInstall(ctx) { msg ->
            Log.i("BENCH", "OCINST: $msg")
        }
        Log.i("BENCH", "OCINST 结果: ok=${result.ok} msg=${result.message}")
        assertTrue("安装失败: ${result.message}", result.ok)

        // 真值从包里现读（版本升级免维护）：tar 记录的 bin/opencode 尺寸
        val expected = expectedBinarySize(pkg)
        val bin = File(ctx.filesDir, "oc/usr/bin/opencode")
        assertEquals(
            "二进制尺寸与真包不符（截断 = 上次事故形态）",
            expected, bin.length()
        )
        assertTrue("缺少执行位（error=13 的直接来源）", bin.canExecute())
    }

    /** 从 xz+tar 包中读出 bin/opencode 的归档记录尺寸。 */
    private fun expectedBinarySize(pkg: File): Long {
        org.apache.commons.compress.compressors.xz.XZCompressorInputStream(pkg.inputStream()).use { xz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                var e = tar.nextTarEntry
                while (e != null) {
                    if (e.name.endsWith("bin/opencode") && e.isFile) return e.size
                    e = tar.nextTarEntry
                }
            }
        }
        error("包内找不到 bin/opencode")
    }
}
