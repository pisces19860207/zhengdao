// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百二十三批 · 玄览「教它一次」的浮层入口（定稿：`docs/reports/623-胶囊定稿-2026-11-13.md`）。
//
// 三件事，别的一件都不做：
// ① 48dp 小胶囊三态（IDLE／THINKING／NEEDS_DECISION，闭集）；呼吸 1200ms、尺寸恒定；
// ② 点开是**半屏叠层**（Box，不是 Dialog、不改路由），高 ≤ min(40% 可用高, 可视高 − 8dp)；
// ③ 位置归一化（x,y ∈ [0,1]）存 SP `xuanlan_capsule_pos`（2 float ＋ 引用开关 1 bool）。
//
// 红线（定稿 §1／§4／§5）：
// - 只读主会话（`controller.messages` / `busy`），发送只走 `controller.send`；
//   不 new 控制器、不建 DB 表／jsonl／第二份历史（判据⑧）；
// - 非教学模式**不进组合树**（调用方 `if (teaching)` 包住，不是 `visibility = GONE`）；
// - `imePadding()` 全文件只出现一次、只在半屏根；玄览根一个字都不加（判据②）；
// - 红点不读不改 `decisionBadge`：三态只由 `busy` / `teachDraft` 决定（判据⑥）；
// - 圆角只用 `CircleShape` 与既有令牌（`rSheet`／`rButton`），不写裸值（`check-radius-tokens.py`）。
package com.example.zhengdao.ui.browser

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.core.content.edit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.baopu.BaopuActionContainer
import com.example.zhengdao.ui.baopu.BaopuChatController
import com.example.zhengdao.ui.baopu.BaopuUiMessage
import com.example.zhengdao.ui.theme.rButton
import com.example.zhengdao.ui.theme.rSheet
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ── 三态（闭集，不许第四态；定稿 §3.1） ──────────────────────────────────────

/** 胶囊三态。优先级：NEEDS_DECISION > THINKING > IDLE。 */
internal enum class XuanlanCapsuleState { IDLE, THINKING, NEEDS_DECISION }

/**
 * 三态判据（纯函数，JVM 直测）。
 *
 * **只看两个读得到的事实**：`controller.busy`（正在想）与 `teachDraft != null`（等你确认）。
 * 刻意不读 `decisionBadge`——那个值只数治理层四类待决（组授权／人设提案／采纳后队列／进化建议），
 * 与「教学这一步对不对」不是一回事（定稿 §0③）。
 */
internal fun capsuleStateOf(busy: Boolean, draftPending: Boolean): XuanlanCapsuleState = when {
    draftPending -> XuanlanCapsuleState.NEEDS_DECISION
    busy -> XuanlanCapsuleState.THINKING
    else -> XuanlanCapsuleState.IDLE
}

/**
 * 引用标记（判据④）：发送那一刻拼在消息最前面。
 *
 * 只为「引用本页」服务；本地预览页签传的是**显示名**，不传 `local-preview://` 假地址
 * （定稿 §0④「不许把假 URL 喂给模型」）。
 */
internal fun citeTagOf(name: String): String = "[当前页: $name]"

/** 几何与数值（dp 口径的 Float，纯函数，JVM 直测；UI 层只负责 `.dp`）。 */
internal object CapsuleGeometry {
    const val CAPSULE = 48f
    const val MARGIN = 12f
    const val BAR = 56f
    const val BAR_GAP = 8f
    const val TOP_SAFE = 52f
    const val SHEET_RATIO = 0.40f
    const val SHEET_GAP = 8f
    const val SHEET_MIN_PORTRAIT = 200f
    const val SHEET_MIN_LANDSCAPE = 168f
    const val SHEET_SIDE = 12f
    const val TITLE_ROW = 44f
    const val CITE_ROW = 24f
    const val INPUT_ROW = 48f
    const val MESSAGE_MIN = 96f
    const val DRAG_COLLAPSE = 32f
    const val DOT = 8f
    const val DOT_STROKE = 2f
    const val ICON = 22f

    /** 归一化跨距（可动范围）：总长减去胶囊自身。 */
    fun span(total: Float): Float = (total - CAPSULE).coerceAtLeast(1f)

    fun defaultLeft(width: Float): Float = clampLeft(width - MARGIN - CAPSULE, width)

