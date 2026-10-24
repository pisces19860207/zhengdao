// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.browser.LocalPreviewPolicy
import com.example.zhengdao.browser.PreviewMode

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
 *
 * 第五十九批 §5 在此加**本地预览页签**那一支（[previewPath] 起）：判据全在
 * [LocalPreviewPolicy]（零 Android），这里只持每标签一份的状态。为什么状态放这儿而不是
 * 新开一个 PreviewTab 类：页签条、提示条、回收出口、优先级切换认的都是 `Tab.state`，
 * 另起一套就等于「同一个标签有两种真相」——正是 §4 打回第二条的形状。
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
     * 渲染进程被系统回收过、页面此刻是死的（第一百七十二批 S2）。
     *
     * 语义只有一条：**UI 该画「点一下重新加载」，而不是画一个白页**。
     * 谁置真：[com.example.zhengdao.ui.browser.BrowserTabManager.markRendererGone]
     * （来自 [android.webkit.WebViewClient.onRenderProcessGone]）。
     * 谁置假：① 用户点了重新加载；② 本标签自己又开始加载（[onPageStarted]）——
     * 后者是兜底，免得"别处触发了加载、卡片还挂着"。
     *
     * 刻意**不在置真的那一刻自动 reload**：那是偷偷联网（用户 2026-10-24 口径：
     * 「不做一打开 APP 就悄悄联网跑玄览」「恢复快照，不假装活着」）。
     */
    var recycled by mutableStateOf(false)

    /** 渲染进程被回收：停转圈 + 亮"已回收"卡片。不导航、不重载。 */
    fun onRendererGone() {
        loading = false
        recycled = true
    }

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
        // 兜底（第一百七十二批 S2）：只要有加载真的开始了，"已回收"卡片就该消失，
        // 免得用户点了别处的"重试"、页面已经活了、卡片还挂在那儿。
        recycled = false
        rememberPage(displayableUrl(url), true)
        blockedMsg = null
    }

    /** 页面加载完成。 */
    fun onPageFinished(url: String) {
        loading = false
        rememberPage(displayableUrl(url), true)
    }

    /**
     * 引擎报上来的地址 → 这个页签**该显示**的地址（第五十九批 §5.4）。
     *
     * 预览页签收到的是注入通道那个地址（`loadDataWithBaseURL` 的 base 是 `about:blank`），
     * 不是文件地址；直接记它会把 [openPreview] 写进去的假地址（`local-preview://…`，
     * 只给人看、不可导航）擦成 `about:blank` —— 用户就认不出这是哪个文件了。
     * 所以预览页签保留已有地址，上网页签原样记录。
     *
     * 这条判据放在**状态这一侧**而不是委托里，是为了让委托继续逐字写
     * `tab.state.onPageStarted(url)`（`BrowserTabStateTest` 的源码门禁钉的就是那一行）：
     * "页面事件写进本标签 state" 与 "预览页签显示什么地址" 是两件事，各归各处。
     */
    private fun displayableUrl(reported: String): String =
        if (isPreview()) url else reported

    /** 页面标题到达（空标题忽略——不让空串把已有标题擦了）。 */
    fun onReceivedTitle(title: String) {
        if (title.isNotEmpty()) this.title = title
    }

    /** 加载被拦/出错：停转圈 + 落一条可关闭的提示。 */
    fun onBlocked(message: String) {
        loading = false
        blockedMsg = message
    }

    // ── 本地预览页签（第五十九批 §5） ──────────────────────────────────────
    //
    // 判别只有一条：[previewPath] 非 null ⇒ 这个页签是「只显示工作区文件」的预览页签，
    // 不是上网页签。上网页签这些字段恒为初始值（null / SOURCE / false），
    // 由 `LocalPreviewTabTest` 判据 P0 钉住「预览状态不污染普通页签」。

    /** 预览的文件（相对工作区根，`/` 分隔）；null = 普通上网页签。 */
    var previewPath by mutableStateOf<String?>(null)

    /** 上一次注入的内容（整段文本；模式切换/开关沙箱时按它重算文档，不重新读盘）。 */
    var previewText by mutableStateOf<String?>(null)

    /** 显示模式（源码／渲染）。 */
    var previewMode by mutableStateOf(PreviewMode.SOURCE)

    /** §5.3 的可选「脚本沙箱」：默认 false；开了也只在本页签跑，**仍断网**。 */
    var previewJsSandbox by mutableStateOf(false)

    /** 是否本地预览页签（[previewPath] 非空的语义化写法）。 */
    fun isPreview(): Boolean = previewPath != null

    /** 预览文件的显示名（路径最后一段；标签条与预览栏都用它）。 */
    fun previewFileName(): String = previewPath?.substringAfterLast('/').orEmpty()

    /**
     * 打开/更新一个本地预览页签的内容。
     *
     * 三条语义都是刻意的：
     * - [url] 写的是**假地址**（`local-preview://…`，[LocalPreviewPolicy.previewUrl]）：
     *   WebView 报上来的只有 `about:blank`（注入通道），显示地址必须由我们自己写；
     * - [everLoaded] 直接置 true：预览天生有内容，不该先闪一下起始页
     *   （[loading] 也不碰——`loadDataWithBaseURL` 的 onPageStarted/Finished 自己会走一遍）；
     * - 模式按扩展名给默认（HTML 先排版、其余先源码，[LocalPreviewPolicy.defaultMode]），
     *   但**同一文件再次预览不重置模式**（用户可能正看着源码，抱朴改完内容不该把他拨回渲染）。
     */
    fun openPreview(relPath: String, text: String) {
        val first = previewPath != relPath
        previewPath = relPath
        previewText = text
        url = LocalPreviewPolicy.previewUrl(relPath)
        title = previewFileName()
        everLoaded = true
        blockedMsg = null
        if (first) previewMode = LocalPreviewPolicy.defaultMode(relPath)
    }

    /** 交给 WebView 注入的文档（判据在 [LocalPreviewPolicy.document]：渲染档逐字、源码档转义）。 */
    fun previewDocument(): String =
        LocalPreviewPolicy.document(previewFileName(), previewText.orEmpty(), previewMode)
}
