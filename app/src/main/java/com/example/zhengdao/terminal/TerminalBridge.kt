// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 内 xterm.js 的桥：JS 侧统一叫 window.AndroidBridge。
 * 只接收三类事件：终端就绪（携带初始行列）、键盘输入（base64 编码的 UTF-8）、尺寸变化。
 * 注意：这些方法在 WebView 的 JS 桥线程上回调，调用方负责切换到主线程操作 UI。
 */
class TerminalBridge(
    private val onReady: (cols: Int, rows: Int) -> Unit,
    private val onInput: (b64: String) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
) {
    @JavascriptInterface
    fun ready(cols: Int, rows: Int) = onReady(cols, rows)

    @JavascriptInterface
    fun input(b64: String) = onInput(b64)

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) = onResize(cols, rows)
}