    fun defaultTop(height: Float): Float = clampTop(height - BAR - BAR_GAP - CAPSULE, height)

    fun clampLeft(x: Float, width: Float): Float =
        x.coerceIn(MARGIN, (width - MARGIN - CAPSULE).coerceAtLeast(MARGIN))

    fun clampTop(y: Float, height: Float): Float =
        y.coerceIn(TOP_SAFE, (height - BAR - BAR_GAP - CAPSULE).coerceAtLeast(TOP_SAFE))

    fun normalize(pos: Float, total: Float): Float = (pos / span(total)).coerceIn(0f, 1f)

    fun denormalize(norm: Float, total: Float): Float = norm.coerceIn(0f, 1f) * span(total)

    /** 半屏高度上限：min(40% 可用高, 可视高 − 余量)。 */
    fun panelCap(available: Float, visible: Float, clearance: Float): Float =
        minOf(available * SHEET_RATIO, visible - clearance).coerceAtLeast(0f)
}

/**
 * 位置偏好（**唯一新增 SP**，判据⑦）。
 *
 * 只放 UI 偏好：`x` / `y` 两个归一化 float ＋「引用本页」开关 1 bool。
 * 消息、账、闸、会话一律不进这里——它们只住在既有的 `BaopuRuntime.db`。
 */
internal object CapsulePrefs {
    const val FILE = "xuanlan_capsule_pos"
    const val KEY_X = "x"
    const val KEY_Y = "y"
    const val KEY_CITE = "cite"

    fun readX(prefs: android.content.SharedPreferences): Float = prefs.getFloat(KEY_X, Float.NaN)

    fun readY(prefs: android.content.SharedPreferences): Float = prefs.getFloat(KEY_Y, Float.NaN)

    fun readCite(prefs: android.content.SharedPreferences): Boolean = prefs.getBoolean(KEY_CITE, true)

    fun writePos(prefs: android.content.SharedPreferences, x: Float, y: Float) {
        // KTX 的 edit { }：lint UseKtx 要求（本仓 lint 0/0 硬闸，不许压制）
        prefs.edit {
            putFloat(KEY_X, x)
            putFloat(KEY_Y, y)
        }
    }

    fun writeCite(prefs: android.content.SharedPreferences, on: Boolean) {
        prefs.edit { putBoolean(KEY_CITE, on) }
    }
}

/** 一次性气泡时长（派工单〇②：固定常量，不进 SwitchTokens）。 */
private const val BUBBLE_MS = 2000L

/** 呼吸周期（定稿 §3.1：1200ms，alpha 0.55 ↔ 1.0，尺寸恒定）。 */
private const val BREATH_MS = 1200

/** 气泡宽（≤30 字一行放得下；不参与几何判据）。 */
private const val BUBBLE_WIDTH = 176f

/**
 * 浮层入口：小胶囊 ＋ 半屏对话页（**只在教学模式下被组合**）。
 *
 * @param controller 主会话控制器（由 MainActivity 上提，与抱朴页是**同一个实例**，判据③）
 * @param draftPending 「保存成技能」待确认（`teachDraft != null`）⇒ 红点态
 * @param expanded 半屏是否展开（状态在 BrowserScreen，返回键要跟它互斥）
 * @param onExpandedChange 展开／收起
 * @param onOpenTeachConfirm 半屏里的「打开确认卡」（落盘仍走既有 `TeachConfirmSheet`）
 * @param onOpenDecisionCenter 仅当 `decisionBadge > 0` 时那一行文字出口（唯一、显式）
 * @param modifier 布局修饰（调用方给 fillMaxSize）
 */
