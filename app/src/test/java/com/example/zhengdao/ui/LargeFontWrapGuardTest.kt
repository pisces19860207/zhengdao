// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-22（2026-10-16）：大字体换行守卫 —— 系统字号放大后，设置页的行必须**换行而不是截断**。
 *
 * 台账原条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * `- **B-补-22**（R3 / P3-2）大字体换行：ui/SettingsScreen.kt:605-630 / :952-962 / :1544-1558 的
 *  Row 不换行`，2026-10-16 复核记「行号已随 §A #47 拆分迁移：三段分别在
 *  `ui/SettingsStorageActivity.kt`「缓存清理」/ `ui/SettingsDataActivity.kt`「权限」/
 *  `ui/SettingsEnvActivity.kt`「运行内存上限」三张卡内」。
 *
 * 2026-10-16 逐条复核（**行号又漂了**，改按组件定位）：
 * - 台账那三处（本轮实际落在 `:338` / `:485` / `:436`）**对不上** —— 分别是安装脚本说明 `Text`、
 *   芯片横向滚动行、安装进度状态 `Text`，都不是「标签—数值」行；
 * - 缓存清理卡（Storage）与权限卡（Data）真正渲染的是共享组件 `ui/SettingsScreen.kt` 的
 *   `InfoRow` / `SettingRow`（`InfoRow(name, "$mb MB")`、`SettingRow(data_perm_storage, …)`）
 *   ⇒ **缺陷在共享组件里**；
 * - 运行内存上限卡（Env）不是标签—数值行，而是三个 `FilterChip2` 挤在一个**裸 `Row`** 里
 *   （1.3× 以上第三个芯片被挤出卡片右沿裁掉）⇒ 改成可折行的 `FlowRow`；
 * - 共享组件当时的写法：标签 `maxLines = 1` + `softWrap = false`（**大字体下硬截断**）、
 *   `SettingRow` 的状态文字 `maxLines = 1` + `TextOverflow.Ellipsis`（**大字体下变省略号**）——
 *   这正是「大字体不换行」；
 * - 本刀修法：字号 ≥ [LARGE_FONT_SCALE]（1.3×）时两个组件改**上下堆叠**（标签一行、数值/状态一行
 *   占满整行宽度），1.3× 以下维持原两列（零视觉变化，且保住 2026-10-11 那次「标签绝不被挤成竖排」的修复）。
 *
 * 判据走「源码级取值 ＋ 断言」，与 [TouchTargetGuardTest] / [A11yBannerGuardTest] 同惯例：
 * 本模块没有 UI 测试基建，字号分支与截断参数都写在源码里，源码就是唯一真源；
 * 断言一律读[剥掉整行注释][code]的源码 —— 解释缺陷的注释里必然出现 `maxLines` 这类反例词。
 */
class LargeFontWrapGuardTest {

    private val screenRel = "app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt"

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private val screen by lazy { repoFile(screenRel).readText() }

