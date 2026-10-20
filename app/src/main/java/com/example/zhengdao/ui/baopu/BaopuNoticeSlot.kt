// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第六十三批 · UI 第四批（抱朴首屏通知位合并）
//      ＋ 蓝总管同日定稿（一次只显示一条：错误 > 行动 > 提示；其余折成「+N」，
//        点开 bottomSheet 看全量，每条都可操作；空态整条不渲染）。
//
// 现场（基线 origin/main `75ced997`）：消息流上方**常驻五条各自独立的条**——
// 防丢横幅（红底＋一键导出）、BaopuSessionBar（中断＋续跑／引擎人话）、
// pendingNote 便签、skillNote 便签、libraryNote 便签。
// 最坏情况五条同屏 ≈ 200dp，比正文还高 ⇒ 本批把它们收进**一个通知位**。
//
// 口径分两层，别混：
// - 排序／计数＝纯函数（`baopuNoticeOrder` / `baopuNoticeMoreCount`），零 Android，JVM 直测；
// - 渲染＝下面那个 Composable，只消费纯函数的产出，自己不再判优先级（第二条判据就是下一次改漏的源头）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 通知档位。**声明顺序就是优先级**（错误 > 行动 > 提示）——
 * 排序纯函数按 `ordinal` 走，所以「把优先级改反」在这种写法下会直接把单测打红
 * （派工单 §3 负例 1 的靶心）。
 */
internal enum class BaopuNoticeLevel { Error, Action, Hint }

/**
 * 通知来源：档位＋同档内的先后（rank）。
 *
 * 定稿里那张「同优先级谁先上」的表就钉在这个枚举上，不在 UI 里散着写 if：
 * - 错误：记忆写失败 > 资料库错 > 技能错
 * - 行动：续跑 > 导出
 * - 提示：引擎人话 > Info 便签（进度一句话）
 */
internal enum class BaopuNoticeSource(val level: BaopuNoticeLevel, val rank: Int) {
    MemoryWriteError(BaopuNoticeLevel.Error, 0),
    LibraryError(BaopuNoticeLevel.Error, 1),
    SkillError(BaopuNoticeLevel.Error, 2),
    Resume(BaopuNoticeLevel.Action, 0),
    Export(BaopuNoticeLevel.Action, 1),
    EngineNotice(BaopuNoticeLevel.Hint, 0),
    InfoNote(BaopuNoticeLevel.Hint, 1),
}

/**
 * 通知条上的**行动**（定稿保命线第 3 条：导出／续跑这类入口必须直接长在条上，
 * 不许藏进「+N」）。`enabled=false` 那档是「导出中…」——按钮还在，只是点不动。
 */
