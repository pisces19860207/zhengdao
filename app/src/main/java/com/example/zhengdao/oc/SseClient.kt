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

        // ── 第㉙刀（§B 顺序 5 / issue #9）：4 类「原来进 Unknown、其实对用户有用」的事件 ──
        // 选它们的判据（取证见 `docs/` 与交付报告）：**REST 轮询盖不到 + 本 App 有对应 UI 面**
        // —— 消息/权限/待办由轮询与既有分支覆盖，这四类只有 SSE 会告诉客户端「变了」。

        /** 会话集合变化（`session.created`）⇒ 抽屉里的会话列表该刷新。 */
        data class SessionsChanged(val raw: String) : Event

        /** 会话的当前模型被切换（`session.model.selected`）⇒ 顶栏模型胶囊该跟上。 */
        data class SessionModelSelected(val raw: String) : Event

        /**
         * 模型目录变化（`model.updated` / `provider.updated` / `models-dev.refreshed`）
         * ⇒ 模型抽屉的列表该重新拉。
         */
        data class ModelCatalogChanged(val raw: String) : Event

        /**
         * 消息被删除（`message.removed`）⇒ 从消息列表里移除。
         *
         * 对称缺口：`part.removed` / `message.part.removed` 早已归一，唯独整条消息的删除漏了
         * ⇒ 删掉的消息要等下一次轮询才消失。价值是「更早生效」而不是「轮询盖不到」。
         */
        data class MessageRemoved(val raw: String) : Event

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
        // 第⑭刀 #2 / P2-15①：连续「连上即关」场次的独立计数（defectStreak 在阶梯末位
        // 就不再增长，没法表达真实场次；熔断看的是这个）。
        var defectRuns = 0

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
            // 第⑭刀 #2 / P2-15①：缺陷熔断 —— 连续 10 场「连上即关、0 字节」（约 1+2+4+
            // 8+15×6 ≈ 96s）就**正常结束 flow**：不发错误、不改连接状态。该端点在服务器
            // 完全健康时也是这个形态（Issue #38458），置失败态会对健康服务误报；失败可见性
            // 由 REST 轮询的熔断（shouldGiveUpReconnecting → TaijiPhase.Failed）负责。
            // 恢复零成本：flow 正常结束 ⇒ sseJob 完结 ⇒ shouldReopenConnection 第 3 条
            // （!sseAlive）判「重开」⇒ 下次 open()（换会话/重进页面/点重试）自然再试。
            if (lines == 0) {
                defectRuns += 1
                if (defectRuns >= SSE_DEFECT_GIVE_UP) {
                    ocLog(
                        "SSE 端点连续 $defectRuns 场连上即关（Issue #38458），停止 SSE 重连；" +
                            "数据以 REST 轮询为准"
                    )
                    break
                }
            } else {
                defectRuns = 0
            }
            delay(BACKOFF_MS[defectStreak])
        }
    }

    /**
     * 把 event 名 + data 原文归一为 [Event]。
     *
     * 第㉙刀：实现整体搬到 [SseEventParser]（**纯函数、不依赖 OkHttp/Android**），这里只留
     * 一行转发 —— 目的就是让「类型 → 语义」这张表能在 JVM 单测里逐格钉死（含「未知类型
     * 不崩、不丢」两条）。搬动前后取值语义逐字一致。
     */
    private fun parse(name: String?, data: String, sessionIdOf: (String) -> String?): Event =
        SseEventParser.parse(name, data, sessionIdOf)

    companion object {
        /** 重连退避阶梯（毫秒）。上限 15s，避免低端机/省电 ROM 上疯狂重连耗电。 */
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        /**
         * 第⑭刀 #2 / P2-15①：连续「连上即关、0 字节」场次的熔断阈值（约 96s 内触达）。
         * 触达后 flow 正常结束 —— 见 [connect] 循环尾部的注释。
         */
        private const val SSE_DEFECT_GIVE_UP = 10
    }
}

/**
 * SSE 事件「类型 → 语义」的**纯函数**（第㉙刀 · §B 顺序 5 / issue #9）。
 *
 * 为什么单独抽出来：[SseClient] 握着 OkHttp 客户端、进不了 JVM 单测，而这张表是
 * 「上游新增/改名事件时会不会静默不同步」的唯一判据。抽成不依赖 Android/OkHttp 的
 * object 之后，正例（已知类型各归各位）与负例（未知类型不崩、不丢）都能在单测里钉死。
 *
 * **三条不可动摇的语义**（后两条就是既有设计约束③，本刀不许改）：
 * 1. 已知类型按表归一（含本刀新接入的 4 类）；
 * 2. **未知类型不丢**：落到 [SseClient.Event.Unknown]，把**类型名与原文都带出去**
 *    —— 静默不同步比多显示一条糟糕得多（E-054 就是「把失败事件当噪声」的真实教训）；
 * 3. **不因解析失败而崩**：类型名取不到时兜底 `"unknown"`；data 是坏 JSON 也照样返回一个
 *    Event（原文原样保留），绝不抛。
 *
 * 类型名两个来源都认：SSE `event:` 行（`name` 参数）优先；没有则从 payload 的
 * `event` / `type` / `name` 字段里摸（真机抓包实测：服务端是把类型放在 JSON 的 `type` 里）。
 */
internal object SseEventParser {

    private val REGEX_TYPE = Regex("\"(?:event|type|name)\"\\s*:\\s*\"([^\"]+)\"")
    private val REGEX_DELTA = Regex("\"delta\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun parse(name: String?, data: String, sessionIdOf: (String) -> String?): SseClient.Event {
        val type = name ?: extractType(data) ?: "unknown"
        return when (type) {
            "server.connected" -> SseClient.Event.Connected(sessionIdOf(data))
            "message.updated", "message.part.updated" -> SseClient.Event.MessageUpdated(data)
            "part.updated" -> SseClient.Event.PartUpdated(data, extractDelta(data))
            "part.removed", "message.part.removed" -> SseClient.Event.PartRemoved(data)
            "session.idle" -> SseClient.Event.SessionIdle(data)
            // v2 事件名 permission.asked；旧版叫 permission.updated，一并兼容（避免版本差异漏事件）
            "permission.asked", "permission.updated" -> SseClient.Event.PermissionAsked(data)
            "todo.updated" -> SseClient.Event.TodoUpdated(data)
            // ── 第㉙刀：4 类新接入（真机都到达过，见本 object 的 KDoc / 交付报告） ──
            "session.created" -> SseClient.Event.SessionsChanged(data)
            "session.model.selected" -> SseClient.Event.SessionModelSelected(data)
            "model.updated", "provider.updated", "models-dev.refreshed" ->
                SseClient.Event.ModelCatalogChanged(data)
            "message.removed" -> SseClient.Event.MessageRemoved(data)
            else -> SseClient.Event.Unknown(type, data)
        }
    }

    /** 部分服务端把事件名放在 payload.type 而非 SSE event: 行里。 */
    fun extractType(data: String): String? =
        runCatching { REGEX_TYPE.find(data)?.groupValues?.getOrNull(1) }.getOrNull()

    /** 提取增量文本；无 delta 字段则返回 null（调用方按全量覆盖处理）。 */
    fun extractDelta(data: String): String? =
        runCatching { REGEX_DELTA.find(data)?.groupValues?.getOrNull(1) }.getOrNull()
}