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
    fun `触发-c 三个以上段落_不再给卡（2026-10-26 口径收紧）`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        assertFalse("多段不再触发：它曾让几乎每条回答都出卡", BaopuLongformPolicy.isLongform(text))
    }

    @Test
    fun `触发-d 有标题但不足 300 字_不给卡；够 300 字_给卡`() {
        assertFalse(
            "只有标题不算文件式内容",
            BaopuLongformPolicy.isLongform("# 标题\n\n一句话。"),
        )
        assertTrue(
            "标题＋300 字以上＝文件式内容",
            BaopuLongformPolicy.isLongform("# 标题\n\n" + "字".repeat(300)),
        )
    }

    @Test
    fun `触发-e 围栏代码块够 5 行_给卡；不足 5 行_不给`() {
        val code5 = "```kotlin\nfun a() = 1\nfun b() = 2\nfun c() = 3\nfun d() = 4\n```"
        val code2 = "```kotlin\nfun a() = 1\n```"
        assertTrue(BaopuLongformPolicy.isLongform(code5))
        assertFalse(BaopuLongformPolicy.isLongform(code2))
    }

    @Test
    fun `触发-f 命令行教学三行以上_给卡`() {
        val shell = "\$ adb devices\n\$ adb install -r app.apk\n\$ adb shell am start -n x/y"
        assertTrue(BaopuLongformPolicy.isLongform(shell))
    }

    @Test
    fun `触发-g 列表三项_不再给卡（2026-10-26 口径收紧）`() {
        assertFalse(BaopuLongformPolicy.isLongform("要点：\n- 甲\n- 乙\n- 丙"))
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
