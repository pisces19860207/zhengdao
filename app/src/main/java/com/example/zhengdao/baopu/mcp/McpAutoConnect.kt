// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零二批 · 自动 A「启动自动连」：启动（延迟 3s）与回前台各给一次机会式尝试——
// 只连 `autoConnect=true` 且未连接的；失败**不阻断**（逐台 try/catch）、每台只落**一行**诊断，
// 按指数退避（60s 起、30min 封顶）排下次机会。判据"一台坏的只影响它自己"由逐台隔离保证。
//
// 三条边界（与 McpToolSource 的旧红线对齐后的新口径）：
// 1. 后台（启动/回前台）**绝不弹授权页**——NeedsAuth 只落一行诊断并停排，等面板/对话授权；
// 2. 显式断开过的服务器不在名单上（disconnect 已把 autoConnect 关掉）；
// 3. 重试是"机会式"的：没有常驻定时器，只有启动/回前台两个触发点。
package com.example.zhengdao.baopu.mcp

/** 机会式自动连（进程级；只被 McpSelfServiceWiring 在后台线程调用）。 */
internal object McpAutoConnect {

    private const val BASE_BACKOFF_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30 * 60_000L
    private const val PARKED = Long.MAX_VALUE

    private val lock = Any()

    /** serverId → 最早下次尝试时刻（[PARKED] = 停排：等授权，不是失败）。 */
    private val nextTryAtMs = HashMap<String, Long>()

    /** serverId → 连续失败次数（退避用；成功/停排清零）。 */
    private val failures = HashMap<String, Int>()

    /**
     * 一轮机会式尝试。幂等：已连接/名单外/没到点的都会被滤掉，空轮零日志。
     *
     * @param reason 触发原因（"启动"/"回前台"）——只进诊断行，帮验收对账
     */
    internal fun pass(reason: String) {
        val e = McpSelfService.env ?: return
        val source = e.source()
        source.restoreFromStore()
        val nowMs = e.now()
        val targets = synchronized(lock) {
            source.serverIds().filter { id ->
                val cfg = source.config(id)
                cfg?.autoConnect == true &&
                    source.status(id) != McpServerStatus.CONNECTED &&
                    nowMs >= (nextTryAtMs[id] ?: 0L)
            }
        }
        if (targets.isEmpty()) return
        e.log("[mcp] 自动连($reason)：试 ${targets.size} 台")
        targets.forEach { id -> tryOne(e, id, reason) }
    }

    /** 授权闭环（后台换到 token 并续连成功）后，把这台从"停排"里捞回来。 */
    internal fun clearRetry(id: String) {
        synchronized(lock) {
            nextTryAtMs.remove(id)
            failures.remove(id)
        }
    }

    /** 单测用：把台账清空（生产不调）。 */
    internal fun resetForTest() {
        synchronized(lock) {
            nextTryAtMs.clear()
            failures.clear()
        }
    }

    /** 逐台隔离的一台：成败都只落一行。 */
    private fun tryOne(e: McpSelfService.Env, id: String, reason: String) {
        val outcome = try {
            e.source().connect(id)
        } catch (ex: Exception) {
            scheduleRetry(e, id)
            e.log("[mcp] 自动连($reason) server=$id 异常（${ex.javaClass.simpleName}）：已排机会式重试")
            return
        }
        when (outcome) {
            is McpConnectOutcome.Ok -> {
                clearRetry(id)
                e.log(
                    "[mcp] 自动连($reason) server=$id 成功：" +
                        "新增 ${outcome.added.coerceAtLeast(0)} 个工具（${outcome.mode ?: "?"}）",
                )
            }
            is McpConnectOutcome.NeedsAuth -> {
                synchronized(lock) { nextTryAtMs[id] = PARKED }
                e.log("[mcp] 自动连($reason) server=$id 需要授权：后台不弹授权页，等面板/对话授权后自动续连")
            }
            is McpConnectOutcome.Failed -> {
                val attempt = scheduleRetry(e, id)
                e.log("[mcp] 自动连($reason) server=$id 失败（第 $attempt 次，已排退避重试）：${outcome.message}")
            }
        }
    }

    /** 指数退避：60s、120s、240s…封顶 30min（机会式的"式"）。只记账，不落日志（一台一行）。 */
    private fun scheduleRetry(e: McpSelfService.Env, id: String): Int = synchronized(lock) {
        val n = (failures[id] ?: 0) + 1
        failures[id] = n
        val backoff = (BASE_BACKOFF_MS shl (n - 1).coerceAtMost(20)).coerceAtMost(MAX_BACKOFF_MS)
        nextTryAtMs[id] = e.now() + backoff
        n
    }
}
