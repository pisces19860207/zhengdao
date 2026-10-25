// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 待发布卡的**渲染层门禁**（负例 1 的 UI 面）。
//
// 为什么用源码级断言：本模块没有 Compose UI 测试基建（无 Robolectric），与
// ContainerTierGuardTest / ContrastGuardTest 同惯例——判据取文本、断言可执行。
//
// 钉四件事：
// 1. 待发布卡上只有两个动作（复制全文 / 打开页面）；本文件里没有第三个 onClick；
// 2. 卡整体走四档容器的行动卡（不新造第五种卡）；
// 3. 触控热区 ≥48dp（139 批口径）、圆角走令牌（不裸写 dp）；
// 4. 文案里不出现「替你点创建」这类反向暗示（按钮文案指向「你自己点」）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormFillSheetGateTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile } ?: throw AssertionError("找不到 $rel")
    }

    private val src: String by lazy { repoFile("$DIR/FormFillSheet.kt").readText() }

    /** 取某个顶层 Composable 的花括号正文（朴素配对，够用）。 */
    private fun funBody(name: String): String {
        val start = src.indexOf("fun $name(")
        if (start < 0) throw AssertionError("找不到 fun $name")
        val open = src.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("花括号不配对：$name")
    }

    @Test
    fun `门禁_待发布卡只有两个动作`() {
        val body = funBody("FormFillPublishCard")
        val clicks = Regex("""onClick\s*=\s*\{""").findAll(body).count()
        assertEquals("待发布卡只许两个 onClick（复制全文 / 打开页面），实际 $clicks 个", 2, clicks)
        assertTrue("复制全文要连 onCopy", body.contains("onCopy(card.copyText)"))
        assertTrue("打开页面要连 onOpen", body.contains("onOpen(card.openUrl)"))
        // 反向断言：卡里不许出现「填下一张/提交/创建」这类动作接线
        listOf("onNext", "onAdvance", "onSubmit", "onPublish", "onCreate", "onSave").forEach {
            assertFalse("待发布卡不许接 $it", body.contains(it))
        }
    }

    @Test
    fun `门禁_整卡走四档容器的行动卡`() {
        assertTrue("必须走 BaopuActionContainer（不新造卡型）", src.contains("BaopuActionContainer("))
        assertTrue("标题条要带徽标（等你点创建）", src.contains("form_publish_badge"))
    }

    @Test
    fun `门禁_触控热区不小于48dp`() {
        val buttons = Regex("""(Button|OutlinedButton|TextButton)\(""").findAll(src).count()
        val hot = Regex("""heightIn\(min = \d+\.dp\)""").findAll(src).count()
        assertTrue("按钮数量应被热区覆盖：按钮 $buttons / 热区 $hot", hot >= buttons)
        assertTrue("热区必须 ≥48dp", src.contains("heightIn(min = 48.dp)"))
        assertFalse("不许出现小于 48 的热区", Regex("""heightIn\(min = ([1-9]|[1-3]\d|4[0-7])\.dp\)""").containsMatchIn(src))
    }

    @Test
    fun `门禁_圆角走令牌不裸写`() {
        assertTrue("浮层圆角取 rSheet 令牌", src.contains("RoundedCornerShape(rSheet)"))
        assertFalse(
            "不许裸写圆角数字（139/137 批口径：圆角只有八档令牌）",
            Regex("""RoundedCornerShape\(\d+(\.\d+)?\.dp\)""").containsMatchIn(src),
        )
    }

    @Test
    fun `门禁_卡面讲清只填不点`() {
        assertTrue("要有「只填不点」的提示行", src.contains("form_publish_hint"))
        // 不允许出现「替你点」这类反向暗示（「你自己按」才是对的）
        assertFalse("不许出现「替你按」", src.contains("替你按"))
        assertFalse("不许出现「自动创建」", src.contains("自动创建"))
    }

    @Test
    fun `门禁_浮层两张流程按钮互斥`() {
        val body = funBody("FormFillSheet")
        assertTrue("有卡时才能点「已发布，下一张」", body.contains("enabled = state.card != null"))
        assertTrue("没卡时才能点「填下一张」", body.contains("enabled = !state.done && state.card == null"))
    }

    private companion object {
        const val DIR = "app/src/main/java/com/example/zhengdao/ui/baopu"
    }
}
