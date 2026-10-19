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
import com.example.zhengdao.baopu.agent.PreviewSink
import com.example.zhengdao.baopu.agent.PreviewTool
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.ui.browser.BrowserHolder
import com.example.zhengdao.ui.browser.LocalPreviewHolder

/**
 * 把 [BrowserGateway] 接到玄览（进程内直调，不经 HTTP 桥、不要 token）。
 *
 * 幂等：重复调用只替换同一份网关。
 *
 * 第五十九批 §5.1 起这里还接第二个落点：`preview_local_file` 的 [PreviewSink]
 * （把已经读出来、守过的文本登记成一次「本地预览」请求）。
 * 两件事同住一个文件的原因见 install() 里那段注释——"玄览的接线"只许有一个地方。
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

        // ── 本地预览（第五十九批 §5.1：`preview_local_file` 的显示落点） ──────────────
        //
        // 为什么也挂在这里：本文件就是「把工具接到玄览」那一层（文件头写明），
        // 预览的显示落点与浏览器六件是同一件事的两个入口，另开一个 Wiring 文件等于
        // 让"玄览的接线"散在两处——下次给玄览加运行时开关就得改两个地方。
        //
        // 为什么**投主线程**：BrowserTabManager 全类限 UI 线程（Compose snapshot 状态 +
        // WebView 构造要主 Looper；直调的那条崩溃原文写在 BrowserCommands 类 KDoc，第㉓刀）。
        // 工具执行在引擎的工作线程上，所以这里必须投。
        //
        // 为什么**投完就回、不等显示**（与上面 BrowserCommands.onMain 的区别）：
        // 这里要落的是"请求已登记"，不是"页面已画出来"。等待会把工具轮次押在主线程上，
        // 而主线程正可能卡在 WebView 构造里（§B 61 实测过 ≥100s）。登记与消费分开，
        // 界面没组合（用户还不在玄览）时请求就地等着，切过去那一帧被消费——
        // 这也是 [com.example.zhengdao.ui.browser.LocalPreviewHolder] 用可观察状态的原因。
        //
        // 越界与读文件都不在这一层：工具侧已用现成的 BaopuGuardCore 判过、读的是工作区内
        // 的文件（§5.2「只加载工作区内的文件，走既有守卫」），这里只负责"送进页签"。
        PreviewTool.sink = object : PreviewSink {
            override fun show(relPath: String, text: String): String? {
                main.post {
                    LocalPreviewHolder.request(relPath, text)
                    // 与抱朴预览卡「在玄览打开」同一条 B 路径：请求由 MainActivity 的
                    // ON_RESUME 消费。用户此刻就在玄览 ⇒ 这一句是 no-op；在别的 Tab ⇒
                    // 回前台时切过去。工具的回话里也照 BaopuPreview 那条纪律写明"切到玄览"，
                    // 不让用户把"没立刻出现"当成"点了没反应"（2026-10-18 那条教训）。
                    BrowserHolder.requestBrowserTab()
                }
                return null
            }
        }
    }

    /** 进程级主线程 Handler（与 WebViewEngine 同规：不用 View 自身的 post，未 attach 时会压队列）。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
}
