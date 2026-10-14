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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/**
 * 玄览主界面 —— 用户模式（第⑨刀 B：多标签版）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1 / §4.2 / §4.4。
 *
 * 已落地（② 文档定稿项）：
 * - 多标签（§4.2）：标签条 + 上限 20 + 每标签独立 WebView/历史栈 + 后台 onPause+WAIVED；
 *   标签状态（地址/标题/加载/提示）每标签一份，切走再切回各归各（§B 顺序 32）；
 * - 地址栏：URL 校验 + http→https 自动升级 + 非 URL 输入走搜索引擎；
 * - 导航按钮（返回/前进/刷新）+ 系统返回键先走 WebView 历史栈；
 * - 安全：启动即 applySecurityConfig(EngineSecurityConfig())（默认加固值，§4.4）；
 *   危险 scheme 由引擎 + 委托双重拦截并给用户提示；
 * - JS 弹窗收口（§4.4，第⑨刀 B 落地）：引擎回调 → 本界面 Compose 对话框；
 * - WebView 实例经 [BrowserTabManager] 跨 Tab 保活（applicationContext 创建防泄漏）。
 *
 * 图标说明：本项目图标一律 Canvas 自绘（Tab 图标 / BackChevron 同款笔法），不引
 * material-icons 库——视觉统一 + 零额外依赖。工具行小 glyph 见文件末尾私有组件。
 *
 * 阶段 C（第⑨刀底栏改版）再做：v1.1 浮动胶囊导航条（替换下方工具行）+ 地址栏带 50dp。
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 首建第一个标签（无标签时）；之后取活跃引擎
    val engine = remember { BrowserHolder.obtain(context) }
    val tabs = BrowserTabManager.tabs()
    val active = BrowserTabManager.activeTab()

    // 地址栏输入：跟随活跃标签（切 Tab 时换成那个标签的地址）
    var urlInput by remember { mutableStateOf(active?.state?.url ?: "") }
    // UI 层一次性提示（如"标签数已达上限"），与本标签安提示条共用一行样式
    var uiMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active?.id) {
        urlInput = active?.state?.url ?: ""
    }

    // ── JS 弹窗收口（§4.4）：引擎（applicationContext 的 WebView，弹不了框）→ 本界面对话框 ──
    // 每条回答都必须最终调用一次结果回调，否则页面 JS 永久阻塞等答案。
    var jsDialog by remember { mutableStateOf<JsDialog?>(null) }
    DisposableEffect(engine) {
        engine.jsConfirmHandler = { message, onResult ->
            jsDialog = JsDialog.Confirm(message, onResult)
        }
        engine.jsPromptHandler = { message, defaultValue, onResult ->
            jsDialog = JsDialog.Prompt(message, defaultValue, onResult)
        }
        onDispose {
            engine.jsConfirmHandler = null
            engine.jsPromptHandler = null
        }
    }

    // 外部打开入口（§4.6，第 ② 刀接线）：终端链接点击把 URL 写进 BrowserHolder；
    // 组合时一次性消费 ⇒ 开新标签加载（多标签语义，见 BrowserTabManager.openExternal）。
    LaunchedEffect(Unit) {
        val target = BrowserHolder.pendingUrl
        if (target != null) {
            BrowserHolder.pendingUrl = null
            BrowserTabManager.openExternal(context, target)
        }
    }

    // 系统返回键先走 WebView 历史栈（§4.2 导航语义）；无历史才让系统处理
    BackHandler(enabled = engine.canGoBack()) { engine.goBack() }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 标签条（第⑨刀 B） ──
        BrowserTabStrip(
            tabs = tabs,
            activeId = active?.id,
            onSelect = { BrowserTabManager.selectTab(it) },
            onClose = { BrowserTabManager.closeTab(it) },
            onAdd = {
                if (BrowserTabManager.newTab(context) == null) {
                    uiMsg = context.getString(R.string.browser_tabs_full)
                }
            },
        )

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
                            active?.state?.onBlocked(context.getString(R.string.browser_blocked_scheme))
                        }
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (!tryNavigate(engine, urlInput)) {
                        active?.state?.onBlocked(context.getString(R.string.browser_blocked_scheme))
                    }
                },
            ) {
                Text(stringResource(R.string.browser_go))
            }
        }

        // ── 工具行：返回 / 前进 / 刷新 + 加载指示（阶段 C 换成 v1.1 浮动胶囊） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canBack = engine.canGoBack()
            val canFwd = engine.canGoForward()
            IconButton(onClick = { engine.goBack() }, enabled = canBack) {
                BackChevron(
                    tint = if (canBack) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                    side = 20.dp,
                )
            }
            IconButton(onClick = { engine.goForward() }, enabled = canFwd) {
                ForwardGlyph(
                    tint = if (canFwd) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                )
            }
            IconButton(
                onClick = {
                    if (!tryNavigate(engine, urlInput)) {
                        active?.state?.onBlocked(context.getString(R.string.browser_blocked_scheme))
                    }
                },
            ) {
                RefreshGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active?.state?.loading == true) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        // ── 安全提示条（可关闭）：本标签安提示优先，其次 UI 层一次性提示 ──
        val msg = active?.state?.blockedMsg ?: uiMsg
        msg?.let { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WarningGlyph(tint = MaterialTheme.colorScheme.error, side = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    // P2-11：提示正文走 zhengdaoErrorText()（左边的警告图标是图形，
                    // 3:1 即过，保留 error 色）
                    color = zhengdaoErrorText(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        active?.state?.blockedMsg = null
                        uiMsg = null
                    },
                ) {
                    CloseGlyph(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = stringResource(R.string.browser_dismiss),
                    )
                }
            }
        }

        // ── 页面区：key(活跃标签) ⇒ 切 Tab 时旧 WebView 摘挂载、新的挂上 ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val activeId = active?.id
            if (activeId != null) {
                key(activeId) {
                    AndroidView(
                        factory = { BrowserTabManager.view() ?: WebView(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (active == null || !active.state.everLoaded) {
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

    // ── JS 弹窗对话框 ──
    jsDialog?.let { dialog ->
        JsDialogUi(dialog = dialog, onDismiss = { jsDialog = null })
    }
}

/** JS 弹窗的两种形态（alert/confirm 同一形态；prompt 多一个输入框）。 */
private sealed class JsDialog {
    /** 页面消息（对话框正文）。 */
    abstract val message: String

    data class Confirm(
        override val message: String,
        val onResult: (Boolean) -> Unit,
    ) : JsDialog()

    data class Prompt(
        override val message: String,
        val defaultValue: String,
        val onResult: (String?) -> Unit,
    ) : JsDialog()
}

/**
 * JS 弹窗对话框（§4.4「JS 弹窗收口」的 UI 半）。
 * 三条出口（确认/取消/点外面）都保证回答一次——页面 JS 在等，不答就永久卡住。
 */
@Composable
private fun JsDialogUi(dialog: JsDialog, onDismiss: () -> Unit) {
    var promptText by remember(dialog) {
        mutableStateOf((dialog as? JsDialog.Prompt)?.defaultValue ?: "")
    }
    val answer: (Boolean, String?) -> Unit = { ok, text ->
        when (dialog) {
            is JsDialog.Confirm -> dialog.onResult(ok)
            is JsDialog.Prompt -> dialog.onResult(if (ok) text else null)
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(stringResource(R.string.browser_js_dialog_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(dialog.message)
                if (dialog is JsDialog.Prompt) {
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(true, promptText) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(false, null) }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
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
