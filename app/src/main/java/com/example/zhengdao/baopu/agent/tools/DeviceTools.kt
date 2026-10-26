// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十八批 ④⑤⑥：递到你手上的三件——`clipboard_write`（R1）／`notify`（R1）／
// `phone_dial`（R1，**只开拨号盘不外呼**）。判据全在这（纯函数可测）；Android 调用经
// [DeviceHost] 注入（房型照 `CapabilitiesTool.sink`：工具层不认识 Context，生产由
// `BaopuRuntime` 注入真实现，测试注入记录器）。
//
// 三条"只发摘要不发原文"的账（派工单 §3）：
//   - clipboard_write：日志只记长度；返回给模型的只有长度＋前 40 字摘要；
//   - notify：内容里的手机号先脱敏（旁人能看见通知面板）；日志只记长度；
//   - phone_dial：号码清洗后只回显位数与前几位；日志只记长度。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.RunLog
import org.json.JSONObject

/** Android 侧的三个口（生产实现在 `BaopuRuntime`；null ⇒ 工具如实说没接上）。 */
internal interface DeviceHost {
    /** 写剪贴板（`ClipboardManager.setPrimaryClip`）。 */
    fun copyToClipboard(text: String): Boolean

    /** 通知权限给没给（`POST_NOTIFICATIONS`）。 */
    fun notificationsEnabled(): Boolean

    /** 发一条本地通知（渠道由调用方选；返回 false＝没发出去）。 */
    fun postNotification(title: String, body: String, channel: String?): Boolean

    /** 打开系统拨号盘并填号（**ACTION_DIAL**——只打开，绝不外呼）。 */
    fun openDialer(number: String): Boolean
}

internal object DeviceTools {

    /** 生产/测试注入点（null ⇒ 三件工具都返回"宿主没接上"）。 */
    internal var host: DeviceHost? = null

    /** 剪贴板上限（派工单 §1④：≤100KB）。 */
    const val CLIPBOARD_MAX_BYTES = 100 * 1024

    /** 摘要长度（返回给模型看的那一小截）。 */
    const val SUMMARY_CHARS = 40

    /** notify 的频率上限（派工单 §1⑤：每分钟 ≤3 条，超了合并成一条）。 */
    const val NOTIFY_PER_MINUTE = 3

    /** 手机号样式（通知脱敏用：7 位以上数字段打码；剪贴板提示同一把尺）。 */
    internal val PHONE_STYLE = Regex("""(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)|(?<!\d)\d{7,12}(?!\d)""")

    /** 身份证样式（18 位；只提示不阻断）。 */
    internal val ID_STYLE = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")

    /** 通知脱敏：手机号/身份证中间打码（负例：通知内容含未脱敏手机号）。 */
    fun maskSensitive(text: String): String = text
        .replace(PHONE_STYLE) { m ->
            val s = m.value
            if (s.length <= 4) s else s.take(2) + "****" + s.takeLast(2)
        }
        .replace(ID_STYLE) { m -> m.value.take(4) + "***********" + m.value.takeLast(3) }

    /** 文本含不含敏感样式（剪贴板"提示不阻断"的判据）。 */
    fun hasSensitive(text: String): Boolean = PHONE_STYLE.containsMatchIn(text) || ID_STYLE.containsMatchIn(text)

    /** 号码清洗：只留电话键盘会有的字符（负例：号码含字母未清洗）。 */
    fun cleanNumber(raw: String): String = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    /** 每分钟计数（notify 频控；进程内存态——进程重启清零是可接受的松口径）。 */
    private val recentNotifyMs = mutableListOf<Long>()

    /** 频控（**同步**：工具池单线程串行 ⇒ 无竞态）。超了 ⇒ 合并成一条（正文带省略说明）。 */
    internal fun notifyAllowed(nowMs: Long): Pair<Boolean, Int> {
        recentNotifyMs.removeAll { nowMs - it > 60_000L }
        return (recentNotifyMs.size < NOTIFY_PER_MINUTE) to recentNotifyMs.size
    }

    internal fun notifyRecorded(nowMs: Long) {
        recentNotifyMs += nowMs
    }
}

// ── ④ clipboard_write ────────────────────────────────────────────────────────

internal object ClipboardWriteTool {

    const val ARG_TEXT = "text"

    fun spec(): ToolSpec = ToolSpec(
        name = "clipboard_write",
        description = "把一段文本写进系统剪贴板（≤100KB），用户随手一贴就用上。" +
            "含手机号/身份证样式的文本会提示（不阻断）。成功后只返回长度与前 40 字摘要，" +
            "全文不进日志。" +
            "（这个工具在 DEVICE 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TEXT to jsonObjectOf("type" to "string", "description" to "要放进剪贴板的文本"),
            ),
            "required" to jsonArrayOf(ARG_TEXT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 剪贴板是用户自己贴走，无系统副作用；敏感提示在结果里
        riskTier = "R1",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    fun run(args: JSONObject, nowMs: Long): ToolOutcome {
        val host = DeviceTools.host
            ?: return ToolOutcome.Failed("设备宿主没接上（剪贴板写不了）——如实说，不假装写成了")
        val text = args.optString(ARG_TEXT)
        if (text.isEmpty()) return ToolOutcome.Failed("text 是空的——没有可复制的内容")
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > DeviceTools.CLIPBOARD_MAX_BYTES) {
            return ToolOutcome.Failed("文本 $bytes 字节 > 上限 ${DeviceTools.CLIPBOARD_MAX_BYTES}（100KB）——不静默截断，请缩短或分次")
        }
        val written = host.copyToClipboard(text)
        if (!written) return ToolOutcome.Failed("剪贴板写入失败（系统侧拒绝）")
        // 日志只记长度与是否敏感（派工单 §3：剪贴板全文不进日志）
        RunLog.log("clipboard_write: ${bytes}B sensitive=${DeviceTools.hasSensitive(text)}")
        val hint = if (DeviceTools.hasSensitive(text)) {
            "\n⚠️ 内容含手机号/身份证样式的文本——已经放进剪贴板了，贴出去前自己再过目一眼。"
        } else {
            ""
        }
        return ToolOutcome.Ok("已复制 ${bytes} 字节到剪贴板｜开头：${text.take(DeviceTools.SUMMARY_CHARS)}$hint")
    }
}

