// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * 263 批实验件：离屏（1dp 常驻、不显示）WebView 渲染取图——只为回答"这台机行不行"。
 *
 * 边界（工单钉死）：
 * - 只在 debug 构建可用（运行期查 FLAG_DEBUGGABLE）；唯一入口是 debug 源集独有的
 *   OffscreenProbeActivity，release 合并清单里根本没有它——"debug 构建 + 显式开关"双保险。
 * - 不进任何产品路径：不 import 玄览/巡按的任何类、不碰共享 WebView、不调
 *   WebView.enableSlowWholeDocumentDraw()（进程全局开关，会拖慢玄览自己的渲染）。
 * - 每格读数必带失败原因（枚举），失败不猜；PNG + JSON 落 [outputDir]。
 *
 * 四个模式（同一次启动顺序跑完）：
 * - A_1dp直画：工单字面形态——1dp 视图 loadUrl → onPageFinished → postVisualStateCallback
 *   → draw 到 Bitmap（位图只有几像素，量的是"机制通不通"）；
 * - B_放大后画：抓图瞬间临时放大到整屏再 draw、画完缩回 1dp（用户全程看不见）——
 *   这是"能拿全幅图"的候选形态；
 * - C_后台画：整屏形态下 moveTaskToBack 停 [backgroundWaitMs] 再 draw——量的是
 *   README 那条已知边界（"玄览要上屏才渲染"）会不会发生在挂窗视图上。
 * - D_后台新载：C 之后不回前台，整屏形态下后台 loadUrl 彩条页 → onPageFinished →
 *   停 [backgroundLoadWaitMs] 直画——量的是 README 边界的正格："后台**新载入**的页
 *   能不能渲染"（C 只证了已渲染页后台画不失真，这一格才是 262 兜底的生死格）。
 */
