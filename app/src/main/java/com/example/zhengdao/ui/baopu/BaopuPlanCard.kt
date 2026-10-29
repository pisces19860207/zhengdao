// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.authz.BaopuGrantRevokeRow
import com.example.zhengdao.baopu.authz.BaopuPlanGrantSection
import com.example.zhengdao.baopu.authz.TaskGrantBoard
import com.example.zhengdao.baopu.authz.GrantSelection
import com.example.zhengdao.baopu.authz.TaskGrantFacts

// ── 纯数据（JVM 直测） ───────────────────────────────────────────────────

/**
 * 计划卡的一条（**解析后**的形状；status 口径同 [PlanRules.STATUSES]）。
 *
 * public 的理由：[BaopuUiMessage]（controller 的公开形状）挂着它——public 函数
 * 不能暴露 internal 类型（编译期硬错，不是风格问题）。
 */
data class BaopuPlanItem(val text: String, val status: String)

/**
 * 挂在计划卡上的契约那一份（第二百四十二批 A 段字段的**卡面形状**）。
 *
 * public 的理由与 [BaopuPlanItem] 一样：它挂在公开的 [BaopuUiPlan] 上。
 * 这里只有数据，判据一条都没有（上限怎么算、超了怎么停在 `baopu/plan/PlanContract.kt`）。
 *
 * [stepRisks] 与 [BaopuUiPlan.items] **同序**：第 i 格是第 i 步的预估上限，
 * 空串＝这一格没有读数（步骤数与条目数不齐时的保守画法，不猜一个档出来）。
 */
data class BaopuUiContract(
    val goal: String,
    val touches: List<String>,
    val readonly: List<String>,
    val expectedAsks: Int,
    val stopIf: List<String>,
    val stepRisks: List<String>,
)

/**
 * 挂在消息上的计划（[com.example.zhengdao.ui.baopu.BaopuChatController] 从
 * `part(kind='plan')` 的 payload 解析而来）。
 *
 * 为什么绕一层解析而不是直接拿 payload：J16——plan 的 JSON 原文**不许**进 UI
 * 文本通道（A+B 段的裸 JSON 气泡事故就是那么来的）。
 *
 * 第二百四十二批：多一个 [contract]（没声明 ⇒ null，卡面与今天逐字相同）。
 * 判据 1 要的是「卡上看得见 步骤 / 白名单 / 每步风险 / 预估问几次」——
 * 步骤本来就是一条一行，剩下四项由 [BaopuPlanCard] 展开态的契约那几行渲染。
 */
data class BaopuUiPlan(
    val items: List<BaopuPlanItem>,
    val contract: BaopuUiContract? = null,
) {
    companion object {
        /** payload ⇒ 计划；解析不出条目 ⇒ null（没有可显示的计划）。 */
        fun fromPayload(payload: String): BaopuUiPlan? {
            val items = PlanRules.fromJson(payload)
            if (items.isEmpty()) return null
            val contract = PlanRules.contractFromJson(payload)
            return BaopuUiPlan(
                items.map { BaopuPlanItem(it.text, it.status) },
                contract?.let {
                    BaopuUiContract(
                        goal = it.goal,
                        touches = it.touches,
                        readonly = it.readonly,
                        expectedAsks = it.expectedAsks,
                        stopIf = it.stopIf,
                        stepRisks = it.steps.map { step -> step.risk.name },
                    )
                },
            )
        }
    }
}

// ── 纯判据（JVM 直测：J5/J15/J17/J18） ─────────────────────────────────

/**
 * J15：轮里当前生效的计划＝**最后一条**带计划的消息的计划（同一轮多次
 * update_plan ⇒ 后面的覆盖前面的，与「UI 只取最后一条」同口径）。
 */
internal fun BaopuRound.planOrNull(): BaopuUiPlan? = messages.lastOrNull { it.plan != null }?.plan

// 第一百零六批（2026-10-21）：`shouldShowPlanCard` 与 `planCardExpanded` 两个旧判据
// 已删除——它们服务的是「计划卡内联在轮里」的形态（第十七批 C 段）。v1.5 §3.11 把
// 计划从消息正文撤出后：
//   · 显隐判据升级为「胶囊显隐只看有没有计划」⇒ `planCapsuleState(messages) != null`
//     （`BaopuPlanArea.kt`；与轮收起无关这一条精神原样保留）；
//   · 展开判据从**卡级**迁到**组级** ⇒ `planGroupExpanded(group, running, userChoice)`
//     （运行中当前组展开 / 跑完收起 / 用户选择优先）。
// 保留 `planOrNull`：它是上述两条判据的公共取数口。

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

// ── 条目级截断判据（UI 第二批 §2 第三行：`maxLines = 1` ＋ Ellipsis ＝ 静默截断）──

