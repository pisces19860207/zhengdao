// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Oracle JNI 规范、zstd/tar crate 官方文档。
package com.example.zhengdao.rust

import android.util.Log

/**
 * 解压流水线 JNI 桥（v2.0 R2，架构文档 §2 嵌入形态）：
 * 归档路径 + 目标目录 + SHA256 进 → native 完成全部工作（数据常驻 native，
 * 边界只跨一次）→ JSON 报告出 + 限频进度回调。
 *
 * Rust 侧：rust/extract（zstd/tar crate + jni 薄层）。
 *
 * 回退纪律（规范 #2）：.so 加载失败或 native 返回 null → 抛出带说明的
 * IllegalStateException，由调用方（RootfsInstaller 集成点）回退 commons-compress
 * 纯 Java 路径——PoC 不影响主流程。
 */
object ExtractNative {

    private val rustAvailable: Boolean = runCatching {
        System.loadLibrary("extract")
    }.isSuccess

    fun isRustAvailable(): Boolean = rustAvailable

    /** 进度回调（native 限频触发，约每 200 条目一次）。 */
    @JvmStatic
    fun onProgress(entries: String, name: String) {
        Log.i("ExtractNative", "解压进度: $entries $name")
    }

    /**
     * 解压归档到目标目录。
     * @return ExtractReport 三元组（条目数/字节/归档 SHA256）
     * @throws IllegalStateException native 不可用或流水线失败（含 SHA 不匹配）
     */
    fun extract(
        archivePath: String,
        targetDir: String,
        expectedSha256: String?,
    ): Triple<Long, Long, String> {
        check(rustAvailable) { "Rust 解压链路不可用（libextract.so 加载失败）" }
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