class OffscreenCaptureProbe(
    private val activity: Activity,
    private val outputDir: File,
    private val secondUrl: String = DEFAULT_SECOND_URL,
    private val onPageTimeoutMs: Long = 20_000,
    private val visualTimeoutMs: Long = 8_000,
    private val backgroundWaitMs: Long = 5_000,
    private val backgroundLoadWaitMs: Long = 3_000,
    private val webConfig: ProbeWebConfig,
) {

    companion object {
        const val TAG = "offscreen-probe"
        const val BATCH = "263"
        const val ASSET_URL = "file:///android_asset/offscreen_probe.html"
        const val DEFAULT_SECOND_URL = "https://www.baidu.com"
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var generation = 0
    private var requestSeq = 0L
    private var pageDone: (() -> Unit)? = null
    private var mainFrameError: String? = null
    private lateinit var report: ProbeReport

    fun run(onFinished: () -> Unit) {
        report = ProbeReport(BATCH, ProbeReport.isoNow(), deviceJson(), outputDir)
        if (!isDebugBuild()) {
            record(
                ProbeModeResult(
                    "GATE", "-", ProbeOutcome.DEBUG_DISABLED,
                    "非 debug 构建（FLAG_DEBUGGABLE=false），拒绝运行",
                    0, 0, 0, 0, 0, null, null, null, null,
                ),
            )
            wrapUp(onFinished)
            return
        }
        outputDir.mkdirs()
        val view = attach()
        probeUrls(view, listOf(ASSET_URL, secondUrl).distinct(), null, onFinished)
    }

    // ── 主流程：URL × 模式 ──

    private fun probeUrls(
        view: WebView,
        remaining: List<String>,
        lastUrl: String?,
        onFinished: () -> Unit,
    ) {
        val url = remaining.firstOrNull()
        if (url == null) {
            backgroundProbe(view, lastUrl ?: secondUrl, onFinished)
            return
        }
        generation++
        val gen = generation
        mainFrameError = null
        val start = System.currentTimeMillis()
        loadAndWait(view, url, gen) { loaded ->
            if (!loaded) {
                record(
                    row(
                        "A_1dp直画", url, ProbeOutcome.ONPAGE_TIMEOUT,
                        "onPageFinished ${onPageTimeoutMs}ms 未回（本 URL 整段作废）", start,
                    ),
                )
                record(
                    row(
                        "B_放大后画", url, ProbeOutcome.ONPAGE_TIMEOUT,
                        "页面没加载完，本格不另测", start,
                    ),
                )
                probeUrls(view, remaining.drop(1), lastUrl, onFinished)
                return@loadAndWait
            }
            awaitVisual(view, gen) { visualA ->
                if (!visualA) {
                    record(
                        row(
                            "A_1dp直画", url, ProbeOutcome.VISUAL_TIMEOUT,
                            "postVisualStateCallback ${visualTimeoutMs}ms 未回", start,
                        ),
                    )
                } else {
                    captureAt(view, "A_1dp直画", url, start, "a", null)
                }
                resizeFull(view) {
                    awaitVisual(view, gen) { visualB ->
                        if (!visualB) {
                            record(
                                row(
                                    "B_放大后画", url, ProbeOutcome.VISUAL_TIMEOUT,
                                    "放大后 visual ${visualTimeoutMs}ms 未回", start,
                                ),
                            )
                        } else {
                            captureAt(view, "B_放大后画", url, start, "b", null)
                        }
                        resizeDp1(view) {
                            main.post { probeUrls(view, remaining.drop(1), url, onFinished) }
                        }
                    }
                }
            }
        }
    }

    private fun backgroundProbe(view: WebView, url: String, onFinished: () -> Unit) {
        val start = System.currentTimeMillis()
        resizeFull(view) {
            awaitVisual(view, generation) { visualOk ->
                if (!visualOk) {
                    record(
                        row(
                            "C_后台画", url, ProbeOutcome.VISUAL_TIMEOUT,
                            "放大后 visual 未回，后台步无意义，跳过", start,
                        ),
                    )
                    wrapUp(onFinished)
                    return@awaitVisual
                }
                val moved = activity.moveTaskToBack(true)
                main.postDelayed(
                    {
                        captureAt(
                            view, "C_后台画", url, start, "c",
                            "movedToBack=$moved；停 ${backgroundWaitMs}ms 后直画（不等 visual——后台没有帧）",
                        )
                        resizeDp1(view) { backgroundLoadProbe(view, onFinished) }
                    },
                    backgroundWaitMs,
                )
            }
        }
    }

    private fun backgroundLoadProbe(view: WebView, onFinished: () -> Unit) {
        val start = System.currentTimeMillis()
        resizeFull(view) {
            generation++
            val gen = generation
            mainFrameError = null
            loadAndWait(view, ASSET_URL, gen) { loaded ->
                if (!loaded) {
                    record(
                        row(
                            "D_后台新载", ASSET_URL, ProbeOutcome.ONPAGE_TIMEOUT,
                            "任务在后台（C 段 moveTaskToBack 后未回前台），loadUrl 后 " +
                                "${onPageTimeoutMs}ms onPageFinished 未回",
                            start,
                        ),
                    )
                    resizeDp1(view) { wrapUp(onFinished) }
                    return@loadAndWait
                }
                main.postDelayed(
                    {
                        captureAt(
                            view, "D_后台新载", ASSET_URL, start, "d",
                            "任务仍在后台；放大到整屏后 loadUrl→onPageFinished→停 " +
                                "${backgroundLoadWaitMs}ms 直画（不等 visual——后台没有帧）",
                        )
                        resizeDp1(view) { wrapUp(onFinished) }
                    },
                    backgroundLoadWaitMs,
                )
            }
        }
    }

    private fun wrapUp(onFinished: () -> Unit) {
        main.post {
            webView?.let { v ->
                (v.parent as? ViewGroup)?.removeView(v)
                v.destroy()
            }
            webView = null
            onFinished()
        }
    }

    // ── 单步原语 ──

    private fun attach(): WebView {
        val view = WebView(activity)
        with(view.settings) {
            javaScriptEnabled = webConfig.javaScriptEnabled
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        view.setBackgroundColor(0xFFFFFFFF.toInt())
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                val done = pageDone
                pageDone = null
                done?.invoke()
            }

            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    mainFrameError = "${error.errorCode} ${error.description}"
                }
            }
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            view,
            FrameLayout.LayoutParams(dp1Px(), dp1Px(), Gravity.TOP or Gravity.START),
        )
        webView = view
        return view
    }

    private fun loadAndWait(view: WebView, url: String, gen: Int, cb: (Boolean) -> Unit) {
        var fired = false
        val timeout = Runnable {
            if (!fired && gen == generation) {
                fired = true
                cb(false)
            }
        }
        main.postDelayed(timeout, onPageTimeoutMs)
        pageDone = {
            if (!fired && gen == generation) {
                fired = true
                main.removeCallbacks(timeout)
                cb(true)
            }
        }
        view.loadUrl(url)
    }

    private fun awaitVisual(
        view: WebView,
        gen: Int,
        timeoutMs: Long = visualTimeoutMs,
        cb: (Boolean) -> Unit,
    ) {
        var fired = false
        val timeout = Runnable {
            if (!fired && gen == generation) {
                fired = true
                cb(false)
            }
        }
        main.postDelayed(timeout, timeoutMs)
        view.postVisualStateCallback(++requestSeq, object : WebView.VisualStateCallback() {
            override fun onComplete(request: Long) {
                if (!fired && gen == generation) {
                    fired = true
                    main.removeCallbacks(timeout)
                    cb(true)
                }
            }
        })
    }

    private fun captureAt(
        view: WebView,
        mode: String,
        url: String,
        start: Long,
        pngTag: String,
        preDetail: String?,
    ) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            record(row(mode, url, ProbeOutcome.SIZE_0, "view 尺寸为 0（${w}x${h}），没法建位图", start))
            return
        }
        val bmp = try {
            createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        } catch (t: Throwable) {
            record(
                row(
                    mode, url, ProbeOutcome.EXCEPTION,
                    "draw 抛了 ${t.javaClass.simpleName}: ${t.message}", start,
                ),
            )
            return
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val verdict = WhiteImageJudge.judge(pixels, w, h)
        val outcome = when {
            mainFrameError != null -> ProbeOutcome.LOAD_ERROR
            verdict.classification == WhiteImageJudge.Classification.RICH -> ProbeOutcome.OK
            verdict.classification == WhiteImageJudge.Classification.UNIFORM_WHITE ->
                ProbeOutcome.UNIFORM_WHITE
            else -> ProbeOutcome.UNIFORM_BLANK
        }
        val detail = listOfNotNull(
            preDetail,
            mainFrameError?.let { "主框架加载失败 $it" },
            verdict.describe(),
        ).joinToString("；")
        val png = savePng(bmp, "${pngTag}_${urlTag(url)}.png")
        val r = ProbeModeResult(
            mode, url, outcome, detail,
            w, h, bmp.width, bmp.height,
            System.currentTimeMillis() - start,
            png?.first, png?.second,
            verdict.colorHex(), verdict.dominantRatio,
        )
        bmp.recycle()
        record(r)
    }

    // ── 小件 ──

    private fun resize(view: WebView, wPx: Int, hPx: Int, cb: () -> Unit) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(wPx, hPx)
        lp.width = wPx
        lp.height = hPx
        view.layoutParams = lp
        view.requestLayout()
        main.post { cb() }
    }

    private fun resizeFull(view: WebView, cb: () -> Unit) = resize(
        view,
        activity.resources.displayMetrics.widthPixels,
        activity.resources.displayMetrics.heightPixels,
        cb,
    )

    private fun resizeDp1(view: WebView, cb: () -> Unit) = resize(view, dp1Px(), dp1Px(), cb)

    private fun dp1Px(): Int = maxOf(1, Math.round(1f * activity.resources.displayMetrics.density))

    private fun row(
        mode: String,
        url: String,
        outcome: ProbeOutcome,
        detail: String?,
        start: Long,
    ): ProbeModeResult {
        val v = webView
        val w = v?.width ?: 0
        val h = v?.height ?: 0
        return ProbeModeResult(
            mode, url, outcome, detail, w, h, w, h,
            System.currentTimeMillis() - start, null, null, null, null,
        )
    }

    private fun record(r: ProbeModeResult): ProbeModeResult {
        report.add(r)
        Log.i(TAG, r.line())
        return r
    }

    private fun savePng(bmp: Bitmap, name: String): Pair<String, String>? {
        return try {
            val f = File(outputDir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name to sha256(f)
        } catch (t: Throwable) {
            Log.w(TAG, "savePng failed: $t")
            null
        }
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
    }

    private fun urlTag(url: String): String = when (url) {
        ASSET_URL -> "asset"
        else -> url.substringAfter("://").take(20)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimEnd('_')
            .ifEmpty { "page" }
    }

    private fun deviceJson() = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("release", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("display", Build.DISPLAY)
        put("fingerprint", Build.FINGERPRINT)
        put("webviewVersion", webViewVersion())
        put("debuggable", isDebugBuild())
    }

    private fun webViewVersion(): String? = listOf(
        "com.google.android.webview",
        "com.android.webview",
        "com.huawei.webview",
        "com.hihonor.webview",
    ).firstNotNullOfOrNull { pkg ->
        try {
            activity.packageManager.getPackageInfo(pkg, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    private fun isDebugBuild(): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
