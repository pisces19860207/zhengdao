// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百三十一批 · UI-D5 门禁（源码/资源级）。
//
// 真机现象（qa-2026-10-21-all/11-English-首屏.png）：English 下设置首页那一行显示
// `For agents inside the te…`——省略号把整句吃掉了。派工单 §B 要的结果是
// 「英文副标题完整显示或换行，中文版面不变」，于是这里钉三件事：
//   J5-struct 该行显式要了两行（`valueMaxLines = 2`），且英文串本身是完整句、不带省略号；
//   J6-struct 共享行组件的默认值仍是 1（中文版面一个像素都不动）；
//   J7-struct 多行时不走 Ellipsis（换成 Clip），否则两行照样可能被截字。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets

class SettingsBridgeSubtitleTest {

    private val screen: String by lazy {
        File("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt").readText(Charsets.UTF_8)
    }
    private val en: String by lazy {
        File("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)
    }
    private val zh: String by lazy {
        File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    }

    /** J5：英文串是完整句（不带省略号），且中文串没被顺手改短。 */
    @Test
    fun `J5_英文副标题是完整句`() {
        val enValue = stringValue(en, "settings_value_bridge")
        assertEquals("For agents inside the terminal", enValue)
        assertTrue("英文副标题不许带省略号（真机被截断的就是它）", !enValue.contains("…") && !enValue.contains("..."))
        val zhValue = stringValue(zh, "settings_value_bridge")
        assertEquals("中文副标题不许动（版面不变）", "给终端里的 agent 用", zhValue)
    }

    /** J5-struct：浏览器桥那一行显式传 `valueMaxLines = 2`。 */
    @Test
    fun `J5_浏览器桥行显式要两行`() {
        val at = screen.indexOf("R.string.settings_bridge")
        assertTrue("设置页必须还有浏览器桥这一行", at >= 0)
        val row = screen.substring(at, minOf(screen.length, at + 320))
        assertTrue(
            "这一行必须传 valueMaxLines = 2，否则英文整句仍被一行截断：\n$row",
            row.contains("valueMaxLines = 2"),
        )
    }

    /** J6-struct：共享行组件的默认值仍是 1 行（中文与其它行不受影响）。 */
    @Test
    fun `J6_共享行默认仍是一行`() {
        assertTrue(
            "SettingRow 的 valueMaxLines 默认值必须是 1（只给长句那一行开口子）",
            screen.contains("valueMaxLines: Int = 1"),
        )
    }

    /** J7-struct：>1 行时不用 Ellipsis（用 Clip），否则两行也会被省略号截字。 */
    @Test
    fun `J7_多行时不走省略号`() {
        assertTrue(
            "多行分支必须避开 TextOverflow.Ellipsis",
            screen.contains("overflow = if (valueMaxLines > 1) TextOverflow.Clip else TextOverflow.Ellipsis"),
        )
    }

    private fun stringValue(xml: String, name: String): String {
        val m = Regex("<string name=\"$name\">([\\s\\S]*?)</string>").find(xml)
        return m?.groupValues?.get(1)?.trim() ?: error("strings.xml 里找不到 $name")
    }
}