@Composable
internal fun XuanlanCapsuleLayer(
    controller: BaopuChatController,
    draftPending: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenTeachConfirm: () -> Unit,
    onOpenDecisionCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = xuanLanPalette()
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(CapsulePrefs.FILE, Context.MODE_PRIVATE)
    }
    var storedX by remember(prefs) { mutableFloatStateOf(CapsulePrefs.readX(prefs)) }
    var storedY by remember(prefs) { mutableFloatStateOf(CapsulePrefs.readY(prefs)) }
    var citeOn by remember(prefs) { mutableStateOf(CapsulePrefs.readCite(prefs)) }
    var dragDp by remember { mutableStateOf(Offset.Zero) }
    var bubbleVisible by remember { mutableStateOf(true) }
    var shortNote by remember { mutableStateOf(false) }
    var draftText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 一次性气泡（≤30 字，2s 自收）；「屏幕太矮自动收起」复用同一条位置 2s。
    LaunchedEffect(Unit) {
        delay(BUBBLE_MS)
        bubbleVisible = false
    }
    LaunchedEffect(shortNote) {
        if (shortNote) {
            delay(BUBBLE_MS)
            shortNote = false
        }
    }

    // 页面标题／地址：切标签即时换（半屏不关），引用在发送那一刻才取值（定稿 §0④）。
    val active = BrowserTabManager.activeTab()
    val page = active?.state
    val previewPage = page?.isPreview() == true
    // 本地预览页签：引用开关自动置关（假地址不许进模型）。
    LaunchedEffect(active?.id, previewPage) {
        if (previewPage) {
            citeOn = false
            CapsulePrefs.writeCite(prefs, false)
        }
    }

    // `busy` 的真源只有 controller 一处，但它是 StateFlow 的读（不是 Compose State）——
    // 不 collect 就永远不重组（617 的栈顶那条教训）。这里只借 collectAsState 拿重组信号，
    // 判据仍然读 controller.busy，不另抄一份 RUNNING 集合。
    val runState by controller.engineState.collectAsState()
    val state = remember(runState, draftPending) {
        capsuleStateOf(busy = controller.busy, draftPending = draftPending)
    }

    // 叠层要真挡住页面：Compose 的浮层压不住 AndroidView（WebView）的手势 —— 实测
    // 点遮罩会穿透到网页（视频当场开播）。展开期间临时关掉活跃 WebView 的触摸，
    // 收起 / 离开组合时原样还原；网页本身不重载（判据②的滚动位置因此不变）。
    PageTouchGate(expanded = expanded, webView = active?.webView)

    // 可引用的前提：有页面、有地址、不是本地预览（预览页的地址是假的）。
    val canCite = page != null && !previewPage && page.url.isNotBlank()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp: Float = maxWidth.value
        val heightDp: Float = maxHeight.value
        val portrait = maxHeight >= maxWidth
        val startX = remember(storedX, widthDp) { startLeftOf(storedX, widthDp) }
        val startY = remember(storedY, heightDp) { startTopOf(storedY, heightDp) }
        val leftDp = CapsuleGeometry.clampLeft(startX + dragDp.x, widthDp)
        val topDp = CapsuleGeometry.clampTop(startY + dragDp.y, heightDp)
        val startXState = rememberUpdatedState(startX)
        val startYState = rememberUpdatedState(startY)
        val widthState = rememberUpdatedState(widthDp)
        val heightState = rememberUpdatedState(heightDp)

        if (expanded) {
            // 遮罩：盖页面区与标签条，**不盖底栏 56dp**（底栏仍可点停止/前进后退）。
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = CapsuleGeometry.BAR.dp)
                    .background(Color(0x52000000))
                    .pointerInput(Unit) {
                        detectTapGestures { onExpandedChange(false) }
                    },
            )
            CapsuleSheet(
                controller = controller,
                pageTitle = pageTitleOf(page, active != null),
                citeText = citeTextOf(page, active != null, citeOn),
                citeOn = citeOn && canCite,
                citeEnabled = canCite,
                previewPage = previewPage,
                portrait = portrait,
                availableH = heightDp,
                draftPending = draftPending,
                state = state,
                listState = listState,
                draftText = draftText,
                onDraftChange = { draftText = it },
                onCiteChange = {
                    citeOn = it
                    CapsulePrefs.writeCite(prefs, it)
                },
                onSend = {
                    val outgoing = outgoingTextOf(draftText, page, citeOn)
                    if (outgoing != null && controller.send(outgoing)) draftText = ""
                },
                onOpenTeachConfirm = onOpenTeachConfirm,
                onOpenDecisionCenter = onOpenDecisionCenter,
                onCollapse = { onExpandedChange(false) },
                onFocusInput = { focusRequester.requestFocus() },
                focusRequester = focusRequester,
                onTooShort = {
                    shortNote = true
                    onExpandedChange(false)
                },
                // 半屏贴底：外层是整块内容区（已扣系统栏），底边距在 CapsuleSheet 里算。
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 展开时胶囊让位（否则正好压在输入行的发送键上）；收起后原位回来，位置不丢。
        if (!expanded) {
            val bubbleText = capsuleBubbleText(shortNote = shortNote, hintVisible = bubbleVisible)
            CapsuleBubble(
                text = bubbleText,
                leftDp = leftDp,
                topDp = topDp,
                widthDp = widthDp,
            )
            CapsuleButton(
                state = state,
                palette = palette,
                leftDp = leftDp,
                topDp = topDp,
                onClick = {
                    bubbleVisible = false
                    focusManager.clearFocus(force = true)
                    onExpandedChange(true)
                },
                onDragEnd = {
                    val nx = CapsuleGeometry.normalize(startXState.value + dragDp.x, widthState.value)
                    val ny = CapsuleGeometry.normalize(startYState.value + dragDp.y, heightState.value)
                    storedX = nx
                    storedY = ny
                    CapsulePrefs.writePos(prefs, nx, ny)
                    dragDp = Offset.Zero
                },
                onDrag = { dx, dy -> dragDp += Offset(dx, dy) },
            )
        }
    }
}

