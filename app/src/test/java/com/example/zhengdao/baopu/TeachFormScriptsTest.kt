// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 生产脚本的形状门禁（JVM 里跑不了 WebView，钉形状与红线）。
//
// 与 196 的 TeachScriptsTest 同一路子：语义行为由 FakeFormDom ＋ 判据测试覆盖，
// 这里钉的是**不该出现的东西**——尤其是「点一下」这条路（负例 1 的源码面）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachFormScriptsTest {

    private val single = TeachLocator(TeachLocatorKind.INPUT_NAME, "char_name")
    private val rich = TeachLocator(TeachLocatorKind.INPUT_NAME, "persona")
    private val select = TeachLocator(TeachLocatorKind.INPUT_NAME, "style")
    private val radio = TeachLocator(TeachLocatorKind.INPUT_NAME, "gender")

    private fun allScripts(): List<String> = listOf(
        FormScripts.SCAN,
        FormScripts.probe(listOf(single)),
        FormScripts.fill(listOf(single), FormControl.SINGLE_LINE, "小明", null),
        FormScripts.fill(listOf(rich), FormControl.RICH_TEXT, "一\n二", null),
        FormScripts.fill(listOf(select), FormControl.SELECT, "古风", "1"),
        FormScripts.fill(listOf(radio), FormControl.RADIO, "女", "f"),
        FormScripts.read(listOf(single), FormControl.SINGLE_LINE),
        FormScripts.read(listOf(rich), FormControl.RICH_TEXT),
        FormScripts.read(listOf(select), FormControl.SELECT),
        FormScripts.read(listOf(radio), FormControl.RADIO),
    )

    @Test
    fun `门禁_脚本里没有点击与快捷键_也没有eval`() {
        allScripts().forEach { s ->
            assertFalse("不许有 click 调用：${s.take(60)}", s.contains(".click("))
            assertFalse("不许有键盘事件：${s.take(60)}", s.contains("KeyboardEvent"))
            assertFalse("不许有 execCommand：${s.take(60)}", s.contains("execCommand"))
            assertFalse("不许有 eval：${s.take(60)}", s.contains("eval("))
            assertFalse("不许提交表单：${s.take(60)}", s.contains("requestSubmit") || s.contains(".submit("))
        }
    }

    @Test
    fun `门禁_填动作唯一命中才动手_单选例外`() {
        val s = FormScripts.fill(listOf(single), FormControl.SINGLE_LINE, "小明", null)
        assertTrue(s.contains("els.length!=1"))
        val g = FormScripts.fill(listOf(radio), FormControl.RADIO, "女", "f")
        assertTrue("单选是一组：允许命中多个", g.contains("els.length<1"))
        assertFalse(g.contains("els.length!=1"))
    }

    @Test
    fun `门禁_填完要发input与change事件`() {
        val s = FormScripts.fill(listOf(single), FormControl.SINGLE_LINE, "小明", null)
        assertTrue(s.contains("dispatchEvent(new Event('input'"))
        assertTrue(s.contains("dispatchEvent(new Event('change'"))
        // 用原型上的 setter：直接赋 value 会被 React 这类受控组件忽略
        assertTrue(s.contains("getOwnPropertyDescriptor"))
    }

    @Test
    fun `门禁_富文本按行分段塞结构_不是纯文本`() {
        val s = FormScripts.fill(listOf(rich), FormControl.RICH_TEXT, "一\n二", null)
        assertTrue("要按行切", s.contains("split("))
        assertTrue("要生成段落结构", s.contains("<p>"))
        assertTrue("要转义尖括号", s.contains("&lt;"))
        assertTrue("textarea 走 value", s.contains("TEXTAREA"))
    }

    @Test
    fun `门禁_扫描覆盖五类控件`() {
        val s = FormScripts.SCAN
        assertTrue(s.contains("input,textarea,select,[contenteditable]"))
        assertTrue("下拉要带选项表", s.contains("el.options"))
        assertTrue("单选要带同组选项", s.contains("getElementsByName"))
        assertTrue("要报可见性", s.contains("vis:"))
        assertTrue("要报只读/禁用", s.contains("disabled") && s.contains("ro:"))
    }

    @Test
    fun `门禁_探针逐档找_第一个有命中的档胜出`() {
        val p = FormScripts.probe(listOf(single, TeachLocator(TeachLocatorKind.TESTID, "char-name")))
        assertTrue(p.contains("hits.length>0"))
        assertTrue("要带回 tag", p.contains("tagName"))
        assertTrue("要带回 contenteditable", p.contains("contenteditable"))
    }

    @Test
    fun `门禁_值一律走转义嵌入_带引号的值进不了选择器`() {
        val evil = "小明\"']); alert(1); //"
        val s = FormScripts.fill(listOf(single), FormControl.SINGLE_LINE, evil, null)
        assertFalse("裸引号不许出现在脚本里：$s", s.contains(evil))
        // CSS 属性档遇到引号/空白直接短路成不可能值（运行时 0 命中）
        val loc = TeachLocator(TeachLocatorKind.INPUT_NAME, "x\"]alert(1)//")
        val probe = FormScripts.probe(listOf(loc))
        assertTrue(probe.contains("SKIP-IMPOSSIBLE"))
    }

    @Test
    fun `门禁_所有脚本都返回JSON字符串`() {
        allScripts().forEach { s ->
            assertTrue("要 stringify：${s.take(40)}", s.contains("JSON.stringify") || s.contains("return h"))
        }
    }
}
