// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百二十三批 · MCP 工具接入 v1：把外部 server 的 tools/list 桥进 ToolRegistry。
// 三条红线在这里落地：不自动连接、不自动执行、执行器自己再查一次授权。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolSpec
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 面板上一行（第一百七十批 B；第二百一十六批补授权列）。
 *
 * 只带 UI 需要的字段，配置本体与 token **一律不外泄**：没有 headers、没有 token、
 * 没有 code/state。UI 想显示"授权状态"只能拿 [auth]（枚举），拿不到凭据。
 */
internal data class McpServerView(
    val id: String,
    val name: String,
    /** 已脱敏的地址（query/fragment 去掉），可以直接显示。 */
    val displayUrl: String,
    /** 主机名（列表里"主机"那一列；也是用户认人的主要线索）。 */
    val host: String,
    val status: McpServerStatus,
    /** 授权状态（未授权/有效/过期/被撤销/不支持）。 */
    val auth: McpAuthStatus,
    val toolCount: Int,
    /** 最后一次连接成功的时刻（毫秒）；null = 本次进程还没连过。 */
    val lastConnectedAtMs: Long?,
    /** 第五百零二批：在不在启动自动连名单上（配置级布尔；与连接状态无关）。 */
    val autoConnect: Boolean = false,
)

/**
 * 外部 MCP server 的**进程内**管理器（第一百七十批 B 起配置落盘）。
 *
 * 生命周期：
 * 1. [addServer] 只存配置（并写盘）——**不发网络请求、不注册工具**；
 * 2. [connect] 是唯一联网入口：initialize → tools/list → 注册为 `ToolGroup.MCP`；
 * 3. 工具注册即 `CONFIRM`；用户批准后，只读工具转 `FREE`，危险工具**每次都再确认**；
 * 4. [disconnect] 注销工具、清授权；[attach] 只把已连接的工具搬到新注册表（不联网）；
 * 5. [restoreFromStore] 进程重启后把盘里的配置登记回来——**仍然不联网**。
 *
 * 线类的外部工具一律 `readOnly=false`、`parallelSafe=false`：它们不改本地盘，但会改
 * 远端状态，v1 不把它们塞进并行段（设计 §4）。
 */
