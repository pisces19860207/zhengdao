// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十四批 · 183-a（2026-10-25）：`orchestrate_engine_on` 的**写入口**。
//
// 与 [OrchestrationGate] 分开（房型照 `ProdEvalSettings`）：闸门只读、写口唯一，
// 而且只有「显式拨动」一条路——没有 devMode / adb / 环境变量旁路。
package com.example.zhengdao.baopu.agent

import android.content.Context
import androidx.core.content.edit

/** `orchestrate_engine_on` 的写口；读仍走 [OrchestrationGate.prefOn]，不养第二份读法。 */
internal object OrchestrationSettings {

    /** 拨动开关。写失败按没写处理——下次读到的还是旧值（默认关）。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(OrchestrationGate.PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(OrchestrationGate.PREF_KEY, on) }
        }
    }

    /** 现读（与闸门同一出口；UI 初值也走它，保证「屏上显示的」与「闸门看到的」一致）。 */
    fun get(ctx: Context): Boolean = OrchestrationGate.prefOn(ctx)
}
