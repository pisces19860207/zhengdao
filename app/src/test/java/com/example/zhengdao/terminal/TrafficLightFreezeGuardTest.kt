// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 交通灯冻结守卫（第一百三十七批，设计语言 §15.1）。
 *
 * 用户 2026-10-22 11:30 原话：「终端的那三个交通灯不变，全部固定成这一套。」
 * ⇒ 全 App 换纸墨黛青时，**唯一豁免就是这三个圆点**，且豁免范围精确到文件：
 * 三个 drawable 的色值与 inset、`activity_main.xml` 里三枚 `<View>` 的 id/尺寸/指向。
 *
 * 为什么单独写成测试：派工单验收写的是「零 diff（`git diff --stat` 证明）」——
 * diff 只在这次提交里成立，**下一个人改的时候没人拦**。本测把它变成长期判据：
 * 谁动了这三个圆点（哪怕只是把 #FF5F57 换成纸墨的朱砂），当场红。
 *
 * 注意：**这不是"不许改"，是"改必须走另开的单"**（§15.1 原文：若要动其中任何一项，
 * 另开单并说明原因）。真到了要改的那天，改这里的期望值并在报告里写清原因即可。
 */
class TrafficLightFreezeGuardTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private fun drawable(name: String): String =
        repoFile("app/src/main/res/drawable/$name.xml").readText()

    private val layout: String
        get() = repoFile("app/src/main/res/layout/activity_main.xml").readText()

    @Test
    fun `门禁_三个圆点色值冻结`() {
        // mac 风格：红 #FF5F57 / 黄 #FFBC2E / 绿 #28C840。任何一个变样都是"终端换皮越界"。
        val expect = mapOf(
            "traffic_dot_red" to "#FFFF5F57",
            "traffic_dot_yellow" to "#FFFFBC2E",
            "traffic_dot_green" to "#FF28C840",
        )
        expect.forEach { (name, hex) ->
            assertTrue(
                "$name 的圆点色被改了（应为 $hex，§15.1 冻结项）",
                drawable(name).contains("android:color=\"$hex\""),
            )
        }
    }

    @Test
    fun `门禁_三个圆点的热区与视觉尺寸冻结`() {
        // 热区 30dp View ＋ 9.5dp inset ⇒ 视觉圆点 11dp（B-补-20 定的口径）。
        listOf("traffic_dot_red", "traffic_dot_yellow", "traffic_dot_green").forEach { name ->
            val xml = drawable(name)
            listOf("insetLeft", "insetRight", "insetTop", "insetBottom").forEach { side ->
                assertTrue("$name 的 $side 应为 9.5dp", xml.contains("android:$side=\"9.5dp\""))
            }
            assertTrue("$name 必须是 oval 圆点", xml.contains("android:shape=\"oval\""))
        }
    }

    @Test
    fun `门禁_布局里三枚圆点的 id 顺序与尺寸冻结`() {
        val close = layout.indexOf("@+id/btn_close")
        val yellow = layout.indexOf("@+id/btn_yellow")
        val split = layout.indexOf("@+id/btn_split")
        assertTrue("三枚圆点必须都在（关闭 / 收分屏 / 分屏）", close in 0 until yellow && yellow < split)
        listOf(
            "btn_close" to "traffic_dot_red",
            "btn_yellow" to "traffic_dot_yellow",
            "btn_split" to "traffic_dot_green",
        ).forEach { (id, target) ->
            val start = layout.indexOf("@+id/$id")
            val tail = layout.substring(start, (start + 900).coerceAtMost(layout.length))
            assertTrue("$id 必须仍指向 @drawable/$target", tail.contains("@drawable/$target"))
            assertTrue("$id 的 View 尺寸必须仍是 30dp", tail.contains("android:layout_width=\"30dp\"") &&
                tail.contains("android:layout_height=\"30dp\""))
        }
    }
}
