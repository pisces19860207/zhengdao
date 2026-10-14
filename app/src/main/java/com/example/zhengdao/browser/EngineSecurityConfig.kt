package com.example.zhengdao.browser

/**
 * 引擎安全配置 —— §4.4 安全加固清单的代码化。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.4。
 * 证据等级：② 各开关与 Android WebSettings API 一一对应；minSdk=36 全覆盖所需 API
 * （Safe Browsing API 26+、混合内容 API 21+、setDataDirectorySuffix API 28+）。
 *
 * 默认值 = 加固值（安全优先；关项必须显式传参）。
 * 本配置属"硬红线区"：agent 无权查看或修改（§4.3 二分区表第①项）。
 */
data class EngineSecurityConfig(
    /** Safe Browsing（API 26+，§4.4：setSafeBrowsingEnabled(true) + onSafeBrowsingHit）。 */
    val safeBrowsingEnabled: Boolean = true,
    /** 混合内容：默认 NEVER_ALLOW（§4.4）。 */
    val mixedContentMode: Int = MIXED_CONTENT_NEVER_ALLOW,
    /** 文件访问三连 + databaseEnabled，默认全关（§4.4「文件访问禁用」行）。 */
    val allowFileAccess: Boolean = false,
    val allowFileAccessFromFileUrls: Boolean = false,
    val allowUniversalAccessFromFileUrls: Boolean = false,
    val databaseEnabled: Boolean = false,
    /** JS 总开关：浏览器核心能力，开。 */
    val javaScriptEnabled: Boolean = true,
    /** 窗口管理由 App 多标签接管（§4.2：setSupportMultipleWindows(false)）。 */
    val javaScriptCanOpenWindowsAutomatically: Boolean = false,
    val domStorageEnabled: Boolean = true,
    /** 第三方 cookie 默认关（隐私取向）。 */
    val thirdPartyCookiesEnabled: Boolean = false,
    /**
     * JS 接口白名单。
     * 注意（§4.4「Bridge 通信」行）：浏览器桥**完全不用 addJavascriptInterface**，
     * 走 postWebMessage / WebMessagePort（结构化、可验源）。此字段仅为
     * 未来页面侧需求预留，默认空；非空时只暴露列名（红线④：agent 不得重绑，§4.3）。
     */
    val allowedJsInterfaces: Set<String> = emptySet(),
    /**
     * 多标签数据目录后缀（§4.2）。
     *
     * 第⑨刀 B 查证（② AOSP `WebView.java` 原文）：`setDataDirectorySuffix` 是**静态、
     * 每进程一次性**（须在首个 WebView 创建前调），设计意图是**多进程间**隔离——
     * "Each directory can be used by only one process… different processes in the same
     * application cannot directly share WebView-related data"。⇒ **单进程内的多个标签
     * 不可能各有数据目录**（cookie/localStorage/cache 在同进程所有标签间共享，与桌面
     * 浏览器普通标签一致）；进程级 suffix 在 `ZhengdaoApp.onCreate` 统一设。
     *
     * 本字段保留 null（引擎不再尝试 per-tab 后缀），仅作文档位。
     */
    val dataDirectorySuffix: String? = null,
) {
    companion object {
        /** 对应 android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW。 */
        const val MIXED_CONTENT_NEVER_ALLOW = 2
        /** 对应 android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE。 */
        const val MIXED_CONTENT_COMPATIBILITY = 1
        /** 对应 android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW。 */
        const val MIXED_CONTENT_ALWAYS_ALLOW = 0
    }
}
