package com.example.zhengdao.ui.browser

import android.content.ComponentCallbacks2
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.browser.WebViewEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 玄览对外门面（第⑨刀 B 起为薄门面）——跨界面入口 + 切 Tab 请求。
 *
 * 为什么需要：玄览 Tab 在 `when (tab)` 分支里，切到底栏其他 Tab 时 BrowserScreen 离开组合、
 * remember 的 WebView 会被回收——用户切走再回来页面白了。终端链接点击（阶段 1.5）发生在
 * TerminalActivity（独立 Activity），更要求实例跨 Activity 存活。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1（分层）、§4.6（打开层入口）。
 *
 * 防泄漏：WebView 用 **applicationContext** 创建，不持 Activity 引用。
 *
 * 阶段 2（第⑨刀 B）拆分：多标签本体（按 tabId 管多个 Engine / 渲染优先级 / 生命周期 /
 * 每标签状态）搬到 [BrowserTabManager]；本类只留**跨界面入口**（TerminalActivity 与
 * MainActivity 都在用，不动它们）：
 * - [pendingUrl]：外部打开入口（终端链接点击写入）——**Compose 可观察状态**
 *   （P1-8，2026-10-19：已停在玄览时点链接也要立刻被消费，裸 @Volatile var 驱动不了重组）；
 * - [requestBrowserTab] / [consumeTabRequest]：请求主页切到玄览（一次性）；
 * - [obtain] / [view]：取活跃引擎/WebView 的转发口。
 *
 * 原「跨 Tab 保留的界面状态」（rememberPage/lastUrl/everLoaded）随多标签迁到
 * [BrowserTabManager.Tab] 的 [BrowserTabState]（每标签一份；语义由 BrowserTabStateTest 钉住）。
 *
 * **受控回收出口（第⑱刀 · 审计 D-4）**：WebView 用 applicationContext 建、跨 Tab 保活，
 * 此前**全仓没有任何 destroy() 出口** —— 内存压力下没人回收。现在由 [onTrimMemory]
 * （两 Activity 的 onTrimMemory 各调一行）在 [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] 档
 * 触发 [releaseIfIdle]：只有"没有标签显示在界面上"（玄览离线）时才真销毁并清空，
 * 下次 [obtain] 重建。**刻意不是"每次 obtain 都新建"** —— 单例保活是本类的设计前提。
 */
object BrowserHolder {

    /**
     * 外部打开入口（§4.6）：终端链接点击等把待加载 URL 写在这里；
     * 玄览界面读到它时由 [BrowserTabManager.openExternal] 消费并清槽（防重复加载）。
     *
     * **P1-8（第五十五批 · §B 77 审计，2026-10-19）：必须是 Compose 可观察状态。**
     * 旧写法是裸 `@Volatile var` —— Volatile 只保证**跨线程可见性**，不保证**重组**：
     * 用户已经停在玄览 Tab 时从终端点一条链接，值确实写进去了，但 BrowserScreen 不会因为
     * 这次写入而重新组合/重跑 effect ⇒ 整条链接石沉大海（要等下次进玄览才被消费）。
     * 现在 [androidx.compose.runtime.State] 承载，写入即失效读侧、驱动重组，消费侧改以
     * 「值」为 key 的 `LaunchedEffect`（判据见 BrowserPendingUrlObservableGateTest S1–S6）。
     *
     * 对外签名不变：写入方（[com.example.zhengdao.TerminalActivity] /
     * [com.example.zhengdao.ui.baopu.BaopuPreview]）仍是 `BrowserHolder.pendingUrl = url`
     * 一行赋值，禁再造第二条「待打开 URL」通道。
     *
     * 线程：写入方都在 UI 线程（点链接、切 Tab 都是 UI 事件）；UI 线程读写 snapshot state
     * 正是它的适用场景，跨线程可见性由快照机制兜底。
     */
    var pendingUrl: String? by mutableStateOf<String?>(null)

    /** 切 Tab 请求（一次性）：终端在独立 Activity 点链接 → 回主页 → MainActivity ON_RESUME 消费。 */
    private val tabRequested = AtomicBoolean(false)