    /**
     * 剥掉**整行 `//` 注释**后的源码，判据一律读它。
     *
     * 为什么必须先剥：本刀的注释里就写着「没有任何 maxLines 截断」「不给 maxLines」这类反例词，
     * 直接对原文断言会把**解释缺陷的注释**当成缺陷本身（2026-10-16 首跑就是这样红的）。
     * 只剥整行注释、不动行尾，避免误伤字符串里的 `//`（如 URL）。
     */
    private val code by lazy { screen.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    /** 按花括号配平抽一个顶层 `internal fun <name>(…) { … }` 的函数体（含最外层花括号）。 */
    private fun composableBody(funName: String): String {
        val sig = code.indexOf("internal fun $funName(")
        assertTrue("$screenRel 里找不到 internal fun $funName(", sig >= 0)
        val open = code.indexOf('{', code.indexOf(')', sig))
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("$funName 的花括号不配平 —— 源码被改坏了？")
    }

    /**
     * 把函数体切成「大字体堆叠分支」与「两列分支」两段。
     * 结构约定：`if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) { …堆叠… } else { …两列… }`。
     */
    private fun branches(funName: String): Pair<String, String> {
        val body = composableBody(funName)
        val cond = body.indexOf("LocalDensity.current.fontScale >= LARGE_FONT_SCALE")
        assertTrue(
            "B-补-22：$funName 里找不到『字号 ≥ LARGE_FONT_SCALE』的分流 —— 大字体又会退回硬截断",
            cond >= 0,
        )
        val open = body.indexOf('{', cond)
        var depth = 0
        var i = open
        while (i < body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val stacked = body.substring(open, i + 1)
                        val rest = body.substring(i)
                        assertTrue("B-补-22：$funName 缺少 else（两列）分支", rest.contains("else {"))
                        return stacked to rest
                    }
                }
            }
            i++
        }
        throw AssertionError("$funName 的堆叠分支没有闭合")
    }

    @Test
    fun `门禁_B补22_字号阈值存在且落在第一档明显放大处`() {
        val m = Regex("""private const val LARGE_FONT_SCALE = ([\d.]+)f""").find(code)
        assertTrue("B-补-22：找不到 private const val LARGE_FONT_SCALE = <n>f", m != null)
        val v = m!!.groupValues[1].toDouble()
        // 低于 1.2 会在正常字号的轻微放大下就翻成堆叠（视觉突变）；高于 1.5 则 1.5× 那一档仍然截断。
        assertTrue("B-补-22：阈值 $v 不在 [1.2, 1.5] —— 过低会误伤正常字号、过高救不回 1.5× 档", v in 1.2..1.5)
    }

    @Test
    fun `门禁_B补22_两个共享行组件都按字号分流`() {
        val hits = Regex("LocalDensity\\.current\\.fontScale >= LARGE_FONT_SCALE").findAll(code).count()
        assertEquals("B-补-22：InfoRow 与 SettingRow 都必须分流（少一个就有一条路在大字体下截断）", 2, hits)
    }

    @Test
    fun `门禁_B补22_堆叠分支里标签与数值都不许截断`() {
        for (funName in listOf("InfoRow", "SettingRow")) {
            val (stacked, _) = branches(funName)
            assertFalse(
                "B-补-22：$funName 的大字体分支里还有 maxLines —— 堆叠的意义就是让文字整句换行",
                stacked.contains("maxLines"),
            )
            assertFalse(
                "B-补-22：$funName 的大字体分支里还有 softWrap = false —— 等于不换行",
                stacked.contains("softWrap = false"),
            )
            assertFalse(
                "B-补-22：$funName 的大字体分支里还有省略号 —— 状态文字会被吃掉",
                stacked.contains("TextOverflow"),
            )
        }
    }

    @Test
    fun `门禁_B补22_堆叠分支的文字吃满整行宽度`() {
        // 只换行不占宽 = 白搭：标签/数值必须拿到整行宽度，不能被兄弟挤成一条窄缝。
        val (infoStacked, _) = branches("InfoRow")
        assertTrue(
            "B-补-22：InfoRow 大字体分支的数值要 fillMaxWidth()（否则仍按内容宽度量、贴着左沿）",
            infoStacked.contains("modifier = Modifier.fillMaxWidth()"),
        )
        val (settingStacked, _) = branches("SettingRow")
        assertTrue(
            "B-补-22：SettingRow 大字体分支的状态文字要 weight(1f)（右侧留给 ›），整句换行不被省略号截",
            settingStacked.contains("Modifier.weight(1f)"),
        )
    }

    @Test
    fun `门禁_B补22_窄屏两列分支仍保住2026-10-11的竖排修复`() {
        // 那次修复的判据：标签按固有宽度一行排（maxLines = 1 + softWrap = false）、数值吃剩余宽度。
        // 若为了让大字体换行而把这两条删了，用户 2026-10-11 报的「存储与缓存这几个字是竖着的」会复发。
        for (funName in listOf("InfoRow", "SettingRow")) {
            val (_, twoCol) = branches(funName)
            assertTrue(
                "B-补-22：$funName 的两列分支丢了 softWrap = false —— 长数值会把标签挤成竖排（旧缺陷复发）",
                twoCol.contains("softWrap = false"),
            )
            assertTrue(
                "B-补-22：$funName 的两列分支丢了数值的 weight(1f)",
                twoCol.contains("Modifier.weight(1f)"),
            )
        }
    }

    @Test
    fun `门禁_B补22_台账点名的三张卡都走到修复点`() {
        // 三个二级页若各自手搓一行/一个不换行的芯片行，这刀的修复就覆盖不到它们 —— 逐个钉住：
        //   · 缓存清理卡（Storage）走共享 InfoRow；
        //   · 权限卡（Data）走共享 SettingRow；
        //   · 运行内存上限卡（Env）是三个 FilterChip2，走可折行的 FlowRow（它本来就不用共享行组件）。
        val pages = listOf(
            Triple("app/src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt", "R.string.storage_cache_title", "InfoRow("),
            Triple("app/src/main/java/com/example/zhengdao/ui/SettingsDataActivity.kt", "R.string.data_perm_ws_card", "SettingRow("),
            Triple("app/src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt", "R.string.env_memlimit_card", "FlowRow("),
        )
        for ((rel, cardRes, marker) in pages) {
            val kt = repoFile(rel).readText()
            assertTrue("B-补-22：$rel 里找不到台账点名的那张卡（$cardRes）", kt.contains(cardRes))
            assertTrue(
                "B-补-22：$rel 的那张卡不再走「$marker」这个修复点 —— 大字体下它的行/芯片会重新截断或挤出屏幕",
                kt.contains(marker),
            )
        }
        // Env 的那张卡再钉细一层：修复点必须在**卡内**，且不能退回裸 `Row {`（裸 Row 不折行＝芯片被右沿裁掉）。
        val env = repoFile("app/src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt").readText()
        val cardAt = env.indexOf("SectionCard(stringResource(R.string.env_memlimit_card))")
        assertTrue("B-补-22：SettingsEnvActivity.kt 里定位不到运行内存上限卡", cardAt >= 0)
        val bodyAt = env.indexOf('{', cardAt)
        var depth = 0
        var i = bodyAt
        var card = ""
        while (i < env.length) {
            when (env[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        card = env.substring(bodyAt, i + 1)
                        break
                    }
                }
            }
            i++
        }
        assertTrue("B-补-22：运行内存上限卡的芯片行不是 FlowRow —— 大字体下第三个芯片会被挤出屏幕右沿", card.contains("FlowRow("))
        assertFalse(
            "B-补-22：运行内存上限卡里又出现裸 `Row {` —— 裸 Row 不折行，芯片会被裁掉；要排芯片请用 FlowRow",
            card.contains(Regex("""\bRow\s*\{""")),
        )
    }
}
