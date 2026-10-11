// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载地址的镜像兜底规则（v1.2 B3 起；**2026-10-11 改为多源**）。只测纯函数
 * [RootfsDownloader.withMirrorFallback] 与它背后的 [GithubMirror]。
 *
 * 背景：rootfs（326MB）此前只有 github.com 一个地址，而 github.com 在部分网络下
 * 不可达（实测本机 http=000、镜像 200）——新用户第一步就可能卡死。
 * 2026-10-11 实测（同一 8MB 资产）：直连 65 KB/s、gh-proxy.com 138 KB/s、**ghfast.top 625 KB/s**
 * ⇒ 镜像链首位改为 ghfast.top，主源仍在最前（直连失败/过慢时才换源，见 [RootfsDownloader.SpeedGuard]）。
 */
class RootfsDownloaderTest {

    private val asset =
        "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst"

    @Test
    fun `github 直链在最前，其后按实测顺序补镜像`() {
        val urls = RootfsDownloader.withMirrorFallback(asset)
        assertEquals(1 + GithubMirror.DOWNLOAD_CHAIN.size, urls.size)
        assertEquals(asset, urls[0])
        assertEquals(listOf("https://ghfast.top/$asset", "https://gh-proxy.com/$asset"), urls.drop(1))
    }

    @Test
    fun `下载链首位是实测最快的 ghfast_top，且它确实排在 gh-proxy 之前`() {
        val urls = RootfsDownloader.withMirrorFallback(asset)
        assertTrue(urls[1].startsWith("https://ghfast.top/"))
        assertTrue(urls.indexOfFirst { it.startsWith("https://gh-proxy.com/") } > 1)
    }

    @Test
    fun `sha256 边车同样享受镜像兜底`() {
        val urls = RootfsDownloader.withMirrorFallback("https://github.com/a/b/x.tar.zst.sha256")
        assertEquals(GithubMirror.DOWNLOAD_CHAIN.size + 1, urls.size)
        assertEquals("https://ghfast.top/https://github.com/a/b/x.tar.zst.sha256", urls[1])
    }

    @Test
    fun `非 github 地址原样返回，不套镜像前缀`() {
        val url = "https://example.com/mirror/debian.tar.zst"
        assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
    }

    @Test
    fun `任一镜像开头都不二次套娃（套娃必然 404）`() {
        for (prefix in GithubMirror.DOWNLOAD_CHAIN) {
            val url = "${prefix}https://github.com/a/b/x.tar.zst"
            assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
        }
    }

    @Test
    fun `git 链不含只代理下载的 ghfast_top`() {
        // 2026-10-11 实测：ghfast.top 对 info/refs?service=git-upload-pack 回 403 text/html
        // ⇒ 它能加速"下载"，但**不能**用于 clone/fetch。这条断言防的正是这个坑。
        assertTrue(GithubMirror.GIT_CHAIN.none { it.contains("ghfast.top") })
        assertTrue(GithubMirror.GIT_CHAIN.contains("https://gh-proxy.com/"))
    }

    // ── 慢速换源判据（纯函数） ──────────────────────────────────────────────────

    @Test
    fun `还有备选源时，慢于阈值即判换源`() {
        // 实测形态：直连 65 KB/s ⇒ 10 秒后才 650 KB，早该换到 625 KB/s 的镜像
        assertTrue(RootfsDownloader.SpeedGuard.shouldAbort(20_000, 65 * 1024 * 20, true))
    }

    @Test
    fun `前 15 秒不看速度（DNS、握手、TCP 慢启动都在这一段）`() {
        assertEquals(false, RootfsDownloader.SpeedGuard.shouldAbort(14_999, 0, true))
    }

    @Test
    fun `快源即使有备选也不被打断`() {
        // ghfast.top 实测 625 KB/s
        assertEquals(false, RootfsDownloader.SpeedGuard.shouldAbort(20_000, 625 * 1024 * 20, true))
    }

    @Test
    fun `最后一个候选永不中断（没有备选时再慢也只能下完）`() {
        assertEquals(false, RootfsDownloader.SpeedGuard.shouldAbort(600_000, 1, false))
    }
}
