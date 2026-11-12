// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零二批 · MCP 自配五件（模型可调）：mcp_list / mcp_add_server / mcp_connect /
// mcp_disconnect / mcp_set_token。
//
// 三条硬边界在这里落地（派工单 §三条硬边界）：
// ① 令牌只进 [McpTokenVault]（Keystore 加密）——本文件没有任何令牌字段可打印，
//    回执/日志一律只说"写没写进去"；
// ② 模型**能写不能读**：读通道只有授权状态枚举（有没有/过期没），值的出口只有传输层；
// ③ `mcp_add_server` 属对外连接 ⇒ permission=CONFIRM ＋ 矩阵 R3：默认必问一次。
//
// 「零新增 UI 入口」：五件走 [com.example.zhengdao.baopu.agent.ToolGroup.CORE]（默认可见），
// 不加设置页、不加面板按钮；自动 A/B/C 在 McpAutoConnect 与 McpAuthInbox 侧。
package com.example.zhengdao.baopu.mcp

import android.content.Context
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import org.json.JSONObject

/**
 * MCP 自配五件的模型入口（进程级单例；运行环境由 BaopuRuntime 注入一次）。
 *
 * Context 纪律同 [com.example.zhengdao.baopu.agent.BackupTools]：工具层不认识 Context，
 * 只认 [Env] 里的供给闭包——JVM 单测直接调 run* 函数打真 [McpToolSource]（MockWebServer）。
 */
internal object McpSelfService {

    const val NAME_LIST = "mcp_list"
    const val NAME_ADD = "mcp_add_server"
    const val NAME_CONNECT = "mcp_connect"
    const val NAME_DISCONNECT = "mcp_disconnect"
    const val NAME_SET_TOKEN = "mcp_set_token"

    const val ARG_URL = "url"
    const val ARG_NAME = "name"
    const val ARG_ID = "id"
    const val ARG_TOKEN = "token"

    /** 外部工具的连接类超时（握手含 OAuth 发现；与 McpServerConfig 默认 30s 对齐再加余量）。 */
    private const val CONNECT_TIMEOUT_MS = 40_000L

    /** 运行环境（BaopuRuntime 唯一装配点；null = 未装配，工具如实回「没接线」）。 */
    internal class Env(
        val source: () -> McpToolSource,
        val auth: () -> McpAuthFlow?,
        /** 把授权页交给系统浏览器；true = 已打开（面板同款 ACTION_VIEW + BROWSABLE）。 */
        val browser: (String) -> Boolean,
        val log: (String) -> Unit,
        val now: () -> Long,
    )

    @Volatile
    var env: Env? = null

    /** 授权弹窗台账（自动 B「只弹一次」）：shown=本episode弹过；denied=被拒过，永不再弹。 */
    internal val popupLedger = McpAuthPopupLedger()

    // ── 规格表 ───────────────────────────────────────────────────────────

    fun specs(): List<ToolSpec> = listOf(listSpec(), addSpec(), connectSpec(), disconnectSpec(), setTokenSpec())

