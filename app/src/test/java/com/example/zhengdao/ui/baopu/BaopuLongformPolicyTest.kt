// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一百七十九批 · 长文输出卡的**纯判据**（触发/标题/slug/文件名/纯文本化）。
 *
 * 图纸：`_zd-private/docs/派工单-2026-10-24-第一百七十九批-长文输出卡.md` §1。
 */
class BaopuLongformPolicyTest {

    private val longWeibo = buildString {
        append("# 秋日随想\n\n")
        append("清晨的雾还没散，巷口的豆浆摊已经排起了队。\n\n")
        append("- 雾是湿的\n- 人是暖的\n- 灯是黄的\n\n")
        append("这样的早晨值得被记下来，哪怕只是几行字。\n\n")
        append("结尾就到这里，祝今天顺利。")
    }

    // ── 触发 ──────────────────────────────────────────────────────────────

    @Test
    fun `触发-a 用户点名微头条_多短都给卡`() {
        assertTrue(BaopuLongformPolicy.isLongform("就一句话。", userText = "帮我写个微头条"))
    }

    @Test
    fun `触发-b 超过 600 字_给卡`() {
        val long = "字".repeat(601)
        assertTrue(BaopuLongformPolicy.isLongform(long))
    }

    @Test
    fun `触发-c 三个以上段落_给卡`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        assertTrue(BaopuLongformPolicy.isLongform(text))
    }

    @Test
    fun `触发-d 有标题行或列表_给卡`() {
        assertTrue(BaopuLongformPolicy.isLongform(longWeibo))
        assertTrue(BaopuLongformPolicy.isLongform("要点：\n- 甲\n- 乙\n- 丙"))
    }

    @Test
    fun `负例-a 普通短回复_不给卡`() {
        assertFalse(BaopuLongformPolicy.isLongform("好的，已记下。"))
        assertFalse(BaopuLongformPolicy.isLongform("第一段。\n\n第二段。"))
        assertFalse(BaopuLongformPolicy.isLongform(""))
    }

    @Test
    fun `负例-b 列表只有两项_不给卡（不足三条）`() {
        assertFalse(BaopuLongformPolicy.isLongform("要点：\n- 甲\n- 乙"))
    }

    // ── 标题 ──────────────────────────────────────────────────────────────

    @Test
    fun `标题-a 优先取 markdown 标题行`() {
        assertEquals("秋日随想", BaopuLongformPolicy.cardTitle(longWeibo))
    }

    @Test
    fun `标题-b 没有标题取首行_超 24 字截断`() {
        assertEquals("无标题", BaopuLongformPolicy.cardTitle("   \n  "))
        val t = BaopuLongformPolicy.cardTitle("这一行有三十个字以上的话就会被截断加省略号看看效果如何呢继续写")
        assertTrue(t.endsWith("…"))
        assertTrue(t.length <= 25)
    }

    // ── slug 与文件名 ─────────────────────────────────────────────────────

    @Test
    fun `slug-a 中文保留_非法字符剔除_空格变横线`() {
        assertEquals("秋日-随想-好!", BaopuLongformPolicy.slug("秋日 随想: 好!"))
    }

    @Test
    fun `slug-b 全是非法字符_回退无标题`() {
        assertEquals("无标题", BaopuLongformPolicy.slug("///"))
    }

    @Test
    fun `文件名-a 平铺命名_微头条-时间-slug`() {
        assertEquals(
            "微头条-20261024-1905-秋日随想.md",
            BaopuLongformPolicy.artifactFileName(2026, 10, 24, 19, 5, "秋日随想", "md"),
        )
    }

    @Test
    fun `文件名-b 同名不覆盖_依次加 _2 _3`() {
        val existing = setOf("a.md", "a_2.md")
        assertEquals("a_3.md", BaopuLongformPolicy.nextFileName(existing, "a.md"))
        assertEquals("b.md", BaopuLongformPolicy.nextFileName(existing, "b.md"))
    }

    // ── 纯文本视图 ────────────────────────────────────────────────────────

    @Test
    fun `纯文本-a 剥标题加粗列表_正文不动`() {
        val md = "# 标题\n\n这是**加粗**和`代码`。\n\n- 甲\n- 乙\n\n[链接文字](https://a.b)"
        val plain = BaopuLongformPolicy.toPlainText(md)
        assertTrue(plain.contains("标题"))
        assertTrue(plain.contains("加粗"))
        assertTrue(plain.contains("代码"))
        assertTrue(plain.contains("链接文字"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("](https"))
        assertFalse(plain.contains("\n- "))
    }

    @Test
    fun `字数-非空白字符计数`() {
        assertEquals(5, BaopuLongformPolicy.wordCount("你好 世\n界!"))
    }
}
