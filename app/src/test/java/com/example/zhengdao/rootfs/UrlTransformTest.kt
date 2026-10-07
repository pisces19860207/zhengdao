// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：MockWebServer 官方文档。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL 变换函数纯逻辑测试（零设备依赖、零网络）。
 *
 * - [RootfsDownloader.withMirrorFallback]：v1.2 B3 的 gh-proxy 兜底
 * - [RootfsDownloader.withObjectStorage]：方案一的对象存储首选源
 *   （docs/milestones/证道-方案一执行清单.md；桶未配置时不接入下载列表）
 */
class UrlTransformTest {

    // ── withMirrorFallback ──────────────────────────────────────────

    @Test
    fun mirror_github直链补gh代理兜底() {
        val url = "https://github.com/pisces19860207/zhengdao/releases/download/v1.0.0/debian.tar.zst"
        assertEquals(
            listOf(url, "https://gh-proxy.com/$url"),
            RootfsDownloader.withMirrorFallback(url)
        )
    }

    @Test
    fun mirror_非github地址原样单元素() {
        val url = "https://cdn.example.com/zhengdao/debian.tar.zst"
        assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
    }

    @Test
    fun mirror_http前缀的github不误伤() {
        // 只认 https://github.com/ 前缀——http 变体不属于受支持的直链形态
        val url = "http://github.com/some/file"
        assertEquals(listOf(url), RootfsDownloader.withMirrorFallback(url))
    }

    @Test
    fun mirror_带查询参数的github链接兜底仍可拼接() {
        // 拼接语义是字符串前置 gh-proxy.com/——查询参数按原样保留（gh-proxy 透传）
        val url = "https://github.com/a/b/releases/download/v1/f.bin?X=1"
        assertEquals(
            listOf(url, "https://gh-proxy.com/$url"),
            RootfsDownloader.withMirrorFallback(url)
        )
    }

    // ── withObjectStorage ───────────────────────────────────────────

    @Test
    fun objectStorage_桶优先原链接兜底() {
        val url = "https://github.com/a/b/releases/download/v1.0.0/debian-13.7-base-arm64.tar.zst"
        assertEquals(
            listOf(
                "https://cdn.example.com/zhengdao/debian-13.7-base-arm64.tar.zst",
                url
            ),
            RootfsDownloader.withObjectStorage(url, "https://cdn.example.com/zhengdao")
        )
    }

    @Test
    fun objectStorage_桶域名末尾斜杠归一() {
        val url = "https://github.com/a/b/f/debian.tar.zst"
        val withSlash = RootfsDownloader.withObjectStorage(url, "https://cdn.example.com/zhengdao/")
        val without = RootfsDownloader.withObjectStorage(url, "https://cdn.example.com/zhengdao")
        assertEquals(without, withSlash)
        assertEquals("https://cdn.example.com/zhengdao/debian.tar.zst", withSlash.first())
    }

    @Test
    fun objectStorage_空桶域名退化为仅原链接() {
        val url = "https://github.com/a/b/f/debian.tar.zst"
        assertEquals(listOf(url), RootfsDownloader.withObjectStorage(url, ""))
        // 纯空白同理
        assertEquals(listOf(url), RootfsDownloader.withObjectStorage(url, "   "))
    }

    @Test
    fun objectStorage_非github源同样可镜像() {
        // 语义上对象存储是「文件名镜像」，与源站是否为 github 无关
        val url = "https://mirror.example.org/path/to/debian.tar.zst"
        val result = RootfsDownloader.withObjectStorage(url, "https://cdn.example.com")
        assertEquals(
            listOf("https://cdn.example.com/debian.tar.zst", url),
            result
        )
    }
}
