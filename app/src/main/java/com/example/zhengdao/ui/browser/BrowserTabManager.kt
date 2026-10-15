package com.example.zhengdao.ui.browser

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.zhengdao.R
import com.example.zhengdao.browser.BrowserMemoryProbe
import com.example.zhengdao.browser.BrowserTabPolicy
import com.example.zhengdao.browser.EngineSecurityConfig
import com.example.zhengdao.browser.NavigationDelegate
import com.example.zhengdao.browser.WebViewEngine
import com.example.zhengdao.rootfs.RunLog

/**
 * 玄览多标签管理器（第⑨刀 B · 阶段 2 §4.2）——按 tabId 管多个 Engine + 渲染优先级 + 生命周期。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1 / §4.2。
 * 替换原 [BrowserHolder] 的单实例角色（其 KDoc 自陈"阶段 2 按 tabId 管多个 Engine，本类随之替换"）；
 * [BrowserHolder] 保留为对外facade（pendingUrl / 切 Tab 请求），tab 语义全部落到本类。
 *
 * **线程模型**：全部公开方法限定 UI 线程调用（Compose 事件 / MainActivity 生命周期 /
 * TerminalActivity 的 UI 线程入口）。WebView 创建与回调本就 UI 线程；标签状态用
 * Compose snapshot state 承载（UI 线程读写），切 Tab 时列表/活跃 id 的变化直接驱动重组。
 *
 * **数据目录隔离的平台现实（第⑨刀 B 查证，② AOSP 原文）**：
 * `WebView.setDataDirectorySuffix` 是**静态、每进程一次性**（须在首个 WebView 创建前调），
 * 设计意图是**多进程间**隔离（"Each directory can be used by only one process… different
 * processes in the same application cannot directly share WebView-related data"，
 * `core/java/android/webkit/WebView.java`）。⇒ **单进程内的多个标签不可能各有数据目录**，
 * cookie / localStorage / cache 在同进程的所有标签间共享（与桌面浏览器普通标签一致）。
 * 进程级 suffix 在 `ZhengdaoApp.onCreate` 统一设（见该处注释）。
 *
 * **每标签真正做到的隔离**：独立 WebView 实例（独立历史栈 / 页面状态 / JS 上下文 /
 * 渲染进程优先级）＋ 后台 onPause + RENDERER_PRIORITY_WAIVED（§4.2 两条）。
 */
object BrowserTabManager {

    /** 一个标签页：WebView 实例 + 引擎 + 可观测的界面状态（[BrowserTabState]）。 */
    class Tab(
        val id: Int,
        val webView: WebView,
        val engine: WebViewEngine,
    ) {
        val state = BrowserTabState()
    }

    /** 按创建顺序排列的标签（SnapshotStateList：增删直接驱动标签条重组）。 */
    private val tabs: SnapshotStateList<Tab> = mutableStateListOf()

    /** 当前活跃标签 id（null = 无标签，玄览显示空态）。 */
    private var activeId by mutableStateOf<Int?>(null)

    private var nextId = 1

    /** 全部标签（创建序）。返回快照，调用方不应改。 */
    fun tabs(): List<Tab> = tabs.toList()

    /** 当前活跃标签（null = 空态）。 */
    fun activeTab(): Tab? = activeId?.let { id -> tabs.firstOrNull { it.id == id } }

    /** 当前活跃引擎（null = 空态）。 */
    fun activeEngine(): WebViewEngine? = activeTab()?.engine

    /** 当前活跃 WebView（AndroidView factory 用；null = 空态）。 */
    fun view(): WebView? = activeTab()?.webView

    /** 标签数。 */
    fun count(): Int = tabs.size

    /**
     * 取活跃引擎；无任何标签时先建一个（首入玄览 / 外部打开各路径都走这里）。
     * 返回的引擎保证非空（首建失败除外——WebView 构造不会失败，省略该分支）。
     */
    fun obtain(context: Context): WebViewEngine {
        activeEngine()?.let { return it }
        newTab(context)
        return activeEngine()!!
    }

    /**
     * 开新标签并激活。到上限（[BrowserTabPolicy.MAX_TABS]）返回 null（调用方给提示）。
     * @param url 非空则建后即加载（外部打开入口 / 终端链接）。
     */
    fun newTab(context: Context, url: String? = null): Int? {
        if (!BrowserTabPolicy.canOpenMore(tabs.size)) return null
        val app = context.applicationContext
        val id = nextId++
        // 空 WebView 内存基线（阶段 0 三条之一）：每个标签首建前后各采一次，差值进 RunLog
        BrowserMemoryProbe.log("before-webview#tab$id")
        // §B 61（2026-10-15）：WebView 构造是本类唯一已知会间歇卡死（≥100s）的点，
        // 且卡点在 Chromium 内部（App 侧修不了）⇒ 只能加细观测：构造前后各落一行
        // 带线程名与耗时的日志，把「构造真卡」与「探针/日志自身卡」区分开。
        RunLog.log("玄览 newTab#tab$id：主线程开始构造 WebView（thread=${Thread.currentThread().name}）")
        val ctorBeginNanos = System.nanoTime()
        val wv = WebView(app)
        val ctorMs = (System.nanoTime() - ctorBeginNanos) / 1_000_000
        RunLog.log(
            "玄览 newTab#tab$id：WebView 构造返回（${ctorMs}ms${if (ctorMs > 2000) "，异常缓慢" else ""}）",
        )
        BrowserMemoryProbe.log("after-webview#tab$id")
        val eng = WebViewEngine(wv)
        val tab = Tab(id, wv, eng)
        // 安全配置 + 每标签委托在建时即装好（后台标签的加载态照常演进，不依赖 UI 组合）
        eng.applySecurityConfig(EngineSecurityConfig())
        eng.setNavigationDelegate(delegateFor(tab, app))
        tabs.add(tab)
        selectTab(id)
        if (!url.isNullOrEmpty()) eng.loadUrl(url)
        return id
    }

