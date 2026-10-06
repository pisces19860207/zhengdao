// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android 官方文档（NotificationChannel，API 26+）。
package com.example.zhengdao.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 通知渠道注册表（P6，2026-10-06）：4 渠道分类（用户定稿）。
 * - ID 发布后不可重命名（系统按 ID 记忆用户设置）——只在这里集中定义
 * - 首次调用时创建；重复创建是幂等操作（系统忽略同 ID 重复注册）
 */
object NotificationChannels {

    /** 会话状态（前台服务常驻，LOW 不可划掉——划掉=保活失效） */
    const val SESSION = "session"

    /** 环境与 Agent 安装（下载进度等，LOW 静默） */
    const val INSTALL = "install"

    /** Agent 执行（DEFAULT，锁屏可见） */
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
        channel(AGENT, "Agent 执行", NotificationManager.IMPORTANCE_DEFAULT, "Agent 运行中的重要事件")
        channel(UPDATE, "更新提示", NotificationManager.IMPORTANCE_DEFAULT, "App 与组件的版本更新提示")
    }
}
