// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 未学会胶囊件（F 的「胶囊橙点显数字」）。
//
// 与 623 教学胶囊的关系（口径裁定③）：**同位兄弟件，不是共用组件**——教学胶囊一字未动，
// 本件只在没有教学且未学会 >0 时被组合（`else if` 分支，两件互斥恒一颗）；位置共用
// `CapsulePrefs`（同一颗的同一个位）；教学模式下不显示橙点（623 定稿 §5.5）；归零不组合
// （判据⑦「归零隐藏」）。共用组件合并归 624 阶段四。
// 点开＝贴底半屏的未学会清单，逐条「就教这一步」（231 F 原文流程）。
package com.example.zhengdao.ui.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.learn.NotLearnedItem
import com.example.zhengdao.baopu.learn.ObserveCenter
import com.example.zhengdao.ui.baopu.LearnItemCard
import com.example.zhengdao.ui.baopu.LearnToasts
import com.example.zhengdao.ui.theme.rButton
import kotlin.math.roundToInt

/** 橙点（230-A2 的「有问题变橙带 ?N」；色值独立成常量，不许被主题漂移带走）。 */
private val LearnOrange = Color(0xFFB45309)

/** 未学会半屏的最小高度（横竖屏都够放一张卡；面板上限仍受 [CapsuleGeometry.panelCap] 约束）。 */
private const val SHEET_MIN_DP = 220f

/** 教学胶囊的 48dp 兄弟件：非教学＋未学会 >0 才组合（组合闸在 BrowserScreen，这里再守一道）。 */
@Composable
internal fun NotLearnedCapsulePill(
    webView: WebView?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by ObserveCenter.notLearned.collectAsState()
    val count = state.count
    LearnToasts()
    if (count <= 0) return // 归零隐藏（判据⑦）

    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(CapsulePrefs.FILE, Context.MODE_PRIVATE)
    }
    var storedX by remember(prefs) { mutableFloatStateOf(CapsulePrefs.readX(prefs)) }
    var storedY by remember(prefs) { mutableFloatStateOf(CapsulePrefs.readY(prefs)) }
    var dragDp by remember { mutableStateOf(Offset.Zero) }
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val palette = xuanLanPalette()
    val label = stringResource(R.string.learn_pill_desc, count)

    // 展开时网页触摸要临时关（同 PageTouchGate；浮层压不住 AndroidView）
    PageTouchGate(expanded = expanded, webView = webView)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp: Float = maxWidth.value
        val heightDp: Float = maxHeight.value
        val startX = startLeftOf(storedX, widthDp)
        val startY = startTopOf(storedY, heightDp)
        val leftDp = CapsuleGeometry.clampLeft(startX + dragDp.x, widthDp)
        val topDp = CapsuleGeometry.clampTop(startY + dragDp.y, heightDp)

        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = CapsuleGeometry.BAR.dp)
                    .background(Color(0x52000000))
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = false }
                    },
            )
            PillSheet(
                items = state.items,
                listState = listState,
                onAnswer = { item, text -> ObserveCenter.answer(item.id, text) },
                onTeach = { item -> ObserveCenter.requestTeachStep(item.id) },
                onCollapse = { expanded = false },
                capDp = CapsuleGeometry.panelCap(heightDp, heightDp, CapsuleGeometry.SHEET_GAP),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        PillButton(
            count = count,
            palette = palette,
            label = label,
            leftDp = leftDp,
            topDp = topDp,
            onClick = { expanded = true },
            onDrag = { dx, dy -> dragDp += Offset(dx, dy) },
            onDragEnd = {
                val nx = CapsuleGeometry.normalize(startX + dragDp.x, widthDp)
                val ny = CapsuleGeometry.normalize(startY + dragDp.y, heightDp)
                storedX = nx
                storedY = ny
                CapsulePrefs.writePos(prefs, nx, ny)
                dragDp = Offset.Zero
            },
        )
    }
}

/** 橙点数徽（点身 18dp 压在右上角；胶囊本体居中显示同数——色弱/遮挡双保险）。 */
@Composable
private fun PillButton(
    count: Int,
    palette: XuanLanPalette,
    label: String,
    leftDp: Float,
    topDp: Float,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset {
                with(density) { IntOffset(leftDp.dp.roundToPx(), topDp.dp.roundToPx()) }
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
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(18.dp)
                .background(LearnOrange, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/** 贴底半屏清单（231 F：点开逐条「就教这一步」；高度上限沿 CapsuleGeometry 口径）。 */
@Composable
private fun PillSheet(
    items: List<NotLearnedItem>,
    listState: LazyListState,
    onAnswer: (NotLearnedItem, String) -> Unit,
    onTeach: (NotLearnedItem) -> Unit,
    onCollapse: () -> Unit,
    capDp: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = capDp.coerceAtLeast(SHEET_MIN_DP).dp),
        shape = RoundedCornerShape(topStart = rButton, topEnd = rButton),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.learn_sheet_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.capsule_collapse),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onCollapse),
                )
            }
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.learn_list_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
                    items(items, key = { it.id }) { item ->
                        LearnItemCard(
                            item = item,
                            onAnswer = { text -> onAnswer(item, text) },
                            onTeach = { onTeach(item) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
