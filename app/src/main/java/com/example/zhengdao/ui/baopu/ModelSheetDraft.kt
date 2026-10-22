// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》11:51 补充节 §三末句——
// 「跳玄览返回后，抽屉保持跳转前的公司名与已填内容，不许重置回默认通道」。
//
// 为什么必须是**进程级**对象（而不是 `remember {…}`）：② 代码事实——MainActivity 的
// 页面分支是 `when (tab) { TabIndex.BAOPU -> BaopuScreen(…) }`（MainActivity.kt:1007），
// 而 `openInXuanlan()` 走的是 `BrowserHolder.requestBrowserTab()` ⇒ `tab` 变成 BROWSER ⇒
// **抱朴页整个离开组合**，它 `remember` 住的一切（含弹层的选中态、正在打的字）当场销毁。
// 所以"返回后还在"这句话只能由一个活过组合周期的对象兑现——房型与 BrowserHolder 同一族
// （进程级、一次性、不落盘）。
//
// 保密纪律：这里只允许出现**用户正在输入框里打字的那把新 Key**（他刚敲的，本就在内存里），
// 已存池子的 Key 一律以 [com.example.zhengdao.baopu.KeyPool.mask] 的形态显示。
// 本对象永不写日志、永不落盘；`clear()` 会把那串原文抹掉（关抽屉即清，不留过夜）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ModelPickerRules

/**
 * 选模型抽屉的**草稿**（跳玄览前后都还在；离开抱朴页不销毁）。
 *
 * 三条语义，逐条对上补充节：
 * - [showing]：抽屉是不是"用户还没关掉"的状态 ⇒ 抱朴页重新组合时据此**把抽屉重新弹回来**；
 * - [channelId]：跳转前选中的那家（公司名）⇒ 不许重置回默认通道；
 * - 其余输入位（[addKey] / [baseUrl] / [protocol] / [model] / [query]）＝已填内容原样保留。
 *
 * **这里故意没有"起稿"函数**：第一次打开（`showing` 为假）时该显示什么是抽屉自己算的——
 * `BaopuChannelSheet` 那七行 `if (restored) 草稿 else 现值`（预设取 `Providers` 那条、自定义取 SP）。
 * 本对象只存"用户敲进去的东西"，不替界面算现值，否则同一个"当前该显示哪条模型"会有两处真相。
 */
internal object ModelSheetDraft {

    /** 抽屉开着（用户没点取消/启用）——回抱朴页时据此重弹。 */
    var showing: Boolean = false

    /** 当前选中的提供商 id（预设 id 或 [com.example.zhengdao.baopu.ProviderPrefs.CHANNEL_CUSTOM]）。 */
    var channelId: String = ""

    /** 输入框里正在打的**新** Key（待添加；不是池子里已存的那把）。 */
    var addKey: String = ""

    /** 自定义通道的接口地址。 */
    var baseUrl: String = ""

    /**
     * 自定义通道的协议线值（第一百三十六批；[com.example.zhengdao.baopu.ProviderPrefs]
     * 的两个 PROTOCOL_* 之一）。预设上没有协议可选 ⇒ 空串。
     */
    var protocol: String = ""

    /** 手填的模型名（自定义必填；预设上留空＝用自带的那条）。 */
    var model: String = ""

    /** 搜索框（输入即过滤那条规则的载体）。 */
    var query: String = ""

    /**
     * 这次会话里在清单上点定的那条模型（连同那一刻的元数据）。
     *
     * null = 用户没点（**不等于**"要删掉 SP 里那条覆盖"）；所以提交时还要看 [pickedFor]
     * 是不是当前这家——不是 ⇒ 这条草稿不属于本次提交，别把上一家的选择串到这一家。
     */
    var picked: ModelPickerRules.ModelOverride? = null

    /** [picked] 属于哪一家（防跨家串味；与 [channelId] 不同 ⇒ 视作用户没点过）。 */
    var pickedFor: String = ""

    /** 切到另一家：输入位跟着这家的真实状态走（其余草稿位作废，特别是 [picked]）。 */
    fun moveTo(id: String, storedBaseUrl: String, storedModel: String, storedProtocol: String = "") {
        channelId = id
        addKey = ""
        baseUrl = storedBaseUrl
        model = storedModel
        protocol = storedProtocol
        picked = null
        pickedFor = ""
    }

    /** 用户关掉抽屉（取消/启用/返回键）⇒ 草稿作废，输入过的那串 Key 原文不留过夜。 */
    fun clear() {
        showing = false
        channelId = ""
        addKey = ""
        baseUrl = ""
        model = ""
        protocol = ""
        query = ""
        picked = null
        pickedFor = ""
    }
}
