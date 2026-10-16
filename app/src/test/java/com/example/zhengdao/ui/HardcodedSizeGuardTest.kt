// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-8（2026-10-16）：写死尺寸守卫（后半）—— 顶栏用「最小高度」而不是「固定高度」，
 * 弹层尺寸用「上限」而不是「写死值」。
 *
 * 台账原条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * `- **B-补-8** **P2-9 写死尺寸只修了一半**：ui/HomeScreen.kt:66 BUTTON_MIN_WIDTH_DP = 108
 *  那六处已改（§A #21/#19），**48dp 顶栏 + 360 / 280 / 300dp 弹层宽度没改**`。
 *
 * 2026-10-16 逐条重定位（台账只写了数字、没写文件）：
 * - 「48dp 顶栏」＝四处**同款**顶栏（`Box(fillMaxWidth().height(48.dp))`）：
 *   `ui/SettingsCommon.kt` 的 `SettingsTopBar`（所有二级设置页共用）、`MainActivity.kt` 的
 *   `composable("settings")` 顶栏（与前者同源）、`ui/KeepaliveLogActivity.kt` 的
 *   `KeepaliveLogTopBar`、`ui/OpenSourceLicensesActivity.kt` 的 `LicensesTopBar`；
 * - 「280dp」＝`ui/SettingsCommon.kt` 工作区文件夹选择弹窗里的可滚动列表
 *   （`Column(Modifier.height(280.dp).verticalScroll(…))`）；
 * - 「360dp」＝`ui/taiji/ModelSheet.kt` 模型池列表（`LazyColumn(Modifier.height(360.dp))`）；
 * - 「300dp」＝`ui/taiji/TaijiScreen.kt` 历史抽屉（`ModalDrawerSheet(Modifier.width(300.dp))`）。
 *
 * 为什么这是缺陷：`height(48.dp)` 把**触摸目标下限**当成了**高度上限** —— 系统字号 ≥1.3× 时
 * 标题会被裁掉半行（B-补-22 同款病根）；`height(280/360.dp)`、`width(300.dp)` 写死值时，
 * 内容少会撑出空白，屏幕/窗口比它窄时（小屏、分屏、横屏窄窗）会被顶出可视区。
 *
 * 判据走「源码级取值 ＋ 断言」，与 [TouchTargetGuardTest] / [LargeFontWrapGuardTest] 同惯例；
 * 断言一律读**剥掉整行注释**后的源码 —— 本刀新增的注释里就写着 `height(48.dp)` / `height(280.dp)`
 * 这类反例词，直接对原文断言会把解释缺陷的注释当成缺陷本身。
 */
class HardcodedSizeGuardTest {

    private val settingsRel = "app/src/main/java/com/example/zhengdao/ui/SettingsCommon.kt"
    private val mainRel = "app/src/main/java/com/example/zhengdao/MainActivity.kt"
    private val keepaliveRel = "app/src/main/java/com/example/zhengdao/ui/KeepaliveLogActivity.kt"
    private val licensesRel = "app/src/main/java/com/example/zhengdao/ui/OpenSourceLicensesActivity.kt"
    private val modelSheetRel = "app/src/main/java/com/example/zhengdao/ui/taiji/ModelSheet.kt"
    private val taijiRel = "app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt"

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /**
     * 剥掉**注释**后的源码（先剥多行注释，再剥整行单行注释）；判据一律读它。
     *
     * 为什么两种都要剥：本刀新增的 KDoc 里就写着 `defaultMinSize(minHeight = 48.dp)`、
     * `height(48.dp)`、`height(280.dp)` 这类词 —— 只剥单行注释时，KDoc 里的正例会被算进
     * 「顶栏个数」（2026-10-16 首跑就是这样红的：期望 4 处、实得 5 处）。
     */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val settings by lazy { code(settingsRel) }
    private val main by lazy { code(mainRel) }
    private val keepalive by lazy { code(keepaliveRel) }
    private val licenses by lazy { code(licensesRel) }
    private val modelSheet by lazy { code(modelSheetRel) }
    private val taiji by lazy { code(taijiRel) }

