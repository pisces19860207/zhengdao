// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.testkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 564 的门禁：沙盒自己的行为必须可测，否则「建了必删」只是口头约定。
 *
 * 覆盖三条硬要求：
 * 1. 正常退出 ⇒ 目录与内容全没了（正例）；
 * 2. block 抛异常 ⇒ 原异常原样抛出、目录照样删掉（负例）；
 * 3. 清理是幂等的、前缀清洗不炸（负例）。
 */
class TempSandboxTest {

    @Test
    fun `正常退出后目录与内容全部消失`() {
        var seen: File? = null
        TempSandbox.use("zd564-ok") { dir ->
            seen = dir
            assertTrue("沙盒目录应真实存在：$dir", dir.isDirectory)
            File(dir, "nested/deep").mkdirs()
            File(dir, "nested/deep/note.md").writeText("564 沙盒样例", Charsets.UTF_8)
            assertTrue(File(dir, "nested/deep/note.md").isFile)
        }
        val dir = requireNotNull(seen)
        assertFalse("use 返回后沙盒目录必须已被递归删除：$dir", dir.exists())
        assertEquals("残留计数应为 0", 0, TempSandbox.deleteTree(dir))
    }

    @Test
    fun `返回值能带出沙盒`() {
        val text = TempSandbox.useResult("zd564-ret") { dir ->
            File(dir, "a.txt").writeText("hello-564", Charsets.UTF_8)
            File(dir, "a.txt").readText(Charsets.UTF_8)
        }
        assertEquals("hello-564", text)
    }

    @Test
    fun `block 抛异常时原异常照旧抛出且目录已删`() {
        val boom = IllegalStateException("564 故意失败")
        var seen: File? = null
        try {
            TempSandbox.use("zd564-throw") { dir ->
                seen = dir
                File(dir, "partial.txt").writeText("半途", Charsets.UTF_8)
                throw boom
            }
            fail("block 抛异常时 use 不应正常返回")
        } catch (t: Throwable) {
            assertSame("必须原样抛出 block 的异常实例（不包装、不换成清理异常）", boom, t)
            assertTrue("原异常上不应有 suppressed（本例清理是干净的）", t.suppressed.isEmpty())
        }
        assertFalse("抛异常路径同样必须删干净", requireNotNull(seen).exists())
    }

    @Test
    fun `重复清理同一路径返回 0 且不抛`() {
        val dir = TempSandbox.create("zd564-idem")
        assertTrue(dir.isDirectory)
        assertEquals(0, TempSandbox.deleteTree(dir))
        assertEquals("已删路径再删一次仍应返回 0", 0, TempSandbox.deleteTree(dir))
    }

    @Test
    fun `前缀清洗后始终可用`() {
        val cases = listOf("", "  ", "a", "ab", "中文前缀", "zd/../../evil", "x".repeat(80))
        for (raw in cases) {
            val safe = TempSandbox.sanitize(raw)
            assertTrue("前缀清洗结果不短于 3：'$raw' -> '$safe'", safe.length >= 3)
            assertTrue("前缀不得含路径分隔符：'$raw' -> '$safe'", !safe.contains('/') && !safe.contains('\\'))
            assertTrue(
                "前缀只允许 [A-Za-z0-9._-]：'$raw' -> '$safe'",
                Regex("^[A-Za-z0-9._-]+$").matches(safe)
            )
        }
    }
}
