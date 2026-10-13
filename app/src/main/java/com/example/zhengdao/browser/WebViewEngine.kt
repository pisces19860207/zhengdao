package com.example.zhengdao.browser

import android.webkit.WebView

/**
 * WebViewEngine —— [BrowserEngine] 的系统 WebView 实现（骨架）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §2.1（复用系统 WebView，
 * 不自带/不自研内核）、§2.2（渲染方案全景对比的结论）、§4.4（安全加固清单落地点）。
 *
 * 实现要点（留给填肉时，② 均已在文档定稿）：
 * - 安全配置：applySecurityConfig 逐项映射 WebSettings（Safe Browsing / 混合内容 NEVER_ALLOW /
 *   文件访问三连关 / databaseEnabled=false / setDataDirectorySuffix 隔离）。
 * - 导航拦截：WebViewClient.shouldOverrideUrlLoading + shouldInterceptRequest 硬拦
 *   file:// / intent:// / tel:// / content://；onReceivedSslError 一律 cancel。
 * - evaluateJavascript：webView.evaluateJavascript；**返回值必须过 SensitiveRedactor**（§4.4 红线）；
 *   结果限长 64KB 防 OOM（§4.5）。
 * - 截图：canvas 绘制取 Bitmap → PNG 字节；agent 模式下由上层决定落盘位置
 *   （MediaStore → Pictures/Screenshots，§4.4）。
 * - 桥通信：postWebMessage / WebMessagePort，**不用 addJavascriptInterface**（§4.4）。
 *
 * 状态：骨架（未实现）。阶段 1 填肉。
 */
class WebViewEngine(
    private val webView: WebView,
) : BrowserEngine {

    override val engineId: String = "webview-system"

    override fun loadUrl(url: String) {
        // TODO(阶段1)：scheme 预检（http/https 放行，其余拒）→ webView.loadUrl(url)
        TODO("阶段1")
    }

    override fun evaluateJavascript(script: String, callback: (String?) -> Unit) {
        // TODO(阶段1)：webView.evaluateJavascript(script) { value ->
        //   callback(SensitiveRedactor().redactText(value.orEmpty()).take(MAX_RESULT_LENGTH))
        // }
        TODO("阶段1")
    }

    override fun captureScreenshot(callback: (ByteArray?) -> Unit) {
        // TODO(阶段1)：WebView canvas → Bitmap → PNG ByteArray
        TODO("阶段1")
    }

    override fun setNavigationDelegate(delegate: NavigationDelegate?) {
        // TODO(阶段1)：包一层 WebViewClient + WebChromeClient，把事件转给 delegate
        TODO("阶段1")
    }

    override fun applySecurityConfig(config: EngineSecurityConfig) {
        // TODO(阶段1)：WebSettings 逐项应用（见类 KDoc 清单）
        TODO("阶段1")
    }

    companion object {
        /** execJS 返回值限长（§4.5：如 64KB，防 OOM）。 */
        const val MAX_RESULT_LENGTH = 64 * 1024
    }
}
