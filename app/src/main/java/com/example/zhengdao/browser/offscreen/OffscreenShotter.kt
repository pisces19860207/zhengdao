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
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
    /**
     * 637：**离线页专用**——断网、禁文件访问、禁 JS 桥。
     *
     * 默认 false，既有调用（262/263 探针、玄览取图）一个字都不变。置 true 时只做加法：
     * · `blockNetworkLoads(true)`：http/https 一律失败 ⇒「断网仍能出图」是**结构性**成立，
     *   不是"我们没写请求"这种口头保证；
     * · `allowFileAccess(false)`＋两个 `allow*FromFileURLs(false)`：页内脚本读不到任何
     *   本地文件（`file:///android_asset` 仍可载入，那是 App 自己的随包资源）；
     * · 不装任何 `addJavascriptInterface`（本类从来没有，这里写明是防后人加）。
     */
    private val offlineOnly: Boolean = false,
    /** 637：「页内脚本就绪」轮询的超时（只在 [capture] 传了 readyScript 时用）。 */
    private val readyTimeoutMs: Long = 10_000,
    /** 637：页内 `console.*` 的出口（排查离屏页时唯一能看见 JS 侧说了什么的地方）。 */
    private val onConsole: ((String) -> Unit)? = null,
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

        /**
         * 637：**页面自己报的错**（图源码有语法错、页面说高度不合法等）。
         *
         * 与 [LOAD_ERROR] 分开：那个是"没载进来"（网络/证书），这个是"载进来了但活没干成"，
         * 给用户看的话术不一样——后者要把页面的原话原样带出去。
         */
        PAGE_ERROR,

        /** 取图环节抛异常。 */
        EXCEPTION,
    }

    private val main = Handler(Looper.getMainLooper())

    companion object {
        /** 637：页内脚本轮询间隔（够密，又不至于把主线程的 evaluateJavascript 排队排爆）。 */
        private const val READY_POLL_MS = 120L

        /** 637：改完子视图尺寸后，等布局/合成一帧再拍（0 会拍到改之前那一帧）。 */
        private const val RELAYOUT_SETTLE_MS = 220L

        /** 637：单张位图的高度上限（像素）——1080 宽 × 6000 高 × 4 字节 ≈ 26MB，留足余量。 */
        const val MAX_CAPTURE_HEIGHT_PX = 6000
    }

    /**
     * 载入 [url] 并取一张图。阻塞直到出结果或超时。
     *
     * @param readyScript 637：页面载完后**反复执行**的 JS 表达式，返回一个普通字符串：
     *   `ok:<设备像素高>` ⇒ 按这个高度重排 WebView 再拍（拍内容的紧边，不是整屏）；
     *   `err:<首行错误>` ⇒ 立刻失败（错误原样带给用户，不许静默留白）；
     *   其它（`wait` 之类）⇒ 隔 [READY_POLL_MS] 再问一次，直到 [readyTimeoutMs]。
     *   传 null ⇒ 老行为：页面载完就等视觉帧，拍整屏。
     * @return [Outcome.Ok] 只在**判定为真图**时返回；白图/灰图一律 [Outcome.Fail]。
     */
    fun capture(url: String, readyScript: String? = null): Outcome {
        val latch = CountDownLatch(1)
        var out: Outcome = Outcome.Fail(Kind.EXCEPTION, "内部错误：没有回填结果")
        main.post { runSession(url, readyScript) { r -> out = r; latch.countDown() } }
        val budget = pageTimeoutMs + visualTimeoutMs + 10_000 +
            if (readyScript == null) 0 else readyTimeoutMs
        if (!latch.await(budget, TimeUnit.MILLISECONDS)) {
            return Outcome.Fail(Kind.PAGE_TIMEOUT, "整体等待超时（页面或视觉帧一直没到）")
        }
        return out
    }

    // ── 一次完整会话（全程主线程） ──────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun runSession(url: String, readyScript: String?, done: (Outcome) -> Unit) {
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
            if (offlineOnly) {
                // 637：断网＋禁文件＋禁跨源，三件事都在这里落地（判据"断网仍成功"靠它结构性成立）
                runCatching { wv.settings.blockNetworkLoads = true }
                // 缓存也要关：只加 blockNetworkLoads 的话，**命中缓存的页面照样能"载入成功"**
                // （637 实测：对照组 example.com 从缓存里出图了，控制变量失效）。
                runCatching { wv.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE }
                runCatching { wv.settings.allowFileAccess = false }
                runCatching { wv.settings.allowContentAccess = false }
                runCatching { wv.settings.allowFileAccessFromFileURLs = false }
                runCatching { wv.settings.allowUniversalAccessFromFileURLs = false }
                runCatching { wv.settings.javaScriptCanOpenWindowsAutomatically = false }
                // 不装 JS 桥：本类从未 addJavascriptInterface，写在这里是给后人看的红线。
            }
            wv.setBackgroundColor(0xFFFFFFFF.toInt())

            onConsole?.let { sink ->
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        sink("${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                        return true
                    }
                }
            }

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
            val viewportWidth = maxOf(1, dm.widthPixels)

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
                    if (readyScript == null) {
                        awaitVisualThenDraw(wv) { finish(it) }
                    } else {
                        awaitScriptReady(wv, readyScript, viewportWidth) { finish(it) }
                    }
                }
            }
            main.postDelayed(awaitPage, 100)
        }.onFailure {
            finish(Outcome.Fail(Kind.EXCEPTION, "取图异常：${it.javaClass.simpleName} ${it.message}"))
        }
    }

    /**
     * 637：页内脚本就绪轮询——**这是"页面自己说它画完了"的唯一通道**。
     *
     * 为什么不能沿用 `onPageFinished`：那个信号只说明 HTML 到了，不代表图算完了；
     * 也不能固定 sleep 一个数——图多大、多复杂，只有页面自己知道。
     *
     * 协议（与 `assets/mermaid/mermaid.html` 是同一份约定）：
     * `ok:<设备像素高>` / `err:<首行错误>` / 其它 = 继续等。
     */
    private fun awaitScriptReady(
        wv: WebView,
        script: String,
        viewportWidth: Int,
        done: (Outcome) -> Unit,
    ) {
        var settled = false
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        val timeUp = "等渲染结果超时（${readyTimeoutMs}ms）——页面没有回话"

        val giveUp = Runnable {
            if (!settled) {
                settled = true
                done(Outcome.Fail(Kind.VISUAL_TIMEOUT, timeUp))
            }
        }
        main.postDelayed(giveUp, readyTimeoutMs)

        lateinit var tick: Runnable
        lateinit var onProbe: (String?) -> Unit

        tick = Runnable {
            if (!settled) {
                if (System.currentTimeMillis() > deadline) {
                    main.removeCallbacks(giveUp)
                    settled = true
                    done(Outcome.Fail(Kind.VISUAL_TIMEOUT, timeUp))
                } else {
                    runCatching { wv.evaluateJavascript(script) { raw -> onProbe(raw) } }
                        .onFailure {
                            settled = true
                            main.removeCallbacks(giveUp)
                            done(Outcome.Fail(Kind.EXCEPTION, "页内脚本执行失败：${it.message}"))
                        }
                }
            }
        }

        onProbe = { raw ->
            if (!settled) {
                when (val probe = parseProbe(unquoteJsString(raw))) {
                    Probe.Wait -> main.postDelayed(tick, READY_POLL_MS)
                    is Probe.Err -> {
                        settled = true
                        main.removeCallbacks(giveUp)
                        done(Outcome.Fail(Kind.PAGE_ERROR, probe.message))
                    }
                    is Probe.Ok -> {
                        settled = true
                        main.removeCallbacks(giveUp)
                        if (probe.heightPx < 8) {
                            done(Outcome.Fail(Kind.PAGE_ERROR, "页面报了成功但高度不合法：${probe.heightPx}"))
                        } else {
                            resizeToContent(wv, viewportWidth, probe.heightPx)
                            main.postDelayed({ awaitVisualThenDraw(wv) { done(it) } }, RELAYOUT_SETTLE_MS)
                        }
                    }
                }
            }
        }

        main.postDelayed(tick, READY_POLL_MS)
    }

    /**
     * 拍**内容紧边**而不是整屏：把子视图高度改成内容高度。
     *
     * 宽仍是真实视口宽（Chromium 的视口宽度必须是真的，否则页面按 1px 排版）。
     */
    private fun resizeToContent(wv: WebView, viewportWidth: Int, contentHeightPx: Int) {
        val lp = wv.layoutParams ?: return
        lp.width = viewportWidth
        lp.height = contentHeightPx.coerceIn(8, MAX_CAPTURE_HEIGHT_PX)
        wv.layoutParams = lp
        wv.requestLayout()
    }

    /** 探针回话的三种语义（与页面协议一一对应，别在这里加第四种）。 */
    private sealed interface Probe {
        data object Wait : Probe

        data class Ok(val heightPx: Int) : Probe

        data class Err(val message: String) : Probe
    }

    private fun parseProbe(text: String?): Probe = when {
        text == null || text.isEmpty() || text == "wait" -> Probe.Wait
        text.startsWith("err:") -> Probe.Err(text.removePrefix("err:"))
        text.startsWith("ok:") -> Probe.Ok(text.removePrefix("ok:").trim().toIntOrNull() ?: -1)
        else -> Probe.Wait
    }

    /** `evaluateJavascript` 回的是 JSON 字面量（带引号、可能 `\uXXXX`）⇒ 解成普通字符串。 */
    private fun unquoteJsString(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (s.isEmpty() || s == "null") return null
        if (!s.startsWith("\"")) return s
        return runCatching { org.json.JSONTokener(s).nextValue() as? String }.getOrNull()
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
