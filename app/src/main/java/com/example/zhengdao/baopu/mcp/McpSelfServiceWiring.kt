// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零二批 · MCP 自配的**装配层**（ZhengdaoApp 唯一调用点）：
// · 注入 [McpSelfService.env]（工具层不认识 Context 的同款纪律）；
// · 挂前/后台边沿监听（回前台＝一次机会式自动连；沿 Scout 的装配次序纪律，
//   必须在 AppForeground.install 之后调 install）；
// · 起一条守护线程做启动自动连（3s 延迟，同 BackupWiring 房型，不跟冷启动抢）；
// · 注册 OAuth 回调的**后台消费者**：面板没开着时回调也有归宿（换 token → 自动续连）。
package com.example.zhengdao.baopu.mcp

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.example.zhengdao.AppForeground
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.ui.baopu.McpAuthInbox
import java.util.concurrent.Executors

internal object McpSelfServiceWiring {

    @Volatile
    private var installed = false

    /** 自配通道的后台作业队列（单线程守护）：自动连与回调换 token 都在这排队，互不抢锁。 */
    private val queue = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zhengdao-mcp-selfservice").apply { isDaemon = true }
    }

    fun install(app: Context) {
        if (installed) return
        installed = true
        val appCtx = app.applicationContext
        McpSelfService.env = McpSelfService.Env(
            source = { BaopuRuntime.mcp(appCtx) },
            auth = { BaopuRuntime.mcpAuth(appCtx) },
            browser = { url -> openInBrowser(appCtx, url) },
            log = { RunLog.log(it) },
            now = { System.currentTimeMillis() },
        )
        // 面板没开时的回调兜底（面板开着时面板自己消费，见 McpAuthInbox 的路由）。
        McpAuthInbox.backgroundConsumer = ::onBackgroundCallback
        // 机会式重试的第二个触发点：回前台（启动那一次在下面的守护线程里）。
        AppForeground.addListener { front -> if (front) kick("回前台") }
        Thread {
            runCatching {
                Thread.sleep(3_000) // 同 BackupWiring：不跟冷启动抢 IO/CPU
                kick("启动")
            }
        }.apply { isDaemon = true; name = "zhengdao-mcp-selfservice-startup" }.start()
        RunLog.log("[mcp] 自配通道已装配（启动自动连 3s 后试一次；回前台机会式重试）")
    }

    /** 排一轮自动连（后台队列；失败只落日志，绝不往上抛）。 */
    internal fun kick(reason: String) {
        queue.execute {
            runCatching { McpAutoConnect.pass(reason) }
                .onFailure { RunLog.log("[mcp] 自动连($reason)整体异常（${it.javaClass.simpleName}）：不影响其它启动任务") }
        }
    }

    /**
     * OAuth 回调的后台消费者（面板未打开时的唯一归宿）。
     *
     * 返回恒 true：回调已被本通道接管（成功/失败都处理并落一行账），不要再投给面板。
     * 与面板路径同一条链：complete（换 token）→ connect（续连＋刷新工具清单）。
     */
    private fun onBackgroundCallback(callback: com.example.zhengdao.baopu.mcp.McpOAuthCallback): Boolean {
        queue.execute {
            val e = McpSelfService.env
            if (e == null) {
                RunLog.log("[mcp] 授权回调到达但自配通道未装配：已丢弃（不重投）")
                return@execute
            }
            val auth = e.auth() ?: return@execute
            try {
                val done = auth.complete(callback)
                McpSelfService.popupLedger.forget(done.serverId)
                val outcome = e.source().connect(done.serverId)
                when (outcome) {
                    is McpConnectOutcome.Ok -> {
                        McpAutoConnect.clearRetry(done.serverId)
                        e.log(
                            "[mcp] server=${done.serverId} 授权回调已续连：" +
                                "新增 ${outcome.added.coerceAtLeast(0)} 个工具（${outcome.mode ?: "?"}）",
                        )
                    }
                    is McpConnectOutcome.NeedsAuth ->
                        e.log("[mcp] server=${done.serverId} 授权完成但续连仍需授权（step-up？）：不再自动弹页")
                    is McpConnectOutcome.Failed ->
                        e.log("[mcp] server=${done.serverId} 授权完成但续连失败：${outcome.message}（已排机会式重试）")
                }
            } catch (ex: McpAuthException) {
                // 被浏览器明确拒绝 ⇒ 记台账：自动 B 从此不再为这台弹页（"拒绝后不反复弹"）。
                val id = ex.serverId
                if (ex.failure == McpAuthFailure.AUTHORIZATION_DENIED && id != null) {
                    McpSelfService.popupLedger.markDenied(id)
                }
                e.log(
                    "[mcp] 授权回调处理失败（${ex.failure}${id?.let { "，server=$it" } ?: ""}）：" +
                        "${ex.userMessage.take(80)}",
                )
            } catch (ex: Exception) {
                e.log("[mcp] 授权回调处理异常（${ex.javaClass.simpleName}）：已丢弃本次回调")
            }
        }
        return true
    }

    /** 授权页交给系统浏览器（同面板口径：ACTION_VIEW + BROWSABLE，不嵌 WebView）。 */
    private fun openInBrowser(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
