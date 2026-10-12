// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：
//   - OpenCode 官方 serve 模式 HTTP API（--port、/global/health、/session、SSE /event）
//   - HTTP/1.1 报文与 SSE 文本事件流格式
//   - OkHttp 4.12 官方 API（Interceptor / Request / Response / okhttp-sse）
package com.example.zhengdao.oc

import android.util.Base64
import com.example.zhengdao.rootfs.RunLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenCode HTTP 客户端 —— 太极 Tab（Compose 原生 UI）的唯一网络出入口。
 *
 * ## 为什么是 OkHttp 直连，而不是沿用 LocalProxy（已删，v1.1.1 阶段 3）
 *
 * 旧路径（WebView 版）自建了一层字节级 HTTP 转发（LocalProxy），为绕开
 * "WebView 的 fetch/XHR 收到 401 不触发 onReceivedHttpAuthRequest"（真机实测）。
 * 该层的代价（2026-10-06 代码审计结论，见 docs/milestones/证道-WebView卡慢根因诊断.md）：
 *   1. readHeaderBlock 逐字节 read + 每字节 O(N²) 拷贝
 *   2. 强制 Connection: close（否则复用连接裸奔 401）→ 每请求新建连接 + 新建线程
 *   3. SSE 长连接各占一条连接与线程，backlog 仅 64
 * 三者叠加表现为用户实测的「卡 / 慢 / 进不了对话框」。
 *
 * 本类作为**客户端**而非转发层，天然解决上述三点：OkHttp 自带缓冲解析、连接池与
 * keep-alive；且鉴权在 [AuthInterceptor] 统一注入，无需重写任何报文。
 *
 * ## ⚠️ 两个客户端的分工（不要合并）
 * - [client]：普通 API 请求，有限超时（[NORMAL_TIMEOUT_SEC]）
 * - [sseClient]：SSE 长连接。**常规应为 readTimeout=0（无限等待，避免长流被中途掐断）**；
 *   当前为诊断实验临时设为 60s（> 服务端 15s 心跳），用于排除"读超时配置异常"这一变量。
 *   ⚠️ **callTimeout 始终未设置（默认 0 = 不限时长）**——它限制整个长连接生命周期，对 SSE 致命，绝不触碰。
 * 若共用一个 client，普通 API 会因 readTimeout=0 而永久等待。
 */
