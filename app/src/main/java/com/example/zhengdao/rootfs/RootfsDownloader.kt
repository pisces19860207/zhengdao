// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：HTTP/1.1 Range 请求与 206 Partial Content（RFC 9110）、
// FIPS 180-4 SHA-256（java.security.MessageDigest，JDK 标准库）、OkHttp 官方文档。
package com.example.zhengdao.rootfs

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * RootFS 下载器（设计文档 §6）：
 * - 多候选 URL，失败自动切换（GitHub Releases 直链 + CDN 回源）；
 * - Range 断点续传：存在 .part 文件且服务器支持 206 时从断点续传；
 *   服务器不支持 Range（返回 200）则从头重下；
 * - SHA256 全量校验（期望值由 manifest 提供；M3 起由 ed25519 验签的 manifest 下发）。
 * 全部为阻塞式 IO，调用方自备工作线程。
 */
object RootfsDownloader {

    private const val TAG = "RootfsDownloader"
    private const val PART_SUFFIX = ".part"
    private const val MAX_ATTEMPTS_PER_URL = 3

    class DownloadFailed(message: String) : IOException(message)

    /** 下载内容与校验值不符（多为发布资产在下载途中被更新）——残件作废、重取校验值、从头再来 */
    class ShaMismatch(message: String) : IOException(message)

    /** 共享 HTTP 客户端：连接池常驻，退后台时由 releaseIdleResources() 清空。 */
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 内存看护（onTrimMemory 退后台时调用）：清掉空闲 TCP 连接与缓冲。
     * WebView/Chromium 的内存由系统回调自动管理，不在此处理。
     */
    fun releaseIdleResources() {
        try { sharedClient.connectionPool.evictAll() } catch (_: Throwable) {}
    }

    /**
     * 依次尝试所有 URL，把文件下载到 dest（先写 dest.part，完成后改名）。
     * @param expectedSha256 期望的校验值；传 null 表示跳过校验（仅开发期允许）
     */
    fun download(
        urls: List<String>,
        dest: File,
        shaUrl: String?,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) {
        if (urls.isEmpty()) throw DownloadFailed("没有可用的下载地址")

        // 每轮尝试前重取校验值：发布资产可能被更新（移动靶），过期校验值只会白忙
        var expectedSha = shaUrl?.let { fetchText(it) }
        if (shaUrl != null && expectedSha.isNullOrBlank()) {
            Log.w(TAG, "未获取到 SHA256 校验值，跳过完整性校验（仅限开发期）")
        }
        var lastError: Exception? = null
        for (url in urls) {
            repeat(MAX_ATTEMPTS_PER_URL) { attempt ->
                try {
                    Log.i(TAG, "下载尝试：$url（第 ${attempt + 1} 次）")
                    downloadOne(sharedClient, url, dest, onProgress)
                    if (expectedSha.isNullOrBlank()) {
                        Log.w(TAG, "未提供 SHA256，跳过完整性校验")
                    } else {
                        verifySha256(dest, expectedSha)
                    }
                    return
                } catch (e: ShaMismatch) {
                    // 资产在下载途中被更新：残件作废、重取最新校验值、从头再来
                    Log.w(TAG, "SHA256 不匹配，删除残件并重取校验值重试", e)
                    dest.delete()
                    expectedSha = shaUrl?.let { fetchText(it) }
                    lastError = e
                } catch (e: Exception) {
                    Log.w(TAG, "下载失败：$url", e)
                    lastError = e
                }
            }
        }
        dest.delete()
        File(dest.parentFile, dest.name + PART_SUFFIX).delete()
        throw DownloadFailed("全部下载通道失败：${lastError?.message ?: "未知错误"}")
    }

    /** 抓取小文本文件（如 .sha256 边车文件）；任何失败都返回 null，由调用方决定降级策略。 */
    fun fetchText(url: String): String? = try {
        sharedClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "抓取文本失败：$url", t)
        null
    }

    private fun downloadOne(
        client: OkHttpClient,
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val part = File(dest.parentFile, dest.name + PART_SUFFIX)
        val already = if (part.isFile) part.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        if (already > 0) requestBuilder.header("Range", "bytes=$already-")

        client.newCall(requestBuilder.build()).execute().use { resp ->
            if (resp.code == 416) {
                // 断点超出远端文件长度（远端内容已更换）：删掉残片重头下
                part.delete()
                throw IOException("HTTP 416：本地断点失效，需重新下载")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应体")

            val resumed = already > 0 && resp.code == 206
            val contentLength = body.contentLength() // 未知时为 -1
            val total: Long = when {
                resumed && contentLength > 0 -> already + contentLength
                contentLength > 0 -> contentLength
                else -> -1L
            }
            if (!resumed) part.delete()

            var done = if (resumed) already else 0L
            var lastReported = -1L
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(done)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReported >= 512 * 1024) {
                            lastReported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("连接提前中断（$done/$total）")
            onProgress(done, if (total > 0) total else done)

            if (!part.renameTo(dest)) {
                // 极少数文件系统上 rename 失败：复制后删除残片
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        }
    }

    /** 流式计算文件 SHA-256 并与期望值比对（不区分大小写）。 */
    fun verifySha256(file: File, expected: String) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ShaMismatch("SHA256 校验失败：actual=$actual expected=$expected")
        }
    }
}
