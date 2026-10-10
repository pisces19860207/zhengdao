// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档、java.nio.file 的 walkFileTree 语义说明。
package com.example.zhengdao.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.ui.SystemInfoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * 真机对拍：Rust 的 `dir_size_bytes`（JNI，`CoreNative.dirSizeBytes`）
 * vs Java 回退实现（`SystemInfoProvider.javaDirSizeBytes`）。
 *
 * ## 为什么必须放在 androidTest
 *
 * `libzhengdao_core.so` 是 Android arm64 动态库，PC 上的 JVM 加载不了
 * ——所以 JVM 单测（`DirSizeMbTest`）**只能覆盖 Java 那一条路**，
 * 「两条路径给同一个数字」这条契约在 PC 上根本没有测试能验。
 * 这里是它唯一的自动化落脚点（E-079 / 《证道-Rust化余地审计-2026-10-09》候选 A 的验收缺口）。
 *
 * ## 契约（两条路必须逐条一致，见 `rust/core/src/dirsize.rs` 的模块文档）
 *
 * 1. **不跟随符号链接**、也不统计软链自身 —— `rootfs` 里有指向整个共享存储的软链，
 *    跟随会把用户的照片视频全算进来（真机实测过的坑）；
 * 2. 只累加普通文件：目录本身不计、设备/FIFO/套接字不计；
 * 3. 无权限条目**静默跳过**、继续走完整棵树；
 * 4. 入口是普通文件 ⇒ 返回该文件大小（不是 0）——Java `walkFileTree(文件)` 会
 *    把它自己当 entry 计入，为保持对拍 Rust 侧照做；
 * 5. 入口不存在 / 无权限 / 是软链根 ⇒ **0**（两侧都是 0，不是错误）。
 *
 * ⚠️ 失败哨兵不在这里测：JNI 只在「路径字符串跨 FFI 转换失败」时返回 `-1`
 * （Kotlin 侧映射为 `null` ⇒ 回退 Java），这条只能用 JVM 层的桩覆盖；
 * 本测试保证的是**成功路径上的数字必须一样**。
 *
 * 跑法（**不要用 `connectedDebugAndroidTest`**：它会先卸载 App、清空整个运行环境，
 * 见 E-072 铁律）：
 * ```
 * gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/zhengdao-2.0.8-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/zhengdao-2.0.8-debug-androidTest.apk
 * adb shell am instrument -w -e class com.example.zhengdao.rust.DirSizeParityTest \
 *     com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DirSizeParityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Rust 路（`null` = 这条路走不通，生产代码会回退 Java）。 */
    private fun rustBytes(dir: File): Long? = CoreNative.dirSizeBytes(dir)

    /** Java 路（对拍基准）。 */
    private fun javaBytes(dir: File): Long = SystemInfoProvider.javaDirSizeBytes(dir)

    /** 在 App 私有目录里造一棵临时树（不需要任何存储权限）。 */
    private fun scratch(tag: String): File =
        File(context.cacheDir, "dirsize-parity-$tag").also {
            it.deleteRecursively()
            it.mkdirs()
        }

    /** 建软链；返回是否成功（个别设备/文件系统不支持软链）。 */
    private fun symlink(link: File, target: File): Boolean =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess

    @Test
    fun rust链路可用() {
        assertTrue(
            "libzhengdao_core.so 加载失败——检查 jniLibs/arm64-v8a 是否打包，以及 16KB 页对齐",
            CoreNative.isRustAvailable(),
        )
    }

    @Test
    fun 对拍_合成树_字节级一致() {
        val tree = scratch("flat")
        try {
            File(tree, "plain.bin").writeBytes(ByteArray(1000))
            File(tree, "sub").mkdirs()
            File(tree, "sub/nested.bin").writeBytes(ByteArray(2000))
            File(tree, "sub/deeper").mkdirs()
            File(tree, "sub/deeper/x.bin").writeBytes(ByteArray(500))
            File(tree, "empty").mkdirs() // 空目录自身不计

            val expect = 3500L
            assertEquals("Java 参考实现与预期不符", expect, javaBytes(tree))
            assertEquals("Rust 与 Java 必须给同一个字节数", expect, rustBytes(tree))
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test
    fun 对拍_软链不跟随() {
        val outside = scratch("outside")
        val tree = scratch("withlinks")
        try {
            val target = File(outside, "big.bin").also { it.writeBytes(ByteArray(9999)) }
            File(tree, "keep.bin").writeBytes(ByteArray(7))
            val linked = symlink(File(tree, "link_to_dir"), outside) and
                symlink(File(tree, "link_to_file"), target)
            assumeTrue("此设备不支持创建符号链接，跳过软链用例", linked)

            // 只应统计 keep.bin：指向目录的软链不递归、指向文件的软链不计自身。
            assertEquals("Java 路把软链算进去了", 7L, javaBytes(tree))
            assertEquals("Rust 路把软链算进去了", 7L, rustBytes(tree))

            // 入口本身就是软链（真实场景：有人直接把指向共享存储的软链传进来）⇒ 两侧都是 0。
            val linkRoot = File(tree, "link_to_dir")
            assertEquals("Java 路跟随了软链根", 0L, javaBytes(linkRoot))
            assertEquals("Rust 路跟随了软链根", 0L, rustBytes(linkRoot))
        } finally {
            tree.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun 对拍_入口是普通文件() {
        val tree = scratch("entryisafile")
        try {
            val f = File(tree, "x.bin").also { it.writeBytes(ByteArray(42)) }
            assertTrue("rust 路径不可用（返回 null）", rustBytes(f) != null)
            assertEquals("入口是文件时两侧必须都给文件大小", 42L, javaBytes(f))
            assertEquals("入口是文件时两侧必须都给文件大小", 42L, rustBytes(f))
        } finally {
            tree.deleteRecursively()
        }
    }

    @Test
    fun 对拍_空目录与不存在的路径都是零() {
        val tree = scratch("empty")
        try {
            val missing = File(tree, "完全没有这个目录")
            assertEquals(0L, javaBytes(tree))
            assertEquals(0L, rustBytes(tree)) // 0 是"真为空"的合法结果，不是错误码
            assertEquals("不存在的路径：两侧都必须给 0", 0L, javaBytes(missing))
            assertEquals("不存在的路径：两侧都必须给 0", 0L, rustBytes(missing))
        } finally {
            tree.deleteRecursively()
        }
    }

    /** 真机真实环境上的对拍（最有价值的一条：rootfs 有 1.8 万条目、约 768 MB）。 */
    @Test
    fun 对拍_真实rootfs_字节级一致并计时() {
        val rootfs = File(context.filesDir, "rootfs")
        assumeTrue("设备上没有已装环境（rootfs 不存在），跳过真实树对拍", rootfs.isDirectory)

        val t0 = System.nanoTime()
        val rust = rustBytes(rootfs)
        val rustMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        val java = javaBytes(rootfs)
        val javaMs = (System.nanoTime() - t1) / 1_000_000

        // Log.i 而非 println：logcat 是设备级缓冲，跑完测试卸载测试包后仍可读取
        android.util.Log.i("DIRSIZE", "rootfs 对拍：Rust=$rust(${rustMs}ms) Java=$java(${javaMs}ms)")

        assertTrue("rust 路径不可用（返回 null）", rust != null)
        assertTrue("rootfs 不该是 0 字节：Rust=$rust", rust!! > 0L)
        assertEquals("rootfs 上两条路径必须给同一个字节数", java, rust)
        // 生产入口（MB 换算）也必须落在同一个数上
        assertEquals("dirSizeMb 与对拍基准不一致", java / 1048576, SystemInfoProvider.dirSizeMb(rootfs))
    }
}
