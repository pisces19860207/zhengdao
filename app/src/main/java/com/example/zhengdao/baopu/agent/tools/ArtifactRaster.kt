// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：artifact 的**无脚本沙箱截图器**（Android 侧唯一实现）。
//
// 红线三条，全部落在这里：
// 1. **不执行 JS**：`javaScriptEnabled = false`，也不调 `evaluateJavascript`；
// 2. **不联网**：`blockNetworkLoads = true`，外加 `shouldInterceptRequest` 把除
//    `about:` / `data:` 之外的请求全部换空响应（双保险；`data:` 是内联资源，不出网）；
// 3. **不把空白当成功**：截完先扫像素，整张接近纯白（或根本没画上）⇒ 抛
//    [RasterException]，由工具层如实回灌。
//
// 为什么必须用进程级主 Looper 的 Handler、不能用 View 自己的 post：
// WebView 是未 attach 的 View，`view.post{}` 会压进它自己的 HandlerActionQueue、
// attach 时才执行（ERRATA E-142 / 第㊳刀）——本类拿不到「attach」这一步，用 View.post
// 就是永久死锁。`Handler(Looper.getMainLooper())` 与挂载状态无关。
//
// 已知限制（真机未验，交付报告如实记）：截图走 software canvas，系统 WebView 的
// 字体与换行随设备/版本有细微差异；这里钉死位图宽 1080 与 CSS 视口约 1000，
// 只保证「同一台设备上可复现」，不承诺跨设备逐像素一致。
package com.example.zhengdao.baopu.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

internal object ArtifactRaster : ArtifactRasterizer {

    /** 装配层注入的 Context（只取 applicationContext；WebView 不碰 Activity 生命周期）。 */
    class Env(val context: () -> Context?)

    @Volatile
    var env: Env? = null

    /** 位图宽度（物理像素）。固定宽度 → 同一设备上截图可复现。 */
    private const val PNG_WIDTH = 1080

    /** 目标 CSS 视口宽度：模型写的页面按「桌面窄屏」排版，13px 字号截出来仍是 13px。 */
    private const val CSS_WIDTH = 1000

    /** 高度上限：1080×6000 ARGB ≈ 25 MB，再高就可能在低端机上 OOM。 */
    private const val MAX_PNG_HEIGHT = 6000

