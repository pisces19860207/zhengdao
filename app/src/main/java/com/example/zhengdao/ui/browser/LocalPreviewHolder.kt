// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §5（玄览本地预览页签的**入口**）。
// 用户口径：入口「工具或 UI 二选一」——本批选**工具**（`preview_local_file`，§5.1），
// UI 侧只负责把送来的内容显示出来（[BrowserPreviewBar]），不另开一条"用户自己填路径"的通道。
package com.example.zhengdao.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「本地预览」请求的跨界面入口 —— 形状照 [BrowserHolder.pendingUrl]（§4.6 那条 B 路径），
 * 但**另开一个文件**而不是往 [BrowserHolder] 里加槽位，原因有两条，都不是审美：
 *
 * 1. `BrowserPendingUrlObservableGateTest` 判据 S8 明确禁止 BrowserHolder 里出现第二个
 *    以 Url／Uri 命名的槽位——那条门禁治的是"另起第二条待打开 URL 通道"。预览槽装的是
 *    **文件内容**（已经在工具侧读好、守过），不是 URL，本就不该同槽；
 * 2. 语义不同：pendingUrl 是「去把这个网址打开」，这里是「把这份已经读出来的文本挂到某个
 *    页签上只显示」。混在一起，`openExternal` 那条链就要开始分叉判类型了。
 *
 * **必须是 Compose 可观察状态**（[mutableStateOf]，不是 `@Volatile var`）：
 * 用户此刻就停在玄览 Tab 时，抱朴调 `preview_local_file` —— 组合不重跑就没人消费这次请求
 * （P1-8 那条病在 2026-10-19 已经被 §B 77 审计钉过一次，这里不重犯）。
 * 消费侧因此以「值」为 key 起 `LaunchedEffect`，判据见 `LocalPreviewTabTest` 判据 P5。
 *
 * 线程：[request] 只许在 **UI 线程**调（与 pendingUrl 同规）。工具层在引擎线程，
 * 所以由 [com.example.zhengdao.baopu.BaopuBrowserWiring] 投一次主线程再进来——
 * 不许在这里 post：Holder 是状态容器，不知道也不该知道线程的事（否则又长出一条第二通道）。
 */
object LocalPreviewHolder {

    /**
     * 一次预览请求。
     *
     * [seq] 不是装饰：「同一个文件、内容一字没改」再预览一次时，若只有 relPath+text
     * 两个字段，data class 的相等性会让 `LaunchedEffect(pending)` 的 key **不变** ⇒
     * 不再跑一次 ⇒ 页签已被用户关掉的话就再也开不回来（表现为"第二次调用没反应"）。
     * 一个单调序号把「每次请求都是新事件」这件事写进 key 里，语义与"事件"而不是"值"对齐。
     */
    data class Request(val relPath: String, val text: String, val seq: Long)

    /** 待消费的预览请求（消费侧置 null，与 [BrowserHolder.pendingUrl] 同规）。 */
    var pending: Request? by mutableStateOf<Request?>(null)

    private var seq = 0L

    /**
     * 放一次预览请求（UI 线程）。
     *
     * 与 [BrowserHolder.pendingUrl] 一样：**只写、不等**。写入方（工具经装配层投主线程）
     * 不阻塞等界面真的挂上去——主线程此刻可能正忙（WebView 构造已知会间歇卡死，§B 61），
     * 阻塞等一次显示等于把工具轮次押在一个不可控的时长上。
     * 没人在听（玄览没组合）时请求就地等着，用户切到玄览那一帧被消费。
     */
    fun request(relPath: String, text: String) {
        pending = Request(relPath, text, ++seq)
    }
}
