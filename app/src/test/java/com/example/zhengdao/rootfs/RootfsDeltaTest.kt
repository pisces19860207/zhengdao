// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 增量更新的纯函数与安全边界单测（协议 §6）。
 *
 * 这里锁两件要命的事：
 * 1. **删除清单**：补丁给的 deletes 是"可以删掉本地任意文件"的能力，越界路径必须硬拒绝；
 * 2. **基线校验**：补丁基线与本机 env 不一致就不许动手（调用方据此回退全量）。
 */
class RootfsDeltaTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "zd-delta-${System.nanoTime()}").apply { mkdirs() }

    // ── 补丁元数据解析 ──

    @Test
    fun `parsePatchInfo 正常解析头与删除清单`() {
        val info = RootfsDelta.parsePatchInfo(
            "zhengdao-patch v1\nbase=AABBCCDDEEFF0011\nnew=1122334455667788\ndeletes=2\nusr/share/doc\n./opt/old/\n"
        )
        assertEquals("aabbccddeeff0011", info!!.base) // 统一小写
        assertEquals("1122334455667788", info.new)
        assertEquals(listOf("usr/share/doc", "opt/old"), info.deletes)
    }

    @Test
    fun `parsePatchInfo deletes 为 0 时删除清单为空`() {
        val info = RootfsDelta.parsePatchInfo(
            "zhengdao-patch v1\nbase=aabbccddeeff0011\nnew=1122334455667788\ndeletes=0\n"
        )
        assertEquals("aabbccddeeff0011", info!!.base)
        assertTrue(info.deletes.isEmpty())
    }

    @Test
    fun `parsePatchInfo 缺行 未知行 非整数都返回 null`() {
        assertNull(RootfsDelta.parsePatchInfo(""))                       // 空
        assertNull(RootfsDelta.parsePatchInfo("hello world"))            // 没有版本头
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v2\nbase=aabbccddeeff0011\nnew=1122334455667788\ndeletes=0\n"))
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nnew=1122334455667788\ndeletes=0\n")) // 缺 base
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nbase=aabbccddeeff0011\ndeletes=0\n")) // 缺 new
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nbase=aabbccddeeff0011\nnew=1122334455667788\n")) // 缺 deletes
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nbase=aabbccddeeff0011\nnew=1122334455667788\ndeletes=x\n"))
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nbase=aabbccddeeff0011\nnew=1122334455667788\ndeletes=-1\n"))
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nunknown=1\n")) // 头部出现未知行
        assertNull(RootfsDelta.parsePatchInfo("zhengdao-patch v1\nbase=\nnew=1122334455667788\ndeletes=0\n")) // 值空
    }

    @Test
    fun `声明 deletes 多于实际行数时拒绝（下载被截断的征兆）`() {
        assertNull(
            RootfsDelta.parsePatchInfo(
                "zhengdao-patch v1\nbase=aabbccddeeff0011\nnew=1122334455667788\ndeletes=3\nusr/a\nusr/b\n"
            )
        )
    }

    // ── 删除清单规整 ──

    @Test
    fun `parseDeletes 过滤空行与空白并按序去重`() {
        val out = RootfsDelta.parseDeletes(
            listOf("", "   ", "# 注释不是路径", "  usr/a  ", "./opt/b/", "usr/a", "opt/b")
        )
        assertEquals(listOf("usr/a", "opt/b"), out)
    }

    @Test
    fun `parseDeletes 丢弃点号与空段`() {
        assertEquals(emptyList<String>(), RootfsDelta.parseDeletes(listOf(".", "./", "/", "   ", "#x")))
    }

    // ── 删除路径的越界防护 ──

    @Test
    fun `deletePath 正常删除文件与目录树`() {
        val root = tempRoot()
        try {
            File(root, "usr/share/doc/a.txt").apply { parentFile!!.mkdirs(); writeText("a") }
            File(root, "usr/share/doc/b/c.txt").apply { parentFile!!.mkdirs(); writeText("c") }
            File(root, "keep.txt").writeText("k")

            RootfsDelta.deletePath(root, "usr/share/doc")
            assertFalse(File(root, "usr/share/doc").exists())
            assertTrue(File(root, "keep.txt").isFile)

            // 不存在 = 幂等 no-op（补丁重复应用不该炸）
            RootfsDelta.deletePath(root, "not/there")
            // 带 ./ 前缀与尾部斜杠也认
            File(root, "x/y.txt").apply { parentFile!!.mkdirs(); writeText("y") }
            RootfsDelta.deletePath(root, "./x/")
            assertFalse(File(root, "x").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deletePath 拒绝上跳与绝对路径（含盘符）`() {
        val root = tempRoot()
        try {
            val bad = listOf(
                "", "..", "../x", "a/../../b", "a/../b", "/etc/passwd", "/", "C:/windows",
                "c:\\windows", "..\\x", "a/./b", "a//b", "./../x",
            )
            for (rel in bad) {
                assertThrows("应拒绝：<$rel>", IllegalArgumentException::class.java) {
                    RootfsDelta.deletePath(root, rel)
                }
            }
            // 拒绝之后根目录还在（没有被误删）
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── 基线校验 ──

    @Test
    fun `canApplyTo 只认基线一致的补丁`() {
        val patch = PatchRef("aabbccddeeff0011", "https://x/p.tar.zst", "sha", 1L)
        assertTrue(RootfsDelta.canApplyTo("AABBCCDDEEFF0011", patch)) // 大小写无关
        assertTrue(RootfsDelta.canApplyTo("aabbccddeeff0011", patch))
        assertFalse(RootfsDelta.canApplyTo("1122334455667788", patch))
        assertFalse(RootfsDelta.canApplyTo(null, patch)) // 旧安装：无版本记录 ⇒ 必须走全量
        assertFalse(RootfsDelta.canApplyTo("", patch))
    }

    // ── 补丁文件读取 ──

    @Test
    fun `readPatchInfo 对非补丁文件返回 null 而不抛`() {
        val dir = tempRoot()
        try {
            val text = File(dir, "not-a-patch.txt").apply { writeText("这不是 tar 包，只是一段文本") }
            assertNull(RootfsDelta.readPatchInfo(text))
            val empty = File(dir, "empty.tar.zst").apply { writeText("") }
            assertNull(RootfsDelta.readPatchInfo(empty))
            val missing = File(dir, "missing.tar.zst")
            assertNull(RootfsDelta.readPatchInfo(missing))
        } finally {
            dir.deleteRecursively()
        }
    }
}
