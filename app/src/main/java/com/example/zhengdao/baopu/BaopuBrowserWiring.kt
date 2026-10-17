// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：浏览器六工具的**真身**（进程内直调玄览）。工具层只认 `BrowserGateway` 接口，
// 本文件把接口接到 `BrowserCommands`（与 HTTP 桥同一套处理函数，差别只在**没过 token**：
// 抱朴与玄览同进程，不走桥）。
//
// 为什么每次现取活跃标签：`BrowserTabManager` 的 WebView 会随标签回收被销毁；工具层若
// 抓着旧实例不放，就会复现「Application attempted to call on a destroyed WebView」。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.agent.BaopuBrowser
import com.example.zhengdao.baopu.agent.BrowserGateway
import com.example.zhengdao.bridge.BrowserCommands

/**
 * 把 [BrowserGateway] 接到玄览（进程内直调，不经 HTTP 桥、不要 token）。
 *
 * 幂等：重复调用只替换同一份网关。
 */
internal object BaopuBrowserWiring {

    /** 滚动步长（一次 tool 调用的位移；滚太多会跳过内容）。 */
    private const val SCROLL_STEP = 600

    fun install(context: Context) {
        val appCtx = context.applicationContext
        BaopuBrowser.gateway = object : BrowserGateway {

            override fun open(url: String, newTab: Boolean): String =
                if (newTab) {
                    BrowserCommands.newTab(appCtx, url)
                } else {
                    BrowserCommands.navigate(url)
                }

            override fun snapshot(selector: String?): String =
                BrowserCommands.snapshot("text", selector)

            override fun click(target: String): String =
                BrowserCommands.click(target)

            override fun typeInto(target: String, text: String): String =
                BrowserCommands.type(target, text)

            override fun scroll(direction: String): String {
                // 用 scrollBy（相对位移）而不是 BrowserCommands.scroll 的 scrollTo（绝对坐标）：
                // 绝对坐标要知道当前滚到哪了，工具层并不知道；scrollBy 超出范围由页面自己 clamp。
                val (dx, dy) = when (direction) {
                    "up" -> 0 to -SCROLL_STEP
                    "down" -> 0 to SCROLL_STEP
                    "left" -> -SCROLL_STEP to 0
                    "right" -> SCROLL_STEP to 0
                    else -> 0 to 0
                }
                return BrowserCommands.execJs("(function(){window.scrollBy($dx,$dy);return 'OK'})()")
            }

            override fun screenshot(fullPage: Boolean): String =
                BrowserCommands.screenshot(appCtx, null, if (fullPage) "1" else "0")
        }
    }
}
