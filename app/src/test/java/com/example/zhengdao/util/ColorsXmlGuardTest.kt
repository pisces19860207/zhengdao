package com.example.zhengdao.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4-6（issue #17）：`res/values/colors.xml` 模板 8 色的守卫。
 *
 * 审计原文：「`res/values/colors.xml` 模板 8 色仍在（`res/layout/window_bg.xml`
 * 已不存在）」——2026-10-14 实测复现：purple_200/500/700、teal_200/700、black、
 * white 八个 Android Studio 新建工程自带的色**全仓零引用**（layout / manifest /
 * mipmap / Kotlin 都 grep 过），`window_bg.xml` 确实已不在。
 *
 * 已删（本刀）。本守卫防复活：模板色名不许再出现；三个真色必须在。
 */
class ColorsXmlGuardTest {

    private val templateColors = listOf(
        "purple_200", "purple_500", "purple_700",
        "teal_200", "teal_700", "black", "white",
    )

    private val realColors = listOf(
        "ic_launcher_background", // 启动图标背景（mipmap-anydpi/ic_launcher*.xml）
        "term_key_active",        // 终端功能键·激活态（TerminalActivity）
        "term_key_idle",          // 终端功能键·空闲态（TerminalActivity）
    )

    @Test
    fun `colors_xml_不许有模板色`() {
        val xml = File("src/main/res/values/colors.xml").readText()
        val leftovers = templateColors.filter { xml.contains("name=\"$it\"") }
        assertTrue("模板色又回来了：$leftovers（P4-6 已删，别复活）", leftovers.isEmpty())
    }

    @Test
    fun `colors_xml_真色必须在`() {
        val xml = File("src/main/res/values/colors.xml").readText()
        val missing = realColors.filter { !xml.contains("name=\"$it\"") }
        assertTrue("真色被误删：$missing", missing.isEmpty())
    }

    @Test
    fun `真色确实有引用_删的不是死色`() {
        val root = File("src/main")
        realColors.forEach { name ->
            // 两种引用形态都算：XML 的 @color/NAME 与 Kotlin 的 R.color.NAME
            val refs = root.walkTopDown()
                .filter { it.isFile && (it.extension == "xml" || it.extension == "kt") && it.name != "colors.xml" }
                .count { f ->
                    val t = f.readText()
                    t.contains("@color/$name") || t.contains("R.color.$name")
                }
            assertTrue("$name 在 colors.xml 里却没有引用（= 死色，该删的是它）", refs > 0)
        }
    }
}
