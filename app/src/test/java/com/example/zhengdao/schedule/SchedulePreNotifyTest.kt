// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十九批 §2：开始前提醒的判据与「不静默」的钉子。
//
// JVM 里能真跑的只有纯函数那一半（开关现读、通知落点都要 Context，android.jar 是桩）；
// 装配与不静默的另一半钉在 `ScheduleRedLineGateTest` 的源码门禁里（两半合起来才是全链）。
package com.example.zhengdao.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.notify.AppNotifications

class SchedulePreNotifyTest {

    @Test
    fun `默认开_关掉是显式选择`() {
        // 判据本体：true ⇒ 响，false ⇒ 不响（只发进会话）。「默认开」落在 prefs 缺省值
        // （SchedulePreNotify.enabled 的 getBoolean(KEY, true)），由源码门禁再钉一层。
        assertTrue("开关开 ⇒ 到点先响", SchedulePreNotifyRules.shouldPreNotify(true))
        assertFalse("开关关 ⇒ 不响，只发进会话（负例：关掉开关仍发通知）", SchedulePreNotifyRules.shouldPreNotify(false))
    }

    @Test
    fun `负例_无权限不许静默_通知落点必须留账`() {
        // 派工单 §2：「无通知权限不许静默（RunLog＋详情页提示）」。
        // 通知落点（ScheduleNotifier.postStarting）里必须有：委托既有单一来源的权限判定 ＋
        // 权限缺失时的 RunLog 那一行。这里是源码门禁（判据本体在 JVM 拿不到 Context）。
        val notifier = source("ScheduleNotifier.kt")
        assertTrue(
            "postStarting 的权限判定必须委托 SchedulePermissions（单一来源，不抄第二份）",
            notifier.contains("fun postStarting(context: Context, task: ScheduleTask)") &&
                notifier.contains("SchedulePermissions.notificationsGranted(context)"),
        )
        assertTrue(
            "权限缺失分支的第一句必须就是 RunLog.log（不许静默，也不许把日志藏进 if(false) 死分支）",
            Regex("notificationsGranted\\(context\\)\\)\\s*\\{\\s*RunLog\\.log\\(").containsMatchIn(notifier),
        )
        // 详情页那一半：任务页必须读同一个判定并显示提示（同一结论，不许两处各查各的）。
        val page = source("ScheduleTasksActivity.kt")
        assertTrue("任务页要现读通知权限（横幅）", page.contains("SchedulePermissions.notificationsGranted(ctx)"))
        assertTrue("任务页要有「通知权限未开」的提示行", page.contains("R.string.sched_perm_missing_note"))
    }

    @Test
    fun `负例_不新建通知渠道_复用169那套`() {
        val notifier = source("ScheduleNotifier.kt")
        assertTrue("仍走 AGENT 渠道", notifier.contains("AppNotifications.AGENT"))
        assertTrue("仍与 169 同一个前台抑制口径", notifier.contains("AppForeground.isForeground()"))
        // 开始前那条与「已发出」那条身份不同：先响的不许顶掉后发的账
        assertTrue("开始前通知要有自己的 NOTIF_ID_PRE", notifier.contains("NOTIF_ID_PRE = 20261103"))
        assertTrue("既有那条 20261102 原样保留", notifier.contains("NOTIF_ID = 20261102"))
    }

    @Test
    fun `装配层_开关关不响_开则走postStarting`() {
        // ScheduleRunner 里 starting 的接法：先判开关（纯函数），关 ⇒ 只落日志不响；开 ⇒ postStarting。
        val runner = source("ScheduleRunner.kt")
        assertTrue("开关现读必须经 SchedulePreNotify.enabled", runner.contains("SchedulePreNotify.enabled("))
        assertTrue("判据必须走纯函数 shouldPreNotify", runner.contains("SchedulePreNotifyRules.shouldPreNotify("))
        assertTrue("关掉时要落一行日志（不响也要可查）", runner.contains("开始前提醒已关"))
        assertTrue("开着要走 postStarting", runner.contains("ScheduleNotifier.postStarting(app, task)"))
    }

    @Test
    fun `开关默认开_缺省值是true`() {
        // 派工单 §2「默认开可关」：prefs 缺省必须 true。
        val pre = source("SchedulePreNotify.kt")
        assertTrue("缺省值必须 true（默认开）", pre.contains("getBoolean(KEY_PRE_NOTIFY_ON, true)"))
    }

    private fun source(name: String): String {
        val rels = listOf(
            "src/main/java/com/example/zhengdao/schedule/$name",
            "../app/src/main/java/com/example/zhengdao/schedule/$name",
            "app/src/main/java/com/example/zhengdao/schedule/$name",
        )
        val f = rels.map(::File).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $name（工作目录 ${File(".").absolutePath}）")
        return f.readText()
    }
}
