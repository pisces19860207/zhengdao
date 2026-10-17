// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.ui.baopu.BaopuIntents
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 静态快捷方式门禁（刀 2 D 段第 1 条；判据 §5.9）。
 *
 * 钉死派工单原话级口径：条数 ≤4、标签约 10 字内、action 字面值与
 * `BaopuIntents.ACTION_BACKUP` 共认（两处漂了用户点图标就没反应）、
 * intent 显式（targetPackage + targetClass）⇒ 不需要往 Manifest 加
 * intent-filter（权限段与组件声明零改动）。
 *
 * 真解析 XML（不 grep 文本）——注释掉的条目不该算数，属性换个序不该误报。
 */
class ShortcutsXmlTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun shortcuts(): List<Element> {
        val f = appFile("src/main/res/xml/shortcuts.xml")
        assertTrue("shortcuts.xml 必须存在", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("shortcut")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `条数不超过4`() {
        val n = shortcuts().size
        assertTrue("静态快捷方式最多 4 条（当前 $n）", n in 1..4)
    }

    @Test
    fun `标签在10字内且走字符串资源`() {
        val zh = appFile("src/main/res/values/strings.xml").readText()
        val en = appFile("src/main/res/values-en/strings.xml").readText()
        for (s in shortcuts()) {
            val shortLabel = s.getAttribute("android:shortcutShortLabel")
            assertTrue("短标签必须走字符串资源：$shortLabel", shortLabel.startsWith("@string/"))
            val key = shortLabel.removePrefix("@string/")
            val value = Regex("name=\"$key\"[^>]*>([^<]*)<").find(zh)?.groupValues?.get(1)
            assertTrue("strings.xml 缺 $key", value != null)
            assertTrue("短标签「$value」超过 10 字", (value ?: "").length <= 10)
            assertTrue("values-en 也必须有 $key（双语一致）", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `长标签也存在且双语都在`() {
        val zh = appFile("src/main/res/values/strings.xml").readText()
        val en = appFile("src/main/res/values-en/strings.xml").readText()
        for (s in shortcuts()) {
            val longLabel = s.getAttribute("android:shortcutLongLabel")
            assertTrue("长标签必须走字符串资源：$longLabel", longLabel.startsWith("@string/"))
            val key = longLabel.removePrefix("@string/")
            assertTrue("strings.xml 缺 $key", zh.contains("name=\"$key\""))
            assertTrue("values-en 缺 $key", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `action与代码共认一个字面值`() {
        for (s in shortcuts()) {
            val intent = s.getElementsByTagName("intent").item(0) as Element
            assertEquals(
                "shortcuts.xml 的 action 必须与 BaopuIntents.ACTION_BACKUP 一致",
                BaopuIntents.ACTION_BACKUP,
                intent.getAttribute("android:action"),
            )
        }
    }

    @Test
    fun `intent显式_不依赖Manifest过滤器`() {
        val manifest = appFile("src/main/AndroidManifest.xml").readText()
        for (s in shortcuts()) {
            val intent = s.getElementsByTagName("intent").item(0) as Element
            assertEquals("com.example.zhengdao", intent.getAttribute("android:targetPackage"))
            assertEquals("com.example.zhengdao.MainActivity", intent.getAttribute("android:targetClass"))
        }
        // 显式 intent 不需要过滤器 ⇒ Manifest 不许为它加 intent-filter（权限段零改动）
        assertFalse(
            "不许往 Manifest 里加 BAOPU_BACKUP 的 intent-filter",
            manifest.contains(BaopuIntents.ACTION_BACKUP),
        )
    }

    @Test
    fun `备份入口id固定且enabled`() {
        val ids = shortcuts().map { it.getAttribute("android:shortcutId") }
        assertTrue(ids.contains("baopu_backup_originals"))
        shortcuts().forEach { s ->
            assertEquals("true", s.getAttribute("android:enabled"))
            assertTrue(s.getAttribute("android:shortcutId").isNotBlank())
        }
    }

    @Test
    fun `shortcuts文件不许写死自然语言`() {
        val raw = appFile("src/main/res/xml/shortcuts.xml").readText()
        // 中文/英文文案一律走字符串资源（values / values-en 各一份）
        val body = raw.substringAfter("<shortcuts")
        assertFalse("不许在 xml 里写死中文", body.any { it.code in 0x4E00..0x9FFF })
    }
}
