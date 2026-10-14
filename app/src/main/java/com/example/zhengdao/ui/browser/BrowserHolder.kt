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

    // ── 跨 Tab 保留的界面状态（§B 顺序 32，2026-10-14 真机验收立账） ──────────────
    //
    // 现象：切走再切回玄览 ⇒ 空态重现、地址栏被清空（看着像"页面没了"，其实 WebView
    // 由本类保活、页面内容还在）。
    // 根因：丢的是 **Compose 侧**状态——BrowserScreen 里那几个 `remember { mutableStateOf }`
    // 随离开组合一起没了。
    // 修法：把「上次 URL」与「是否成功加载过」搬到这里（与 [pendingUrl] 同处：跨 Tab、
    // 跨 Activity 都活得下来），BrowserScreen 入场时回填。前进/后退能力不用另存 ——
    // 它本就活在 WebView 的历史栈里，入场直接问引擎。

    @Volatile
    private var lastUrl: String = ""

    @Volatile
    private var everLoadedFlag: Boolean = false

    /** 记下当前页面（onPageStarted / 外部打开入口调用）。空 URL 不覆盖上次记录。 */
    fun rememberPage(url: String, loaded: Boolean) {
        if (url.isNotEmpty()) lastUrl = url
        if (loaded) everLoadedFlag = true
    }

    /** 上次加载过的 URL（空串 = 从未打开过页面）。 */
    fun lastUrl(): String = lastUrl

    /** 是否成功加载过页面（决定入场要不要显示空态引导）。只涨不落。 */
    fun everLoaded(): Boolean = everLoadedFlag

    /** 仅单测用：清空界面状态（object 单例会跨用例串味）。 */
    internal fun resetUiStateForTest() {
        lastUrl = ""
        everLoadedFlag = false
    }
}
