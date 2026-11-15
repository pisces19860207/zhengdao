// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import com.example.zhengdao.browser.offscreen.WhiteImageJudge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 玄览导出的**取图与排版**（651）：量页面 → 拍可视区 / 拍整页 → 组 PDF。
 *
 * 判据全在 [PageExportPolicy]（纯函数，JVM 直测），本类只负责"把手伸到 WebView 上"：
 * 三件事必须知道 Android 才知道——`document.scrollHeight` 怎么读、`View.draw` 怎么拍、
 * `PdfDocument` 怎么排页。
 *
 * 三条纪律（照 `OffscreenShotter` 的既有结论）：
 * 1. **判白图**：画出来如果整幅同色（白/灰），一律当失败——绝不把一张灰图当"导出成功"交出去
 *    （262/263 批付过学费：后台载入的页 draw 出来是灰的）。判据复用 [WhiteImageJudge]，
 *    不另写一套。
 * 2. **拍完复原**：整页拍摄要把 WebView 临时拉高，拍了立刻恢复原尺寸与原滚动位
 *    （用户正在看的那一页不许被我们的导出动作留在"拉长"状态）。
 * 3. **不联网**：只画当前这一页，不 reload、不新建 WebView、不 fetch——
 *    导出是"把眼前这一页拿出来"，不是"再去要一次"。
 */
internal object PageExporter {

