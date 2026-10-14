package com.example.zhengdao.ui.browser

import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.zhengdao.BackChevron
import com.example.zhengdao.R
import com.example.zhengdao.browser.BrowserEngine
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.browser.EngineSecurityConfig
import com.example.zhengdao.browser.NavigationDelegate

/**
 * 玄览主界面 —— 用户模式（阶段 1 MVP）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1（分层）、§4.2（用户模式要点）。
 *
 * 已落地（② 文档定稿项）：
 * - 地址栏：URL 校验 + http→https 自动升级（外部明文被网络策略拦）+ 非 URL 输入走搜索引擎；
 * - 导航按钮（返回/前进/刷新）+ 系统返回键先走 WebView 历史栈；
 * - 安全：启动即 applySecurityConfig(EngineSecurityConfig())（默认加固值，§4.4）；
 *   危险 scheme 由引擎 + 委托双重拦截并给用户提示；
 * - WebView 实例经 [BrowserHolder] 跨 Tab 切换保活（applicationContext 创建防泄漏）。
 *
 * 图标说明：本项目图标一律 Canvas 自绘（Tab 图标 / BackChevron 同款笔法），不引
 * material-icons 库——视觉统一 + 零额外依赖。工具行小 glyph 见文件末尾私有组件。
 *
 * 阶段 2 再做的（§4.2）：多标签（独立 data dir + 后台降优先级 + 上限 20）、
 * WebView onPause/onResize 生命周期精细管理、JS 弹窗 Activity 级对话框。
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { BrowserHolder.obtain(context) }

    // §B 顺序 32（2026-10-14）：这几个状态必须挺过「切走再切回」。
    // WebView 由 BrowserHolder 保活（页面内容还在），但 `remember` 会随离开组合丢失
    // ⇒ 切回来地址栏空白、空态重现，看着像"页面没了"。
    //  - urlInput / everLoaded：从 holder 回填（与 pendingUrl 同处，跨 Tab/跨 Activity 都在）；
    //  - canBack / canFwd：入场直接问引擎（历史栈本就在 WebView 里活着）；
    //  - loading / blockedMsg 是**瞬态**：重进时页面已加载完、提示条本也该消失，留 remember。
    var urlInput by remember { mutableStateOf(BrowserHolder.lastUrl()) }
    var everLoaded by remember { mutableStateOf(BrowserHolder.everLoaded()) }
    var canBack by remember { mutableStateOf(engine.canGoBack()) }
    var canFwd by remember { mutableStateOf(engine.canGoForward()) }
    var loading by remember { mutableStateOf(false) }
    var blockedMsg by remember { mutableStateOf<String?>(null) }

    val enabledTint = MaterialTheme.colorScheme.onSurfaceVariant
    val disabledTint = MaterialTheme.colorScheme.outlineVariant

    DisposableEffect(engine) {
        engine.applySecurityConfig(EngineSecurityConfig())
        engine.setNavigationDelegate(object : NavigationDelegate {
            override fun shouldOverrideUrlLoading(url: String): Boolean {
                val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                if (scheme !in setOf("http", "https")) {
                    blockedMsg = context.getString(R.string.browser_blocked_scheme)
                    return true
                }
                return false
            }

            override fun onPageStarted(url: String) {
                loading = true
                everLoaded = true
                urlInput = url
                canBack = engine.canGoBack()
                canFwd = engine.canGoForward()
                // 记进 holder：切走再切回时靠它把地址栏还原（§B 顺序 32）
                BrowserHolder.rememberPage(url, true)
            }

            override fun onPageFinished(url: String) {
                loading = false
                canBack = engine.canGoBack()
                canFwd = engine.canGoForward()
                BrowserHolder.rememberPage(url, true)
            }

            override fun onSslError(url: String) {
                loading = false
                blockedMsg = context.getString(R.string.browser_ssl_error)
            }

            override fun onPageError(url: String, message: String) {
                loading = false
                blockedMsg = context.getString(R.string.browser_page_error)
            }

            override fun onSafeBrowsingHit(url: String, threatType: Int) {
                loading = false
                blockedMsg = context.getString(R.string.browser_safe_browsing_hit)
            }
        })
        onDispose {
            // 只摘委托，不 destroy WebView——holder 持有，跨 Tab 切换/终端往返保活
            engine.setNavigationDelegate(null)
        }
    }

    // 外部打开入口（§4.6，2026-10-14 第 ② 刀接线）：终端链接点击把 URL 写进
    // BrowserHolder；这里组合时一次性消费（阶段 3 的桥分享、系统分享同走此入口）。
    LaunchedEffect(Unit) {
        BrowserHolder.consumePendingUrl()?.let { target ->
            urlInput = target
            everLoaded = true
            BrowserHolder.rememberPage(target, true)
            engine.loadUrl(target)
        }
    }

    // 系统返回键先走 WebView 历史栈（§4.2 导航语义）；无历史才让系统处理
    BackHandler(enabled = canBack) { engine.goBack() }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 地址栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_url_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (!tryNavigate(engine, urlInput)) {
                            blockedMsg = context.getString(R.string.browser_blocked_scheme)
                        }
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (!tryNavigate(engine, urlInput)) {
                        blockedMsg = context.getString(R.string.browser_blocked_scheme)
                    }
                },
            ) {
                Text(stringResource(R.string.browser_go))
            }
        }

        // ── 工具行：返回 / 前进 / 刷新 + 加载指示 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { engine.goBack() }, enabled = canBack) {
                BackChevron(
                    tint = if (canBack) enabledTint else disabledTint,
                    side = 20.dp,
                )
            }
            IconButton(onClick = { engine.goForward() }, enabled = canFwd) {
                ForwardGlyph(tint = if (canFwd) enabledTint else disabledTint)
            }
            IconButton(
                onClick = {
                    if (!tryNavigate(engine, urlInput)) {
                        blockedMsg = context.getString(R.string.browser_blocked_scheme)
                    }
                },
            ) {
                RefreshGlyph(tint = enabledTint)
            }
            if (loading) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        // ── 安全提示条（可关闭） ──
        blockedMsg?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WarningGlyph(tint = MaterialTheme.colorScheme.error, side = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    // P2-11：提示正文走 zhengdaoErrorText()（左边的警告图标是图形，
                    // 3:1 即过，保留 error 色）
                    color = zhengdaoErrorText(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { blockedMsg = null }) {
                    CloseGlyph(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = stringResource(R.string.browser_dismiss),
                    )
                }
            }
        }

        // ── 页面区 ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { BrowserHolder.view() ?: WebView(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (!everLoaded) {
                Text(
                    text = stringResource(R.string.browser_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
            }
        }
    }
}

/**
 * 地址栏导航：归一化 URL 后加载；scheme 不在白名单返回 false（调用方给提示）。
 * 白名单与引擎内拦截一致（http/https）——这里是用户输入预检，引擎是兜底。
 */
