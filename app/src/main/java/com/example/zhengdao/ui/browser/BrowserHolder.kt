package com.example.zhengdao.ui.browser

import android.content.Context
import android.webkit.WebView
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
 * - [pendingUrl]：外部打开入口（终端链接点击写入）；
 * - [requestBrowserTab] / [consumeTabRequest]：请求主页切到玄览（一次性）；
 * - [obtain] / [view]：取活跃引擎/WebView 的转发口。
 *
 * 原「跨 Tab 保留的界面状态」（rememberPage/lastUrl/everLoaded）随多标签迁到
 * [BrowserTabManager.Tab] 的 [BrowserTabState]（每标签一份；语义由 BrowserTabStateTest 钉住）。
 */
object BrowserHolder {

    /**
     * 外部打开入口（§4.6）：终端链接点击等把待加载 URL 写在这里；
     * 玄览组合时由 [BrowserTabManager.openExternal] 一次性消费（防重复加载）。
     */
    @Volatile
    var pendingUrl: String? = null

    /** 切 Tab 请求（一次性）：终端在独立 Activity 点链接 → 回主页 → MainActivity ON_RESUME 消费。 */
    private val tabRequested = AtomicBoolean(false)

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
}
