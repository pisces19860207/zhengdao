// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 637 · 落点与命名的门禁（判据⑥「产物只有 PNG 无缓存」的前半条：**只会写这一个文件**）。
 *
 * 名字必须是全 ASCII：产物目录本身叫「图表」是给用户看的，文件名若也用中文，
 * `adb pull`／脚本批量处理都会踩编码坑（本仓在取证环节踩过）。
 */
class MermaidPathsTest {

    @Test
    fun `落点是 产物 图表`() {
        assertEquals("产物/图表", MermaidPaths.DIAGRAMS)
        assertEquals(
            File(File("ws"), "产物").path + File.separator + "图表",
            MermaidPaths.diagramsDir(File("ws")).path,
        )
    }

    @Test
    fun `文件名是 ASCII 且带类型与时间戳`() {
        val name = MermaidPaths.fileName(MermaidKind.SEQUENCE, "20261114-140233")
        assertEquals("mermaid-sequence-20261114-140233.png", name)
        assertTrue("文件名必须全是 ASCII", name.all { it.code < 128 })
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `同名不覆盖 依次追加序号`() {
        val dir = Files.createTempDirectory("zd637").toFile()
        try {
            val first = MermaidPaths.unique(dir, "mermaid-flowchart-20261114-140233.png")
            assertEquals("mermaid-flowchart-20261114-140233.png", first.name)

            first.writeBytes(byteArrayOf(1, 2, 3))
            val second = MermaidPaths.unique(dir, "mermaid-flowchart-20261114-140233.png")
            assertEquals("mermaid-flowchart-20261114-140233-2.png", second.name)

            second.writeBytes(byteArrayOf(4, 5, 6))
            val third = MermaidPaths.unique(dir, "mermaid-flowchart-20261114-140233.png")
            assertEquals("mermaid-flowchart-20261114-140233-3.png", third.name)

            // 老文件一个字节没被动过
            assertEquals(listOf<Byte>(1, 2, 3), first.readBytes().toList())
            assertEquals(2, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `时间戳格式固定为 年月日-时分秒`() {
        val stamp = MermaidPaths.stamp(0L)
        assertTrue("$stamp 不符合 yyyyMMdd-HHmmss", Regex("""^\d{8}-\d{6}$""").matches(stamp))
        assertFalse("别把时区写成 UTC 前缀之类的花样", stamp.contains("T"))
    }
}
