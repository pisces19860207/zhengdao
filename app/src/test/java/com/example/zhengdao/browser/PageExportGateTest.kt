// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设备尺寸预设与导出的**接线**门禁（651）。
 *
 * 六件事：
 * ① 「未加载完 ⇒ 拒绝导出」在**点击那一刻**再判一次（不是只靠半屏里那枚灰键——
 *    切 Tab、开始加载都可能在点击瞬间发生）；
 * ② 半屏里三枚导出键共用同一个 `ready` 判据（不许某一枚自己判一套）；
 * ③ 尺寸预设真的接到了**取图**（`captureTarget` → `captureViewport/captureFullPage`），
 *    且屏幕上的排版不许被预设动（真机上「撑大再缩」会画空白）；
 * ④ 落点：图进相册（`MediaPublisher` ＋ SOURCE_SCREENSHOT）、PDF 进下载（`MediaStore.Downloads`）；
 * ⑤ 导出链路不联网（本文件与页记忆门禁各钉一半）；
 * ⑥ **不动 622/645/623/624 的判定**：教学浮层与未学会胶囊仍被调用（本批只加东西，不删别人的件）。
 */
class PageExportGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen() = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"))

    private fun sheet() = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/XuanlanExportSheet.kt"))

    private fun sink() = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/PageExportSink.kt"))

    /** 从 `marker` 起、到下一个同名深度的括号块为止。 */
    private fun blockAfter(s: String, marker: String): String {
        val at = s.indexOf(marker)
        assertTrue("找不到标记 `$marker`", at >= 0)
        val open = s.indexOf('{', at)
        assertTrue("`$marker` 之后没有函数体 `{`", open > at)
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("`$marker` 的花括号没配平")
    }

    // ── ① 点击那一刻再判一次 ────────────────────────────────────────────────

    @Test
    fun `判据_点击那一刻再判一次_判不过就只给提示不拍图`() {
        val body = blockAfter(screen(), "val submitExport: (ExportKind) -> Unit = {")
        assertTrue("submitExport 里必须再判一次 refusalOf", body.contains("PageExportPolicy.refusalOf("))
        assertTrue("提示必须落到既有提示条（uiMsg）", body.contains("uiMsg = refuseMsg ?: refuseNoTab"))
        val refuseAt = body.indexOf("PageExportPolicy.refusalOf(")
        val captureAt = body.indexOf("PageExporter.captureViewport(")
        assertTrue("判据必须早于取图", refuseAt in 0 until captureAt)
        assertFalse("拒绝分支里不许出现取图", body.substring(0, captureAt).contains("captureViewport"))
    }

    @Test
    fun `判据_半屏的灰键与点击判据同源`() {
        val s = screen()
        assertTrue(
            "半屏的 refusal 必须来自同一个纯函数",
            s.contains("val exportRefusal = PageExportPolicy.refusalOf("),
        )
        assertTrue("半屏要吃 exportRefusal", s.contains("refusal = exportRefusal,"))
        // 七种理由都要有一句人话（漏一种就会在界面上拼出英文枚举名）
        for (r in listOf("NO_TAB", "RESTORED", "RECYCLED", "LOADING", "NOT_LOADED", "NOT_RENDERED")) {
            assertTrue("拒绝理由 $r 必须有对应文案分支", s.contains("PageExportPolicy.Refusal.$r ->"))
        }
    }

    // ── ② 三枚键共用 ready ──────────────────────────────────────────────────

    @Test
    fun `判据_三枚导出键共用 ready_不许各判一套`() {
        val s = sheet()
        assertTrue("ready ＝ 可导且不在导出中", s.contains("val ready = refusal == PageExportPolicy.Refusal.NONE && !busy"))
        val rows = Regex("ExportRow\\(stringResource\\(R\\.string\\.xuanlan_export_[a-z_]+\\)\\s*,\\s*ready")
            .findAll(s).count()
        assertTrue("三枚键都必须传 ready（实际 $rows 枚）", rows == 3)
        assertTrue("禁用时要有解释", s.contains("refusalText(refusal)"))
    }

    // ── ③ 预设真的接到页面区 ────────────────────────────────────────────────

    @Test
    fun `判据_预设接到取图_屏幕排版不动`() {
        val s = screen()
        assertTrue("预设 → 视口必须走纯函数", s.contains("PageExportPolicy.viewportOf("))
        assertTrue("预设 → 像素目标必须走 captureTarget", s.contains("PageExportPolicy.captureTarget("))
        assertTrue("可视区取图要带目标尺寸", s.contains("PageExporter.captureViewport(tab.webView, target)"))
        assertTrue("整页取图要带目标宽", s.contains("PageExporter.captureFullPage(tab.webView, metrics, target.w)"))
        // 屏上排版不许被预设动：这两条是 21:41／21:46 两次真机实测换来的（画空白）
        assertFalse("不许再出现「撑到预设宽再缩」的写法", s.contains(".requiredSize(vwDp.dp"))
        assertFalse("不许再给 WebView 套缩放图层", s.contains("transformOrigin = androidx.compose.ui.graphics.TransformOrigin"))
        assertTrue("页面区必须仍是 fillMaxSize（预设不参与屏上排版）", s.contains("modifier = Modifier.fillMaxSize()"))
    }

    @Test
    fun `判据_四档预设都在半屏上`() {
        val s = sheet()
        for (k in listOf(
            "xuanlan_preset_phone",
            "xuanlan_preset_tablet",
            "xuanlan_preset_desktop",
            "xuanlan_preset_custom",
        )) {
            assertTrue("半屏缺预设 $k", s.contains("R.string.$k"))
        }
        assertTrue("自定义宽度要能填", s.contains("onCustomWidth"))
    }

    // ── ④ 落点 ──────────────────────────────────────────────────────────────

    @Test
    fun `判据_图进相册_PDF 进产物`() {
        val s = sink()
        assertTrue("PNG 必须走既有相册唯一出口", s.contains("MediaPublisher.publish("))
        assertTrue("PNG 的来源必须是截屏", s.contains("MediaOwnership.SOURCE_SCREENSHOT"))
        assertTrue("PNG 的 MIME 必须是 image/png", s.contains("\"image/png\""))
        // PDF 不进 MediaStore：G3「MediaStore 写删只有一个出口」是既有铁律，本批不许为 PDF 破它
        assertTrue("PDF 必须落 产物/玄览导出/", s.contains("LibraryPaths.doneDir("))
        assertTrue("PDF 子目录名要在代码里（回执才对得上）", s.contains("const val PDF_SUBDIR = \"玄览导出\""))
        assertFalse("PDF 这条链不许碰 MediaStore", s.contains("MediaStore."))
        assertTrue("同名不许覆盖用户已有文件", s.contains("uniqueFile("))
    }

    @Test
    fun `判据_失败要说得出原因_不许静默成功`() {
        val s = sink()
        assertTrue("失败要有类型", s.contains("enum class Failure"))
        assertTrue("空内容要拦", s.contains("Saved.Failed(Failure.EMPTY)"))
        assertTrue("先写 .part 再改名（产物里不许出现半截 PDF）", s.contains(".part"))
        assertTrue("失败要删掉半截临时文件", s.contains("if (temp.exists()) temp.delete()"))
    }

    // ── ⑤ 不联网 ────────────────────────────────────────────────────────────

    @Test
    fun `判据_执行层不联网`() {
        val s = src("src/main/java/com/example/zhengdao/browser/PageExporter.kt")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
        assertFalse("导出不许 loadUrl", s.contains("loadUrl("))
        assertFalse("导出不许 reload", s.contains("reload("))
    }

    // ── ⑥ 不动 622/645/623/624 的判定 ──────────────────────────────────────

    @Test
    fun `判据_教学浮层与未学会胶囊原样在`() {
        val s = screen()
        assertTrue("623 的教学浮层必须还在组合树里", s.contains("XuanlanCapsuleLayer("))
        assertTrue("624 的未学会橙点件必须还在", s.contains("NotLearnedCapsulePill("))
        assertTrue("二者仍是互斥的兄弟分支", s.contains("} else if (learnState.count > 0) {"))
        assertTrue("622 的执行端口不在本批动过（浏览器 UI 不走端口）", s.contains("BrowserTabManager.openExternal("))
    }
}
