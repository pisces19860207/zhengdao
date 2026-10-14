package com.example.zhengdao.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 单标签界面状态（第⑨刀 B）——「切 Tab 不丢页面」的状态半（§B 顺序 32）。
 *
 * 从两处合并而来：原 [BrowserHolder] 的「跨 Tab 保留状态」（rememberPage/lastUrl/everLoaded）
 * 与原 BrowserScreen 里的 `remember { mutableStateOf }`（loading/blockedMsg）。多标签下这些
 * 状态必须**每标签一份**（切走再切回，各自的地址栏/提示条各归各）。
 *
 * 可观测性：字段用 Compose snapshot state 承载（标签条/地址栏/提示条直接读，变化驱动重组）；
 * 写方只有 UI 线程（WebView 回调与 Compose 事件都在 UI 线程）。
 *
 * 纯逻辑部分（rememberPage 的「空 URL 不覆盖 / everLoaded 只涨不落」等语义）不碰 Android，
 * JVM 单测直接覆盖（`BrowserTabStateTest`，由原 `BrowserHolderStateTest` 平迁）。
 */
class BrowserTabState {

    /** 当前/最近一次开始的 URL（地址栏回显）。 */
    var url by mutableStateOf("")

    /** 页面标题（onReceivedTitle；空 = 页面没给，UI 回退显示 URL）。 */
    var title by mutableStateOf("")

    /** 是否正在加载（刷新键变身停止键的判据）。 */
    var loading by mutableStateOf(false)

    /** 是否成功加载过页面（空态引导判据；只涨不落）。 */
    var everLoaded by mutableStateOf(false)

    /** 本标签的安提示条（scheme 被拦 / SSL / Safe Browsing / 页面错误；可关闭）。 */
    var blockedMsg by mutableStateOf<String?>(null)

    /**
     * 记下当前页面（onPageStarted / onPageFinished 调）。
     * 语义与旧 [BrowserHolder.rememberPage] 逐字一致（§B 顺序 32 验收钉的）：
     * - 空 URL 不覆盖上次记录（别把地址栏擦掉）；
     * - loaded 只把 [everLoaded] 往 true 推（只涨不落）。
     */
    fun rememberPage(url: String, loaded: Boolean) {
        if (url.isNotEmpty()) this.url = url
        if (loaded) everLoaded = true
    }

    /** 页面开始加载。 */
    fun onPageStarted(url: String) {
        loading = true
        rememberPage(url, true)
        blockedMsg = null
    }

    /** 页面加载完成。 */
    fun onPageFinished(url: String) {
        loading = false
        rememberPage(url, true)
    }

    /** 页面标题到达（空标题忽略——不让空串把已有标题擦了）。 */
    fun onReceivedTitle(title: String) {
        if (title.isNotEmpty()) this.title = title
    }

    /** 加载被拦/出错：停转圈 + 落一条可关闭的提示。 */
    fun onBlocked(message: String) {
        loading = false
        blockedMsg = message
    }
}
