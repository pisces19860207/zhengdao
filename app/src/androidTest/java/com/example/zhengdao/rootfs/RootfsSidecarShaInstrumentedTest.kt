// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zhengdao.rust.CoreNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **真机复现现场**的回归测试（E-053）：设备上那个"文件是对的、边车是过期的"组合，
 * 在修好的决策下必须落到索引的 sha256 上，并且那个值必须与文件字节真的一致。
 *
 * 为什么值得单独一条真机用例：单测（`RootfsSidecarShaTest`）喂的是**我自己编的**数字，
 * 而这条喂的是**用户手机上真实躺着的那两个文件** —— 修复前它会红（旧逻辑取边车
 * `d12cd1d3…` ⇒ 与实际 `d80639e7…` 不符 ⇒ 终端页弹「安装失败：SHA256 校验失败」），
 * 修复后它绿（取索引值 ⇒ 一致）。这条路径不联网就没法完整跑通（要抓索引），
 * 所以网络/包/边车任一缺失就 `assumeTrue` 跳过，绝不误报红。
 */
@RunWith(AndroidJUnit4::class)
class RootfsSidecarShaInstrumentedTest {

    private val pkgName = "debian-13.7-base-arm64.tar.zst"
    private val staleSha = "d12cd1d37e0c4767e6730fd709eb796f5fe996b27e94e40b480bdb96afec9e27"

    private fun sharedArchiveDir() = File(RootfsCache.SHARED_DIR_PATH, "rootfs")

    /**
     * 设备的真实组合：`Download/证道/rootfs/debian-13.7-base-arm64.tar.zst`（真包）
     * + 同名 `.sha256`（**过期副本**）。
     */
    @Test
    fun 过期边车在场时_决策必须落在索引上且与文件字节一致() {
        assumeTrue("Rust 核心不可用（旧 .so），跳过", CoreNative.isRustAvailable())
        val dir = sharedArchiveDir()
        val local = File(dir, pkgName)
        assumeTrue("设备上没有那个整包，跳过", local.isFile && local.length() > 100_000_000L)
        val sidecar = File(dir, "$pkgName.sha256")
        assumeTrue("设备上没有边车文件（已不是复现现场），跳过", sidecar.isFile)
        // 这个目录在 /sdcard 下，包没拿到"完整存储访问"时是 EACCES（不是文件不存在）——
        // 那种情况属于环境没准备好，跳过并说明怎么补（adb shell appops set … 见注释），
        // 绝不把"读不到"伪装成"验过了"。
        val sidecarText = runCatching { sidecar.readText().trim() }.getOrElse { e ->
            assumeTrue("读不到边车（存储访问未授予？${e.javaClass.simpleName}: ${e.message}），跳过", false)
            return
        }
        val idx = RootfsIndexFetcher.fetch()
        assumeTrue("拿不到（签名验过的）索引，跳过", idx != null)

        val c = RootfsCache.pickExpectedSha(
            localName = local.name,
            localSize = local.length(),
            indexUrl = idx!!.url,
            indexSize = idx.size,
            indexSha = idx.sha256,
            sidecar = sidecarText,
            onlineSha = null,
        )
        assertEquals("决策必须落在索引的 sha256 上（这正是假失败的修法）", idx.sha256, c.sha)
        assertEquals("索引", c.source)
        if (!sidecarText.equals(idx.sha256, ignoreCase = true)) {
            assertTrue("边车与索引冲突时必须标记为过期，装完要自愈重写", c.staleSidecar)
            assertNotEquals("自愈要写的值不能还是那个过期值", staleSha, c.sha)
        }
        // 决定性的一步：索引给的值必须与这个文件的**真实字节**一致 —— 否则用户照样装不上去
        // （走 Rust 核心算，192 MB 约 1 秒）。
        assertEquals("索引 sha256 必须与设备上这个包的字节一致", idx.sha256, RootfsDownloader.sha256Of(local))
    }

    /** 自愈写入：把过期边车按"索引值 + 换行"重写后，下一次决策不再报过期。 */
    @Test
    fun 自愈重写边车之后_不再标记过期() {
        assumeTrue("Rust 核心不可用（旧 .so），跳过", CoreNative.isRustAvailable())
        val idx = RootfsIndexFetcher.fetch()
        assumeTrue("拿不到（签名验过的）索引，跳过", idx != null)
        val tmp = File.createTempFile("zd-sidecar", ".sha256")
        try {
            tmp.writeText("$staleSha\n")
            assertEquals(staleSha, tmp.readText().trim())
            val before = RootfsCache.pickExpectedSha(
                pkgName, 1L, idx!!.url, 1L, idx.sha256, tmp.readText().trim(), null,
            )
            assertTrue("重写前应被标成过期", before.staleSidecar)
            // 与 TerminalActivity 校验通过后的自愈写法保持一致
            tmp.writeText("${before.sha}\n")
            val after = RootfsCache.pickExpectedSha(
                pkgName, 1L, idx.url, 1L, idx.sha256, tmp.readText().trim(), null,
            )
            assertEquals("重写后取到的就是索引值", idx.sha256, after.sha)
            assertTrue("重写后不再标记过期", !after.staleSidecar)
        } finally {
            tmp.delete()
        }
    }
}
