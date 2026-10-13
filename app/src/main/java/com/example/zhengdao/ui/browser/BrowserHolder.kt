package com.example.zhengdao.ui.browser

import android.content.Context
import android.webkit.WebView
import com.example.zhengdao.browser.BrowserMemoryProbe
import com.example.zhengdao.browser.WebViewEngine

/**
 * 玄览全局持有器 —— WebView / Engine 实例的跨界面保活。
 *
 * 为什么需要：玄览 Tab 在 `when (tab)` 分支里，切到底栏其他 Tab 时 BrowserScreen 离开组合、
 * remember 的 WebView 会被回收——用户切走再回来页面白了。终端链接点击（阶段 1.5）发生在
 * TerminalActivity（独立 Activity），更要求实例跨 Activity 存活。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1（分层）、§4.6（打开层入口）。
 *
 * 防泄漏：WebView 用 **applicationContext** 创建，不持 Activity 引用。
 *
 * 阶段 1：单实例（用户模式 MVP）。
 * 阶段 2（多标签，§4.2）：重构为按 tabId 管多个 Engine + 数据目录隔离，本类随之替换。
 */
object BrowserHolder {

    private var webView: WebView? = null

    var engine: WebViewEngine? = null
        private set

    /**
     * 外部打开入口（§4.6）：终端链接点击等把待加载 URL 写在这里；
     * 玄览组合时由 [consumePendingUrl] 一次性取出（防重复加载）。
     */
    @Volatile
    var pendingUrl: String? = null

    /** 切 Tab 请求（一次性）：终端在独立 Activity 点链接 → 回主页 → MainActivity ON_RESUME 消费。 */
    private val tabRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 取（或首建）全局引擎实例。 */
    fun obtain(context: Context): WebViewEngine {
        engine?.let { return it }
        val app = context.applicationContext
        // 空 WebView 内存基线（阶段 0 三条之一，2026-10-14 派工单）：首建前后各采一次，
        // 差值即"空 WebView 增量"，写 RunLog（真机采数归蓝总管）。
        BrowserMemoryProbe.log("before-webview")
        val wv = WebView(app)
        BrowserMemoryProbe.log("after-webview")
        val eng = WebViewEngine(wv)
        webView = wv
        engine = eng
        return eng
    }

    /** 当前 WebView 实例（AndroidView factory 用；未初始化时为 null）。 */
    fun view(): WebView? = webView

    /** 请求主页切到玄览 Tab（TerminalActivity 点链接时调；MainActivity ON_RESUME 消费）。 */
    fun requestBrowserTab() {
        tabRequested.set(true)
    }

    /** 消费切 Tab 请求（一次性）；有待处理请求时返回 true。 */
    fun consumeTabRequest(): Boolean = tabRequested.compareAndSet(true, false)

    /** 玄览侧消费 pendingUrl（取出并清空）。 */
    fun consumePendingUrl(): String? {
        val url = pendingUrl
        pendingUrl = null
        return url
    }
}