    internal fun listSpec() = ToolSpec(
        name = NAME_LIST,
        description = "列出已登记的 MCP 服务器：名字、id、连接状态、授权状态（**只说有没有令牌，" +
            "永不显示令牌内容**）、工具数、自动连开关、最近一次问题。用户问「MCP 服务器有哪些」" +
            "「连上没有」或你要拿 id 时调。",
        inputSchema = JSONObject(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        group = ToolGroup.CORE,
        riskTier = "R0",
    ) { _ -> runList() }

    internal fun addSpec() = ToolSpec(
        name = NAME_ADD,
        // 蓝总管验收接管（2026-11-07）：原来是四段 `+` 拼接，lint `TextConcatSpace` 新增 1 条告警
        // （`…url 必填` + `（https…` 之间没有空格）。中文本来不需要空格，但闸只看形状 ⇒
        // 改成列表拼接，文案逐字不变。
        description = listOf(
            "登记并连接一个外部 MCP 服务器（**对外连接：必弹一次确认卡**）。url 必填",
            "（https 或本机 http）；name 省略时自动取自主机名。登记后自动探测 OAuth 授权与",
            "传输类型并连接；需要授权时自动打开授权页（**只弹一次**，被拒后不再弹）。",
            "连上即报新增工具数；新工具首次调用还要用户批准。用户给了地址要接入时用。",
        ).joinToString(""),
        inputSchema = JSONObject(
            """{"type":"object","properties":{"url":{"type":"string","description":""" +
                """"MCP 服务器地址（https://… 或本机 http://127.0.0.1:…）"},""" +
                """"name":{"type":"string","description":"可选：服务器显示名；省略＝取主机名"}},""" +
                """"required":["url"],"additionalProperties":false}""",
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = CONNECT_TIMEOUT_MS,
        group = ToolGroup.CORE,
        riskTier = "R3",
    ) { args -> runAdd(args.optString(ARG_URL), args.optString(ARG_NAME)) }

    internal fun connectSpec() = ToolSpec(
        name = NAME_CONNECT,
        description = "连接／重连／刷新一台已登记的 MCP 服务器（对外动作）。已连接时重拉工具清单" +
            "（报新增/移除数）；未连接时完整连接，需要授权会自动弹一次授权页。id 用 mcp_list 查。",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"id":{"type":"string","description":""" +
                """"服务器 id（mcp_list 里拿）"}},"required":["id"],"additionalProperties":false}""",
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = CONNECT_TIMEOUT_MS,
        group = ToolGroup.CORE,
        riskTier = "R1",
    ) { args -> runConnect(args.optString(ARG_ID)) }

    internal fun disconnectSpec() = ToolSpec(
        name = NAME_DISCONNECT,
        description = "断开一台 MCP 服务器：注销它的工具、**清除本机令牌**（保险柜删除）、" +
            "关闭启动自动连。配置保留，之后还能 mcp_connect 再连。用户说「断开」「先别连这个」时用。",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"id":{"type":"string","description":""" +
                """"服务器 id（mcp_list 里拿）"}},"required":["id"],"additionalProperties":false}""",
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
        group = ToolGroup.CORE,
        riskTier = "R1",
    ) { args -> runDisconnect(args.optString(ARG_ID)) }