    @Test
    fun `门禁_B补8_四个顶栏改用最小高度_不再写死48dp`() {
        // 顶栏的定高写法一律换成 defaultMinSize(minHeight = 48.dp)
        val oldForms = listOf(
            settingsRel to "Modifier.fillMaxWidth().height(48.dp)",
            mainRel to "Modifier.height(48.dp),",
            keepaliveRel to "Modifier.height(48.dp),",
            licensesRel to "Modifier.height(48.dp),",
        )
        oldForms.forEach { (rel, old) ->
            val src = code(rel)
            assertFalse("$rel 仍在写死顶栏高度（$old）", src.contains(old))
            assertTrue(
                "$rel 的顶栏必须用 defaultMinSize(minHeight = 48.dp)（48dp 是触摸目标下限，不是高度上限）",
                src.contains("defaultMinSize(minHeight = 48.dp)")
            )
        }
    }

    @Test
    fun `门禁_B补8_四个顶栏的高度下限都还是48dp_没有顺手缩水`() {
        val hits = listOf(settings, main, keepalive, licenses)
            .sumOf { Regex("""defaultMinSize\(minHeight = 48\.dp\)""").findAll(it).count() }
        assertEquals("四个顶栏应各有一处 defaultMinSize(minHeight = 48.dp)", 4, hits)
        listOf(settingsRel, mainRel, keepaliveRel, licensesRel).forEach { rel ->
            assertTrue("$rel 缺 defaultMinSize 的 import",
                code(rel).contains("import androidx.compose.foundation.layout.defaultMinSize"))
        }
    }

    @Test
    fun `门禁_B补8_文件夹选择弹窗列表用上限_不再写死280dp`() {
        assertTrue(
            "工作区文件夹选择弹窗的列表必须用 heightIn(max = 280.dp)（内容少时贴内容）",
            settings.contains("Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())")
        )
        assertFalse("SettingsCommon.kt 仍写死 height(280.dp)", settings.contains("Modifier.height(280.dp)"))
        assertTrue("SettingsCommon.kt 缺 heightIn 的 import",
            settings.contains("import androidx.compose.foundation.layout.heightIn"))
    }

    @Test
    fun `门禁_B补8_模型池列表用上限_不再写死360dp`() {
        assertTrue(
            "模型池列表必须用 LazyColumn(Modifier.heightIn(max = 360.dp))",
            modelSheet.contains("LazyColumn(Modifier.heightIn(max = 360.dp))")
        )
        assertFalse("ModelSheet.kt 仍写死 Modifier.height(360.dp)",
            modelSheet.contains("Modifier.height(360.dp)"))
        assertTrue("ModelSheet.kt 缺 heightIn 的 import",
            modelSheet.contains("import androidx.compose.foundation.layout.heightIn"))
    }

    @Test
    fun `门禁_B补8_历史抽屉宽度用上限_不再写死300dp`() {
        assertTrue(
            "历史抽屉必须用 Modifier.widthIn(max = 300.dp)（比 300dp 还窄的窗口不该被顶出去）",
            taiji.contains("Modifier.widthIn(max = 300.dp)")
        )
        assertFalse("TaijiScreen.kt 仍写死 Modifier.width(300.dp)",
            taiji.contains("Modifier.width(300.dp)"))
        assertTrue("TaijiScreen.kt 缺 widthIn 的 import",
            taiji.contains("import androidx.compose.foundation.layout.widthIn"))
    }

    @Test
    fun `门禁_B补8_台账点名的三类尺寸都留下可追溯的注释`() {
        // 注释是原始文档，不能剥 —— 这里专门读原文，确认每处改动都写清了「为什么改」
        val raw = listOf(settingsRel, mainRel, keepaliveRel, licensesRel, modelSheetRel, taijiRel)
            .joinToString("\n") { repoFile(it).readText() }
        assertTrue("改动处必须留下 B-补-8 的日期与理由注释（否则后人不知道 48dp 是下限而非上限）",
            raw.contains("B-补-8（2026-10-16）"))
        assertTrue("顶栏注释必须写明「48dp 是触摸目标下限，不是高度上限」",
            raw.contains("48dp 是**触摸目标下限**，不是高度上限"))
    }
}