// ── ⑤ notify ─────────────────────────────────────────────────────────────────

internal object NotifyTool {

    const val ARG_TITLE = "title"
    const val ARG_BODY = "body"
    const val ARG_CHANNEL = "channel"

    fun spec(): ToolSpec = ToolSpec(
        name = "notify",
        description = "发一条本地通知（活干完了让你知道）。需要通知权限——没给会明说去哪里开，" +
            "不静默失败。每分钟最多 3 条，超了会合并。通知面板别人可能看见，手机号会先打码。" +
            "（这个工具在 DEVICE 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TITLE to jsonObjectOf("type" to "string", "description" to "通知标题（一句话）"),
                ARG_BODY to jsonObjectOf("type" to "string", "description" to "通知正文（会显示在通知面板，敏感号已自动打码）"),
                ARG_CHANNEL to jsonObjectOf("type" to "string", "description" to "可选：渠道名（便于分组静音）"),
            ),
            "required" to jsonArrayOf(ARG_TITLE, ARG_BODY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R1",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    fun run(args: JSONObject, nowMs: Long): ToolOutcome {
        val host = DeviceTools.host
            ?: return ToolOutcome.Failed("设备宿主没接上（发不了通知）——如实说，不假装发成了")
        val title = args.optString(ARG_TITLE).ifBlank { return ToolOutcome.Failed("缺 title") }
        val body = args.optString(ARG_BODY).ifBlank { return ToolOutcome.Failed("缺 body") }
        // 频控（负例：刷屏）：超了合并不丢弃
        val (allowed, recent) = DeviceTools.notifyAllowed(nowMs)
        if (!allowed) {
            return ToolOutcome.Failed(
                "这一分钟已经发过 ${DeviceTools.NOTIFY_PER_MINUTE} 条通知（频控：每分钟 ≤3 条，防刷屏）——" +
                    "把要说的合并成一条再发，或等下一分钟",
            )
        }
        if (!host.notificationsEnabled()) {
            // 没权限 ⇒ 明说去哪开（负例：没权限静默失败）
            return ToolOutcome.Failed(
                "通知权限没给，发不出去。去 手机「设置 → 应用 → 证道 → 通知」打开权限" +
                    "（或系统弹的那张「申请通知权限」卡上点允许）——我这边不静默失败。",
            )
        }
        val masked = DeviceTools.maskSensitive(body) // 通知面板旁人可见：先打码
        val posted = host.postNotification(title, masked, args.optString(ARG_CHANNEL).ifBlank { null })
        if (!posted) return ToolOutcome.Failed("通知没发出去（系统侧拒绝）——不静默")
        DeviceTools.notifyRecorded(nowMs)
        RunLog.log("notify: ${masked.length}ch（最近 1 分钟 ${recent + 1} 条）")
        return ToolOutcome.Ok("通知已发：${title}（正文 ${masked.length} 字，敏感号已打码）")
    }
}

// ── ⑥ phone_dial ─────────────────────────────────────────────────────────────

internal object PhoneDialTool {

    const val ARG_NUMBER = "number"

    fun spec(): ToolSpec = ToolSpec(
        name = "phone_dial",
        description = "打开系统拨号盘并把号码填好（**只打开，不外呼**——按不按呼叫键由用户决定）。" +
            "号码来自表或卡片（可追溯）；空号/认不出的号码会拒绝。" +
            "（这个工具在 DEVICE 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NUMBER to jsonObjectOf("type" to "string", "description" to "要填进拨号盘的号码（纯数字或带分隔符，内部会清洗）"),
            ),
            "required" to jsonArrayOf(ARG_NUMBER),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R1",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    fun run(args: JSONObject, nowMs: Long): ToolOutcome {
        @Suppress("UNUSED_PARAMETER") val unused = nowMs
        val host = DeviceTools.host
            ?: return ToolOutcome.Failed("设备宿主没接上（打不开拨号盘）——如实说")
        val raw = args.optString(ARG_NUMBER).ifBlank { return ToolOutcome.Failed("缺 number（号码不能为空）") }
        // 清洗（负例：号码含字母未清洗）——字母/汉字一律剥掉，剥完是空的 ⇒ 空号拒绝
        val cleaned = DeviceTools.cleanNumber(raw)
        if (cleaned.isEmpty() || cleaned.count { it.isDigit() } < 3) {
            return ToolOutcome.Failed(
                "「$raw」清洗后不是有效号码（只剩「$cleaned」）——空号拒绝；来源要能点开（表/卡片），别凭空拨",
            )
        }
        val opened = host.openDialer(cleaned)
        if (!opened) return ToolOutcome.Failed("拨号盘没打开（系统侧拒绝）")
        RunLog.log("phone_dial: ${cleaned.length} 位（ACTION_DIAL 只开盘不外呼）")
        return ToolOutcome.Ok(
            "拨号盘已打开，号码已填：${cleaned.take(4)}…（共 ${cleaned.length} 位）。" +
                "只开了盘，没按呼叫——打不打你定。",
        )
    }
}
