// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog

/**
 * 源级开关（第三百九十九批 §二①「每个触发源必须显式开启（默认全关），且可随时关」）。
 *
 * **默认全关是本批的第一条判据**：新装用户不拨开关，`TriggerPass` 连事件都不看，
 * 一个自动动作都不会有。写口只有 [set]（设置页两张开关调用它）——没有 devMode/adb/
 * 环境变量旁路，`ProdEvalSettings` 同款口径；判据侧只读 [schedOn]/[calendarOn]，
 * 不养第二份读法。
 */
object TriggerSwitch {

    private const val PREFS_NAME = "zhengdao-event-trigger"
    private const val KEY_SCHED = "sched_on"
    private const val KEY_CALENDAR = "calendar_on"

    fun schedOn(ctx: Context): Boolean = read(ctx, KEY_SCHED)

    fun calendarOn(ctx: Context): Boolean = read(ctx, KEY_CALENDAR)

    /** 设置页唯一写口。写失败按没写处理——下次读到的还是旧值，屏上状态回正。 */
    fun set(ctx: Context, source: TriggerSource, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(keyOf(source), on) }
        }
        RunLog.log("事件触发：源开关 ${source.wire} = $on（默认关，拨过才算数）")
    }

    /** 供判据侧一把读（`TriggerRunner` 每趟 pass 都要问两个源）。 */
    fun of(ctx: Context, source: TriggerSource): Boolean = read(ctx, keyOf(source))

    private fun keyOf(source: TriggerSource): String = when (source) {
        TriggerSource.SCHED -> KEY_SCHED
        TriggerSource.CALENDAR -> KEY_CALENDAR
    }

    private fun read(ctx: Context, key: String): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, false)
    }.getOrDefault(false)
}
