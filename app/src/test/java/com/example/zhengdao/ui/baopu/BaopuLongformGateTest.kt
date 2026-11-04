// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十九批 · **接线与红线**门禁。
 *
 * 纯判据在 [BaopuLongformPolicyTest]；这里钉四条"判据对、但别在接线上走样"的红线：
 * ① 保存**平铺**在 `产物/`，不许出现 `文本/` 第三层；
 * ② 同名不覆盖（走 nextFileName）；
 * ③ 大复制按钮 ≥48dp 触控；
 * ④ 渲染用现成 mikepenz 渲染器（不许自写 markdown 解析器）。
 */
class BaopuLongformGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `红线-a 保存平铺_不许出现文本第三层`() {
        val store = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformStore.kt")
        assertFalse(
            "不许加 文本/ 第三层（LibraryTools 只认 投递/ 与 产物/ 两级）",
            store.contains("产物/文本") || store.contains("\"文本\""),
        )
        assertTrue("必须挂在工作区根的 产物/ 下", store.contains("BaopuRuntime.workspaceRoot"))
        val policy = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformPolicy.kt")
        assertTrue("目录名必须是 产物（常量 PRODUCT_DIR）", policy.contains("const val PRODUCT_DIR = \"产物\""))
    }

    @Test
    fun `红线-b 同名不覆盖_走 nextFileName`() {
        val store = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformStore.kt")
        assertTrue("保存前必须先过 nextFileName（同名加 -2/-3，不许覆盖）", store.contains("nextFileName"))
        assertTrue("nextFileName 的实现必须真的检查现有文件", store.contains("dir.list()"))
    }

    @Test
    fun `红线-c 大复制按钮至少 48dp 触控`() {
        val card = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformCard.kt")
        assertTrue(
            "复制全文大按钮必须 heightIn(min = 48.dp)",
            card.contains("Modifier.weight(1.4f).heightIn(min = 48.dp)"),
        )
    }

    @Test
    fun `红线-d 渲染用现成 mikepenz_不许自写解析器`() {
        val card = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformCard.kt")
        assertTrue("必须用 com.mikepenz.markdown.m3.Markdown", card.contains("com.mikepenz.markdown.m3.Markdown"))
        assertFalse(
            "不许自写 markdown 解析器（渲染归 mikepenz；toPlainText 只是展示变换）",
            card.contains("class.*MarkdownParser") || card.contains("fun parseMarkdown"),
        )
    }

    @Test
    fun `红线-e 接线：长文走卡_一条消息最多一张_手动文本卡入口已删`() {
        val list = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")
        // 209：长文卡带 messageId（折叠态按消息 id 记住）——调用形状变了，门禁跟着钉
        assertTrue(
            "长文助手消息必须渲染 BaopuLongformCard 且带上 msg.id（折叠态按 id 记）",
            list.contains("BaopuLongformCard(assistantText, messageId = msg.id.toString())"),
        )
        // 407 判据⑤：「文本卡」按钮删掉（它与自动卡重复，且曾把同一条消息叠成两张卡）
        assertFalse(
            "「文本卡」手动入口必须删干净（源码与文案都不许再出现）",
            list.contains("baopu_longform_open_as_card") || list.contains("manualCard"),
        )
        // 407 判据④：一条消息最多一张卡＝BaopuLongformCard 在列表里只装配一次（自动卡），
        // 手动卡装配口已删——装两次就会叠两张。负例：改动前此处是 2。
        val cardAssemblies = Regex("BaopuLongformCard\\(").findAll(list).count()
        assertEquals("同一条消息只许有一个卡装配口", 1, cardAssemblies)
        // 407 判据⑤负例护栏：朗读保留（删错行会把朗读一起删掉）
        assertTrue("「朗读」按钮必须保留", list.contains("R.string.baopu_speak"))
        assertTrue("触发判据必须走 LongformPolicy（不许在列表里内联写触发条件）", list.contains("BaopuLongformPolicy.isLongform"))
    }

    @Test
    fun `红线-f 407_文案与策略收紧自检_按钮文案删除_策略有负例`() {
        val zh = src("src/main/res/values/strings.xml")
        val en = src("src/main/res/values-en/strings.xml")
        assertFalse("中文文案不许再有「文本卡」按钮", zh.contains("baopu_longform_open_as_card"))
        assertFalse("英文文案不许再有 Text card 按钮", en.contains("baopu_longform_open_as_card"))
        val policy = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformPolicy.kt")
        // 触发收紧的形状：isLongform 里不许再读字数阈值（字数只留给 isArticleLike 话题批收稿）
        val longformBody = policy.substringAfter("fun isLongform(").substringBefore("fun isFileLike")
        assertFalse("聊天卡入口不许再按字数触发", longformBody.contains("LENGTH_THRESHOLD"))
        assertTrue("话题批收稿保留含字数旧口径（isArticleLike）", policy.contains("fun isArticleLike"))
    }
}