/**
 * 一条计划**收起态显示几行**。派工单给的口径是「至少两行或加展开入口」——
 * 这里两行**和**入口都给：两行是保底（不点也看得见开头），入口负责看全。
 */
internal const val PLAN_ITEM_COLLAPSED_MAX_LINES = 2

/**
 * 「两行装不装得下」的字符阈值（保守值，不测像素）。
 *
 * 为什么用字数而不是直接量：JVM 里跑不了 Compose 排版，拿不到真实宽度；
 * 按 bodySmall（约 12sp）在常见手机宽度上**一行 ≈ 30 字**估，两行取 60。
 * 偏保守 ⇒ 后果只是「短条目多摆了一个展开入口」，点一下全量就在眼前；
 * 反过来估小了会让长条目两行截掉还没入口，那正是本批要治的病。
 */
internal const val PLAN_ITEM_COLLAPSED_CHARS = 60

/**
 * 这条计划需不需要摆展开入口：**只有可能装不下的才摆**，
 * 每条都挂个「展开」等于没有（点了什么也不会多出来）。
 */
internal fun planItemNeedsExpand(text: String): Boolean = text.length > PLAN_ITEM_COLLAPSED_CHARS

// ── 契约那几行的判据（第二百四十二批判据 1；JVM 直测） ──────────────────────

/**
 * 卡面上一个「读数值」：空白 ⇒ null（整行不摆）。
 *
 * 为什么要这一步而不是直接把空串塞进文案：「只读区：」后面跟空的，读起来像
 * 数据丢了——没声明就是没这一行，才是实话。
 */
internal fun planContractValue(values: List<String>): String? =
    values.joinToString("、").ifEmpty { null }

/**
 * 第 [index] 步的预估上限（"R2" 那样的档位符号，照 [planStatusSymbol] 的口径：
 * 符号是字形不是文案，不走 strings）。没有那一格的读数 ⇒ null，**不猜一档**。
 */
internal fun planStepRiskTag(contract: BaopuUiContract?, index: Int): String? =
    contract?.stepRisks?.getOrNull(index)?.takeIf { it.isNotEmpty() }

// ── 渲染 ────────────────────────────────────────────────────────────────

/**
 * 工作计划卡：收起态一行「计划 3/5 完成」＋ 箭头；展开态每条一行（符号＋原文，
 * 超长给 [PLAN_ITEM_COLLAPSED_MAX_LINES] 行＋「展开」看全）。点卡头那一行 ⇄ 翻转
 * （不新增「全部展开」按钮——派工单 §2.1）。
 *
 * UI 第二批 §2：条目不再 `maxLines = 1` 硬省略；展开状态**按条目记**（一条一个键），
 * 点某条不许把整卡其他条目也撑开。done/doing/todo 判据一个字没动。
 *
 * 第一百零六批（2026-10-21）：本组件从「内联在轮里」改成「计划面板里的组渲染」，
 * 因此多两个可选参数——
 * - [showHeader]：面板已经有组头（「本轮 · 10:24 · 3/6」），卡头不再重复画；
 * - [rowMinHeightDp]：面板要求条目行命中高度 ≥48dp（设计 §3.11 规格表「触控」行）。
 * 两个参数都有默认值 ⇒ 旧调用方（如果有）行为不变。
 *
 * 第一百四十批（2026-10-22）：再添两个可选参数 [highlightIndex] / [accentColor]——
 * 当前步骤只把符号与原文染成黛青，不加粗整行（11:50 设计约束）；默认 null ⇒ 旧行为不变。
 *
 * 第二百四十二批（2026-10-27 计划即契约）：展开态多契约那几行——目标、每步上限、白名单、
 * 只读区、预估问几次、停机条件（判据 1「卡上看得见」）。没声明契约 ⇒
 * [BaopuUiPlan.contract] 为 null，卡面与今天逐字相同。
 *
 * 第二百四十九批（2026-10-28 用户补一条）：**授权并进这张卡**，不再另起一张——
 * 上半是这份计划（目标/步骤/可碰＝「做什么、碰哪些对象」），下半接
 * [BaopuPlanGrantSection]（对象清单☑／动作类型☑／上限）＋一个「开工」按钮；
 * 对外这张卡就叫「开工卡」，内部仍是 [BaopuUiContract]（契约）＋授权快照
 * （[TaskGrantFacts]）。理由是她那句「用户不该为同一件事被打断两次」。
 *
 * 没给 [grant]（B 段接线之前的全部生产路径）⇒ 卡面与今天逐字相同。
 */
