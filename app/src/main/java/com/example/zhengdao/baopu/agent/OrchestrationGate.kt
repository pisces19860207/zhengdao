// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十四批 · 183-a（2026-10-25）：编排引擎的**只读闸门**。
//
// 与 [com.example.zhengdao.baopu.eval.ProdEvalGate] 同一房型（只读闸 ＋ 独立写口），
// 理由也一样：模型一轮提交多条 tool_calls 会改变**整条回灌路径**，这种开关只能由
// 用户显式拨动——没有 devMode / adb / 环境变量旁路（关了就是今天的行为）。
//
// 默认关是这一批的硬判据（派工单 §1.7）：`OrchestrationBatchTest` 钉着
// `PREF_DEFAULT == false` 与「关掉时引擎构造闭包默认 `{ false }`」。
package com.example.zhengdao.baopu.agent

import android.content.Context

/**
 * 编排开关的闸门（**纯判定**；Context 只用来取 SharedPreferences）。
 *
 * 写口在 [OrchestrationSettings]，本对象只读——两件事分开，源码门禁才门得住
 * （`OrchestrationBatchTest` 的 forbidden 名单：本文件里出现写口即红）。
 */
internal object OrchestrationGate {

    /** 用户拨动的开关（设置页「批量调用编排」；写口在 [OrchestrationSettings]）。 */
    const val PREF_KEY = "orchestrate_engine_on"

    /** 独立 prefs 文件（不和三处已有的开关抢键）。 */
    const val PREFS_NAME = "zhengdao_orchestrate"

    /** 默认值。**false**——派工单 §1.7：默认关，关掉＝今天行为一行不改。 */
    const val PREF_DEFAULT = false

    /** 纯判定：只有用户拨过才开（**没有 devMode 并联**，理由见文件头）。 */
    fun enabled(prefOn: Boolean): Boolean = prefOn

    /** 现读用户开关；读不到按**关**处理（磁盘坏了一道开关不该变成默认开）。 */
    fun prefOn(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_KEY, PREF_DEFAULT)
    }.getOrDefault(PREF_DEFAULT)

    /** 当前该不该走编排。 */
    fun enabledNow(ctx: Context): Boolean = enabled(prefOn(ctx))
}
