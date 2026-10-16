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

    /** 重试退避基数（毫秒）：第 1 次重试等这么久，之后翻倍。 */
    private const val RETRY_BASE_DELAY_MS = 800L

    /** 重试退避上限（毫秒）：翻到这么大就不再涨（rootfs 326MB，等太久用户会以为卡死）。 */
    private const val RETRY_MAX_DELAY_MS = 8_000L

    /**
     * 第 [retryIndex] 次重试前要睡多久（0 起算；纯函数，JVM 单测钉住）。
     *
     * 为什么必须有（台账 issue #17 P2-14）：原实现同一 URL 失败后**立即**重试，
     * 3 连击在几百毫秒内打完——对端（github / 镜像）正在抖的时候，这等于帮着砸，
     * 且第 2、3 次几乎必然同样失败，白耗一次完整超时周期。
     * 指数退避把"立即重试"变成"让对端喘口气"，Generic Exception / ShaMismatch /
     * 末源超时这三条会重试同一 URL 的路径都走它。
     */
    internal fun backoffDelayMs(retryIndex: Int): Long {
        if (retryIndex < 0) return 0L
        var delay = RETRY_BASE_DELAY_MS
        repeat(retryIndex) { delay = minOf(delay * 2, RETRY_MAX_DELAY_MS) }
        return minOf(delay, RETRY_MAX_DELAY_MS)
    }

    class DownloadFailed(message: String) : IOException(message)

    /** 下载内容与校验值不符（多为发布资产在下载途中被更新）——残件作废、重取校验值、从头再来 */
    class ShaMismatch(message: String) : IOException(message)

    /**
     * 传输过慢/卡死（**不是错误，是"该换源了"**）：残件保留，交给下一个候选源续传。
     *
     * 为什么要有这条（2026-10-11，用户：「给终端里的 git 加下速和加固一下」，同一批把下载层
     * 一起收口）：原来的换源只发生在**失败**时——而国内网络的典型形态是「连得上、慢得像屎」
     * （直连 65 KB/s vs 镜像 625 KB/s，差 9.5×）。没有这条，直连能连通的机器永远吃不到镜像的
     * 速度；有了它，"卡住"也变成可切换信号。
     */
    internal class SlowTransfer(message: String) : IOException(message)

    /**
     * 慢速判定（纯函数，可单测）：**只对还有后续候选的源**生效。
     *
     * 三个参数都是实测/权衡出来的：
     * - `MIN_BYTES_PER_SEC = 192 KB/s`：实测直连 65 KB/s、gh-proxy 138 KB/s 都要被换掉，
     *   而 ghfast.top 625 KB/s 要能留下 ⇒ 阈值取在两者之间；
     * - `GRACE_MS = 15s`：前 15 秒不看速度（DNS/握手/TTFB 与 TCP 慢启动都在这段里，
     *   一上来就判"慢"会误伤；顺带把"连接挂着不动"的检测从 OkHttp 的 60s readTimeout
     *   提前到 15s）；
     * - 最后一个候选**永不**中断：没有备选时再慢也只能下完，否则等于把用户卡死。
     */
    internal object SpeedGuard {
        const val MIN_BYTES_PER_SEC = 192L * 1024
        const val GRACE_MS = 15_000L

        fun shouldAbort(
            elapsedMs: Long,
            transferredBytes: Long,
            hasFallback: Boolean,
            minBps: Long = MIN_BYTES_PER_SEC,
            graceMs: Long = GRACE_MS,
        ): Boolean {
            if (!hasFallback) return false
            if (elapsedMs < graceMs) return false
            val bps = transferredBytes * 1000 / elapsedMs
            return bps < minBps
        }
    }

    /** 共享 HTTP 客户端：连接池常驻，退后台时由 releaseIdleResources() 清空。 */
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * **大文件**下载专用客户端（2026-10-11）：readTimeout 从 60s 收紧到 25s。
     *
     * 为什么要单独一个：换源只在"失败"时发生，而 60s 的 readTimeout 意味着一条**挂死的**
     * 连接要占满 60 秒才被判失败（再乘上每源 3 次重试）。25s 让"卡住的源"更快让位给镜像；
     * 小文件（.sha256 / 索引 / 清单）仍走 [sharedClient] 的 60s，不受影响——它们慢的原因
     * 往往是服务端 TTFB 而非带宽。
     *
     * ⚠️ 慢而**活着**的连接不会被这个超时误伤：读缓冲 64 KB，只要字节在流，read() 就不会超时；
     *    那种"稳定慢"由 [SpeedGuard] 处理。
     */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 内存看护（onTrimMemory 退后台时调用）：清掉空闲 TCP 连接与缓冲。
     * WebView/Chromium 的内存由系统回调自动管理，不在此处理。
     *
     * P2-7（issue #17）：连接池清空是 best-effort（失败不影响正确性，下一轮自然重建），
     * 但不能空 catch 静默——evictAll 抛异常意味着底层 socket 有问题，而那正是排查
     * "下载偶发失败"时要看的线索。降级为 debug 日志：不刷屏、可查。
     */
    fun releaseIdleResources() {
        try {
            sharedClient.connectionPool.evictAll()
        } catch (t: Throwable) {
            Log.d(TAG, "sharedClient 连接池清空失败（best-effort，忽略）", t)
        }
        try {
            downloadClient.connectionPool.evictAll()
        } catch (t: Throwable) {
            Log.d(TAG, "downloadClient 连接池清空失败（best-effort，忽略）", t)
        }
    }

    /**
     * 给 github.com 直链补镜像兜底（v1.2 B3 起；2026-10-11 起镜像表收口到 [GithubMirror]）。
     *
     * 为什么需要：github.com 在部分网络下不可达（实测本机 http=000、镜像 200），
     * 而 rootfs（326MB）此前只有这一个地址——新用户第一步就可能卡死。
     *
     * ⚠️ 2026-10-11 变更：兜底源从「只有 gh-proxy.com」变成 **[GithubMirror.DOWNLOAD_CHAIN]**
     *    （ghfast.top → gh-proxy.com）。实测同一 8 MB 资产：直连 65 KB/s、gh-proxy.com
     *    138 KB/s、**ghfast.top 625 KB/s**。主源仍在首位，镜像只在直连失败/过慢时接手
     *    （慢速接手见 [SpeedGuard]）。
     */
    fun withMirrorFallback(url: String): List<String> = GithubMirror.candidates(url)

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
     *   传空列表 = 调用方明确不校验（OcManager 走这条路 —— 该类 2026-10-16 已删：它用 release digest 或定版 SHA 自行校验）。
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
        urls.forEachIndexed { index, url ->
            val hasFallback = index < urls.size - 1
            for (attempt in 0 until MAX_ATTEMPTS_PER_URL) {
                // P2-14：同一 URL 的重试之间指数退避（首次尝试不睡）。
                // 换源的 break 路径在睡之前就退出循环，不会白等。
                if (attempt > 0) {
                    val waitMs = backoffDelayMs(attempt - 1)
                    Log.i(TAG, "第 ${attempt + 1} 次尝试前退避 ${waitMs}ms（$url）")
                    Thread.sleep(waitMs)
                }
                try {
                    Log.i(
                        TAG,
                        "下载尝试：$url（第 ${attempt + 1} 次）" +
                            if (hasFallback) "，过慢会被换源" else "（最后一个候选，不再换源）",
                    )
                    downloadOne(downloadClient, url, dest, hasFallback, onProgress)
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
                } catch (e: SlowTransfer) {
                    // 不是失败、是"这个源不行"：**残件保留**给下一个源续传（Range）；
                    // 换源后字节若不一致，最终 sha256 校验会拦住（见 downloadOne 的 416 处理）。
                    Log.w(TAG, "传输过慢，换下一个源：$url —— ${e.message}")
                    lastError = e
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    // 挂死的连接（readTimeout）：既然还有别的源，就别在同一个源上再等 3 轮
                    Log.w(TAG, "连接超时，换下一个源：$url —— ${e.message}")
                    lastError = e
                    if (hasFallback) break
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
        hasFallback: Boolean,
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
            // 慢速看护（2026-10-11）：从本次连接的 0 字节算起，不看已续传的历史字节。
            val startedAt = System.nanoTime()
            val startBytes = done
            var lastCheckedMs = 0L
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
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                        if (elapsedMs - lastCheckedMs >= 1_000) {
                            lastCheckedMs = elapsedMs
                            if (SpeedGuard.shouldAbort(elapsedMs, done - startBytes, hasFallback)) {
                                val kb = (done - startBytes) / 1024
                                throw SlowTransfer(
                                    "本次连接 ${elapsedMs / 1000}s 只收到 ${kb}KB" +
                                        "（低于 ${SpeedGuard.MIN_BYTES_PER_SEC / 1024}KB/s），残件保留待续传",
                                )
                            }
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

    /**
     * 流式计算文件 SHA-256（小写十六进制）。
     *
     * 实现顺序（规范 #2 回退纪律）：**先走 Rust 核心**（`rust/core` 的 sha2+asm，与解压流水线
     * 同一份哈希实现，见 [com.example.zhengdao.rust.CoreNative.sha256File]），Rust 不可用或读盘
     * 失败才回落到平台的 `MessageDigest` 流式实现。
     *
     * 为什么需要它（而不是只在下载后顺手记一个）：增量基线（env）要不要在"重装同一份包"时
     * 写回，判据就是"这次要装的包 sha256 == 标记里那个 sha256"（见
     * [com.example.zhengdao.rootfs.RootfsInstaller.envForReinstall]）。192 MB 的包在真机上
     * 约 1 秒读完，调用方负责放到后台线程。
     */
    fun sha256Of(file: File): String {
        val rust = com.example.zhengdao.rust.CoreNative.sha256File(file)
        if (rust != null) {
            // 留一行"这条路走的是谁"的证据：release 包里 R8 若改了 native 方法名，
            // 这里会退化成"平台回退"，日志里能直接看出来（真机验收要的就是这一行）。
            Log.i(TAG, "sha256Of 走 Rust 核心：${file.name}")
            return rust
        }
        Log.i(TAG, "sha256Of 走平台回退：${file.name}")
        return platformSha256Of(file)
    }

    /** 平台回落实现（Rust 不可用时唯一路径；两者对同一文件必须给出逐字符相同的 hex）。 */
    private fun platformSha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 流式计算文件 SHA-256 并与期望值比对（不区分大小写）。 */
    fun verifySha256(file: File, expected: String) {
        val actual = sha256Of(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ShaMismatch("SHA256 校验失败：actual=$actual expected=$expected")
        }
    }
}
