// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十四批 · 自检的**生产闸门**（派工单 §0 第 3 条）。
//
// 硬要求：自检（v1 的 eval 跑批 / v2 的正文判据）**默认不许跑**。
// 三条理由：
//   1. v1 要调模型、烧额度（一次约 ¥0.09）、还要 key——普通用户没这个意愿；
//   2. 自检失败会**回滚用户刚写的东西**——没有明确授权就动用户文件，越权；
//   3. 它跑在采纳路径上（v1 在 IO 上不阻塞，但 CPU/磁盘/网络占用是真的）。
//
// 打开只有两条路，都是**显式**的：
// - 调试构建（`BuildConfig.DEBUG`）——开发/CI 开箱即用，Release 永远默认关；
// - 设置里由用户拨动的开关（SharedPreferences）。
//
// 失败不许静默：跑不了要说话（v1 的 `NotRun` + RunLog），回滚失败要给路径
// （v1 的 `RollbackFailed`）——本文件只回答「该不该跑」。
package com.example.zhengdao.baopu.eval

import android.content.Context
import android.content.SharedPreferences

/**
 * 自检开关（**纯函数**，Context 只用来取 SharedPreferences）。
 *
 * 判定刻意做成 `devMode || prefOn` 的并联：调试构建开箱即跑（测试/CI 显式开的场景），
 * Release 里只有用户自己拨过开关才跑。没有"环境变量悄悄打开"这条路——
 * 那会让门禁在生产机上被人无意触发，回滚别人刚写的技能。
 */
internal object SelfCheckGate {

    /** 用户拨动的那个开关（设置页「自检」）。 */
    const val PREF_KEY = "eval_selfcheck_on"

    /** 开关默认值。**false**——派工单 §0.3：默认关。 */
    const val PREF_DEFAULT = false

    /** 纯判定（测试直插，不建 Context）。 */
    fun enabled(devMode: Boolean, prefOn: Boolean): Boolean = devMode || prefOn

    /** 现读用户开关；读不到（没有 prefs／磁盘坏了）按**关**处理。 */
    fun prefOn(ctx: Context): Boolean = runCatching {
        prefs(ctx).getBoolean(PREF_KEY, PREF_DEFAULT)
    }.getOrDefault(PREF_DEFAULT)

    /** 当前该不该跑（调试构建或用户开过开关）。 */
    fun enabledNow(devMode: Boolean, ctx: Context): Boolean =
        enabled(devMode, prefOn(ctx))

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("zhengdao_eval", Context.MODE_PRIVATE)
}
