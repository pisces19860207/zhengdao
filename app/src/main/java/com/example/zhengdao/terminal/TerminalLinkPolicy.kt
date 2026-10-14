package com.example.zhengdao.terminal

import android.net.Uri

/**
 * 终端链接打开策略（scheme allowlist + 安全确认）—— §4.6 安全层。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.6 安全层；
 * 源头为 10-12 审计报告要求："外部 URI 必须做 scheme allowlist 与安全确认，
 * 避免终端内容任意跳转"（② 文档原文）。
 *
 * 立场：终端输出**不可信**——任何程序都能打印伪装链接，这与"用户在地址栏主动输入"
 * 有本质区别。故：
 * - http/https → App 内玄览打开（默认，见打开层）；
 * - mailto:/tel: 等有明确接收者的 → 交系统处理；
 * - intent:// / file:// / content:// / 自定义 scheme → **默认拒绝**（终端内容任意跳转的最大面）；
 * - allowlist 外或首次出现的域名 → CONFIRM（弹框显示**完整目标 URL**，防"显示 A 跳 B"钓鱼）。
 *
 * 边界（§4.6）：终端链接点击是**用户行为**——不经 §4.5 桥、不触发脱敏；
 * 脱敏红线只盖"流向 agent 的数据"，不盖用户自己看。
 *
 * 状态：骨架（未实现）。阶段 1.5 填肉。
 */
object TerminalLinkPolicy {

    enum class Verdict {
        /** App 内玄览打开（默认）。 */
        OPEN_IN_APP,
        /** 交系统处理（mailto/tel 等）。 */
        OPEN_SYSTEM,
        /** 弹框显示完整 URL，用户确认后再开（allowlist 外/首次域名）。 */
        CONFIRM,
        /** 拒绝（危险 scheme）。 */
        REJECT,
    }

    /** 放行的 scheme（http/https 走 App 内；mailto/tel 走系统）。 */
    private val SYSTEM_HANDLED = setOf("mailto", "tel", "sms", "geo")

    /** 一律拒绝的 scheme（终端内容不可信，§4.6）。 */
    private val DENIED = setOf("intent", "file", "content", "android-app")

    fun classify(url: String): Verdict {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
            ?: return Verdict.REJECT // 无 scheme 的不是有效链接
        return when (scheme) {
            "http", "https" -> Verdict.OPEN_IN_APP
            in SYSTEM_HANDLED -> Verdict.OPEN_SYSTEM
            in DENIED -> Verdict.REJECT
            else -> Verdict.REJECT // 未知自定义 scheme 默认拒（白名单外）
        }
    }

    /**
     * 首次域名确认（③ 待定粒度）：建议"本会话首次出现的域名"或"桥设置里的已知域名簿"。
     * TODO(阶段1.5)：与打开层设置项（App 内/系统浏览器/每次询问，§4.6）合并设计。
     */
    fun needsConfirm(url: String): Boolean {
        // TODO(阶段1.5)
        return false
    }
}