    internal fun setTokenSpec() = ToolSpec(
        name = NAME_SET_TOKEN,
        description = "把用户提供的访问令牌写进某台 MCP 服务器的令牌保险柜（Keystore 加密）。" +
            "令牌**不回显、不进日志、不进备份**——回执只说写没写进去。适用于服务器发预置令牌" +
            "（PAT）而走不了 OAuth 的场景；id 用 mcp_list 查。写完用 mcp_connect 验证。",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"id":{"type":"string","description":""" +
                """"服务器 id（mcp_list 里拿）"},"token":{"type":"string","description":""" +
                """"用户交给你的访问令牌原文（只此一用，入库后不许在任何回复里重复）"}},""" +
                """"required":["id","token"],"additionalProperties":false}""",
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
        group = ToolGroup.CORE,
        riskTier = "R3",
    ) { args -> runSetToken(args.optString(ARG_ID), args.optString(ARG_TOKEN)) }

    // ── 执行体（JVM 直测的入口都在这） ────────────────────────────────────

    private fun runList(): ToolOutcome {
        val e = env ?: return ToolOutcome.Failed(NOT_WIRED)
        return ToolOutcome.Ok(renderList(e.source()))
    }

    /** 清单只读渲染（纯函数，JVM 直测）：令牌只有状态列，没有值。 */
    internal fun renderList(source: McpToolSource): String {
        val rows = source.snapshot()
        if (rows.isEmpty()) {
            return "还没有登记任何 MCP 服务器。把服务器地址发来，我可以用 mcp_add_server 登记并连接。"
        }
        val sb = StringBuilder("共 ${rows.size} 台 MCP 服务器（授权列只报有无令牌，内容永不显示）：\n")
        rows.forEachIndexed { i, v ->
            sb.append(i + 1).append(". ").append(v.name)
                .append(" · id=").append(v.id)
                .append(" · 状态=").append(statusCn(v.status))
                .append(" · 授权=").append(authCn(v.auth))
                .append(" · 工具 ").append(v.toolCount).append(" 个")
                .append(" · 自动连=").append(if (v.autoConnect) "开" else "关")
                .append('\n')
                .append("   地址：").append(v.displayUrl).append('\n')
            source.lastError(v.id)?.let { sb.append("   最近问题：").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    private fun statusCn(s: McpServerStatus) = when (s) {
        McpServerStatus.CONNECTED -> "已连接"
        McpServerStatus.OFFLINE -> "离线"
        McpServerStatus.ERROR -> "出错"
    }

    private fun authCn(s: McpAuthStatus) = when (s) {
        McpAuthStatus.VALID -> "有效（有令牌）"
        McpAuthStatus.EXPIRED -> "过期（有令牌）"
        McpAuthStatus.REVOKED -> "被撤销（令牌已清）"
        McpAuthStatus.UNSUPPORTED -> "不支持 OAuth（无令牌）"
        McpAuthStatus.UNAUTHORIZED -> "未授权（无令牌）"
    }

    internal fun runAdd(urlRaw: String, nameRaw: String): ToolOutcome {
        val e = env ?: return ToolOutcome.Failed(NOT_WIRED)
        val config = draftFrom(urlRaw, nameRaw)
            ?: return ToolOutcome.Failed("地址不许可：${McpUrlRules.check(urlRaw.trim()) ?: "形状不像 URL"}")
        val source = e.source()
        if (!source.addServer(config)) {
            return ToolOutcome.Failed("这个地址已经登记过（id=${config.id}）：用 mcp_connect 即可，不要重复登记。")
        }
        // 登记得批准即进自动连名单（自动 A 的「已启用」）；连接失败也留着，机会式重试兜底。
        return connectAndReport(e, config.id, head = "已登记「${config.name}」")
    }

    internal fun runConnect(idRaw: String): ToolOutcome {
        val e = env ?: return ToolOutcome.Failed(NOT_WIRED)
        val id = idRaw.trim()
        val name = e.source().config(id)?.name
            ?: return ToolOutcome.Failed("没有这个 MCP server（$id）：先用 mcp_list 查 id。")
        return connectAndReport(e, id, head = "「$name」")
    }

    internal fun runDisconnect(idRaw: String): ToolOutcome {
        val e = env ?: return ToolOutcome.Failed(NOT_WIRED)
        val id = idRaw.trim()
        val view = e.source().snapshot().firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这个 MCP server（$id）：先用 mcp_list 查 id。")
        e.source().disconnect(id)
        return ToolOutcome.Ok("已断开「${view.name}」：工具已注销、本机令牌已从保险柜删除、自动连已关。配置保留，要再连就 mcp_connect。")
    }

    internal fun runSetToken(idRaw: String, tokenRaw: String): ToolOutcome {
        val e = env ?: return ToolOutcome.Failed(NOT_WIRED)
        val id = idRaw.trim()
        val source = e.source()
        val config = source.config(id)
            ?: return ToolOutcome.Failed("没有这个 MCP server（$id）：先用 mcp_list 查 id。")
        val auth = e.auth() ?: return ToolOutcome.Failed("授权流程未装配，无法导入令牌")
        // 硬边界①②：token 只进 vault；回执与日志都不出现内容（importToken 里同样只记事件）。
        val ok = auth.importToken(config, tokenRaw)
        if (!ok) {
            // 606④：形状闸三通道同一把尺——非 ASCII 本机就拒，入库前拦下。
            return ToolOutcome.Failed(
                "令牌形状不合法（空/超长/含控制字符或非 ASCII，只收可见 ASCII）：没有写入保险柜。",
            )
        }
        return ToolOutcome.Ok(
            "已把「${config.name}」的令牌写进令牌保险柜（Keystore 加密）。" +
                "内容不回显、不进日志、不进备份；现在可以 mcp_connect（id=$id）验证连接。",
        )
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 连接并把结果翻成回执；NeedsAuth 走自动 B（台账管「只弹一次」）。
     *
     * @param head 回执开头（「已登记「X」」/「「X」」）
     */
    private fun connectAndReport(e: Env, id: String, head: String): ToolOutcome {
        val outcome = try {
            e.source().connect(id)
        } catch (ex: Exception) {
            return ToolOutcome.Failed("连接时内部异常（${ex.javaClass.simpleName}）：配置已保存，可稍后重试。")
        }
        return when (outcome) {
            is McpConnectOutcome.Ok -> {
                popupLedger.forget(id) // 授权闭环 ⇒ 本 episode 结束（过期重授还能再弹）
                ToolOutcome.Ok(
                    "$head：传输 ${outcome.mode ?: "未知"}，" +
                        (if (outcome.removed > 0) "移除 ${outcome.removed} 个工具、" else "") +
                        "新增 ${outcome.added.coerceAtLeast(0)} 个工具（现共 ${outcome.toolCount} 个）。" +
                        "新工具首次调用还要用户在确认卡上批准。",
                )
            }
            is McpConnectOutcome.NeedsAuth -> needsAuthOutcome(e, id, outcome)
            is McpConnectOutcome.Failed ->
                ToolOutcome.Failed("$head 连接失败：${outcome.message}（配置与自动连名单已保留，会机会式重试）")
        }
    }

    /** 自动 B：发现需授权 ⇒ 自动开授权页，但**只弹一次**；被拒过/弹过就只报话不弹。 */
    private fun needsAuthOutcome(e: Env, id: String, outcome: McpConnectOutcome.NeedsAuth): ToolOutcome {
        val verdict = popupLedger.verdict(id)
        return when {
            verdict == McpAuthPopupLedger.State.DENIED -> ToolOutcome.Failed(
                "需要授权，但这台服务器的授权**被拒绝过**：按约不再自动弹授权页。" +
                    "请用户到 MCP 面板手动「重新授权」，或换一台服务器。",
            )
            verdict == McpAuthPopupLedger.State.SHOWN -> ToolOutcome.Ok(
                "需要授权：授权页此前已打开过一次（按约不重复弹）。" +
                    "用户在浏览器完成后会自动续连并刷新工具清单；完成后用 mcp_list 查看结果。",
            )
            e.browser(outcome.authorizationUrl) -> {
                popupLedger.markShown(id)
                e.log("[mcp] server=$id 自配通道自动弹授权页（本 episode 一次）")
                ToolOutcome.Ok(
                    "需要授权：已自动打开授权页（**只弹这一次**）。用户完成后自动续连；" +
                        "若在浏览器里拒绝，本通道不会再弹，需到 MCP 面板手动处理。完成后用 mcp_list 查看结果。",
                )
            }
            else -> ToolOutcome.Failed("需要授权，但打不开浏览器：请用户到 MCP 面板手动授权。")
        }
    }

    /** 造配置：URL 许可与自动命名同 [McpServerDraft]；显式给名则覆盖（同口径清洗）。 */
    private fun draftFrom(urlRaw: String, nameRaw: String): McpServerConfig? {
        val draft = McpServerDraft.fromUrl(urlRaw.trim())
        val base = (draft as? McpServerDraft.Result.Ok)?.config ?: return null
        val name = nameRaw.trim()
        if (name.isEmpty()) return base.copy(autoConnect = true)
        val flat = McpText.flat(name)
        if (flat.isEmpty()) return base.copy(autoConnect = true)
        return try {
            base.copy(name = McpText.clip(flat, 40), autoConnect = true)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private const val NOT_WIRED = "MCP 自配工具还没装配运行环境（内部错误）"
}

/**
 * 自动 B 的弹窗台账（进程内）。
 *
 * 「只弹一次」的记账单位是**授权 episode**：弹过（SHOWN）就不再弹，直到这次授权闭环
 * （[forget]——续连成功/后台换到 token）；被浏览器明确拒绝（DENIED）后**永不再弹**，
 * 只剩面板手动一条路。面板手动授权不经过这里（那是用户显式动作，本来就该开）。
 */
internal class McpAuthPopupLedger {

    internal enum class State { NONE, SHOWN, DENIED }

    private val shown = HashSet<String>()
    private val denied = HashSet<String>()

    @Synchronized
    internal fun verdict(id: String): State = when {
        id in denied -> State.DENIED
        id in shown -> State.SHOWN
        else -> State.NONE
    }

    @Synchronized
    internal fun markShown(id: String) {
        shown += id
    }

    @Synchronized
    internal fun markDenied(id: String) {
        denied += id
    }

    /** 授权闭环（续连成功/后台换到 token）：episode 结束，允许下一次过期重授再弹。 */
    @Synchronized
    internal fun forget(id: String) {
        shown -= id
        denied -= id
    }

    /** 单测用：整本清空（生产不调）。 */
    @Synchronized
    internal fun forgetAllForTest() {
        shown.clear()
        denied.clear()
    }
}
