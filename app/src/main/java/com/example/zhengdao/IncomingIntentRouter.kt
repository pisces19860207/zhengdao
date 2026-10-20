// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import java.net.URLDecoder

/**
 * 第八十三批（§B 88）：系统/其他 App 送进来的一次请求，**已从 Intent 拍平成纯数据**。
 *
 * 为什么要拍平：本仓没有 Robolectric/Compose UI 测试基建，[android.content.Intent] 与
 * [android.net.Uri] 在 JVM 单测里不可用。路由判据全部放在这里，Activity 只做「取字段 →
 * 调 [routeIncomingIntent] → 按结果分发」；分享/深链的优先级、长度、拒绝口径都能被
 * 普通 JVM 用例逐条钉住。
 *
 * 安全前提：这里的一切字段都来自**外部不可信输入**，只当数据看，不据此授予任何权限。
 */
internal data class IncomingIntent(
    /** Intent action；null = 没有 action（例如裸 data 的投递）。 */
    val action: String?,
    /** `Uri.scheme`（大小写不敏感，路由时统一小写比较）。 */
    val scheme: String? = null,
    /** `Uri.host`（同上）。 */
    val host: String? = null,
    /** `Uri.path`；`zhengdao://ask` 与 `zhengdao://ask/` 都算无路径。 */
    val path: String? = null,
    /** `Uri.encodedQuery`（**不预解码**，解码与畸形判断由路由统一做）。 */
    val rawQuery: String? = null,
    /** `Uri.isHierarchical`；`zhengdao:ask?q=…` 这种不透明 URI 直接拒。 */
    val isHierarchical: Boolean = true,
    /** `Uri.userInfo != null`；深链不需要任何用户信息，出现即视为伪形。 */
    val hasUserInfo: Boolean = false,
    /** `Uri.port`；没有端口时 Android 返回 -1，深链出现端口一律拒。 */
    val port: Int = -1,
    /** `Intent.type`（ACTION_SEND 的 MIME）。 */
    val type: String? = null,
    /** `Intent.EXTRA_TEXT`。 */
    val text: String? = null,
    /** `ClipData` 第一项的**纯文本**（只取 text/htmlText，绝不 coerce 成 URI 去读文件）。 */
    val clipText: String? = null,
    /** 单次消费门的键；由 Activity 侧生成（见 [incomingIntentToken]）。 */
    val token: String = "",
)

/** 一次外部请求的路由结果。 */
internal sealed interface IncomingRoute {
    /** ACTION_SEND text/plain：预填抱朴输入框（**不自动发送**）。 */
    data class ShareToBaopu(val text: String, val truncated: Boolean) : IncomingRoute

    /** `zhengdao://ask?q=…`：URL decode 后同样预填（**不自动发送**）。 */
    data class AskBaopu(val text: String, val truncated: Boolean) : IncomingRoute

    /** `zhengdao://terminal?cmd=…`：只把命令预填到终端输入行（**不带换行、不执行**）。 */
    data class PrefillTerminal(val command: String) : IncomingRoute

    /** 外部输入被拒：原因进日志，界面不弹任何东西。 */
    data class Rejected(val reason: RejectReason) : IncomingRoute

    /** 与分享/深链无关的 Intent（桌面启动、内部快捷方式等），静默放过。 */
    data object Ignore : IncomingRoute
}

/** 拒绝原因（报告里的「拒绝清单」逐条对应这里）。 */
internal enum class RejectReason(val label: String) {
    UNSUPPORTED_TYPE("不是 text/plain"),
    UNSAFE_SCHEME("只认 zhengdao://"),
    MALFORMED_URI("URI 形态不合法"),
    UNKNOWN_HOST("未知 host"),
    EMPTY_TEXT("正文为空"),
    COMMAND_TOO_LONG("命令过长"),
    COMMAND_HAS_CONTROL("命令含换行/控制字符"),
}

/** `zhengdao://terminal?cmd=` 预填命令的 Intent extra 名（MainActivity 写、TerminalActivity 读）。 */
internal const val EXTRA_TERMINAL_PREFILL = "prefill_cmd"

/** 抱朴预填上限：超出截断并在输入框旁提示（8000 字 ≈ 4k tokens，足够放一条长分享）。 */
internal const val MAX_BAOPU_CHARS = 8_000

/** 终端预填上限：命令不是文章，超长一律拒（截断命令可能变成另一条命令）。 */
internal const val MAX_TERMINAL_COMMAND_CHARS = 500

/** 原始 query 的硬上限：先挡大小，再谈解码（避免外部用超长 URI 打内存）。 */
private const val MAX_RAW_QUERY_CHARS = 32_000

private const val ACTION_SEND = "android.intent.action.SEND"
private const val ACTION_VIEW = "android.intent.action.VIEW"
private const val DEEP_LINK_SCHEME = "zhengdao"

