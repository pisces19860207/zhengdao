// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §5（S3 操作前后自校验）。纯函数直测，不碰 WebView。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageFingerprint] 的判据。
 *
 * 钉三件事：
 * 1. **解析要严**：字段数不对/数字解析不了 ⇒ null（宁可说「没做成败校验」，不许编结论）；
 * 2. **diff 要如实**：变了就说变了、没变就说没变（且明说「可能没生效」）；
 * 3. **取不到指纹**（页面还没绘制）⇒ 明说没做成败校验，不当成成功。
 */
class PageFingerprintTest {

    private fun fp(
        url: String = "https://a.com/",
        title: String = "A",
        h: Int = 1000,
        n: Int = 10,
        t: Int = 500,
    ) = PageFingerprint.Fp(url, title, h, n, t)

    private fun raw(
        url: String = "https://a.com/",
        title: String = "A",
        h: String = "1000",
        n: String = "10",
        t: String = "500",
    ) = listOf(url, title, h, n, t).joinToString(PageFingerprint.SEP.toString())

    @Test
    fun 解析_五个字段_成功() {
        val p = PageFingerprint.parse(raw())
        assertNotNull(p)
        assertEquals("https://a.com/", p!!.url)
        assertEquals(1000, p.scrollHeight)
        assertEquals(10, p.interactive)
        assertEquals(500, p.textLen)
    }

    @Test
    fun 解析_字段数不对_回null() {
        assertNull(PageFingerprint.parse("a${PageFingerprint.SEP}b"))
        assertNull(PageFingerprint.parse(raw().substringBeforeLast(PageFingerprint.SEP)))
    }

    @Test
    fun 解析_数字坏掉_回null() {
        assertNull("高度不是数字", PageFingerprint.parse(raw(h = "abc")))
        assertNull("元素数不是数字", PageFingerprint.parse(raw(n = "")))
        assertNull("正文长不是数字", PageFingerprint.parse(raw(t = "x1")))
    }

    @Test
    fun 解析_null或空_回null() {
        assertNull(PageFingerprint.parse(null))
        assertNull(PageFingerprint.parse(""))
    }

    @Test
    fun diff_取不到指纹_明说没做成败校验() {
        val line = PageFingerprint.diff(null, fp())
        assertTrue(line.contains("没做成败校验"))
        assertTrue(line.contains("还没绘制"))
    }

    @Test
    fun diff_完全没变_明说可能没生效() {
        val line = PageFingerprint.diff(fp(), fp())
        assertTrue(line.contains("URL 未变"))
        assertTrue(line.contains("无可测变化"))
        assertTrue("不许把没变化说成成功", line.contains("别当成已经成功"))
    }

    @Test
    fun diff_标题变了_说出来() {
        val line = PageFingerprint.diff(fp(title = "登录"), fp(title = "登录成功"))
        assertTrue(line.contains("标题「登录」→「登录成功」"))
        assertTrue("有变化就不该再说没变化", !line.contains("无可测变化"))
    }

    @Test
    fun diff_URL变了_说出来() {
        val line = PageFingerprint.diff(fp(url = "https://a.com/1"), fp(url = "https://a.com/2"))
        assertTrue(line.contains("URL 变了"))
    }

    @Test
    fun diff_高度与元素数变化也各算一条() {
        val line = PageFingerprint.diff(fp(h = 1000, n = 10), fp(h = 1200, n = 10))
        assertTrue(line.contains("页面高度 1000→1200"))
        val line2 = PageFingerprint.diff(fp(h = 1000, n = 10), fp(h = 1000, n = 14))
        assertTrue(line2.contains("可交互元素 10→14"))
    }

    @Test
    fun diff_目标元素消失算变化() {
        val line = PageFingerprint.diff(fp(), fp(), targetStillThere = false)
        assertTrue(line.contains("目标元素已消失"))
        assertTrue(!line.contains("无可测变化"))
    }

    @Test
    fun diff_目标元素仍在且没变化_还是没变化() {
        val line = PageFingerprint.diff(fp(), fp(), targetStillThere = true)
        assertTrue(line.contains("目标元素仍在"))
        assertTrue(line.contains("无可测变化"))
    }

    @Test
    fun diff_超长URL与标题_压到60字符() {
        val long = "https://a.com/" + "x".repeat(200)
        val line = PageFingerprint.diff(fp(url = long, title = "t".repeat(200)), fp())
        assertTrue("回执不许被长 URL 撑爆", line.length < 400)
        assertTrue(line.contains("…"))
    }

    @Test
    fun 目标元素脚本_selector带引号嵌入() {
        val script = PageFingerprint.targetExistsScript("\"#q\"")
        assertTrue(script.contains("document.querySelector(\"#q\")"))
        assertTrue(script.contains("?'YES':'NO'"))
    }
}
