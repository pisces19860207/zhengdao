// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuMemoryCard：记忆写入要在会话里可见——设计 §9
// 施工细节第 8 条）+ 程枢版 §16.4（记忆带来源、可单条删改；提炼归会话结束一次性）
// ＋ 派工单第十批 §1 C 段（提炼候选一律 PENDING 未落盘，等用户过目；顺带一条自省）。
//
// 第一百三十七批（2026-10-22）：三张卡全部换四档容器的**信息卡**档
// （纸深底＋只左边 4dp 黛青竖条）。原先是「surfaceVariant 底 + 无边界」——
// §6.1 实测纸深对纸只有 1.11:1，靠它分卡片屏幕上看不见，这是「糊」的来源。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.MemoryEntry

// ── 静态截断的公共判据（UI 第二批 §2；技能提案卡共用同一套，别各写一份）──────
//
// 口径只有一句：**同屏少显示几条可以，"少显示了还不说"不行**。
// 所以 `take(3)` 这类切片必须配上「还有 N 条」的入口，入口点开能看全量、再点收起。

/** 同屏最多显示几条（要改这个数字得另派单，门禁 M3 钉着）。 */
internal const val CARD_VISIBLE_LIMIT = 3

/** 还有几条没显示 ⇒ 0 表示根本没折叠（那就不许摆入口）。 */
internal fun moreCount(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)

/** 折叠态给前 [limit] 条，展开态给全量（顺序不动，只做"看得全/看得少"）。 */
internal fun <T> visibleItems(items: List<T>, expanded: Boolean, limit: Int): List<T> =
    if (expanded) items else items.take(limit)

/**
 * 「还有 N 条」那一行（记忆两张卡＋技能提案卡共用）。
 *
 * 文案只有两样：计数走本批新增的**唯一一个**键 `baopu_more_items`，
 * 箭头复用既有的 `common_expand_arrow` / `common_collapse_arrow`（派工单 §2：不许新造第二套）。
 * 整行可点——它不是一个"按钮"，是这一区块的开关。
 */
@Composable
internal fun MoreItemsRow(hidden: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (expanded) R.string.common_collapse_arrow else R.string.common_expand_arrow,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!expanded) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.baopu_more_items, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 记忆候选卡：一条候选 = 标题 ＋ 正文摘要 ＋「记住 / 不记」。
 * 只要有候选就显示（候选来自 MemoryStore 的 CANDIDATE 条目）；
 * 真正的会话结束提炼归刀 2，本刀先把卡与确认链路通起来（机制可见）。
 */
@Composable
internal fun BaopuMemoryCard(
    candidates: List<MemoryEntry>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    // 折叠状态记在组合里（房型照计划卡：不落库），**两张卡各自一份**——
    // 候选卡和待过目卡同屏时，点开一张不许把另一张也撑开。
    var candidatesExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        val visible = visibleItems(candidates, expanded = candidatesExpanded, limit = CARD_VISIBLE_LIMIT)
        val hidden = moreCount(total = candidates.size, shown = visible.size)
        visible.forEach { e -> // 同屏最多 3 张，避免把对话挤没；超出的走「还有 N 条」，不静默
            BaopuInfoContainer(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.baopu_memory_card_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = e.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (e.body.isNotBlank()) {
                    Text(
                        text = e.body.take(120) + if (e.body.length > 120) "\u2026" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onConfirm(e.id) }) {
                        Text(stringResource(R.string.baopu_memory_confirm))
                    }
                    TextButton(onClick = { onForget(e.id) }) {
                        Text(stringResource(R.string.baopu_memory_forget))
                    }
                }
            }
        }
        if (hidden > 0) {
            MoreItemsRow(
                hidden = hidden,
                expanded = candidatesExpanded,
                onToggle = { candidatesExpanded = !candidatesExpanded },
            )
        }
    }
}

/**
 * 会话结束提炼出的**待过目**候选卡（刀 2 C 段）。
 *
 * 与 [BaopuMemoryCard] 的两点不同（都是派工单点名的）：
 * 1. **没落盘**——条目是 [com.example.zhengdao.baopu.MemoryStatus.PENDING]，
 *    只活在进程里；用户点「记住」才转 CONFIRMED 写盘，点「不记」直接丢弃。
 *    标题明说「还没落盘」，用户知道点什么都不会立刻改文件。
 * 2. 按钮文案用同一对（记住/不记）——动作语义一致，不发明第三套词。
 *
 * 房型（同屏 3 张、信息卡容器 + 两按钮）与老卡一致：同一个位置换数据源，
 * 不让用户学两套界面。第一百三十七批：底色不再靠 secondaryContainer 区分
 * （那是"另一种底色深浅"，§6.1 明确不许），改由标题文案区分。
 */
@Composable
internal fun BaopuPendingMemoryCard(
    pending: List<MemoryEntry>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty()) return
    var pendingExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        val visible = visibleItems(pending, expanded = pendingExpanded, limit = CARD_VISIBLE_LIMIT)
        val hidden = moreCount(total = pending.size, shown = visible.size)
        visible.forEach { e ->
            BaopuInfoContainer(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.baopu_pending_card_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = e.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (e.body.isNotBlank()) {
                    Text(
                        text = e.body.take(120) + if (e.body.length > 120) "\u2026" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onConfirm(e.id) }) {
                        Text(stringResource(R.string.baopu_memory_confirm))
                    }
                    TextButton(onClick = { onForget(e.id) }) {
                        Text(stringResource(R.string.baopu_memory_forget))
                    }
                }
            }
        }
        if (hidden > 0) {
            MoreItemsRow(
                hidden = hidden,
                expanded = pendingExpanded,
                onToggle = { pendingExpanded = !pendingExpanded },
            )
        }
    }
}

/**
 * 自省便签（刀 2 C 段）——**恰好一条**，短句，不占地方。
 *
 * 派工单原话：「顺带产出一条『这次学到什么』……一条，短句」。
 * 2026-10-18 用户：「已完成那个卡不会自己消失吗？做成动态卡，点击后就消失，表示用户知道了」
 * ⇒ 整条可点掉（onDismiss 为空时保持只读，不显示 ×）。
 *
 * 第一百三十七批：换信息卡档。`compact` 只压内距，不换档（同一条便签不许有时是卡、有时不是）。
 */
@Composable
internal fun BaopuIntrospectionNote(
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    /** 标题；null ⇒ 用「这次学到」（兼容老调用点）。2026-10-19：错误便签不该顶着这个标题。 */
    label: String? = null,
    /** true ⇒ 用错误色（留痕不变，警示性回来）。 */
    isError: Boolean = false,
    /** true ⇒ 压紧凑（错误便签只是一句原因，不需要正文级留白）。 */
    compact: Boolean = false,
) {
    if (text.isBlank()) return
    val fg = if (isError) com.example.zhengdao.ui.theme.zhengdaoErrorText() else MaterialTheme.colorScheme.onSurfaceVariant
    val padV = if (compact) 1.dp else 2.dp
    BaopuInfoContainer(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = padV)
            .then(if (onDismiss != null) Modifier.clickable { onDismiss() } else Modifier),
    ) {
        // 内距由信息卡统一给 12dp；compact 只压缩卡外上下留白（档位不许随场景变）
        Row {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label ?: stringResource(R.string.baopu_introspection_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Text(
                    text = text,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = fg,
                    modifier = Modifier.padding(top = if (compact) 0.dp else 2.dp),
                )
            }
            if (onDismiss != null) {
                Text(
                    text = "\u00d7",
                    style = MaterialTheme.typography.titleMedium,
                    color = fg,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
