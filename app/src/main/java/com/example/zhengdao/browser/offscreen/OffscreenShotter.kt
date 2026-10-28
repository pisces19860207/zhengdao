// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批（2026-10-28 收窄后重做）：**生产用**离屏取图。
//
// 为什么要有它（用户实测确认的根因）：抱朴在前台时，玄览的 WebView 根本没在画 ⇒
// 直接抓必然是一张灰图；点一下玄览再回抱朴就正常（最后一帧还留着）。所以决定性
// 判据不是"前台/后台"，而是**载入/渲染时那一页可不可见**（263 已在本机验过）。
//
// 复用 263 已验证的机制（不是另写一套）：
// - 1dp 挂窗（`Gravity.TOP or Gravity.START`，用户不可见、**不抢焦点**）；
// - `loadUrl` 载入目标地址（App 在前台时载入，Chromium 才会真渲染）；
// - `postVisualStateCallback` 等一帧真画出来（263 实测的信号源）；
// - `webView.draw(Canvas)` 取位图；
// - `WhiteImageJudge` 判"真图 / 白图 / 灰图"——**判出来不是真图就报失败**，
//   绝不把一张灰图当成功回给模型（那正是这次要治的病）。
//
// 边界（工单钉死）：
// - 不复用玄览那个 WebView：离屏这张是**重新载入**目标 URL 画的，不能把用户正在看的
//   页面顶掉、也不能改他的滚动位置。代价如实写进工具说明：这张反映**页面初始状态**。
// - 只画一次，不重试；超时如实报哪一种超时。
// - 用完必拆：位图 recycle、WebView destroy、容器从 decorView 摘下。
package com.example.zhengdao.browser.offscreen

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离屏取一张图：1dp 挂窗 → 载入目标地址 → 等 visual → draw → 判白图。
 *
 * 调用方必须是**非主线程**（内部会阻塞等主线程）；[capture] 返回时现场已经收拾干净。
 */