// ── 小胶囊 ─────────────────────────────────────────────────────────────────

@Composable
private fun CapsuleButton(
    state: XuanlanCapsuleState,
    palette: XuanLanPalette,
    leftDp: Float,
    topDp: Float,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val label = stringResource(
        when (state) {
            XuanlanCapsuleState.IDLE -> R.string.capsule_teach_idle
            XuanlanCapsuleState.THINKING -> R.string.capsule_teach_thinking
            XuanlanCapsuleState.NEEDS_DECISION -> R.string.capsule_teach_confirm
        },
    )
    // 呼吸：只动 alpha，**不动尺寸**（定稿 §3.1）。
    val breath = rememberInfiniteTransition(label = "capsule-breath")
    val alpha by breath.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "capsule-alpha",
    )
    val iconTint = if (state == XuanlanCapsuleState.THINKING) palette.focusRing else palette.ink

    Box(
        modifier = Modifier
            .offset {
                with(density) {
                    IntOffset(leftDp.dp.roundToPx(), topDp.dp.roundToPx())
                }
            }
            .size(CapsuleGeometry.CAPSULE.dp),
    ) {
        Surface(
            modifier = Modifier
                .size(CapsuleGeometry.CAPSULE.dp)
                .semantics { contentDescription = label }
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.x.toDp().value, amount.y.toDp().value)
                        },
                    )
                },
            shape = CircleShape,
            color = palette.clusterPrimaryBg,
            border = BorderStroke(1.dp, palette.clusterStrokePrimary),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CapsuleGlyph(
                    tint = iconTint,
                    modifier = Modifier
                        .size(CapsuleGeometry.ICON.dp)
                        .alpha(if (state == XuanlanCapsuleState.THINKING) alpha else 1f),
                )
            }
        }
        if (state == XuanlanCapsuleState.NEEDS_DECISION) {
            // 红点是**点不是数**：8dp、2dp 同底色描空、不带数字。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(CapsuleGeometry.DOT.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(CapsuleGeometry.DOT_STROKE.dp)
                    .background(palette.clusterPrimaryBg, CircleShape),
            )
        }
    }
}