private fun tryNavigate(engine: BrowserEngine, input: String): Boolean {
    val url = normalizeInput(input)
    if (url.isEmpty()) return true
    val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
    if (scheme !in setOf("http", "https")) return false
    engine.loadUrl(url)
    return true
}

/**
 * 输入归一化（§4.2 用户模式要点）：
 * - 空串 → 空（调用方无动作）；
 * - 不含点号或含空格 → 当搜索词走 bing（与 openWebSearch 兜底一致，② `TerminalActivity.kt:1061` 先例）；
 * - http:// → https://（外部明文被网络策略拦，升级避免加载失败）；
 * - 其他 → 补 https://。
 */
private fun normalizeInput(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    val looksLikeUrl = t.contains(".") && !t.contains(" ")
    if (!looksLikeUrl) {
        return "https://www.bing.com/search?q=" + Uri.encode(t)
    }
    return when {
        t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring("http://".length)
        t.startsWith("https://", ignoreCase = true) -> t
        else -> "https://$t"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 自绘工具行小图标（与项目 Tab 图标 / BackChevron 同一套笔：圆头 1.9f 描边）。
// 刻意不引 material-icons 库：视觉统一 + 零额外依赖（项目既有做法）。
// ─────────────────────────────────────────────────────────────────────────────

/** 前进箭头（›）：返回箭头的镜像。 */
@Composable
private fun ForwardGlyph(tint: Color, side: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.12f)
            lineTo(w * 0.72f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.88f)
        }
        drawPath(
            path,
            tint,
            style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 刷新：圆环 + 环右上折角箭头（"↻"意象）。 */
@Composable
private fun RefreshGlyph(tint: Color, side: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val r = w * 0.34f
        val cx = w * 0.5f
        val cy = h * 0.5f
        drawCircle(color = tint, radius = r, center = Offset(cx, cy), style = stroke)
        // 折角箭头（环右上的"刷新头"）
        val p = Path().apply {
            moveTo(cx + r * 0.55f, cy - r * 1.15f)
            lineTo(cx + r * 1.05f, cy - r * 0.70f)
            lineTo(cx + r * 0.50f, cy - r * 0.25f)
        }
        drawPath(p, tint, style = stroke)
    }
}

/** 警告：三角 + 感叹号。 */
@Composable
private fun WarningGlyph(tint: Color, side: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.95f, h * 0.88f)
            lineTo(w * 0.05f, h * 0.88f)
            close()
        }
        drawPath(
            tri,
            tint,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), 1.6.dp.toPx(), StrokeCap.Round)
        drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.50f, h * 0.75f))
    }
}

/** 关闭：X。 */
@Composable
private fun CloseGlyph(
    tint: Color,
    contentDescription: String? = null,
    side: androidx.compose.ui.unit.Dp = 18.dp,
) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val m = w * 0.22f
        drawLine(tint, Offset(m, m), Offset(w - m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(w - m, m), Offset(m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
    }
}
