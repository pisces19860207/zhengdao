// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 目录占用统计单测（[SystemInfoProvider.dirSizeMb]）。
 *
 * 背景（2026-10-09 Rust 化）：`dirSizeMb` 现在是 **Rust 优先 + Java 回退** 两条路径
 * （见 `docs/证道-Rust化余地审计-2026-10-09.md` 候选 A）。
 *
 * ⚠️ JVM 单测里 `CoreNative.dirSizeBytes` 必然返回 `null`（Android 的 `System.loadLibrary`
 * 在纯 JVM 上不成立 ⇒ `rustAvailable=false`），所以这里测的是**回退那半条**：
 * - Java 路径的统计正确性（KB/MB 取整、递归求和）；
 * - **软链不跟随**（`rootfs` 里有指向整块共享存储的软链，跟随会把用户照片视频全算进来
 *   —— 这是真机上实测过的坑，两条路径都必须跳过）；
 * - 不存在 / 非目录的入口返回 0（而不是报错）。
 *
 * Rust 那半条的对拍在 `rust/core/src/dirsize.rs` 的 `cargo test` 里（PC 上直接跑）。
 * 两条路径的语义一致性由两边的测试**同源约束**（同一份规则：跳过软链、只算普通文件、
 * 无权限跳过、入口非目录判 0）。
 */
class DirSizeMbTest {

    private fun scratch(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "zd-dirsize-t-${System.nanoTime()}-$tag")
            .apply { mkdirs() }

    @Test
    fun `扁平目录按MB取整`() {
        val d = scratch("flat")
        // 恰好 2 MiB
        File(d, "a.bin").writeBytes(ByteArray(2 * 1024 * 1024))
        assertEquals(2L, SystemInfoProvider.dirSizeMb(d))
        d.deleteRecursively()
    }

    @Test
    fun `递归统计子目录`() {
        val d = scratch("nested")
        File(d, "top.bin").writeBytes(ByteArray(1024 * 1024))
        val sub = File(d, "sub/deeper").apply { mkdirs() }
        File(sub, "deep.bin").writeBytes(ByteArray(1024 * 1024))
        // 2 MiB 合计
        assertEquals(2L, SystemInfoProvider.dirSizeMb(d))
        d.deleteRecursively()
    }

    @Test
    fun `不足1MB向下取整为0`() {
        val d = scratch("small")
        File(d, "tiny.bin").writeBytes(ByteArray(1000))
        assertEquals(0L, SystemInfoProvider.dirSizeMb(d))
        d.deleteRecursively()
    }

    @Test
    fun `不存在的目录返回0`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "zd-dirsize-必然不存在-404")
        assertEquals(0L, SystemInfoProvider.dirSizeMb(missing))
    }

    @Test
    fun `普通文件入口返回文件大小_不是0`() {
        // ⚠️ 反直觉但这是 Java `walkFileTree(文件)` 的真实行为：它会把该文件本身
        // 当作一个 entry 计入大小（2026-10-09 实测 3 MiB 文件 → 3）。
        // Rust 侧 `dirsize.rs` 的 `普通文件入口返回文件大小_与Java版一致` 是**成对**的用例，
        // 两条路径必须给同一个数字（回退对拍的前提）。
        val d = scratch("entryfile")
        val f = File(d, "x.bin").apply { writeBytes(ByteArray(3 * 1024 * 1024)) }
        assertEquals(3L, SystemInfoProvider.dirSizeMb(f))
        d.deleteRecursively()
    }

    /**
     * 软链**不跟随**：指向别处的软链不计入占用。
     * 这是与 Rust 侧 `dirsize.rs` 的 `软链不跟随` 用例**成对**的回归锁。
     *
     * JVM 上造软链需要 `Files.createSymbolicLink`；无权限的环境（非管理员 Windows）
     * 建不出软链时跳过断言——不让平台限制把这条测试变成假红。
     */
    @Test
    fun `软链不跟随`() {
        val outside = scratch("outside")
        File(outside, "big.bin").writeBytes(ByteArray(5 * 1024 * 1024))

        val d = scratch("withlink")
        File(d, "keep.bin").writeBytes(ByteArray(1024 * 1024))
        val linkOk = runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(d, "link_to_outside").toPath(),
                outside.toPath(),
            )
        }.isSuccess

        if (linkOk) {
            // 只应算到 keep.bin 的 1 MiB，不把 outside 的 5 MiB 带进来
            assertEquals(1L, SystemInfoProvider.dirSizeMb(d))
        }

        d.deleteRecursively()
        outside.deleteRecursively()
    }
}
