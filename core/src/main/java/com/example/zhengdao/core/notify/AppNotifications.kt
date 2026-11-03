// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android 官方文档（NotificationChannel，API 26+）。
package com.example.zhengdao.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 通知渠道注册表（P6，2026-10-06）：4 渠道分类（用户定稿）。
 * - ID 发布后不可重命名（系统按 ID 记忆用户设置）——只在这里集中定义
 * - 首次调用时创建；重复创建是幂等操作（系统忽略同 ID 重复注册）
 *
 * 第二百四十批（终端解耦第一刀）：从 `terminal/NotificationChannels` 搬进 `:core`，
 * 类名改为 [AppNotifications]（派工单口径）。**四个渠道 id 一字未改**——
 * `session` / `install` / `agent` / `update` 是已发布值：系统按 id 记忆用户的通知设置，
 * 改 id 等于把用户的静音/重要性设置全部重置（负例②）。
 *
 * 第三百八十八单（2026-11-03 用户拍板）：**`agent` 渠道 IMPORTANCE 从 DEFAULT 升 HIGH**——
 * 离场值守的"红线停手/圈外入队"要横幅（HIGH 档），不新增第五个渠道、也不动其余三渠道。
 * 升级路径与代价（写在这里是因为只此一处）：
 * - `NotificationChannel` 只创建一次，对已装用户**改 importance 不生效**；
 *   ⇒ 必须同 ID **delete + recreate**（Android 允许按 channelId 删了重建）；
 * - 首次重建会**重置该渠道的用户手动设置**（用户在系统设置里调过"agent"的静音/震动 ⇒ 归零）；
 * - 所以只在"现存 importance **低于** HIGH"时重建；已经 HIGH 的（新装即 HIGH）一个字不碰；
 * - "同一渠道不能分两档"：值守的低优先通知（降级/暂停/报告）走 `session`（LOW），不占 agent。
 */
object AppNotifications {

    /** 会话状态（前台服务常驻，LOW 不可划掉——划掉=保活失效） */
    const val SESSION = "session"

    /** 环境与 Agent 安装（下载进度等，LOW 静默） */
    const val INSTALL = "install"

    /** Agent 执行（388 起 HIGH：红线停手/圈外入队要横幅） */
    const val AGENT = "agent"

    /** 更新提示（DEFAULT） */
    const val UPDATE = "update"

    fun ensureAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun channel(id: String, name: String, importance: Int, desc: String) {
            nm.createNotificationChannel(
                NotificationChannel(id, name, importance).apply { description = desc }
            )
        }
        channel(SESSION, "会话状态", NotificationManager.IMPORTANCE_LOW, "显示后台会话状态；关闭会导致会话无法保活")
        channel(INSTALL, "环境与安装", NotificationManager.IMPORTANCE_LOW, "环境与 Agent 的下载安装进度")
        ensureAgentChannel(nm, ::channel)
        channel(UPDATE, "更新提示", NotificationManager.IMPORTANCE_DEFAULT, "App 与组件的版本更新提示")
    }

    /**
     * agent 渠道的升档口（388，见类注释）：新装直接建 HIGH；
     * 已存在但低于 HIGH ⇒ delete + recreate（同 ID 同名）；已是 HIGH ⇒ 不碰。
     */
    private fun ensureAgentChannel(
        nm: NotificationManager,
        channel: (String, String, Int, String) -> Unit,
    ) {
        val desc = "Agent 运行中的重要事件（红线停手/圈外入队为高优先横幅）"
        val existing = nm.getNotificationChannel(AGENT)
        if (existing == null) {
            channel(AGENT, "Agent 执行", NotificationManager.IMPORTANCE_HIGH, desc)
            return
        }
        if (existing.importance < NotificationManager.IMPORTANCE_HIGH) {
            nm.deleteNotificationChannel(AGENT)
            channel(AGENT, "Agent 执行", NotificationManager.IMPORTANCE_HIGH, desc)
        }
    }
}
