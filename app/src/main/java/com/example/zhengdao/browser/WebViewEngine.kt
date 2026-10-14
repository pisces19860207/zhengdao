package com.example.zhengdao.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream

/**
 * WebViewEngine —— [BrowserEngine] 的系统 WebView 实现（阶段 1 已填肉）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §2.1 / §2.2 / §4.4 / §4.5。
 *
 * 已落地（② 文档定稿项）：
 * - scheme 白名单：loadUrl 与 shouldOverrideUrlLoading 双重拦截 file:// / intent:// /
 *   tel:// / content://（"浏览器本身"红线，agent 无法绕过，§4.4）。
 * - SSL 错误一律 cancel（生产不 proceed，§4.4）。
 * - Safe Browsing：settings 开启 + onSafeBrowsingHit 显示拦截页（§4.4）。
 * - evaluateJavascript：返回值先过 [SensitiveRedactor]（强制脱敏红线）再限长
 *   （64KB，§4.5 防 OOM）——agent 无法借 execJS 读原始隐私值绕过。
 * - 截图：WebView.draw → PNG 字节。agent 模式落盘位置由上层定
 *   （MediaStore → Pictures/Screenshots，§4.4）。
 * - 所有外部调用经 webView.post 包 UI 线程（桥从 HTTP 线程调也安全）。
 *
 * 已知限制（③ 待实测，真机阶段 1 验收时核）：
 * - 截图走 software canvas，硬件加速下视频/WebGL 区域可能空白；质量不足再评估 TextureView。
 * - evaluateJavascript 返回值是 JSON 编码字符串，脱敏作用于编码后文本；阶段 3 接桥时
 *   改为"解码 → 脱敏 → 再编码"更精确。
 * - onReceivedError 只报主 frame 错误（子资源错误不打扰用户）。
 */
class WebViewEngine(
    private val webView: WebView,
    private val redactor: SensitiveRedactor = SensitiveRedactor(),
) : BrowserEngine {

    override val engineId: String = "webview-system"

    private var delegate: NavigationDelegate? = null

    /** scheme 白名单（白名单外一律拒——"浏览器本身"红线，§4.4 导航拦截）。 */
    private val allowedSchemes = setOf("http", "https")

    override fun loadUrl(url: String) {
        webView.post {
            if (!isAllowedUrl(url)) return@post
            webView.loadUrl(url)
        }
    }

    override fun goBack() {
        webView.post { if (webView.canGoBack()) webView.goBack() }
    }

    override fun goForward() {
        webView.post { if (webView.canGoForward()) webView.goForward() }
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun evaluateJavascript(script: String, callback: (String?) -> Unit) {
        webView.post {
            webView.evaluateJavascript(script) { value ->
                // ① 脱敏（强制红线，agent 不可绕，§4.4）② 限长（§4.5，防 OOM）
                val masked = redactor.redactText(value.orEmpty())
                val out = if (masked.length > MAX_RESULT_LENGTH) {
                    masked.take(MAX_RESULT_LENGTH) + "…[截断]"
                } else {
                    masked
                }
                callback(out)
            }
        }
    }

    override fun captureScreenshot(callback: (ByteArray?) -> Unit) {
        webView.post {
            try {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(bitmap))
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                callback(out.toByteArray())
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    override fun setNavigationDelegate(delegate: NavigationDelegate?) {
        this.delegate = delegate
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!isAllowedUrl(url)) {
                    // 危险 scheme：浏览器本身硬拦（红线，agent 无法绕过；用户输入同样拦）
                    return true
                }
                return delegate?.shouldOverrideUrlLoading(url) ?: false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                delegate?.onPageStarted(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                delegate?.onPageFinished(url.orEmpty())
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError,
            ) {
                // 只报主 frame 错误（子资源失败不打扰用户）
                if (request?.isForMainFrame == true) {
                    delegate?.onPageError(
                        request.url?.toString().orEmpty(),
                        error.description?.toString().orEmpty(),
                    )
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // 生产一律 cancel，不 proceed（§4.4）
                delegate?.onSslError(error?.url.orEmpty())
                handler?.cancel()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                // 阶段 0 三条之一（2026-10-14 派工单并入本刀）：**资源加载级**拦截。
                // shouldOverrideUrlLoading 拦的是"点击导航"，本方法拦的是页面内嵌资源
                // （iframe/img/script src="intent://…" 之类）——点击级放过的漏网资源在这里兜底。
                val url = request.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (!isAllowedUrl(url)) {
                    logWarning("blocked resource scheme: $url")
                    // 返回空响应 = 阻断加载（不给页面任何内容）
                    return android.webkit.WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: android.webkit.SafeBrowsingResponse?,
            ) {
                delegate?.onSafeBrowsingHit(request?.url?.toString().orEmpty(), threatType)
                // 显示安全警告 interstitial（浏览器本身能力，与 agent 权限无关，§4.4）
                callback?.showInterstitial(true)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // JS 弹窗（alert/confirm/prompt）：阶段 1 一律 cancel（不卡页面、不弹系统框）。
            // §4.4「JS 弹窗收口」完整形态（App 对话框 + agent 模式路由 Policy）阶段 2/3 做——
            // 弹对话框要 Activity 上下文，而引擎只持 applicationContext 的 WebView，
            // 届时由 UI 层注入 jsDialogHandler lambda（TODO 阶段2）。
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }
        }
    }

    override fun applySecurityConfig(config: EngineSecurityConfig) {
        webView.settings.apply {
            javaScriptEnabled = config.javaScriptEnabled
            javaScriptCanOpenWindowsAutomatically = config.javaScriptCanOpenWindowsAutomatically
            domStorageEnabled = config.domStorageEnabled
            mixedContentMode = config.mixedContentMode
            allowFileAccess = config.allowFileAccess
            allowFileAccessFromFileURLs = config.allowFileAccessFromFileUrls
            allowUniversalAccessFromFileURLs = config.allowUniversalAccessFromFileUrls
            databaseEnabled = config.databaseEnabled
            safeBrowsingEnabled = config.safeBrowsingEnabled
            // TODO(阶段2)：dataDirectorySuffix 是 WebView **静态**方法（setDataDirectorySuffix），
            //   只能在 WebView 首次使用前调一次——多标签隔离时在 App 进程初始化处统一设，不在这里。
            // TODO(阶段2)：thirdPartyCookies / allowedJsInterfaces 落地
            //   （桥不用 addJavascriptInterface，走 postWebMessage/WebMessagePort，§4.4）。
        }
    }

    private fun isAllowedUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme in allowedSchemes
    }

    companion object {
        /** execJS 返回值限长（§4.5：如 64KB，防 OOM）。 */
        const val MAX_RESULT_LENGTH = 64 * 1024

        private const val TAG = "WebViewEngine"

        private fun logWarning(msg: String) = android.util.Log.w(TAG, msg)
    }
}
