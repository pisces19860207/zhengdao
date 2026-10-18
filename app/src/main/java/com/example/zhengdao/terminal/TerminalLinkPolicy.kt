package com.example.zhengdao.terminal

/**
 * 终端链接打开策略（scheme allowlist ＋ 安全确认）—— §4.6 安全层。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.6 安全层；
 * 源头为 10-12 审计报告要求："外部 URI 必须做 scheme allowlist 与安全确认，
 * 避免终端内容任意跳转"（② 文档原文）。
 *
 * 立场：终端输出**不可信**——任何程序都能打印伪装链接，这与"用户在地址栏主动输入"
 * 有本质区别。故：
 * - http/https → **host 不在信任簿 ⇒ CONFIRM**（弹框显示完整目标 URL，防"显示 A 跳 B"
 *   钓鱼）；host 在信任簿 ⇒ 直接 App 内玄览打开；
 * - mailto:/tel: 等有明确接收者的 → 交系统处理；
 * - intent:// / file:// / content:// / 自定义 scheme → **默认拒绝**（终端内容任意跳转的最大面）。
 *
 * 边界（§4.6）：终端链接点击是**用户行为**——不经 §4.5 桥、不触发脱敏；
 * 脱敏红线只盖"流向 agent 的数据"，不盖用户自己看。
 *
 * ## 第二十一批（2026-10-18）：「假安全」修成真闸门
 * 旧版 `needsConfirm` 恒 `false`、`classify` 从不产出 `CONFIRM`——界面上写着
 * "首次域名二次确认"，代码里这条判据是空的（接线审计 P1）。本版：
 * 1. `needsConfirm(url, trusted)` 与 `classify(url, trusted)` **同源**——判据只有一个
 *    私有函数 [verdictFor]，两个入口都走它（派工单 J1：不许一个说确认、一个说直接开）；
 * 2. 信任簿（trusted）由调用方持有（TerminalActivity 会话内记忆），本 object
 *    **不存状态**——纯函数、JVM 直测、无 Android API（scheme/host 都是手写解析，
 *    不碰 `android.net.Uri`，否则单测里全是 stub）；
 * 3. 信任簿上限 [MAX_TRUSTED]：会话内记忆，防把内存点爆。
 */
object TerminalLinkPolicy {

    enum class Verdict {
        /** App 内玄览打开（信任簿内的 http/https）。 */
        OPEN_IN_APP,
        /** 交系统处理（mailto/tel 等）。 */
        OPEN_SYSTEM,
        /** 弹框显示完整 URL，用户确认后再开（信任簿外的域名）。 */
        CONFIRM,
        /** 拒绝（危险 scheme）。 */
        REJECT,
    }

    /** 放行的 scheme（http/https 走 App 内；mailto/tel 走系统）。 */
    private val SYSTEM_HANDLED = setOf("mailto", "tel", "sms", "geo")

    /** 一律拒绝的 scheme（终端内容不可信，§4.6）。 */
    private val DENIED = setOf("intent", "file", "content", "android-app")

    /** 信任簿域名数上限（会话内记忆；调用方负责裁切，本 object 只给口径）。 */
    const val MAX_TRUSTED = 64

    /**
     * 分类 verdict（**纯函数**；判据本体，[needsConfirm] 也调它）。
     *
     * @param url 点击的链接原文
     * @param trusted 已信任域名簿（host，建议小写；比较时统一小写，大小写不敏感）
     */
    fun classify(url: String, trusted: Set<String> = emptySet()): Verdict = verdictFor(url, trusted)

    /**
     * 要不要弹二次确认（**纯函数**，JVM 直测）。
     *
     * 与 [classify] 同源：`needsConfirm == (classify == CONFIRM)`——旧版这里恒
     * `false` 的"假安全"就是这么烂掉的（J1 的靶子）。
     */
    fun needsConfirm(url: String, trusted: Set<String> = emptySet()): Boolean =
        verdictFor(url, trusted) == Verdict.CONFIRM

    /** 唯一判据（两个入口都调它——别在这里之外再写第二套口径）。 */
    private fun verdictFor(url: String, trusted: Set<String>): Verdict {
        val scheme = schemeOf(url) ?: return Verdict.REJECT // 无 scheme 的不是有效链接
        return when (scheme) {
            "http", "https" -> {
                val host = hostOf(url)?.lowercase() ?: return Verdict.REJECT // http 无 host ⇒ 不可信
                if (trusted.any { it.lowercase() == host }) Verdict.OPEN_IN_APP else Verdict.CONFIRM
            }
            in SYSTEM_HANDLED -> Verdict.OPEN_SYSTEM
            in DENIED -> Verdict.REJECT
            else -> Verdict.REJECT // 未知自定义 scheme 默认拒（白名单外）
        }
    }

    /**
     * scheme 纯解析（RFC 3986：字母开头，后接字母/数字/`+`/`-`/`.`）。
     *
     * 不碰 `android.net.Uri`：那个是 Android stub，JVM 单测里 `scheme` 拿不到值。
     */
    internal fun schemeOf(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!s.first().isLetter()) return null
        if (!s.all { it.isLetterOrDigit() || it in "+-." }) return null
        return s.lowercase()
    }

    /**
     * host 纯解析（**不含端口、不含 userinfo**；信任簿按 host 记，端口不影响信任）。
     *
     * 覆盖：`host` / `host:port` / `user@host:port` / `[::1]:8080`；没有 `://`
     * authorities 的形态返回 null（调用方按 REJECT 处理）。
     */
    internal fun hostOf(url: String): String? {
        val marker = "://"
        val start = url.indexOf(marker)
        if (start < 0) return null
        var authority = url.substring(start + marker.length)
        val end = authority.indexOfFirst { it in "/?#" }
        if (end >= 0) authority = authority.substring(0, end)
        if (authority.isEmpty()) return null
        // userinfo：取最后一段 @ 之后（@ 允许出现在 userinfo 里，取最后一个）
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.isEmpty()) return null
        // IPv6 字面量：[::1]:8080 ⇒ ::1（括号剥掉，端口不管）
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            return authority.substring(1, close)
        }
        // 裸 host[:port]：第一个冒号起是端口
        val colon = authority.indexOf(':')
        if (colon >= 0) authority = authority.substring(0, colon)
        return authority.takeIf { it.isNotEmpty() }
    }
}
