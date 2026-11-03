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
    fun `触发-b 字数过阈值_不再给卡（407 字数触发取消）`() {
        val long = "字".repeat(601)
        assertFalse(
            "字数不再是出卡理由（333 字实证出过卡，用户口径：卡只装文件与命令行）",
            BaopuLongformPolicy.isLongform(long),
        )
        // 但话题批收稿保留含字数旧口径（isArticleLike），防既有落稿功能回退
        assertTrue(BaopuLongformPolicy.isArticleLike(long))
        // 用户点名要长文时，多短都给卡（另一条通道）
        assertTrue(BaopuLongformPolicy.isLongform("就一句话。", userText = "帮我写篇文章"))
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
    fun `触发-e 命令行系围栏_给卡；kotlin与无标签围栏_不给（407 收紧）`() {
        val bash6 = "```bash\ncd _401m7\nls -la\ncat 汇总.md\nmv a.md 明细/\nrm b.md\npwd\n```"
        assertTrue("6 行 bash ⇒ 给卡（判据②）", BaopuLongformPolicy.isLongform(bash6))
        val bash2 = "```bash\nls\npwd\n```"
        assertFalse("命令行不足 3 行不给卡", BaopuLongformPolicy.isLongform(bash2))
        val kotlin5 = "```kotlin\nfun a() = 1\nfun b() = 2\nfun c() = 3\nfun d() = 4\n```"
        assertFalse("kotlin 代码不是命令行（407：卡只装文件与命令行）", BaopuLongformPolicy.isLongform(kotlin5))
        val tree = "```\nDownload/_401m7/\n  汇总.md 30 B\n  明细/\n    m7a.md 4 B\n    m7b.md 4 B\n```"
        assertFalse(
            "无标签目录树围栏不给卡（407 实证：333 字普通回复的真凶就是它）",
            BaopuLongformPolicy.isLongform(tree),
        )
    }

    @Test
    fun `触发-f 命令行教学三行以上_给卡`() {
        val shell = "\$ adb devices\n\$ adb install -r app.apk\n\$ adb shell am start -n x/y"
        assertTrue(BaopuLongformPolicy.isLongform(shell))
    }

    @Test
    fun `触发-f2 引用与井号行首_不再算命令行（407 漏口②收口）`() {
        val quoted = "> 引用一句\n> 再引一句\n> 第三句引用"
        assertFalse("行首 > 是引用块不是命令行", BaopuLongformPolicy.isLongform(quoted))
        val hashes = "# 标题一\n# 标题二\n# 标题三"
        assertFalse(
            "行首 # 是标题不是命令行（短文本不该因三行标题出卡）",
            BaopuLongformPolicy.isLongform(hashes),
        )
    }

    @Test
    fun `触发-h 407 现场 333 字普通回复_不出卡（真机库 msg66 原文回归）`() {
        val msg66 = """五项全部做完了：`_401m7` 现在有 `汇总.md` 和 `明细/`（里面装着 m7a–m7e）。

```
Download/_401m7/
  汇总.md       30 B · 7 行
  明细/
    m7a.md      4 B
    m7b.md      4 B
    m7c.md      4 B
    m7d.md      4 B
    m7e.md      4 B
```

- 五个文件各写一行自己的名字（`m7a` 到 `m7e`），没有多余内容
- `汇总.md` 是 `# 汇总` + 一个空行 + a 到 e 五行名字，顺序没错
- 五个零散文件都进了 `明细/`，根目录下只剩合并稿和子文件夹

清单的五个步骤现在都打上了勾。之前两次更新没能生效；这次只更新了勾选状态，条目文字没有改动，才最终通过。

还有一件事等你决定：`_401m3` 那三份占位稿怎么合并。如果要合并，请回复「按 1，放原地，三份留着」这种格式即可，我立刻做。"""
        assertEquals(333, BaopuLongformPolicy.wordCount(msg66))
        assertFalse(
            "判据①：333 字普通回复不出卡（无标签围栏曾误触发）",
            BaopuLongformPolicy.isLongform(msg66),
        )
    }

    @Test
    fun `触发-i 文件式_标题必须在开头_中间夹标题不算（407 漏口①收口）`() {
        val midHeading = "前面先说结论，这事办完了。\n\n" + "# 中间标题\n\n" + "字".repeat(320)
        assertFalse(
            "正文中间夹一行 # 的普通回复不是文件",
            BaopuLongformPolicy.isLongform(midHeading),
        )
        assertTrue(
            "开头就是标题＋够 300 字＝文件式（判据③）",
            BaopuLongformPolicy.isLongform("# 标题\n\n" + "字".repeat(300)),
        )
        assertTrue(BaopuLongformPolicy.isFileLike("# 标题\n\n" + "字".repeat(300)))
        assertFalse(BaopuLongformPolicy.isFileLike(midHeading))
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

    @Test
    fun `标题-c 纯命令行消息_标题取第一条命令_不是围栏标记（407 判据②）`() {
        val bash6 = "```bash\ncd _401m7\nls -la\ncat 汇总.md\nmv a.md 明细/\nrm b.md\npwd\n```"
        assertEquals("cd _401m7", BaopuLongformPolicy.cardTitle(bash6))
        assertEquals("无标题", BaopuLongformPolicy.cardTitle("```\n```"))
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