/**
 * 外部 Intent 的唯一路由判据（纯函数）。
 *
 * 优先级：**ACTION_SEND 先于 data** —— 分享进来的 Intent 即使带了奇怪的 data，也按分享的
 * 文本处理；反过来只看 ACTION_VIEW 的 scheme/host，绝不因为 data 里写了 `zhengdao://`
 * 就把一个 SEND 当成深链。
 *
 * 返回 [IncomingRoute.Ignore] 只给「与本单无关」的 action（MAIN 等）；形态不对但确实是
 * 分享/深链的，一律 [IncomingRoute.Rejected] 并记日志 —— 静默丢弃一次用户动作等于
 * "点了没反应"，必须能查。
 */
internal fun routeIncomingIntent(request: IncomingIntent): IncomingRoute = when (request.action) {
    ACTION_SEND -> routeSend(request)
    ACTION_VIEW -> routeView(request)
    else -> IncomingRoute.Ignore
}

private fun routeSend(request: IncomingIntent): IncomingRoute {
    // MIME 允许参数（`text/plain; charset=utf-8`），其余类型（图片/任意文件）本批不做。
    val mime = request.type?.substringBefore(';')?.trim()?.lowercase()
    if (mime != "text/plain") return IncomingRoute.Rejected(RejectReason.UNSUPPORTED_TYPE)

    // EXTRA_TEXT 优先；没有时只接受 ClipData 里的**纯文本**。
    // 不调用 coerceToText：content:// / file:// 会被解析器真的读出来，等于接收本地文件。
    val raw = request.text?.takeIf { it.isNotBlank() }
        ?: request.clipText?.takeIf { it.isNotBlank() }
        ?: return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)
    val normalized = normalizeIncomingText(raw).trim()
    if (normalized.isEmpty()) return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)

    val capped = takeSafely(normalized, MAX_BAOPU_CHARS)
    return IncomingRoute.ShareToBaopu(capped.text, capped.truncated)
}

private fun routeView(request: IncomingIntent): IncomingRoute {
    if (!request.isHierarchical) return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    val scheme = request.scheme?.lowercase()
        ?: return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    // http/https 不声明、不接收：那是玄览的浏览器地盘（派工单 §1①）。
    if (scheme != DEEP_LINK_SCHEME) return IncomingRoute.Rejected(RejectReason.UNSAFE_SCHEME)
    if (request.hasUserInfo || request.port != -1) {
        return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    }
    val path = request.path.orEmpty()
    if (path.isNotEmpty() && path != "/") return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)

    return when (request.host?.lowercase()) {
        "ask" -> routeAsk(request.rawQuery)
        "terminal" -> routeTerminal(request.rawQuery)
        null -> IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
        else -> IncomingRoute.Rejected(RejectReason.UNKNOWN_HOST)
    }
}

private fun routeAsk(rawQuery: String?): IncomingRoute {
    val raw = when (val found = lookupQuery(rawQuery, "q")) {
        is QueryLookup.Found -> found.value
        QueryLookup.Missing -> return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)
        QueryLookup.Bad -> return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    }
    val decoded = decodeQueryValue(raw)
        ?: return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    val normalized = normalizeIncomingText(decoded).trim()
    if (normalized.isEmpty()) return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)
    val capped = takeSafely(normalized, MAX_BAOPU_CHARS)
    return IncomingRoute.AskBaopu(capped.text, capped.truncated)
}

private fun routeTerminal(rawQuery: String?): IncomingRoute {
    val raw = when (val found = lookupQuery(rawQuery, "cmd")) {
        is QueryLookup.Found -> found.value
        QueryLookup.Missing -> return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)
        QueryLookup.Bad -> return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    }
    val decoded = decodeQueryValue(raw)
        ?: return IncomingRoute.Rejected(RejectReason.MALFORMED_URI)
    val command = decoded.trim()
    if (command.isEmpty()) return IncomingRoute.Rejected(RejectReason.EMPTY_TEXT)
    if (command.length > MAX_TERMINAL_COMMAND_CHARS) {
        return IncomingRoute.Rejected(RejectReason.COMMAND_TOO_LONG)
    }
    if (containsTerminalControl(command)) {
        return IncomingRoute.Rejected(RejectReason.COMMAND_HAS_CONTROL)
    }
    return IncomingRoute.PrefillTerminal(command)
}

/**
 * 终端预填命令的二次校验（TerminalActivity 写 pty 前再问一遍）。
 *
 * 为什么两处都判：路由层是第一道闸，但 TerminalActivity 是真正把字节送进 pty 的地方；
 * 任何一条新入口（将来别的内部调用方）绕过路由时，这里必须仍然兜住「不带换行/控制符」。
 */
internal fun isSafeTerminalPrefillCommand(command: String): Boolean =
    command.isNotBlank() &&
        command.length <= MAX_TERMINAL_COMMAND_CHARS &&
        !containsTerminalControl(command)

