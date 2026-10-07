// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Oracle JNI 规范、sha2/jni crate 官方文档。
package com.example.zhengdao.rust

/**
 * Rust Core PoC（v2.0 前置）：验证「Rust 交叉编译 .so → JNI 调用」全链路。
 *
 * Rust 实现：rust/sha256poc（sha2 crate + jni 薄层，构建方式见 rust/README.md）。
 *
 * 设计约束：**PoC 不影响主流程**——.so 加载失败时自动回退平台 MessageDigest，
 * 调用方无感知；native 返回 null 同样走回退（Rust 侧约定：错误不跨 FFI panic）。
 */
object Sha256Native {

    /** 一次类初始化探测；加载失败即永久标记（不反复尝试拖慢调用）。 */
    private val rustAvailable: Boolean = runCatching {
        System.loadLibrary("sha256poc")
    }.isSuccess

    /** Rust 链路是否可用（供测试与体检展示）。 */
    fun isRustAvailable(): Boolean = rustAvailable

    fun sha256Hex(data: ByteArray): String {
        if (!rustAvailable) return platformFallback(data)
        return nativeSha256Hex(data) ?: platformFallback(data)
    }

    private external fun nativeSha256Hex(data: ByteArray): String?

    private fun platformFallback(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
