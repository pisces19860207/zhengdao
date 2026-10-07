// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：MockWebServer 官方文档。
package com.example.zhengdao.rootfs

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 多源回退逻辑（候选源列表逐源重试）——**真实走 OkHttp 栈**：
 * MockWebServer 起在本地环回，download() 对它的请求与对真实源完全同构，
 * 零外网依赖。锁住三条硬语义：
 * 1. 主源失败自动回落次源（回落不是可选项，是行为）
 * 2. 全部源失败必须抛 DownloadFailed，且 dest/.part 无残留（不能静默半成品）
 * 3. 校验值取不到必须报错拒绝安装（v1.2 B3 堵上的洞，不许回退）
 */
class RootfsDownloaderFallbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val contentA = "payload-A（主源内容）".toByteArray(Charsets.UTF_8)
    private val contentB = "payload-B（次源内容）".toByteArray(Charsets.UTF_8)

    private fun shaOf(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun startServer(dispatcher: Dispatcher): MockWebServer =
        MockWebServer().apply { this.dispatcher = dispatcher; start() }

    private fun shaFile(content: ByteArray): File =
        tmp.newFile().apply { writeText(shaOf(content)) }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    fun `主源404自动回落次源成功落盘`() {
        server = startServer(object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path == "/missing.bin" -> MockResponse().setResponseCode(404)
                req.path == "/ok.sha256" -> MockResponse().setBody(shaOf(contentB))
                else -> MockResponse().setBody(String(contentB, Charsets.UTF_8))
            }
        })
        val dest = File(tmp.root, "out.bin")
        RootfsDownloader.download(
            urls = listOf("${server.url("/missing.bin")}", "${server.url("/ok.bin")}"),
            dest = dest,
            shaUrls = listOf("${server.url("/ok.sha256")}"),
            onProgress = { _, _ -> },
        )
        assertEquals(String(contentB, Charsets.UTF_8), dest.readText(Charsets.UTF_8))
    }

    @Test
    fun `全部源失败必须报错且无残留`() {
        server = startServer(object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        })
        val dest = File(tmp.root, "out.bin")
        val err = runCatching {
            RootfsDownloader.download(
                urls = listOf("${server.url("/a")}", "${server.url("/b")}"),
                dest = dest,
                shaUrls = emptyList(),   // 无校验要求——仍必须因全源失败而报错
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()
        assertTrue("应抛 DownloadFailed", err is RootfsDownloader.DownloadFailed)
        assertTrue("报错应说明全部通道失败", (err?.message ?: "").contains("全部下载通道失败"))
        assertFalse("dest 不应残留", dest.exists())
        assertFalse(".part 不应残留", File(dest.parentFile, dest.name + ".part").exists())
    }

    @Test
    fun `校验值取不到必须拒绝安装`() {
        server = startServer(object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)   // 包源与 sha 源全部 404
        })
        val dest = File(tmp.root, "out.bin")
        val err = runCatching {
            RootfsDownloader.download(
                urls = listOf("${server.url("/a")}"),
                dest = dest,
                shaUrls = listOf("${server.url("/a.sha256")}"),   // 声明要校验，但取不到
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()
        assertTrue(err is RootfsDownloader.DownloadFailed)
        assertTrue("应拒绝未校验的包", (err?.message ?: "").contains("SHA256"))
        assertFalse(dest.exists())
    }

    @Test
    fun `成功路径_内容与校验值匹配`() {
        server = startServer(object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path == "/a.sha256" -> MockResponse().setBody(shaOf(contentA))
                else -> MockResponse().setBody(String(contentA, Charsets.UTF_8))
            }
        })
        val dest = File(tmp.root, "out.bin")
        RootfsDownloader.download(
            urls = listOf("${server.url("/a")}"),
            dest = dest,
            shaUrls = listOf("${server.url("/a.sha256")}"),
            onProgress = { _, _ -> },
        )
        assertEquals(String(contentA, Charsets.UTF_8), dest.readText(Charsets.UTF_8))
    }
}
