// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境索引解析（协议 §4）的单测。
 *
 * 这个解析器是"检查更新"的第一道闸：CI 产出的 JSON、镜像回源回来的半截内容、老格式索引
 * 都会经过它，所以每个分支都断言**具体期望值**（不是 assertNotNull 了事）：
 * 能解析的必须字段正确，不能解析的必须给 null 让调用方降级——**绝不抛异常到 UI**。
 */
class RootfsIndexTest {

    /** 契约 §4 的完整索引。 */
    private val fullJson = """
        {
          "schema": 1,
          "distro": "debian-13.7",
          "version": "13.7",
          "env": "0123456789ABCDEF",
          "builtAt": "2026-10-08T06:00:00Z",
          "url": "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "size": 217000000,
          "manifestUrl": "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-manifest.txt",
          "patch": {
            "from": "FEDCBA9876543210",
            "url": "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-patch-fedcba9876543210-to-0123456789abcdef.tar.zst",
            "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "size": 12000000
          }
        }
    """.trimIndent()

    @Test
    fun `完整索引（含 patch）字段逐一正确`() {
        val idx = RootfsIndexParser.parse(fullJson)
        assertNotNull(idx)
        idx!!
        assertEquals(1, idx.schema)
        assertEquals("debian-13.7", idx.distro)
        assertEquals("13.7", idx.version)
        assertEquals("0123456789abcdef", idx.env) // env 统一小写，便于直接与本地标记比对
        assertEquals(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst",
            idx.url
        )
        assertEquals("a".repeat(64), idx.sha256)
        assertEquals(217_000_000L, idx.size)
        assertEquals(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-manifest.txt",
            idx.manifestUrl
        )
        val p = idx.patch
        assertNotNull(p)
        assertEquals("fedcba9876543210", p!!.from)
        assertEquals(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-patch-fedcba9876543210-to-0123456789abcdef.tar.zst",
            p.url
        )
        assertEquals("b".repeat(64), p.sha256)
        assertEquals(12_000_000L, p.size)
    }

    @Test
    fun `patch 为 null 时索引仍可用（只能全量）`() {
        val json = """{"schema":1,"distro":"debian-13.7","version":"13.7","env":"0123456789abcdef",
            "url":"https://x/y.tar.zst","sha256":"c","size":10,"patch":null}""".trimIndent()
        val idx = RootfsIndexParser.parse(json)
        assertNotNull(idx)
        assertNull(idx!!.patch)
        assertEquals("0123456789abcdef", idx.env)
        assertEquals(10L, idx.size)
    }

    @Test
    fun `patch 缺省（老索引没有这个键）时也只是 null`() {
        val idx = RootfsIndexParser.parse("""{"url":"https://x/y.tar.zst","version":"13.7"}""")
        assertNotNull(idx)
        assertNull(idx!!.patch)
        assertEquals(0L, idx.size)
        assertEquals("", idx.sha256)
    }

    @Test
    fun `patch 缺 sha256 时整条 patch 判 null（没有校验值的补丁绝不装）`() {
        val idx = RootfsIndexParser.parse(
            """{"url":"https://x/y.tar.zst","patch":{"from":"0123456789abcdef","url":"https://x/p.tar.zst"}}"""
        )
        assertNotNull(idx)
        assertNull(idx!!.patch)
    }

    @Test
    fun `老格式索引（只有 version-distro-url-sha256）按默认值降级`() {
        val idx = RootfsIndexParser.parse(
            """{"version":"13.7","distro":"debian-13.7","url":"https://x/y.tar.zst","sha256":""}"""
        )
        assertNotNull(idx)
        idx!!
        assertEquals(0, idx.schema)
        assertEquals("debian-13.7", idx.distro)
        assertEquals("13.7", idx.version)
        assertNull(idx.env) // 老格式没有 env ⇒ 调用方按"本地无版本记录"处理
        assertEquals("https://x/y.tar.zst", idx.url)
        assertEquals("", idx.sha256)
        assertEquals(0L, idx.size)
        assertNull(idx.manifestUrl)
        assertNull(idx.patch)
    }

    @Test
    fun `字段缺失取默认值但 url 缺失则整条判 null`() {
        val idx = RootfsIndexParser.parse("""{"url":"https://x/y.tar.zst"}""")
        assertNotNull(idx)
        idx!!
        assertEquals(0, idx.schema)
        assertEquals("", idx.distro)
        assertEquals("", idx.version)
        assertNull(idx.env)
        assertEquals(0L, idx.size)
        assertNull(idx.patch)

        assertNull(RootfsIndexParser.parse("""{"schema":1,"distro":"debian-13.7"}""")) // 没有 url
        assertNull(RootfsIndexParser.parse("""{"url":""}"""))                          // url 是空串
        assertNull(RootfsIndexParser.parse("""{"url":"   "}"""))                       // url 只有空白
        assertNull(RootfsIndexParser.parse("""{"url":123}"""))                         // url 类型不对
    }

    @Test
    fun `数字字段接受数字或数字字符串`() {
        val idx = RootfsIndexParser.parse(
            """{"url":"https://x/y.tar.zst","size":"217000000","schema":"1","patch":
               {"from":"0123456789abcdef","url":"https://x/p.tar.zst","sha256":"d","size":"12000000"}}"""
        )
        assertNotNull(idx)
        assertEquals(1, idx!!.schema)
        assertEquals(217_000_000L, idx.size)
        assertEquals(12_000_000L, idx.patch!!.size)
        // 带小数点的数字也不会炸（size 取整）
        assertEquals(123L, RootfsIndexParser.parse("""{"url":"u","size":123.9}""")!!.size)
        // 完全不是数字 ⇒ 0（UI 显示"大小未知"）
        assertEquals(0L, RootfsIndexParser.parse("""{"url":"u","size":"big"}""")!!.size)
    }

    @Test
    fun `半截 JSON 与垃圾输入一律 null`() {
        // 下载被截断：括号没配平
        assertNull(RootfsIndexParser.parse("""{"schema":1,"distro":"debian-13.7","url":"https://x/y"""))
        assertNull(RootfsIndexParser.parse("""{"url":"https://x/y.tar.zst" """))
        // 尾部有多余内容（代理注入/拼接）
        assertNull(RootfsIndexParser.parse("""{"url":"https://x/y.tar.zst"} extra"""))
        // 顶层不是对象
        assertNull(RootfsIndexParser.parse("[1,2,3]"))
        assertNull(RootfsIndexParser.parse("\"just a string\""))
        // 纯垃圾 / 空
        assertNull(RootfsIndexParser.parse(""))
        assertNull(RootfsIndexParser.parse("not json at all"))
        assertNull(RootfsIndexParser.parse("<html><body>502 Bad Gateway</body></html>"))
        // 嵌套过深（脏数据不该把递归栈打爆）
        assertNull(RootfsIndexParser.parse("[".repeat(200) + "]".repeat(200)))
    }

    @Test
    fun `索引地址是滚动 Release 里的固定资产名`() {
        assertEquals(
            "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-index.json",
            RootfsIndexFetcher.URL
        )
        assertTrue(RootfsIndexFetcher.URL.endsWith("/rootfs-index.json"))
    }
}
