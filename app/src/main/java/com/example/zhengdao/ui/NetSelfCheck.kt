package com.example.zhengdao.ui

import com.example.zhengdao.rootfs.RootfsIndexFetcher
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 设置页「网络自检」的探针与结论拼装。
 *
 * ## 为什么要有第二个探针
 *
 * 自检原先只 ping `registry.npmmirror.com`（国内主机）。于是会出现这样的设备：
 * 国内主机通、**GitHub 家族全军覆没**，界面却打出绿色的「✅ 网络可用」——
 * 而 App 真正依赖的东西（下载环境包、「检查环境更新」、刷新 Agent 清单）全在 GitHub / jsDelivr 上，
 * 用户拿着绿灯却什么都下不动，还以为是 App 坏了。
 *
 * 2026-10-08 真机实测（取证见 `docs/ERRATA.md` E-034）：`github.com`、`api.github.com`、
 * `raw.githubusercontent.com`、`cdn.jsdelivr.net`、`gh-proxy.com` 在 App 的 uid 下**全部不可达**，
 * 而 `registry.npmmirror.com` 通（285ms）；同一台手机的 shell 里 curl 却能通（走代理的进程不同）。
 * 所以自检必须**同时**回答两个问题：网络本身通不通、**下载/更新这条路通不通**。
 *
 * 结论拼装 [summary] 是纯函数（有单测）；探针本体 [probeCn] / [probeIndex] 走网络，只在这里收口。
 */
object NetSelfCheck {

    /** 一次探针的结果：[ok] 是否可达、[ms] 耗时、[err] 不可达原因的短描述。 */
    data class Probe(val ok: Boolean, val ms: Long, val err: String? = null)

    /** 国内基线（顺带证明 INTERNET 权限与基本连通性，历史行为保持不变）。 */
    private const val CN_PROBE_URL = "https://registry.npmmirror.com/-/ping"

    /** 索引探针的总预算：自检是用户手动点的，不能因为一条卡死的连接把界面挂住。 */
    const val INDEX_TIMEOUT_MS = 25_000L

    /**
     * 按两个探针的成败给出人话结论。四种组合都有确定文案，**不让绿灯掩盖"下不动"**。
     */
    fun summary(cn: Probe, update: Probe): String = when {
        cn.ok && update.ok ->
            "✅ 网络可用（${cn.ms}ms）· ✅ 更新源可达（${update.ms}ms）"

        cn.ok ->
            "✅ 网络可用（${cn.ms}ms）· ⚠️ 更新源不可达：下载环境包与「检查环境更新」都会失败。" +
                "若在代理下，请到代理 App 的「分应用代理」里勾选证道"

        update.ok ->
            "⚠️ 国内镜像不通（${cn.err ?: "超时"}），但更新源可达（${update.ms}ms）"

        else ->
            "❌ 不通：${cn.err ?: "超时"}——检查网络，或在代理 App 的分应用代理里勾选证道"
    }

    /** 探国内基线：只关心「有没有网」，5 秒超时不拖慢自检。 */
    fun probeCn(): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = URL(CN_PROBE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            val ms = System.currentTimeMillis() - t0
            if (code in 200..299) Probe(true, ms) else Probe(false, ms, "HTTP $code")
        } catch (t: Throwable) {
            Probe(false, System.currentTimeMillis() - t0, t.message)
        }
    }

    /**
     * 探更新源：直接复用 [RootfsIndexFetcher.fetch]（含 gh-proxy 镜像回退），
     * 于是自检问的就是「检查环境更新」问的那个问题，不会各说各话。
     * 超出 [timeoutMs] 按不可达处理（底下的请求是 daemon 线程，不会拦住进程退出）。
     */
    fun probeIndex(timeoutMs: Long = INDEX_TIMEOUT_MS): Probe {
        val t0 = System.currentTimeMillis()
        val task = FutureTask(Callable { RootfsIndexFetcher.fetch() })
        val th = Thread(task, "net-self-check-index").apply { isDaemon = true }
        th.start()
        val idx = runCatching { task.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        return if (idx != null) Probe(true, ms)
        else Probe(false, ms, if (ms >= timeoutMs) "超时 ${timeoutMs / 1000}s" else "索引不可达")
    }
}
