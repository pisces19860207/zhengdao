// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Oracle JNI 规范、sha2/jni/zstd/tar crate 官方文档。
package com.example.zhengdao.rust

import android.util.Log

/**
 * Rust Core 统一 JNI 桥（v2.0 R1 收编）：
 *
 * 原 `Sha256Native`（libsha256poc.so）与 `ExtractNative`（libextract.so）合并为单一
 * `libzhengdao_core.so` —— **演化，不并存**（架构文档 §2.3）。动机是收益模型铁律：
 * JNI 边界只该跨一次、`.so` 只该 load 一次；两个模块共享同一份 sha2/zstd 静态链接。
 *
 * Rust 侧：rust/core（`sha256` 纯逻辑 + `extract` 流水线 + 共用一份 jni_bridge）。
 *
 * 回退纪律（规范 #2）：本类只做桥接与可用性判定，任何失败都由调用方回退平台实现——
 * - sha256：native 不可用或返回 null → `java.security.MessageDigest`
 * - extract：native 不可用或流水线失败 → 抛 IllegalStateException，由 `RootfsInstaller`
 *   回退 commons-compress 纯 Java 路径
 */
object CoreNative {

    private const val TAG = "CoreNative"

    /** 一次类初始化探测；加载失败即永久标记（不反复尝试拖慢调用）。 */
    private val rustAvailable: Boolean = runCatching {
        System.loadLibrary("zhengdao_core")
    }.isSuccess

    /** Rust 链路是否可用（供测试与体检展示）。 */
    fun isRustAvailable(): Boolean = rustAvailable

    // ────────────────────────── sha256 模块 ──────────────────────────

    fun sha256Hex(data: ByteArray): String {
        if (!rustAvailable) return platformSha256(data)
        return nativeSha256Hex(data) ?: platformSha256(data)
    }

    private external fun nativeSha256Hex(data: ByteArray): String?

    private fun platformSha256(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    // ────────────────────────── extract 模块 ──────────────────────────

    /** 进度回调（native 限频触发，约每 200 条目一次）。 */
    @JvmStatic
    fun onProgress(entries: String, name: String) {
        Log.i(TAG, "解压进度: $entries $name")
    }

    /**
     * 解压归档到目标目录（数据常驻 native，边界只跨一次）。
     * @return 三元组（条目数 / 字节数 / 归档 SHA256）
     * @throws IllegalStateException native 不可用或流水线失败（含 SHA 不匹配）
     */
    fun extract(
        archivePath: String,
        targetDir: String,
        expectedSha256: String?,
    ): Triple<Long, Long, String> {
        check(rustAvailable) { "Rust 解压链路不可用（libzhengdao_core.so 加载失败）" }
        val json = nativeExtract(archivePath, targetDir, expectedSha256)
            ?: throw IllegalStateException("Rust 解压流水线无返回（JNI 层异常）")
        // 协议（永远返回 JSON）：成功 {"ok":true,entries,bytes,sha256}；失败 {"ok":false,error}
        if (Regex("\"ok\":(true|false)").find(json)?.groupValues?.get(1) != "true") {
            val err = Regex("\"error\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "未知"
            throw IllegalStateException("Rust 解压失败: $err")
        }
        val entries = Regex("\"entries\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val bytes = Regex("\"bytes\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sha = Regex("\"sha256\":\"([0-9a-f]+)\"").find(json)?.groupValues?.get(1) ?: ""
        return Triple(entries, bytes, sha)
    }

    private external fun nativeExtract(
        archivePath: String,
        targetDir: String,
        expectedSha256: String?,
    ): String?
}
