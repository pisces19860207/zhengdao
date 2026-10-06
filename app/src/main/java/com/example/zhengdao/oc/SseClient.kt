// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Server-Sent Events（SSE）文本事件流格式（RFC 8286 风格，
// text/event-stream: data: 行 +空行分隔），OpenCode 官方 /event 端点的事件类型。
package com.example.zhengdao.oc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * SSE 客户端：建立/维持事件长连接，解析事件，重连退避。
 *
 * ## ⚠️ 现状：SSE 仅作「信号通道」，数据以 REST 轮询为准（ERRATA E-008）
 *
 * OpenCode 的 `/api/event` 存在服务端缺陷（Issue #38458）：每次 flush 后 0.1–1.4s 即关闭
 * 连接，客户端只收到第一波数据然后静默。本机（2.0.22）实测表现为「HTTP 200 连上、读到
 * 0 字节即断」。**故本类不再作为数据源**——[OcRepository] 以 REST 轮询取消息；本类只在
 * 连上时发一次 [Event.Reconnected] 表示「信号通道就绪」。等上游修复后可切回纯 SSE。
 *
 * ## 为什么自己解析而不用 okhttp-sse 的EventSources
 *
 * okhttp-sse 是独立 artifact（不随 okhttp 主包提供），且其工厂类面向"创建监听器"
 * 而非 Flow。本项目需要的是 **[重连退避 + 未知事件兜底] 两条硬要求**，直接基于
 * OkHttp 的 `response.body.source()` 解析反而更直白，且**不新增依赖**。
 * 协议本身很简单：`data:` 行 + 空行即一个事件。
 *
 * ## 三条不可省略的设计约束
 *
 * 1. **未知事件必须保留**（[Event.Unknown]）：上游持续新增事件类型，丢弃会导致
 *    UI **静默不同步**——比多显示一条糟糕得多。
 * 2. **重连退避上限 15s**：低端机+ 省电ROM 上疯狂重连会耗电。
 * 3. **[reconnected] 标记**：调用方据此触发"全量补齐"——断线期间的消息
 *    **只存在于** `GET /session/{id}/message` 的全量结果里（见 OcRepository）。
 */
class SseClient(private val http: OkHttpClient) {

    sealed interface Event {
        /** 连接建立（服务器侧可能先发server.connected）。 */
        data class Connected(val sessionId: String?) : Event

        /** 消息新增/更新。 */
        data class MessageUpdated(val raw: String) : Event

        /**
         * 消息某一段更新。
         *
         * ⚠️ [delta] 是流式增量追加的关键：**有则追加、无则视为全量覆盖**。
         * 混用会导致文字重复或闪烁（见 OcRepository.reduce）。
         */
        data class PartUpdated(val raw: String, val delta: String?) : Event

        data class PartRemoved(val raw: String) : Event

        data class SessionIdle(val raw: String) : Event

        /** Agent 请求授权。UI **必须**响应，否则 Agent 卡死（见 PermissionSheet）。 */
        data class PermissionAsked(val raw: String) : Event

        data class TodoUpdated(val raw: String) : Event

        /** 未知类型 —— 记录但不丢弃。 */
        data class Unknown(val type: String, val raw: String) : Event

        /** 连接断开（携带是否曾成功连上，用于退避策略）。 */
        data class Disconnected(val cause: Throwable?) : Event

        /** 重连成功后发出，调用方应据此做全量补齐。 */
        data class Reconnected(val sessionId: String?) : Event
    }

