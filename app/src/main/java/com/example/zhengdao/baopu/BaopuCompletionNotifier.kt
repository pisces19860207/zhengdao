// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android 官方文档（Notification.Builder / NotificationChannel / PendingIntent）。
package com.example.zhengdao.baopu

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.zhengdao.AppForeground
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.NotificationChannels
import com.example.zhengdao.ui.baopu.BaopuIntents

/**
 * 抱朴「长任务 / 子 Agent 跑完」的完成通知（第一百六十九批 §3）。
 *
 * 只做完成通知，不做常驻：
 * - App 在前台 ⇒ 不发（用户看得见会话）；
 * - 不在前台 ⇒ 发一条；点击带着会话 id 回抱朴，由 [BaopuIntents.requestSession] 挂单；
 * - 同一会话 5 秒内只响一次（子 Agent 完成 + 整轮结束不会连响）；
 * - 通知权限被拒 ⇒ 什么都不做（会话照常更新，权限页写明后果），绝不崩；
 * - 内容只有标题＋状态＋「点按回会话」，不含模型回复/工具输出/会话标题/Key。
 *
 * 渠道复用既有的 [NotificationChannels.AGENT]（2026-10-06 定稿的四个渠道，不新增第五个）。
 */
internal object BaopuCompletionNotifier {

    /** 与保活 42、安装 20261008、缓存 20261010 区分开。 */
    private const val NOTIF_ID = 20261101

    /**
     * 点击通知的 PendingIntent 请求码。
     *
     * 必须与 4101（缓存）/4102（安装）/4103（保活）/4104（停会话）/4105（切保活）都不同：
     * PendingIntent 的等价性是「请求码 + filterEquals」，extras 不参与比较。本通知只用一个
     * 固定的 NOTIF_ID，复用同一个 PendingIntent 是**有意**的——`FLAG_UPDATE_CURRENT`
     * 让最新的会话 id 生效。
     */
    internal const val REQ_SESSION = 4106

    /** 上一次真正发出去的时刻与会话（合并窗口；只在主线程读写）。 */
    private var lastPostedAtMs = 0L
    private var lastSessionId = -1L

    /** 整轮结束（[com.example.zhengdao.baopu.agent.EngineEvent.Settled]）。 */
    fun onRunSettled(ctx: Context, sessionId: Long, state: RunState, notice: String?) {
        val outcome = BaopuCompletionRules.outcomeOf(state, notice)
        post(ctx, sessionId, CompletionKind.RUN, outcome)
    }

    /** 一轮里的子 Agent 批结束（`run_subtasks` 工具返回）。 */
    fun onSubAgentsFinished(ctx: Context, sessionId: Long, ok: Boolean) {
        post(ctx, sessionId, CompletionKind.SUB_AGENTS, BaopuCompletionRules.subAgentOutcome(ok))
    }

    private fun post(ctx: Context, sessionId: Long, kind: CompletionKind, outcome: CompletionOutcome) {
        val now = SystemClock.elapsedRealtime()
        val permission = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!BaopuCompletionRules.shouldPost(
                foreground = AppForeground.isForeground(),
                permissionGranted = permission,
                nowMs = now,
                lastPostedAtMs = lastPostedAtMs,
                lastSessionId = lastSessionId,
                sessionId = sessionId,
            )
        ) {
            // 后台但没权限说一声（只在日志；页面上权限页已写明后果）。
            if (!permission) RunLog.log("抱朴完成通知：权限未授予，只更新会话不弹通知")
            return
        }
        lastPostedAtMs = now
        lastSessionId = sessionId
        runCatching {
            NotificationChannels.ensureAll(ctx)
            val n = Notification.Builder(ctx, NotificationChannels.AGENT)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(BaopuCompletionRules.titleOf(kind, outcome))
                .setContentText(BaopuCompletionRules.textOf(kind, outcome))
                .setContentIntent(contentIntent(ctx, sessionId))
                .setAutoCancel(true)
                .setOngoing(false)
                // 同一 NOTIF_ID 的后续更新不再响铃/振动（合并而不是刷屏）
                .setOnlyAlertOnce(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        }.onFailure {
            // 系统拒绝/渠道被关都不许把这一轮收尾弄崩。
            RunLog.log("抱朴完成通知：发送失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun contentIntent(ctx: Context, sessionId: Long): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_SESSION,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BaopuIntents.EXTRA_SESSION, sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
