// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

/**
 * 导航委托 —— §4.4 拦截点 + §4.6 终端链接安全确认层的回调面。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.4（导航拦截 / SSL / 混合内容）、
 * §4.6（终端链接点击的安全确认层）。
 *
 * 拦截职责（§4.4「导航拦截」行）：shouldOverrideUrlLoading + shouldInterceptRequest
 * 拦 file:// / intent:// / tel:// / content://，仅放行白名单 http/https；SSL 错误一律 cancel（生产不 proceed）。
 * agent 高权限不改变这些——危险 scheme 拦截属"浏览器本身"行为，agent 无法绕过（§4.3）。
 */
interface NavigationDelegate {

    /** 返回 true = 已处理（拦截默认加载）。危险 scheme 在此硬拦。 */
    fun shouldOverrideUrlLoading(url: String): Boolean

    fun onPageStarted(url: String) {}

    fun onPageFinished(url: String) {}

    /**
     * 页面标题到达（第⑨刀 B 多标签：标签条显示标题用）。
     * 注意：标题可能后于 onPageFinished 到，也可能为空（部分页不发 title）。
     */
    fun onReceivedTitle(title: String) {}

    /** SSL 错误：实现层一律 handler.cancel()（§4.4，生产不 proceed）。 */
    fun onSslError(url: String) {}

    fun onPageError(url: String, message: String) {}

    /**
     * Safe Browsing 命中回调（§4.4：setSafeBrowsingEnabled(true) + onSafeBrowsingHit）。
     * 实现层默认行为：拦截并提示（这是浏览器本身的安全能力，与 agent 权限无关）。
     */
    fun onSafeBrowsingHit(url: String, threatType: Int) {}

    /**
     * 本标签的**渲染进程**被系统回收了（第一百七十二批 S2）。
     *
     * 触发点：[android.webkit.WebViewClient.onRenderProcessGone] —— 那一条必须 `return true`，
     * 否则框架默认处理是**杀掉应用进程**（L1/L2 承诺下最坏的结果是"重载一次"，绝不能是崩）。
     * 引擎只负责上报，善后（复位该标签、画「点一下重新加载」）归
     * [com.example.zhengdao.ui.browser.BrowserTabManager] —— 它才知道标签表与活跃 id。
     *
     * 默认空实现：不在标签表里的引擎（单测桩 / 将来的离屏引擎）接到也不做事。
     */
    fun onRenderProcessGone() {}
}