    /**
     * 连接 [path]（如 `/event`）并持续发出事件，直到协程被取消。
     *
     * 使用方在 collect 中须保证**归约逻辑集中且可测**（见 OcRepository）。
     */
    fun connect(
        path: String,
        request: Request,
        sessionIdOf: (raw: String) -> String?,
    ): Flow<Event> = flow {
        var everConnected = false
        // 连续"连上即关、0 字节"的次数（OpenCode /api/event 已知缺陷，Issue #38458）。
        // ⚠️ 不能用"连上就重置退避"——那会让每轮都回到 1s，退化成每秒重连刷屏。
        var defectStreak = 0

        while (currentCoroutineContext().isActive) {
            var lines = 0
            var bytes = 0
            // 🔍 诊断：把响应形态（HTTP code + Content-Type）捕获到 try 作用域外，
            //    供 catch 块在连接级失败 / 异常断连时也能打印出来——
            //    用来确认服务端实际返回的 Content-Type 是否为 text/event-stream、
            //    以及 HTTP 状态码有无异常（401/404/200-html 等）。
            var respCode = -1
            var respContentType: String = "未建立连接（连接级失败，无响应对象）"
            try {
                http.newCall(request).execute().use { resp ->
                    respCode = resp.code
                    respContentType = resp.body?.contentType()?.toString() ?: "(有响应但无 body)"
                    if (!resp.isSuccessful) {
                        throw IOException("SSE HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("SSE 响应无 body")
                    // 信号通道语义（ERRATA E-008）：连上（TCP + HTTP 200）即视为「就绪」。
                    // 不指望它持续推事件——该端点连上即关（服务端已知缺陷），数据一律走 REST 轮询。
                    if (!everConnected) {
                        ocLog("SSE 信号通道就绪（HTTP ${resp.code}）；数据以 REST 轮询为准")
                    }
                    emit(Event.Reconnected(if (everConnected) sessionIdOf("") else null))
                    everConnected = true

                    val source = body.source()   // BufferedSource 本身即可 readUtf8Line / exhausted
                    var eventName: String? = null
                    val dataLines = StringBuilder()

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        lines++
                        bytes += line.length
                        when {
                            // 空行 = 一个事件结束（SSE 以空行分隔）
                            line.isEmpty() -> {
                                if (dataLines.isNotEmpty()) {
                                    emit(parse(eventName, dataLines.toString(), sessionIdOf))
                                }
                                eventName = null; dataLines.setLength(0)
                            }
                            line.startsWith(":") -> Unit// 注释行，忽略
                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (dataLines.isNotEmpty()) dataLines.append('\n')
                                dataLines.append(line.removePrefix("data:").trimStart())
                            }
                            // id: / retry: 本项目暂不消费（不跨连接续传）
                            else -> Unit
                        }
                    }
                }
                // 流结束（服务端关流）。本端点"连上即关"属**已知缺陷**（Issue #38458），
                // 不再逐轮刷日志；只有"读到过事件"（意外情况）才留痕。
                if (lines > 0) ocLog("SSE 流结束：读到 $lines 行 / $bytes 字节（非空流，值得关注）")
                emit(Event.Disconnected(null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(Event.Disconnected(e))
                // 异常是"值得关注"的少数派，照记；respCode=-1 = 连响应都拿不到（连接级失败）。
                ocLog(
                    "SSE 信号通道异常：${e.javaClass.simpleName} ${e.message} | " +
                        "HTTP=$respCode contentType=$respContentType"
                )
            }

            if (!currentCoroutineContext().isActive) break
            // ⚠️ 退避：只有"真正读到过事件"才算健康连接并重置；否则（连上即关、0 字节）
            //    按缺陷累计，指数退避（1s→2s→4s→8s→15s 封顶），不再每秒一轮刷屏。
            defectStreak = if (lines > 0) 0 else minOf(defectStreak + 1, BACKOFF_MS.lastIndex)
            delay(BACKOFF_MS[defectStreak])
        }
    }

    /** 把 "event 名 + data 原文" 归一为 [Event]。未知类型不丢，归 [Event.Unknown]。 */
    private fun parse(name: String?, data: String, sessionIdOf: (String) -> String?): Event {
        val type = name ?: extractType(data) ?: "unknown"
        return when (type) {
            "server.connected" -> Event.Connected(sessionIdOf(data))
            "message.updated", "message.part.updated" -> Event.MessageUpdated(data)
            "part.updated" -> Event.PartUpdated(data, extractDelta(data))
            "part.removed", "message.part.removed" -> Event.PartRemoved(data)
            "session.idle" -> Event.SessionIdle(data)
            "permission.asked" -> Event.PermissionAsked(data)
            "todo.updated" -> Event.TodoUpdated(data)
            else -> Event.Unknown(type, data)
        }
    }

    /** 部分服务端把事件名放在 payload.type 而非 SSE event: 行里。 */
    private fun extractType(data: String): String? =
        REGEX_TYPE.find(data)?.groupValues?.getOrNull(1)

    /** 提取增量文本；无 delta 字段则返回 null（调用方按全量覆盖处理）。 */
    private fun extractDelta(data: String): String? {
        // 注意：JSON 内转义（\n 等）需由调用方的 JSON 解析器还原，此处不手工反转义。
        val m = REGEX_DELTA.find(data) ?: return null
        return m.groupValues.getOrNull(1)
    }

    companion object {
        /** 重连退避阶梯（毫秒）。上限 15s，避免低端机/省电 ROM 上疯狂重连耗电。 */
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        private val REGEX_TYPE = Regex("\"(?:event|type|name)\"\\s*:\\s*\"([^\"]+)\"")
        private val REGEX_DELTA = Regex("\"delta\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** 取当前退避档位，供 UI 展示"重连中…第 N 次"。 */
        fun backoffMs(attempt: Int): Long = BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)]
    }
}