// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「这次学到和已压缩放到待命中和＋那一栏里，开一个滚动循环播放的小口子，
// 就像财经里面那种」「不滚动详情，只滚动标题这几个字，点到哪条就展开那条的详情」。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 跑马灯里的一条：标题（短）× 详情（点标题才看）。 */
internal data class NoticeItem(val label: String, val body: String)

/**
 * 跑马灯窗口高度（**默认**一行文字 16dp）。
 *
 * 第一百零五批：它不再是一个写死的 Dp —— 旧版恒 16dp，1.3× 字体下 labelSmall 的行高是
 * 13sp×1.3 ≈ 16.9dp ⇒ 窗口比字还矮，字被裁掉一条（1.0× 时 13 ≤ 16 刚好没事，所以真机
 * 只有"系统字体调大"才暴露）。现在窗口 = [tickerWindowDp]（默认 16、大字体随行高长），
 * 门禁 H5 的判据同步改成「默认 16dp、大字体 ≤ 一行行高」——见 `BaopuTouchTargetGateTest`。
 */
internal const val TICKER_HEIGHT_DP = 16

/** labelSmall 的行高（`ui/theme/Type.kt`：10sp/13sp）。窗口在大字体下长到这个高度就够一行。 */
internal const val TICKER_LINE_DP = 13

/**
 * 窗口高度 = max(默认 16dp, 一行行高 × fontScale)。
 *
 * 为什么在大字体下**正好**长到一行行高、而不是"随字号无限长"：窗口是**单行视口**，
 * 比一行高就会露出下一条标签的半截（2026-10-20 真机 UI-D1「这次学到」重影就是露半截）。
 * 所以大字体下窗口＝一行行高：既裁不到字，也不会看到第二条。
 * 1.0× 时一行只有 13dp（比默认 16dp 还矮）⇒ 窗口**保持 16dp 不回缩**，那是 v1.4 与
 * 门禁 H5 一起钉死的默认值。
 */
internal fun tickerWindowDp(fontScale: Float): Float {
    val line = TICKER_LINE_DP * fontScale
    return if (line > TICKER_HEIGHT_DP) line else TICKER_HEIGHT_DP.toFloat()
}

/**
 * 系统「动画时长缩放」＝0 ⇒ 用户关了动画（无障碍/省电设置，**不是** App 的开关）。
 * 此时自动滚动降级为「只显最新一条、不滚」（v1.4 §3.9 末条；不新增设置项）。
 */
internal fun reduceMotionFrom(animatorScale: Float): Boolean = animatorScale == 0f

/** 每帧滚 0.3px（≈18px/秒），慢到能看清、快过"卡住"的错觉。 */
private const val SCROLL_STEP_PX = 0.3f

/**
 * 跑马灯摆几份标签。
 *
 * **单条不复制**（2026-10-20 真机 UI-D1）：视口只有 [TICKER_HEIGHT] 一行高，
 * 复制出来的第二份永远露着顶上那一截，看上去就是「这次学到」写了两遍、下面被裁掉。
 * 只有两条及以上才有「首尾相接循环」的意义，这时才复制两份。
 */
internal fun tickerLoop(items: List<NoticeItem>): List<NoticeItem> =
    if (items.size <= 1) items else items + items

/**
 * 「这次学到 / 已压缩」**标题竖向跑马灯**（2026-10-18 用户定稿，第四版）。
 *
 * 口径（用户逐条给的原话）：
 * 1. 位置＝顶栏那一行（「待命中」与「＋」之间），不另占一行；
 * 2. **只滚标题**（「这次学到」「已压缩」这种短标签），**详情不滚**；
 * 3. 标题首尾相接循环（复制两份，滚过半程瞬回起点，肉眼看是无限循环）；
 * 4. **点哪个标题就展开哪条的详情**（详情限高 180dp、内部可滚）；再点同一个收回。
 */
@Composable
internal fun BaopuNoticeTicker(
    items: List<NoticeItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    // 系统动画缩放读一次就够（改它要回系统设置，页内不会变）。默认值 1f＝动画开着。
    val animatorScale = remember(ctx) {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    val reduceMotion = reduceMotionFrom(animatorScale)
    var selected by remember(items) { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()

    // 只滚标题：两条以上才复制成首尾相接；单条不复制（否则第二份会露一截＝重影）。
    // 减少动画时**只摆最新一条**（items.last()）——不滚、不复制，视口里始终是当前这条。
    val doubled = remember(items, reduceMotion) {
        if (reduceMotion) listOf(items.last()) else tickerLoop(items)
    }

    LaunchedEffect(doubled, selected, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect // 减少动画：自动滚降级为「只显最新一条」
        if (items.size <= 1) return@LaunchedEffect // 单条没有循环可言，不滚
        if (selected >= 0) return@LaunchedEffect // 详情弹窗开着时不滚（正在读的不许被滚走）
        while (true) {
            val half = scroll.maxValue / 2
            if (half > 0) {
                scroll.scrollBy(SCROLL_STEP_PX)
                if (scroll.value >= half) scroll.scrollTo(0)
            }
            delay(16)
        }
    }

    Surface(
        // 圆角与卡片/按钮同一套（BaopuSkin）：顶栏里的小药丸不再各写一个 shapes.small。
        shape = BaoChipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            // 视口高度随字体走（默认 16dp、大字体＝一行行高），所以 1.3× 下不再裁字。
            Column(Modifier.height(tickerWindowDp(fontScale).dp).verticalScroll(scroll)) {
                doubled.forEachIndexed { i, item ->
                    // 减少动画时 doubled 只有一条（最新那条），索引要指回它在 items 里的位置，
                    // 否则点标题会展开"第 0 条"的详情。
                    val idx = if (reduceMotion) items.lastIndex else i % items.size
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (selected == idx) -1 else idx },
                    )
                }
            }
            // 详情改**弹窗**（2026-10-18 用户：「点了过后能弹出来吗？再点就收回去」）：
            // 浮在内容上面，不撑开顶栏、不推正文；点外面空白处即收回。
            androidx.compose.material3.DropdownMenu(
                expanded = selected >= 0 && selected < items.size,
                onDismissRequest = { selected = -1 },
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (selected >= 0 && selected < items.size) {
                        Text(
                            text = items[selected].label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Text(
                            text = items[selected].body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