internal class BaopuNoticeAction(
    val label: String,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/**
 * 一条通知。档位由 [source] 派生，调用点不再各写一份颜色/优先级
 * （同一件事有第二处判据，就是下一次「改一处漏一处」）。
 */
internal data class BaopuNoticeEntry(
    val source: BaopuNoticeSource,
    val text: String,
    /** 小标题（错误便签那三个「没记住／技能没成／资料库没成」）；null ⇒ 只有一行正文。 */
    val label: String? = null,
    /** 点掉（错误档常驻可关）。null ⇒ 这条自己不走。 */
    val onDismiss: (() -> Unit)? = null,
    /** 条上直接可点的行动。null ⇒ 提示档，没有按钮。 */
    val action: BaopuNoticeAction? = null,
) {
    val level: BaopuNoticeLevel get() = source.level
}

/**
 * 排序纯函数：先按档位（[BaopuNoticeLevel] 声明序＝优先级），同档按 [BaopuNoticeSource.rank]，
 * 再同按传进来的先后（`sortedWith` 是稳定排序，同档同 rank 不互相插队）。
 *
 * 去重口径：**同档同文案只留先来那条**（技能与资料库同报一句「没有可导出的原件」时，
 * 第二条是噪音，不该占一个 +1）。
 */
internal fun baopuNoticeOrder(entries: List<BaopuNoticeEntry>): List<BaopuNoticeEntry> =
    entries
        .distinctBy { it.level to it.text }
        .sortedWith(compareBy({ it.level.ordinal }, { it.source.rank }))

/** 「+N」的 N：条上显最高那一档，剩下几条就报几（空态与只剩一条都是 0）。 */
internal fun baopuNoticeMoreCount(ordered: List<BaopuNoticeEntry>): Int =
    (ordered.size - 1).coerceAtLeast(0)

/** 无按钮那一档的行高（定稿 36–40dp：一行 `bodySmall` 加内边距，正落在 36–40）。 */
private val ROW_HEIGHT_MIN = 36.dp
private val ROW_HEIGHT_MAX = 40.dp

/**
 * 条上带按钮那一档的行高。
 *
 * 定稿同时要「单行 36–40dp」与「按钮 48dp 触控区」——一条 36dp 的行装不下 48dp 的
 * 触控区（Material3 `TextButton` 自己就 `minimumInteractiveComponentSize()` = 48dp，
 * 36dp 行高会被它顶开）。这里取**不缩触控区**那一头：行动条 48dp，无按钮的
 * 错误／提示条仍 36–40dp。差的那 8–12dp 已写进交付报告的冲突项。
 */
private val ROW_HEIGHT_WITH_ACTION = 48.dp

/**
 * 首屏通知位（第六十三批）：消息流上方**只此一条**。
 *
 * 三条保命线的落点：
 * 1. 空态整条不渲染——`if (ordered.isEmpty()) return` 在 `Surface(` **之前**
 *    （49 批 `SessionBarEmptyStateGateTest` 钉过同样的形态，不许回退成 16dp 空灰条）；
 * 2. 行动入口长在条上——[BaopuNoticeAction] 直接渲成条尾的 `TextButton`，
 *    「+N」那张详情卡只是**多出来的路**，不是唯一的入口；
 * 3. CopyBox／底部卡区／顶条一律不在这儿——这个文件只认识 [BaopuNoticeEntry]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaopuNoticeSlot(
    entries: List<BaopuNoticeEntry>,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(entries) { baopuNoticeOrder(entries) }
    var expanded by remember { mutableStateOf(false) }
    if (ordered.isEmpty()) return
    val top = ordered.first()
    val more = baopuNoticeMoreCount(ordered)
    // 三档三底色（定稿）：错误 errorContainer／行动 secondaryContainer／提示 surfaceVariant。
    // 全是既有令牌，不另开一套通知配色。
    val bg = when (top.level) {
        BaopuNoticeLevel.Error -> MaterialTheme.colorScheme.errorContainer
        BaopuNoticeLevel.Action -> MaterialTheme.colorScheme.secondaryContainer
        BaopuNoticeLevel.Hint -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (top.level) {
        BaopuNoticeLevel.Error -> MaterialTheme.colorScheme.onErrorContainer
        BaopuNoticeLevel.Action -> MaterialTheme.colorScheme.onSecondaryContainer
        BaopuNoticeLevel.Hint -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val hasButton = top.action != null || more > 0
    Surface(
        color = bg,
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    min = if (hasButton) ROW_HEIGHT_WITH_ACTION else ROW_HEIGHT_MIN,
                    max = if (hasButton) ROW_HEIGHT_WITH_ACTION else ROW_HEIGHT_MAX,
                )
                .padding(start = 16.dp, end = 8.dp)
                // 错误档整条可点掉（沿用 49 批那个便签的手感）；条上有按钮时整条不抢点击。
                .then(
                    if (top.onDismiss != null && top.action == null) {
                        Modifier.clickable { top.onDismiss() }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            top.label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = top.text,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (more > 0) {
                TextButton(onClick = { expanded = true }) {
                    Text(stringResource(R.string.baopu_notice_more, more))
                }
            }
            top.action?.let { a ->
                TextButton(onClick = a.run, enabled = a.enabled) { Text(a.label) }
            }
        }
    }
    // 详情卡：仿 `BaopuChannelSheet` 那一套（ModalBottomSheet ＋ 竖滚 Column），
    // 全量条目都在里面，**每条都可操作**（错误能点掉、行动能点动、提示只是读）。
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.baopu_notice_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ordered.forEach { e ->
                    // 用户在这张卡里点了什么（点掉／行动）就收回去——他要看的是首屏那条，不是清单。
                    SheetRow(entry = e, onActed = { expanded = false })
                }
            }
        }
    }
}

/**
 * 详情卡里的一行。
 *
 * 错误档复用 49 批那个现成的可关便签（Surface ＋ 标题 ＋ ×），**不新造组件**；
 * 其余两档没有标题与 ×，用同一套令牌（`labelSmall`/`bodySmall` ＋ `surfaceVariant`）
 * 直接铺一行，不另开第二种通知样式。
 */
@Composable
private fun SheetRow(entry: BaopuNoticeEntry, onActed: () -> Unit) {
    val body = @Composable {
        when (entry.level) {
            BaopuNoticeLevel.Error -> BaopuIntrospectionNote(
                text = entry.text,
                label = entry.label,
                isError = true,
                onDismiss = entry.onDismiss?.let { dismiss ->
                    {
                        dismiss()
                        onActed()
                    }
                },
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                entry.label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val action = entry.action
    if (action == null) {
        body()
    } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { body() }
            TextButton(onClick = { action.run(); onActed() }, enabled = action.enabled) {
                Text(action.label)
            }
        }
    }
}