/**
 * 终端命令里的控制字符一律拒：`\n`/`\r` 会直接执行，ESC 等可以注入终端序列。
 *
 * 范围取 C0（<0x20）、DEL（0x7f）、C1（0x80..0x9f）与三个 Unicode 行分隔符。
 * 中文/emoji 都大于 0x9f，不会误伤。
 */
private fun containsTerminalControl(text: String): Boolean = text.any { c ->
    c.code < 0x20 ||
        c.code == 0x7f ||
        c.code in 0x80..0x9f ||
        c == '\u2028' ||
        c == '\u2029'
}

/** 分享/提问正文的清洗：换行统一成 LF，去掉除 Tab 外的控制字符（NUL/ESC/DEL/C1）。 */
internal fun normalizeIncomingText(text: String): String {
    val unified = text.replace("\r\n", "\n").replace('\r', '\n')
    return unified.filter {
        it == '\n' || it == '\t' ||
            (it.code >= 0x20 && it.code != 0x7f && it.code !in 0x80..0x9f)
    }
}

private data class CappedText(val text: String, val truncated: Boolean)

/** 按字符截断；截断点落在代理对中间时回退一格，避免留下孤立高位代理。 */
private fun takeSafely(text: String, max: Int): CappedText {
    if (text.length <= max) return CappedText(text, false)
    var end = max
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return CappedText(text.substring(0, end), true)
}

private sealed interface QueryLookup {
    data class Found(val value: String) : QueryLookup
    data object Missing : QueryLookup
    data object Bad : QueryLookup
}

/**
 * 取一个 query 参数（**原始值，未解码**）。
 *
 * 缺参数与畸形分开：缺 = 正文为空（用户可理解），畸形/重复 = URI 不合法（可能是攻击）。
 * 重复参数不做"取最后一个"的猜测 —— 两个 `q=` 只会让"看到的是 A、用的是 B"成为可能。
 */
private fun lookupQuery(rawQuery: String?, key: String): QueryLookup {
    if (rawQuery.isNullOrEmpty()) return QueryLookup.Missing
    if (rawQuery.length > MAX_RAW_QUERY_CHARS) return QueryLookup.Bad
    var found: String? = null
    for (part in rawQuery.split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val rawKey = if (eq < 0) part else part.substring(0, eq)
        val rawValue = if (eq < 0) "" else part.substring(eq + 1)
        val decodedKey = decodeQueryValue(rawKey) ?: return QueryLookup.Bad
        if (decodedKey != key) continue
        if (found != null) return QueryLookup.Bad
        found = rawValue
    }
    return if (found == null) QueryLookup.Missing else QueryLookup.Found(found)
}

/**
 * 深链 query 的百分号解码。
 *
 * 不用 `Uri.getQueryParameter`（JVM 里没有）；`URLDecoder` 语义是表单编码，会把 `+` 当空格，
 * 而 URI 里的 `+` 是字面加号 —— 先把 `+` 转成 `%2B` 再解，保持 URI 口径。
 * 畸形 `%` 序列返回 null（交给调用方按 MALFORMED_URI 拒），不做"尽量猜"。
 */
private fun decodeQueryValue(raw: String): String? = runCatching {
    URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
}.getOrNull()

/**
 * 把用户已有草稿和外部送来的正文合到一起（纯函数）。
 *
 * 口径：输入框空 → 直接用送来的正文；已有草稿 → 保留草稿，空一行再接进来。
 * **不覆盖用户正在写的东西**，也**不自动发送**（发送只由用户点按钮触发）。
 */
internal fun mergeIncomingDraft(existing: String, incoming: String): String {
    val next = incoming.trim()
    if (next.isEmpty()) return existing
    if (existing.isBlank()) return next
    return existing.trimEnd() + "\n\n" + next
}

/**
 * 单次消费门的键：对象身份 + action/data/正文摘要，够区分"同一次投递"与"再分享一次"。
 *
 * data 与正文都只留 hashCode：门里按容量保留最近若干条，深链再长也不把整条 URL 攥在内存里。
 */
internal fun incomingIntentToken(
    identityHash: Int,
    action: String?,
    dataString: String?,
    text: String?,
): String =
    "$identityHash|${action.orEmpty()}|${dataString.orEmpty().hashCode()}|${text.orEmpty().hashCode()}"

/**
 * 进程级单次消费门（纯 JVM 可测）。
 *
 * 同一 token 只在第一次返回 true；保留最近 [capacity] 条，防止内存随投递增长。
 * 空 token 不拦（没有身份就没有"重复"可言，宁可多处理一次也不吞用户动作）。
 */
internal class IntentSingleShot(private val capacity: Int = 16) {

    init {
        require(capacity > 0) { "capacity 必须为正" }
    }

    private val handled = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun shouldHandle(token: String?): Boolean {
        if (token.isNullOrBlank()) return true
        if (handled.containsKey(token)) return false
        handled[token] = Unit
        return true
    }
}