    /** 一张拍好的图（PNG 字节 ＋ 尺寸 ＋ 是否被上限截断）。 */
    data class Shot(val png: ByteArray, val widthPx: Int, val heightPx: Int, val truncated: Boolean) {
        /** 解回位图（PDF 排版用；调用方负责 recycle）。 */
        fun toBitmap(): Bitmap? = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inMutable = false }
            android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        }.getOrNull()
    }

    /** 页面度量（CSS px）：视口宽 ＋ 整页高。 */
    data class Metrics(val cssWidth: Int, val contentHeightCss: Int)


    /**
     * 量当前页面：视口宽与整页高。
     *
     * 用 `documentElement.scrollHeight` 而不是 `body.scrollHeight`：前者含 `html` 上的边距
     * 与浮动元素（后者在某些站上短一截，拍整页会漏尾巴）。两个都取，取大者（有的站反过来）。
     */
    fun measureContent(engine: WebViewEngine, done: (Metrics?) -> Unit) {
        engine.evaluateJavascript(MEASURE_JS) { raw -> done(parseMetrics(raw)) }
    }

    /**
     * [measureContent] 的挂起版（界面协程里用）：等回话，最多 [timeoutMs]。
     *
     * 为什么要超时：页面没了/引擎被换掉时 `evaluateJavascript` 的回调可能永远不到
     * （WebView 的既有脾性），不设上限就会把"正在导出…"永远挂在那儿。
     */
    suspend fun measureContent(engine: WebViewEngine, timeoutMs: Long = 4_000): Metrics? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                engine.evaluateJavascript(MEASURE_JS) { raw ->
                    if (cont.isActive) cont.resume(parseMetrics(raw))
                }
            }
        }

    /**
     * 可视区一张图：把 WebView **临时**改成 [target] 尺寸拍一张，再复原。
     *
     * 为什么不是"拍它此刻的样子"：屏幕上的排版恒等于手机宽（651 的口径：预设只作用于导出，
     * 见交付报告 §四），所以"桌面档的可视区"必须真的按 1280×800 重排一次再拍。
     * 重排是 WebView 自己的事（`measure`/`layout` → Chromium 改视口），拍完立刻复原。
     */
    fun captureViewport(wv: WebView, target: PageExportPolicy.Px): Shot? =
        captureAt(wv, target.w, target.h, truncated = false)

    /**
     * 整页一张图：宽按 [targetWidthPx] 重排，高取内容的实际高度（受像素预算夹紧）。
     *
     * @param metrics [measureContent] 量到的那两个数（CSS 宽只从这里来，本类不缓存、
     *   也不猜 density——页面带 initial-scale 时 density 会把图画短或画长）
     */
    fun captureFullPage(wv: WebView, metrics: Metrics, targetWidthPx: Int): Shot? {
        if (metrics.contentHeightCss <= 0 || metrics.cssWidth <= 0 || targetWidthPx <= 0) return null
        // 内容高（CSS px）→ 目标宽下的设备 px：按「目标宽 / CSS 视口宽」换算
        val rawPx = (metrics.contentHeightCss.toFloat() * targetWidthPx / metrics.cssWidth).toInt().coerceAtLeast(1)
        val target = PageExportPolicy.captureHeightPx(rawPx, targetWidthPx)
        val truncated = PageExportPolicy.truncated(rawPx, targetWidthPx) || target < rawPx
        return captureAt(wv, targetWidthPx, target, truncated)
    }

    /**
     * 临时把 WebView 改成 [w]×[h] 拍一张，然后复原（尺寸、布局、滚动位）。
     *
     * 三条都是踩过的：
     * 1. **量一次排一次，不等下一帧**（我们只要一张图，不需要它先上屏）；
     * 2. **复原顺序固定**：先尺寸、再 `requestLayout`、最后滚回去（滚动范围才有意义）；
     * 3. 画出来**先判白图**（[WhiteImageJudge]），不是真图一律 null —— 绝不把灰图当成功。
     */
    private fun captureAt(wv: WebView, w: Int, h: Int, truncated: Boolean): Shot? {
        if (w <= 0 || h <= 0) return null
        val lp = wv.layoutParams ?: return null
        val oldW = lp.width
        val oldH = lp.height
        val oldScrollX = wv.scrollX
        val oldScrollY = wv.scrollY
        return try {
            lp.width = w
            lp.height = h
            wv.layoutParams = lp
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            wv.layout(wv.left, wv.top, wv.left + w, wv.top + h)
            drawAndJudge(wv, w, h, truncated)
        } finally {
            lp.width = oldW
            lp.height = oldH
            wv.layoutParams = lp
            wv.requestLayout()
            runCatching { wv.scrollTo(oldScrollX, oldScrollY) }
        }
    }

    /** 把一张整页图按 A4 分页组成 PDF（页数由 [PageExportPolicy.pdfPageCount] 定）。 */
    fun toPdf(shot: Shot): ByteArray? {
        val bmp = shot.toBitmap() ?: return null
        return try {
            writePdf(bmp)
        } finally {
            bmp.recycle()
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun writePdf(bmp: Bitmap): ByteArray? = runCatching {
        val doc = PdfDocument()
        val slice = PageExportPolicy.sourceSliceHeightPx(bmp.width, bmp.height)
        if (slice <= 0) return@runCatching null
        val pages = PageExportPolicy.pdfPageCount(bmp.width, bmp.height)
        val margin = PageExportPolicy.PDF_MARGIN_PT.toFloat()
        val usableW = PageExportPolicy.A4_WIDTH_PT - margin * 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until pages) {
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(
                    PageExportPolicy.A4_WIDTH_PT,
                    PageExportPolicy.A4_HEIGHT_PT,
                    i + 1,
                ).create(),
            )
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val top = i * slice
            val bottom = minOf(bmp.height, top + slice)
            if (bottom > top) {
                // 宽度贴合可用宽，高度按同一比例 ⇒ 一张图不会被拉扁
                val dstH = ((bottom - top) * (usableW / bmp.width)).toInt().coerceAtLeast(1)
                canvas.drawBitmap(
                    bmp,
                    Rect(0, top, bmp.width, bottom),
                    Rect(margin.toInt(), margin.toInt(), (margin + usableW).toInt(), margin.toInt() + dstH),
                    paint,
                )
            }
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        out.toByteArray()
    }.getOrNull()

    /** 画一张并按 [WhiteImageJudge] 判真图；不是真图 ⇒ null（不把灰图当成功）。 */
    private fun drawAndJudge(wv: WebView, w: Int, h: Int, truncated: Boolean): Shot? = runCatching {
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(bmp))
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val verdict = WhiteImageJudge.judge(pixels, w, h)
        if (verdict.classification != WhiteImageJudge.Classification.RICH) {
            bmp.recycle()
            return null
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        Shot(out.toByteArray(), w, h, truncated)
    }.getOrNull()

    private fun parseMetrics(raw: String?): Metrics? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        val body = if (text.startsWith("\"")) {
            runCatching { org.json.JSONTokener(text).nextValue() as? String }.getOrNull()
        } else {
            text
        }.orEmpty()
        if (body.isEmpty()) return null
        return runCatching {
            val arr = JSONArray(body)
            val w = arr.optInt(0, 0)
            val h = arr.optInt(1, 0)
            if (w <= 0 || h <= 0) null else Metrics(w, h)
        }.getOrNull()
    }

    /**
     * 量页面的 JS：返回 `[视口宽, 整页高]`。
     *
     * 只用 DOM 的只读属性（不写页面、不改滚动）——导出动作不许有副作用。
     * `try` 里兜住：跨域 iframe 上的 `documentElement` 在某些站会抛。
     */
    private const val MEASURE_JS =
        "(function(){try{" +
            "var d=document.documentElement,b=document.body;" +
            "var w=window.innerWidth||d.clientWidth||0;" +
            "var h=Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0);" +
            "return JSON.stringify([w,h]);" +
            "}catch(e){return '';}})()"
}
