// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批追加一 §2（C 段：工作计划卡的界面）。用户 2026-10-18 逐字：
// 「他先列计划，做完一条打个勾，然后就收回去，或者有个小按钮，方便使用者点击看他
// 的工作计划以及做到哪儿了」。
//
// 判据纪律（与第十六批第一件同房型）：可见性/展开态/计数/符号映射全是**本文件的
// 纯函数**（无 Compose 依赖，J5/J15/J17/J18 JVM 直测）；Composable 只做渲染与
// 点击接线。计划数据只认**解析后的** [BaopuUiPlan]——payload 原文一律不进 UI
// （J16：绝不当正文渲染）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PlanRules

// ── 纯数据（JVM 直测） ───────────────────────────────────────────────────

/**
 * 计划卡的一条（**解析后**的形状；status 口径同 [PlanRules.STATUSES]）。
 *
 * public 的理由：[BaopuUiMessage]（controller 的公开形状）挂着它——public 函数
 * 不能暴露 internal 类型（编译期硬错，不是风格问题）。
 */
data class BaopuPlanItem(val text: String, val status: String)

/**
 * 挂在消息上的计划（[com.example.zhengdao.ui.baopu.BaopuChatController] 从
 * `part(kind='plan')` 的 payload 解析而来）。
 *
 * 为什么绕一层解析而不是直接拿 payload：J16——plan 的 JSON 原文**不许**进 UI
 * 文本通道（A+B 段的裸 JSON 气泡事故就是那么来的）。
 */
data class BaopuUiPlan(val items: List<BaopuPlanItem>) {
    companion object {
        /** payload ⇒ 计划；解析不出条目 ⇒ null（没有可显示的计划）。 */
        fun fromPayload(payload: String): BaopuUiPlan? {
            val items = PlanRules.fromJson(payload)
            return if (items.isEmpty()) {
                null
            } else {
                BaopuUiPlan(items.map { BaopuPlanItem(it.text, it.status) })
            }
        }
    }
}

// ── 纯判据（JVM 直测：J5/J15/J17/J18） ─────────────────────────────────

/**
 * J15：轮里当前生效的计划＝**最后一条**带计划的消息的计划（同一轮多次
 * update_plan ⇒ 后面的覆盖前面的，与「UI 只取最后一条」同口径）。
 */
internal fun BaopuRound.planOrNull(): BaopuUiPlan? = messages.lastOrNull { it.plan != null }?.plan

/**
 * J15：计划卡显不显示——**与轮收起无关**：有计划（非空）就显示，收起态也露那一行。
 *
 * [round] / [collapsed] 入参是把「不许被第十六批的轮收纳吃掉」写进签名：函数体
 * 故意不看它们（轮收起 ⇒ true，这正是本判据）。负例：把 `&& !collapsed` 写回来
 * ⇒ 收起轮的计划卡消失 ⇒ J15 红。
 */
internal fun shouldShowPlanCard(round: BaopuRound, plan: BaopuUiPlan?, collapsed: Boolean): Boolean {
    // round 与 collapsed 故意不参与判断（见 KDoc）：有计划就显示，与轮收起无关。
    // 负例：把 `&& !collapsed` 写回来 ⇒ 收起轮的计划卡消失 ⇒ J15 红。
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION")
    val contract = round to collapsed
    return plan != null && plan.items.isNotEmpty()
}

/** J18：分子口径＝`done` 条数（「计划 3/5 完成」的 3）。负例：改成「非 todo 条数」必红。 */
internal fun planDoneCount(items: List<BaopuPlanItem>): Int =
    items.count { it.status == PlanRules.STATUS_DONE }

/**
 * J17：状态 → 符号。未知状态按 `todo` 画（不崩、不猜——把它当未开始最保守）。
 * 符号是字形不是文案，不走 strings（✓/●/○ 三态照派工单 §2.1）。
 */
internal fun planStatusSymbol(status: String): String = when (status) {
    PlanRules.STATUS_DONE -> "✓"
    PlanRules.STATUS_DOING -> "●"
    else -> "○"
}

/**
 * J5：计划卡展开态（与**轮的收起各自独立**——点计划卡只翻计划卡）：
 * - 正在跑 ⇒ 自动展开（用户要看得见做到哪儿了）；
 * - 跑完 ⇒ 自动收起（跟轮一起收，但那一行还在）；
 * - 用户点过（键在 [userPlanExpandedIds]）⇒ 展开，再点收起（翻转由组合层做）。
 *
 * 房型照十六批 [roundCollapsed]：状态只记在组合内（remember 轮键），不落库。
 */
internal fun planCardExpanded(
    round: BaopuRound,
    running: Boolean,
    userPlanExpandedIds: Set<Long>,
): Boolean = when {
    running && round.isCurrent -> true
    else -> round.userMessageId in userPlanExpandedIds
}

// ── 渲染 ────────────────────────────────────────────────────────────────

/**
 * 工作计划卡：收起态一行「计划 3/5 完成」＋ 箭头；展开态每条一行（符号＋原文，
 * 超长单行省略）。点这一行 ⇄ 翻转（不新增「全部展开」按钮——派工单 §2.1）。
 */
@Composable
internal fun BaopuPlanCard(
    plan: BaopuUiPlan,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.baopu_plan_summary,
                        planDoneCount(plan.items),
                        plan.items.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                plan.items.forEach { item ->
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = planStatusSymbol(item.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
