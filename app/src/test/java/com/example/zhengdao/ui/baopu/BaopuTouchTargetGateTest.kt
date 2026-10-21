// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19《UI 第二批 · 热区与静态截断》§1＋§4 判据 3/4。
// 钉的是「**可点区域**」而不是「画出来的大小」：本批的口径是视觉一个字不换，只动命中区。
//
// 为什么这些判据只能写在源码＋算术层（真机手感另说）：JVM 没有 Robolectric，
// Compose 的触摸判定跑不起来；所以这里把「框架已经扩到 48dp」与「框架扩不动的那两处」
// 都写成能从源码常量算出来的数——常量一改，算术就红。
//
// 框架事实（主证据， androidx.compose.ui 1.10.4，随 BOM 2026.09.00 打进来的那份 sources jar）：
//   - `ViewConfiguration.minimumTouchTargetSize` 默认 `DpSize(48.dp, 48.dp)`，
//     KDoc：「If layout has reduced the pointer input bounds below this, the touch target
//     will be expanded evenly around the layout to ensure that it is at least this big.」
//   - `NodeCoordinator.hitTest`：`PointerType.Touch` 落在节点外但在这个最小距离内 ⇒ `hitNear`；
//     `InnerNodeCoordinator.hitTestChild`：越过父级裁剪也照样继续测子节点。
//   - 同文件 KDoc 的优先级三条：**别人的直接命中赢过我的最小触摸扩展命中**。
//     ⇒ 一个 24dp 的 ⊕ 套在**自己可点的行**里，框架那 12dp 扩展是被行抢走的 ⇒ 真缺陷；
//     一个 28dp 的「＋」/16dp 的跑马灯/28dp 的「朗读」父级不可点 ⇒ 框架已经给到 48dp ⇒ 不改。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二批 A 段·热区（派工单 §1 五行 + §4 判据 3、4）。
 *
 * H1 抽屉 ⊕：唯一一处「框架救不了」的实改——48dp 命中盒、圆点仍 24dp、行尾 padding 补偿。
 * H2 行高算术：命中盒 48dp ⇒ 行高 ≥48dp（派工单要的 ≥48×48）。
 * H3 附件 ×：56dp 方块放不下 48dp 命中盒＋还能点的缩略图 ⇒ 本批**不改**，
 *    把算术钉住（要改就得先动视觉，那是另一单）。
 * H4 朗读：判定为「框架已达标」⇒ 门禁断言保留 28dp 视觉、不许偷偷套 48dp。
 * H5 跑马灯：窗口仍 16dp（滚动参数不动、顶栏不撑高）⇒ 不改。
 */
class BaopuTouchTargetGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码（房型照 [BaopuCollapseAffordanceTest]）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 空白压平：源码换行／缩进改了不算门禁红。 */
    private fun flat(s: String): String = s.replace(Regex("\\s+"), " ")

    private fun drawer(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun composer(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private fun list(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    private fun ticker(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")

    /**
     * 从源码里把一个尺寸常量读出来（读数绑定到具体文件，不靠记忆）。
     * 两种房型都认：`= 48.dp`（本批之前就有的）与 `= 48`（本批新加的 Int 常量，
     * 因为行尾 padding 要走算术，得先当 Int 用）。
     */
    private fun dpValue(src: String, name: String): Int {
        val m = Regex("$name\\s*=\\s*(\\d+(?:\\.\\d+)?)(?:\\.dp)?").find(src)
        assertTrue("源码里找不到常量 $name（被改名或删掉了？）", m != null)
        return m!!.groupValues[1].toFloat().toInt()
    }

    // ── H1／H2：抽屉 ⊕ ──────────────────────────────────────────────────────

    @Test
    fun `H1_抽屉⊕套了48dp命中盒_圆点仍是24dp`() {
        val src = drawer()
        assertEquals("⊕ 的命中盒必须 48dp（派工单 §1）", 48, dpValue(src, "RUN_HERE_TOUCH_DP"))
        assertEquals("⊕ 的视觉圆点一个字不许改（纪律：只扩大命中区）", 24, dpValue(src, "RUN_HERE_GLYPH_DP"))
        val flatSrc = flat(src)
        assertTrue(
            "48dp 那一层必须是可点的那一层（clickable 直接挂在命中盒上）",
            flatSrc.contains("Modifier .size(RUN_HERE_TOUCH_DP.dp) .clickable { onRunHere(node) }"),
        )
        assertTrue(
            "24dp 那一层只画圆（border 还在原处），不许把 border 挪到 48dp 上",
            flatSrc.contains(".size(RUN_HERE_GLYPH_DP.dp) .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)"),
        )
        assertFalse(
            "旧的「24dp 直接 clickable」写法不许还留着（留着就是两处命中盒）",
            flatSrc.contains(".size(24.dp) .border"),
        )
    }

    @Test
    fun `H2_行高与行尾补偿算得过来`() {
        val src = drawer()
        val touch = dpValue(src, "RUN_HERE_TOUCH_DP")
        val glyph = dpValue(src, "RUN_HERE_GLYPH_DP")
        // 行高 = 命中盒（行内最高的那个孩子）＋上下 padding（本批把 9/9 收掉，让命中盒自己当呼吸位）
        val rowHeight = touch + 0 + 0
        assertTrue("工作区行必须给到 ≥48dp 的可点高度，实际 $rowHeight", rowHeight >= 48)
        // 行尾补偿：圆点还画在原来的位置上（旧 end=16dp，盒子左右各多出 (48−24)/2 = 12dp）
        assertEquals(
            "行尾 padding 必须按算术补偿，否则 ⊕ 会往左挪 12dp（视觉位置就变了）",
            4,
            runHereRowEndPaddingDp(OLD_WORKSPACE_ROW_END_DP, touch, glyph),
        )
        assertTrue(
            "源码里的行尾 padding 要跟算术一致",
            flat(src).contains("end = runHereRowEndPaddingDp(OLD_WORKSPACE_ROW_END_DP, RUN_HERE_TOUCH_DP, RUN_HERE_GLYPH_DP).dp"),
        )
        assertFalse(
            "旧的 top/bottom = 9dp 不许留着（留着行高就变 66dp，比现在胖 24dp）",
            flat(src).contains("top = 9.dp, bottom = 9.dp"),
        )
    }

    // ── H3：附件 ×（本批不改，钉住取舍的算术）──────────────────────────────────

    @Test
    fun `H3_缩略图放不下48dp的移除盒_算术钉住不许静默缩小`() {
        val src = composer()
        val chip = dpValue(src, "ATTACH_THUMB_DP")
        val remove = dpValue(src, "ATTACH_REMOVE_DP")
        assertEquals("缩略图方块视觉不许动", 56, chip)
        assertEquals("× 的视觉圆不许动（也不许偷偷放大成 48dp）", 28, remove)
        // 56 的方块里套 48 的命中盒 ⇒ 只剩 8dp 宽的 L 形边给「点缩略图看路径」
        val band = thumbTapBandDp(chipDp = chip, hitDp = 48)
        assertEquals(8, band)
        assertTrue(
            "剩下的边 $band dp 远小于 48dp ⇒ 48dp 移除盒会废掉缩略图自己的点击，本批按派工单停下来报告",
            band < 48,
        )
        assertFalse(
            "本批不许给 × 套 48dp 布局盒（那是「静默改掉缩略图」，不是「扩大命中区」）",
            flat(src).contains(".size(48.dp) .clickable(onClick = onRemove)") ||
                flat(src).contains("defaultMinSize(48.dp) .clickable(onClick = onRemove)"),
        )
    }

    // ── H4：朗读（判定＝框架已达标 ⇒ 不改）───────────────────────────────────

    @Test
    fun `H4_朗读按钮保留28dp视觉_不许偷偷套48dp`() {
        val src = list()
        val flatSrc = flat(src)
        assertTrue("「朗读」仍走 TextButton（判定为不改）", flatSrc.contains("TextButton( onClick = { onSpeak(speakable) }"))
        assertTrue(
            "视觉高度仍是 28dp（纪律：视觉尺寸一个字不换）",
            Regex("Modifier\\s*\\.padding\\(top = 2\\.dp\\)\\s*\\.height\\(28\\.dp\\)").containsMatchIn(src),
        )
        assertFalse(
            "既然判「不改」，就不许给这一处新增 48dp 布局（那会改行高）",
            flatSrc.contains("height(48.dp)") || flatSrc.contains("defaultMinSize(minHeight = 48.dp)"),
        )
    }

    // ── H5：跑马灯（不改）────────────────────────────────────────────────────

    /**
     * H5（第一百零五批改判据）：窗口**默认**仍是 16dp，但大字体下随行高增长。
     *
     * 为什么必须改：v1.4 §3.9 与交接提示都点名了——旧判据把 16dp 钉成常量，
     * 而 1.3× 字体下 labelSmall 的行高是 13sp×1.3≈16.9dp ⇒ 字被裁掉一条（真机只有
     * 「系统字体调大」才暴露）。新判据＝「默认 16dp、大字体 ≤ 一行行高」，两件都不许破：
     * 破了第一件顶栏被撑高／露出第二条，破了第二件就是大字体裁字。
     */
    @Test
    fun `H5_跑马灯窗口默认16dp_大字体随行高_滚动参数没动`() {
        val src = ticker()
        assertEquals("默认窗口高度不许改（改了就看到第二条／撑顶栏）", 16, dpValue(src, "TICKER_HEIGHT_DP"))
        assertEquals("每帧步长不许改（派工单：滚动参数不动）", 0.3f, src.toFloatConst("SCROLL_STEP_PX"))
        assertEquals("1.0× 仍是 16dp", 16f, tickerWindowDp(1.0f), 0.01f)
        assertTrue("1.3× 必须长到一行行高（16.9dp），否则裁字", tickerWindowDp(1.3f) > 16f)
        assertTrue("大字体也不许超过一行行高（超了就露半截）", tickerWindowDp(1.3f) <= 13f * 1.3f + 0.01f)
        assertFalse("不许给窗口套 48dp", flat(src).contains("height(48.dp)"))
    }

    private fun String.toFloatConst(name: String): Float {
        val m = Regex("const val $name = ([0-9.]+)f").find(this)
        assertTrue("源码里找不到常量 $name", m != null)
        return m!!.groupValues[1].toFloat()
    }
}
