// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android WebView 官方文档（shouldOverrideUrlLoading）与
// OpenCode 官方 serve 模式（--port 参数）。
package com.example.zhengdao.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.example.zhengdao.oc.OcManager
import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 太极 Tab（批次 3 v1）：OpenCode Web UI 的唯一入口。
 *
 * 状态机：未安装（下载卡）→ 未运行（启动卡）→ 运行中（WebView 全屏）。
 * 判活用 HTTP ping（进程被杀也能正确恢复）；WebView 只加载 localhost，
 * 外部链接丢系统浏览器（用户定稿：不加载外部网页）。
 * 数据隔离：OcManager 以独立 XDG 四目录拉起 serve，与终端 TUI 的 npm 版互不影响。
 */
@Composable
fun TaijiScreen() {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf("check") } // check | downloading | notInstalled | stopped | starting | running
    var msg by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    // 代理是否可到达。⚠️ 不用 HttpURLConnection：真机实测它对本地代理端口
    // 恒判失败（curl 同端口 200；疑似 keep-alive 池对 127.0.0.1 直连的怪癖）——
    // 改原始 socket 发 HTTP/1.0 HEAD，按有无响应字节判定。
    fun proxyAlive(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", com.example.zhengdao.oc.LocalProxy.PROXY_PORT), 800)
            s.soTimeout = 1200
            s.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
            s.getInputStream().read() >= 0
        }
    } catch (_: Throwable) {
        false
    }

    // 判活轮询：可见期间每 2 秒探测 serve（经本地代理，进程被杀回来也能自动恢复）
    LaunchedEffect(Unit) {
        // 初始转换：check → 已安装？stopped : 未安装
        val installed = OcManager.installed(ctx)
        phase = if (installed) "stopped" else "notInstalled"
        while (true) {
            val serveOk = withContext(Dispatchers.IO) { OcManager.serveRunning() }
            val proxyOk = withContext(Dispatchers.IO) { proxyAlive() }
            android.util.Log.w("TaijiPhase", "poll serve=$serveOk proxy=$proxyOk phase=$phase")
            val alive = serveOk && proxyOk
            if (alive && phase != "running") phase = "running"
            if (!alive && phase == "running") phase = "stopped"
            tick++
            delay(2000)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (phase) {
            "running" -> {
                // WebView 全屏（只加载 localhost；外链丢系统浏览器）
                val captured = ctx
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { wctx ->
                        WebView(wctx).apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                    android.util.Log.w("TaijiWeb", "console: ${consoleMessage?.message()} @${consoleMessage?.sourceId()?.substringAfterLast('/')}:${consoleMessage?.lineNumber()}")
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedHttpAuthRequest(
                                    view: WebView?,
                                    handler: android.webkit.HttpAuthHandler?,
                                    host: String?,
                                    realm: String?,
                                ) {
                                    val pw = OcManager.servePassword
                                    RunLog.log("太极Web: auth请求 host=$host pw=${if (pw != null) "有" else "无"}")
                                    android.util.Log.w("TaijiWeb", "authRequest host=$host pw=${if (pw != null) "yes" else "no"}")
                                    if (handler != null && pw != null) {
                                        handler.proceed("opencode", pw)
                                    } else {
                                        handler?.cancel()
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        RunLog.log("太极Web: 主帧错误 ${error?.errorCode} ${error?.description} ${request?.url}")
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url ?: return false
                                    if (u.host == "127.0.0.1" && u.port == OcManager.PORT) return false
                                    // 外链：丢系统浏览器（定稿：不加载外部网页）
                                    runCatching { captured.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                                    return true
                                }
                            }
                            loadUrl("http://127.0.0.1:${com.example.zhengdao.oc.LocalProxy.PROXY_PORT}")
                        }
                    },
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("太极", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "OpenCode 对话入口（内置版，独立于终端）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    when (phase) {
                        "check", "downloading" -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                progressText.ifBlank { "检查中…" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        "notInstalled" -> {
                            Text(
                                text = "首次使用需要下载 OpenCode 内置版（约 65MB 下载 / 289MB 落盘）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                phase = "downloading"
                                Thread {
                                    val r = OcManager.downloadAndInstall(ctx) { p -> progressText = p }
                                    msg = r.message
                                    phase = if (r.ok) "stopped" else "notInstalled"
                                }.start()
                            }) { Text("下载并安装") }
                        }
                        "stopped" -> {
                            Button(onClick = {
                                phase = "starting"
                                Thread {
                                    val err = OcManager.startServe(ctx)
                                    if (err != null) {
                                        msg = err
                                        phase = "stopped"
                                    } else {
                                        phase = "running"
                                    }
                                }.start()
                            }) { Text("启动 OpenCode") }
                        }
                        "starting" -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("启动中（首次约 3–8 秒）…", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    msg?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    // 运行中（含 stopped 之前跑过）的停止入口
                    if (phase == "stopped" || phase == "starting") {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            OcManager.stopServe()
                            msg = null
                        }) { Text("停止后台服务") }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "对话数据与终端 TUI 相互隔离；API 密钥自动注入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 检查更新（用户定稿：默认不打扰，手动触发）
                    TextButton(onClick = {
                        Thread {
                            val upd = OcManager.checkUpdate()
                            msg = when {
                                upd == null -> "检查失败或已是最新（当前 ${OcManager.VERSION}）"
                                else -> "发现新版本 ${upd.first}，开始后台更新…（完成后下次启动生效）"
                            }
                            if (upd != null) {
                                // v1：提示即到，下载复用主流程（覆盖释放）
                                val r = OcManager.downloadAndInstall(ctx) { p -> progressText = p }
                                msg = if (r.ok) "已更新到 ${OcManager.VERSION}，下次启动生效" else r.message
                            }
                        }.start()
                    }) { Text("检查 OpenCode 更新") }
                }
            }
        }
    }
}
