// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.zhengdao.core.RunLog

/**
 * 系统日历的探针（第三百九十九批源②「日程/提醒」）。
 *
 * 只做一件事：把时间窗内的日历事件读成 [CalEventRow] 原始行——
 * **内容不落盘、不进模型上下文**：事件标题只进会话标题与去重键，正文/备注/参会人一概不读
 * （派工单 §四「不读用户隐私内容」在日历源上的落实）。判不判、去不去重归 [TriggerRules]。
 *
 * 权限 fail-closed：`READ_CALENDAR` 没给 ⇒ 返回空表＋落一行日志——读不到宁可空转，
 * 不许装作「今天没有日程」。申请入口唯一化（`PermissionEntryGateTest` 钉的那张权限卡），
 * 本文件只判不申请。
 */
object TriggerCalendarProbe {

    /** 一次最多读多少条（一个意外巨大的日历不许把叫醒链拖死；due 判定用不了这么多）。 */
    private const val MAX_EVENTS = 200

    fun granted(ctx: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 读 `(fromMs, toMs]` 窗口内的事件（按开始时刻升序）。
     * 日历提供器读不到（无权限/无账户/提供器异常）一律空表＋日志，不抛。
     */
    fun query(ctx: Context, fromMs: Long, toMs: Long): List<CalEventRow> {
        if (!granted(ctx)) {
            RunLog.log("事件触发：日历权限没给，这一趟当没有日程（fail-closed）")
            return emptyList()
        }
        return runCatching { queryInner(ctx, fromMs, toMs) }
            .onFailure { RunLog.log("事件触发：日历读失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun queryInner(ctx: Context, fromMs: Long, toMs: Long): List<CalEventRow> {
        val cr = ctx.contentResolver
        // Instances.CONTENT_URI 本身已含 /when 段（content://com.android.calendar/instances/when），
        // 后面只接 from/to 两段数字——多 append 一段 "when" 会拼出 instances/when/when/…，
        // 提供器抛 Unknown URL（2026-11-05 模拟器实测抓到过，别改回去）。
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMs.toString())
            .appendPath(toMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )
        val out = ArrayList<CalEventRow>()
        cr.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val iTitle = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val iBegin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            while (c.moveToNext() && out.size < MAX_EVENTS) {
                out += CalEventRow(eventId = c.getLong(iId), title = c.getString(iTitle) ?: "", beginMs = c.getLong(iBegin))
            }
        } ?: RunLog.log("事件触发：日历提供器返回 null，这一趟当没有日程")
        return out
    }
}