@Composable
internal fun BaopuPlanCard(
    plan: BaopuUiPlan,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    rowMinHeightDp: Int = 0,
    highlightIndex: Int? = null,
    accentColor: Color? = null,
    grant: TaskGrantFacts? = null,
    onStart: ((GrantSelection) -> Unit)? = null,
) {
    // 条目级展开：记条目下标（[plan.items] 的顺序由解析器给，同一次渲染内稳定）。
    var expandedItemIndexes by remember { mutableStateOf(setOf<Int>()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (showHeader) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (grant != null && onStart != null) {
                            // 249：这张卡带上授权段时，对外就叫「开工卡」。
                            stringResource(R.string.baopu_authz_card_title)
                        } else {
                            pluralStringResource(
                                R.plurals.baopu_plan_summary,
                                planDoneCount(plan.items),
                                planDoneCount(plan.items),
                                plan.items.size,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                // 第二百四十二批判据 1：目标那一行摆在步骤之前（先说这次要干成什么）。
                val contract = plan.contract
                if (contract != null) {
                    PlanContractLine(stringResource(R.string.baopu_plan_contract_goal, contract.goal))
                }
                plan.items.forEachIndexed { index, item ->
                    // 140：当前步骤只换前景色（黛青），不整行加粗——高亮靠颜色指认。
                    val isCurrent = highlightIndex == index
                    val foreground = if (isCurrent && accentColor != null) {
                        accentColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        Modifier
                            .padding(top = 4.dp)
                            .heightIn(min = rowMinHeightDp.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = planStatusSymbol(item.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = foreground,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = foreground,
                                maxLines =
                                    if (index in expandedItemIndexes) Int.MAX_VALUE
                                    else PLAN_ITEM_COLLAPSED_MAX_LINES,
                                overflow = if (index in expandedItemIndexes) {
                                    TextOverflow.Visible
                                } else {
                                    TextOverflow.Ellipsis
                                },
                            )
                            // 入口只在「可能装不下」的条目上；点开之后文案换成「收起」。
                            if (planItemNeedsExpand(item.text)) {
                                Text(
                                    text = stringResource(
                                        if (index in expandedItemIndexes) {
                                            R.string.common_collapse_arrow
                                        } else {
                                            R.string.common_expand_arrow
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickable {
                                            expandedItemIndexes = if (index in expandedItemIndexes) {
                                                expandedItemIndexes - index
                                            } else {
                                                expandedItemIndexes + index
                                            }
                                        },
                                )
                            }
                        }
                        // 判据 1「每步风险」：上限那一档摆在条目右边（有读数才摆）。
                        val riskTag = planStepRiskTag(contract, index)
                        if (riskTag != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = riskTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (contract != null) {
                    PlanContractLine(
                        planContractValue(contract.touches)
                            ?.let { stringResource(R.string.baopu_plan_contract_touches, it) },
                    )
                    PlanContractLine(
                        planContractValue(contract.readonly)
                            ?.let { stringResource(R.string.baopu_plan_contract_readonly, it) },
                    )
                    PlanContractLine(stringResource(R.string.baopu_plan_contract_asks, contract.expectedAsks))
                    PlanContractLine(
                        planContractValue(contract.stopIf)
                            ?.let { stringResource(R.string.baopu_plan_contract_stop_if, it) },
                    )
                    // 红线①在卡面上的那句话：批准计划≠授权改盘（不靠她记住规则，写在眼前）。
                    // 249 起授权段就在下面：勾完点「开工」＝连同授权一起批，不勾就不授权，
                    // 所以这句照样留着（它说的是「批准计划」这三个字本身不产生授权）。
                    PlanContractLine(stringResource(R.string.baopu_plan_contract_note))
                }
                // 249：授权并进同一张卡（不再另起一张，也不再有第二组批准按钮）。
                if (grant != null && onStart != null) {
                    BaopuPlanGrantSection(facts = grant, onStart = onStart)
                }
                // 第二百七十六批 Q2 补（2026-10-29 用户裁定「P1 补，只补最简版」）：
                // 授权段之后加**一行**「撤销本单」——撤销是安全出口，且这是 L26 的三元组第一次由人按下。
                // 只在「这张卡就是那张开着的单」时画：没有开着的单就没有可撤的东西；
                // 不新开页面、不做撤销列表／历史／批量（她：那是后面的事，别扩）。
                if (grant != null && onStart != null && TaskGrantBoard.openedGrantId() == grant.grantId) {
                    TaskGrantBoard.currentOffer()?.let { BaopuGrantRevokeRow(approved = it) }
                }
            }
        }
    }
}

/**
 * 契约在卡面上的一行（小字、同组前景色）。
 *
 * null ⇒ 整行不摆：没声明过的东西不占位（「只读区：」跟空串，读起来像数据丢了）。
 */
@Composable
private fun PlanContractLine(line: String?) {
    if (line == null) return
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = PLAN_ITEM_COLLAPSED_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}