    private const val SETTLE_MS = 100L
    private const val TIMEOUT_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())

    override fun capture(html: String, target: File): RasterResult {
        val ctx = env?.context?.invoke()
            ?: throw RasterException("截图器没接线（App 未装配），无法截图。")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw RasterException("截图不能在主线程等待（会死锁）：请在后台工具线程调用。")
        }
        val job = Job(html, target)
        main.post { start(ctx, job) }
        val finished = job.done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            main.post { job.web?.destroy() }
            throw RasterException("截图超时（${TIMEOUT_MS / 1000} 秒）：页面太大或系统 WebView 没响应。")
        }
        job.error?.let {
            main.post { job.web?.destroy() }
            throw RasterException(it)
        }
        return job.result ?: throw RasterException("截图没有结果（原因未知）。")
    }

    private class Job(val html: String, val target: File) {
        val done = CountDownLatch(1)

        @Volatile
        var web: WebView? = null

        @Volatile
        var result: RasterResult? = null

        @Volatile
        var error: String? = null

        @Volatile
        private var finished = false

        fun fail(message: String) {
            if (finished) return
            finished = true
            error = message
            done.countDown()
        }

        fun ok(value: RasterResult) {
            if (finished) return
            finished = true
            result = value
            done.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(ctx: Context, job: Job) {
        val web = try {
            WebView(ctx)
        } catch (t: Throwable) {
            job.fail("系统 WebView 建不起来：${t.javaClass.simpleName}。")
            return
        }
        job.web = web
        runCatching {
            with(web.settings) {
                javaScriptEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }
            web.setInitialScale(initialScale(web.resources.displayMetrics.density))
            web.setBackgroundColor(Color.WHITE)
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web.isVerticalScrollBarEnabled = false
            web.overScrollMode = View.OVER_SCROLL_NEVER
            web.webViewClient = client(job)
            web.loadDataWithBaseURL("about:blank", job.html, "text/html", "utf-8", null)
        }.onFailure {
            job.fail("页面装载失败：${it.javaClass.simpleName}。")
            web.destroy()
            return
        }
        main.postDelayed({
            if (job.done.count > 0L) job.fail("截图超时（${TIMEOUT_MS / 1000} 秒）：页面太大或系统 WebView 没响应。")
        }, TIMEOUT_MS - 500)
    }

    private fun client(job: Job): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("API 24 起走 WebResourceRequest 那条")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url?.toString().orEmpty()
            if (url.startsWith("about:", ignoreCase = true) ||
                url.startsWith("data:", ignoreCase = true)
            ) {
                return null
            }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (job.done.count == 0L) return
            // 等一帧排版收尾（字体/表格宽度）；不跑任何脚本，所以不需要更长的等待。
            main.postDelayed({
                if (job.done.count > 0L) captureOnMain(view, job)
            }, SETTLE_MS)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                job.fail("页面加载失败（${error.errorCode}）：无法截图。")
            }
        }
    }

    private fun captureOnMain(web: WebView, job: Job) {
        var bitmap: Bitmap? = null
        try {
            // 先按固定宽度量一次，让 WebView 以最终宽度排版；contentHeight 才是整页高度。
            web.measure(
                View.MeasureSpec.makeMeasureSpec(PNG_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PNG_WIDTH, View.MeasureSpec.EXACTLY),
            )
            web.layout(0, 0, PNG_WIDTH, PNG_WIDTH)

            val density = web.resources.displayMetrics.density
            val scale = (web.scale / 100.0).takeIf { it > 0.0 } ?: 1.0
            val cssHeight = web.contentHeight.coerceAtLeast(1)
            val physical = ceil(cssHeight * density * scale).toInt().coerceAtLeast(1)
            val height = min(physical, MAX_PNG_HEIGHT)
            val clipped = physical > MAX_PNG_HEIGHT
            web.layout(0, 0, PNG_WIDTH, height)

            val bmp = createBitmap(PNG_WIDTH, height)
            bitmap = bmp
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            web.draw(canvas)

            if (isBlank(bmp)) {
                job.fail("渲染结果是空白（页面没有画出任何内容）：已拒绝产出空白 PNG。")
                return
            }
            val bytes = writePng(bmp, job.target)
            job.ok(RasterResult(bytes, PNG_WIDTH, height, clipped))
        } catch (t: Throwable) {
            job.fail("截图失败：${t.javaClass.simpleName}${t.message?.let { "（$it）" }.orEmpty()}")
        } finally {
            bitmap?.recycle()
            web.destroy()
        }
    }

    /** 扫一遍位图：全是纯白/透明 ⇒ 空白。隔行隔列取样（只用于判定，不用于出图）。 */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                if (Color.alpha(c) != 0 && (c and 0x00FFFFFF) != 0x00FFFFFF) return false
                x += 8
            }
            y += 8
        }
        return true
    }

    private fun writePng(bitmap: Bitmap, target: File): Int {
        val parent = target.parentFile ?: throw RasterException("没有父目录：${target.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw RasterException("建不了目录：${parent.absolutePath}")
        }
        val tmp = File(parent, target.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw RasterException("PNG 编码失败。")
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw RasterException("写不进 ${target.absolutePath}")
            }
            return target.length().toInt()
        } finally {
            tmp.delete()
        }
    }

    /** 让「约 [CSS_WIDTH] 个 CSS 像素」铺满 [PNG_WIDTH] 物理像素。 */
    private fun initialScale(density: Float): Int {
        if (density <= 0f) return 100
        val raw = 100.0 * PNG_WIDTH / (density * CSS_WIDTH)
        return raw.roundToInt().coerceIn(10, 100)
    }
}