class OffscreenShotter(
    private val activity: Activity,
    private val webConfig: ProbeWebConfig = ProbeWebConfig(javaScriptEnabled = true),
    private val pageTimeoutMs: Long = 20_000,
    private val visualTimeoutMs: Long = 8_000,
) {

    /** 一次离屏取图的结局。失败必须带可读原因——不许静默回一张灰图。 */
    sealed class Outcome {
        data class Ok(
            val png: ByteArray,
            val width: Int,
            val height: Int,
            /** 最大同色占比（0..1）：越接近 0 越"有内容"，便于日志里一眼看出余量。 */
            val dominantRatio: Double,
        ) : Outcome()

        data class Fail(val kind: Kind, val reason: String) : Outcome()
    }

    enum class Kind {
        /** 主框架载入报错（DNS/证书/网络）。 */
        LOAD_ERROR,

        /** 等 onPageFinished 超时。 */
        PAGE_TIMEOUT,

        /** 页面载完了，但等不到一帧真正画出来。 */
        VISUAL_TIMEOUT,

        /** WebView 量不到尺寸（没挂上/被摘了）。 */
        SIZE_ZERO,

        /** 画出来了，但整幅同色（白图或灰图）——等于没画。 */
        UNIFORM,

        /** 取图环节抛异常。 */
        EXCEPTION,
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * 载入 [url] 并取一张图。阻塞直到出结果或超时。
     *
     * @return [Outcome.Ok] 只在**判定为真图**时返回；白图/灰图一律 [Outcome.Fail]。
     */
    fun capture(url: String): Outcome {
        val latch = CountDownLatch(1)
        var out: Outcome = Outcome.Fail(Kind.EXCEPTION, "内部错误：没有回填结果")
        main.post { runSession(url) { r -> out = r; latch.countDown() } }
        if (!latch.await(pageTimeoutMs + visualTimeoutMs + 10_000, TimeUnit.MILLISECONDS)) {
            return Outcome.Fail(Kind.PAGE_TIMEOUT, "整体等待超时（页面或视觉帧一直没到）")
        }
        return out
    }

    // ── 一次完整会话（全程主线程） ──────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun runSession(url: String, done: (Outcome) -> Unit) {
        var finished = false
        var webView: WebView? = null
        var container: FrameLayout? = null

        fun finish(r: Outcome) {
            if (finished) return
            finished = true
            runCatching {
                container?.let { c ->
                    val child = c.getChildAt(0)
                    if (child != null && child.parent === c) c.removeView(child)
                    (c.parent as? ViewGroup)?.removeView(c)
                }
                webView?.let { wv ->
                    runCatching { wv.stopLoading() }
                    runCatching { wv.destroy() }
                }
            }
            done(r)
        }

        runCatching {
            val wv = WebView(activity)
            webView = wv
            wv.settings.javaScriptEnabled = webConfig.javaScriptEnabled
            wv.settings.domStorageEnabled = true
            wv.setBackgroundColor(0xFFFFFFFF.toInt())

            val pageLatch = CountDownLatch(1)
            var pageError: String? = null
            var pageOk = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    pageOk = true
                    pageLatch.countDown()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        pageError = "${error.errorCode} ${error.description}"
                        pageLatch.countDown()
                    }
                }
            }

            // 1dp 挂窗：不抢焦点、用户看不见（263 实测该形态前台载入能出真图）
            val dm = activity.resources.displayMetrics
            val onePx = maxOf(1, Math.round(1f * dm.density))
            val host = activity.findViewById<ViewGroup>(android.R.id.content)
            if (host == null) {
                finish(Outcome.Fail(Kind.EXCEPTION, "找不到宿主容器（activity 已销毁？）"))
                return
            }
            container = FrameLayout(activity)
            container.isFocusable = false
            container.isFocusableInTouchMode = false
            host.addView(
                container,
                FrameLayout.LayoutParams(onePx, onePx, Gravity.TOP or Gravity.START),
            )
            container.addView(
                wv,
                FrameLayout.LayoutParams(
                    // 子视图按**真实屏幕尺寸**布局：Chromium 的视口尺寸必须是真的，
                    // 否则页面按 1px 宽排版，画出来是废图（263 的坑，照抄结论）
                    maxOf(1, dm.widthPixels),
                    maxOf(1, dm.heightPixels),
                ),
            )

            wv.loadUrl(url)

            // 等页面（不阻塞主线程：用 postDelayed 分两次判）
            val pageDeadline = Runnable {
                if (!pageOk) finish(Outcome.Fail(Kind.PAGE_TIMEOUT, "等 onPageFinished 超时（${pageTimeoutMs}ms）"))
            }
            main.postDelayed(pageDeadline, pageTimeoutMs)

            val awaitPage = object : Runnable {
                override fun run() {
                    if (finished) return
                    pageError?.let {
                        finish(Outcome.Fail(Kind.LOAD_ERROR, "页面载入失败：$it"))
                        return
                    }
                    if (!pageOk) {
                        main.postDelayed(this, 100)
                        return
                    }
                    main.removeCallbacks(pageDeadline)
                    awaitVisualThenDraw(wv) { finish(it) }
                }
            }
            main.postDelayed(awaitPage, 100)
        }.onFailure {
            finish(Outcome.Fail(Kind.EXCEPTION, "取图异常：${it.javaClass.simpleName} ${it.message}"))
        }
    }

    /** 等一帧真画出来（`postVisualStateCallback`），再 draw。 */
    private fun awaitVisualThenDraw(wv: WebView, done: (Outcome) -> Unit) {
        var fired = false
        val timeout = Runnable {
            if (!fired) {
                fired = true
                done(Outcome.Fail(Kind.VISUAL_TIMEOUT, "等视觉帧超时（${visualTimeoutMs}ms）——页面载完了但没画出来"))
            }
        }
        main.postDelayed(timeout, visualTimeoutMs)
        runCatching {
            wv.postVisualStateCallback(
                System.nanoTime(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (fired) return
                        fired = true
                        main.removeCallbacks(timeout)
                        done(drawAndJudge(wv))
                    }
                },
            )
        }.onFailure {
            if (!fired) {
                fired = true
                main.removeCallbacks(timeout)
                done(Outcome.Fail(Kind.EXCEPTION, "postVisualStateCallback 失败：${it.message}"))
            }
        }
    }

    /** 画一张并按 [WhiteImageJudge] 判真图/白图/灰图。 */
    private fun drawAndJudge(wv: WebView): Outcome {
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) {
            return Outcome.Fail(Kind.SIZE_ZERO, "WebView 尺寸为 0（${w}x$h），没法建位图")
        }
        return runCatching {
            val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp))
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val verdict = WhiteImageJudge.judge(pixels, w, h)
            if (verdict.classification != WhiteImageJudge.Classification.RICH) {
                bmp.recycle()
                val what = when (verdict.classification) {
                    WhiteImageJudge.Classification.UNIFORM_WHITE -> "整幅白"
                    WhiteImageJudge.Classification.UNIFORM_BLANK -> "整幅同色（灰/黑）"
                    WhiteImageJudge.Classification.EMPTY -> "空位图"
                    else -> "同色"
                }
                return Outcome.Fail(
                    Kind.UNIFORM,
                    "画出来是$what，没有内容（最大同色占比 ${
                        String.format(java.util.Locale.US, "%.1f%%", verdict.dominantRatio * 100)
                    }）——页面没真正渲染",
                )
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            Outcome.Ok(out.toByteArray(), w, h, verdict.dominantRatio)
        }.getOrElse {
            Outcome.Fail(Kind.EXCEPTION, "取图异常：${it.javaClass.simpleName} ${it.message}")
        }
    }
}
