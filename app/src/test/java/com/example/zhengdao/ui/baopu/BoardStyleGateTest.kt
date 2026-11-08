// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十二批（2026-11-08）· 小黑板样式（A 案）的**源级门禁**。
//
// 为什么这些判据只能按源码钉：Compose 树在 JVM 直测不了（本仓既有门禁的同一取舍，
// 322 的 `GovernanceCenterGateTest` 就是这套写法）。钉法照它抄：**先剥注释再扫**、
// 锚点用 `R.string.*` 键名与调用名（不用行号）、禁词按"业务口"列。
//
// 五条判据（派工单 §判据 ①②＋§样式四条）：
//   ① 板面三档色只从 `BoardPalette` 来：屏幕源码里**零裸色值**（不许出现 `Color(0x…)`）；
//   ② 字号/圆角/内距全走既有的 token：屏幕源码里**零 `.sp` 字面量**、圆角只用 `ShapeCard`；
//   ③ **不做主题切换**：屏幕不读 `isZhengdaoDark()`、不出现 `Switch(`——一块板不随深浅模式变色；
//   ④ **不引第三方字体**：屏幕不出现 `FontFamily(`、仓里没有 `res/font/` 目录；
//   ⑤ ②的三样齐有源码锚：顶上一行（`gc_board_header`）／占位（`gc_board_empty`）／清空（`gc_board_clear`），
//      且占位的中文就是用户要的「（还没写过）」；
//   ⑥ 板面色**只有那一处用**：`BoardPalette` 在 main 源码里只许被小黑板那一屏引用
//      （判据："绿色不许回到语义色板"的地基——它只能是道具色）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStyleGateTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }

    /** 目录版（单测的工作目录可能是 `app/`，也可能是仓库根——两个都试）。 */
    private fun repoDir(rel: String): File? =
        listOf(File(rel), File("../$rel"), File("app/$rel")).firstOrNull { it.isDirectory }

    /** 去行注释与块注释后的代码文本（免得被注释绕过，也免得注释里的词误伤）。 */
    private fun codeOnly(src: String): String =
        src.replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split("\n").joinToString("\n") { it.substringBefore("//") }

    private val screenPath = "src/main/java/com/example/zhengdao/ui/baopu/GovernanceCenterScreen.kt"

    private val screen by lazy { codeOnly(repoFile(screenPath).readText(Charsets.UTF_8)) }

    // ── ① 颜色只从色板来 ──────────────────────────────────────────────────────

    @Test
    fun `屏幕里零裸色值_三档全从BoardPalette来`() {
        assertFalse("屏幕不许写裸色值（Color(0x…)）：色只有 BoardPalette 一个出处", screen.contains("Color(0x"))
        listOf("BoardPalette.ground", "BoardPalette.chalk", "BoardPalette.chalkDim").forEach {
            assertTrue("小黑板必须用 $it", screen.contains(it))
        }
    }

    // ── ② 字号/圆角/内距走既有 token ──────────────────────────────────────────

    @Test
    fun `屏幕里零字号字面量_圆角只用ShapeCard`() {
        assertFalse("屏幕不许写死字号（.sp）：字号只有 ui/theme/Type.kt 那一套", screen.contains(".sp"))
        assertTrue("卡片圆角必须走既有 token ShapeCard（16dp，rCard）", screen.contains("shape = ShapeCard"))
        assertFalse("不许自己拼圆角（RoundedCornerShape(…)）", screen.contains("RoundedCornerShape("))
        // 字号槽位：顶上那行 labelMedium（13sp）、正文 bodyMedium（15sp）、上限 labelSmall（10sp）
        listOf(
            "MaterialTheme.typography.labelMedium",
            "MaterialTheme.typography.bodyMedium",
            "MaterialTheme.typography.labelSmall",
        ).forEach { assertTrue("字号槽位应是 $it", screen.contains(it)) }
    }

    // ── ③ 不做主题切换 ────────────────────────────────────────────────────────

    @Test
    fun `不做主题切换_板面不读深浅模式`() {
        assertFalse("屏幕不许读 isZhengdaoDark()（一块板不随主题变色）", screen.contains("isZhengdaoDark"))
        assertFalse("屏幕不许出现主题开关", screen.contains("Switch("))
    }

    // ── ④ 不引第三方字体 ─────────────────────────────────────────────────────

    @Test
    fun `不引第三方字体`() {
        assertFalse("屏幕不许自己指定字体族", screen.contains("FontFamily("))
        assertFalse("屏幕不许从资源取字体", screen.contains("Font(R.font"))
        val fontDir = repoDir("app/src/main/res/font")
        assertTrue("仓里不许有 res/font/（引第三方字体就会在这里躺着）：$fontDir", fontDir == null)
    }

    // ── ⑤ 三样齐的源码锚 ─────────────────────────────────────────────────────

    @Test
    fun `顶上那行_占位_清空三样都在源码里`() {
        assertTrue("顶上一行：板名 + 最近更新（gc_board_header）", screen.contains("R.string.gc_board_header"))
        assertTrue("空板占位（gc_board_empty）", screen.contains("R.string.gc_board_empty"))
        assertTrue("右下清空（gc_board_clear）", screen.contains("R.string.gc_board_clear"))
        // 占位的中文就是用户要的那五个字（改文案要回来改这条）
        val zh = repoFile("src/main/res/values/strings_baopu_governance_zone.xml").readText(Charsets.UTF_8)
        assertTrue("中文占位必须是「（还没写过）」", zh.contains("""<string name="gc_board_empty">（还没写过）</string>"""))
        assertTrue("顶上那行必须带「最近更新」", zh.contains("最近更新"))
        // 小字是 13sp 的 labelMedium、正文是 15sp 的 bodyMedium：两档都在 BoardSection 里用上
        val boardFn = screen.substringAfter("private fun BoardSection(")
        assertTrue("顶上那行要用小字档（labelMedium）", boardFn.contains("labelMedium"))
        assertTrue("正文要用正文档（bodyMedium）", boardFn.contains("bodyMedium"))
    }

    // ── ⑦ 板面那一行字不许填满宽度（真机截图踩过的坑）────────────────────────

    @Test
    fun `BoardText不许fillMaxWidth_否则右下清空被挤到屏外`() {
        // 542 首轮真机截图抓到的：`BoardText` 带 `fillMaxWidth()` 时，底部那个 Row 里
        // 它吃满整行 ⇒ `Spacer(weight)` 与「清空」被挤到可视区外（`uiautomator dump` 里
        // 连节点都没有，看着像"按钮没做"）。这条把它钉死：谁再加回来，这里立刻红。
        val body = screen.substringAfter("private fun BoardText(").substringBefore("\n}")
        assertTrue("BoardText 必须还在（锚点丢了要回来重新钉）", body.contains("Text("))
        assertFalse("BoardText 不许 fillMaxWidth（会挤掉右下「清空」）", body.contains("fillMaxWidth"))
    }

    // ── ⑥ 板面色只给小黑板用 ──────────────────────────────────────────────────

    @Test
    fun `BoardPalette只被那一屏引用`() {
        val root = repoDir("src/main/java/com/example/zhengdao")
            ?: repoDir("app/src/main/java/com/example/zhengdao")
            ?: throw AssertionError("找不到 main 源码目录")
        val paletteFile = "BoardPalette.kt"
        val users = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != paletteFile }
            .filter { it.readText(Charsets.UTF_8).contains("BoardPalette") }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "板面色只许小黑板那一屏用（语义色板里不许有绿；道具色也不许外溢）",
            listOf("GovernanceCenterScreen.kt"),
            users,
        )
    }
}
