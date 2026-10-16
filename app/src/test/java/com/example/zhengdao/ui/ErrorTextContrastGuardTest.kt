package com.example.zhengdao.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-11（issue #17）：错误色当正文用的对比度守卫。
 *
 * 审计原文：「红字对比度 3.55:1：`ui/baopu/BaopuComponents.kt:281`」（该文件 2026-10-16 已删，本条为历史审计留痕）——实测复现：
 * 浅色主题的 `colorScheme.error` = IOSRed #FF3B30，白底对比度正好 **3.55:1**，
 * 够不上 WCAG AA 正文的 4.5:1（图形/图标只要 3:1，那个槽位本是给它们用的）。
 *
 * 修法：新增语义色 `zhengdaoErrorText()`（Theme.kt，与既有的 zhengdaoReadyGreen /
 * zhengdaoBlueText 同一模式）：浅色 #C0392B（5.4:1）/ 深色 #FFC9C2（对 #1C1C1E 11.7:1）。
 * 全部**文字**站点切过去；**图标/图形**（警告三角、状态点、⚠️/✗ 字形对）保留 error 色
 * ——它们只需 3:1，3.55:1 本来就过。
 *
 * 本守卫钉：① 语义色函数存在且两分支齐全；② 源码里 colorScheme.error 不再出现在
 * 任何**文字**赋值行（color = / valueColor = / tone = / Triple 的第二元素）。
 */
class ErrorTextContrastGuardTest {

    /** 文字赋值行里出现 colorScheme.error = 违规（这些形态都喂给 Text 的 color）。 */
    private val textPatterns = listOf(
        Regex("""color\s*=\s*(if\s*\([^)]*\)\s*)?MaterialTheme\.colorScheme\.error\b"""),
        Regex("""valueColor\s*=.*MaterialTheme\.colorScheme\.error\b"""),
        Regex("""tone\s*=\s*MaterialTheme\.colorScheme\.error\b"""),
        Regex(""""", MaterialTheme\.colorScheme\.error,"""), // Triple 第二元素（图标+文字同色）
    )

    @Test
    fun `门禁_Theme 必须有 zhengdaoErrorText 且两分支齐全`() {
        val theme = File("src/main/java/com/example/zhengdao/ui/theme/Theme.kt").readText()
        assertTrue("Theme.kt 必须有 zhengdaoErrorText()", theme.contains("fun zhengdaoErrorText()"))
        assertTrue("浅色分支用 IOSErrorText（#C0392B 5.4:1）", theme.contains("else IOSErrorText"))
        assertTrue("深色分支用 IOSDarkOnTintedRed（11.7:1）", theme.contains("isZhengdaoDark()) IOSDarkOnTintedRed"))
    }

    @Test
    fun `门禁_文字赋值不再用 colorScheme_error`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("zhengdao") }
            .flatMap { f ->
                textPatterns.flatMap { pat -> pat.findAll(f.readText()).map { f.path to it.value } }
            }
            .toList()
        assertTrue(
            "以下位置还把 error 色当文字用（3.55:1 不达 AA，应改 zhengdaoErrorText()）：" +
                offenders.joinToString("；") { (p, v) -> "$p => $v" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_图标与图形保留 error 色是允许的`() {
        // 反向钉口径：警告图标 tint / 状态点 targetValue / 字形对仍用 error 色（3:1 即过）。
        // 这条是文档性断言——防后人把"图形也要换"当口径，把图标色也改成深红反而发虚。
        val browser = File("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt").readText()
        assertTrue(
            "警告图标是图形，保留 colorScheme.error（3.55:1 过图形的 3:1）",
            browser.contains("WarningGlyph(tint = MaterialTheme.colorScheme.error"),
        )
    }
}
