// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · Mermaid 离屏页的**协议层**（纯函数、零 Android，JVM 单测直接跑）。
//
// 与 `assets/mermaid/mermaid.html` 的约定只有三条，改一边必须同时改另一边：
//   ① 页面地址：`file:///android_asset/mermaid/mermaid.html?maxh=<像素上限>`
//   ② 轮询脚本返回普通字符串：`wait` / `ok:<设备像素高>` / `err:<首行错误>`
//   ③ 图源码**只当 JS 字符串字面量**递进去（`JSONObject.quote`），绝不拼进 HTML
//
// ③ 是这道闸的全部意义：注入 `</script><script>…` 这类东西，进到页面里也只是一个
// 字符串里的字符——它既不在 HTML 里，也不在 innerHTML 里。安全性由（"不拼 HTML"＋
// 页面侧 strict/htmlLabels:false）两层共同保证，任何一层单独都不够。
package com.example.zhengdao.mermaid

import org.json.JSONObject

object MermaidPage {

    /** 随包页面（本地 asset，**不联网**）。 */
    const val ASSET_URL = "file:///android_asset/mermaid/mermaid.html"

    /** 单张位图的高度上限（像素）——与 [com.example.zhengdao.browser.offscreen.OffscreenShotter] 同一口径。 */
    const val MAX_HEIGHT_PX = 6000

    /** 页面地址：把高度上限递进去（页面自己决定要不要等比缩小）。 */
    fun pageUrl(maxHeightPx: Int = MAX_HEIGHT_PX): String =
        "$ASSET_URL?maxh=${maxHeightPx.coerceIn(320, 20_000)}"

    /**
     * 轮询脚本：**第一次**执行时把源码交给页面开渲，之后每次只回报状态。
     *
     * 为什么不分开「先 evaluate 一次 render，再轮询」：多一次 eval 就多一个时序假设
     * （页面脚本到底加载完没有）。让第一次轮询自己开渲，只在 `window.ZD` 存在时才置
     * `__zd_started`，页面没加载完就自然重试——时序问题在协议里被消掉了。
     */
    fun probeScript(code: String): String {
        val literal = JSONObject.quote(code)
        return "(function(){var z=window.__zd||{s:'wait'};" +
            "if(window.__zd_started!==true){" +
            "if(!window.ZD){return 'wait';}" +
            "window.__zd_started=true;" +
            "window.ZD.render($literal);" +
            "return 'wait';}" +
            "if(z.s==='ok'){return 'ok:'+z.h;}" +
            "if(z.s==='error'){return 'err:'+(z.msg||'渲染失败');}" +
            "return 'wait';})()"
    }
}
