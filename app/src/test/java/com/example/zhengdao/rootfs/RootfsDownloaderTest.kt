package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载地址的镜像兜底规则（v1.2 B3）。只测纯函数 [RootfsDownloader.withMirrorFallback]。
 *
 * 背景：rootfs（326MB）此前只有 github.com 一个地址，而 github.com 在部分网络下
 * 不可达（实测本机 http=000、gh-proxy.com 200）——新用户第一步就可能卡死。
 * 太极的 OpenCode 包早就有镜像兜底，两边此前不对称。
 */
class RootfsDownloaderTest {

    @Test
    fun `github 直链补一条 gh-proxy 镜像，主源在前`() {
        val urls = RootfsDownloader.withMirrorFallback(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst"
        )
        assertEquals(2, urls.size)
        assertEquals(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst",
            urls[0],
        )
        assertEquals("https://gh-proxy.com/${urls[0]}", urls[1])
    }

    @Test
    fun `sha256 边车同样享受镜像兜底`() {
        val urls = RootfsDownloader.withMirrorFallback("https://github.com/a/b/x.tar.zst.sha256")
        assertEquals(2, urls.size)
        assertEquals("https://gh-proxy.com/https://github.com/a/b/x.tar.zst.sha256", urls[1])
    }

    @Test
    fun `非 github 地址原样返回，不套镜像前缀`() {
        val url = "https://example.com/mirror/debian.tar.zst"
        assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
    }

    @Test
    fun `已是 gh-proxy 的地址不二次套娃`() {
        // 套娃会变成 gh-proxy.com/gh-proxy.com/... —— 必然 404
        val url = "https://gh-proxy.com/https://github.com/a/b/x.tar.zst"
        assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
    }
}
