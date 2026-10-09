// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import org.json.JSONObject

/**
 * Agent **运行失败**（`session.step.failed` / `session.execution.failed`）的解析与文案。
 *
 * ## 为什么单独成文件（E-054）
 *
 * 2026-10-09 真机 smoke（2.0.0）：在太极页发一句「run ls -a …」，服务端随后回
 * `session.inbox.enqueued` → `session.execution.started` → `session.step.started` →
 * `session.step.failed` → `session.execution.failed`，而 App 把这几个事件全部当
 * 「未知 SSE 事件，已忽略但保留原文」**记一行日志了事**：界面上既不报错、也不给重试，
 * 用户只看到自己那条消息下面永远空着（真机现象：等了 3 分钟一无所有）。
 *
 * 静默失败是本项目反复出现的错误形态（E-008 / E-020 / E-024 / E-050），所以这里把
 * 「事件 → 人话」的映射做成**纯函数**，让 JVM 单测能在没有设备的情况下守住它。
 */
internal object RunFailure {

    /** 视为「本次运行失败」的事件类型（服务端 SSE）。 */
    private val FAILURE_TYPES = setOf(
        "session.step.failed",
        "session.execution.failed",
        "session.error",
    )

    fun isFailure(type: String): Boolean = type in FAILURE_TYPES

    /**
     * 从事件原文里尽力挖出一句**人话原因**。
     *
     * 服务端字段路径不稳定（同一事件在不同版本里挂在不同层），故按「已知路径依次尝试 +
     * 兜底给类型名」的防御式取法 —— 与 [OcRepository.parseStepModel] 同款教训：
     * 路径猜错时**必须仍有可读输出**，绝不能返回空串（空串在界面上等于没提示）。
     */
    fun reasonOf(type: String, raw: String): String {
        val candidate = runCatching { dig(JSONObject(raw)) }.getOrNull()
        val cleaned = candidate?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "服务端报告 $type（未给出原因）"
        return if (cleaned.length > 160) cleaned.take(160) + "…" else cleaned
    }

    /** 依次尝试 `error.message` / `error` / `message` / `reason` / `detail`，根与 data/properties 都试。 */
    private fun dig(root: JSONObject): String? {
        val data = root.optJSONObject("data")
        val props = root.optJSONObject("properties")
        val scopes = listOfNotNull(root, data, props, data?.optJSONObject("properties"))
        for (scope in scopes) {
            when (val e = scope.opt("error")) {
                is JSONObject -> {
                    e.optString("message").takeIf { it.isNotEmpty() }?.let { return it }
                    e.optString("name").takeIf { it.isNotEmpty() }?.let { return it }
                    e.toString().takeIf { it != "{}" && it.isNotEmpty() }?.let { return it }
                }
                is String -> if (e.isNotEmpty()) return e
            }
            for (key in listOf("message", "reason", "detail")) {
                scope.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }
}

/**
 * SSE「未知事件」日志聚合：同类只记**首次**与**每 [every] 次**一条汇总。
 *
 * 真机实测（2026-10-09，OpenCode 2.0.22）：每条连接都会推十余种 `*.updated`
 * （project / model / provider / agent / command / skill / plugin / websearch / reference /
 * models-dev.refreshed …），原先**每种每次一行**，一屏日志全是噪声，真正的失败行反而被埋掉。
 *
 * 事件本身照旧**不丢弃**（数据以 REST 轮询为准，见 ERRATA E-008），降的只是日志量。
 */
internal class UnknownSseLog(private val every: Int = 100) {
    private val counts = HashMap<String, Int>()

    /** 返回该落盘的日志行；不必记时返回 null。 */
    fun next(type: String): String? {
        val n = (counts[type] ?: 0) + 1
        counts[type] = n
        if (n == 1) {
            return "未知 SSE 事件 type=$type，已忽略但保留原文（同类后续仅每 $every 次汇总）"
        }
        if (n % every == 0) {
            return "未知 SSE 事件 type=$type 累计已忽略 $n 次（聚合记录，事件未丢弃）"
        }
        return null
    }

    /** 总计一句话（断线/关闭时落一条），无记录则 null。 */
    fun summary(): String? {
        val total = counts.values.sum()
        if (total == 0) return null
        return "未知 SSE 事件累计忽略 $total 条 / ${counts.size} 种（聚合记录，事件未丢弃）"
    }
}
