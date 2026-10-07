// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：FIPS 180-4 测试向量、Android instrumented test 官方文档。
package com.example.zhengdao.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 真机对拍：Rust JNI 链路 vs 平台 MessageDigest（sha256 模块）。
 *
 * 为什么放 androidTest 而非本地单测：libzhengdao_core.so 是 Android arm64 动态库，
 * PC 上的 JVM 加载不了——JNI 链路只能在真机/模拟器上验证。
 *
 * v2.0 R1 收编后桥接类是 [CoreNative]（原 Sha256Native 已并入）。
 */
@RunWith(AndroidJUnit4::class)
class CoreNativeSha256InstrumentedTest {

    private fun platformHex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun rust链路可用() {
        assertTrue(
            "libzhengdao_core.so 加载失败——检查 jniLibs/arm64-v8a 是否打包，以及 16KB 页对齐",
            CoreNative.isRustAvailable()
        )
    }

    @Test
    fun nist_空串对拍() {
        assertEquals(platformHex(ByteArray(0)), CoreNative.sha256Hex(ByteArray(0)))
    }

    @Test
    fun nist_abc对拍() {
        assertEquals(platformHex("abc".toByteArray()), CoreNative.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun 随机数据对拍_16组_8B到1MB() {
        val rng = Random(42)
        var size = 8L
        repeat(16) {
            val data = ByteArray(size.toInt()).also { rng.nextBytes(it) }
            assertEquals("size=$size 对拍失败", platformHex(data), CoreNative.sha256Hex(data))
            size = (size * 2).coerceAtMost(1 shl 20)
        }
    }

    @Test
    fun 计时对比_10MB_各10轮() {
        val data = ByteArray(10 * 1024 * 1024).also { Random(7).nextBytes(it) }

        fun bench(label: String, block: (ByteArray) -> String): Long {
            block(data) // 预热
            val t0 = System.nanoTime()
            repeat(10) { block(data) }
            val ms = (System.nanoTime() - t0) / 1_000_000 / 10
            // Log.i 而非 println：logcat 是设备级缓冲，gradle 跑完卸载测试包后仍可读取
            android.util.Log.i("BENCH", "$label: ${ms}ms / 10MB")
            return ms
        }

        val platform = bench("平台MessageDigest") { platformHex(it) }
        val rust = bench("Rust/JNI") { CoreNative.sha256Hex(it) }
        android.util.Log.i(
            "BENCH",
            "结论: Rust/JNI ${if (rust <= platform) "不慢于" else "慢于"}平台 ${kotlin.math.abs(rust - platform)}ms"
        )
        // 不对性能做断言（ARMv8 硬件 SHA 两边都吃，差距预期很小）——数据进验收记录
    }
}
