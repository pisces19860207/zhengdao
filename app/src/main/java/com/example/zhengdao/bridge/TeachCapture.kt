// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百二十六批 G2 · 示教录制的「点击即回传」桥（222 验收：录制跨导航丢事件）。
//
// 与红线④的关系（先把话说清楚，免得后来人以为这里破了规矩）：
//   ④「addJavascriptInterface 重绑」禁的是 **agent 侧的浏览器桥** ——BridgePolicy 的 11 条命令
//   仍走 postWebMessage / WebMessagePort，本批一个字节没改；agent 也够不到下面这个对象
//   （它不在命令白名单里，参数面也没有任何入口）。
//   本对象是 **App 自己的录制探针**，三条约束钉住攻击面：
//     ① 只在录制期间挂（start → attach），停止立即 removeJavascriptInterface；
//     ② **只写**：页面能做的只有往录制缓冲塞一条 JSON，取不到任何东西（没有读方法）；
//     ③ 载荷限长：超长/空串直接丢，不让页面拿桥当内存放大器。
package com.example.zhengdao.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.example.zhengdao.ui.browser.BrowserTabManager

/**
 * 录制的即时回传通道：页面里 `window.__zdTeachBridge.emit(json)` → 原生回调。
 *
 * 线程契约：[attach] 必须在 UI 线程调（要解析标签、挂 WebView 接口）；回调 [onEvent]
 * 落在 WebView 的 JavaBridge 线程 —— 录制方自己负责线程安全。
 */
internal object TeachCaptureBridge {

    /** 页面侧看到的对象名（JS 里硬编码同一个）。 */
    const val NAME: String = "__zdTeachBridge"

    /** 单条载荷上限（字符）；正常事件 JSON 不到 2KB。 */
    const val MAX_PAYLOAD_CHARS: Int = 20_000

    /** 页面事件回调对象。**只有 emit 一个方法，且不返回任何值**。 */
    class Sink(private val onEvent: (String) -> Unit) {
        @JavascriptInterface
        fun emit(payload: String?) {
            if (payload.isNullOrBlank() || payload.length > MAX_PAYLOAD_CHARS) return
            onEvent(payload)
        }
    }

    /** 一次挂载的句柄（摘桥 / 停注入）。 */
    class Handle internal constructor(
        private val tab: BrowserTabManager.Tab,
        private val sink: Sink,
        private val injectJs: String,
    ) {
        private val main = Handler(Looper.getMainLooper())

        /** 文档开始加载后补一次：onPageStarted 时新文档未必已提交，350ms 后再落一次。 */
        private val retryInject = Runnable { inject() }

        private fun inject() {
            // evaluateJavascript 自己会投主线程（WebViewEngine 的投递契约），这里不阻塞。
            runCatching { tab.engine.evaluateJavascript(injectJs) {} }
        }

        fun onDocumentStarted() {
            inject()
            main.removeCallbacks(retryInject)
            main.postDelayed(retryInject, 350L)
        }

        /** 摘桥（幂等）：清探针、移除页面对象；正在排队的补注入一并取消。 */
        fun detach() {
            main.removeCallbacks(retryInject)
            val run = Runnable {
                tab.engine.onDocumentStarted = null
                tab.engine.onDocumentReady = null
                runCatching { tab.webView.removeJavascriptInterface(NAME) }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
        }
    }

    /**
     * 挂到 [tabId] 的标签上；标签不存在 ⇒ null（录制方据此退化为纯轮询）。
     *
     * [injectJs] 是**整段**注入脚本（调用方拼好 IIFE）：文档开始时由本对象负责重装，
     * 录制方只管把「同样的脚本」也放进轮询兜底里。
     */
    fun attach(
        tabId: Int?,
        injectJs: String,
        onEvent: (String) -> Unit,
        onNavigation: (String) -> Unit,
    ): Handle? {
        val tab = (if (tabId == null) BrowserTabManager.activeTab() else BrowserTabManager.tabById(tabId))
            ?: return null
        val sink = Sink(onEvent)
        val handle = Handle(tab, sink, injectJs)
        runOnMain {
            tab.webView.addJavascriptInterface(sink, NAME)
            tab.engine.onDocumentStarted = { url ->
                onNavigation(url)
                handle.onDocumentStarted()
            }
            tab.engine.onDocumentReady = { url -> onNavigation(url) }
            // 从当前页开始录制时没有 onPageStarted 可等 ⇒ 当场装一次（轮询兜底也照装，双保险）。
            handle.onDocumentStarted()
        }
        return handle
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }
}