internal class McpToolSource(
    registry: ToolRegistry,
    private val grants: McpGrantStore = InMemoryMcpGrants(),
    /**
     * 授权流程（null = 不做 OAuth，单测与"无鉴权服务器"用）。
     *
     * 生产必须传：不传就等于把 216 的授权接回"贴静态头"那条死路。
     */
    private val auth: McpAuthFlow? = null,
    /** token 供给（按 server 绑定）；默认从 [auth] 取。 */
    private val tokenProviderFactory: (McpServerConfig) -> McpTokenProvider? = { auth?.providerFor(it) },
    private val clientFactory: (McpServerConfig) -> McpSession = {
        McpClient(it, tokenProvider = tokenProviderFactory(it))
    },
    private val log: (String) -> Unit = {},
    /** null = 不落盘（单测与「配置只在内存」的旧调用点）。 */
    private val store: McpServerStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private class ServerState(
        var config: McpServerConfig,
        var client: McpSession? = null,
        var tools: List<McpConvertedTool> = emptyList(),
        var status: McpServerStatus = McpServerStatus.OFFLINE,
        var lastConnectedAtMs: Long? = null,
        /** 最近一次失败的结论（UI 上"失败可读"；不含 token/URL）。 */
        var lastError: String? = null,
    ) {
        val wireNames: Set<String> get() = tools.map { it.wireName }.toSet()
    }

    private val lock = Any()
    private val servers = LinkedHashMap<String, ServerState>()

    @Volatile
    private var registry: ToolRegistry = registry

    /** 换注册表（换通道会重建引擎/注册表）：只搬已连接的工具，**不联网、不重连**。 */
    fun attach(newRegistry: ToolRegistry) {
        synchronized(lock) {
            if (registry === newRegistry) return
            registry = newRegistry
            servers.values.forEach { state ->
                if (state.client == null) return@forEach
                state.tools.forEach { newRegistry.register(specFor(state, it)) }
                if (ToolGroup.MCP !in newRegistry.disabledGroups()) {
                    newRegistry.setGroupApproved(ToolGroup.MCP, true)
                }
            }
        }
    }

    /**
     * 登记配置（存内存 + 写盘）。
     *
     * @return false = 同 id 已存在（不覆盖，避免把正在用的连接换掉）
     */
    fun addServer(config: McpServerConfig): Boolean = synchronized(lock) {
        if (servers.containsKey(config.id)) return false
        servers[config.id] = ServerState(config)
        log("[mcp] 登记 server=${config.id}（未连接）")
        persistLocked()
        true
    }

    /**
     * 把上次退出时存下的配置登记回来（**只登记、不联网**）。
     *
     * 幂等：已存在的 id 跳过；条数受 [McpServerStore.MAX_SERVERS] 限制。
     * 不回写盘（这些配置本来就是从盘里读的）。
     *
     * @return 新登记条数
     */
    fun restoreFromStore(): Int = synchronized(lock) {
        val store = store ?: return 0
        var added = 0
        store.load().forEach { config ->
            if (servers.containsKey(config.id)) return@forEach
            if (servers.size >= McpServerStore.MAX_SERVERS) return@forEach
            servers[config.id] = ServerState(config)
            added++
        }
        if (added > 0) log("[mcp] 从本地恢复 $added 个 server 配置（均未连接）")
        added
    }

    /** 移除 server（含断连、注销工具、清授权、删盘上的记录）。 */
    fun removeServer(id: String): Boolean = synchronized(lock) {
        val state = servers[id] ?: return false
        releaseLocked(state)
        // 删除即清：token、撤销标记、授权会话（client_id）一起删，不留孤儿。
        auth?.wipe(id)
        auth?.clearSession(id)
        servers.remove(id)
        persistLocked()
        log("[mcp] 移除 server=$id（token 与会话已清）")
        true
    }

    /**
     * 连接（**唯一会联网的入口**；只能在用户显式动作后调用）。
     *
     * 失败时：不注册任何工具、清掉本次客户端、状态标 [McpServerStatus.ERROR]；
     * 读原文不含 URL/请求头/响应体。
     *
     * 第五百零二批：对**已连接**的 server 不再短路返回——重拉 `tools/list` 按差集增删
     * （自动⑤「连上后自动刷新工具清单」）；刷新失败保留旧清单，不半拆。
     */
    fun connect(id: String): McpConnectOutcome = synchronized(lock) {
        val state = servers[id]
            ?: return McpConnectOutcome.Failed("没有这个 MCP server（$id）")
        if (state.client != null) return refreshConnectedLocked(state)

        // 216：没有可用 token 就先走授权——**不发匿名请求**（省一次必然 401 的往返，
        // 也让"点连接"这件事在 UI 上直接变成"去授权"，不会先报一次错再让用户点第二次）。
        auth?.let { flow ->
            if (!flow.hasValidToken(state.config)) {
                return when (val begin = flow.begin(state.config)) {
                    is McpAuthBegin.Ready -> McpConnectOutcome.NeedsAuth(
                        begin.authorizationUrl,
                        "「${state.config.name}」需要授权：已打开授权页，回来会自动连上",
                    )
                    is McpAuthBegin.Failed -> {
                        state.status = McpServerStatus.ERROR
                        state.lastError = begin.message
                        McpConnectOutcome.Failed(begin.message)
                    }
                }
            }
        }

        val handshake = handshakeLocked(state)
        if (handshake is Handshake.NeedsAuth) return handshake.outcome
        if (handshake is Handshake.Fail) return McpConnectOutcome.Failed(handshake.message, handshake.failure)
        finishConnectLocked(state, handshake as Handshake.Done)
    }

    /** 握手的一次结果（[connect] 专用；刷新路径不重新握手）。 */
    private sealed interface Handshake {
        /** 已握上的 client（尚未登记进 state）＋ 原始描述符（尚未做名字避让转换）。 */
        data class Done(val client: McpSession, val descriptors: List<McpToolDescriptor>) : Handshake
        data class NeedsAuth(val outcome: McpConnectOutcome.NeedsAuth) : Handshake

        /** 606：带机器分类（[failure]；null = 无从归类，如「没有这个 id」）。 */
        data class Fail(val message: String, val failure: McpFailure? = null) : Handshake
    }

    /** 建 client → initialize → tools/list。失败分支在这里收口（状态置 ERROR、关 client）。 */
    private fun handshakeLocked(state: ServerState): Handshake {
        val client = try {
            clientFactory(state.config)
        } catch (e: Exception) {
            state.status = McpServerStatus.ERROR
            // 606：配置构造不过检（URL/头/超时）是本机 CONFIG，不是无名异常。
            return Handshake.Fail(
                "MCP 服务器「${state.config.name}」配置不可用（${e.javaClass.simpleName}）",
                McpFailure.CONFIG,
            )
        }
        val descriptors = try {
            client.connect()
            client.listTools()
        } catch (e: McpException) {
            runCatching { client.close() }
            state.status = McpServerStatus.ERROR
            state.lastError = e.userMessage
            log("[mcp] server=${state.config.id} 连接失败：${e.failure}")
            // 401 / 权限不足 ⇒ 转授权流程（正常一步，不是"失败"）。
            auth?.let { flow ->
                if (e.failure == McpFailure.AUTH_REQUIRED || e.failure == McpFailure.INSUFFICIENT_SCOPE) {
                    val begin = flow.begin(state.config, scope = e.missingScope)
                    if (begin is McpAuthBegin.Ready) {
                        return Handshake.NeedsAuth(
                            McpConnectOutcome.NeedsAuth(
                                authorizationUrl = begin.authorizationUrl,
                                message = e.userMessage,
                                missingScope = e.missingScope,
                            ),
                        )
                    }
                }
            }
            return Handshake.Fail(e.userMessage, e.failure)
        } catch (e: Exception) {
            runCatching { client.close() }
            state.status = McpServerStatus.ERROR
            state.lastError = "MCP 服务器「${state.config.name}」连接失败（${e.javaClass.simpleName}）"
            return Handshake.Fail(state.lastError!!)
        }
        return Handshake.Done(client, descriptors)
    }

    /** 名字避让规则统一在这（新连与刷新同口径）：本 server 旧名字不算撞名，本地工具名不行。 */
    private fun convertedForLocked(
        state: ServerState,
        descriptors: List<McpToolDescriptor>,
    ): List<McpConvertedTool> {
        val existing = registry.names().toSet() - state.wireNames
        val mcpOwned = servers.values
            .filter { it !== state }
            .flatMap { it.wireNames }
            .toSet()
        return McpSchema.convertAll(descriptors, existing, mcpOwned)
    }

    /** 握手成功后的收尾：转换 → 登记 → 状态与组同意。 */
    private fun finishConnectLocked(state: ServerState, done: Handshake.Done): McpConnectOutcome {
        val converted = try {
            convertedForLocked(state, done.descriptors)
        } catch (e: McpException) {
            runCatching { done.client.close() }
            state.status = McpServerStatus.ERROR
            return McpConnectOutcome.Failed(e.userMessage)
        }
        state.client = done.client
        val mode = done.client.mode
        state.tools = converted
        state.status = McpServerStatus.CONNECTED
        state.lastConnectedAtMs = now()
        state.lastError = null
        converted.forEach { registry.register(specFor(state, it)) }

        val warnings = ArrayList<String>(2)
        if (ToolGroup.MCP in registry.disabledGroups()) {
            warnings += "工具组「外部工具（MCP）」已被你在设置里关闭：连接成功，但工具不会出现在模型面前"
        } else {
            // 用户点「连接」就是显式同意这一组；设置页关掉时绝不覆盖（上面的分支）。
            registry.setGroupApproved(ToolGroup.MCP, true)
        }
        if (converted.isEmpty()) warnings += "服务器没有公布任何工具"
        log("[mcp] server=${state.config.id} 已连接：注册 ${converted.size} 个工具（${mode ?: "?"}）")
        return McpConnectOutcome.Ok(
            toolCount = converted.size,
            warnings = warnings,
            mode = mode,
            added = converted.size,
            removed = 0,
        )
    }

    /**
     * 第五百零二批：对已连接的 server 重拉 `tools/list`，按差集增删注册。
     *
     * 刷新失败**保留旧清单**（半拆比旧清单更糟）；成功后 `lastError` 清零、状态保持连接。
     */
    private fun refreshConnectedLocked(state: ServerState): McpConnectOutcome {
        val client = state.client
            ?: return McpConnectOutcome.Failed("没有这个 MCP server（${state.config.id}）")
        val descriptors = try {
            client.listTools()
        } catch (e: McpException) {
            log("[mcp] server=${state.config.id} 工具清单刷新失败：${e.failure}（保留旧清单 ${state.tools.size} 个）")
            // 606：失败带分类（602 落点：刷新失败不再无机键）。
            return McpConnectOutcome.Failed(e.userMessage, e.failure)
        } catch (e: Exception) {
            log("[mcp] server=${state.config.id} 工具清单刷新失败（${e.javaClass.simpleName}）（保留旧清单 ${state.tools.size} 个）")
            return McpConnectOutcome.Failed("MCP 服务器「${state.config.name}」刷新失败（${e.javaClass.simpleName}）")
        }
        val converted = try {
            convertedForLocked(state, descriptors)
        } catch (e: McpException) {
            log("[mcp] server=${state.config.id} 刷新到的工具形状不合法：${e.failure}（保留旧清单）")
            return McpConnectOutcome.Failed(e.userMessage, e.failure)
        }
        val before = state.wireNames
        val after = converted.map { it.wireName }.toSet()
        val gone = before - after
        val added = (after - before).size
        gone.forEach { registry.unregister(it) }
        state.tools = converted
        state.status = McpServerStatus.CONNECTED
        state.lastError = null
        converted.forEach { registry.register(specFor(state, it)) }
        log(
            "[mcp] server=${state.config.id} 工具清单已刷新：新增 $added 个、移除 ${gone.size} 个" +
                "（现 ${converted.size} 个，${client.mode}）",
        )
        return McpConnectOutcome.Ok(
            toolCount = converted.size,
            mode = client.mode,
            added = added,
            removed = gone.size,
        )
    }

    /**
     * 断开：注销工具、清工具授权、**清 token**（派工单 §2.5「断开＝清本机 token」）、关传输。
     * 配置留着，可重新授权再连。
     *
     * 第五百零二批：显式断开同时把 `autoConnect` 关掉——用户收起来的服务器不许在
     * 下次启动时自己爬回来（自动 A 只连「还在名单上」的）。
     */
    fun disconnect(id: String) {
        synchronized(lock) {
            val state = servers[id] ?: return
            releaseLocked(state)
            auth?.wipe(id)
            if (state.config.autoConnect) {
                state.config = state.config.copy(autoConnect = false)
                persistLocked()
            }
            state.status = McpServerStatus.OFFLINE
            state.lastError = null
            log("[mcp] server=$id 已断开（token 已清；启动自动连已关）")
        }
    }

    /** 「全部断开」：清所有服务器的 token（不删配置、不断开之外的任何东西）。 */
    fun disconnectAll() {
        synchronized(lock) {
            servers.values.forEach { state ->
                releaseLocked(state)
                state.status = McpServerStatus.OFFLINE
                state.lastError = null
            }
            auth?.wipeAll()
            log("[mcp] 已全部断开（${servers.size} 台服务器的 token 已清）")
        }
    }

    fun status(id: String): McpServerStatus? = synchronized(lock) { servers[id]?.status }

    /** 面板用的快照（按登记顺序），UI 只读这份、不碰 State。 */
    fun snapshot(): List<McpServerView> = synchronized(lock) {
        servers.values.map { state ->
            McpServerView(
                id = state.config.id,
                name = state.config.name,
                displayUrl = McpUrlRules.redact(state.config.url),
                host = hostOf(state.config.url),
                status = state.status,
                auth = authStatusOf(state.config),
                toolCount = state.tools.size,
                lastConnectedAtMs = state.lastConnectedAtMs,
                autoConnect = state.config.autoConnect,
            )
        }
    }

    /**
     * 第二百四十五批 · B3「测试连接」：只探**地址**，不碰会话与凭据。
     *
     * 与 [connect] 的区别是结构性的：不建会话、不取/不存 token、不注册工具。
     * 地址在锁内取一份副本，网络请求在锁外跑（不许拿着锁做 IO）。
     *
     * @return null ＝ 清单里没有这个 id
     */
    fun probe(id: String, http: OkHttpClient = McpOAuthClient.defaultHttp()): McpProbeResult? {
        val url = synchronized(lock) { servers[id]?.config?.url } ?: return null
        return McpConnectProbe.probe(url, http)
    }

    /** 最近一次失败的原因（"失败要可读"）；null = 没有失败记录。 */
    fun lastError(id: String): String? = synchronized(lock) { servers[id]?.lastError }

    /** 「查看工具」用：已连接时它公布的工具名（只读，按注册顺序）。 */
    fun toolNames(id: String): List<String> = synchronized(lock) {
        servers[id]?.tools?.map { it.remoteName }.orEmpty()
    }

    fun toolCount(id: String): Int = synchronized(lock) { servers[id]?.tools?.size ?: 0 }

    fun registeredNames(id: String): Set<String> = synchronized(lock) { servers[id]?.wireNames ?: emptySet() }

    fun serverIds(): List<String> = synchronized(lock) { servers.keys.toList() }

    /**
     * 第五百零二批：按 id 取配置副本（`mcp_set_token` / 自动连要用它找 resource 与名字）。
     * 配置里没有凭据字段（headers 恒空、token 永不在配置里），副本随便传。
     */
    fun config(id: String): McpServerConfig? = synchronized(lock) { servers[id]?.config }

    /** 只给门禁/单测看（断连后应清空）。 */
    fun grantSnapshot(): Map<String, Set<String>> = grants.snapshot()

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 把当前登记的配置写盘（**只写配置，不写连接状态/授权**）。
     *
     * 在 [lock] 内调用：条数上限 16、整份覆盖，代价是一次 SP 写，不会拖慢连接路径
     * （连接本身也在同一把锁内串行，UI 侧全部在 IO 线程上点）。
     */
    /** 授权状态：没有 auth 流程时一律 UNAUTHORIZED（单测/无鉴权服务器的口径）。 */
    private fun authStatusOf(config: McpServerConfig): McpAuthStatus =
        auth?.status(config) ?: McpAuthStatus.UNAUTHORIZED

    /** 主机名（只用于显示；解析失败给空串，不抛）。 */
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun persistLocked() {
        val store = store ?: return
        store.save(servers.values.map { it.config })
    }

    private fun releaseLocked(state: ServerState) {
        state.wireNames.forEach { registry.unregister(it) }
        runCatching { state.client?.close() }
        state.client = null
        state.tools = emptyList()
        grants.revokeServer(state.config.id)
    }

    /**
     * 一个外部工具的当前形态。
     *
     * - 授权表里已有该工具且它是只读 ⇒ `FREE`（批准过一次就不再烦用户）；
     * - 其余一律 `CONFIRM`（危险工具每次调用都会再出一次确认卡）；
     * - 执行器闭包**不信任**权限级：开跑前再查一次授权（防绕过 Guard 直调）。
     */
    private fun specFor(state: ServerState, tool: McpConvertedTool): ToolSpec {
        val serverId = state.config.id
        val granted = grants.isGranted(serverId, tool.remoteName)
        return ToolSpec(
            name = tool.wireName,
            description = tool.description,
            inputSchema = tool.inputSchema,
            permission = if (tool.readOnly && granted) ToolPermission.FREE else ToolPermission.CONFIRM,
            timeoutMs = state.config.callTimeoutMs + 5_000L,
            group = ToolGroup.MCP,
            // 线类工具不改本地盘、也不许并行（远端状态可能不是线程安全的）。
            readOnly = false,
            parallelSafe = false,
            onUserApproved = { approve(serverId, tool.remoteName) },
            executor = { args -> invoke(serverId, tool.remoteName, args) },
        )
    }

    /**
     * 用户在现有确认卡上点了批准 ⇒ 记授权；只读工具立刻重注册为 `FREE`。
     *
     * 若这期间 server 已断开：**不记**（避免旧卡批准后，重连时白拿一个免确认）。
     * 由 `GuardPipeline.executeApproved` 调用；其它路径不许调。
     */
    private fun approve(serverId: String, remoteName: String) {
        synchronized(lock) {
            val state = servers[serverId] ?: return
            if (state.client == null) return
            grants.grant(serverId, remoteName)
            val tool = state.tools.firstOrNull { it.remoteName == remoteName } ?: return
            if (tool.readOnly) {
                // 仍是同一张注册表；断连/换表时 attach 会重新盖章。
                registry.register(specFor(state, tool))
            }
        }
    }

    /**
     * 执行器（Guard ④ 段内）：先查授权，再发请求。
     *
     * 授权缺失 ⇒ [ToolOutcome.Rejected]（这不是可重试错误，是绕过 Guard 直调）；
     * 网络失败/服务器 `isError` ⇒ [ToolOutcome.Failed]（原文回灌，可重试一次）。
     */
    private fun invoke(serverId: String, remoteName: String, args: JSONObject): ToolOutcome {
        var reject: String? = null
        val client = synchronized(lock) {
            val state = servers[serverId]
            val active = state?.client
            when {
                active == null -> {
                    reject = "MCP 服务器「${state?.config?.name ?: serverId}」已断开：本次不发请求，请让用户重新连接"
                    null
                }
                !grants.isGranted(serverId, remoteName) -> {
                    reject = "外部工具「$remoteName」还没有得到你的授权：请先在确认卡上批准；" +
                        "本工具不会在没有授权的情况下发网络请求"
                    null
                }
                else -> active
            }
        }
        if (client == null) return ToolOutcome.Rejected(reject ?: "外部工具不可用")
        return try {
            val result = client.callTool(remoteName, args)
            if (result.isError) ToolOutcome.Failed(result.text) else ToolOutcome.Ok(result.text)
        } catch (e: McpException) {
            ToolOutcome.Failed(e.userMessage)
        } catch (e: Exception) {
            ToolOutcome.Failed("外部工具「$remoteName」调用崩溃（${e.javaClass.simpleName}）")
        }
    }
}