/** 一次性气泡（≤30 字，2s 自收）；位置贴胶囊上方，越界则左移。 */
@Composable
private fun CapsuleBubble(text: String?, leftDp: Float, topDp: Float, widthDp: Float) {
    if (text == null) return
    val density = LocalDensity.current
    val x = minOf(leftDp, (widthDp - BUBBLE_WIDTH - CapsuleGeometry.MARGIN).coerceAtLeast(CapsuleGeometry.MARGIN))
    val y = (topDp - 34f).coerceAtLeast(0f)
    Surface(
        modifier = Modifier
            .offset { with(density) { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) } }
            .widthIn(max = BUBBLE_WIDTH.dp),
        shape = RoundedCornerShape(rButton),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 22dp 自绘图标（派工单〇①：允许自绘，不新增图片资源）。 */
@Composable
private fun CapsuleGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pen = 1.5.dp.toPx()
        val bodyH = size.height * 0.66f
        drawRoundRect(
            color = tint,
            topLeft = Offset(pen / 2f, pen / 2f),
            size = Size(size.width - pen, bodyH),
            cornerRadius = CornerRadius(bodyH * 0.42f),
            style = Stroke(width = pen, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 一笔尾巴：气泡向左下收口（与底栏 glyph 同笔：圆头圆角、1.5dp）
        val tail = Path().apply {
            moveTo(size.width * 0.34f, bodyH)
            lineTo(size.width * 0.34f, size.height - pen)
            lineTo(size.width * 0.54f, bodyH)
        }
        drawPath(tail, tint, style = Stroke(width = pen, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── 半屏 ───────────────────────────────────────────────────────────────────

@Composable
private fun CapsuleSheet(
    controller: BaopuChatController,
    pageTitle: String,
    citeText: String,
    citeOn: Boolean,
    citeEnabled: Boolean,
    previewPage: Boolean,
    portrait: Boolean,
    availableH: Float,
    draftPending: Boolean,
    state: XuanlanCapsuleState,
    listState: LazyListState,
    draftText: String,
    onDraftChange: (String) -> Unit,
    onCiteChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onOpenTeachConfirm: () -> Unit,
    onOpenDecisionCenter: () -> Unit,
    onCollapse: () -> Unit,
    onFocusInput: () -> Unit,
    focusRequester: FocusRequester,
    onTooShort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val collapseLabel = stringResource(R.string.capsule_collapse)
    val sheetLabel = stringResource(R.string.capsule_sheet_title)
    Box(
        modifier = modifier
            .fillMaxWidth()
            // ★ 全文件唯一一处 imePadding：半屏根。玄览根一个字都不加（判据②）。
            .imePadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val visibleH = maxHeight.value
            // 键盘是否弹起：半屏根被 IME 顶起后可视高变矮——不写死 dp，也不取 WindowInsets.ime。
            val keyboardUp = visibleH < availableH - 1f
            val clearance = if (keyboardUp) {
                CapsuleGeometry.SHEET_GAP
            } else {
                CapsuleGeometry.BAR + CapsuleGeometry.SHEET_GAP
            }
            val room = visibleH - clearance
            val cap = CapsuleGeometry.panelCap(availableH, visibleH, clearance)
            val minSheet = if (portrait) {
                CapsuleGeometry.SHEET_MIN_PORTRAIT
            } else {
                CapsuleGeometry.SHEET_MIN_LANDSCAPE
            }
            // 太低就收起：宁可收，不许把输入行压没（定稿 §3.2）。
            LaunchedEffect(room) {
                if (room < CapsuleGeometry.SHEET_MIN_LANDSCAPE) onTooShort()
            }
            if (room >= CapsuleGeometry.SHEET_MIN_LANDSCAPE) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = CapsuleGeometry.SHEET_SIDE.dp,
                            end = CapsuleGeometry.SHEET_SIDE.dp,
                            bottom = clearance.dp,
                        )
                        .heightIn(min = minOf(minSheet, cap).dp, max = cap.dp)
                        .semantics { contentDescription = sheetLabel }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.key == Key.Escape) {
                                onCollapse()
                                true
                            } else {
                                false
                            }
                        },
                    shape = RoundedCornerShape(topStart = rSheet, topEnd = rSheet),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SheetHeader(
                            title = pageTitle,
                            collapseLabel = collapseLabel,
                            onCollapse = onCollapse,
                        )
                        // 横屏紧凑档隐藏引用行，开关移到输入行左侧（定稿 §3.2②）。
                        if (portrait) {
                            CiteRow(
                                text = citeText,
                                checked = citeOn,
                                enabled = citeEnabled,
                                onCheckedChange = onCiteChange,
                            )
                        }
                        DecisionExitRow(
                            badge = controller.decisionBadge,
                            onOpenDecisionCenter = onOpenDecisionCenter,
                        )
                        if (draftPending) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                TeachQuestionCard(
                                    onOpenTeachConfirm = onOpenTeachConfirm,
                                    onAnswerHere = onFocusInput,
                                )
                            }
                        }
                        MessageSection(
                            messages = controller.messages,
                            portrait = portrait,
                            listState = listState,
                        )
                        InputRow(
                            text = draftText,
                            hint = stringResource(R.string.capsule_input_hint),
                            sendLabel = stringResource(R.string.capsule_send),
                            showCiteToggle = !portrait,
                            citeOn = citeOn,
                            citeEnabled = citeEnabled,
                            onCiteChange = onCiteChange,
                            onTextChange = onDraftChange,
                            onSend = onSend,
                            focusRequester = focusRequester,
                            state = state,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, collapseLabel: String, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CapsuleGeometry.TITLE_ROW.dp)
            .padding(start = 16.dp, end = 4.dp)
            // 下拖 ≥32dp 收起（定稿 §3.3 出口①）
            .pointerInput(Unit) {
                var moved = 0f
                detectVerticalDragGestures(
                    onDragStart = { moved = 0f },
                    onDragEnd = { if (moved >= CapsuleGeometry.DRAG_COLLAPSE.dp.toPx()) onCollapse() },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        moved += delta
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onCollapse,
            modifier = Modifier
                .size(CapsuleGeometry.CAPSULE.dp)
                .semantics { contentDescription = collapseLabel },
        ) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CiteRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CapsuleGeometry.CITE_ROW.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CapsuleToggle(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** 全局待决出口（**唯一**、可选）：仅 `decisionBadge > 0` 时出现，点了才走。 */
@Composable
private fun DecisionExitRow(badge: Int, onOpenDecisionCenter: () -> Unit) {
    if (badge <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CapsuleGeometry.CITE_ROW.dp)
            .clickable(onClick = onOpenDecisionCenter)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.capsule_go_decision_center, badge),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 教学就地确认卡（复用四档容器里的**行动卡**，不新增第五档）。
 *
 * 落盘口仍只有 `TeachConfirmSheet` 一个：这里的按钮只是把它打开，不自己写盘。
 */
@Composable
private fun TeachQuestionCard(onOpenTeachConfirm: () -> Unit, onAnswerHere: () -> Unit) {
    BaopuActionContainer(title = stringResource(R.string.capsule_action_answer)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.capsule_action_open_confirm),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onOpenTeachConfirm)
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.capsule_action_answer),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onAnswerHere)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** 消息区：主会话最近 5 条（横屏 3 条），只读，不写第二份历史。 */
@Composable
private fun ColumnScope.MessageSection(
    messages: List<BaopuUiMessage>,
    portrait: Boolean,
    listState: LazyListState,
) {
    val shown = messages.takeLast(if (portrait) 5 else 3)
    val userLabel = stringResource(R.string.baopu_role_user)
    val assistantLabel = stringResource(R.string.baopu_role_assistant)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CapsuleGeometry.MESSAGE_MIN.dp)
            .weight(1f, fill = false)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(shown, key = { it.id }) { message ->
            val isUser = message.role == "user"
            val body = message.parts.filter { it.kind == "text" && it.text.isNotBlank() }
                .joinToString("\n") { it.text }
            if (body.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Text(
                        text = if (isUser) userLabel else assistantLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    text: String,
    hint: String,
    sendLabel: String,
    showCiteToggle: Boolean,
    citeOn: Boolean,
    citeEnabled: Boolean,
    onCiteChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    state: XuanlanCapsuleState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CapsuleGeometry.INPUT_ROW.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCiteToggle) {
            CapsuleToggle(checked = citeOn, enabled = citeEnabled, onCheckedChange = onCiteChange)
            Spacer(Modifier.width(12.dp))
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            shape = RoundedCornerShape(rButton),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester),
                )
            }
        }
        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(CapsuleGeometry.CAPSULE.dp)
                .semantics { contentDescription = sendLabel },
        ) {
            SendGlyph(tint = MaterialTheme.colorScheme.primary)
        }
        if (state == XuanlanCapsuleState.THINKING) {
            // 正在想：输入行右侧留一颗会呼吸的青点，提示发送后会有回应（不新增文案）。
            Box(
                Modifier
                    .size(CapsuleGeometry.DOT.dp)
                    .background(xuanLanPalette().focusRing, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SendGlyph(tint: Color) {
    Canvas(Modifier.size(CapsuleGeometry.ICON.dp)) {
        val pen = 1.5.dp.toPx()
        val cx = size.width * 0.18f
        val cy = size.height * 0.82f
        drawLine(tint, Offset(cx, cy), Offset(size.width * 0.82f, size.height * 0.18f), pen, StrokeCap.Round)
        val head = Path().apply {
            moveTo(size.width * 0.82f, size.height * 0.18f)
            lineTo(size.width * 0.42f, size.height * 0.2f)
            moveTo(size.width * 0.82f, size.height * 0.18f)
            lineTo(size.width * 0.8f, size.height * 0.58f)
        }
        drawPath(head, tint, style = Stroke(width = pen, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 24dp 行内开关（视觉全圆，命中由行高承接；不用 Material Switch：48dp 高塞不进这一行）。 */
@Composable
private fun CapsuleToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track = if (checked) {
        xuanLanPalette().focusRing
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(16.dp)
            .background(track, CircleShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(12.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

/** 归一化坐标 → 起点（没存过就落默认位）。单独成函数，免得把组合体的圈复杂度顶上去。 */
private fun startLeftOf(storedX: Float, width: Float): Float =
    if (storedX.isNaN()) CapsuleGeometry.defaultLeft(width) else CapsuleGeometry.denormalize(storedX, width)

private fun startTopOf(storedY: Float, height: Float): Float =
    if (storedY.isNaN()) CapsuleGeometry.defaultTop(height) else CapsuleGeometry.denormalize(storedY, height)

/** 展开期间临时关掉活跃 WebView 的触摸（Compose 浮层压不住 AndroidView）；收起即还原。 */
@Composable
private fun PageTouchGate(expanded: Boolean, webView: android.webkit.WebView?) {
    DisposableEffect(expanded, webView) {
        if (expanded) webView?.isEnabled = false
        onDispose { if (expanded) webView?.isEnabled = true }
    }
}

/** 一次性气泡文案（屏幕太矮优先，其次首次进入提示；都没有就不画）。 */
@Composable
private fun capsuleBubbleText(shortNote: Boolean, hintVisible: Boolean): String? = when {
    shortNote -> stringResource(R.string.capsule_screen_short)
    hintVisible -> stringResource(R.string.capsule_bubble_hint)
    else -> null
}

/** 发出去的那句话：空 ⇒ null；带引用就前置 `[当前页: …]`，不带就原样。 */
internal fun outgoingTextOf(text: String, page: BrowserTabState?, citeOn: Boolean): String? {
    val body = text.trim()
    if (body.isEmpty()) return null
    val tag = citeTagFor(page, citeOn)
    return if (tag == null) body else "$tag\n$body"
}

// ── 取值小函数（页面状态 → 文案／标记；纯函数，无 Android 依赖） ────────────

/** 标题行：页面标题 → 回退 URL → 回退「没有打开的页面」（定稿 §3.4）。 */
@Composable
private fun pageTitleOf(page: BrowserTabState?, hasTab: Boolean): String {
    if (!hasTab || page == null) return stringResource(R.string.capsule_sheet_no_page)
    if (page.isPreview()) return page.previewFileName()
    return page.title.ifBlank { page.url }.ifBlank { stringResource(R.string.capsule_sheet_no_page) }
}

/** 引用提示行：开＝「引用本页 · URL」，关＝「引用已关」，预览页＝「预览页不引用网址」。 */
@Composable
private fun citeTextOf(page: BrowserTabState?, hasTab: Boolean, citeOn: Boolean): String {
    if (!hasTab || page == null) return stringResource(R.string.capsule_ref_page_off)
    if (page.isPreview()) return stringResource(R.string.capsule_ref_preview_note)
    if (page.url.isBlank()) return stringResource(R.string.capsule_ref_page_off)
    return if (citeOn) {
        stringResource(R.string.capsule_ref_page_on, page.url)
    } else {
        stringResource(R.string.capsule_ref_page_off)
    }
}

/** 发送那一刻的引用标记（null = 不带）。预览页签用**显示名**，绝不用假 URL。 */
internal fun citeTagFor(page: BrowserTabState?, citeOn: Boolean): String? {
    if (!citeOn || page == null) return null
    val name = if (page.isPreview()) page.previewFileName() else page.url
    return if (name.isBlank()) null else citeTagOf(name)
}
