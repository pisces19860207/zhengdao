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
import org.json.JSONObject

/** 面板上一行（第一百七十批 B）：只带 UI 需要的字段，配置本体不外泄。 */
internal data class McpServerView(
    val id: String,
    val name: String,
    /** 已脱敏的地址（query/fragment 去掉），可以直接显示。 */
    val displayUrl: String,
    val status: McpServerStatus,
    val toolCount: Int,
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
    private val clientFactory: (McpServerConfig) -> McpSession = { McpClient(it) },
    private val log: (String) -> Unit = {},
    /** null = 不落盘（单测与「配置只在内存」的旧调用点）。 */
    private val store: McpServerStore? = null,
) {

    private class ServerState(
        val config: McpServerConfig,
        var client: McpSession? = null,
        var tools: List<McpConvertedTool> = emptyList(),
        var status: McpServerStatus = McpServerStatus.OFFLINE,
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
        servers.remove(id)
        persistLocked()
        log("[mcp] 移除 server=$id")
        true
    }

    /**
     * 连接（**唯一会联网的入口**；只能在用户显式动作后调用）。
     *
     * 失败时：不注册任何工具、清掉本次客户端、状态标 [McpServerStatus.ERROR]；
     * 读原文不含 URL/请求头/响应体。
     */
    fun connect(id: String): McpConnectOutcome = synchronized(lock) {
        val state = servers[id]
            ?: return McpConnectOutcome.Failed("没有这个 MCP server（$id）")
        state.client?.let { return McpConnectOutcome.Ok(state.tools.size) }

        val client = try {
            clientFactory(state.config)
        } catch (e: Exception) {
            state.status = McpServerStatus.ERROR
            return McpConnectOutcome.Failed("MCP 服务器「${state.config.name}」配置不可用（${e.javaClass.simpleName}）")
        }

        val descriptors = try {
            client.connect()
            client.listTools()
        } catch (e: McpException) {
            runCatching { client.close() }
            state.status = McpServerStatus.ERROR
            log("[mcp] server=$id 连接失败：${e.failure}")
            return McpConnectOutcome.Failed(e.userMessage)
        } catch (e: Exception) {
            runCatching { client.close() }
            state.status = McpServerStatus.ERROR
            return McpConnectOutcome.Failed(
                "MCP 服务器「${state.config.name}」连接失败（${e.javaClass.simpleName}）",
            )
        }

        val converted = try {
            // 本 server 上一次注册的名字不算撞名（重连是自己的位子）；其它 MCP server 的
            // 名字可以哈希避让，本地工具名不行（McpSchema 会整 server 拒收）。
            val existing = registry.names().toSet() - state.wireNames
            val mcpOwned = servers.values
                .filter { it !== state }
                .flatMap { it.wireNames }
                .toSet()
            McpSchema.convertAll(descriptors, existing, mcpOwned)
        } catch (e: McpException) {
            runCatching { client.close() }
            state.status = McpServerStatus.ERROR
            return McpConnectOutcome.Failed(e.userMessage)
        }

        state.client = client
        state.tools = converted
        state.status = McpServerStatus.CONNECTED
        converted.forEach { registry.register(specFor(state, it)) }

        val warnings = ArrayList<String>(2)
        if (ToolGroup.MCP in registry.disabledGroups()) {
            warnings += "工具组「外部工具（MCP）」已被你在设置里关闭：连接成功，但工具不会出现在模型面前"
        } else {
            // 用户点「连接」就是显式同意这一组；设置页关掉时绝不覆盖（上面的分支）。
            registry.setGroupApproved(ToolGroup.MCP, true)
        }
        if (converted.isEmpty()) warnings += "服务器没有公布任何工具"
        log("[mcp] server=$id 已连接：注册 ${converted.size} 个工具（${client.mode}）")
        McpConnectOutcome.Ok(converted.size, warnings)
    }

    /** 断连：注销本 server 的全部工具、清授权、关传输；配置留着可重连。 */
    fun disconnect(id: String) {
        synchronized(lock) {
            val state = servers[id] ?: return
            releaseLocked(state)
            state.status = McpServerStatus.OFFLINE
            log("[mcp] server=$id 已断开")
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
                status = state.status,
                toolCount = state.tools.size,
            )
        }
    }

    fun toolCount(id: String): Int = synchronized(lock) { servers[id]?.tools?.size ?: 0 }

    fun registeredNames(id: String): Set<String> = synchronized(lock) { servers[id]?.wireNames ?: emptySet() }

    fun serverIds(): List<String> = synchronized(lock) { servers.keys.toList() }

    /** 只给门禁/单测看（断连后应清空）。 */
    fun grantSnapshot(): Map<String, Set<String>> = grants.snapshot()

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 把当前登记的配置写盘（**只写配置，不写连接状态/授权**）。
     *
     * 在 [lock] 内调用：条数上限 16、整份覆盖，代价是一次 SP 写，不会拖慢连接路径
     * （连接本身也在同一把锁内串行，UI 侧全部在 IO 线程上点）。
     */
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
