// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ⑤：影子账本的逐字节断言。
// 口径：键序与位宽＝定稿 §七；只追加不改写；档越界当场抛；一行一物类。
// 临时目录在本类里自建自删（水位闸口径：测试不许攒沙盒）。
package com.example.zhengdao.baopu.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShadowRecorderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zd788b-rec").toFile()
    }

    @After
    fun tearDown() {
        dir.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun `一行账与定稿逐字相同`() {
        val expected =
            "{\"round\":1234,\"zone\":\"glans\",\"proxyTier\":2,\"snnTier\":2," +
                "\"agree\":true,\"spikeRate\":0.4100,\"seed\":24301}"
        assertEquals(expected, ShadowRecorder.line(1234, "glans", 2, 2, 0.41))
    }

    @Test
    fun `键序固定_换序即红`() {
        val line = ShadowRecorder.line(1, "glans", 0, 0, 0.0)
        val keys = listOf("round", "zone", "proxyTier", "snnTier", "agree", "spikeRate", "seed")
        val positions = keys.map { line.indexOf("\"$it\"") }
        assertTrue("七个键一个都不能少：$line", positions.all { it >= 0 })
        assertEquals("键序必须按定稿 §七", positions.sorted(), positions)
    }

    @Test
    fun `seed 默认就是 0x5eed 的十进制`() {
        val line = ShadowRecorder.line(1, "glans", 0, 0, 0.0)
        assertEquals(24301L, LifParams.SEED)
        assertTrue("账本必须写十进制 24301：$line", line.endsWith("\"seed\":24301}"))
    }

    @Test
    fun `两档相同才算一致`() {
        assertTrue(ShadowRecorder.line(1, "glans", 3, 3, 0.1).contains("\"agree\":true"))
        assertTrue(ShadowRecorder.line(1, "glans", 2, 3, 0.1).contains("\"agree\":false"))
        assertTrue(ShadowRecorder.line(1, "glans", 3, 2, 0.1).contains("\"agree\":false"))
    }

    @Test
    fun `只追加_前一行一字不改`() {
        val recorder = ShadowRecorder.under(dir)
        val first = recorder.record(1, "glans", 1, 1, 0.02)
        val second = recorder.record(2, "shaft", 2, 3, 0.09)
        val text = recorder.file().readText(Charsets.UTF_8)
        assertEquals(first + second, text)
        assertEquals("两行两换行", 2, text.count { it == '\n' })
        assertEquals("文件名固定", "脑影.jsonl", recorder.file().name)
    }

    @Test
    fun `档越界当场抛`() {
        for (bad in listOf(-1, 4)) {
            assertTrue(
                "代理档 $bad 必须抛",
                runCatching { ShadowRecorder.line(1, "glans", bad, 0, 0.0) }.exceptionOrNull()
                    is IllegalArgumentException,
            )
            assertTrue(
                "影子档 $bad 必须抛",
                runCatching { ShadowRecorder.line(1, "glans", 0, bad, 0.0) }.exceptionOrNull()
                    is IllegalArgumentException,
            )
        }
    }

    @Test
    fun `空物类与非有限率都不许落账`() {
        assertTrue(
            "空物类必须抛",
            runCatching { ShadowRecorder.line(1, "  ", 0, 0, 0.0) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            "NaN 率必须抛",
            runCatching { ShadowRecorder.line(1, "glans", 0, 0, Double.NaN) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `引号与反斜杠要转义_别写出坏行`() {
        val line = ShadowRecorder.line(1, "a\"b\\c", 0, 0, 0.0)
        assertTrue("双引号要转义：$line", line.contains("\"zone\":\"a\\\"b\\\\c\""))
    }

    @Test
    fun `四档率都写成四位小数`() {
        assertEquals(true, ShadowRecorder.line(1, "z", 0, 0, 0.0).contains("\"spikeRate\":0.0000"))
        assertEquals(true, ShadowRecorder.line(1, "z", 0, 0, 1.0 / 3).contains("\"spikeRate\":0.3333"))
        assertEquals(true, ShadowRecorder.line(1, "z", 0, 0, 0.10312).contains("\"spikeRate\":0.1031"))
    }
}
