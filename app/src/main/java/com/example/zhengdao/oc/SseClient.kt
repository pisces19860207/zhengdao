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
        var attempt = 0
        var everConnected = false

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
                    // 🔍 诊断：打印响应头与 Content-Length，用于区分「服务端给了空流」
                    //    和「读到了数据但被中途关闭」。服务端三路鉴别已洗清，
                    //    问题在客户端侧，这里要把客户端实际看到的响应形态打出来。
                    ocLog(
                        "SSE 连接建立 HTTP ${resp.code} | " +
                            "encoding=${resp.header("Content-Encoding")} " +
                            "len=${resp.header("Content-Length")} " +
                            "transfer=${resp.header("Transfer-Encoding")} " +
                            "conn=${resp.header("Connection")}"
                    )
                    emit(Event.Reconnected(if (everConnected) sessionIdOf("") else null))
                    everConnected = true
                    attempt = 0                       // 连上就重置退避

                    val source = body.source().buffer()
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
                // 正常结束流（服务器关闭）——视为一次断开。
                // ⚠️ 必须留痕：这条路径此前**没有任何日志**，与 CancellationException
                //    一起构成两条"静默死亡"路径，导致断连原因完全无法定位。
                // 🔍 关键判据：读到 0 行 = 服务端给的是空流（或客户端根本没读到）；
                //    读到若干行才退出 = 服务端中途关流。两者修法完全不同。
                ocLog("SSE 流结束（第 ${attempt + 1} 次）：读到 $lines 行 / $bytes 字节")
                emit(Event.Disconnected(null))
            } catch (e: CancellationException) {
                // ⚠️ 不记日志就等于无痕迹死亡：协程被取消时看不出原因。
                //    必须区分"外部主动取消"（正常关闭，不该算故障）与"超时取消"（真故障）。
                ocLog("SSE 协程被取消：${e.message ?: "无消息"}")
                throw e
            } catch (e: Exception) {
                emit(Event.Disconnected(e))
                // 🔍 最终诊断（用户要求）：除 e.message 外，同时打出 HTTP code 与
                //    Content-Type，确认服务端返回形态是否正确（必须是 text/event-stream）。
                //    respCode=-1 表示连响应都拿不到（连接级失败，如连接被拒 / DNS / 端口未开）。
                ocLog(
                    "SSE 连接异常：${e.javaClass.simpleName} ${e.message} | " +
                        "HTTP=$respCode contentType=$respContentType"
                )
            }

            if (!currentCoroutineContext().isActive) break
            val wait = BACKOFF_MS[minOf(attempt++, BACKOFF_MS.lastIndex)]
            delay(wait)
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