    /** 关闭标签。关活跃标签时按 [BrowserTabPolicy.nextActiveAfterClose] 换活跃；最后一个关掉即空态。 */
    fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val wasActive = activeId == id
        val nextId2 = BrowserTabPolicy.nextActiveAfterClose(tabs.map { it.id }, id, activeId)
        tabs.remove(tab)
        if (wasActive) {
            activeId = nextId2
            // 先摘挂载再销毁（WebView 挂在窗口上时 destroy 是未定义行为）；
            // 活跃标签此刻还挂在 AndroidView 上 ⇒ removeView 后 Compose 那次 dispose 的
            // removeView 自然成 no-op。
            destroySafely(tab.webView)
            // 新活跃标签恢复优先级/生命周期
            activeTab()?.let { resumeAndPromote(it) }
        } else {
            // 后台标签从没挂进窗口，直接销毁
            destroySafely(tab.webView)
        }
    }

    /** 激活指定标签（旧的退后台：onPause + WAIVED；新的回前台：onResume + IMPORTANT）。 */
    fun selectTab(id: Int) {
        if (activeId == id) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTab()?.let { pauseAndWaive(it) }
        activeId = id
        resumeAndPromote(tab)
    }

    /** 活跃标签直接导航（地址栏"前往"用；scheme 预检在 UI 层）。 */
    fun navigateActive(input: String) {
        activeTab()?.engine?.loadUrl(input)
    }

    /** 整个 App 退后台（MainActivity ON_PAUSE）：全部标签 onPause + 全部降 WAIVED。 */
    fun pauseAll() {
        tabs.forEach { tab ->
            runCatching { tab.webView.onPause() }
            runCatching { tab.webView.setRendererPriorityPolicy(BrowserTabPolicy.RENDERER_PRIORITY_WAIVED, false) }
        }
    }

    /** 回前台（MainActivity ON_RESUME）：活跃标签 onResume + 升 IMPORTANT。 */
    fun resumeActive() {
        activeTab()?.let { resumeAndPromote(it) }
    }

    /**
     * 外部打开入口（终端链接点击等，§4.6）：**开新标签**加载（[BrowserTabPolicy.externalOpenShouldCreateTab]；
     * 满 20 时回退当前活跃标签）。由 BrowserScreen 组合时消费 BrowserHolder.pendingUrl 后调。
     */
    fun openExternal(context: Context, url: String) {
        if (url.isEmpty()) return
        if (BrowserTabPolicy.externalOpenShouldCreateTab(tabs.size)) {
            newTab(context, url)
        } else {
            activeTab()?.engine?.loadUrl(url)
        }
    }

    /**
     * 回收用（第⑱刀 · D-4）：把**全部**标签从管理器摘下并返回它们的 WebView，交给
     * [BrowserHolder.releaseIfIdle] 统一销毁。
     *
     * **只摘不销毁**：销毁必须由持有回收出口的 [BrowserHolder] 单独做（单一 destroy 出口，
     * 门禁可钉）；本方法只把管理器的"活引用"清干净，避免留下已销毁实例的引用。
     * 调用方须先确认 [BrowserHolder.isIdle]（没有标签显示在界面上）。
     */
    internal fun detachAllForRelease(): List<WebView> {
        val detached = tabs.map { it.webView }
        tabs.clear()
        activeId = null
        return detached
    }

    /** 仅单测用：清空全部标签态（object 单例会跨用例串味）。WebView 销毁不在此路径（单测无 WebView）。 */
    internal fun resetForTest() {
        tabs.clear()
        activeId = null
        nextId = 1
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun pauseAndWaive(tab: Tab) {
        runCatching { tab.webView.onPause() }
        runCatching {
            tab.webView.setRendererPriorityPolicy(BrowserTabPolicy.RENDERER_PRIORITY_WAIVED, false)
        }
    }

    private fun resumeAndPromote(tab: Tab) {
        runCatching { tab.webView.onResume() }
        runCatching {
            tab.webView.setRendererPriorityPolicy(BrowserTabPolicy.RENDERER_PRIORITY_IMPORTANT, false)
        }
    }

    private fun destroySafely(webView: WebView) {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }

    /**
     * 每标签导航委托：把页面事件写进**该标签**的状态（后台标签照常演进）。
     * scheme 拦截与消息文案与原单标签版逐字一致（[BrowserHolder] 时代的 BrowserScreen 逻辑）。
     */
    private fun delegateFor(tab: Tab, app: Context): NavigationDelegate = object : NavigationDelegate {
        override fun shouldOverrideUrlLoading(url: String): Boolean {
            val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase() }.getOrNull()
            if (scheme !in setOf("http", "https")) {
                tab.state.onBlocked(app.getString(R.string.browser_blocked_scheme))
                return true
            }
            return false
        }

        override fun onPageStarted(url: String) {
            tab.state.onPageStarted(url)
        }

        override fun onPageFinished(url: String) {
            tab.state.onPageFinished(url)
        }

        override fun onReceivedTitle(title: String) {
            tab.state.onReceivedTitle(title)
        }

        override fun onSslError(url: String) {
            tab.state.onBlocked(app.getString(R.string.browser_ssl_error))
        }

        override fun onPageError(url: String, message: String) {
            tab.state.onBlocked(app.getString(R.string.browser_page_error))
        }

        override fun onSafeBrowsingHit(url: String, threatType: Int) {
            tab.state.onBlocked(app.getString(R.string.browser_safe_browsing_hit))
        }
    }
}
