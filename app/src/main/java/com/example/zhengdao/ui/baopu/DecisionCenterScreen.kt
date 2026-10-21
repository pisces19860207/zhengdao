// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批（2026-10-21，派工单 §1③）：**决策中心**——四页签
// （待授权 / 建议 / 提案 / 记录），承载排队中的卡与审计撤销入口。
//
// 设计依据（v1.4 §0 决策 #5 / §3 W6/W7）：
//   - 盾牌是顶栏第五件；徽标＝前三页签待处理总数，记录页不计；
//   - 「建议」页签双类型（99 建议＋100 效果同驻，类型签区分，效果行排前）；
//   - 记录页承载审计与撤销（**仅可回退操作**，不制造新待处理）；
//   - 四页签各有空态文案，无插画。
//
// 纪律：本文件只渲染＋转发，判据与数据全在控制器（`decisionCenter*` 系列）
// 与 `DecisionCardRules`；点行打开同款决策卡（设计稿 W7）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R

/**
 * 决策中心（全屏 Dialog；与抱朴设置同一条承载路子——摆对话 Column 里会露两端）。
 *
 * @param onOpenSettings 「去设置页处理」（RETIRE 效果卡的指路钮）与抽屉同入口
 */
@Composable
internal fun DecisionCenterScreen(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 点行打开的「同款决策卡」（null = 只看列表）
    var openCardKey by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(DecisionTab.TO_AUTHORIZE) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // 顶条：返回＋标题＋（待处理数）
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("←") }
                    Text(
                        text = stringResource(R.string.decision_center_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    val badge = controller.decisionBadge
                    if (badge > 0) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                text = badge.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                }
                Text(
                    text = stringResource(R.string.decision_center_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    DecisionTab.entries.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(stringResource(decisionTabTitle(t))) },
                        )
                    }
                }
                when (tab) {
                    DecisionTab.TO_AUTHORIZE -> AuthorizeTab(controller)
                    DecisionTab.SUGGESTIONS -> SuggestionsTab(controller, openCardKey) { openCardKey = it }
                    DecisionTab.PROPOSALS -> ProposalsTab(controller)
                    DecisionTab.HISTORY -> HistoryTab(controller)
                }
            }
        }
    }

    // 点行打开的「同款决策卡」（W7）；动作经控制器按 key 分发
    openCardKey?.let { key ->
        val spec = controller.decisionCardSpecFor(key)
        if (spec == null) {
            openCardKey = null // 行已经没了（被处理/被撤销）⇒ 收对话框
        } else {
            AlertDialog(
                onDismissRequest = { openCardKey = null },
                title = { Text(spec.title) },
                text = {
                    DecisionCard(
                        spec = spec,
                        onAction = { buttonId ->
                            controller.onDecisionCardAction(key, buttonId)
                            // 高风险采纳要二次确认 ⇒ 先收决策中心，让确认层露出来
                            if (controller.pendingAdoptConfirm != null) {
                                openCardKey = null
                                onDismiss()
                            }
                        },
                    )
                },
                confirmButton = {},
            )
        }
    }
}

/** 页签标题（四句；空态见各 Tab）。 */
private fun decisionTabTitle(t: DecisionTab): Int = when (t) {
    DecisionTab.TO_AUTHORIZE -> R.string.decision_tab_authorize
    DecisionTab.SUGGESTIONS -> R.string.decision_tab_suggestions
    DecisionTab.PROPOSALS -> R.string.decision_tab_proposals
    DecisionTab.HISTORY -> R.string.decision_tab_history
}

/** 页签空态（四句各一句，无插画）。 */
private fun decisionEmptyText(t: DecisionTab): Int = when (t) {
    DecisionTab.TO_AUTHORIZE -> R.string.decision_empty_authorize
    DecisionTab.SUGGESTIONS -> R.string.decision_empty_suggestions
    DecisionTab.PROPOSALS -> R.string.decision_empty_proposals
    DecisionTab.HISTORY -> R.string.decision_empty_history
}

/** 一个页签的公共外壳：空态一句话 / 有内容走 [content]。 */
@Composable
private fun DecisionTabBody(tab: DecisionTab, empty: Boolean, content: @Composable () -> Unit) {
    if (empty) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(decisionEmptyText(tab)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        content()
    }
}

@Composable
private fun AuthorizeTab(controller: BaopuChatController) {
    val spec = controller.pendingAuthorizationSpec()
    DecisionTabBody(DecisionTab.TO_AUTHORIZE, empty = spec == null) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                spec?.let {
                    DecisionCard(
                        spec = it,
                        onAction = { id -> controller.onDecisionAction(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsTab(
    controller: BaopuChatController,
    openCardKey: String?,
    onOpenCard: (String?) -> Unit,
) {
    val rows = controller.decisionCenterSuggestionRows()
    DecisionTabBody(DecisionTab.SUGGESTIONS, empty = rows.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(rows, key = { it.id }) { row ->
                DecisionRowCard(row, onClick = { onOpenCard(row.id) })
            }
        }
    }
}

@Composable
private fun ProposalsTab(controller: BaopuChatController) {
    val spec = controller.pendingProposalSpec()
    DecisionTabBody(DecisionTab.PROPOSALS, empty = spec == null) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                spec?.let {
                    DecisionCard(
                        spec = it,
                        onAction = { id -> controller.onDecisionAction(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(controller: BaopuChatController) {
    val rows = controller.decisionHistoryRows()
    DecisionTabBody(DecisionTab.HISTORY, empty = rows.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(rows, key = { it.id }) { row ->
                DecisionRowCard(
                    row = row,
                    onClick = null,
                    trailing = if (row.undoable) {
                        {
                            // 撤销热区 ≥48dp（视觉 44dp 不判不合格，W7b）
                            TextButton(
                                onClick = { controller.undoDecisionGrant(row.id) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.decision_undo)) }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 列表里的一行（标题＋副标题＋徽标/类型签；可点开同款卡，可带撤销钮）。 */
@Composable
private fun DecisionRowCard(
    row: DecisionCenterRow,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.badge != null) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = row.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                val sub = listOfNotNull(row.subtitle.ifBlank { null }, row.typeLabel).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onClick != null) {
                TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_expand_arrow))
                }
            }
            trailing?.invoke()
        }
    }
}
