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
 * ## 为什么是 OkHttp 直连，而不是沿用 LocalProxy
 *
 * 旧路径（WebView 版）自建了一层字节级 HTTP 转发（[LocalProxy]），为绕开
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
 * - [sseClient]：SSE 长连接，readTimeout 必须为 0（否则长流被中途掐断）
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
     * SSE 专用：readTimeout=0（长连接不设读超时）。
     * 鉴权同样在此注入（不是复用 [client]，避免流式响应占用普通连接池条目过久）。
     */
    val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
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
 * - `x-opencode-directory` —— OpenCode 用该 header（或等价 query 参数）确定
 *   "这个请求作用在哪个项目上下文里"。太极 Tab 固定 [OcClient.WORKSPACE_DIR]，
 *   **不做目录选择器**（见设计文档 §3.4）。
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
            .header("x-opencode-directory", OcClient.WORKSPACE_DIR)

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