    /**
     * 玄览界面此刻是否显示在屏幕上（由 [BrowserScreen] 组合进出维护）。
     * 只读区在 UI 线程，但 TRIM 回调也可能来自别的线程 ⇒ @Volatile。
     */
    @Volatile
    private var browserOnScreen = false

    /** 取（或首建）全局活跃引擎实例（无标签时建第一个）。 */
    fun obtain(context: Context): WebViewEngine = BrowserTabManager.obtain(context)

    /** 当前活跃 WebView 实例（AndroidView factory 用；未初始化时为 null）。 */
    fun view(): WebView? = BrowserTabManager.view()

    /** 请求主页切到玄览 Tab（TerminalActivity 点链接时调；MainActivity ON_RESUME 消费）。 */
    fun requestBrowserTab() {
        tabRequested.set(true)
    }

    /** 消费切 Tab 请求（一次性）；有待处理请求时返回 true。 */
    fun consumeTabRequest(): Boolean = tabRequested.compareAndSet(true, false)

    // ── 受控回收出口（第⑱刀 · D-4） ───────────────────────────────────────

    /** 玄览进入组合（[BrowserScreen] 显示）。 */
    internal fun onBrowserShown() {
        browserOnScreen = true
    }

    /** 玄览离开组合（切到别的底栏 Tab）。 */
    internal fun onBrowserHidden() {
        browserOnScreen = false
    }

    /**
     * 是否"没有活动标签需要保活"：**此刻没有标签显示在界面上、却仍离线持有 WebView**。
     *
     * 判据只用 ⑨刀落下的 [BrowserTabManager] 状态（标签数）+ 组合可见性，不新增状态源：
     * - 有标签且玄览离线 ⇒ 可回收（这正是 D-4 要治的"占着内存没人管"）；
     * - 玄览在线 ⇒ 不回收（挂载中的 WebView 不可 destroy，见 [releaseIfIdle]）；
     * - 没有标签 ⇒ 没有可回收对象。
     */
    internal fun isIdle(): Boolean = !browserOnScreen && BrowserTabManager.count() > 0

    /**
     * 受控回收（D-4）：把**离线持有**的全部 WebView 销毁并清空标签，下次 [obtain] 重建
     * （cookie / localStorage 落在 WebView 数据目录里，不随实例销毁而丢）。
     *
     * 为什么必须先 [isIdle]：`WebView.destroy()` 对一个**仍挂在窗口上、仍被界面持有**的实例
     * 是未定义行为（与 [BrowserTabManager.closeTab] 里"先 removeView 再 destroy"同一条纪律）。
     * TRIM 回调到达时玄览可能正显示着 —— 那种情况**宁可不回收**，不冒崩的风险。
     *
     * @return 真的回收了（销毁了 ≥1 个 WebView）为 true。
     */
    fun releaseIfIdle(): Boolean {
        if (!isIdle()) return false
        val released = BrowserTabManager.detachAllForRelease()
        released.forEach { destroySafely(it) }
        return released.isNotEmpty()
    }

    /**
     * 内存压力回调（[MainActivity] / [TerminalActivity] 的 onTrimMemory 各调一行）。
     *
     * 只在 [ComponentCallbacks2.TRIM_MEMORY_COMPLETE]（"进程已接近后台 LRU 尾、即将被系统回收"）
     * 才真回收；更轻的档位频繁出现且此时"切走底栏再回来"的页面必须还在（那是本类存在的理由）。
     *
     * @param level 见 [ComponentCallbacks2]（`TRIM_MEMORY_COMPLETE` = 80）。
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) releaseIfIdle()
    }

    /**
     * 三步安全销毁：先摘挂载 → 清页面 → destroy。
     * 先 removeView 是因为"挂在窗口上的 WebView 调 destroy 未定义"；三步各自 runCatching，
     * 任何一步失败都不拦住后面的清理（本方法只服务于回收，不吞噬调用方的异常语义）。
     */
    private fun destroySafely(webView: WebView) {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }
}
