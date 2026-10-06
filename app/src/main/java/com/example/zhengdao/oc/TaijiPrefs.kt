// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import android.content.Context
import android.content.SharedPreferences

/**
 * 太极 Tab 走哪套 UI 的开关。
 *
 * ## 为什么需要一个开关，而不是直接替换
 *
 * Compose 原生 UI 直连 `opencode serve` 的**鉴权尚未在真机验证**（阶段 0-1：
 * OkHttp + Basic auth 打 `GET /global/health` 是否 200）。直接把 Tab 0 换掉，
 * 等于把当前唯一可用的入口（WebView + LocalProxy 版）一次性赌掉。
 *
 * 故：默认走新 UI，但保留一键回退入口。出问题时用户自己能退回可用状态，
 * 不必等发版——这也是项目「失败必须可见且可恢复」原则的体现。
 *
 * 与 [com.example.zhengdao.terminal.TerminalPrefs] 共用同一个 prefs 文件
 * `zhengdao-ui`，不新建文件（减少需要清理的残留）。
 */
object TaijiPrefs {

    private const val PREFS = "zhengdao-ui"
    private const val KEY_NATIVE_UI = "taiji_native_ui"

    /** 默认走 Compose 原生 UI。 */
    const val DEFAULT_NATIVE_UI = true

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun useNativeUi(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NATIVE_UI, DEFAULT_NATIVE_UI)

    fun setNativeUi(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_NATIVE_UI, enabled).apply()
    }

    /** 供 Compose 侧注册变更监听用（设置页改完即时生效，不必重启 App）。 */
    fun key(): String = KEY_NATIVE_UI
}
