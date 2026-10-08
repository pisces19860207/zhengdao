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
 * - SHA256 全量校验（期望值来自**已验签**的环境索引 `rootfs-index.json`；
 *   2026-10-08 起该索引本身带 Ed25519 签名，见 RootfsIndexFetcher）。
 *   ⚠️ 本行原写的是"由 ed25519 验签的 manifest 下发"，而当时**环境包这条链一个签名都没有**
 *   （只有 agents.json 有）——那句话把两条链混成了一条，属于误导性描述，已按事实改写。
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
     * 给 github.com 直链补一条 gh-proxy 镜像兜底（v1.2 B3）。
     *
     * 为什么需要：github.com 在部分网络下不可达（实测本机 http=000、gh-proxy.com 200），
     * 而 rootfs（326MB）此前只有这一个地址——新用户第一步就可能卡死。
     * 太极的 OpenCode 包早就有 gh-proxy 兜底，两边此前不对称。
     */
    fun withMirrorFallback(url: String): List<String> =
        if (url.startsWith("https://github.com/")) listOf(url, "https://gh-proxy.com/$url")
        else listOf(url)

    /**
     * 对象存储镜像变换——**方案一已于 2026-10-07 被用户废弃（见 docs/ERRATA.md E-015）**：
     * rootfs 主下载源不迁往对象存储，因此**不要接入默认源列表**。
     *
     * 保留实现仅供试验与单测（`UrlTransformTest`）：从原始 URL 提取文件名，
     * 拼到对象存储桶公开域之后作为首选源，原 URL 兜底。纯函数、无副作用。
     *
     * 现状：真实生效的兜底是 [withMirrorFallback] → gh-proxy.com。
     *
     * @param objectStoreBase 桶公开域（末尾不带 /，如 https://cdn.example.com/zhengdao）
     */
    fun withObjectStorage(url: String, objectStoreBase: String): List<String> {
        val base = objectStoreBase.trim().trimEnd('/')
        if (base.isEmpty()) return listOf(url)
        return listOf("$base/${url.substringAfterLast('/')}", url)
    }

    /**
     * 依次尝试所有 URL，把文件下载到 dest（先写 dest.part，完成后改名）。
     *
     * @param shaUrls 校验值边车的**候选源列表**（github 优先、镜像兜底）。
     *   传空列表 = 调用方明确不校验（OcManager 走这条路：它用 release digest 或定版 SHA 自行校验）。
     * @return **本次实际校验通过的那个 SHA256**（无校验值时 null）。
     *   返回值是给"信任锚"用的（用户 2026-10-08 追加规则）：只有它与索引里的 sha256
     *   一致时，才允许把索引的 env 写进环境标记——见 [RootfsInstaller.envForMarker]。
     *   已有调用点忽略返回值即可，行为不变。
     */
    fun download(
        urls: List<String>,
        dest: File,
        shaUrls: List<String> = emptyList(),
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ): String? {
        if (urls.isEmpty()) throw DownloadFailed("没有可用的下载地址")

        // 每轮尝试前重取校验值：发布资产可能被更新（移动靶），过期校验值只会白忙
        var expectedSha = fetchFirstSha(shaUrls)
        // ⚠️ 拿不到校验值**必须报错，不能"跳过校验"**：镜像可用而主源不通时，
        //    旧逻辑会静默装上未校验的 rootfs（B3 要补的正是这个洞）。
        if (shaUrls.isNotEmpty() && expectedSha.isNullOrBlank()) {
            throw DownloadFailed(
                "无法获取 SHA256 校验值（已尝试 ${shaUrls.size} 个源），拒绝安装未校验的包"
            )
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
                    // 校验通过（或调用方明确不校验）：把"实际校验通过的 SHA256"交回调用方
                    return expectedSha?.trim()?.takeIf { it.isNotEmpty() }
                } catch (e: ShaMismatch) {
                    // 资产在下载途中被更新：残件作废、重取最新校验值、从头再来
                    Log.w(TAG, "SHA256 不匹配，删除残件并重取校验值重试", e)
                    dest.delete()
                    expectedSha = fetchFirstSha(shaUrls) ?: expectedSha
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

    /** 按序尝试各校验值源，返回第一个非空结果；全失败返回 null。 */
    private fun fetchFirstSha(shaUrls: List<String>): String? {
        for (u in shaUrls) {
            val t = fetchText(u)
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    /**
     * 抓取小文件为**原始字节**（验签必须走这条：任何一次解码/裁剪都会破坏签名覆盖范围）。
     * 任何失败都返回 null，由调用方决定降级策略。
     */
    fun fetchBytes(url: String): ByteArray? = try {
        sharedClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "抓取字节失败：$url", t)
        null
    }

    /**
     * 抓取小文本文件；任何失败都返回 null，由调用方决定降级策略。
     * @param trimEnds 默认去除首尾空白（.sha256 边车等）；**验签类调用必须传 false**——
     *   签名覆盖文件全部字节，裁掉末尾换行即验签恒败（实测 2026-10-04，恰好差 1 字节）。
     *   新的验签路径（环境包索引）直接用 [fetchBytes]，从根上避免"先解码再验"。
     */
    fun fetchText(url: String, trimEnds: Boolean = true): String? =
        // ⚠️ 不用 body.string()：对无 charset 的 text/* 响应它按 ISO-8859-1 解码
        //（RFC 7231 老规则），代理剥掉 charset 头时中文 UTF-8 会被静默破坏
        //（实测 2026-10-04 manifest 验签恒败的根因之一）。统一显式 UTF-8。
        fetchBytes(url)?.toString(Charsets.UTF_8)?.let { text ->
            if (trimEnds) text.trim() else text
        }?.takeIf { it.isNotEmpty() }

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
