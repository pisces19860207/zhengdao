// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcTodo
import com.example.zhengdao.oc.ToolState
import com.example.zhengdao.ui.ThinkGlyph
import com.example.zhengdao.ui.ToolGlyph
import com.example.zhengdao.ui.theme.zhengdaoErrorText

// ── 工具调用卡片 ──────────────────────────────────────────────────────

/**
 * 工具调用卡片（可折叠）。
 *
 * 结构化事件带来的红利：TUI 只能画终端画面，这里可以分状态、分区展示。
 * [ToolState] 三态视觉区分，让用户一眼看出成功/失败。
 */
@Composable
fun ToolCallCard(part: OcPart.Tool, onLongPress: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    // v1.1 第二阶段：统一为「<状态图标> 🔧 <工具名> · <状态>」。
    // 状态图标：运行中 ● / 完成 ✓ / 失败 ✗（计划第 8 条）。
    val (icon, tint, stateText) = when (part.state) {
        ToolState.Running -> Triple(
            "●", MaterialTheme.colorScheme.primary, stringResource(R.string.taiji_state_running),
        )
        ToolState.Success -> Triple(
            "✓", MaterialTheme.colorScheme.primary, stringResource(R.string.taiji_state_done),
        )
        ToolState.Error -> Triple(
            // P2-11：tint 同时给 ✗ 图标和「失败」状态文字——文字那半要 4.5:1，
            // 走 zhengdaoErrorText()（C0392B 对白底 5.4:1，也顺带满足图标 3:1）。
            "✗", zhengdaoErrorText(), stringResource(R.string.taiji_state_failed),
        )
        ToolState.Unknown -> Triple("•", MaterialTheme.colorScheme.onSurfaceVariant, "")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 形状显式写出来，好让 clip 用同一个值（Card 默认是 12dp 圆角，
        // 不 clip 的话展开/收起的涟漪会在四角露出方块）
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            // P2-10（issue #17）：整张卡就是点击区（见下方 combinedClickable）。折叠态
            // 且无入参摘要时，10dp×2 + 标题行约 20dp ≈ 40dp，够不上 M3 最小触摸目标。
            // defaultMinSize 对 Card 生效：M3 Surface 是 propagateMinConstraints = true 的
            // Box，会把 minHeight 透给内容（展开态本就远超 48dp，不受影响）。
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            // 单击＝展开/收起（原行为）；长按＝交给外部弹「复制」面板。
            // 卡片自带点击手势，长按若不在这里接住，会被孩子的 clickable 吃掉（Compose 手势
            // 先给子节点），于是"长按工具卡复制"会时灵时不灵——所以每个可点区域都显式接长按。
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.taiji_copy_message),
            ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolGlyph(tint = tint)
                Spacer(Modifier.width(6.dp))
                Text(
                    part.toolName.ifEmpty { stringResource(R.string.taiji_tool) },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stateText.isNotEmpty()) {
                    // 2026-10-09：状态从「紧贴工具名的 · 已完成」改为**淡色胶囊徽标**。
                    // 原因（用户反馈「调用工具那里有个小蓝色的对勾总是被遮挡」）：
                    //   原写法把状态文字贴在工具名尾部（labelSmall 12sp、无底色、无间距），
                    //   工具名一长（MCP 全限定名常见 30+ 字符）两者视觉糊成一团、状态被压得看不清；
                    //   且 `icon`（●/✓/✗，「计划第 8 条」）此前**只声明、从未渲染**。
                    // 现在：徽标自带底色 + 内边距 + 与工具名 8dp 间距，任何长度下都清晰可辨。
                    Spacer(Modifier.width(8.dp))
                    ToolStatusBadge(icon = icon, text = stateText, tint = tint)
                }
            }
            // 折叠态也显示一行入参摘要（命令 / 路径），让"用了哪个工具、干了啥"一眼可见
            part.input?.takeIf { it.isNotBlank() }?.let { inp ->
                Spacer(Modifier.height(4.dp))
                Text(
                    inp.lineSequence().first().take(140),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                part.input?.let {
                    Text(stringResource(R.string.taiji_tool_input), style = MaterialTheme.typography.labelSmall)
                    Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                part.output?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.taiji_tool_output), style = MaterialTheme.typography.labelSmall)
                    Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── 工具状态徽标 ─────────────────────────────────────────────────────

/**
 * 工具状态徽标 —— `[图标] [状态文字]` 包在一枚淡色胶囊里。
 *
 * 背景色取状态主色的 12% 叠加（与 `IOSColor` 的 `systemBlue 12%` 同一套语言），
 * 前景文字用状态主色本身，所以「执行中 ●」是淡蓝底蓝字、「失败 ✗」是淡红底红字，
 * 颜色编码一眼可辨、又不至于像实心标签那样喧宾夺主（它是辅助信息，工具名才是主角）。
 *
 * 为什么要独立成组件：见 [ToolCallCard] 里的说明——状态此前紧贴工具名、无底色，
 * 工具名一长就糊在一起。
 */
@Composable
private fun ToolStatusBadge(icon: String, text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // B-补-21②（2026-10-16）：●/✓/✗ 是**装饰**——紧挨着的状态文字已经把意思
            // 说全了（「运行中 / 已完成 / 失败」），读屏不该再多念一个符号。
            Text(
                icon,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

// ── 思考过程（reasoning）独立样式化块 ─────────────────────────────────

/**
 * 思考过程的**折叠块**（v1.1 第二阶段）。
 *
 * 把一条消息内**相邻的多段 reasoning** 合并为一个折叠块：
 * - 头部：`💭 思考过程 · N 步　展开 ▼`（N = 非空段数；仅 1 段时省去「· N 步」）；
 * - 默认**折叠**（思考是辅助，不该抢正文的注意力）；
 * - 展开后以**灰色小字**渲染全部思考原文（辅助层级）。
 *
 * 与「最终回答」（大字号、高对比、无折叠）形成明确主次对比——这正是第二阶段要修的
 * 「正文看起来属于思考过程」的层级错位。
 */
@Composable
// P4-1（2026-10-14）拆分机械必需：原 private 仅同文件可见，拆出后 [TaijiMessageList]
// 的 PartRow / MessageList 要调用 ⇒ 放宽为 internal（模块内可见，不扩公网 API）。
internal fun ReasoningBlock(parts: List<OcPart.Reasoning>, onLongPress: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val text = parts.joinToString("\n\n") { it.text }.trim()
    if (text.isEmpty()) return

    // 步数 = 非空段数（直接来自数据，不猜测）；<2 段时不显示「· N 步」，避免「· 1 步」的怪读法。
    val steps = parts.count { it.text.isNotBlank() }
    val head = if (steps > 1) stringResource(R.string.taiji_reasoning_head_steps, steps)
    else stringResource(R.string.taiji_reasoning_head)
    // 头部摘要：取首个非空行
    val summary = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                // P2-10（issue #17）：2026-10-08 走查把竖内边距 2dp → 12dp 后整行约 44dp，
                // 仍差 M3 最小触摸目标 48dp 一截。补 defaultMinSize（放 padding 外层，
                //  Row 的 crossAxisLayoutSize 会吃这个 minHeight，内容由
                //  verticalAlignment 居中，视觉不变、热区达标）。
                .defaultMinSize(minHeight = 48.dp)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress,
                    onLongClickLabel = stringResource(R.string.taiji_copy_message),
                )
                // 2026-10-08 走查：2dp → 12dp。原来整行只有约 20dp 高，
                // 「展开/收起思考过程」这个折叠头是全 App 点不中排行榜的第二名。
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                head,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!expanded && summary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                if (expanded) stringResource(R.string.common_collapse_arrow)
                else stringResource(R.string.common_expand_arrow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 可折叠块（未知内容用）─────────────────────────────────────────────

@Composable
// P4-1（2026-10-14）拆分机械必需：同 [ReasoningBlock]——MessageList 的 OcPart.Unknown
// 分支要调用 ⇒ private 放宽为 internal。
internal fun CollapsibleBlock(
    title: String,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            // P2-10（issue #17）：与 [ReasoningBlock] 折叠头同款形态——2026-10-08 走查
            // 只把竖内边距 2dp → 12dp（约 40dp 高），仍不到 M3 最小触摸目标 48dp。
            // defaultMinSize 放在 clickable/padding 外层：padding 会连 minHeight 一起扣减，
            // 顺序反了（放 padding 内层）会按 48+24 计算、整块涨到 72dp。
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress,
                    onLongClickLabel = stringResource(R.string.taiji_copy_message),
                )
                .padding(vertical = 12.dp),
        )
        if (expanded) { HorizontalDivider(); content() }
    }
}

// ── 待办面板 ──────────────────────────────────────────────────────────

@Composable
fun TodoPanel(todos: List<OcTodo>) {
    if (todos.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(
                    R.string.taiji_todo_header,
                    todos.count { it.status == OcTodo.TodoStatus.Completed },
                    todos.size,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            todos.forEach { t ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (t.status) {
                            OcTodo.TodoStatus.Completed -> "☑"
                            OcTodo.TodoStatus.Running -> "⟳"
                            OcTodo.TodoStatus.Cancelled -> "✗"
                            OcTodo.TodoStatus.Pending -> "☐"
                        },
                        Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.content,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = if (t.status == OcTodo.TodoStatus.Completed)
                            androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}