class OcClient(
    private val passwordProvider: () -> String?,
) {

    private val baseUrl: String = "http://127.0.0.1:${OcManager.PORT}"

    /** 普通 API 用：有界超时，避免异常时线程挂死。 */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(NORMAL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(NORMAL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(passwordProvider))
        .build()

    /**
     * SSE 专用。
     *
     * **读超时为什么是有限值（而不是教科书的 0）**：本 App 把 SSE 当**信号通道**用
     * （服务端 `/api/event` 每次 flush 后 0.1–1.4 秒就主动 FIN，见 ERRATA 那条
     * 「服务端缺陷」条目与上游 Issue #38458），真正的数据源是 REST 轮询。
     * 这条连接若静默挂死，`SseClient` 的退避重连就永远等不到流结束、永远不触发 ——
     * 所以留一个**高于服务端心跳（15s）**的上限：4 倍心跳 = 60s，作为活性兜底。
     *
     * 历史：这个 60s 原本是 2026-10-07 那次 SSE 空流排查留下的「诊断期临时值」，
     * 结论是**服务端缺陷、与本客户端超时无关**（该排查的排除项清单见 ERRATA）。
     * 2026-10-12 把它从"临时值"转正为 [SSE_READ_TIMEOUT_SEC] 并写清依据，
     * 免得下一个人照着旧注释又改回 0。
     *
     * callTimeout 未设置（默认 0 = 不限时长），对 SSE 正确，绝不改。
     * 鉴权同样在此注入（不是复用 [client]，避免流式响应占用普通连接池条目过久）。
     */
    val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(SSE_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)   // 重连由 SseClient 的退避策略负责
        .addInterceptor(AuthInterceptor(passwordProvider))
        .build()

    fun url(path: String): String = baseUrl + path

    fun sseRequest(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .header("Accept", "text/event-stream")
        // ⚠️ SSE 必须显式禁用压缩。OkHttp 默认自动加 `Accept-Encoding: gzip`，
        //    服务端一旦对事件流做 gzip，数据会被缓冲而不逐条 flush——
        //    表现为"连上了但读不到任何事件 / 立刻断"。这是 SSE + OkHttp 的经典坑。
        .header("Accept-Encoding", "identity")
        .build()

    // ── 端点 ────────────────────────────────────────────────────────────
    // ⚠️ 以下路径与字段以**实际打包版本**的 OpenAPI spec 为准。
    //    社区 bionic 版为 binary surgery 移植产物（Hope2333/opencode-termux），
    //    API 层理论上与上游一致但不保证——**每个端点都要实测存在性**。
    //    详见 docs/milestones/证道-bionic版查证补充.md §3。

    companion object {
        const val CONNECT_TIMEOUT_SEC = 10L
        const val NORMAL_TIMEOUT_SEC = 30L

        /**
         * SSE 读超时（活性兜底，不是"等数据"）。
         *
         * 取值 = 服务端心跳 15s 的 4 倍。别改成 0：见 [sseClient] 的注释 ——
         * 挂死的信号连接会让退避重连永远不触发。
         */
        const val SSE_READ_TIMEOUT_SEC = 60L

        /** 太极 Tab 固定绑定的工作区。不做目录选择器（会话/文件/权限三类接口全带此参数）。 */
        const val WORKSPACE_DIR = "/workspace"
    }
}

/**
 * 统一注入 HTTP Basic 鉴权与工作区目录。
 *
 * 这一层取代了旧路径的整个 LocalProxy：App 自己是 HTTP 客户端，直接在请求头带上
 * 凭据即可，无需任何转发与重写。
 *
 * - `Authorization: Basic base64(opencode:<password>)` —— 密码由 [OcManager] 从
 *   serve.log 解析（见 OcManager.servePassword）。
 * - `x-opencode-directory` —— ⚠️ 该头**真实 spec 里并不存在**（真实目录参数走 query：
 *   `directory` / `location[directory]`，本项目实际只用了
 *   `GET /api/model?location[directory]=`，见 `oc/OcRepository.kt` 里的 `location[directory]`）。
 *
 *   所以它是一条**兼容垫片**，不是机制：留着是因为「删掉会不会改变目录上下文」没在真机上验证过，
 *   而它已知无害（2026-10-07 那次 SSE 空流排查把它列进排除项；空流的真因是服务端缺陷，
 *   上游 Issue #38458）。**新增的接口请直接用 query 参数，不要再抄这个头。**
 *
 *   历史（2026-10-12 改写措辞）：原文写"🔬 诊断实验：SSE 请求不注入"——那次实验早已定案
 *   （服务端缺陷），继续用"实验"的口气会让人以为这里还有变量没排完。
 *   行为**未改**：SSE 仍然不带这个头（改它等于改流式路径的请求形状，要有真机验证才动）。
 *
 * 密码解析失败时**不抛异常、不加 header**（返回原请求）：让服务端返回 401，
 * 由 UI 明确报"鉴权未就绪"，好过本地崩溃。
 */
private class AuthInterceptor(
    private val passwordProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        // 兼容垫片（见类注释）：只给 REST 带上。SSE 刻意不带 —— 不是"实验"，
        // 而是流式路径的请求形状保持现状，改动要有真机验证才做。
        val isSse = request.header("Accept")?.contains("text/event-stream") == true
        if (!isSse) {
            builder.header("x-opencode-directory", OcClient.WORKSPACE_DIR)
        }

        val pw = passwordProvider()
        if (!pw.isNullOrEmpty()) {
            val creds = Base64.encodeToString(
                "opencode:$pw".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            builder.header("Authorization", "Basic $creds")
        } else {
            RunLog.log("太极: serve 密码未就绪，请求将返回 401")
        }
        return chain.proceed(builder.build())
    }
}

/** 轻量日志封装，与项目其他模块一致的失败可见原则。 */
internal fun ocLog(message: String) = RunLog.log("太极: $message")

/** 网络异常统一包装，便于 UI 层区分"连不上"与"服务端拒绝"。 */
class OcHttpException(val code: Int, message: String) : IOException(message)