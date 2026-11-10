// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**总闸**（进程级单例，房型照 HaltGate/Scout 同一条）。
//
// 职责只有三件：
//   1. **装配**（install）：接 Context、凭据库、传输层、配置仓——全是"把零件递进来"，
//      零网络零阻塞（主线程只碰 SP 与线程池提交，判据⑥的结构保证）；
//   2. **期望对齐**（sync）： desired = 开关开着 ∧ 没停机(471)。真实状态永远向期望收敛：
//      该跑没跑 ⇒ 起；停机了 ⇒ 关。1 秒一次的看门狗（zd-mcp-halt-watch 线程）保证
//      停机后**端口在秒级让出来**（判据④），解除停机后自动复活；
//   3. **读数**（status）：给设置页/取证用的纯读快照——不碰套接字。
//
// 判定不在这里：鉴权/白名单/租约/审计全在 [McpServerCore]，本类不重复一遍。
package com.example.zhengdao.baopu.mcp.server

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

object McpServerController {

    /** 默认只绑本机回环（判据⑤）；0.0.0.0 是显式开的可选档，公网档不存在。 */
    const val HOST_LOOPBACK = "127.0.0.1"
    const val HOST_LAN = "0.0.0.0"
    const val PORT = 8765

    /** 一次状态快照（纯读；UI 与取证用）。 */
    data class Status(
        val enabled: Boolean,
        val bindAll: Boolean,
        val running: Boolean,
        val endpoint: String?,
        val halted: Boolean,
        val haltLine: String?,
        val tokenReady: Boolean,
        val tokenFingerprint: String?,
        val whitelistCount: Int,
    )

    /** 配置仓（可注入：测试用内存件）。 */
    interface ConfigStore {
        fun enabled(): Boolean
        fun setEnabled(on: Boolean)
        fun bindAll(): Boolean
        fun setBindAll(on: Boolean)
    }

    /** SP 实现（一个用途一个文件，照 zhengdao-safety 同一条规矩）。 */
    class PrefsConfig(ctx: Context) : ConfigStore {
        private val prefs = ctx.applicationContext
            .getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)

        override fun enabled(): Boolean = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

        override fun setEnabled(on: Boolean) {
            runCatching { prefs.edit { putBoolean(KEY_ENABLED, on) } }
        }

        override fun bindAll(): Boolean = runCatching { prefs.getBoolean(KEY_BIND_ALL, false) }.getOrDefault(false)

        override fun setBindAll(on: Boolean) {
            runCatching { prefs.edit { putBoolean(KEY_BIND_ALL, on) } }
        }

