// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

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
            card.contains("heightIn(min = 48.dp)"),
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
    fun `红线-e 接线：长文走卡_短回复保底有手动入口`() {
        val list = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")
        assertTrue("长文助手消息必须渲染 BaopuLongformCard", list.contains("BaopuLongformCard(assistantText)"))
        assertTrue("普通短回复要有手动「文本卡」入口", list.contains("baopu_longform_open_as_card"))
        assertTrue("触发判据必须走 LongformPolicy（不许在列表里内联写触发条件）", list.contains("BaopuLongformPolicy.isLongform"))
    }
}