        companion object {
            const val SP_FILE = "zhengdao-mcp-server"
            const val KEY_ENABLED = "server_enabled"
            const val KEY_BIND_ALL = "bind_all"
        }
    }

    /** 内存实现（单测）。 */
    class MemoryConfig(enabled: Boolean = false, bindAll: Boolean = false) : ConfigStore {
        @Volatile
        private var on = enabled

        @Volatile
        private var all = bindAll

        override fun enabled(): Boolean = on

        override fun setEnabled(on: Boolean) {
            this.on = on
        }

        override fun bindAll(): Boolean = all

        override fun setBindAll(on: Boolean) {
            this.all = on
        }
    }

    // ── 装配态（install/attach 写；其余只读）──────────────────────────────

    @Volatile
    private var config: ConfigStore = MemoryConfig()

    @Volatile
    private var vault: McpVault = InMemoryMcpVault()

    @Volatile
    private var transport: McpServerTransport = LoopbackHttpTransport { RunLog.log(it) }

    @Volatile
    private var runner: McpToolRunner = McpToolRunner.UNAVAILABLE

    @Volatile
    private var haltProbe: () -> Boolean = { false }

    @Volatile
    private var serverVersion: String = "dev"

    /** 上次启动用的绑定档（运行中改 bind_all ⇒ 下次 sync 重启换绑）。 */
    @Volatile
    private var startedBindAll: Boolean? = null

    private val lock = Any()

    private val ctl = Executors.newSingleThreadExecutor { Thread(it, "zd-mcp-ctl").apply { isDaemon = true } }

    private var watchdog: ScheduledThreadPoolExecutor? = null

    // ── 生产装配口（ZhengdaoApp.onCreate 调一次）──────────────────────────

    /**
     * @param version 服务器Info 里的版本（调用方给 BuildConfig.VERSION_NAME——
     *   本文件不 import BuildConfig，保持可注入可测）。
     */
    fun install(ctx: Context, version: String) {
        install(
            config = PrefsConfig(ctx),
            vault = PrefsMcpVault(com.example.zhengdao.baopu.mcp.PrefsMcpSecretStore(ctx), log = { RunLog.log(it) }),
            transport = LoopbackHttpTransport { RunLog.log(it) },
            haltProbe = { com.example.zhengdao.baopu.safety.HaltGate.halted() },
            runner = McpToolRunner.UNAVAILABLE,
            version = version,
            withWatchdog = true,
        )
    }

    /** 注入口（单测/特殊装配用；install 内部走这里）。 */
    @Synchronized
    fun install(
        config: ConfigStore,
        vault: McpVault,
        transport: McpServerTransport,
        haltProbe: () -> Boolean,
        runner: McpToolRunner,
        version: String,
        withWatchdog: Boolean,
    ) {
        this.config = config
        this.vault = vault
        this.transport = transport
        this.haltProbe = haltProbe
        this.runner = runner
        this.serverVersion = version
        this.startedBindAll = null
        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to "installed", "enabled" to config.enabled().toString()))
        if (withWatchdog) {
            val w = ScheduledThreadPoolExecutor(1) { Thread(it, "zd-mcp-halt-watch").apply { isDaemon = true } }
            w.scheduleWithFixedDelay({ runCatching { sync() }.onFailure { RunLog.log("[mcp-server] sync 异常（${it.javaClass.simpleName}）") } }, 1, 1, TimeUnit.SECONDS)
            watchdog = w
        }
        submitSync()
    }

    // ── 用户/界面动作 ─────────────────────────────────────────────────────

    /** 开关（安全台）。写完立即触发一次对齐；看门狗兜底。 */
    fun setEnabled(on: Boolean) {
        config.setEnabled(on)
        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to if (on) "enabled" else "disabled"))
        submitSync()
    }

    /** 绑定档（默认 false＝只绑 127.0.0.1；true＝0.0.0.0 局域网可选档）。 */
    fun setBindAll(on: Boolean) {
        config.setBindAll(on)
        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to if (on) "bind_all_on" else "bind_all_off"))
        submitSync()
    }

    /** 换钥匙（设置页）。轮换后传输层校验即刻认新 token（同一 vault 实例）。 */
    fun rotateToken(): String = vault.rotate().also {
        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to "token_rotated"))
    }

    // ── 读数（零套接字操作）───────────────────────────────────────────────

    fun status(): Status {
        val cfgEnabled = config.enabled()
        val all = config.bindAll()
        val running = transport.isRunning()
        val halted = runCatching { haltProbe() }.getOrDefault(false)
        return Status(
            enabled = cfgEnabled,
            bindAll = all,
            running = running,
            endpoint = transport.endpoint(),
            halted = halted,
            haltLine = runCatching { com.example.zhengdao.baopu.safety.HaltGate.record()?.line }.getOrNull(),
            tokenReady = vault.currentToken() != null,
            tokenFingerprint = vault.fingerprint(),
            whitelistCount = runCatching { McpWhitelist.names().size }.getOrDefault(0),
        )
    }

    /** 设置页人工显/抄（唯一出 vault 的读口；进不了日志/账）。 */
    fun revealToken(): String? = vault.reveal()

    fun whitelistNames(): List<String> = runCatching { McpWhitelist.names() }.getOrDefault(emptyList())

    // ── 期望对齐（全部在后台线程；主线程永远不碰 ServerSocket）───────────

    private fun submitSync() {
        runCatching { ctl.execute { sync() } }
    }

    /** 测试同步口：把 ctl 队列排干（sync 全部跑完才返回）；生产不调。 */
    internal fun flushSyncForTest() {
        runCatching { ctl.submit {}.get() }
    }

    private fun sync() {
        synchronized(lock) {
            val halted = runCatching { haltProbe() }.getOrDefault(false)
            val desired = config.enabled() && !halted
            val running = transport.isRunning()
            val bindAll = config.bindAll()
            val needsRebind = running && startedBindAll != null && startedBindAll != bindAll
            when {
                // 停机/关开关 ⇒ 收摊（判据④：端口让出来；审计一行写明为什么停）。
                !desired && running -> {
                    transport.stop()
                    startedBindAll = null
                    McpAuditLedger.append(
                        McpAuditLedger.EV_SERVER,
                        mapOf("detail" to if (halted) "halted_stop" else "disabled_stop"),
                    )
                }
                // 该跑没跑（或换了绑定档）⇒ 起。
                desired && (!running || needsRebind) -> {
                    if (running) transport.stop()
                    if (vault.currentToken() == null) {
                        vault.ensureToken()
                        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to "token_created"))
                    }
                    val host = if (bindAll) HOST_LAN else HOST_LOOPBACK
                    val core = McpServerCore(vault, runner, haltProbe, serverVersion)
                    val ok = transport.start(McpServerTransport.Config(host, PORT), core::handle)
                    startedBindAll = if (ok) bindAll else null
                    McpAuditLedger.append(
                        McpAuditLedger.EV_SERVER,
                        mapOf(
                            "detail" to if (ok) "started" else "start_failed",
                            "bind" to if (bindAll) HOST_LAN else HOST_LOOPBACK,
                        ),
                    )
                }
            }
        }
    }
}
